package com.island.perf;

import com.formdev.flatlaf.FlatDarkLaf;
import com.island.island.ui.IslandWindow;
import com.island.util.WindowsTheme;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.win32.W32APIOptions;

import javax.swing.JComponent;
import javax.swing.RepaintManager;
import javax.swing.SwingUtilities;
import java.awt.AWTException;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.Window;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * UI 交互与渲染性能测试（真实屏幕、真实窗口、Robot 真实点击）。
 *
 * 覆盖：
 * - 点击→展开动画启动延迟、展开动画时长与帧间隔（EXPAND_ANIM_FRAME_MS=10ms）
 * - 展开完成→点击折叠→收起动画时长与帧间隔
 * - 通知动画（showBluetoothNotification）的 EDT 调度延迟、帧率、展示时长与隐藏流程
 * - RepaintManager 级渲染耗时（每次 paintDirtyRegions 的墙钟时间）
 *
 * 通过反射读取私有状态、注入 RepaintManager 埋点，不修改业务代码。
 */
public class UiPerfHarness {

    private static final List<Long> paintDurationsNs = new ArrayList<>();
    private static final AtomicLong paintCount = new AtomicLong();
    private static final Object paintLock = new Object();

    /** GDI32：用于获取真实物理分辨率（不受 DPI 虚拟化影响），换算 Robot 物理鼠标坐标。 */
    private interface Gdi32 extends Library {
        Gdi32 INSTANCE = Native.load("gdi32", Gdi32.class, W32APIOptions.DEFAULT_OPTIONS);
        Pointer CreateDC(String driver, String device, Pointer output, Pointer initData);
        int GetDeviceCaps(Pointer hdc, int index);
        int DeleteDC(Pointer hdc);
    }

    /** 物理分辨率 / 逻辑分辨率 的缩放因子（150% 缩放下为 1.5）。 */
    private static double screenScale() {
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
        System.out.println("=== UI 交互与渲染性能测试 ===");

        if (WindowsTheme.isDarkMode()) FlatDarkLaf.setup();

        installPaintInstrumentation();

        final IslandWindow island = new IslandWindow();
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        island.setLocation((screen.width - island.getWidth()) / 2, 0);
        island.setVisible(true);
        // 等待首帧渲染与各监控首次回调稳定
        Thread.sleep(2500);

        Robot robot = new Robot();
        robot.setAutoDelay(0);

        // DPI 缩放：JVM 非 DPI-aware 时组件为逻辑坐标，Robot 需物理坐标
        double scale = screenScale();
        System.out.printf("[PERF] 屏幕逻辑=%s 物理缩放因子=%.3f%n",
                Toolkit.getDefaultToolkit().getScreenSize(), scale);

        Point islandCenter = new Point(
                island.getX() + island.getWidth() / 2,
                island.getY() + island.getHeight() / 2);
        Point islandCenterPhys = toPhysical(islandCenter, scale);
        System.out.printf("[PERF] 主岛 bounds=%s 逻辑中心=%s 物理点击点=%s%n",
                island.getBounds(), islandCenter, islandCenterPhys);

        // 诊断：额外注册监听器，验证鼠标事件是否到达主岛
        java.awt.event.MouseListener diag = new java.awt.event.MouseAdapter() {
            @Override public void mousePressed(java.awt.event.MouseEvent e) {
                System.out.println("[DIAG] 主岛收到 mousePressed @" + e.getPoint());
            }
            @Override public void mouseEntered(java.awt.event.MouseEvent e) {
                System.out.println("[DIAG] 主岛收到 mouseEntered @" + e.getPoint());
            }
        };
        island.addMouseListener(diag);
        System.out.printf("[DIAG] 主岛可见=%s 鼠标监听器数=%d 当前指针=%s%n",
                island.isVisible(), island.getMouseListeners().length,
                java.awt.MouseInfo.getPointerInfo().getLocation());

        // ═══ 1. 点击 → 展开 ═══
        System.out.println("\n--- 测试1: 点击展开 ---");
        paintCount.set(0);
        long clickTs = System.nanoTime();
        robot.mouseMove(islandCenterPhys.x, islandCenterPhys.y);
        Thread.sleep(120);
        robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);

        Window expanded = waitForExpandedWindow(island, 5000);
        long expandStartNs = System.nanoTime() - clickTs;
        System.out.printf("[PERF] 点击→展开窗口出现延迟: %.1fms%n", expandStartNs / 1e6);

