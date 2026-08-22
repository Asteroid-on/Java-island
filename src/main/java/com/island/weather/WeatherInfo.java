package com.island.weather;

import java.util.Collections;
import java.util.List;

/**
 * 天气信息模型
 */
public class WeatherInfo {
    private final String location;
    private final double temperature;
    private final String condition;
    /** WMO 天气码，-1 表示无天气码（如来自聚合数据等中文描述源） */
    private final int weatherCode;
    /** 体感温度（摄氏度），Double.NaN 表示数据源未提供 */
    private final double feelsLike;
    /** 相对湿度（百分比），Double.NaN 表示数据源未提供 */
    private final double humidity;
    /** 未来 24 小时逐时预报（数据源未提供时为空列表） */
    private final List<HourlyForecast> hourlyForecasts;
    /** 7 天内每日预报（数据源未提供时为空列表） */
    private final List<DailyForecast> dailyForecasts;
    /** 今日日出时间（如 "05:29"，数据源未提供时为 null） */
    private final String sunrise;
    /** 今日日落时间（如 "19:01"，数据源未提供时为 null） */
    private final String sunset;
    /** 未来数天日出/日落事件（逐时时间轴标记用，数据源未提供时为空列表） */
    private final List<SunEvent> sunEvents;
    /** 空气质量指数（中国 AQI，0-500；-1 表示数据源未提供） */
    private final int airQualityIndex;
    /** 空气质量等级描述（如 "优/良/轻度污染"，数据源未提供时为 null） */
    private final String airQualityDesc;
    /** 紫外线指数（Double.NaN 表示数据源未提供） */
    private final double uvIndex;
    /** 紫外线强度描述（如 "弱/中等/很强"，数据源未提供时为 null） */
    private final String uvDesc;

    public WeatherInfo(String location, double temperature, String condition) {
        this(location, temperature, condition, -1);
    }

    public WeatherInfo(String location, double temperature, String condition, int weatherCode) {
        this(location, temperature, condition, weatherCode, Double.NaN, Double.NaN);
    }

    public WeatherInfo(String location, double temperature, String condition,
                       int weatherCode, double feelsLike, double humidity) {
        this(location, temperature, condition, weatherCode, feelsLike, humidity,
                Collections.emptyList(), Collections.emptyList());
    }

    public WeatherInfo(String location, double temperature, String condition,
                       int weatherCode, double feelsLike, double humidity,
                       List<HourlyForecast> hourlyForecasts, List<DailyForecast> dailyForecasts) {
        this(location, temperature, condition, weatherCode, feelsLike, humidity,
                hourlyForecasts, dailyForecasts, null, null);
    }

    public WeatherInfo(String location, double temperature, String condition,
                       int weatherCode, double feelsLike, double humidity,
                       List<HourlyForecast> hourlyForecasts, List<DailyForecast> dailyForecasts,
                       String sunrise, String sunset) {
        this(location, temperature, condition, weatherCode, feelsLike, humidity,
                hourlyForecasts, dailyForecasts, sunrise, sunset, Collections.emptyList(),
                -1, null, Double.NaN, null);
    }

    public WeatherInfo(String location, double temperature, String condition,
                       int weatherCode, double feelsLike, double humidity,
                       List<HourlyForecast> hourlyForecasts, List<DailyForecast> dailyForecasts,
                       String sunrise, String sunset, List<SunEvent> sunEvents,
                       int airQualityIndex, String airQualityDesc,
                       double uvIndex, String uvDesc) {
        this.location = location;
        this.temperature = temperature;
        this.condition = condition;
        this.weatherCode = weatherCode;
        this.feelsLike = feelsLike;
        this.humidity = humidity;
        this.hourlyForecasts = hourlyForecasts != null ? hourlyForecasts : Collections.emptyList();
        this.dailyForecasts = dailyForecasts != null ? dailyForecasts : Collections.emptyList();
        this.sunrise = sunrise;
        this.sunset = sunset;
        this.sunEvents = sunEvents != null ? sunEvents : Collections.emptyList();
        this.airQualityIndex = airQualityIndex;
        this.airQualityDesc = airQualityDesc;
        this.uvIndex = uvIndex;
        this.uvDesc = uvDesc;
    }

    public String getLocation() {
        return location;
    }

    public double getTemperature() {
        return temperature;
    }

    public String getCondition() {
        return condition;
    }

    /**
     * 获取 WMO 天气码（当前天气源均不提供，恒返回 -1，UI 回退到中文描述图标）。
     */
    public int getWeatherCode() {
        return weatherCode;
    }

    public double getFeelsLike() {
        return feelsLike;
    }

    public double getHumidity() {
        return humidity;
    }

    /** 数据源是否提供了体感温度 */
    public boolean hasFeelsLike() {
        return !Double.isNaN(feelsLike);
    }

    /** 数据源是否提供了相对湿度 */
    public boolean hasHumidity() {
        return !Double.isNaN(humidity);
    }

    public String getFormattedTemperature() {
        return Math.round(temperature) + "°";
    }

    /** 未来 24 小时逐时预报（不会为 null） */
    public List<HourlyForecast> getHourlyForecasts() {
        return hourlyForecasts;
    }

    /** 7 天内每日预报（不会为 null） */
    public List<DailyForecast> getDailyForecasts() {
        return dailyForecasts;
    }

    /** 今日日出时间（数据源未提供时为 null） */
    public String getSunrise() {
        return sunrise;
    }

    /** 今日日落时间（数据源未提供时为 null） */
    public String getSunset() {
        return sunset;
    }

    /** 数据源是否提供了今日日出/日落时间 */
    public boolean hasSunriseSunset() {
        return (sunrise != null && !sunrise.isEmpty()) || (sunset != null && !sunset.isEmpty());
    }

    /** 未来数天日出/日落事件列表（不会为 null） */
    public List<SunEvent> getSunEvents() {
        return sunEvents;
    }

    /** 空气质量指数（中国 AQI，数据源未提供时为 -1） */
    public int getAirQualityIndex() {
        return airQualityIndex;
    }

    /** 空气质量等级描述（数据源未提供时为 null） */
    public String getAirQualityDesc() {
        return airQualityDesc;
    }

    /** 数据源是否提供了空气质量 */
    public boolean hasAirQuality() {
        return airQualityIndex >= 0;
    }

    /** 紫外线指数（数据源未提供时为 NaN） */
    public double getUvIndex() {
        return uvIndex;
    }

    /** 紫外线强度描述（数据源未提供时为 null） */
    public String getUvDesc() {
        return uvDesc;
    }

    /** 数据源是否提供了紫外线指数 */
    public boolean hasUltraviolet() {
        return !Double.isNaN(uvIndex);
    }
}