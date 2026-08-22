package com.island.weather;

import com.island.config.AppConfig;
import com.island.util.AmapGeocoder;
import com.island.util.CityCoordinateTable;
import com.island.util.AppLogger;
import com.island.util.WindowsLocationProvider;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 聚合数据天气服务 — 使用聚合数据API获取实时天气与多日预报，是应用唯一天气数据源。
 */
public class JuheWeatherService {

    private ScheduledExecutorService scheduler = createScheduler();
    private WeatherListener listener;
    private volatile boolean running = false;

    /**
     * API Key：优先级 系统属性 juhe.api.key → classpath config.properties → 空字符串。
     * 为空时直接触发 onWeatherError，UI 层显示兑底状态。
     */
    private static final String API_KEY = AppConfig.get("juhe.api.key", "");
    private static final String API_URL = "https://apis.juhe.cn/simpleWeather/query";

    /** 共享 HTTP 客户端（避免每次请求新建连接池/线程） */
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();



    public interface WeatherListener {
        void onWeatherUpdated(WeatherInfo weather);
        void onWeatherError(String error);
    }

    public void setListener(WeatherListener listener) { this.listener = listener; }

    public void start() {
        if (running) return;
        synchronized (this) {
            if (running) return;
            running = true;
            if (scheduler.isShutdown()) {
                scheduler = createScheduler();
            }
            // 首次拉取放入调度线程异步执行，绝不阻塞调用线程（如 EDT），
            // 否则定位 EXE + 网络请求最长会卡住 UI 十余秒，拖慢蓝牙/WiFi 通知
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
            Thread t = new Thread(r, "JuheWeatherService");
            t.setDaemon(true);
            return t;
        });
    }

    private void fetchWeather() {
        try {
            if (API_KEY.isEmpty()) {
                handleError("未配置聚合数据 API Key");
                return;
            }
            String cityName = getCityName();
            if (cityName == null) { handleError("无法获取城市名"); return; }

            String params = String.format("key=%s&city=%s",
                    API_KEY, URLEncoder.encode(cityName, StandardCharsets.UTF_8));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_URL + "?" + params))
                    .timeout(Duration.ofSeconds(15)).build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JSONObject json = new JSONObject(response.body());
                if (json.optInt("error_code", -1) != 0) {
                    handleError("聚合数据API错误: " + json.optString("reason", ""));
                    return;
                }
                JSONObject result = json.optJSONObject("result");
                if (result == null) return;
                JSONObject realtime = result.optJSONObject("realtime");
                if (realtime == null) return;

                double temperature = Double.parseDouble(realtime.optString("temperature", "0"));
                String condition = realtime.optString("info", "未知");

                // 聚合数据未提供体感温度；湿度可能带 "%" 后缀，解析失败保留 NaN
                double humidity = Double.NaN;
                String humidityStr = realtime.optString("humidity", "").replace("%", "").trim();
                if (!humidityStr.isEmpty()) {
                    try {
                        humidity = Double.parseDouble(humidityStr);
                    } catch (NumberFormatException ignored) {
                        // 湿度格式异常时保留 NaN，UI 层显示兜底文案
                    }
                }

                if (listener != null)
                    listener.onWeatherUpdated(new WeatherInfo(getDisplayCityName(), temperature, condition, -1, Double.NaN, humidity,
                            null, parseDailyForecasts(result)));
            } else {
                handleError("HTTP " + response.statusCode());
            }
        } catch (Exception e) {
            handleError(e.getMessage());
        }
    }

    private String getCityName() {
        WindowsLocationProvider.LocationResult result = WindowsLocationProvider.getLocation();
        if (result != null) {
            String name = CityCoordinateTable.findNearestCity(result.latitude, result.longitude);
            if (name != null) {
                System.out.printf("[聚合数据] 定位: %.6f,%.6f -> %s%n",
                        result.latitude, result.longitude, name);
                return name;
            }
        }
        AppLogger.warn("Weather", "聚合数据定位失败，使用默认: " + CityCoordinateTable.DEFAULT_CITY);
        return CityCoordinateTable.DEFAULT_CITY;
    }

    /** 显示地名：区级优先（高德逆地理编码返回"市 区"），失败回退市级（与聚合请求参数一致） */
    private String getDisplayCityName() {
        WindowsLocationProvider.LocationResult result = WindowsLocationProvider.getLocation();
        if (result != null) {
            String district = AmapGeocoder.lookupDistrict(result.latitude, result.longitude);
            if (district != null) {
                System.out.printf("[聚合数据] 显示地名: %s%n", district);
                return district;
            }
        }
        return getCityName();
    }

    /**
     * 解析多日预报（result.future）：温度形如 "17/30"（最低/最高），
     * 聚合数据无逐时预报，hourly 留空。解析失败项以 NaN 兜底。
     */
    private List<DailyForecast> parseDailyForecasts(JSONObject result) {
        List<DailyForecast> list = new ArrayList<>();
        JSONArray future = result.optJSONArray("future");
        if (future == null) {
            return list;
        }
        int n = Math.min(7, future.length());
        for (int i = 0; i < n; i++) {
            JSONObject day = future.optJSONObject(i);
            if (day == null) {
                continue;
            }
            double min = Double.NaN;
            double max = Double.NaN;
            // 温度区间形如 "17/30℃"：最高温带 ℃ 后缀，需剥离非数字字符再解析
            String[] parts = day.optString("temperature", "").split("/");
            if (parts.length == 2) {
                try {
                    min = Double.parseDouble(parts[0].replaceAll("[^0-9.\\-]", ""));
                    max = Double.parseDouble(parts[1].replaceAll("[^0-9.\\-]", ""));
                } catch (NumberFormatException ignored) {
                    // 温度区间格式异常时保留 NaN，UI 层显示兜底文案
                }
            }
            list.add(DailyForecast.ofCondition(day.optString("date", ""),
                    day.optString("weather", ""), min, max));
        }
        return list;
    }



    // 城市坐标表已迁移至 CityCoordinateTable 工具类

    private void handleError(String error) {
        AppLogger.warn("Weather", "聚合数据: " + error);
        if (listener != null) listener.onWeatherError(error);
    }
}
