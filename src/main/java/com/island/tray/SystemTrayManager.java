package com.island.tray;

import com.island.config.AppConstants;
import com.island.island.model.IslandState;
import com.island.island.service.DynamicIslandService;
import com.island.island.service.impl.DynamicIslandServiceImpl;
import com.island.island.ui.IslandWindow;
import com.island.island.ui.SettingsDialog;
import com.island.util.AnimationUtil;
import com.island.util.AppLogger;
import com.island.util.SvgIcon;

import java.awt.*;
import java.awt.event.*;
import java.awt.geom.RoundRectangle2D;
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

    /** 菜单字体（黑体，13pt） */
    private static final Font MENU_FONT = new Font("SimHei", Font.PLAIN, 13);

    public SystemTrayManager(IslandWindow islandWindow) {
        this.islandWindow = islandWindow;
        this.service = DynamicIslandServiceImpl.getInstance();
        setupSystemTray();
        setupStateListener();
        setupMouseMonitor();
    }

    private void setupSystemTray() {
        if (!SystemTray.isSupported()) {
            AppLogger.error("SystemTray", "系统不支持系统托盘，程序退出");
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
            AppLogger.error("SystemTray", "托盘图标添加失败", e);
        }
    }

    private void showPopupMenu() {
        hidePopup();
        popupDialog = new JDialog();
        popupDialog.setUndecorated(true);
        popupDialog.setAlwaysOnTop(true);
        popupDialog.setModal(false);
        popupDialog.setFocusableWindowState(true);
        popupDialog.setType(Window.Type.POPUP);

        final int MENU_W = 100;
        final int ITEM_H = 32;
        final int RADIUS = 10;
        final int OUTER_PAD = 8; // 外圈透明边，模拟阴影
        final Color BG_NORMAL = new Color(45, 45, 48);  // #2D2D30
        final Color BG_HOVER  = new Color(61, 61, 64);  // hover 略亮
        final Color FG_TEXT    = new Color(235, 235, 235); // 浅色文字
        final Color BORDER_COLOR = new Color(62, 62, 67);

        // 圆角容器面板，重写 paint 以裁剪子组件到圆角区域
        JPanel panel = new JPanel() {
            @Override public void paint(Graphics g) {
                Graphics2D g2d = (Graphics2D) g;
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                // 背景
                g2d.setColor(BG_NORMAL);
                g2d.fillRoundRect(0, 0, getWidth(), getHeight(), RADIUS * 2, RADIUS * 2);
                // 裁剪子组件到圆角区域
                Shape oldClip = g2d.getClip();
                Shape clip = new RoundRectangle2D.Double(0, 0, getWidth(), getHeight(), RADIUS * 2, RADIUS * 2);
                g2d.setClip(clip);
                super.paintChildren(g);
                // 边框
                g2d.setColor(BORDER_COLOR);
                g2d.setStroke(new BasicStroke(1f));
                g2d.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, RADIUS * 2, RADIUS * 2);
                g2d.setClip(oldClip);
            }
        };
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));

        // ── "设置" 项 ──
        Image settingsIcon = SvgIcon.load("/icons/设置_setting-two.svg", 40, FG_TEXT);
        MenuItem settingsItem = new MenuItem("设  置", MENU_W, ITEM_H, BG_NORMAL, BG_HOVER, FG_TEXT, MENU_FONT, settingsIcon, 20);
        settingsItem.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                hidePopup();
                SwingUtilities.invokeLater(() -> {
                    SettingsDialog dialog = new SettingsDialog(null,
                            () -> islandWindow.getLyricsService().reinitCache());
                    dialog.setVisible(true);
                });
            }
        });
        panel.add(settingsItem);

        // ── 分隔线（自定义绘制，避免 JSeparator 忽略 setForeground） ──
        JPanel sep2 = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2d = (Graphics2D) g;
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2d.setColor(new Color(120, 120, 125));
                int midY = getHeight() / 2;
                g2d.drawLine(8, midY, getWidth() - 8, midY);
            }
        };
        sep2.setPreferredSize(new Dimension(MENU_W, 8));
        sep2.setOpaque(false);
        panel.add(sep2);

        // ── "退出" 项 ──
        Image exitIcon = SvgIcon.load("/icons/退出_logout.svg", 40, FG_TEXT);
        MenuItem exitItem = new MenuItem("退  出", MENU_W, ITEM_H, BG_NORMAL, BG_HOVER, FG_TEXT, MENU_FONT, exitIcon, 20);
        exitItem.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                hidePopup();
                dispose();
                System.exit(0);
            }
        });
        panel.add(exitItem);

        // ── 外层透明根面板：四周留出 OUTER_PAD 透明边，模拟阴影/发光 ──
        JPanel rootPanel = new JPanel(new BorderLayout());
        rootPanel.setOpaque(false);
        rootPanel.setBorder(BorderFactory.createEmptyBorder(OUTER_PAD, OUTER_PAD, OUTER_PAD, OUTER_PAD));
        rootPanel.add(panel, BorderLayout.CENTER);

        popupDialog.add(rootPanel);
        popupDialog.pack();

        // 透明背景让窗口四周不可见，内层面板抗锯齿圆角渲染呈现在透明基底上
        popupDialog.setBackground(new Color(0, 0, 0, 0));

        // 定位：鼠标右上方，边界检测防止溢出
        Point mousePoint = MouseInfo.getPointerInfo().getLocation();
        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        int menuW = panel.getPreferredSize().width;
        int menuH = panel.getPreferredSize().height;
        int x = mousePoint.x + 5;
        int y = mousePoint.y - menuH - 5;
        if (x + menuW > screenSize.width)  x = mousePoint.x - menuW - 5;
        if (y < 0) y = mousePoint.y + 5;
        if (y + menuH > screenSize.height) y = screenSize.height - menuH;
        popupDialog.setLocation(x, y);

        popupDialog.addWindowFocusListener(new WindowAdapter() {
            public void windowLostFocus(WindowEvent e) { hidePopup(); }
        });

        popupDialog.setVisible(true);
        popupDialog.toFront();
        popupDialog.requestFocus();
    }

    // ── 菜单项组件（自定义绘制，支持图标） ──
    private static class MenuItem extends JPanel {
        private final Color normalBg, hoverBg;
        private final String text;
        private final Font font;
        private final Image icon;
        private final int iconDisplaySize;
        private boolean hover;

        MenuItem(String text, int w, int h, Color normalBg, Color hoverBg, Color fg, Font font) {
            this(text, w, h, normalBg, hoverBg, fg, font, null, 0);
        }

        MenuItem(String text, int w, int h, Color normalBg, Color hoverBg, Color fg, Font font, Image icon, int iconDisplaySize) {
            this.text = text;
            this.normalBg = normalBg;
            this.hoverBg = hoverBg;
            this.font = font;
            this.icon = icon;
            this.iconDisplaySize = iconDisplaySize;
            setPreferredSize(new Dimension(w, h));
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setForeground(fg);
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override
                public void mouseExited(MouseEvent e) { hover = false; repaint(); }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2d = (Graphics2D) g;
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            if (hover) {
                g2d.setColor(hoverBg);
                g2d.fillRoundRect(3, 2, getWidth() - 6, getHeight() - 4, 8, 8);
            }
            g2d.setColor(getForeground());
            g2d.setFont(font);
            FontMetrics fm = g2d.getFontMetrics();

            if (icon != null && iconDisplaySize > 0) {
                int isz = iconDisplaySize;
                int iconY = (getHeight() - isz) / 2;
                int tw = fm.stringWidth(text);
                int totalW = isz + 6 + tw;
                int startX = (getWidth() - totalW) / 2;
                g2d.drawImage(icon, startX, iconY, isz, isz, null);
                g2d.drawString(text, startX + isz + 6, (getHeight() + fm.getAscent()) / 2 - 1);
            } else {
                int tw = fm.stringWidth(text);
                g2d.drawString(text, (getWidth() - tw) / 2, (getHeight() + fm.getAscent()) / 2 - 1);
            }
        }
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
            AppLogger.info("DynamicIsland", "状态变化: " + oldState + " -> " + newState);
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
