package com.island.util;

import com.island.config.AppConfig;
import org.json.JSONObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 高德地图逆地理编码：坐标 → "市 区"级地名（如"巴彦淖尔市 临河区"）。
 *
 * <p>Key 未配置、请求失败或无区级结果时返回 null（调用方回退市级静态表
 * {@link CityCoordinateTable}）。以量化坐标（4 位小数，约 11 米）为键缓存
 * 区级地名，约 10 分钟过期：天气服务的每小时定时刷新与手动刷新在同一位置时
 * 命中缓存，避免重复调用高德接口；缓存条目上限 {@link #MAX_CACHE_SIZE}，
 * 超限时先清过期条目、仍超限则整体清空，线程安全且内存占用可控。</p>
 */
public final class AmapGeocoder {

    /** 高德 Web 服务 Key：优先级 系统属性 amap.api.key → classpath config.properties → 空（不启用反查） */
    private static final String API_KEY = AppConfig.get("amap.api.key", "");
    private static final String URL_TEMPLATE =
            "https://restapi.amap.com/v3/geocode/regeo?key=%s&location=%.6f,%.6f&extensions=base";

    /** 共享 HTTP 客户端（避免每次请求新建连接池/线程） */
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    /** 缓存过期时长：与定位结果缓存（10 分钟）对齐 */
    private static final long CACHE_TTL_MS = 10 * 60 * 1000;
    /** 缓存条目上限：覆盖用户近期停留过的少数位置，内存占用可控 */
    private static final int MAX_CACHE_SIZE = 8;

    /** 单个缓存条目：市+区地名 + 过期时刻（毫秒时间戳） */
    private static final class CacheEntry {
        final String placeName;
        final long expireAt;

        CacheEntry(String placeName, long expireAt) {
            this.placeName = placeName;
            this.expireAt = expireAt;
        }
    }

    /** 量化坐标键 → 缓存条目；ConcurrentHashMap 保证多线程读写安全 */
    private static final Map<Long, CacheEntry> CACHE = new ConcurrentHashMap<>();

    private AmapGeocoder() {
        // 工具类，禁止实例化
    }

    /**
     * 反查地名（"市 区"，如"巴彦淖尔市 临河区"）；不可用时返回 null（调用方回退市级静态表）。
     * 类级锁串行化请求：同轮刷新两个天气服务并发首次查询时只发一次请求，
     * 其余线程等待后命中缓存（调用频率每小时级，锁内阻塞可忽略）。
     * 含同步网络请求，调用方应在工作线程执行（两个天气服务的调度线程）。
     */
    public static synchronized String lookupDistrict(double lat, double lng) {
        if (API_KEY.isEmpty()) {
            return null;
        }
        long key = coordKey(lat, lng);
        long now = System.currentTimeMillis();
        CacheEntry hit = CACHE.get(key);
        if (hit != null && now < hit.expireAt) {
            return hit.placeName; // 命中缓存：同一位置定时/手动刷新共享结果，不再请求高德
        }
        // 未命中或已过期：重新请求；请求仍失败则静默返回 null（调用方回退市级）
        String placeName = fetchPlaceName(lat, lng);
        if (placeName == null) {
            return null;
        }
        putCached(key, placeName, now);
        return placeName;
    }

    /** 量化坐标（4 位小数，约 11 米）组合为 long 键：同一位置刷新命中同一键，零字符串分配 */
    private static long coordKey(double lat, double lng) {
        long la = Math.round(lat * 1e4);
        long lo = Math.round(lng * 1e4);
        return (la << 32) ^ lo;
    }

    /** 写入缓存：超限时先清过期条目，仍超限则整体清空（极端多位置场景，内存占用优先） */
    private static void putCached(long key, String placeName, long now) {
        if (CACHE.size() >= MAX_CACHE_SIZE && !CACHE.containsKey(key)) {
            CACHE.entrySet().removeIf(e -> now >= e.getValue().expireAt);
            if (CACHE.size() >= MAX_CACHE_SIZE) {
                CACHE.clear();
            }
        }
        CACHE.put(key, new CacheEntry(placeName, now + CACHE_TTL_MS));
    }

    /** 请求高德逆地理编码并解析地名（"市 区"）；任何失败返回 null */
    private static String fetchPlaceName(double lat, double lng) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(String.format(URL_TEMPLATE, API_KEY, lng, lat)))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "Java-island/1.0")
                    .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                AppLogger.warn("Amap", "逆地理编码 HTTP " + response.statusCode());
                return null;
            }
            JSONObject json = new JSONObject(response.body());
            if (!"1".equals(json.optString("status", ""))) {
                AppLogger.warn("Amap", "逆地理编码错误: " + json.optString("info", ""));
                return null;
            }
            JSONObject regeo = json.optJSONObject("regeocode");
            JSONObject ac = regeo != null ? regeo.optJSONObject("addressComponent") : null;
            if (ac == null) {
                return null;
            }
            // 市名与区名可能返回空串或空数组 "[]"；两者均无时回退市级静态表
            String city = ac.optString("city", "");
            String district = ac.optString("district", "");
            boolean cityOk = !city.isEmpty() && !"[]".equals(city);
            boolean districtOk = !district.isEmpty() && !"[]".equals(district);
            if (!cityOk && !districtOk) {
                return null;
            }
            String placeName;
            if (!cityOk) {
                placeName = district; // 仅区级（如省直辖县）
            } else if (!districtOk || district.equals(city)) {
                placeName = city; // 无区级或市/区同名（如东莞），仅显示市名
            } else {
                placeName = city + " " + district; // 常规："巴彦淖尔市 临河区"
            }
            AppLogger.info("Amap", String.format("逆地理编码: %.6f,%.6f -> %s", lat, lng, placeName));
            return placeName;
        } catch (Exception e) {
            AppLogger.warn("Amap", "逆地理编码失败: " + e.getMessage());
            return null;
        }
    }
}
