package com.island;

import com.island.config.AppConstants;
import com.island.island.ui.IslandWindow;
import com.island.music.MusicMonitor;
import com.island.tray.SystemTrayManager;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;

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
 *   <li>启动依赖守护进程（MediaInfoDaemon、ncm-server、qqmusic-api）</li>
 *   <li>初始化 UI 与各监控模块</li>
 * </ol>
 */
public class IslandApplication {

    private static ServerSocket instanceLock;

    public static void main(String[] args) {
        // ── 1. 单实例锁 ──
        if (!acquireInstanceLock()) {
            System.err.println("[IslandApplication] 已有实例在运行，退出。");
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

        // ── 4. UI 初始化 ──
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception e) {
                e.printStackTrace();
            }

            IslandWindow island = new IslandWindow();

            // 初始化系统托盘
            SystemTrayManager trayManager = new SystemTrayManager(island);
            island.setTrayManager(trayManager);

            // 初始化音乐监控（依赖 .NET 8 MediaInfoDaemon 后台运行）
            MusicMonitor musicMonitor = new MusicMonitor();
            island.setMusicMonitor(musicMonitor);

            Toolkit toolkit = Toolkit.getDefaultToolkit();
            Dimension screenSize = toolkit.getScreenSize();
            int x = (screenSize.width - island.getWidth()) / 2;
            int y = 0;
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
            System.out.println("[IslandApplication] 应用已退出。");
        }));
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
            System.out.println("[IslandApplication] 单实例锁已获取 (port="
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
     * 启动所有依赖守护进程。
     * 通过 {@code ProcessBuilder} 在后台启动，输出重定向到 daemon.log。
     * 如果进程已经在运行（端口被占用），重复启动会自然失败，不会影响使用。
     */
    private static void launchDaemons() {
        String baseDir = detectBaseDir();
        System.out.println("[IslandApplication] 守护进程基准目录: " + baseDir);

        // ── MediaInfoDaemon (.NET 8 SMTC 媒体信息守护进程) ──
        File daemonExe = new File(baseDir, "MediaInfoDaemon.exe");
        if (daemonExe.exists()) {
            try {
                new ProcessBuilder(daemonExe.getAbsolutePath())
                        .directory(new File(baseDir))
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.appendTo(
                                new File(baseDir, "daemon.log")))
                        .start();
                System.out.println("[IslandApplication] MediaInfoDaemon 已启动");
            } catch (IOException e) {
                System.err.println("[IslandApplication] MediaInfoDaemon 启动失败: "
                        + e.getMessage());
            }
        } else {
            System.err.println("[IslandApplication] MediaInfoDaemon.exe 未找到: "
                    + daemonExe.getAbsolutePath());
        }

        // ── ncm-server (网易云 API 代理) ──
        File ncmExe = new File(baseDir, "ncm-server.exe");
        if (ncmExe.exists()) {
            try {
                new ProcessBuilder(ncmExe.getAbsolutePath())
                        .directory(new File(baseDir))
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.appendTo(
                                new File(baseDir, "daemon.log")))
                        .start();
                System.out.println("[IslandApplication] ncm-server 已启动");
            } catch (IOException e) {
                System.err.println("[IslandApplication] ncm-server 启动失败: "
                        + e.getMessage());
            }
        } else {
            System.err.println("[IslandApplication] ncm-server.exe 未找到: "
                    + ncmExe.getAbsolutePath());
        }

        // ── qqmusic-api (QQ音乐 API 代理) ──
        String nodeExe = AppConstants.findNodeExecutable();
        File qqmusicDir = new File(baseDir, "QQMusicapi");
        File serverJs = new File(qqmusicDir, "src" + File.separator + "server.js");
        if (nodeExe != null && qqmusicDir.isDirectory() && serverJs.exists()) {
            try {
                new ProcessBuilder(nodeExe, "src" + File.separator + "server.js")
                        .directory(qqmusicDir)
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.appendTo(
                                new File(baseDir, "daemon.log")))
                        .start();
                System.out.println("[IslandApplication] qqmusic-api 已启动 (node="
                        + nodeExe + ")");
            } catch (IOException e) {
                System.err.println("[IslandApplication] qqmusic-api 启动失败: "
                        + e.getMessage());
            }
        } else {
            if (nodeExe == null) {
                System.err.println("[IslandApplication] qqmusic-api 未能启动: "
                        + "未找到 Node.js，请安装 Node.js (>=18)");
            } else {
                System.err.println("[IslandApplication] qqmusic-api 未能启动: "
                        + "QQMusicapi 目录不完整");
            }
        }
    }

    /**
     * 探测应用基准目录（JAR 所在目录或工作目录）。
     */
    private static String detectBaseDir() {
        try {
            File jarFile = new File(
                    IslandApplication.class.getProtectionDomain()
                            .getCodeSource().getLocation().toURI());
            String parent = jarFile.getParent();
            if (parent != null) {
                return parent;
            }
        } catch (Exception ignored) { }
        return System.getProperty("user.dir");
    }
}
