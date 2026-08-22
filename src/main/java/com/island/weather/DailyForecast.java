package com.island.weather;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * 每日天气预报模型（多日天气预报列表单项）。
 */
public class DailyForecast {

    /** 日期标签，如 "8月22日" */
    private final String dateLabel;
    /** 星期标签：今天/明天/昨天/周X */
    private final String weekLabel;
    /** WMO 天气码，-1 表示无天气码（中文描述源） */
    private final int weatherCode;
    /** 中文天气描述（聚合数据等无 WMO 码的源提供，可为 null） */
    private final String condition;
    private final double tempMin;
    private final double tempMax;

    public DailyForecast(String dateLabel, String weekLabel, int weatherCode, String condition,
                         double tempMin, double tempMax) {
        this.dateLabel = dateLabel;
        this.weekLabel = weekLabel;
        this.weatherCode = weatherCode;
        this.condition = condition;
        this.tempMin = tempMin;
        this.tempMax = tempMax;
    }

    /** 从聚合数据 future 数据构建（ISO 日期 + 中文天气描述，无 WMO 码） */
    public static DailyForecast ofCondition(String isoDate, String condition, double tempMin, double tempMax) {
        LocalDate date = parseDate(isoDate);
        return new DailyForecast(dateLabelOf(date), weekLabelOf(date), -1, condition, tempMin, tempMax);
    }

    private static LocalDate parseDate(String isoDate) {
        try {
            return LocalDate.parse(isoDate);
        } catch (DateTimeParseException | NullPointerException e) {
            return LocalDate.now();
        }
    }

    private static String dateLabelOf(LocalDate date) {
        return date.getMonthValue() + "月" + date.getDayOfMonth() + "日";
    }

    private static String weekLabelOf(LocalDate date) {
        long diff = date.toEpochDay() - LocalDate.now().toEpochDay();
        if (diff == 0) return "今天";
        if (diff == 1) return "明天";
        if (diff == -1) return "昨天";
        String[] weeks = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        return weeks[date.getDayOfWeek().getValue() - 1];
    }

    public String getDateLabel() {
        return dateLabel;
    }

    public String getWeekLabel() {
        return weekLabel;
    }

    public int getWeatherCode() {
        return weatherCode;
    }

    public String getCondition() {
        return condition;
    }

    public double getTempMin() {
        return tempMin;
    }

    public double getTempMax() {
        return tempMax;
    }

    public boolean hasTempMin() {
        return !Double.isNaN(tempMin);
    }

    public boolean hasTempMax() {
        return !Double.isNaN(tempMax);
    }
}
