package com.island.perf;

import com.island.battery.BatteryMonitor;
import com.island.island.ui.IslandWindow;
import com.island.music.LyricsService;
import com.island.music.MusicMonitor;
import com.island.privacy.PrivacyMonitor;
import com.island.tray.SystemTrayManager;
import com.island.util.WindowsTheme;

import javax.swing.SwingUtilities;
import java.awt.Dimension;
import java.awt.Toolkit;

/**
 * 启动与初始化性能测试：逐阶段测量真实启动链路的墙钟时间。
 *
 * 与 IslandApplication.main 相同的顺序：主题 → Locale → IslandWindow(主岛+扩展岛
 * +BT/WiFi/天气监控) → SystemTrayManager(托盘+鼠标监听) → MusicMonitor/Battery/
 * Privacy 注入与首次轮询 → EDT 首次空闲。不修改业务代码。
 */
public class StartupPerfTest {

    public static void main(String[] args) throws Exception {
        System.out.println("=== 启动与初始化性能测试 ===");

        long[] phase = new long[20];
        long t0 = System.nanoTime();

        // ── 1. 主题检测与 FlatLaf 初始化 ──
        boolean dark = WindowsTheme.isDarkMode();
        long t1 = System.nanoTime();
        if (dark) com.formdev.flatlaf.FlatDarkLaf.setup();
        else com.formdev.flatlaf.FlatLightLaf.setup();
        long t2 = System.nanoTime();
        System.out.printf("[PERF] 1. Windows主题检测+FlatLaf初始化: %.1fms (dark=%s)%n", (t2 - t1) / 1e6, dark);

        // ── 2. 主岛 JWindow 构建（含扩展岛控制器、图标/字体加载、BT/WiFi/天气监控启动）──
        long t3 = System.nanoTime();
        IslandWindow island = new IslandWindow();
        long t4 = System.nanoTime();
        System.out.printf("[PERF] 2. 主岛IslandWindow构建(含扩展岛/图标/字体/BT/WiFi/天气启动): %.1fms%n", (t4 - t3) / 1e6);

        // ── 3. 系统托盘（托盘图标 + 全局鼠标监听 + MouseInfoMonitor 线程）──
        long t5 = System.nanoTime();
        SystemTrayManager tray = new SystemTrayManager(island);
        long t6 = System.nanoTime();
        System.out.printf("[PERF] 3. 系统托盘SystemTrayManager构建: %.1fms%n", (t6 - t5) / 1e6);
        island.setTrayManager(tray);

        // ── 4. 音乐监控注入（含首次 onStart 轮询读取 media_info.json）──
        long t7 = System.nanoTime();
        MusicMonitor mm = new MusicMonitor();
        island.setMusicMonitor(mm);
        long t8 = System.nanoTime();
        System.out.printf("[PERF] 4. MusicMonitor构建+注入+首次轮询: %.1fms%n", (t8 - t7) / 1e6);

        // ── 5. 电池监控注入（JNA GetSystemPowerStatus）──
        long t9 = System.nanoTime();
        BatteryMonitor bm = new BatteryMonitor();
        island.setBatteryMonitor(bm);
        long t10 = System.nanoTime();
        System.out.printf("[PERF] 5. BatteryMonitor构建+注入+首次轮询: %.1fms%n", (t10 - t9) / 1e6);

        // ── 6. 隐私监控注入（注册表读取）──
        long t11 = System.nanoTime();
        PrivacyMonitor pm = new PrivacyMonitor();
        island.setPrivacyMonitor(pm);
        long t12 = System.nanoTime();
        System.out.printf("[PERF] 6. PrivacyMonitor构建+注入+首次轮询: %.1fms%n", (t12 - t11) / 1e6);

        // ── 7. 歌词服务（LyricsCache 磁盘预加载）──
        long t13 = System.nanoTime();
        LyricsService ls = new LyricsService();
        long t14 = System.nanoTime();
        System.out.printf("[PERF] 7. LyricsService构建(含LyricsCache磁盘预加载): %.1fms%n", (t14 - t13) / 1e6);

        // ── 8. 定位与置中 ──
        long t15 = System.nanoTime();
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        island.setLocation((screen.width - island.getWidth()) / 2, 0);
        long t16 = System.nanoTime();
        System.out.printf("[PERF] 8. 屏幕探测+窗口置中: %.1fms (屏幕=%s)%n", (t16 - t15) / 1e6, screen);

        // ── 9. 首次 EDT 空闲（所有排队 UI 任务排空，代表"可交互就绪"）──
        long t17 = System.nanoTime();
        SwingUtilities.invokeAndWait(() -> {});
        long t18 = System.nanoTime();
        System.out.printf("[PERF] 9. EDT首次排空(可交互就绪): %.1fms%n", (t18 - t17) / 1e6);

        long total = (t18 - t0) / 1_000_000;
        System.out.println();
        System.out.printf("[PERF] ★ 应用侧初始化总耗时(UI线程): %dms%n", total);

        // ── 线程清点（启动完成后）──
        Thread.sleep(3000);
        System.out.println();
        System.out.println("--- 启动完成后线程清单 ---");
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.isDaemon() || t.getName().startsWith("AWT") || t.getName().contains("Monitor")
                    || t.getName().contains("Weather") || t.getName().contains("Event")
                    || t.getName().contains("Timer") || t.getName().contains("Daemon")) {
                System.out.printf("  %-28s daemon=%s state=%s%n", t.getName(), t.isDaemon(), t.getState());
            }
        }
        System.out.printf("[PERF] 总线程数: %d%n", Thread.activeCount());

        // ── 清理 ──
        SwingUtilities.invokeAndWait(() -> {
            island.dispose();
            tray.dispose();
        });
        System.out.println("=== 启动性能测试完成 ===");
        System.exit(0);
    }
}
