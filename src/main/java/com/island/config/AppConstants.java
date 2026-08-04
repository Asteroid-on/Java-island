package com.island.config;

/**
 * 应用常量配置
 */
public final class AppConstants {

    private AppConstants() {
        // 工具类，禁止实例化
    }

    /** 触发距离：鼠标距离上边框50px时触发 */
    public static final int TRIGGER_DISTANCE = 50;

    /** 隐藏检测间隔：100ms */
    public static final int HIDE_CHECK_INTERVAL = 100;

    /** 动画帧间隔：8ms（约120fps） */
    public static final int ANIMATION_FRAME_INTERVAL = 8;

    /** 默认窗口宽度 */
    public static final int DEFAULT_WIDTH = 180;

    /** 默认窗口高度 */
    public static final int DEFAULT_HEIGHT = 50;

    /** 小球大小 */
    public static final int BALL_SIZE = 20;

    /** 阶段1动画帧数（小球移动） */
    public static final double ANIMATION_DURATION_PHASE1 = 5.5;

    /** 阶段2动画帧数（展开/收束） */
    public static final double ANIMATION_DURATION_PHASE2 = 18.67;
}
