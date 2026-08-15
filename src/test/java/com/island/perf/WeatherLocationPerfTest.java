package com.island.perf;

import com.island.util.WindowsLocationProvider;
import com.island.weather.JuheWeatherService;
import com.island.weather.OpenMeteoWeatherService;
import com.island.weather.HybridWeatherService;
import com.island.weather.WeatherInfo;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 天气/定位服务性能测试（真实网络、真实定位 EXE）。
 *
 * 覆盖：
 * - WindowsLocationProvider：EXE 编译缓存命中、10 分钟结果缓存命中率、
 *   未命中时的原生进程调用耗时（含超时行为）
 * - JuheWeatherService / OpenMeteoWeatherService 真实 HTTP 请求耗时
 * - HybridWeatherService 降级行为（聚合失败 → Open-Meteo 兜底的触发时延）
 */
public class WeatherLocationPerfTest {

    public static void main(String[] args) throws Exception {
        PerfUtil.header("天气/定位服务性能测试（真实网络与原生进程）");

        // ═══ 1. WindowsLocationProvider ═══
        Path exe = Paths.get(System.getProperty("java.io.tmpdir"), "java-island-locator.exe");
        System.out.println("[PERF] 定位EXE已编译缓存: " + Files.exists(exe) + " (" + exe + ")");

        // 1a. 缓存命中（10 分钟内第二次调用）
        WindowsLocationProvider.LocationResult first = WindowsLocationProvider.getLocation();
        long t1 = System.nanoTime();
        WindowsLocationProvider.LocationResult second = WindowsLocationProvider.getLocation();
        double cachedMs = (System.nanoTime() - t1) / 1e6;
        System.out.printf("[PERF] 定位结果缓存命中耗时: %.3fms (结果=%s)%n", cachedMs,
                second == null ? "null" : second.latitude + "," + second.longitude);

        // 1b. 缓存未命中（反射清空缓存 → 强制原生进程调用）
        clearLocationCache();
        long t2 = System.nanoTime();
        WindowsLocationProvider.LocationResult uncached = WindowsLocationProvider.getLocation();
        double procMs = (System.nanoTime() - t2) / 1e6;
        System.out.printf("[PERF] 缓存未命中→原生EXE进程调用: %.1fms (结果=%s)%n", procMs,
                uncached == null ? "null" : uncached.latitude + "," + uncached.longitude);

        // 缓存率验证：清空后第一次调用慢、第二次快
        clearLocationCache();
        long a = System.nanoTime();
        WindowsLocationProvider.getLocation();
        double missMs = (System.nanoTime() - a) / 1e6;
        long b = System.nanoTime();
        WindowsLocationProvider.getLocation();
        double hitMs = (System.nanoTime() - b) / 1e6;
        System.out.printf("[PERF] 缓存率验证: miss=%.1fms hit=%.3fms (10min TTL内仅1次进程拉起)%n", missMs, hitMs);

        // ═══ 2. JuheWeatherService 真实请求 ═══
        PerfUtil.header("聚合数据天气服务（真实 HTTP）");
        AtomicLong juheMs = new AtomicLong(-1);
        CountDownLatch juheDone = new CountDownLatch(1);
        JuheWeatherService juhe = new JuheWeatherService();
        long juheStart = System.nanoTime();
        juhe.setListener(new JuheWeatherService.WeatherListener() {
            @Override public void onWeatherUpdated(WeatherInfo weather) {
                juheMs.set((System.nanoTime() - juheStart) / 1_000_000);
                System.out.printf("[PERF] 聚合数据成功: %s %.1f°C %s%n", weather.getLocation(), weather.getTemperature(), weather.getCondition());
                juheDone.countDown();
            }
            @Override public void onWeatherError(String error) {
                juheMs.set((System.nanoTime() - juheStart) / 1_000_000);
                System.out.println("[PERF] 聚合数据失败: " + error);
                juheDone.countDown();
            }
        });
        juhe.start();
        if (juheDone.await(60, TimeUnit.SECONDS)) {
            System.out.printf("[PERF] 聚合数据端到端耗时: %.1fms%n", juheMs.get() / 1.0);
        } else {
            System.out.println("[PERF] 聚合数据 60s 超时未返回");
        }
        juhe.stop();

        // ═══ 3. OpenMeteoWeatherService 真实请求 ═══
        PerfUtil.header("Open-Meteo 天气服务（真实 HTTP）");
        AtomicLong omMs = new AtomicLong(-1);
        CountDownLatch omDone = new CountDownLatch(1);
        OpenMeteoWeatherService om = new OpenMeteoWeatherService();
        long omStart = System.nanoTime();
        om.setListener(new OpenMeteoWeatherService.WeatherListener() {
            @Override public void onWeatherUpdated(WeatherInfo weather) {
                omMs.set((System.nanoTime() - omStart) / 1_000_000);
                System.out.printf("[PERF] Open-Meteo成功: %s %.1f°C %s%n", weather.getLocation(), weather.getTemperature(), weather.getCondition());
                omDone.countDown();
            }
            @Override public void onWeatherError(String error) {
                omMs.set((System.nanoTime() - omStart) / 1_000_000);
                System.out.println("[PERF] Open-Meteo失败: " + error);
                omDone.countDown();
            }
        });
        om.start();
        if (omDone.await(60, TimeUnit.SECONDS)) {
            System.out.printf("[PERF] Open-Meteo端到端耗时: %.1fms%n", omMs.get() / 1.0);
        } else {
            System.out.println("[PERF] Open-Meteo 60s 超时未返回");
        }
        om.stop();

        // ═══ 4. Hybrid 降级路径时序 ═══
        PerfUtil.header("HybridWeatherService 降级路径");
        long hStart = System.nanoTime();
        HybridWeatherService hybrid = new HybridWeatherService();
        CountDownLatch hDone = new CountDownLatch(1);
        hybrid.setListener(new HybridWeatherService.WeatherListener() {
            @Override public void onWeatherUpdated(WeatherInfo weather) {
                System.out.printf("[PERF] Hybrid最终成功(%.1fms): %s %.1f°C%n",
                        (System.nanoTime() - hStart) / 1e6, weather.getLocation(), weather.getTemperature());
                hDone.countDown();
            }
            @Override public void onWeatherError(String error) {
                System.out.printf("[PERF] Hybrid最终失败(%.1fms): %s%n", (System.nanoTime() - hStart) / 1e6, error);
                hDone.countDown();
            }
        });
        hybrid.start();
        hDone.await(60, TimeUnit.SECONDS);
        hybrid.stop();

        System.out.println("\n=== 天气/定位性能测试完成 ===");
        System.exit(0);
    }

    /** 反射清空定位缓存，模拟 10 分钟 TTL 过期后的首次调用。 */
    private static void clearLocationCache() throws Exception {
        Field f1 = WindowsLocationProvider.class.getDeclaredField("cachedLocation");
        Field f2 = WindowsLocationProvider.class.getDeclaredField("cachedLocationAt");
        f1.setAccessible(true);
        f2.setAccessible(true);
        f1.set(null, null);
        f2.set(null, 0L);
    }
}
