package com.island.config;

import java.io.File;
import java.util.prefs.Preferences;

/**
 * 应用常量配置
 */
public final class AppConstants {

    private AppConstants() {
        // 工具类，禁止实例化
    }

    private static final String PREF_KEY_CACHE_DIR = "cache.dir";
    private static final String PREF_KEY_AUTO_START = "auto.start";
    private static final String PREF_KEY_MINIMIZE_TO_TRAY = "minimize.to.tray";
    private static final String PREF_KEY_LOG_DIR = "log.dir";
    private static volatile String customCacheDir;
    private static volatile Runnable onCacheDirChange;
    private static volatile String customLogDir;
    private static volatile Runnable onLogDirChange;

    /** 单实例锁定端口 */
    public static final int SINGLE_INSTANCE_PORT = 9127;

    /** 触发距离：鼠标距离上边框50px时触发 */
    public static final int TRIGGER_DISTANCE = 8;

    /**
     * 高频调试输出开关（-Disland.debug=true 开启）。
     * 默认关闭：避免播放期间每 300ms 的轮询回调/歌词进度日志造成控制台 I/O 压力。
     */
    public static final boolean DEBUG_CONSOLE = Boolean.getBoolean("island.debug");

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

    // ── 日志目录 ──

    /** 日志目录默认值：位于用户目录下，无需管理员权限即可读写。 */
    public static final String LOG_DIR_DEFAULT = System.getProperty("user.home")
            + File.separator + ".java-island" + File.separator + "logs";

    /** 获取当前生效的日志目录。优先级：用户设置 → 用户目录默认值。 */
    public static String getLogDir() {
        if (customLogDir != null) return customLogDir;
        try {
            String saved = Preferences.userNodeForPackage(AppConstants.class)
                    .get(PREF_KEY_LOG_DIR, null);
            if (saved != null && !saved.isBlank()) {
                customLogDir = saved;
                return saved;
            }
        } catch (Exception ignored) {}
        return LOG_DIR_DEFAULT;
    }

    /** 更新日志目录并持久化到 Preferences。设为空字符串恢复默认。 */
    public static void setLogDir(String dir) {
        customLogDir = (dir == null || dir.isBlank()) ? null : dir;
        try {
            if (customLogDir != null) {
                Preferences.userNodeForPackage(AppConstants.class)
                        .put(PREF_KEY_LOG_DIR, customLogDir);
            } else {
                Preferences.userNodeForPackage(AppConstants.class)
                        .remove(PREF_KEY_LOG_DIR);
            }
        } catch (Exception ignored) {}
        Runnable r = onLogDirChange;
        if (r != null) r.run();
    }

    /** 日志目录变更时触发回调。 */
    public static void setOnLogDirChange(Runnable listener) {
        onLogDirChange = listener;
    }

    // ── 日志保留天数 ──

    private static final String PREF_KEY_LOG_RETENTION_DAYS = "log.retention.days";

    /** 日志保留天数默认值：30 天。 */
    public static final int LOG_RETENTION_DAYS_DEFAULT = 30;

    /** 获取日志保留天数（<=0 表示不自动清理）。 */
    public static int getLogRetentionDays() {
        try {
            return Preferences.userNodeForPackage(AppConstants.class)
                    .getInt(PREF_KEY_LOG_RETENTION_DAYS, LOG_RETENTION_DAYS_DEFAULT);
        } catch (Exception e) {
            return LOG_RETENTION_DAYS_DEFAULT;
        }
    }

    /** 设置日志保留天数并持久化到 Preferences（<=0 表示不自动清理）。 */
    public static void setLogRetentionDays(int days) {
        try {
            Preferences.userNodeForPackage(AppConstants.class)
                    .putInt(PREF_KEY_LOG_RETENTION_DAYS, days);
        } catch (Exception ignored) { }
    }

    // ── 界面语言 ──

    private static final String PREF_KEY_LANGUAGE = "ui.language";

    /** 获取界面语言（"zh" 简体中文 / "en" English，默认 "zh"）。 */
    public static String getLanguage() {
        try {
            String saved = Preferences.userNodeForPackage(AppConstants.class)
                    .get(PREF_KEY_LANGUAGE, "zh");
            return "en".equals(saved) ? "en" : "zh";
        } catch (Exception e) {
            return "zh";
        }
    }

    /** 设置界面语言并持久化（"zh" / "en"，其他值按 "zh" 处理）。 */
    public static void setLanguage(String lang) {
        String value = "en".equals(lang) ? "en" : "zh";
        try {
            Preferences.userNodeForPackage(AppConstants.class)
                    .put(PREF_KEY_LANGUAGE, value);
        } catch (Exception ignored) { }
    }

