package com.island.weather;

import com.island.util.CityCoordinateTable;
import com.island.util.AppLogger;
import com.island.util.WindowsLocationProvider;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Open-Meteo天气服务 - 使用Open-Meteo API获取当前位置天气
 */
public class OpenMeteoWeatherService {
    private ScheduledExecutorService scheduler = createScheduler();
    private WeatherListener listener;
    private volatile boolean running = false;
    
    // Open-Meteo API基础URL
    private static final String OPEN_METEO_API_URL = "https://api.open-meteo.com/v1/forecast";

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
            // 首次拉取改为异步执行，避免阻塞调用线程（如 EDT）导致通知延迟
            scheduler.schedule(this::fetchWeather, 0, TimeUnit.SECONDS);
            // 每小时更新一次天气
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
            Thread t = new Thread(r, "OpenMeteoWeatherService");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 获取天气数据
     */
    private void fetchWeather() {
        try {
            // 获取当前位置坐标
            Location location = getCurrentLocation();
            if (location == null) {
                handleError("无法获取当前位置");
                return;
            }

            // Open-Meteo: CMA GRAPES + 陆地网格优选 + 历史数据校正
            // hourly数据比current_weather更新更频繁、温度更准确
            String apiUrl = String.format(
                "%s?latitude=%.6f&longitude=%.6f" +
                "&current_weather=true" +
                "&hourly=temperature_2m,weathercode,apparent_temperature,relativehumidity_2m" +
                "&forecast_hours=6" +
                "&past_days=1" +
                "&temperature_unit=celsius" +
                "&timeformat=iso8601" +
                "&models=cma_grapes_global" +
                "&cell_selection=land" +
                "&timezone=Asia/Shanghai",
                OPEN_METEO_API_URL, location.latitude, location.longitude);

            HttpClient client = HTTP;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "Java-island/1.0")
                    .build();

            HttpResponse<String> response = client.send(request, 
                    HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                parseWeatherData(response.body(), location.city);
            } else {
                handleError("获取天气数据失败，HTTP状态码: " + response.statusCode());
            }
        } catch (Exception e) {
            handleError("获取天气数据异常: " + e.getMessage());
            // 网络错误时使用模拟数据
            simulateWeatherData();
        }
    }

    // 城市坐标表已迁移至 CityCoordinateTable 工具类

    /**
     * 获取当前地理位置（Windows 系统级 WiFi+GPS+基站三角定位，精度 5-30m）
     * 降级策略：Windows原生定位 → 北京兜底
     */
    private Location getCurrentLocation() {
        WindowsLocationProvider.LocationResult result = WindowsLocationProvider.getLocation();
        if (result != null) {
            String placeName = reverseGeocode(result.latitude, result.longitude);
            if (placeName == null) {
                placeName = CityCoordinateTable.findNearestCity(result.latitude, result.longitude);
            }
            if (placeName == null) {
                placeName = String.format("%.4f,%.4f", result.latitude, result.longitude);
            }
            System.out.printf("[Windows定位] 成功: %s (精度 %.0fm)%n", placeName, result.accuracy);
            return new Location(result.latitude, result.longitude, placeName);
        }

        AppLogger.warn("Weather", "Windows 定位失败，使用默认位置：" + CityCoordinateTable.DEFAULT_CITY);
        return new Location(CityCoordinateTable.DEFAULT_LAT, CityCoordinateTable.DEFAULT_LON, CityCoordinateTable.DEFAULT_CITY);
    }






    /**
     * 逆地理编码：BigDataCloud API（免费免Key，国内可访问）
     * 返回格式如"临河区 巴彦淖尔市"
     */
    private String reverseGeocode(double lat, double lon) {
        try {
            String apiUrl = String.format(
                "https://api.bigdatacloud.net/data/reverse-geocode-client?latitude=%.6f&longitude=%.6f&localityLanguage=zh",
                lat, lon);

            HttpClient client = HTTP;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", "Java-island/1.0")
                    .build();

            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JSONObject json = new JSONObject(response.body());
                String locality = json.optString("locality", "");  // 区/县
                String city = json.optString("city", "");          // 市

                StringBuilder name = new StringBuilder();
                if (!locality.isEmpty()) {
                    name.append(locality);
                }
                if (!city.isEmpty()) {
                    if (name.length() > 0) name.append(" ");
                    name.append(city);
                }
                if (name.length() > 0) {
                    System.out.println("[反向地理编码] 解析地名: " + name);
                    return name.toString();
                }
            }
        } catch (Exception e) {
            AppLogger.warn("Weather", "反向地理编码失败: " + e.getMessage());
        }
        return null;
    }

    /**
     * 解析天气数据
     * 优先使用hourly数据（更新更频繁、更准确），current_weather作为fallback
     */
    private void parseWeatherData(String responseBody, String city) {
        try {
            JSONObject jsonResponse = new JSONObject(responseBody);
            
            double temperature = 0.0;
            int weathercode = 0;
            boolean usedHourly = false;

            // ── 优先尝试从hourly数据中获取当前小时的温度和天气码 ──
            JSONObject hourly = jsonResponse.optJSONObject("hourly");
            if (hourly != null) {
                JSONArray times = hourly.optJSONArray("time");
                JSONArray temps = hourly.optJSONArray("temperature_2m");
                JSONArray codes = hourly.optJSONArray("weathercode");
                
                if (times != null && temps != null && times.length() > 0 && temps.length() > 0) {
                    // 找到当前小时对应的索引
                    String currentHourStr = LocalDateTime.now()
                            .withMinute(0).withSecond(0).withNano(0)
                            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));
                    
                    for (int i = 0; i < times.length(); i++) {
                        String timeStr = times.optString(i, "");
                        if (timeStr.startsWith(currentHourStr)) {
                            temperature = temps.optDouble(i, 0.0);
                            if (codes != null && i < codes.length()) {
                                weathercode = codes.optInt(i, 0);
                            }
                            usedHourly = true;
                            break;
                        }
                    }
                    
                    // 如果精确匹配当前小时失败，使用第一个条目（最近的可用数据）
                    if (!usedHourly) {
                        temperature = temps.optDouble(0, 0.0);
                        if (codes != null) {
                            weathercode = codes.optInt(0, 0);
                        }
                        usedHourly = true;
                    }
                }
            }

            // ── fallback：使用current_weather数据 ──
            if (!usedHourly) {
                JSONObject currentWeather = jsonResponse.optJSONObject("current_weather");
                if (currentWeather == null) {
                    simulateWeatherData();
                    return;
                }
                temperature = currentWeather.optDouble("temperature", 0.0);
                weathercode = currentWeather.optInt("weathercode", 0);
            }

            // 根据天气代码转换为天气描述
            String condition = weatherCodeToDescription(weathercode);

            WeatherInfo weather = new WeatherInfo(city, temperature, condition, weathercode);
            
            if (listener != null) {
                listener.onWeatherUpdated(weather);
            }
        } catch (Exception e) {
            handleError("解析天气数据失败: " + e.getMessage());
        }
    }

    /**
     * 天气代码转描述
     */
    private String weatherCodeToDescription(int code) {
        switch (code) {
            case 0: return "晴";
            case 1: case 2: case 3: return "少云";
            case 45: case 48: return "雾";
            case 51: case 53: case 55: return "小雨";
            case 56: case 57: return "冻雨";
            case 61: case 63: case 65: return "雨";
            case 66: case 67: return "冻雨";
            case 71: case 73: case 75: return "雪";
            case 77: return "霰";
            case 80: case 81: case 82: return "阵雨";
            case 85: case 86: return "阵雪";
            case 95: case 96: case 99: return "雷暴";
            default: return "多云";
        }
    }

    /**
     * 模拟天气数据
     */
    private void simulateWeatherData() {
        try {
            // 生成模拟的天气数据
            String[] conditions = {"晴", "多云", "阴", "小雨", "阵雨", "雷阵雨", "大雨", "雾", "霾"};
            String condition = conditions[(int) (Math.random() * conditions.length)];
            double temperature = 15 + Math.random() * 20; // 15-35度之间
            
            WeatherInfo weather = new WeatherInfo("本地", temperature, condition);
            
            if (listener != null) {
                listener.onWeatherUpdated(weather);
            }
        } catch (Exception e) {
            handleError("获取天气数据失败: " + e.getMessage());
        }
    }

    private void handleError(String error) {
        AppLogger.warn("Weather", "Open-Meteo: " + error);
        if (listener != null) {
            listener.onWeatherError(error);
        }
    }

    /**
     * 地理位置信息类
     */
    private static class Location {
        final double latitude;
        final double longitude;
        final String city;

        Location(double latitude, double longitude, String city) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.city = city;
        }
    }
}