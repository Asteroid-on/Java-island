package com.island.weather;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 混合天气服务：聚合数据与彩云天气并行拉取、合并推送。
 * <p>聚合数据提供实时天气（温度/状况/湿度）与 7 天每日预报（无逐时）；
 * 彩云提供未来两天 48 小时逐时预报（点亮详情时间轴）、3 天每日预报与
 * 分钟级雷达降水实况。两源任一刷新后合并为一条 {@link WeatherInfo}
 * 推送给监听器：实时优先取彩云（分钟级更新、有雷达降水实况，准确性
 * 远高于聚合观测站数据；彩云失败时回退聚合），每日预报优先聚合
 * （7 天，彩云仅 3 天；聚合失败时回退彩云），逐时恒取彩云（彩云未
 * 就绪/失败时为空，UI 显示"暂无逐时预报"兜底）。</p>
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
     * 合并推送：实时优先取彩云（彩云失败时回退聚合），每日预报优先聚合
     * （聚合失败时回退彩云），逐时恒取彩云；两源均无数据时向 UI 报错进入
     * 兜底状态。
     */
    private void pushMerged() {
        WeatherInfo realtime = lastCaiyun != null ? lastCaiyun : lastJuhe;
        if (realtime == null) {
            if (listener != null) {
                listener.onWeatherError("聚合数据与彩云天气均不可用");
            }
            return;
        }
        List<HourlyForecast> hourly = lastCaiyun != null
                ? lastCaiyun.getHourlyForecasts() : Collections.emptyList();
        List<DailyForecast> daily = lastJuhe != null && !lastJuhe.getDailyForecasts().isEmpty()
                ? lastJuhe.getDailyForecasts()
                : (lastCaiyun != null ? lastCaiyun.getDailyForecasts() : Collections.emptyList());
        double feelsLike = realtime.hasFeelsLike() ? realtime.getFeelsLike()
                : (lastJuhe != null && lastJuhe.hasFeelsLike() ? lastJuhe.getFeelsLike() : Double.NaN);
        double humidity = realtime.hasHumidity() ? realtime.getHumidity()
                : (lastJuhe != null && lastJuhe.hasHumidity() ? lastJuhe.getHumidity() : Double.NaN);
        // 日出/日落、空气质量与紫外线仅彩云提供（聚合无此数据）
        String sunrise = lastCaiyun != null ? lastCaiyun.getSunrise() : null;
        String sunset = lastCaiyun != null ? lastCaiyun.getSunset() : null;
        List<SunEvent> sunEvents = lastCaiyun != null ? lastCaiyun.getSunEvents() : Collections.emptyList();
        int airQualityIndex = lastCaiyun != null ? lastCaiyun.getAirQualityIndex() : -1;
        String airQualityDesc = lastCaiyun != null ? lastCaiyun.getAirQualityDesc() : null;
        double uvIndex = lastCaiyun != null ? lastCaiyun.getUvIndex() : Double.NaN;
        String uvDesc = lastCaiyun != null ? lastCaiyun.getUvDesc() : null;
        WeatherInfo merged = new WeatherInfo(
                realtime.getLocation(),
                realtime.getTemperature(),
                realtime.getCondition(),
                realtime.getWeatherCode(),
                feelsLike, humidity, hourly, daily, sunrise, sunset, sunEvents,
                airQualityIndex, airQualityDesc, uvIndex, uvDesc);
        if (listener != null) {
            listener.onWeatherUpdated(merged);
        }
    }
}
