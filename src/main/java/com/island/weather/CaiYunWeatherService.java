package com.island.weather;

import com.island.config.AppConfig;
import com.island.util.AmapGeocoder;
import com.island.util.AppLogger;
import com.island.util.CityCoordinateTable;
import com.island.util.WindowsLocationProvider;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.RejectedExecutionException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 彩云天气服务 — 使用彩云天气 API（v2.6）获取实时天气、48 小时逐时预报与 3 天每日预报。
 * 逐时预报点亮扩展岛天气详情的 24 小时逐时时间轴；实时与每日预报作为聚合数据的兜底源。
 * 请求 URL 形如 https://api.caiyunapp.com/v2.6/{token}/{lng},{lat}/weather?dailysteps=3&hourlysteps=48
 * （注意坐标顺序为 经度,纬度）。
 */
public class CaiYunWeatherService {

    private ScheduledExecutorService scheduler = createScheduler();
    private WeatherListener listener;
    private volatile boolean running = false;

    /**
     * API Token：优先级 系统属性 caiyun.api.token → classpath config.properties → 空字符串。
     * 为空时直接触发 onWeatherError，逐时预报由 UI 显示"暂无逐时预报"兜底。
     */
    private static final String API_TOKEN = AppConfig.get("caiyun.api.token", "");
    private static final String API_URL_TEMPLATE =
            "https://api.caiyunapp.com/v2.6/%s/%.4f,%.4f/weather?dailysteps=3&hourlysteps=48";

    /** 共享 HTTP 客户端（避免每次请求新建连接池/线程） */
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    public interface WeatherListener {
        void onWeatherUpdated(WeatherInfo weather);
        void onWeatherError(String error);
    }

    public void setListener(WeatherListener listener) {
        this.listener = listener;
    }

    public void start() {
        if (running) return;
        synchronized (this) {
            if (running) return;
            running = true;
            if (scheduler.isShutdown()) {
                scheduler = createScheduler();
            }
            // 首次拉取放入调度线程异步执行，绝不阻塞调用线程（如 EDT）
            scheduler.schedule(this::fetchWeather, 0, TimeUnit.SECONDS);
            scheduler.scheduleAtFixedRate(this::fetchWeather, 60, 60, TimeUnit.MINUTES);
        }
    }

    public void stop() {
        running = false;
        scheduler.shutdown();
    }

    /**
     * 手动触发一次立即刷新：与定时刷新共用同一单线程调度器排队执行，
     * 不会产生并发请求；完成后在调度线程回调 onComplete（未运行时直接回调）。
     */
    public void refreshNow(Runnable onComplete) {
        if (!running) {
            if (onComplete != null) onComplete.run();
            return;
        }
        try {
            scheduler.schedule(() -> {
                try {
                    fetchWeather();
                } finally {
                    if (onComplete != null) onComplete.run();
                }
            }, 0, TimeUnit.SECONDS);
        } catch (RejectedExecutionException e) {
            // 与 stop() 竞态：running 检查后调度器刚被关闭，任务被拒绝时直接回调避免刷新按钮永久禁用
            if (onComplete != null) onComplete.run();
        }
    }

