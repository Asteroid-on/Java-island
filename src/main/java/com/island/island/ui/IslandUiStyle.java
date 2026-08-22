package com.island.island.ui;

import java.awt.Color;
import java.awt.Font;

/**
 * 动态岛 UI 共享样式常量：颜色、字体、动画时长与布局尺寸。
 * 主岛与扩展岛各组件统一引用，避免魔法值散落各处。
 */
public final class IslandUiStyle {

    private IslandUiStyle() {
        // 常量类，禁止实例化
    }

    // ── 通用颜色 ──
    public static final Color GREEN = new Color(0, 200, 80);
    public static final Color BACKGROUND_COLOR = new Color(30, 30, 30, 200);
    public static final Color TRANSPARENT_BLACK = new Color(0, 0, 0, 0);
    public static final Color SEMI_TRANSPARENT_BLACK = new Color(0, 0, 0, 60);
    public static final Color LIGHT_GRAY = new Color(200, 200, 200);
    public static final Color DEEP_BLACK = new Color(10, 10, 10, 240);

    // ── 字体 ──
    public static final Font TIME_FONT = new Font("Microsoft YaHei", Font.BOLD, 24);
    public static final Font DATE_FONT = new Font("Microsoft YaHei", Font.PLAIN, 11);
    public static final Font WEATHER_TEMP_FONT = new Font("Microsoft YaHei", Font.BOLD, 14);
    public static final Font WEATHER_COND_FONT = new Font("Microsoft YaHei", Font.PLAIN, 10);
    public static final Font NOTIFY_TITLE_FONT = new Font("Microsoft YaHei", Font.BOLD, 16);
    public static final Font MUSIC_TITLE_FONT = new Font("Microsoft YaHei", Font.PLAIN, 11);
    public static final Font MUSIC_ARTIST_FONT = new Font("Microsoft YaHei", Font.PLAIN, 10);
    public static final Font MUSIC_LYRICS_FONT = new Font("Microsoft YaHei", Font.BOLD, 14);

    // ── 主岛通知动画 ──
    public static final int ANIM_DURATION_MS = 650;
    public static final int ANIM_FRAME_MS = 16;
    public static final int NOTIFICATION_DISPLAY_TIME = 2000;
    public static final double TEXT_VISIBLE_THRESHOLD_RATIO = 2.0 / 3.0;

    // ── 扩展岛窗口与展开/收起动画 ──
    public static final int EXPANDED_WIDTH = 450;
    public static final int EXPANDED_HEIGHT = 54;
    public static final int EXPAND_ANIM_DURATION_MS = 280;
    public static final int EXPAND_ANIM_FRAME_MS = 10;
    public static final int SLIDE_ANIM_DURATION_MS = 400;
    public static final int SLIDE_ANIM_FRAME_MS = 11; // 约 90 FPS（1000/11 ≈ 90.9）
    /** 直接隐藏阶段1（两边向中间收缩成小球）时长 */
    public static final int SLIDE_UP_SHRINK_MS = 400;
    /** 直接隐藏阶段2（小球向上滑出屏幕）时长：快速滑出，无需看清 */
    public static final int SLIDE_UP_RISE_MS = 100;

    // ── 音乐面板 ──
    public static final int COVER_SIZE = 48;
    public static final int COVER_SSAA = 3;
    public static final int COVER_HIRES = COVER_SIZE * COVER_SSAA;
    public static final double COVER_ROTATION_DEG_PER_FRAME = 0.2667; // 16.67°/s × 16ms，与原 30ms×0.5° 转速一致
    public static final int COVER_ROTATION_FRAME_MS = 16; // 约 60 FPS（1000/16 ≈ 62.5）
    public static final int LYRIC_SCROLL_MS = 200;

    // ── 摄像头 / 麦克风使用状态 ──
    public static final int USAGE_ICON_MS = 1500;
    public static final int USAGE_MORPH_MS = 400;
    public static final int USAGE_FADE_OUT_MS = 350;
    public static final int USAGE_ANIM_FRAME_MS = 16;
    /** 图标尺寸：定位在扩展岛右半圆圆心，放大 2 倍（14 → 28） */
    public static final int USAGE_ICON_SIZE = 28;
    public static final int USAGE_DOT_DIAMETER = 8;
    public static final int USAGE_SLOT_GAP = 6;
    public static final int USAGE_PANEL_PAD = 6;

    // ── 扩展岛天气区域与天气详情延伸区 ──
    /** 天气条图标字号（与主岛天气图标一致，QWeather 图标字体） */
    public static final int WEATHER_ICON_SIZE = 12;
    /** 天气详情展开时右上角返回图标尺寸（替换上行温度文字） */
    public static final int WEATHER_RETURN_ICON_SIZE = 20;
    /** 天气详情手动刷新按钮图标尺寸 */
    public static final int WEATHER_REFRESH_ICON_SIZE = 20;
    /** 天气条与绿点/设备图标之间的最小间距 */
    public static final int WEATHER_TO_DOT_GAP = 10;
    /** 天气详情延伸高度：窗口在药丸基础上向下无缝延伸的高度（全宽连为一体） */
    public static final int WEATHER_DETAIL_HEIGHT = 560;
    /** 延伸形态的底部圆角半径（顶部保持药丸半圆帽） */
    public static final int WEATHER_BOTTOM_ARC = 24;
    /** 天气详情向下延伸/收起动画时长 */
    public static final int WEATHER_DETAIL_ANIM_MS = 300;
    public static final Font WEATHER_CARD_TITLE_FONT = new Font("Microsoft YaHei", Font.PLAIN, 13);
    public static final Font WEATHER_CARD_TEMP_FONT = new Font("Microsoft YaHei", Font.BOLD, 32);
    public static final Font WEATHER_CARD_INFO_FONT = new Font("Microsoft YaHei", Font.PLAIN, 14);
    /** 逐时预报时间标签字体 */
    public static final Font WEATHER_FORECAST_TIME_FONT = new Font("Microsoft YaHei", Font.PLAIN, 12);
    /** 逐时预报温度字体 */
    public static final Font WEATHER_FORECAST_TEMP_FONT = new Font("Microsoft YaHei", Font.BOLD, 14);
    /** 多日预报行字体 */
    public static final Font WEATHER_DAILY_FONT = new Font("Microsoft YaHei", Font.PLAIN, 14);

    // ── 自动弹出/收起时长 ──
    /** 设备占用自动弹出扩展岛后保持显示的时长 */
    public static final int DEVICE_AUTO_HIDE_MS = 5000;
    /** 音乐停止播放后自动收回扩展岛的等待时长（连续未恢复播放满 2 分钟） */
    public static final int MUSIC_STOP_AUTO_HIDE_MS = 2 * 60 * 1000;
    /** 扩展岛空闲自动收起：仅用户主动展开时启动，连续空闲满此时长后自动收起 */
    public static final int IDLE_AUTO_COLLAPSE_MS = 10 * 60 * 1000;
    /** 空闲自动收起巡检间隔 */
    public static final int IDLE_AUTO_COLLAPSE_CHECK_MS = 5000;
}
