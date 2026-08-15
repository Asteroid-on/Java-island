package com.island.weather;

import com.island.config.AppConfig;
import com.island.util.CityCoordinateTable;
import com.island.util.AppLogger;
import com.island.util.WindowsLocationProvider;
import org.json.JSONObject;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 聚合数据天气服务 — 使用聚合数据API获取实时天气。
 */
public class JuheWeatherService {

    private ScheduledExecutorService scheduler = createScheduler();
    private WeatherListener listener;
    private volatile boolean running = false;

    /**
     * API Key：优先级 系统属性 juhe.api.key → classpath config.properties → 空字符串。
     * 为空时直接触发 onWeatherError，由 HybridWeatherService 降级到 Open-Meteo。
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

                if (listener != null)
                    listener.onWeatherUpdated(new WeatherInfo(cityName, temperature, condition));
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



    // 城市坐标表已迁移至 CityCoordinateTable 工具类

    private void handleError(String error) {
        AppLogger.warn("Weather", "聚合数据: " + error);
        if (listener != null) listener.onWeatherError(error);
    }
}
