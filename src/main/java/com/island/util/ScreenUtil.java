package com.island.util;

import java.awt.*;

/**
 * 多显示器屏幕几何工具：把屏幕坐标点映射到其所在显示器，
 * 供主岛/扩展岛按鼠标所在屏幕居中定位（多分辨率、多显示器自适应）。
 */
public final class ScreenUtil {

    /**
     * 全部屏幕边界快照缓存：鼠标监控热路径（100ms/16ms 轮询）每轮都要按点反查所在屏，
     * 旧实现每次全量枚举 GraphicsEnvironment（native 屏幕遍历 + 多个 Rectangle 分配）；
     * 常态屏幕布局不变，按 5s 低频刷新即可覆盖热插拔屏/改缩放场景。
     */
    private static final long CACHE_TTL_MS = 5000;
    private static final Object CACHE_LOCK = new Object();
    /** 快照与时间戳捆绑为不可变记录经单一 volatile 字段发布：
     *  避免非 volatile 的 Rectangle[] 被其他线程（轮询线程/EDT）无锁读到未初始化内容 */
    private static volatile Snapshot snapshot;

    /** 屏幕边界快照 + 生成时刻 */
    private record Snapshot(Rectangle[] bounds, long at) { }

    private ScreenUtil() { }

    /**
     * 返回全部显示器边界的只读快照（虚拟桌面坐标，至少含主屏）。
     * 命中缓存在 5s TTL 内零分配；调用方不得修改返回数组内的 Rectangle。
     */
    public static Rectangle[] getScreenBoundsSnapshot() {
        Snapshot s = snapshot;
        long now = System.currentTimeMillis();
        if (s != null && now - s.at() < CACHE_TTL_MS) {
            return s.bounds();
        }
        synchronized (CACHE_LOCK) {
            s = snapshot;
            now = System.currentTimeMillis();
            if (s != null && now - s.at() < CACHE_TTL_MS) {
                return s.bounds();
            }
            GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
            GraphicsDevice[] devices = ge.getScreenDevices();
            Rectangle[] bounds = new Rectangle[devices.length];
            for (int i = 0; i < devices.length; i++) {
                bounds[i] = devices[i].getDefaultConfiguration().getBounds();
            }
            if (bounds.length == 0) {
                bounds = new Rectangle[] {
                        ge.getDefaultScreenDevice().getDefaultConfiguration().getBounds() };
            }
            snapshot = new Snapshot(bounds, now);
            return bounds;
        }
    }

    /**
     * 返回屏幕坐标点所在显示器的完整边界（虚拟桌面坐标，兼容负坐标副屏）。
     * 点不在任何显示器内时（如主岛隐藏动画后的离屏球位 y=-ballSize）按 x 范围匹配
     * 所在显示器（各屏 x 范围互不重叠，匹配无歧义）；仍不命中时兜底主显示器。
     * 返回防御性拷贝，调用方可安全持有/修改。
     */
    public static Rectangle getScreenBoundsAt(Point screenPoint) {
        Rectangle[] bounds = getScreenBoundsSnapshot();
        for (Rectangle b : bounds) {
            if (b.contains(screenPoint)) {
                return new Rectangle(b);
            }
        }
        // 离屏兜底：按 x 范围匹配（隐藏动画收尾的球位在屏幕正上方，x 仍在所属屏水平范围内）
        for (Rectangle b : bounds) {
            if (screenPoint.x >= b.x && screenPoint.x < b.x + b.width) {
                return new Rectangle(b);
            }
        }
        return new Rectangle(bounds[0]);
    }

    /** 返回当前鼠标指针所在显示器边界（鼠标信息不可用时兜底主显示器）。 */
    public static Rectangle getScreenBoundsAtMouse() {
        try {
            PointerInfo info = MouseInfo.getPointerInfo();
            if (info != null) {
                return getScreenBoundsAt(info.getLocation());
            }
        } catch (Exception ignored) { }
        return new Rectangle(getScreenBoundsSnapshot()[0]);
    }
}