        // 采样展开动画窗口边界（2ms 间隔，采样 1.5s）
        List<Long> expandFrames = sampleBounds(expanded, 1500, 2);
        System.out.printf("[PERF] 展开动画: 帧数=%d 总时长=%.0fms 平均帧间隔=%.1fms (设计值10ms)%n",
                expandFrames.size(),
                (expandFrames.get(expandFrames.size() - 1) - expandFrames.get(0)) / 1e6,
                avgDelta(expandFrames));
        printFrameStats("展开动画帧间隔", deltas(expandFrames));

        long repaintsAfterExpand = paintCount.get();
        System.out.printf("[PERF] 展开过程 repaint 次数=%d 平均每次=%.2fms%n",
                repaintsAfterExpand, avgPaintMs());

        // ═══ 2. 点击展开后的窗口 → 折叠 ═══
        System.out.println("\n--- 测试2: 点击折叠 ---");
        Thread.sleep(500);
        Point expandedCenter = new Point(
                expanded.getX() + expanded.getWidth() / 2,
                expanded.getY() + expanded.getHeight() / 2);
        long click2Ts = System.nanoTime();
        Point expandedCenterPhys = toPhysical(expandedCenter, scale);
        robot.mouseMove(expandedCenterPhys.x, expandedCenterPhys.y);
        Thread.sleep(120);
        robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);

        // 折叠动画：窗口尺寸回缩，采样直到窗口销毁
        List<Long> collapseFrames = sampleWindowUntilDisposed(expanded, 3000, 2);
        System.out.printf("[PERF] 折叠动画: 帧数=%d 总时长=%.0fms 平均帧间隔=%.1fms (设计值10ms)%n",
                collapseFrames.size(),
                collapseFrames.isEmpty() ? 0 : (collapseFrames.get(collapseFrames.size() - 1) - collapseFrames.get(0)) / 1e6,
                avgDelta(collapseFrames));
        printFrameStats("折叠动画帧间隔", deltas(collapseFrames));

        // ═══ 3. 通知动画 E2E（蓝牙连接事件 → 通知显示 → 动画 → 隐藏）═══
        System.out.println("\n--- 测试3: 蓝牙连接通知 E2E ---");
        // 通过反射调用真实路径 showBluetoothNotification（生产路径为 BT 轮询→EDT 回调→本方法）
        Method m = IslandWindow.class.getDeclaredMethod("showBluetoothNotification", String.class);
        m.setAccessible(true);

        paintCount.set(0);
        long notifyTs = System.nanoTime();
        m.invoke(island, "AirPods Pro");

        // 轮询通知状态机与动画进度（2ms 采样，总 6s）
        List<Long> animFrames = new ArrayList<>();
        long notifyShownAt = -1, animStartTs = -1, animEndTs = -1, notifyHiddenAt = -1;
        long deadline = System.nanoTime() + 6_000_000_000L;
        Field showingField = IslandWindow.class.getDeclaredField("showingNotification");
        Field activeField = IslandWindow.class.getDeclaredField("isNotificationActive");
        Field animField = IslandWindow.class.getDeclaredField("animProgress");
        Field animTimerField = IslandWindow.class.getDeclaredField("animTimer");
        showingField.setAccessible(true);
        activeField.setAccessible(true);
        animField.setAccessible(true);
        animTimerField.setAccessible(true);

        while (System.nanoTime() < deadline) {
            boolean showing = showingField.getBoolean(island);
            boolean active = activeField.getBoolean(island);
            float progress = animField.getFloat(island);
            long now = System.nanoTime();
            if (showing && notifyShownAt < 0) notifyShownAt = now;
            if (progress > 0 && progress < 1.0f) {
                if (animStartTs < 0) animStartTs = now;
                animFrames.add(now);
            } else if (progress >= 1.0f && animStartTs > 0 && animEndTs < 0) {
                animEndTs = now;
            }
            if (!showing && notifyShownAt > 0) { notifyHiddenAt = now; break; }
            Thread.sleep(0, 500_000);
        }
        if (notifyShownAt > 0) {
            System.out.printf("[PERF] 事件调用→通知状态置位延迟: %.1fms (含 invokeLater EDT 调度)%n",
                    (notifyShownAt - notifyTs) / 1e6);
        }
        if (animStartTs > 0 && animEndTs > 0) {
            System.out.printf("[PERF] 通知动画(0→100%%): 时长=%.0fms (设计650ms) 帧数=%d 平均帧间隔=%.1fms (设计16ms)%n",
                    (animEndTs - animStartTs) / 1e6, animFrames.size(), avgDelta(animFrames));
            printFrameStats("通知动画帧间隔", deltas(animFrames));
        }
        if (notifyHiddenAt > 0) {
            System.out.printf("[PERF] 通知总生命周期(显示→隐藏): %.0fms (设计=650ms动画+2000ms展示+收尾)%n",
                    (notifyHiddenAt - notifyShownAt) / 1e6);
        }
        System.out.printf("[PERF] 通知过程 repaint 次数=%d 平均每次=%.2fms%n", paintCount.get(), avgPaintMs());

        // ═══ 4. 清理 ═══
        System.out.println("\n--- 清理 ---");
        SwingUtilities.invokeAndWait(() -> {
            island.dispose();
        });
        System.out.println("=== UI 性能测试完成 ===");
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
                    paintDurationsNs.add(System.nanoTime() - t0);
                }
                paintCount.incrementAndGet();
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

    private static Point toPhysical(Point logical, double scale) {
        if (scale == 1.0) return logical;
        return new Point((int) Math.round(logical.x * scale), (int) Math.round(logical.y * scale));
    }

    private static double avgPaintMs() {
        synchronized (paintLock) {
            if (paintDurationsNs.isEmpty()) return 0;
            double sum = 0;
            for (long d : paintDurationsNs) sum += d / 1e6;
            return sum / paintDurationsNs.size();
        }
    }

    // ── 窗口探测与采样 ──

    /** 找到展开的扩展岛窗口（非主岛、可见、高度>50）。 */
    private static Window waitForExpandedWindow(IslandWindow island, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (Window w : Window.getWindows()) {
                if (w != island && w.isVisible() && w.getHeight() > 50) {
                    return w;
                }
            }
            Thread.sleep(5);
        }
        // 诊断：枚举所有窗口状态，帮助定位点击未生效原因
        System.out.println("[DIAG] 超时未找到扩展岛窗口，当前窗口清单:");
        for (Window w : Window.getWindows()) {
            System.out.printf("[DIAG]   %s visible=%s displayable=%s bounds=%s%n",
                    w.getClass().getSimpleName(), w.isVisible(), w.isDisplayable(), w.getBounds());
        }
        System.out.printf("[DIAG] 主岛 visible=%s bounds=%s 当前指针=%s%n",
                island.isVisible(), island.getBounds(),
                java.awt.MouseInfo.getPointerInfo().getLocation());
        throw new IllegalStateException("未找到扩展岛窗口");
    }

    /** 采样窗口边界变化时间戳（帧），sampleMs 间隔。 */
    private static List<Long> sampleBounds(Window w, long durationMs, int sampleMs) throws Exception {
        List<Long> frames = new ArrayList<>();
        Rectangle last = w.getBounds();
        long deadline = System.nanoTime() + durationMs * 1_000_000;
        while (System.nanoTime() < deadline) {
            Rectangle cur = w.getBounds();
            if (!cur.equals(last)) {
                frames.add(System.nanoTime());
                last = cur;
            }
            Thread.sleep(0, sampleMs * 1_000_000);
        }
        return frames;
    }

    /** 采样直到窗口被 dispose（折叠动画结束）。 */
    private static List<Long> sampleWindowUntilDisposed(Window w, long timeoutMs, int sampleMs) throws Exception {
        List<Long> frames = new ArrayList<>();
        Rectangle last = w.getBounds();
        long deadline = System.nanoTime() + timeoutMs * 1_000_000;
        while (System.nanoTime() < deadline) {
            if (!w.isDisplayable()) break;
            Rectangle cur = w.getBounds();
            if (!cur.equals(last)) {
                frames.add(System.nanoTime());
                last = cur;
            }
            Thread.sleep(0, sampleMs * 1_000_000);
        }
        return frames;
    }

    private static List<Double> deltas(List<Long> frames) {
        List<Double> ds = new ArrayList<>();
        for (int i = 1; i < frames.size(); i++) {
            ds.add((frames.get(i) - frames.get(i - 1)) / 1e6);
        }
        return ds;
    }

    private static double avgDelta(List<Long> frames) {
        if (frames.size() < 2) return 0;
        return (frames.get(frames.size() - 1) - frames.get(0)) / 1e6 / (frames.size() - 1);
    }

    private static void printFrameStats(String label, List<Double> ds) {
        if (ds.isEmpty()) return;
        var sorted = new ArrayList<>(ds);
        java.util.Collections.sort(sorted);
        double avg = sorted.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        int n = sorted.size();
        System.out.printf("[PERF]   %s: n=%d avg=%.1fms p50=%.1fms p95=%.1fms p99=%.1fms max=%.1fms%n",
                label, n, avg,
                sorted.get((int) (n * 0.5)), sorted.get((int) (n * 0.95)),
                sorted.get((int) (n * 0.99)), sorted.get(n - 1));
    }
}
