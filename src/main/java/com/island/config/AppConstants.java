package com.island.config;

import java.util.prefs.Preferences;

/**
 * 应用常量配置
 */
public final class AppConstants {

    private AppConstants() {
        // 工具类，禁止实例化
    }

    private static final String PREF_KEY_CACHE_DIR = "cache.dir";
    private static volatile String customCacheDir;
    private static volatile Runnable onCacheDirChange;

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

    /**
     * 歌词/封面磁盘缓存目录（默认值）。
     * 支持相对于工作目录的路径或绝对路径。
     */
    public static final String CACHE_DIR = "cache/lyrics";

    // ── 动态缓存路径 ──

    /** 获取当前生效的缓存目录。优先级：用户设置 → 系统默认。 */
    public static String getCacheDir() {
        if (customCacheDir != null) return customCacheDir;
        try {
            String saved = Preferences.userNodeForPackage(AppConstants.class)
                    .get(PREF_KEY_CACHE_DIR, null);
            if (saved != null && !saved.isBlank()) {
                customCacheDir = saved;
                return saved;
            }
        } catch (Exception ignored) {}
        return CACHE_DIR;
    }

    /** 更新缓存目录并持久化到 Preferences。设为空字符串恢复默认。 */
    public static void setCacheDir(String dir) {
        customCacheDir = (dir == null || dir.isBlank()) ? null : dir;
        try {
            if (customCacheDir != null) {
                Preferences.userNodeForPackage(AppConstants.class)
                        .put(PREF_KEY_CACHE_DIR, customCacheDir);
            } else {
                Preferences.userNodeForPackage(AppConstants.class)
                        .remove(PREF_KEY_CACHE_DIR);
            }
        } catch (Exception ignored) {}
        Runnable r = onCacheDirChange;
        if (r != null) r.run();
    }

    /** 缓存目录变更时触发回调（由 LyricsService 注册）。 */
    public static void setOnCacheDirChange(Runnable listener) {
        onCacheDirChange = listener;
    }
}
