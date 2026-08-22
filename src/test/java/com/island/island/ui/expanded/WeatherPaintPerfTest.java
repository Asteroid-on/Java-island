package com.island.island.ui.expanded;

import com.island.perf.PerfUtil;
import com.island.weather.DailyForecast;
import com.island.weather.HourlyForecast;
import com.island.weather.SunEvent;
import com.island.weather.WeatherInfo;

import javax.swing.JPanel;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 天气详情面板绘制性能基准（离线、无网络）：
 * 用模拟的 48 小时逐时 + 7 天多日 + 日出日落数据驱动 WeatherDetailPanel 的
 * 完整绘制路径（逐时行 + 日出日落细线/信息行 + 多日列表），度量每帧耗时与
 * 每帧对象分配字节，评估滚轮滚动/动画期间的 CPU 与 GC 压力。
 */
public class WeatherPaintPerfTest {

    public static void main(String[] args) throws Exception {
        PerfUtil.header("天气详情面板绘制性能基准（离线模拟数据）");

        WeatherDetailPanel detail = new WeatherDetailPanel(null);
        detail.updateWeather(buildFakeWeather());
        // 注入 450x560 的宿主面板（绘制路径依赖 panel.getWidth()）
        JPanel host = new JPanel(null);
        host.setSize(450, 560);
        Field panelField = WeatherDetailPanel.class.getDeclaredField("panel");
        panelField.setAccessible(true);
        panelField.set(detail, host);

        Method paintHourly = WeatherDetailPanel.class.getDeclaredMethod("paintHourlyForecast", Graphics2D.class);
        Method paintSunInfo = WeatherDetailPanel.class.getDeclaredMethod("paintSunInfo", Graphics2D.class);
        Method paintDaily = WeatherDetailPanel.class.getDeclaredMethod("paintDailyForecast", Graphics2D.class);
        Method ensureMetrics = WeatherDetailPanel.class.getDeclaredMethod("ensureMetrics", Graphics2D.class);
        for (Method m : new Method[]{paintHourly, paintSunInfo, paintDaily, ensureMetrics}) {
            m.setAccessible(true);
        }

        BufferedImage img = new BufferedImage(450, 560, BufferedImage.TYPE_INT_ARGB);

        // 首帧：初始化字体度量缓存（缩放倍率变化时才会重建）
        {
            Graphics2D g0 = img.createGraphics();
            ensureMetrics.invoke(detail, g0);
            g0.dispose();
        }

        Runnable paintFrame = () -> {
            Graphics2D g = img.createGraphics();
            try {
                paintHourly.invoke(detail, g);
                paintSunInfo.invoke(detail, g);
                paintDaily.invoke(detail, g);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            } finally {
                g.dispose();
            }
        };

        int iterations = 20_000;
        List<Double> samples = PerfUtil.sample(iterations, paintFrame);
        PerfUtil.print("完整绘制一帧（逐时+日出日落+多日）", PerfUtil.stats(samples));

        // 每帧对象分配字节
        com.sun.management.ThreadMXBean tmxb =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (tmxb.isThreadAllocatedMemorySupported() && tmxb.isThreadAllocatedMemoryEnabled()) {
            long tid = Thread.currentThread().getId();
            paintFrame.run();
            paintFrame.run();
            long before = tmxb.getThreadAllocatedBytes(tid);
            for (int i = 0; i < 1000; i++) {
                paintFrame.run();
            }
            long after = tmxb.getThreadAllocatedBytes(tid);
            System.out.printf(Locale.ROOT, "[PERF] 每帧分配字节: %.1f B（1000 帧均值）%n",
                    (after - before) / 1000.0);
        } else {
            System.out.println("[PERF] 线程分配统计不可用");
        }

        System.out.println("\n=== 天气绘制性能测试完成 ===");
    }

    /** 模拟真实天气数据：48 小时逐时 + 7 天多日 + 6 个日出日落事件 */
    private static WeatherInfo buildFakeWeather() {
        List<HourlyForecast> hourly = new ArrayList<>(48);
        for (int i = 0; i < 48; i++) {
            String label = String.format(Locale.ROOT, "%02d:00", (14 + i) % 24);
            hourly.add(new HourlyForecast(label, 22 + Math.sin(i) * 6, -1, i % 3 == 0 ? "晴" : "多云"));
        }
        List<DailyForecast> daily = new ArrayList<>(7);
        String[] conds = {"晴", "多云", "小雨", "阴", "晴", "多云", "晴"};
        for (int i = 0; i < 7; i++) {
            daily.add(DailyForecast.ofCondition("2026-08-" + (22 + i), conds[i], 20 + i, 32 - i));
        }
        List<SunEvent> events = new ArrayList<>(6);
        events.add(new SunEvent(0, true, "05:32"));
        events.add(new SunEvent(0, false, "18:45"));
        events.add(new SunEvent(1, true, "05:33"));
        events.add(new SunEvent(1, false, "18:44"));
        events.add(new SunEvent(2, true, "05:34"));
        events.add(new SunEvent(2, false, "18:43"));
        return new WeatherInfo("巴彦淖尔市 临河区", 24.5, "晴", -1, Double.NaN, 45.0,
                hourly, daily, "05:32", "18:45", events, 55, "良", 7.0, "很强");
    }
}
