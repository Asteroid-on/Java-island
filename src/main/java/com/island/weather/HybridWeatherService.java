package com.island.weather;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 混合天气服务：聚合数据与彩云天气并行拉取、合并推送。
 * <p>聚合数据提供实时天气（温度/状况/湿度）与 7 天每日预报（无逐时）；
 * 彩云提供未来两天 48 小时逐时预报（点亮详情时间轴）与 3 天每日预报，
 * 同时作为聚合数据失败时的实时天气兜底源。两源任一刷新后合并为一条
 * {@link WeatherInfo} 推送给监听器：实时/每日优先取聚合（失败回退彩云），
 * 逐时恒取彩云（彩云未就绪/失败时为空，UI 显示"暂无逐时预报"兜底）。</p>
 */
public class HybridWeatherService {

    private final JuheWeatherService juhe;
    private final CaiYunWeatherService caiyun;
    private WeatherListener listener;

    /** 最近一次聚合数据（实时+每日），null 表示尚未取得/失败 */
    private volatile WeatherInfo lastJuhe;
    /** 最近一次彩云数据（实时+逐时+每日），null 表示尚未取得/失败 */
    private volatile WeatherInfo lastCaiyun;

    public interface WeatherListener {
        void onWeatherUpdated(WeatherInfo weather);
        void onWeatherError(String error);
    }

    public HybridWeatherService() {
        this.juhe = new JuheWeatherService();
        this.caiyun = new CaiYunWeatherService();
    }

    public void setListener(WeatherListener listener) {
        this.listener = listener;
    }

    public void start() {
        juhe.setListener(new JuheWeatherService.WeatherListener() {
            @Override
            public void onWeatherUpdated(WeatherInfo weather) {
                lastJuhe = weather;
                pushMerged();
            }

            @Override
            public void onWeatherError(String error) {
                // 聚合失败不立即向 UI 报错：彩云兜底仍在途/已就绪时先推送彩云数据
                pushMerged();
            }
        });
        caiyun.setListener(new CaiYunWeatherService.WeatherListener() {
            @Override
            public void onWeatherUpdated(WeatherInfo weather) {
                lastCaiyun = weather;
                pushMerged();
            }

            @Override
            public void onWeatherError(String error) {
                pushMerged();
            }
        });
        juhe.start();
        caiyun.start();
    }

    public void stop() {
        juhe.stop();
        caiyun.stop();
    }

    /**
     * 手动触发一次两源立即刷新：聚合与彩云并行拉取，各自完成后照常合并推送；
     * 两源均完成（成功或失败）后，在最后一个完成的调度线程回调 onComplete。
     * 不改变每小时定时自动刷新机制。
     */
    public void refreshNow(Runnable onComplete) {
        AtomicInteger pending = new AtomicInteger(2);
        Runnable done = () -> {
            if (pending.decrementAndGet() == 0 && onComplete != null) {
                onComplete.run();
            }
        };
        juhe.refreshNow(done);
        caiyun.refreshNow(done);
    }

    /**
     * 合并推送：实时/每日优先聚合（聚合失败时整体回退彩云），逐时恒取彩云；
     * 两源均无数据时向 UI 报错进入兜底状态。
     */
    private void pushMerged() {
        WeatherInfo primary = lastJuhe != null ? lastJuhe : lastCaiyun;
        if (primary == null) {
            if (listener != null) {
                listener.onWeatherError("聚合数据与彩云天气均不可用");
            }
            return;
        }
        List<HourlyForecast> hourly = lastCaiyun != null
                ? lastCaiyun.getHourlyForecasts() : Collections.emptyList();
        List<DailyForecast> daily = primary.getDailyForecasts().isEmpty() && lastCaiyun != null
                ? lastCaiyun.getDailyForecasts() : primary.getDailyForecasts();
        double feelsLike = primary.hasFeelsLike() ? primary.getFeelsLike()
                : (lastCaiyun != null && lastCaiyun.hasFeelsLike() ? lastCaiyun.getFeelsLike() : Double.NaN);
        double humidity = primary.hasHumidity() ? primary.getHumidity()
                : (lastCaiyun != null && lastCaiyun.hasHumidity() ? lastCaiyun.getHumidity() : Double.NaN);
        // 日出/日落、空气质量与紫外线仅彩云提供（聚合无此数据）
        String sunrise = lastCaiyun != null ? lastCaiyun.getSunrise() : null;
        String sunset = lastCaiyun != null ? lastCaiyun.getSunset() : null;
        List<SunEvent> sunEvents = lastCaiyun != null ? lastCaiyun.getSunEvents() : Collections.emptyList();
        int airQualityIndex = lastCaiyun != null ? lastCaiyun.getAirQualityIndex() : -1;
        String airQualityDesc = lastCaiyun != null ? lastCaiyun.getAirQualityDesc() : null;
        double uvIndex = lastCaiyun != null ? lastCaiyun.getUvIndex() : Double.NaN;
        String uvDesc = lastCaiyun != null ? lastCaiyun.getUvDesc() : null;
        WeatherInfo merged = new WeatherInfo(
                primary.getLocation(),
                primary.getTemperature(),
                primary.getCondition(),
                primary.getWeatherCode(),
                feelsLike, humidity, hourly, daily, sunrise, sunset, sunEvents,
                airQualityIndex, airQualityDesc, uvIndex, uvDesc);
        if (listener != null) {
            listener.onWeatherUpdated(merged);
        }
    }
}
