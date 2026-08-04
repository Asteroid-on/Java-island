package com.island.weather;

/**
 * 天气信息模型
 */
public class WeatherInfo {
    private final String location;
    private final double temperature;
    private final String condition;
    /** WMO 天气码，-1 表示无天气码（如来自聚合数据等中文描述源） */
    private final int weatherCode;

    public WeatherInfo(String location, double temperature, String condition) {
        this(location, temperature, condition, -1);
    }

    public WeatherInfo(String location, double temperature, String condition, int weatherCode) {
        this.location = location;
        this.temperature = temperature;
        this.condition = condition;
        this.weatherCode = weatherCode;
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
     * 获取 WMO 天气码（仅 Open-Meteo 来源有效，聚合数据返回 -1）。
     */
    public int getWeatherCode() {
        return weatherCode;
    }

    public String getFormattedTemperature() {
        return Math.round(temperature) + "°";
    }
}