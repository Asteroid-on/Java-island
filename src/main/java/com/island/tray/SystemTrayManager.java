package com.island.tray;

import com.island.config.AppConstants;
import com.island.island.model.IslandState;
import com.island.island.service.DynamicIslandService;
import com.island.island.service.impl.DynamicIslandServiceImpl;
import com.island.island.ui.IslandWindow;
import com.island.util.AnimationUtil;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import javax.swing.*;

/**
 * 系统托盘管理器 - 管理托盘图标、鼠标监听、动画控制
 */
@SuppressWarnings("this-escape")
public class SystemTrayManager {
    private SystemTray tray;
    private TrayIcon trayIcon;
    private final IslandWindow islandWindow;
    private final DynamicIslandService service;
    private MouseInfoMonitor mouseMonitor; // 鼠标监听器
    private JDialog popupDialog;          // 菜单的 JDialog 容器

    public SystemTrayManager(IslandWindow islandWindow) {
        this.islandWindow = islandWindow;
        this.service = DynamicIslandServiceImpl.getInstance();
        setupSystemTray();
        setupStateListener();
        setupMouseMonitor();
    }

    private void setupSystemTray() {
        if (!SystemTray.isSupported()) {
            System.out.println("系统不支持系统托盘，程序退出。");
            System.exit(0);
        }

        tray = SystemTray.getSystemTray();
        Image image = createDefaultIcon();

        // 不使用 AWT PopupMenu（原生渲染中文乱码），改用 Swing JPopupMenu
        trayIcon = new TrayIcon(image, service.getConfig().title, null);
        trayIcon.setImageAutoSize(true);

        trayIcon.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                // 左键或点击时先关闭弹出菜单
                hidePopup();
                if (e.getButton() == MouseEvent.BUTTON1) {
                    toggleIslandWindow();
                } else if (e.getButton() == MouseEvent.BUTTON3) {
                    showPopupMenu();
                }
            }
        });

        trayIcon.addActionListener(e -> {
            hidePopup();
            toggleIslandWindow();
        });

        try {
            tray.add(trayIcon);
        } catch (AWTException e) {
            System.err.println("无法将图标添加到系统托盘。");
            e.printStackTrace();
        }
    }

    private void showPopupMenu() {
        hidePopup(); // 先关掉旧的
        popupDialog = new JDialog();
        popupDialog.setUndecorated(true);
        popupDialog.setAlwaysOnTop(true);
        popupDialog.setModal(false);
        popupDialog.setFocusableWindowState(true);
        popupDialog.setType(Window.Type.POPUP);

        // 手绘面板 — 暗色背景、居中文案、精准尺寸
        final int MENU_W = 70;
        final int MENU_H = 26;
        JPanel panel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2d = (Graphics2D) g;
                g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

                // 背景
                g2d.setColor(new Color(50, 50, 50));
                g2d.fillRect(0, 0, getWidth(), getHeight());

                // 边框
                g2d.setColor(new Color(70, 70, 70));
                g2d.drawRect(0, 0, getWidth() - 1, getHeight() - 1);

                // 居中文字
                g2d.setColor(Color.WHITE);
                g2d.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
                FontMetrics fm = g2d.getFontMetrics();
                String text = "\u9000\u51FA";
                int tw = fm.stringWidth(text);
                int th = fm.getAscent();
                g2d.drawString(text, (getWidth() - tw) / 2, (getHeight() + th) / 2 - 1);
            }
        };
        panel.setPreferredSize(new Dimension(MENU_W, MENU_H));
        panel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        panel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                hidePopup();
                dispose();
                System.exit(0);
            }
            @Override
            public void mouseEntered(MouseEvent e) {
                panel.setBackground(new Color(70, 70, 70));
                panel.repaint();
            }
            @Override
            public void mouseExited(MouseEvent e) {
                panel.setBackground(new Color(50, 50, 50));
                panel.repaint();
            }
        });

        popupDialog.add(panel);
        popupDialog.pack();

        // 鼠标位置定位
        Point mousePoint = MouseInfo.getPointerInfo().getLocation();
        popupDialog.setLocation(mousePoint.x - MENU_W, mousePoint.y - MENU_H - 5);

        // 失焦自动关闭
        popupDialog.addWindowFocusListener(new WindowAdapter() {
            public void windowLostFocus(WindowEvent e) {
                hidePopup();
            }
        });

        popupDialog.setVisible(true);
        popupDialog.toFront();
        popupDialog.requestFocus();
    }

    private void hidePopup() {
        if (popupDialog != null) {
            popupDialog.dispose();
            popupDialog = null;
        }
    }

    private Image createDefaultIcon() {
        int size = 32;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = image.createGraphics();

        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Draw island pill shape
        int pillW = 22;
        int pillH = 12;
        int pillX = (size - pillW) / 2;
        int pillY = (size - pillH) / 2;
        int arc = pillH;

        g2d.setColor(new Color(30, 30, 30, 220));
        g2d.fillRoundRect(pillX, pillY, pillW, pillH, arc, arc);

        // Draw a small dot/circle inside
        g2d.setColor(new Color(255, 255, 255, 180));
        int dotSize = 5;
        int dotX = (size - dotSize) / 2;
        int dotY = (size - dotSize) / 2;
        g2d.fillOval(dotX, dotY, dotSize, dotSize);

        g2d.dispose();
        return image;
    }

    private Point calculateIslandLocation() {
        return service.calculateLocation();
    }

    private void toggleIslandWindow() {
        // 扩展岛显示时，主岛不再响应
        if (islandWindow.isExpandedIslandVisible()) {
            return;
        }
        if (service.getState() == IslandState.VISIBLE ||
            service.getState() == IslandState.SHOWING) {
            service.hide();
            animateHide();
        } else {
            // 显示前立即恢复时间/日期标签，消除显示延迟
            SwingUtilities.invokeLater(() -> islandWindow.restoreTimeDisplay());
            service.show();
            animateShow();
        }
    }

    /**
     * 设置状态监听器
     */
    private void setupStateListener() {
        service.addStateListener((oldState, newState) -> {
            System.out.println("状态变化: " + oldState + " -> " + newState);
        });
    }

    /**
     * 设置鼠标监听器
     */
    private void setupMouseMonitor() {
        mouseMonitor = new MouseInfoMonitor();
        mouseMonitor.start();
    }

    /**
     * 鼠标信息监听器 - 检测鼠标位置，控制岛的显示和隐藏
     */
    private class MouseInfoMonitor extends Thread {
        private volatile boolean running = true;

        public void run() {
            while (running) {
                try {
                    PointerInfo pointerInfo = MouseInfo.getPointerInfo();
                    if (pointerInfo != null) {
                        Point mouseLocation = pointerInfo.getLocation();

                        // 获取云隙泡的位置和大小
                        Point islandLocation = islandWindow.getLocation();
                        int islandWidth = islandWindow.getWidth();
                        int islandHeight = islandWindow.getHeight();

                        // 检测鼠标是否在云隙泡区域内
                        boolean isMouseOverIsland = (
                            mouseLocation.x >= islandLocation.x &&
                            mouseLocation.x <= islandLocation.x + islandWidth &&
                            mouseLocation.y >= islandLocation.y &&
                            mouseLocation.y <= islandLocation.y + islandHeight
                        );

                        // 检测鼠标是否在上边框触发区域内（限制在岛的宽度范围内）
                        boolean isNearTopEdge = (
                            mouseLocation.y <= AppConstants.TRIGGER_DISTANCE &&
                            mouseLocation.x >= islandLocation.x - 10 &&
                            mouseLocation.x <= islandLocation.x + islandWidth + 10
                        );

                        // 扩展岛显示时，主岛不再响应鼠标触发
                        if (islandWindow.isExpandedIslandVisible()) {
                            Thread.sleep(AppConstants.HIDE_CHECK_INTERVAL);
                            continue;
                        }

                        // 逻辑：鼠标靠近上边框或在岛上时显示，否则隐藏
                        if ((isNearTopEdge || isMouseOverIsland) &&
                            service.getState() == IslandState.HIDDEN) {
                            System.out.println("鼠标靠近上边框或在岛上，显示云隙泡");
                            // 立即恢复时间/日期标签，消除显示延迟
                            SwingUtilities.invokeLater(() -> islandWindow.restoreTimeDisplay());
                            service.show();
                            animateShow();
                        } else if (!isNearTopEdge && !isMouseOverIsland &&
                                   service.getState() == IslandState.VISIBLE &&
                                   !islandWindow.isShowingNotification()) {
                            System.out.println("鼠标离开触发区域和岛，隐藏云隙泡");
                            service.hide();
                            animateHide();
                        }
                    }

                    Thread.sleep(AppConstants.HIDE_CHECK_INTERVAL);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }

        public void stopMonitor() {
            running = false;
        }
    }

    public void animateShow() {
        Point location = calculateIslandLocation();
        int targetWidth = AppConstants.DEFAULT_WIDTH;
        int targetHeight = AppConstants.DEFAULT_HEIGHT;
        int ballSize = AppConstants.BALL_SIZE;

        System.out.println("开始展开动画，目标位置: " + location);

        // 第一阶段：从小球开始（完全隐藏在顶部）
        islandWindow.setLocation(location.x + (targetWidth - ballSize) / 2, location.y - ballSize);
        islandWindow.setSize(ballSize, ballSize);
        islandWindow.setVisible(true);
        islandWindow.toFront();

        // 使用定时器实现两阶段动画 - 120fps极致帧率
        javax.swing.Timer timer = new javax.swing.Timer(AppConstants.ANIMATION_FRAME_INTERVAL, null);
        final int[] phase = {0}; // 0=向下移动, 1=展开
        final double[] progress = {0.0};
        final double durationPhase1 = AppConstants.ANIMATION_DURATION_PHASE1;
        final double durationPhase2 = AppConstants.ANIMATION_DURATION_PHASE2;

        timer.addActionListener(e -> {
            if (phase[0] == 0) {
                // 阶段1：小球向下移动到目标位置
                progress[0] += 1.0 / durationPhase1;
                if (progress[0] >= 1.0) {
                    progress[0] = 1.0;
                    phase[0] = 1; // 进入下一阶段
                    progress[0] = 0.0; // 重置进度
                }

                double eased = AnimationUtil.linear(progress[0]);
                int currentY = (int)(location.y - ballSize + (ballSize * eased));

                islandWindow.setLocation(location.x + (targetWidth - ballSize) / 2, currentY);
                islandWindow.repaint();

            } else if (phase[0] == 1) {
                // 阶段2：从中心向两边展开
                progress[0] += 1.0 / durationPhase2;
                if (progress[0] >= 1.0) {
                    progress[0] = 1.0;
                }

                double eased = AnimationUtil.linear(progress[0]);

                int currentWidth = (int)(ballSize + (targetWidth - ballSize) * eased);
                int currentHeight = (int)(ballSize + (targetHeight - ballSize) * eased);

                // 保持中心点不变
                int newX = location.x + (targetWidth - currentWidth) / 2;
                int newY = location.y + (targetHeight - currentHeight) / 2;

                islandWindow.setSize(currentWidth, currentHeight);
                islandWindow.setLocation(newX, newY);
                islandWindow.repaint();

                if (progress[0] >= 1.0) {
                    timer.stop();
                    islandWindow.setSize(targetWidth, targetHeight);
                    islandWindow.setLocation(location.x, location.y);
                    service.onAnimationComplete();
                    System.out.println("动画完成");
                }
            }
        });
        timer.setInitialDelay(0);
        timer.start();
    }

    public void animateHide() {
        Point currentLocation = islandWindow.getLocation();
        int currentWidth = islandWindow.getWidth();
        int currentHeight = islandWindow.getHeight();
        int ballSize = AppConstants.BALL_SIZE;

        // 捕获岛屿当前实际中心点，避免每帧重算导致中心漂移
        int centerX = currentLocation.x + currentWidth / 2;
        int centerY = currentLocation.y + currentHeight / 2;

        System.out.println("开始收缩动画，中心点: (" + centerX + ", " + centerY + ")");

        javax.swing.Timer timer = new javax.swing.Timer(AppConstants.ANIMATION_FRAME_INTERVAL, null);
        final int[] phase = {0}; // 0=收束成球, 1=向上移动
        final double[] progress = {0.0};
        final double durationPhase1 = AppConstants.ANIMATION_DURATION_PHASE2;
        final double durationPhase2 = AppConstants.ANIMATION_DURATION_PHASE1;

        timer.addActionListener(e -> {
            if (phase[0] == 0) {
                // 阶段1：从两边向中心收束成小球
                progress[0] += 1.0 / durationPhase1;
                if (progress[0] >= 1.0) {
                    progress[0] = 1.0;
                }

                double eased = AnimationUtil.linear(progress[0]);

                int newWidth = (int)(currentWidth - (currentWidth - ballSize) * eased);
                int newHeight = (int)(currentHeight - (currentHeight - ballSize) * eased);

                if (newWidth < ballSize) newWidth = ballSize;
                if (newHeight < ballSize) newHeight = ballSize;

                // 保持岛屿实际中心点不变，确保左右等速收缩
                int newX = centerX - newWidth / 2;
                int newY = centerY - newHeight / 2;

                islandWindow.setSize(newWidth, newHeight);
                islandWindow.setLocation(newX, newY);
                islandWindow.repaint();

                if (progress[0] >= 1.0) {
                    phase[0] = 1;
                    progress[0] = 0.0;
                }

            } else if (phase[0] == 1) {
                // 阶段2：小球向上移动并消失
                progress[0] += 1.0 / durationPhase2;
                if (progress[0] >= 1.0) {
                    progress[0] = 1.0;
                }

                double eased = AnimationUtil.linear(progress[0]);

                Point location = calculateIslandLocation();
                int targetY = location.y - ballSize;
                int currentY = (int)(location.y + (targetY - location.y) * eased);

                islandWindow.setLocation(location.x + (AppConstants.DEFAULT_WIDTH - ballSize) / 2, currentY);
                islandWindow.repaint();

                if (progress[0] >= 1.0) {
                    timer.stop();
                    islandWindow.setVisible(false);
                    islandWindow.setHiding(false);
                    service.onAnimationComplete();
                    System.out.println("隐藏完成");
                }
            }
        });
        timer.setInitialDelay(0);
        timer.start();
    }

    public void dispose() {
        // 关闭弹出菜单
        hidePopup();

        // 隐藏并销毁岛屿窗口
        if (islandWindow != null) {
            islandWindow.setVisible(false);
            islandWindow.dispose();
        }

        // 停止鼠标监听器
        if (mouseMonitor != null) {
            mouseMonitor.running = false;
            mouseMonitor.interrupt();
        }

        // 移除系统托盘图标
        if (tray != null && trayIcon != null) {
            tray.remove(trayIcon);
        }
    }
}
