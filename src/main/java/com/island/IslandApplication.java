package com.island;

import com.island.island.ui.IslandWindow;
import com.island.music.MusicMonitor;
import com.island.tray.SystemTrayManager;

import javax.swing.*;
import java.awt.*;

/**
 * 云隙泡应用启动类
 */
public class IslandApplication {

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception e) {
                e.printStackTrace();
            }

            IslandWindow island = new IslandWindow();

            // 初始化系统托盘
            SystemTrayManager trayManager = new SystemTrayManager(island);

            // 让 IslandWindow 能调用动画
            island.setTrayManager(trayManager);

            // 初始化音乐监控（依赖 .NET 8 MediaInfoDaemon 后台运行）
            MusicMonitor musicMonitor = new MusicMonitor();
            island.setMusicMonitor(musicMonitor);

            Toolkit toolkit = Toolkit.getDefaultToolkit();
            Dimension screenSize = toolkit.getScreenSize();
            int x = (screenSize.width - island.getWidth()) / 2;
            int y = 0; // 贴紧上边框
            island.setLocation(x, y);
            // 启动时不显示窗口，只显示托盘图标
            // island.setVisible(true);

            // 添加窗口关闭监听器，清理托盘图标
            island.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override
                public void windowClosing(java.awt.event.WindowEvent windowEvent) {
                    trayManager.dispose();
                    System.exit(0);
                }

                @Override
                public void windowIconified(java.awt.event.WindowEvent windowEvent) {
                    // 最小化时隐藏窗口
                    island.setVisible(false);
                }
            });
        });
    }
}