    /** 守护线程调度器：应用退出时不阻塞 JVM，stop() 后可重建复用。 */
    private static ScheduledExecutorService createScheduler() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "CaiYunWeatherService");
            t.setDaemon(true);
            return t;
        });
    }

    private void fetchWeather() {
        try {
            if (API_TOKEN.isEmpty()) {
                handleError("未配置彩云天气 API Token");
                return;
            }
            // Windows 原生定位失败时使用默认坐标（北京）
            double lng = CityCoordinateTable.DEFAULT_LON;
            double lat = CityCoordinateTable.DEFAULT_LAT;
            WindowsLocationProvider.LocationResult result = WindowsLocationProvider.getLocation();
            if (result != null) {
                lng = result.longitude;
                lat = result.latitude;
            }

            String apiUrl = String.format(API_URL_TEMPLATE, API_TOKEN, lng, lat);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "Java-island/1.0")
                    .build();

            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                parseWeatherData(response.body(), lng, lat);
            } else {
                handleError("彩云天气 HTTP " + response.statusCode());
            }
        } catch (Exception e) {
            handleError("彩云天气请求异常: " + e.getMessage());
        }
    }

    /**
     * 解析彩云天气响应：
     * - realtime：实时温度/天气现象/体感/湿度（湿度为 0~1 比例，转百分比）；
     * - hourly：仅调用未来两天的逐时预报（hourlysteps=48，48 小时），剔除早于当前小时
     *   的历史项后按时间序排列（首项即当前时段"现在"，末项为后天当前时刻前一小时）；
     * - daily：3 天每日预报（date/最高温/最低温 + 天气现象），并提取今日日出/日落时间
     *   （astro 数组按日期匹配今天，供逐时预报区域显示）。
     */
    private void parseWeatherData(String responseBody, double lng, double lat) {
        try {
            JSONObject jsonResponse = new JSONObject(responseBody);
            if (!"ok".equals(jsonResponse.optString("status", ""))) {
                handleError("彩云天气API错误: " + jsonResponse.optString("error", ""));
                return;
            }
            JSONObject result = jsonResponse.optJSONObject("result");
            if (result == null) return;

            double temperature = 0.0;
            String condition = "未知";
            double feelsLike = Double.NaN;
            double humidity = Double.NaN;
            int airQualityIndex = -1;
            String airQualityDesc = null;
            double uvIndex = Double.NaN;
            String uvDesc = null;
            List<HourlyForecast> hourlyList = new ArrayList<>();
            List<DailyForecast> dailyList = new ArrayList<>();

            // ── 实时天气 ──
            JSONObject realtime = result.optJSONObject("realtime");
            if (realtime != null) {
                temperature = realtime.optDouble("temperature", 0.0);
                condition = skyconToDescription(realtime.optString("skycon", ""));
                double appTemp = realtime.optDouble("apparent_temperature", Double.NaN);
                if (!Double.isNaN(appTemp)) {
                    feelsLike = appTemp;
                }
                double h = realtime.optDouble("humidity", Double.NaN);
                if (!Double.isNaN(h)) {
                    humidity = h * 100;
                }
                // 空气质量（中国 AQI，0-500）
                JSONObject airQuality = realtime.optJSONObject("air_quality");
                if (airQuality != null) {
                    JSONObject aqi = airQuality.optJSONObject("aqi");
                    if (aqi != null) {
                        airQualityIndex = aqi.optInt("chn", -1);
                        if (airQualityIndex >= 0) {
                            airQualityDesc = aqiLevelOf(airQualityIndex);
                        }
                    }
                }
                // 紫外线指数与强度描述
                JSONObject lifeIndex = realtime.optJSONObject("life_index");
                if (lifeIndex != null) {
                    JSONObject uv = lifeIndex.optJSONObject("ultraviolet");
                    if (uv != null) {
                        uvIndex = uv.optDouble("index", Double.NaN);
                        String desc = uv.optString("desc", "");
                        if (!desc.isEmpty()) {
                            uvDesc = desc;
                        }
                    }
                }
            }

            // ── 48 小时逐时预报（未来两天播报） ──
            JSONObject hourly = result.optJSONObject("hourly");
            if (hourly != null) {
                JSONArray temps = hourly.optJSONArray("temperature");
                JSONArray skycons = hourly.optJSONArray("skycon");
                if (temps != null && temps.length() > 0) {
                    String currentHourStr = LocalDateTime.now()
                            .withMinute(0).withSecond(0).withNano(0)
                            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));
                    for (int i = 0; i < temps.length() && hourlyList.size() < 48; i++) {
                        JSONObject item = temps.optJSONObject(i);
                        if (item == null) {
                            continue;
                        }
                        String datetime = item.optString("datetime", "");
                        String hourStr = datetime.length() >= 16 ? datetime.substring(0, 16) : "";
                        if (hourStr.compareTo(currentHourStr) < 0) {
                            continue; // 早于当前小时的历史时段一律剔除，不进入时间轴
                        }
                        double t = item.optDouble("value", Double.NaN);
                        if (Double.isNaN(t)) {
                            continue;
                        }
                        String label = datetime.length() >= 16 ? datetime.substring(11, 16) : "--:--";
                        hourlyList.add(new HourlyForecast(label, t, -1,
                                skyconToDescription(skyValueAt(skycons, i))));
                    }
                }
            }

            // ── 3 天每日预报 ──
            String sunrise = null;
            String sunset = null;
            List<SunEvent> sunEvents = new ArrayList<>();
            JSONObject daily = result.optJSONObject("daily");
            if (daily != null) {
                JSONArray dTemps = daily.optJSONArray("temperature");
                JSONArray dSkycons = daily.optJSONArray("skycon");
                if (dTemps != null) {
                    for (int i = 0; i < dTemps.length(); i++) {
                        JSONObject day = dTemps.optJSONObject(i);
                        if (day == null) {
                            continue;
                        }
                        dailyList.add(DailyForecast.ofCondition(
                                day.optString("date", ""),
                                skyconToDescription(skyValueAt(dSkycons, i)),
                                day.optDouble("min", Double.NaN),
                                day.optDouble("max", Double.NaN)));
                    }
                }
                // 日出/日落（astro 数组）：date 形如 "2026-08-22T00:00+08:00"，
                // 取前 10 字符与今天比较得出天数偏移（0=今天，1=明天，2=后天），
                // 逐时时间轴 48 小时内未来两天的日出/日落均可标记。
                JSONArray astros = daily.optJSONArray("astro");
                if (astros != null) {
                    LocalDate today = LocalDate.now();
                    for (int i = 0; i < astros.length(); i++) {
                        JSONObject astro = astros.optJSONObject(i);
                        if (astro == null) {
                            continue;
                        }
                        String date = astro.optString("date", "");
                        String dateOnly = date.length() >= 10 ? date.substring(0, 10) : date;
                        int dayOffset;
                        try {
                            dayOffset = (int) ChronoUnit.DAYS.between(today, LocalDate.parse(dateOnly));
                        } catch (Exception e) {
                            continue; // 日期格式异常，跳过该项
                        }
                        if (dayOffset < 0 || dayOffset > 2) {
                            continue; // 仅收集未来两天内的日出/日落
                        }
                        JSONObject sr = astro.optJSONObject("sunrise");
                        if (sr != null) {
                            String t = sr.optString("time", "");
                            if (!t.isEmpty()) {
                                if (dayOffset == 0) {
                                    sunrise = t;
                                }
                                sunEvents.add(new SunEvent(dayOffset, true, t));
                            }
                        }
                        JSONObject ss = astro.optJSONObject("sunset");
                        if (ss != null) {
                            String t = ss.optString("time", "");
                            if (!t.isEmpty()) {
                                if (dayOffset == 0) {
                                    sunset = t;
                                }
                                sunEvents.add(new SunEvent(dayOffset, false, t));
                            }
                        }
                    }
                }
            }

            // 城市名：区级优先（高德逆地理编码），失败回退最近市级，再失败以坐标串兑底
            String placeName = AmapGeocoder.lookupDistrict(lat, lng);
            if (placeName == null) {
                placeName = CityCoordinateTable.findNearestCity(lat, lng);
            }
            if (placeName == null) {
                placeName = String.format("%.4f,%.4f", lat, lng);
            }

            WeatherInfo weather = new WeatherInfo(placeName, temperature, condition, -1,
                    feelsLike, humidity, hourlyList, dailyList, sunrise, sunset, sunEvents,
                    airQualityIndex, airQualityDesc, uvIndex, uvDesc);

            if (listener != null) {
                listener.onWeatherUpdated(weather);
            }
        } catch (Exception e) {
            handleError("解析彩云天气数据失败: " + e.getMessage());
        }
    }

    /** 从天气现象数组中取第 i 项字符串值（兼容 {datetime,value} 对象数组与纯字符串数组） */
    private static String skyValueAt(JSONArray arr, int i) {
        if (arr == null || i >= arr.length()) {
            return "";
        }
        JSONObject o = arr.optJSONObject(i);
        if (o != null) {
            return o.optString("value", "");
        }
        return arr.optString(i, "");
    }

    /** 中国 AQI 数值 → 空气质量等级描述（国标 HJ633-2012） */
    private static String aqiLevelOf(int aqi) {
        if (aqi <= 50) return "优";
        if (aqi <= 100) return "良";
        if (aqi <= 150) return "轻度污染";
        if (aqi <= 200) return "中度污染";
        if (aqi <= 300) return "重度污染";
        return "严重污染";
    }

    /** 彩云 skycon 天气现象代码 → 中文描述（供 WeatherIconMapper 映射图标） */
    private static String skyconToDescription(String skycon) {
        if (skycon == null || skycon.isEmpty()) {
            return "未知";
        }
        switch (skycon) {
            case "CLEAR_DAY":
            case "CLEAR_NIGHT": return "晴";
            case "PARTLY_CLOUDY_DAY":
            case "PARTLY_CLOUDY_NIGHT": return "多云";
            case "CLOUDY": return "阴";
            case "LIGHT_HAZE":
            case "MODERATE_HAZE":
            case "HEAVY_HAZE": return "霾";
            case "LIGHT_RAIN": return "小雨";
            case "MODERATE_RAIN": return "中雨";
            case "HEAVY_RAIN": return "大雨";
            case "STORM_RAIN": return "暴雨";
            case "FOG": return "雾";
            case "LIGHT_SNOW": return "小雪";
            case "MODERATE_SNOW": return "中雪";
            case "HEAVY_SNOW": return "大雪";
            case "STORM_SNOW": return "暴雪";
            case "DUST": return "扬沙";
            case "SAND": return "沙尘";
            case "WIND": return "大风";
            case "RAIN": return "雨";
            case "SNOW": return "雪";
            case "SLEET": return "冻雨";
            default: return "未知";
        }
    }

    private void handleError(String error) {
        AppLogger.warn("Weather", "彩云天气: " + error);
        if (listener != null) {
            listener.onWeatherError(error);
        }
    }
}
