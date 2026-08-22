package com.island.weather;

/**
 * 逐时天气预报模型（24 小时预报行单项）。
 */
public class HourlyForecast {

    /** 时间标签，如 "03:00" */
    private final String timeLabel;
    private final double temperature;
    /** WMO 天气码，-1 表示无天气码（中文描述源） */
    private final int weatherCode;
    /** 中文天气描述（彩云等无 WMO 码的源提供，可为 null） */
    private final String condition;

    public HourlyForecast(String timeLabel, double temperature, int weatherCode) {
        this(timeLabel, temperature, weatherCode, null);
    }

    public HourlyForecast(String timeLabel, double temperature, int weatherCode, String condition) {
        this.timeLabel = timeLabel;
        this.temperature = temperature;
        this.weatherCode = weatherCode;
        this.condition = condition;
    }

    public String getTimeLabel() {
        return timeLabel;
    }

    public double getTemperature() {
        return temperature;
    }

    public int getWeatherCode() {
        return weatherCode;
    }

    public String getCondition() {
        return condition;
    }
}
