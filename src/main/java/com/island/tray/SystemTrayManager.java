package com.island.tray;

import com.island.config.AppConstants;
import com.island.island.model.IslandState;
import com.island.island.service.DynamicIslandService;
import com.island.island.service.impl.DynamicIslandServiceImpl;
import com.island.island.ui.IslandWindow;
import com.island.island.ui.SettingsDialog;
import com.island.util.AnimationUtil;
import com.island.util.AppLogger;
import com.island.util.ScreenUtil;
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
    private AWTEventListener globalClickListener; // 全局鼠标监听：点击菜单外部时自动关闭菜单
    private SettingsDialog settingsDialog; // 设置窗口唯一实例（单例激活语义）

    /** 主岛显示/隐藏动画唯一定时器（EDT 上创建/停止）：重复触发去重，反向触发先终止旧动画 */
    private javax.swing.Timer showHideAnimTimer;
    private volatile boolean showHideAnimRunning = false;
    private volatile boolean showHideAnimToVisible = false;

    /** 菜单字体（黑体，13pt） */
    private static final Font MENU_FONT = new Font("SimHei", Font.PLAIN, 13);

    public SystemTrayManager(IslandWindow islandWindow) {
        this.islandWindow = islandWindow;
        this.service = DynamicIslandServiceImpl.getInstance();
        setupSystemTray();
        setupStateListener();
        setupMouseMonitor();
        registerGlobalClickListener();
    }

    private void setupSystemTray() {
        if (!SystemTray.isSupported()) {
            AppLogger.error("SystemTray", "系统不支持系统托盘，程序退出");
            System.exit(0);
        }

        tray = SystemTray.getSystemTray();
        Image image = createDefaultIcon();

        // 不使用 AWT PopupMenu（原生渲染中文乱码），改用 Swing JPopupMenu
        trayIcon = new TrayIcon(image, "云隙泡(Java-island)", null);
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
        // 设置窗口是模态对话框：托盘菜单必须豁免应用级模态阻塞，
        // 否则设置窗口打开期间菜单无法获得焦点、点击外部不会触发 windowLostFocus 导致菜单残留
        popupDialog.setModalExclusionType(Dialog.ModalExclusionType.APPLICATION_EXCLUDE);
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
            public void mousePressed(MouseEvent e) {
                // 用 mousePressed 而非 mouseClicked：先于 windowLostFocus 的延迟销毁执行，
                // 避免主岛 toFront 抢焦点导致菜单提前 dispose、点击事件被吞
                hidePopup();
                SwingUtilities.invokeLater(SystemTrayManager.this::showOrActivateSettings);
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
            public void mousePressed(MouseEvent e) {
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

        // 定位：鼠标附近（优先右上方），基于鼠标所在屏幕的可视区域做边界修正，避免溢出屏幕
        Point mousePoint = MouseInfo.getPointerInfo().getLocation();
        Dimension dlgSize = popupDialog.getSize(); // pack 后含外圈透明边的实际尺寸
        Rectangle usable = getUsableScreenBounds(mousePoint);

        int x = mousePoint.x + 5;
        if (x + dlgSize.width > usable.x + usable.width) x = mousePoint.x - dlgSize.width - 5;
        int y = mousePoint.y - dlgSize.height - 5;
        if (y < usable.y) y = mousePoint.y + 5;
        x = Math.max(usable.x, Math.min(x, usable.x + usable.width - dlgSize.width));
        y = Math.max(usable.y, Math.min(y, usable.y + usable.height - dlgSize.height));
        popupDialog.setLocation(x, y);

        popupDialog.addWindowFocusListener(new WindowAdapter() {
            @Override
            public void windowLostFocus(WindowEvent e) {
                // 延迟 200ms 关闭：给菜单项 mousePressed 留出派发时间，
                // 避免焦点被主岛 toFront 抢走时菜单在点击前被销毁
                JDialog lostDialog = popupDialog;
                Timer closeTimer = new Timer(200, ev -> {
                    // 实例校验：若期间菜单项动作已触发（popupDialog 已置空）则不重复关闭；
                    // 若用户已重新打开新菜单也不误关
                    if (popupDialog == lostDialog) {
                        hidePopup();
                    }
                });
                closeTimer.setRepeats(false);
                closeTimer.start();
            }
        });

        popupDialog.setVisible(true);
        popupDialog.toFront();
        popupDialog.requestFocus();
    }

    /**
     * 获取屏幕点所在屏幕的可视区域（扣除任务栏等屏幕内边距）。
     */
    private Rectangle getUsableScreenBounds(Point screenPoint) {
        GraphicsDevice[] devices =
                GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
        for (GraphicsDevice device : devices) {
            GraphicsConfiguration gc = device.getDefaultConfiguration();
            Rectangle bounds = gc.getBounds();
            if (bounds.contains(screenPoint)) {
                Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(gc);
                return new Rectangle(
                        bounds.x + insets.left,
                        bounds.y + insets.top,
                        bounds.width - insets.left - insets.right,
                        bounds.height - insets.top - insets.bottom);
            }
        }
        // 兜底：鼠标不在任何屏幕内时使用主屏工作区
        return GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
    }

    /**
     * 注册全局鼠标监听：菜单显示期间，点击菜单外部即关闭菜单（仅关闭菜单本身）。
     * 与 windowLostFocus 互补，保证模态设置窗口打开时菜单也能正常自动关闭。
     */
    private void registerGlobalClickListener() {
        globalClickListener = event -> {
            if (popupDialog == null || !popupDialog.isShowing()) return;
            if (event instanceof MouseEvent me && me.getID() == MouseEvent.MOUSE_PRESSED) {
                Window clickedWindow = SwingUtilities.getWindowAncestor(me.getComponent());
                if (clickedWindow != popupDialog) {
                    hidePopup();
                }
            }
        };
        Toolkit.getDefaultToolkit().addAWTEventListener(
                globalClickListener, AWTEvent.MOUSE_EVENT_MASK);
    }

    /**
     * 打开设置窗口（单例激活语义，仅在 EDT 中调用）：
     * - 实例已存在且未销毁：置前并请求焦点；
     * - 实例不存在：创建并显示新窗口；窗口关闭（dispose）后清理引用，下次点击重新创建。
     */
    private void showOrActivateSettings() {
        // 引用失效校验：窗口被 dispose 后不再 displayable
        if (settingsDialog != null && !settingsDialog.isDisplayable()) {
            settingsDialog = null;
        }
        if (settingsDialog == null) {
            // 兜底：从应用现有窗口中找回已打开的设置窗口，避免重复创建
            for (Window w : Window.getWindows()) {
                if (w instanceof SettingsDialog d && d.isDisplayable()) {
                    settingsDialog = d;
                    break;
                }
            }
        }
        if (settingsDialog == null) {
            SettingsDialog dialog = new SettingsDialog(null,
                    () -> islandWindow.getLyricsService().reinitCache());
            dialog.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosed(WindowEvent e) {
                    // 关闭后清理引用，保证下次点击能正确重新创建
                    if (settingsDialog == dialog) {
                        settingsDialog = null;
                    }
                }
            });
            settingsDialog = dialog;
        }

        SettingsDialog dialog = settingsDialog;
        if (dialog.isVisible()) {
            // 已存在并可见：置前并请求焦点。
            // Windows 上 toFront 受前台窗口锁定经常失效，用 alwaysOnTop 弹跳强制置前
            dialog.setAlwaysOnTop(true);
            dialog.toFront();
            dialog.requestFocus();
            SwingUtilities.invokeLater(() -> dialog.setAlwaysOnTop(false));
        } else {
            // 新创建或隐藏的窗口：直接显示（新窗口自动置前并获得激活）；
            // 模态窗口的 setVisible(true) 会阻塞当前 EDT 事件直至窗口关闭，
            // 因此置前操作只能在“已可见”分支中执行，避免在已销毁的窗口上操作
            dialog.setVisible(true);
        }
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

    /**
     * 创建托盘图标。优先加载资源中的原图标（favicon 原图，128px），
     * 资源缺失时回退到代码绘制的胶囊形状。
     */
    private Image createDefaultIcon() {
        try {
            ImageIcon icon = new ImageIcon(getClass().getResource("/icons/tray-icon.png"));
            if (icon.getIconWidth() > 0 && icon.getIconHeight() > 0) {
                return icon.getImage();
            }
        } catch (Exception e) {
            AppLogger.warn("SystemTray", "加载托盘图标失败，回退到代码绘制", e);
        }

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

                        // 检测鼠标是否在所在屏幕的上边框触发区域内（多屏：以鼠标所在屏顶部为准，
                        // 水平范围取该屏内岛的居中位置附近，不依赖岛当前残留位置）
                        Rectangle triggerScreen = ScreenUtil.getScreenBoundsAt(mouseLocation);
                        int islandCenterX = triggerScreen.x + triggerScreen.width / 2;
                        boolean isNearTopEdge = (
                            mouseLocation.y <= triggerScreen.y + AppConstants.TRIGGER_DISTANCE &&
                            Math.abs(mouseLocation.x - islandCenterX) <= islandWidth / 2 + 10
                        );

                        // 扩展岛显示时，主岛不再响应鼠标触发
                        if (islandWindow.isExpandedIslandVisible()) {
                            Thread.sleep(AppConstants.HIDE_CHECK_INTERVAL);
                            continue;
                        }

                        // 逻辑：鼠标靠近上边框或在岛上时显示，否则隐藏
                        if ((isNearTopEdge || isMouseOverIsland) &&
                            service.getState() == IslandState.HIDDEN) {
                            if (AppConstants.DEBUG_CONSOLE) {
                                System.out.println("鼠标靠近上边框或在岛上，显示云隙泡");
                            }
                            // 立即恢复时间/日期标签，消除显示延迟
                            SwingUtilities.invokeLater(() -> islandWindow.restoreTimeDisplay());
                            service.show();
                            animateShow();
                        } else if (isNearTopEdge && !isMouseOverIsland &&
                                   service.getState() == IslandState.VISIBLE &&
                                   !islandWindow.isShowingNotification()) {
                            // 岛已显示但鼠标在其所在屏顶部而岛不在此屏：直接把岛切到鼠标所在屏
                            // （完整形态免动画；Swing 写操作切 EDT 执行）
                            Point expected = service.calculateLocation();
                            if (!islandWindow.getLocation().equals(expected)) {
                                SwingUtilities.invokeLater(() -> islandWindow.setLocation(expected));
                            }
                        } else if (!isNearTopEdge && !isMouseOverIsland &&
                                   service.getState() == IslandState.VISIBLE &&
                                   !islandWindow.isShowingNotification()) {
                            if (AppConstants.DEBUG_CONSOLE) {
                                System.out.println("鼠标离开触发区域和岛，隐藏云隙泡");
                            }
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

    /** 显示动画（线程安全：内部切 EDT 执行，重复触发去重，反向触发自动接续） */
    public void animateShow() {
        startShowHideAnimation(true);
    }

    /** 隐藏动画（线程安全：内部切 EDT 执行，重复触发去重，反向触发自动接续） */
    public void animateHide() {
        startShowHideAnimation(false);
    }

    /**
     * 主岛显示/隐藏动画统一入口。修复双 Timer 互搏与状态脱钩：
     * - 相同方向动画进行中：忽略重复触发（消除 100ms 鼠标巡检/托盘连点动画风暴）；
     * - 反向动画进行中：先终止旧动画，从当前实际几何状态反向接续；
     * - 动画完成回调统一对齐 service 状态机（SHOWING→VISIBLE / HIDING→HIDDEN）。
     */
    private void startShowHideAnimation(boolean toVisible) {
        SwingUtilities.invokeLater(() -> {
            if (showHideAnimRunning && showHideAnimToVisible == toVisible) {
                return;
            }
            stopShowHideAnimation();
            showHideAnimRunning = true;
            showHideAnimToVisible = toVisible;
            if (toVisible) {
                runShowAnimation();
            } else {
                runHideAnimation();
            }
        });
    }

    private void stopShowHideAnimation() {
        if (showHideAnimTimer != null) {
            showHideAnimTimer.stop();
            showHideAnimTimer = null;
        }
        showHideAnimRunning = false;
    }

    private void runShowAnimation() {
        Point location = calculateIslandLocation();
        int targetWidth = AppConstants.DEFAULT_WIDTH;
        int targetHeight = AppConstants.DEFAULT_HEIGHT;
        int ballSize = AppConstants.BALL_SIZE;

        // 已完整显示：仅对齐状态（含历史脱钩自愈），不重复播放动画
        if (islandWindow.isVisible()
                && islandWindow.getWidth() == targetWidth
                && islandWindow.getHeight() == targetHeight) {
            // 跨屏：岛已完整显示但位置与鼠标所在屏不一致（如状态脱钩后鼠标换屏触发），直接移动
            if (!islandWindow.getLocation().equals(location)) {
                islandWindow.setLocation(location);
            }
            service.show();
            service.onAnimationComplete();
            showHideAnimRunning = false;
            return;
        }

        if (AppConstants.DEBUG_CONSOLE) {
            System.out.println("开始展开动画，目标位置: " + location);
        }

        // 第一阶段：从小球开始（完全隐藏在顶部）
        islandWindow.setBounds(location.x + (targetWidth - ballSize) / 2,
                location.y - ballSize, ballSize, ballSize);
        islandWindow.setVisible(true);
        islandWindow.setHiding(false);
        islandWindow.toFront();

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

                islandWindow.setBounds(location.x + (targetWidth - ballSize) / 2,
                        currentY, ballSize, ballSize);
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

                islandWindow.setBounds(newX, newY, currentWidth, currentHeight);
                islandWindow.repaint();

                if (progress[0] >= 1.0) {
                    timer.stop();
                    showHideAnimTimer = null;
                    islandWindow.setBounds(location.x, location.y, targetWidth, targetHeight);
                    service.show();
                    service.onAnimationComplete();
                    showHideAnimRunning = false;
                    if (AppConstants.DEBUG_CONSOLE) {
                        System.out.println("动画完成");
                    }
                }
            }
        });
        showHideAnimTimer = timer;
        timer.setInitialDelay(0);
        timer.start();
    }

    private void runHideAnimation() {
        // 已隐藏：对齐状态（HIDING→HIDDEN，兼容历史脱钩残留），不播放动画
        if (!islandWindow.isVisible()) {
            service.hide();
            service.onAnimationComplete();
            showHideAnimRunning = false;
            return;
        }

        int ballSize = AppConstants.BALL_SIZE;
        Point currentLocation = islandWindow.getLocation();
        int currentWidth = islandWindow.getWidth();
        int currentHeight = islandWindow.getHeight();

        // 捕获岛屿当前实际中心点，避免每帧重算导致中心漂移
        int centerX = currentLocation.x + currentWidth / 2;
        int centerY = currentLocation.y + currentHeight / 2;

        // 隐藏滑出目标按岛当前所在显示器固定：动画期间鼠标跨屏移动不会改变滑出终点
        Rectangle hideScreen = ScreenUtil.getScreenBoundsAt(currentLocation);
        final int hideBaseX = hideScreen.x + (hideScreen.width - AppConstants.DEFAULT_WIDTH) / 2;
        final int hideBaseY = hideScreen.y;
        final int hideTargetY = hideBaseY - ballSize;

        if (AppConstants.DEBUG_CONSOLE) {
            System.out.println("开始收缩动画，中心点: (" + centerX + ", " + centerY + ")");
        }

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

                int newWidth = Math.max(ballSize, (int)(currentWidth - (currentWidth - ballSize) * eased));
                int newHeight = Math.max(ballSize, (int)(currentHeight - (currentHeight - ballSize) * eased));

                // 保持岛屿实际中心点不变，确保左右等速收缩
                int newX = centerX - newWidth / 2;
                int newY = centerY - newHeight / 2;

                islandWindow.setBounds(newX, newY, newWidth, newHeight);
                islandWindow.repaint();

                if (progress[0] >= 1.0) {
                    phase[0] = 1;
                    progress[0] = 0.0;
                }

            } else if (phase[0] == 1) {
                // 阶段2：小球向上移动并消失（目标固定在动画开始时捕获的所在屏）
                progress[0] += 1.0 / durationPhase2;
                if (progress[0] >= 1.0) {
                    progress[0] = 1.0;
                }

                double eased = AnimationUtil.linear(progress[0]);

                int currentY = (int)(hideBaseY + (hideTargetY - hideBaseY) * eased);

                islandWindow.setBounds(hideBaseX + (AppConstants.DEFAULT_WIDTH - ballSize) / 2,
                        currentY, ballSize, ballSize);
                islandWindow.repaint();

                if (progress[0] >= 1.0) {
                    timer.stop();
                    showHideAnimTimer = null;
                    islandWindow.setVisible(false);
                    islandWindow.setHiding(false);
                    // 通知收尾标志复位（通知结束路径走 animateHide 收尾时由该钩子复位）
                    islandWindow.onTrayHideAnimationFinished();
                    service.hide();
                    service.onAnimationComplete();
                    showHideAnimRunning = false;
                    if (AppConstants.DEBUG_CONSOLE) {
                        System.out.println("隐藏完成");
                    }
                }
            }
        });
        showHideAnimTimer = timer;
        timer.setInitialDelay(0);
        timer.start();
    }

    public void dispose() {
        // 关闭弹出菜单
        hidePopup();

        // 终止进行中的显示/隐藏动画，避免动画 Timer 在窗口销毁后继续驱动 EDT
        stopShowHideAnimation();

        // 注销全局鼠标监听
        if (globalClickListener != null) {
            Toolkit.getDefaultToolkit().removeAWTEventListener(globalClickListener);
            globalClickListener = null;
        }

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
