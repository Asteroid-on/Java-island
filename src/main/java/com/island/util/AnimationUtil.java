package com.island.util;

/**
 * 动画缓动工具类
 */
public final class AnimationUtil {

    private AnimationUtil() {
        // 工具类，禁止实例化
    }

    /**
     * 线性缓动 - 匀速运动，最丝滑均匀
     */
    public static double linear(double x) {
        return x;
    }

    /**
     * 缓出（ease-out）二次方
     */
    public static double easeOutQuad(double x) {
        return 1 - (1 - x) * (1 - x);
    }

    /**
     * 缓入缓出（ease-in-out）二次方
     */
    public static double easeInOutQuad(double x) {
        return x < 0.5 ? 2 * x * x : 1 - Math.pow(-2 * x + 2, 2) / 2;
    }
}
