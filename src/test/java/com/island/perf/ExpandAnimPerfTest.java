package com.island.perf;

import com.formdev.flatlaf.FlatDarkLaf;
import com.island.island.ui.IslandUiStyle;
import com.island.island.ui.IslandWindow;
import com.island.island.ui.expanded.ExpandedIslandController;
import com.island.util.DpiUtil;
import com.island.util.WindowsTheme;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.win32.W32APIOptions;

import javax.swing.JComponent;
import javax.swing.RepaintManager;
import javax.swing.SwingUtilities;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 扩展岛展开/收起动画性能测试（真实屏幕、真实窗口、反射直接驱动动画）。
 *
 * 覆盖：
 * - 展开/收起动画帧数、总时长、帧间隔分布（avg/p50/p95/p99/max）与卡顿帧位置
 * - RepaintManager 级渲染耗时（每次 paintDirtyRegions 的墙钟时间）
 * - 当前 DPI 缩放（JVM uiScale + GDI 物理/逻辑比），以及生效的自适应帧间隔
 *
 * 通过反射驱动 ExpandedIslandController.show()/hide()，不依赖 Robot 点击
 * （绕开 T3 点击不命中问题），不修改业务代码。
 */
public class ExpandAnimPerfTest {

    private static final List<long[]> paintSamples = new ArrayList<>(); // {ns, durationNs}
    private static final Object paintLock = new Object();

    private interface Gdi32 extends Library {
        Gdi32 INSTANCE = Native.load("gdi32", Gdi32.class, W32APIOptions.DEFAULT_OPTIONS);
        Pointer CreateDC(String driver, String device, Pointer output, Pointer initData);
        int GetDeviceCaps(Pointer hdc, int index);
        int DeleteDC(Pointer hdc);
    }

    private interface Winmm extends Library {
        Winmm INSTANCE = Native.load("winmm", Winmm.class, W32APIOptions.DEFAULT_OPTIONS);
        int timeBeginPeriod(int periodMs);
        int timeEndPeriod(int periodMs);
    }

