package com.island.weather;

import java.util.Scanner;

/**
 * OpenMeteoWeatherService测试类
 */
public class OpenMeteoWeatherServiceTest {
    public static void main(String[] args) {
        System.out.println("正在测试OpenMeteoWeatherService...");
        
        OpenMeteoWeatherService weatherService = new OpenMeteoWeatherService();
        
        // 设置监听器
        weatherService.setListener(new OpenMeteoWeatherService.WeatherListener() {
            @Override
            public void onWeatherUpdated(WeatherInfo weather) {
                System.out.println("天气数据获取成功！");
                System.out.println("城市: " + weather.getLocation());
                System.out.println("温度: " + weather.getFormattedTemperature());
                System.out.println("天气状况: " + weather.getCondition());
                
                // 测试完成后退出
                System.exit(0);
            }

            @Override
            public void onWeatherError(String error) {
                System.out.println("天气数据获取失败: " + error);
                System.exit(1);
            }
        });
        
        // 开始获取天气数据
        weatherService.start();
        
        System.out.println("正在获取天气数据，请稍候...");
        
        // 等待一段时间让请求完成
        Scanner scanner = new Scanner(System.in);
        System.out.println("按Enter键退出...");
        scanner.nextLine();
        
        weatherService.stop();
    }
}