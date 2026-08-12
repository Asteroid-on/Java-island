package com.island.weather;

import com.island.util.AppLogger;

/**
 * 混合天气服务：聚合数据优先 + Open-Meteo CMA GRAPES 兜底。
 */
public class HybridWeatherService {

    private final JuheWeatherService juhe;
    private final OpenMeteoWeatherService openMeteo;
    private WeatherListener listener;

    public interface WeatherListener {
        void onWeatherUpdated(WeatherInfo weather);
        void onWeatherError(String error);
    }

    public HybridWeatherService() {
        this.juhe = new JuheWeatherService();
        this.openMeteo = new OpenMeteoWeatherService();
    }

    public void setListener(WeatherListener listener) {
        this.listener = listener;
    }

    public void start() {
        juhe.setListener(new JuheWeatherService.WeatherListener() {
            @Override
            public void onWeatherUpdated(WeatherInfo weather) {
                System.out.println("[天气] 聚合数据更新成功");
                if (listener != null) listener.onWeatherUpdated(weather);
            }

            @Override
            public void onWeatherError(String error) {
                AppLogger.warn("Weather", "聚合数据失败: " + error + "，降级到 Open-Meteo");
                openMeteo.setListener(new OpenMeteoWeatherService.WeatherListener() {
                    @Override
                    public void onWeatherUpdated(WeatherInfo weather) {
                        System.out.println("[天气] Open-Meteo（CMA GRAPES）更新成功");
                        if (listener != null) listener.onWeatherUpdated(weather);
                    }

                    @Override
                    public void onWeatherError(String err) {
                        AppLogger.error("Weather", "所有天气源均失败: " + err);
                        if (listener != null) listener.onWeatherError(err);
                    }
                });
                openMeteo.start();
            }
        });
        juhe.start();
    }

    public void stop() {
        juhe.stop();
        openMeteo.stop();
    }
}