    /** GDI 物理/逻辑分辨率比（与 JVM uiScale 交叉验证）。 */
    private static double gdiScale() {
        Pointer hdc = Gdi32.INSTANCE.CreateDC("DISPLAY", null, null, null);
        try {
            int logicalW = Gdi32.INSTANCE.GetDeviceCaps(hdc, 118); // DESKTOPHORZRES
            int physW = Gdi32.INSTANCE.GetDeviceCaps(hdc, 8);      // HORZRES
            if (physW > 0 && logicalW > physW) return (double) logicalW / physW;
            return 1.0;
        } finally {
            Gdi32.INSTANCE.DeleteDC(hdc);
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== 扩展岛展开/收起动画性能测试 ===");

        DpiUtil.enablePerMonitorDpi();
        Winmm.INSTANCE.timeBeginPeriod(1);

        if (WindowsTheme.isDarkMode()) FlatDarkLaf.setup();

        installPaintInstrumentation();

        final IslandWindow island = new IslandWindow();
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        island.setLocation((screen.width - island.getWidth()) / 2, 0);
        island.setVisible(true);
        // 等待首帧渲染与各监控首次回调稳定（避免天气首拉等一次性 EDT 任务混入动画窗口）
        Thread.sleep(3000);

        double uiScale = IslandUiStyle.currentUiScale();
        int designFrameMs = IslandUiStyle.EXPAND_ANIM_FRAME_MS;
        int effectiveFrameMs = IslandUiStyle.expandAnimFrameMs();
        System.out.printf("[PERF] JVM uiScale=%.3f GDI物理/逻辑比=%.3f%n", uiScale, gdiScale());
        System.out.printf("[PERF] 展开动画: 设计帧间隔=%dms(100%%) 当前生效=%dms 时长=%dms%n",
                designFrameMs, effectiveFrameMs, IslandUiStyle.EXPAND_ANIM_DURATION_MS);
        System.out.printf("[PERF] 切卡动画: 设计帧间隔=%dms(100%%) 当前生效=%dms 时长=%dms%n",
                IslandUiStyle.SLIDE_ANIM_FRAME_MS, IslandUiStyle.slideAnimFrameMs(),
                IslandUiStyle.SLIDE_ANIM_DURATION_MS);

        // 反射获取控制器与 show/hide 方法
        Field ctrlField = IslandWindow.class.getDeclaredField("expandedController");
        ctrlField.setAccessible(true);
        final ExpandedIslandController controller = (ExpandedIslandController) ctrlField.get(island);
        final Method showMethod = ExpandedIslandController.class.getDeclaredMethod("show");
        showMethod.setAccessible(true);
        final Method hideMethod = ExpandedIslandController.class.getDeclaredMethod("hide");
        hideMethod.setAccessible(true);

        // ── 展开 ──
        System.out.println("\n--- 展开动画 ---");
        long expandInvokeNs = System.nanoTime();
        Sampler expandSampler = new Sampler(island, Sampler.Mode.EXPAND);
        expandSampler.start();
        SwingUtilities.invokeAndWait(() -> {
            try {
                showMethod.invoke(controller);
            } catch (Exception e) {
                throw new RuntimeException("show() 调用失败", e);
            }
        });
        expandSampler.join();
        FrameStats expand = expandSampler.stats();
        expand.print("展开", effectiveFrameMs);
        printPaintStats("展开", expandInvokeNs, expand.endNs);

        // ── 卡片滑动切换动画 ──
        // 切卡不改窗口 bounds（只改子组件 x），故改为采样 gestureSlideProgress 变化时刻
        System.out.println("\n--- 切卡滑动动画 ---");
        final Method slideMethod = ExpandedIslandController.class
                .getDeclaredMethod("showMusicPanelInExpanded");
        slideMethod.setAccessible(true);
        Field progressField = ExpandedIslandController.class.getDeclaredField("gestureSlideProgress");
        progressField.setAccessible(true);
        long slideInvokeNs = System.nanoTime();
        SlideSampler slide = new SlideSampler(progressField, controller);
        slide.start();
        SwingUtilities.invokeAndWait(() -> {
            try {
                slideMethod.invoke(controller);
            } catch (Exception e) {
                throw new RuntimeException("showMusicPanelInExpanded() 调用失败", e);
            }
        });
        slide.join();
        slide.stats().print("切卡", IslandUiStyle.slideAnimFrameMs());
        printPaintStats("切卡", slideInvokeNs, slide.endNs);

        // ── 收起 ──
        System.out.println("\n--- 收起动画 ---");
        Thread.sleep(500);
        Sampler collapseSampler = new Sampler(island, Sampler.Mode.COLLAPSE);
        collapseSampler.start();
        SwingUtilities.invokeAndWait(() -> {
            try {
                hideMethod.invoke(controller);
            } catch (Exception e) {
                throw new RuntimeException("hide() 调用失败", e);
            }
        });
        collapseSampler.join();
        FrameStats collapse = collapseSampler.stats();
        collapse.print("收起", effectiveFrameMs);
        printPaintStats("收起", collapse.startNs, collapse.endNs);

        System.out.println("\n--- 清理 ---");
        SwingUtilities.invokeAndWait(island::dispose);
        Winmm.INSTANCE.timeEndPeriod(1);
        System.out.println("=== 扩展岛动画性能测试完成 ===");
        System.exit(0);
    }

    // ── RepaintManager 埋点 ──

    private static void installPaintInstrumentation() {
        final RepaintManager delegate = RepaintManager.currentManager(null);
        RepaintManager rm = new RepaintManager() {
            @Override public void addDirtyRegion(JComponent c, int x, int y, int w, int h) {
                delegate.addDirtyRegion(c, x, y, w, h);
            }
            @Override public void addInvalidComponent(JComponent c) {
                delegate.addInvalidComponent(c);
            }
            @Override public void markCompletelyDirty(JComponent c) {
                delegate.markCompletelyDirty(c);
            }
            @Override public void markCompletelyClean(JComponent c) {
                delegate.markCompletelyClean(c);
            }
            @Override public void paintDirtyRegions() {
                long t0 = System.nanoTime();
                delegate.paintDirtyRegions();
                synchronized (paintLock) {
                    paintSamples.add(new long[]{t0, System.nanoTime() - t0});
                }
            }
            @Override public Rectangle getDirtyRegion(JComponent c) {
                return delegate.getDirtyRegion(c);
            }
            @Override public void setDoubleBufferingEnabled(boolean b) {
                delegate.setDoubleBufferingEnabled(b);
            }
            @Override public boolean isDoubleBufferingEnabled() {
                return delegate.isDoubleBufferingEnabled();
            }
            @Override public void setDoubleBufferMaximumSize(Dimension d) {
                delegate.setDoubleBufferMaximumSize(d);
            }
            @Override public Dimension getDoubleBufferMaximumSize() {
                return delegate.getDoubleBufferMaximumSize();
            }
            @Override public void validateInvalidComponents() {
                delegate.validateInvalidComponents();
            }
            @Override public void removeInvalidComponent(JComponent c) {
                delegate.removeInvalidComponent(c);
            }
        };
        RepaintManager.setCurrentManager(rm);
    }

    // ── 窗口边界采样 ──

    /** 采样线程：1ms 轮询扩展岛窗口 bounds 变化，记录每帧时间戳。 */
    static class Sampler extends Thread {
        enum Mode { EXPAND, COLLAPSE }

        final IslandWindow island;
        final Mode mode;
        final List<Long> frames = new ArrayList<>();
        volatile long startNs;
        volatile long endNs;
        volatile Window target;

        Sampler(IslandWindow island, Mode mode) {
            super("ExpandAnimSampler-" + mode);
            this.island = island;
            this.mode = mode;
            setDaemon(true);
        }

        @Override
        public void run() {
            // 1. 等待目标窗口出现（扩展岛：show() 后唯一非主岛可见窗口）
            long deadline = System.nanoTime() + 8_000_000_000L;
            while (System.nanoTime() < deadline) {
                for (Window w : Window.getWindows()) {
                    if (w != island && w.isVisible()) {
                        target = w;
                        break;
                    }
                }
                if (target != null) break;
                try { Thread.sleep(1); } catch (InterruptedException ignored) { }
            }
            if (target == null) {
                System.out.println("[DIAG] 未找到扩展岛窗口");
                return;
            }
            Rectangle last = target.getBounds();
            frames.add(System.nanoTime()); // 首帧
            long lastChange = frames.get(frames.size() - 1);
            // 2. 采样：展开到 bounds 稳定 250ms 为止；收起到窗口隐藏为止
            deadline = System.nanoTime() + 5_000_000_000L;
            while (System.nanoTime() < deadline) {
                if (mode == Mode.COLLAPSE && !target.isVisible()) break;
                Rectangle cur = target.getBounds();
                long now = System.nanoTime();
                if (!cur.equals(last)) {
                    frames.add(now);
                    last = cur;
                    lastChange = now;
                } else if (mode == Mode.EXPAND && now - lastChange > 250_000_000L && frames.size() > 3) {
                    break; // bounds 稳定 250ms：展开动画结束
                }
                try { Thread.sleep(1); } catch (InterruptedException ignored) { }
            }
            startNs = frames.get(0);
            endNs = System.nanoTime();
        }

        FrameStats stats() {
            return new FrameStats(frames, startNs, endNs);
        }
    }

    /** 切卡动画采样：1ms 轮询 gestureSlideProgress，变化即计为一帧，稳定 250ms 判定结束。 */
    static class SlideSampler extends Thread {
        private final Field progressField;
        private final ExpandedIslandController controller;
        final List<Long> frames = new ArrayList<>();
        volatile long startNs;
        volatile long endNs;

        SlideSampler(Field progressField, ExpandedIslandController controller) {
            super("SlideAnimSampler");
            this.progressField = progressField;
            this.controller = controller;
            setDaemon(true);
        }

        @Override
        public void run() {
            float last = -1f;
            long lastChange = 0;
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (System.nanoTime() < deadline) {
                float cur;
                try {
                    cur = progressField.getFloat(controller);
                } catch (IllegalAccessException e) {
                    System.out.println("[DIAG] gestureSlideProgress 读取失败: " + e.getMessage());
                    return;
                }
                long now = System.nanoTime();
                if (cur != last) {
                    frames.add(now);
                    last = cur;
                    lastChange = now;
                } else if (!frames.isEmpty() && now - lastChange > 250_000_000L && frames.size() > 3) {
                    break;
                }
                try { Thread.sleep(1); } catch (InterruptedException ignored) { }
            }
            if (!frames.isEmpty()) {
                startNs = frames.get(0);
                endNs = System.nanoTime();
            }
        }

        FrameStats stats() {
            return new FrameStats(frames, startNs, endNs);
        }
    }

    // ── 统计输出 ──

    static class FrameStats {
        final List<Long> frames;
        final long startNs;
        final long endNs;

        FrameStats(List<Long> frames, long startNs, long endNs) {
            this.frames = frames;
            this.startNs = startNs;
            this.endNs = endNs;
        }

        void print(String label, int designMs) {
            int n = frames.size();
            double totalMs = (endNs - startNs) / 1e6;
            System.out.printf("[PERF] %s动画: 帧数=%d 总时长=%.0fms%n", label, n, totalMs);
            if (n < 2) return;
            List<Double> ds = new ArrayList<>();
            for (int i = 1; i < n; i++) ds.add((frames.get(i) - frames.get(i - 1)) / 1e6);
            printDist(label + "帧间隔(设计" + designMs + "ms)", ds);
            // 卡顿帧：间隔 > 设计值 1.5 倍，输出其在动画中的进度位置
            StringBuilder stutter = new StringBuilder();
            int cnt = 0;
            for (int i = 1; i < n; i++) {
                double d = (frames.get(i) - frames.get(i - 1)) / 1e6;
                if (d > designMs * 1.5) {
                    cnt++;
                    double posPct = (frames.get(i) - frames.get(0)) / 1e6 / totalMs * 100.0;
                    if (stutter.length() > 0) stutter.append(", ");
                    stutter.append(String.format("%.0f%%(%.1fms)", posPct, d));
                }
            }
            System.out.printf("[PERF] %s动画卡顿帧(间隔>%.1fms): n=%d %s%n",
                    label, designMs * 1.5, cnt, cnt == 0 ? "无" : stutter);
        }
    }

    private static void printPaintStats(String label, long fromNs, long toNs) {
        synchronized (paintLock) {
            List<Double> ds = new ArrayList<>();
            for (long[] s : paintSamples) {
                if (s[0] >= fromNs && s[0] <= toNs) ds.add(s[1] / 1e6);
            }
            if (ds.isEmpty()) {
                System.out.printf("[PERF] %s阶段 paint 样本: 0%n", label);
                return;
            }
            printDist(label + "阶段 paint 耗时", ds);
        }
    }

    private static void printDist(String label, List<Double> ds) {
        if (ds.isEmpty()) return;
        List<Double> sorted = new ArrayList<>(ds);
        Collections.sort(sorted);
        double avg = sorted.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        int n = sorted.size();
        System.out.printf("[PERF]   %s: n=%d avg=%.1fms p50=%.1f p95=%.1f p99=%.1f max=%.1f%n",
                label, n, avg,
                sorted.get((int) (n * 0.5)), sorted.get((int) (n * 0.95)),
                sorted.get((int) (n * 0.99)), sorted.get(n - 1));
    }
}
