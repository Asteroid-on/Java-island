package com.island.weather;

import com.island.util.CityCoordinateTable;
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

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private WeatherListener listener;
    private volatile boolean running = false;

    private static final String API_KEY = "a20806587e5750d0a58e1b3af4305a34";
    private static final String API_URL = "https://apis.juhe.cn/simpleWeather/query";



    public interface WeatherListener {
        void onWeatherUpdated(WeatherInfo weather);
        void onWeatherError(String error);
    }

    public void setListener(WeatherListener listener) { this.listener = listener; }

    public void start() {
        if (running) return;
        running = true;
        fetchWeather();
        scheduler.scheduleAtFixedRate(this::fetchWeather, 60, 60, TimeUnit.MINUTES);
    }

    public void stop() {
        running = false;
        scheduler.shutdown();
    }

    private void fetchWeather() {
        try {
            String cityName = getCityName();
            if (cityName == null) { handleError("无法获取城市名"); return; }

            String params = String.format("key=%s&city=%s",
                    API_KEY, URLEncoder.encode(cityName, StandardCharsets.UTF_8));

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10)).build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_URL + "?" + params))
                    .timeout(Duration.ofSeconds(15)).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

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
        System.err.println("[聚合数据] 使用默认: " + CityCoordinateTable.DEFAULT_CITY);
        return CityCoordinateTable.DEFAULT_CITY;
    }



    // 城市坐标表已迁移至 CityCoordinateTable 工具类

    private void handleError(String error) {
        System.err.println("[聚合数据] " + error);
        if (listener != null) listener.onWeatherError(error);
    }
}
