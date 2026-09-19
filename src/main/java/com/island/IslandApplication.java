package com.island;

import com.island.battery.BatteryMonitor;
import com.island.privacy.PrivacyMonitor;
import com.island.config.AppConstants;
import com.island.config.AppVersion;
import com.island.island.ui.IslandWindow;
import com.island.music.MusicMonitor;
import com.island.tray.SystemTrayManager;
import com.island.update.UpdateChecker;
import com.island.util.AppLogger;
import com.island.util.DpiUtil;
import com.island.util.ScreenUtil;
import com.island.util.WindowsStartupManager;
import com.island.util.WindowsTheme;
import com.island.qq.QqNotificationMonitor;
import com.island.wechat.WechatNotificationMonitor;
import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.win32.W32APIOptions;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 云隙泡应用启动类。
 *
 * <h3>支持的 CLI 参数</h3>
 * <ul>
 *   <li>{@code --autostart} — 由开机自启触发，结合托盘最小化策略决定初始可见性</li>
 *   <li>{@code --minimized} — 启动后直接最小化到托盘</li>
 * </ul>
 *
 * <h3>启动流程</h3>
 * <ol>
 *   <li>单实例锁（端口 {@value AppConstants#SINGLE_INSTANCE_PORT}）</li>
 *   <li>启动依赖守护进程（MediaInfoDaemon、ncm-server、qqmusic-api、qishui-api）</li>
 *   <li>初始化 UI 与各监控模块</li>
 * </ol>
 */
public class IslandApplication {

    private static ServerSocket instanceLock;

    /** 由本应用拉起的守护进程，退出时统一回收（防进程泄漏） */
    private static final List<Process> STARTED_DAEMONS = new ArrayList<>();

    /** winmm 高精度定时器：把 Swing 动画帧率从 Windows 默认 15.6ms 粒度恢复到设计值 */
    private interface Winmm extends Library {
        Winmm INSTANCE = Native.load("winmm", Winmm.class, W32APIOptions.DEFAULT_OPTIONS);
        int timeBeginPeriod(int periodMs);
        int timeEndPeriod(int periodMs);
    }

    public static void main(String[] args) {
        // ── 0. 锁定默认区域为简体中文（必须先于任何类加载：IslandWindow 的静态
        //        时间/日期 formatter 在类初始化时捕获 Locale.getDefault()，
        //        类加载晚于本行才会使用中文格式，否则日期会显示为英文）──
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE);

        // ── 0.05 HTTPS 信任链改用 Windows 根证书库（必须先于任何网络请求）──
        // 部分机器装有 HTTPS 加速/代理工具（如 SteamTools）对 GitHub 等域名做证书 MITM，
        // 其根证书已入 Windows 证书库（浏览器/PowerShell 访问正常）但不在 JVM 内置
        // cacerts 中，导致更新检测报 PKIX path building failed；Windows-ROOT 让 JVM 跟随
        // 系统信任链，一并解决。先探测 Windows-ROOT 可用再设置（jpackage 精简运行时
        // 若裁掉 jdk.crypto.mscapi 模块则不可用），探测失败保持默认信任库，
        // 否则默认 SSLContext 初始化抛异常导致启动崩溃。
        try {
            if (System.getProperty("javax.net.ssl.trustStoreType") == null) {
                KeyStore probe = KeyStore.getInstance("Windows-ROOT");
                probe.load(null, null);
                System.setProperty("javax.net.ssl.trustStoreType", "Windows-ROOT");
            }
        } catch (Exception ignored) {
        }

        // ── 0.1 启用 Per-Monitor V2 DPI 感知（必须在创建任何窗口之前调用，否则失效）──
        DpiUtil.enablePerMonitorDpi();

        // ── 0.5 启用 1ms 系统定时器粒度（动画帧率优化；退出时由关闭钩子恢复）──
        enableHighResolutionTimer();

        // ── 0.6 EDT 延迟探针（-Disland.edtProbe=true 开启，默认关闭零开销）──
        if (Boolean.getBoolean("island.edtProbe")) {
            startEdtLatencyProbe();
        }

        // ── 1. 单实例锁 ──
        if (!acquireInstanceLock()) {
            AppLogger.warn("IslandApplication", "已有实例在运行，退出。");
            System.exit(0);
        }

        // ── 2. CLI 参数 ──
        boolean autoStartFlag = false;
        boolean minimizeFlag = false;
        for (String arg : args) {
            if ("--autostart".equals(arg)) {
                autoStartFlag = true;
            } else if ("--minimized".equals(arg)) {
                minimizeFlag = true;
            }
        }
        final boolean autoStart = autoStartFlag;
        final boolean minimized = minimizeFlag;

        // ── 3. 启动守护进程 ──
        new Thread(IslandApplication::launchDaemons, "DaemonLauncher").start();

        // ── 3.1 开机自启：首次运行默认开启，此后尊重用户显式设置 ──
        new Thread(IslandApplication::ensureAutoStart, "AutoStartInit").start();

        // ── 4. UI 初始化 ──
        SwingUtilities.invokeLater(() -> {
            try {
                // 跟随 Windows 系统主题：深色用 FlatDarkLaf，浅色用 FlatLightLaf
                if (WindowsTheme.isDarkMode()) {
                    FlatDarkLaf.setup();
                } else {
                    FlatLightLaf.setup();
                }
            } catch (Exception e) {
                AppLogger.warn("IslandApplication", "设置 FlatLaf 主题失败", e);
            }

            IslandWindow island = new IslandWindow();

            // 初始化系统托盘
            SystemTrayManager trayManager = new SystemTrayManager(island);
            island.setTrayManager(trayManager);

            // 自动更新检测：后台延迟执行，发现新版本时托盘气泡提示（入口在 设置 → 更新）
            scheduleUpdateCheck(trayManager);

            // 初始化音乐监控（依赖 .NET 8 MediaInfoDaemon 后台运行）
            MusicMonitor musicMonitor = new MusicMonitor();
            island.setMusicMonitor(musicMonitor);

            // 初始化电池监控
            BatteryMonitor batteryMonitor = new BatteryMonitor();
            island.setBatteryMonitor(batteryMonitor);

            // 初始化摄像头/麦克风使用状态监控
            PrivacyMonitor privacyMonitor = new PrivacyMonitor();
            island.setPrivacyMonitor(privacyMonitor);

            // 初始化微信消息通知监控（依赖 WechatNotifyDaemon 后台运行）
            WechatNotificationMonitor wechatMonitor = new WechatNotificationMonitor();
            island.setWechatMonitor(wechatMonitor);

            // 初始化 QQ 消息通知监控（依赖 QqNotifyDaemon 后台运行，状态文件推送）
            QqNotificationMonitor qqMonitor = new QqNotificationMonitor();
            island.setQqMonitor(qqMonitor);

            // 初始定位按鼠标所在显示器居中（窗口初始隐藏，随首次触发重新定位）
            Rectangle screenBounds = ScreenUtil.getScreenBoundsAtMouse();
            int x = screenBounds.x + (screenBounds.width - island.getWidth()) / 2;
            int y = screenBounds.y;
            island.setLocation(x, y);

            // 默认隐藏到托盘（动态岛特性：鼠标靠近顶部才显示）
            // island.setVisible(true);

            // 窗口关闭监听器
            island.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override
                public void windowClosing(java.awt.event.WindowEvent windowEvent) {
                    releaseInstanceLock();
                    trayManager.dispose();
                    System.exit(0);
                }

                @Override
                public void windowIconified(java.awt.event.WindowEvent windowEvent) {
                    island.setVisible(false);
                }
            });
        });

        // ── JVM 关闭钩子 ──
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            releaseInstanceLock();
            destroyStartedDaemons();
            disableHighResolutionTimer();
            AppLogger.info("IslandApplication", "应用已退出。");
        }));
    }

    // ═══════════════════════════════════════════
    //  自动更新检测
    // ═══════════════════════════════════════════

    /**
     * 后台检查 GitHub Releases 最新版本：延迟 30s 执行，避开冷启动高峰与
     * 天气/守护进程的首次网络请求；同一版本仅提示一次（记录于 Preferences）。
     */

    private static void scheduleUpdateCheck(SystemTrayManager trayManager) {
        Thread checker = new Thread(() -> {
            try {
                Thread.sleep(30_000);
                UpdateChecker.UpdateInfo info = UpdateChecker.checkLatest();
                if (info == null || !AppVersion.isNewerThanCurrent(info.version())) {
                    return;
                }
                if (info.version().equals(AppConstants.getUpdateNotifiedVersion())) {
                    return;
                }
                AppConstants.setUpdateNotifiedVersion(info.version());
                AppLogger.info("IslandApplication", "发现新版本 v" + info.version() + "，已发送托盘通知");
                trayManager.notifyUpdateAvailable(info.version());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                AppLogger.warn("IslandApplication", "更新检测失败", e);
            }
        }, "UpdateCheck");
        checker.setDaemon(true);
        checker.start();
    }

    // ═══════════════════════════════════════════
    //  高精度定时器（动画帧率优化）
    // ═══════════════════════════════════════════

    /** 高精度定时器周期重申调度器（待机恢复后系统定时器粒度可能回退为 15.6ms） */
    private static ScheduledExecutorService hiResTimerKeeper;

    private static void enableHighResolutionTimer() {
        affirmHighResolutionTimer();
        // 周期重申：timeBeginPeriod 幂等且开销极小（单次 native 调用微秒级），
        // 覆盖"系统待机恢复后定时器粒度回退"场景；间隔取 5 秒，
        // 把恢复后到首次重申的盲区窗口压到最短（配合交互侧的即时重申双重保险）。
        hiResTimerKeeper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "HiResTimerKeeper");
            t.setDaemon(true);
            return t;
        });
        hiResTimerKeeper.scheduleWithFixedDelay(
                IslandApplication::affirmHighResolutionTimer, 5, 5, TimeUnit.SECONDS);
    }

    /**
     * 立即重申 1ms 系统定时器粒度（幂等，微秒级开销，任意线程可调用）。
     *
     * <p>粒度失效期间 {@code Thread.sleep(16)} 实际睡 ~31ms、{@code javax.swing.Timer}
     * 被钳到 15.6ms，动画与鼠标轮询同步变慢；因此除周期重申外，
     * 交互入口（鼠标监控切入快轮询的瞬间）也会调用本方法即时恢复，
     * 消除"待机恢复 → 用户首次交互"之间的感知延迟窗口。</p>
     */
    public static void affirmHighResolutionTimer() {
        try {
            Winmm.INSTANCE.timeBeginPeriod(1);
        } catch (Throwable ignored) {
        }
    }

    private static void disableHighResolutionTimer() {
        if (hiResTimerKeeper != null) {
            hiResTimerKeeper.shutdownNow();
            hiResTimerKeeper = null;
        }
        try {
            Winmm.INSTANCE.timeEndPeriod(1);
        } catch (Throwable ignored) { }
    }

    // ═══════════════════════════════════════
    //  EDT 延迟探针（验证用埋点，-Disland.edtProbe=true 开启）
    // ═══════════════════════════════════════

    /**
     * 每 250ms 向 EDT 投递一个空任务并测量派发延迟，每 60 秒输出 p50/p99/max。
     * 用于验证长时间运行与待机恢复后 EDT 无任务堆积（p99 持续 < 50ms 视为健康）。
     */
    private static void startEdtLatencyProbe() {
        ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "EdtLatencyProbe");
            t.setDaemon(true);
            return t;
        });
        exec.submit(() -> {
            long[] samples = new long[1024];
            int idx = 0;
            long windowStart = System.currentTimeMillis();
            while (true) {
                try {
                    long posted = System.nanoTime();
                    CountDownLatch latch = new CountDownLatch(1);
                    SwingUtilities.invokeLater(latch::countDown);
                    if (!latch.await(5, TimeUnit.SECONDS)) {
                        AppLogger.warn("EdtProbe", "EDT 任务 5 秒未被派发！");
                        continue;
                    }
                    samples[idx++ % samples.length] = (System.nanoTime() - posted) / 1_000_000;
                    long now = System.currentTimeMillis();
                    if (now - windowStart >= 60_000 && idx > 0) {
                        int n = Math.min(idx, samples.length);
                        long[] sorted = Arrays.copyOf(samples, n);
                        Arrays.sort(sorted);
                        AppLogger.info("EdtProbe", String.format(Locale.ROOT,
                                "60s窗口 EDT派发延迟 n=%d p50=%dms p99=%dms max=%dms",
                                n, sorted[n / 2], sorted[(int) (n * 0.99)], sorted[n - 1]));
                        idx = 0;
                        windowStart = now;
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });
    }

    // ═══════════════════════════════════════════
    //  单实例锁
    // ═══════════════════════════════════════════

    /**
     * 尝试获取单实例锁。
     * 通过绑定本地回环地址的固定端口来实现，失败说明已有实例在运行。
     *
     * @return {@code true} 成功获取锁（首个实例），{@code false} 已有实例
     */
    private static boolean acquireInstanceLock() {
        try {
            instanceLock = new ServerSocket(
                    AppConstants.SINGLE_INSTANCE_PORT,
                    0,
                    InetAddress.getLoopbackAddress());
            instanceLock.setReuseAddress(true);
            AppLogger.info("IslandApplication", "单实例锁已获取 (port="
                    + AppConstants.SINGLE_INSTANCE_PORT + ")");
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static void releaseInstanceLock() {
        if (instanceLock != null && !instanceLock.isClosed()) {
            try {
                instanceLock.close();
            } catch (IOException ignored) { }
        }
    }

    // ═══════════════════════════════════════════
    //  守护进程管理
    // ═══════════════════════════════════════════

    /**
     * 开机自启默认策略（异步执行，不阻塞启动）：
     * <ul>
     *   <li>首次运行（设置未落盘）：默认开启并注册开机自启项</li>
     *   <li>用户已开启但启动项缺失/失效（被手动删除等）：静默修复注册</li>
     *   <li>用户显式关闭过：尊重设置，不自动注册</li>
     * </ul>
     * 注册失败时开关状态按实际结果落盘，与设置页“以实际状态为准”的同步逻辑保持一致。
     */
    private static void ensureAutoStart() {
        try {
            if (!AppConstants.isAutoStartConfigured()) {
                // 首次运行：默认勾选开机自启
                try {
                    WindowsStartupManager.register();
                    AppConstants.setAutoStartEnabled(true);
                    AppLogger.info("IslandApplication", "首次运行，已默认开启开机自启");
                } catch (Exception e) {
                    AppConstants.setAutoStartEnabled(false);
                    AppLogger.warn("IslandApplication", "默认开机自启注册失败: " + e.getMessage());
                }
            } else if (AppConstants.isAutoStartEnabled() && !WindowsStartupManager.isRegistered()) {
                // 已开启但启动项缺失/失效 → 修复注册（如更新后快捷方式目标失效）
                try {
                    WindowsStartupManager.register();
                    AppLogger.info("IslandApplication", "开机自启项缺失，已重新注册");
                } catch (Exception e) {
                    AppLogger.warn("IslandApplication", "开机自启项修复失败: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            AppLogger.warn("IslandApplication", "开机自启检查异常", e);
        }
    }

    /**
     * 启动所有依赖守护进程。
     * 通过 {@code ProcessBuilder} 在后台启动，输出重定向到 daemon.log。
     * 如果进程已经在运行（端口被占用），重复启动会自然失败，不会影响使用。
     */
    private static void launchDaemons() {
        String baseDir = detectBaseDir();
        AppLogger.info("IslandApplication", "守护进程基准目录: " + baseDir);

        // ── MediaInfoDaemon (.NET 8 SMTC 媒体信息守护进程) ──
        File daemonExe = new File(baseDir, "MediaInfoDaemon.exe");
        if (daemonExe.exists()) {
            if (isProcessRunning("MediaInfoDaemon")) {
                AppLogger.info("IslandApplication", "MediaInfoDaemon 已在运行，跳过启动");
            } else {
                startDaemon(new ProcessBuilder(daemonExe.getAbsolutePath())
                        .directory(new File(baseDir)), "MediaInfoDaemon", baseDir);
            }
        } else {
            AppLogger.error("IslandApplication", "MediaInfoDaemon.exe 未找到: "
                    + daemonExe.getAbsolutePath());
        }

        // ── WechatNotifyDaemon (.NET 8 UserNotificationListener 微信通知守护进程) ──
        File wechatDaemonExe = new File(baseDir, "WechatNotifyDaemon.exe");
        if (wechatDaemonExe.exists()) {
            if (isProcessRunning("WechatNotifyDaemon")) {
                AppLogger.info("IslandApplication", "WechatNotifyDaemon 已在运行，跳过启动");
            } else {
                startDaemon(new ProcessBuilder(wechatDaemonExe.getAbsolutePath())
                        .directory(new File(baseDir)), "WechatNotifyDaemon", baseDir);
            }
        } else {
            // 微信提醒为非关键功能：缺失仅告警，不影响应用整体可用性
            AppLogger.warn("IslandApplication", "WechatNotifyDaemon.exe 未找到: "
                    + wechatDaemonExe.getAbsolutePath() + "（微信消息提醒不可用）");
        }

        // ── QqNotifyDaemon (.NET 8 WinRT 通知采集的 QQ 通知守护进程) ──
        File qqDaemonExe = new File(baseDir, "QqNotifyDaemon.exe");
        if (qqDaemonExe.exists()) {
            if (isProcessRunning("QqNotifyDaemon")) {
                AppLogger.info("IslandApplication", "QqNotifyDaemon 已在运行，跳过启动");
            } else {
                startDaemon(new ProcessBuilder(qqDaemonExe.getAbsolutePath())
                        .directory(new File(baseDir)), "QqNotifyDaemon", baseDir);
            }
        } else {
            // QQ 提醒为非关键功能：缺失仅告警，不影响应用整体可用性
            AppLogger.warn("IslandApplication", "QqNotifyDaemon.exe 未找到: "
                    + qqDaemonExe.getAbsolutePath() + "（QQ 消息提醒不可用）");
        }

        // ── ncm-server (网易云 API 代理，默认端口 3000) ──
        File ncmExe = new File(baseDir, "ncm-server.exe");
        if (ncmExe.exists()) {
            if (isPortInUse(3000)) {
                AppLogger.info("IslandApplication", "ncm-server 已在运行（端口 3000），跳过启动");
            } else {
                startDaemon(new ProcessBuilder(ncmExe.getAbsolutePath())
                        .directory(new File(baseDir)), "ncm-server", baseDir);
            }
        } else {
            AppLogger.error("IslandApplication", "ncm-server.exe 未找到: "
                    + ncmExe.getAbsolutePath());
        }

        // ── qqmusic-api (QQ音乐 API 代理，端口 3301；3300 让给 qishui-api 使用其默认端口) ──
        String nodeExe = AppConstants.findNodeExecutable();
        File qqmusicDir = new File(baseDir, "QQMusicapi");
        File serverJs = new File(qqmusicDir, "src" + File.separator + "server.js");
        if (nodeExe != null && qqmusicDir.isDirectory() && serverJs.exists()) {
            if (isPortInUse(3301)) {
                AppLogger.info("IslandApplication", "qqmusic-api 已在运行（端口 3301），跳过启动");
            } else {
                ProcessBuilder qqPb = new ProcessBuilder(nodeExe, "src" + File.separator + "server.js")
                        .directory(qqmusicDir);
                qqPb.environment().put("PORT", "3301");
                startDaemon(qqPb, "qqmusic-api", baseDir);
            }
        } else {
            if (nodeExe == null) {
                AppLogger.error("IslandApplication", "qqmusic-api 未能启动: "
                        + "未找到 Node.js，请安装 Node.js (>=18)");
            } else {
                AppLogger.error("IslandApplication", "qqmusic-api 未能启动: "
                        + "QQMusicapi 目录不完整");
            }
        }

        // ── qishui-api (汽水音乐 API 代理，默认端口 3300；qqmusic-api 已改让到 3301) ──
        File qishuiDir = new File(baseDir, "qishui-api");
        File qishuiAppJs = new File(qishuiDir, "app.js");
        if (nodeExe != null && qishuiDir.isDirectory() && qishuiAppJs.exists()) {
            if (isPortInUse(3300)) {
                AppLogger.info("IslandApplication", "qishui-api 已在运行（端口 3300），跳过启动");
            } else {
                ProcessBuilder qishuiPb = new ProcessBuilder(nodeExe, "app.js")
                        .directory(qishuiDir);
                startDaemon(qishuiPb, "qishui-api", baseDir);
            }
        } else {
            // 汽水音乐适配为非关键功能：未部署 qishui-api 目录时仅告警（歌词降级到 LRCLIB）
            AppLogger.warn("IslandApplication", "qishui-api 未部署或 Node.js 缺失: "
                    + qishuiDir.getAbsolutePath() + "（汽水音乐歌词/封面不可用）");
        }
    }

    /**
     * 拉起守护进程并登记（输出重定向到 daemon.log），供退出时统一回收。
     */
    private static void startDaemon(ProcessBuilder pb, String name, String baseDir) {
        try {
            Process p = pb
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(
                            new File(baseDir, "daemon.log")))
                    .start();
            synchronized (STARTED_DAEMONS) {
                STARTED_DAEMONS.add(p);
            }
            AppLogger.info("IslandApplication", name + " 已启动");
        } catch (IOException e) {
            AppLogger.error("IslandApplication", name + " 启动失败", e);
        }
    }

    /** 退出时回收由本应用拉起的守护进程（防止进程泄漏）。 */
    private static void destroyStartedDaemons() {
        synchronized (STARTED_DAEMONS) {
            for (Process p : STARTED_DAEMONS) {
                try {
                    if (p.isAlive()) p.destroy();
                } catch (Exception ignored) { }
            }
            STARTED_DAEMONS.clear();
        }
    }

    /** 按可执行文件名检测进程是否已在运行（无端口可查的 daemon 用）。 */
    private static boolean isProcessRunning(String exeName) {
        try {
            return ProcessHandle.allProcesses().anyMatch(ph -> {
                try {
                    String cmd = ph.info().command().orElse("");
                    return !cmd.isEmpty() && cmd.toLowerCase().contains(exeName.toLowerCase());
                } catch (Exception e) {
                    return false;
                }
            });
        } catch (Exception e) {
            return false;
        }
    }

    /** 探测本地端口是否已被占用（有端口协议的 daemon 用，比进程名更可靠）。 */
    private static boolean isPortInUse(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 300);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 探测应用基准目录（JAR 所在目录或工作目录）。
     * <p>IDE 环境（target/classes）下会向上查找包含 pom.xml 的项目根目录。</p>
     */
    private static String detectBaseDir() {
        // jpackage 打包运行：jpackage.app-path 指向应用 exe，守护进程部署在 exe 同目录
        String packagedApp = System.getProperty("jpackage.app-path");
        if (packagedApp != null && !packagedApp.isBlank()) {
            File exeDir = new File(packagedApp).getParentFile();
            if (exeDir != null) return exeDir.getAbsolutePath();
        }
        try {
            File codeLocation = new File(
                    IslandApplication.class.getProtectionDomain()
                            .getCodeSource().getLocation().toURI());
            // 若是目录（IDE 环境通常为 target/classes），从父级开始探测
            File dir = codeLocation.isDirectory() ? codeLocation : codeLocation.getParentFile();
            while (dir != null) {
                // 以 pom.xml 存在作为项目根目录的标识
                if (new File(dir, "pom.xml").exists()) {
                    return dir.getAbsolutePath();
                }
                dir = dir.getParentFile();
            }
        } catch (Exception ignored) { }
        return System.getProperty("user.dir");
    }
}