    // ── 开机自启 ──

    /** 获取开机自启开关状态（默认关闭）。 */
    public static boolean isAutoStartEnabled() {
        try {
            return Preferences.userNodeForPackage(AppConstants.class)
                    .getBoolean(PREF_KEY_AUTO_START, false);
        } catch (Exception e) {
            return false;
        }
    }

    /** 设置开机自启开关状态并持久化到 Preferences。 */
    public static void setAutoStartEnabled(boolean enabled) {
        try {
            Preferences.userNodeForPackage(AppConstants.class)
                    .putBoolean(PREF_KEY_AUTO_START, enabled);
        } catch (Exception ignored) {
            // Preferences 不可用时静默忽略
        }
    }

    // ── 启动时最小化到托盘 ──

    /** 获取"启动时最小化到系统托盘"开关状态（默认开启）。 */
    public static boolean isMinimizeToTrayEnabled() {
        try {
            return Preferences.userNodeForPackage(AppConstants.class)
                    .getBoolean(PREF_KEY_MINIMIZE_TO_TRAY, true);
        } catch (Exception e) {
            return true;
        }
    }

    /** 设置"启动时最小化到系统托盘"开关状态。 */
    public static void setMinimizeToTrayEnabled(boolean enabled) {
        try {
            Preferences.userNodeForPackage(AppConstants.class)
                    .putBoolean(PREF_KEY_MINIMIZE_TO_TRAY, enabled);
        } catch (Exception ignored) { }
    }

    // ── 扩展岛空闲自动收起 ──

    private static final String PREF_KEY_AUTO_COLLAPSE_EXPANDED = "island.autoCollapseExpanded";

    /** 获取"扩展岛空闲 10 分钟后自动收起"开关状态（默认关闭）。 */
    public static boolean isAutoCollapseExpandedEnabled() {
        try {
            return Preferences.userNodeForPackage(AppConstants.class)
                    .getBoolean(PREF_KEY_AUTO_COLLAPSE_EXPANDED, false);
        } catch (Exception e) {
            return false;
        }
    }

    /** 设置"扩展岛空闲 10 分钟后自动收起"开关状态。 */
    public static void setAutoCollapseExpandedEnabled(boolean enabled) {
        try {
            Preferences.userNodeForPackage(AppConstants.class)
                    .putBoolean(PREF_KEY_AUTO_COLLAPSE_EXPANDED, enabled);
        } catch (Exception ignored) { }
    }

    // ── Node.js 路径查找 ──

    /**
     * 查找 Node.js 可执行文件路径。
     * 依次搜索：环境变量 NODE_PATH → PATH → 常见安装位置 → fnm/scoop/nvm 等版本管理器。
     *
     * @return node.exe 的绝对路径，找不到返回 {@code null}
     */
    public static String findNodeExecutable() {
        // 0. jpackage 打包环境：优先使用 exe 同目录下捆绑的 node\node.exe
        String packagedApp = System.getProperty("jpackage.app-path");
        if (packagedApp != null && !packagedApp.isBlank()) {
            File exeDir = new File(packagedApp).getParentFile();
            if (exeDir != null) {
                File bundled = new File(exeDir, "node" + File.separator + "node.exe");
                if (bundled.exists()) return bundled.getAbsolutePath();
            }
        }

        // 1. 环境变量 NODE_PATH
        String nodePath = System.getenv("NODE_PATH");
        if (nodePath != null) {
            File f = new File(nodePath, "node.exe");
            if (f.exists()) return f.getAbsolutePath();
        }

        // 2. PATH 搜索
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String dir : pathEnv.split(File.pathSeparator)) {
                File f = new File(dir.trim(), "node.exe");
                if (f.exists()) return f.getAbsolutePath();
            }
        }

        // 3. 常见安装位置
        String[] candidates = {
                System.getenv("ProgramFiles") + "\\nodejs\\node.exe",
                System.getenv("ProgramFiles(x86)") + "\\nodejs\\node.exe",
                System.getenv("LOCALAPPDATA") + "\\Programs\\nodejs\\node.exe",
                System.getenv("APPDATA") + "\\nvm\\node.exe",
                System.getProperty("user.home") + "\\scoop\\apps\\nodejs\\current\\node.exe",
                System.getProperty("user.home") + "\\AppData\\Local\\fnm\\node.exe",
        };
        for (String candidate : candidates) {
            File f = new File(candidate);
            if (f.exists()) return f.getAbsolutePath();
        }

        return null;
    }
}
