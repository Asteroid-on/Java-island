package com.island.util;

import java.awt.*;

/**
 * 多显示器屏幕几何工具：把屏幕坐标点映射到其所在显示器，
 * 供主岛/扩展岛按鼠标所在屏幕居中定位（多分辨率、多显示器自适应）。
 */
public final class ScreenUtil {

    private ScreenUtil() { }

    /**
     * 返回屏幕坐标点所在显示器的完整边界（虚拟桌面坐标，兼容负坐标副屏）。
     * 点不在任何显示器内时（如主岛隐藏动画后的离屏球位 y=-ballSize）按 x 范围匹配
     * 所在显示器（各屏 x 范围互不重叠，匹配无歧义）；仍不命中时兜底主显示器。
     */
    public static Rectangle getScreenBoundsAt(Point screenPoint) {
        GraphicsDevice[] devices =
                GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
        for (GraphicsDevice device : devices) {
            GraphicsConfiguration gc = device.getDefaultConfiguration();
            Rectangle bounds = gc.getBounds();
            if (bounds.contains(screenPoint)) {
                return bounds;
            }
        }
        // 离屏兜底：按 x 范围匹配（隐藏动画收尾的球位在屏幕正上方，x 仍在所属屏水平范围内）
        for (GraphicsDevice device : devices) {
            GraphicsConfiguration gc = device.getDefaultConfiguration();
            Rectangle bounds = gc.getBounds();
            if (screenPoint.x >= bounds.x && screenPoint.x < bounds.x + bounds.width) {
                return bounds;
            }
        }
        return GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
                .getDefaultConfiguration().getBounds();
    }

    /** 返回当前鼠标指针所在显示器边界（鼠标信息不可用时兜底主显示器）。 */
    public static Rectangle getScreenBoundsAtMouse() {
        try {
            PointerInfo info = MouseInfo.getPointerInfo();
            if (info != null) {
                return getScreenBoundsAt(info.getLocation());
            }
        } catch (Exception ignored) { }
        return GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
                .getDefaultConfiguration().getBounds();
    }
}
