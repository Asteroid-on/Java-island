package com.island.island.ui;

import com.island.battery.BatteryMonitor;
import com.island.bluetooth.BluetoothMonitor;
import com.island.config.AppConstants;
import com.island.island.model.IslandConfig;
import com.island.island.service.DynamicIslandService;
import com.island.island.service.impl.DynamicIslandServiceImpl;
import com.island.island.ui.expanded.ExpandedIslandController;
import com.island.music.LyricsService;
import com.island.music.MusicMonitor;
import com.island.music.WindowsMediaManager;
import com.island.privacy.PrivacyMonitor;
import com.island.qq.QqNotificationMonitor;
import com.island.tray.SystemTrayManager;
import com.island.util.AppLogger;
import com.island.util.Win32WindowUtil;
import com.island.weather.HybridWeatherService;
import com.island.weather.WeatherIconMapper;
import com.island.weather.WeatherInfo;
import com.island.wifi.WifiMonitor;
import com.island.wechat.WechatNotification;
import com.island.wechat.WechatNotificationMonitor;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.IntByReference;

import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JWindow;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Robot;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Arc2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 动态岛主窗口（类似iOS动态岛的通知与时间显示）：
 * 负责时间/日期/天气显示、蓝牙/WiFi 通知动画，以及对外注入接口。
 * 扩展岛（展开大窗口、音乐面板、电池、设备占用指示）委托给
 * {@link ExpandedIslandController} 管理。
 */
@SuppressWarnings({"this-escape"})
public class IslandWindow extends JWindow implements Serializable, ExpandedIslandHost {

    private static final long serialVersionUID = 1L;

    // 时间/日期格式固定为简体中文，与 JVM 默认 Locale 脱钩：
    // 静态字段在类加载时一次性初始化，若此时 Locale.getDefault() 尚未被设置为中文
    // （如类加载时机早于 Locale.setDefault），日期会显示为英文 "Aug 25, 2026"
    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.SIMPLIFIED_CHINESE);
    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.SIMPLIFIED_CHINESE);

    private final transient DynamicIslandService service;
    private final transient ExpandedIslandController expandedController;

    private JLabel timeLabel;
    private JLabel dateLabel;
    private JLabel deviceNameLabel;
    private Timer clockTimer;
    private transient BluetoothMonitor bluetoothMonitor;
    private Timer notificationTimer;
    /** 通知收尾一次性定时器（持有引用：新通知到来或窗口销毁时能及时取消，避免旧定时器误收新通知） */
    private Timer finishNotificationTimer;
    private volatile boolean showingNotification = false;
    private volatile boolean showingWifiNotification = false;
    /** 当前正在展示的是否为微信消息通知（展示期间点击岛跳转微信，而非展开扩展岛） */
    private volatile boolean showingWechatNotification = false;
    /** 当前正在展示的是否为 QQ 消息通知（展示期间点击岛跳转 QQ，而非展开扩展岛） */
    private volatile boolean showingQqNotification = false;
    private transient SystemTrayManager trayManager;
    private transient WifiMonitor wifiMonitor;
        private transient HybridWeatherService weatherMonitor;
    private transient MusicMonitor musicMonitor;
    private transient BatteryMonitor batteryMonitor;
    private transient PrivacyMonitor privacyMonitor;
    private transient WechatNotificationMonitor wechatMonitor;
    private transient QqNotificationMonitor qqMonitor;

    private transient Image bluetoothIcon;
    private transient Image wifiIcon;
    private transient Image cameraInUseIcon;
    private transient Image micInUseIcon;
    /** 天气详情展开时右上角返回图标 */
    private transient Image returnIcon;
    /** 天气详情手动刷新按钮图标 */
    private transient Image refreshIcon;
    /** 微信图标（内置资源 icons/wechat.png，loadIcons 加载） */
    private transient Image wechatIcon;
    /** 微信图标 4x 高清副本（绘制时从高分辨率源缩小，边缘更锐利） */
    private transient Image wechatIconHq;
    /** QQ 图标（内置资源 icons/qq.png，loadIcons 加载） */
    private transient Image qqIcon;
    /** QQ 图标 4x 高清副本（绘制时从高分辨率源缩小，边缘更锐利） */
    private transient Image qqIconHq;
    /** 最近一条微信通知携带的微信 exe 路径（点击岛跳转微信用） */
    private volatile String wechatExePath = "";
    /** 跳转微信后的通知抑制截止时间（用户正在看微信时不重复弹窗） */
    private volatile long wechatLaunchSuppressUntilMs = 0;
    /** 微信连续消息去重/合并窗口：5 秒内最多弹一次，窗口过期后新消息重新允许弹窗 */
    private static final long WECHAT_NOTIFY_DEDUP_WINDOW_MS = 5_000L;
    /** 微信通知抑制/去重判定互斥锁（监听回调来自 daemon 监控线程） */
    private final transient Object wechatNotifyLock = new Object();
    /** 上次微信弹窗时间戳（epoch ms，对齐整秒）：用于去重窗口判定 */
    private long lastWechatNotifyShownMs = 0;
    /** 跳转 QQ 后的通知抑制截止时间（用户正在看 QQ 时不重复弹窗） */
    private volatile long qqLaunchSuppressUntilMs = 0;
    /** QQ 连续消息去重/合并窗口：5 秒内最多弹一次，窗口过期后新消息重新允许弹窗 */
    private static final long QQ_NOTIFY_DEDUP_WINDOW_MS = 5_000L;
    /** QQ 通知抑制/去重判定互斥锁（监听回调来自 daemon 监控线程） */
    private final transient Object qqNotifyLock = new Object();
    /** 上次 QQ 弹窗时间戳（epoch ms，对齐整秒）：用于去重窗口判定 */
    private long lastQqNotifyShownMs = 0;

    private JPanel animPanel;
    private JPanel weatherPanel;
    private JLabel weatherIconLabel;
    private JLabel weatherTempLabel;
    private JLabel weatherConditionLabel;
    private JPanel textPanel;
    private GridBagConstraints textConstraints;

    private Timer animTimer;
    private final Object notificationLock = new Object();

    private boolean isAnimating = false;
    private float animProgress = 0f;

    private volatile boolean isFinishingNotification = false;
    private volatile boolean isNotificationActive = false;
    private volatile boolean isHiding = false;

    public IslandWindow() {
        this.service = DynamicIslandServiceImpl.getInstance();
        this.expandedController = new ExpandedIslandController(this);
        initUI();
    }

    public void setTrayManager(SystemTrayManager trayManager) {
        if (trayManager == null) {
            throw new IllegalArgumentException("Tray manager cannot be null");
        }
        this.trayManager = trayManager;
    }

    /** 注入音乐监控器，由 IslandApplication 调用 */
    public void setMusicMonitor(MusicMonitor monitor) {
        if (monitor == null) return;
        this.musicMonitor = monitor;
        AppLogger.info("IslandWindow", "MusicMonitor 已注入，开始监听");
        if (!WindowsMediaManager.isDaemonRunning()) {
            AppLogger.error("IslandWindow", "MediaInfoDaemon 未运行，音乐监控将无数据");
        }
        monitor.setListener(info -> {
            // 高频回调（播放期间每 300ms 一次）：默认关闭输出，需诊断时用 -Disland.debug=true 开启
            if (AppConstants.DEBUG_CONSOLE) {
                System.out.println("[IslandWindow] MusicMonitor 回调: " + info);
            }
            SwingUtilities.invokeLater(() -> expandedController.onMusicInfoChanged(info));
        });
        monitor.start();
    }

    /** 注入电池监控器，由 IslandApplication 调用 */
    public void setBatteryMonitor(BatteryMonitor monitor) {
        if (monitor == null) return;
        this.batteryMonitor = monitor;
        AppLogger.info("IslandWindow", "BatteryMonitor 已注入，开始监听");
        monitor.setListener(info -> {
            SwingUtilities.invokeLater(() -> expandedController.onBatteryInfoChanged(info));
        });
        monitor.start();
    }

    /** 注入摄像头/麦克风使用状态监控器，由 IslandApplication 调用 */
    public void setPrivacyMonitor(PrivacyMonitor monitor) {
        if (monitor == null) return;
        this.privacyMonitor = monitor;
        AppLogger.info("IslandWindow", "PrivacyMonitor 已注入，开始监听");
        monitor.setListener((camera, mic) -> {
            long postedAt = System.nanoTime();
            SwingUtilities.invokeLater(() -> {
                long t0 = System.nanoTime();
                expandedController.onDeviceUsageChanged(camera, mic);
                // 定位埋点：岛内付出的成本（EDT 执行 + 排队）。若首次明显慢于后续
                // → 仍有未捂热的冷路径；若首次已与后续同为毫秒级但游戏仍卡顿
                // → 残余卡顿不在岛侧（麦克风激活瞬间的系统级事件）
                AppLogger.info("PrivacyMonitor", "设备占用回调链: EDT 执行 "
                        + (System.nanoTime() - t0) / 1_000_000 + "ms，排队 "
                        + (t0 - postedAt) / 1_000_000 + "ms (camera=" + camera + ", mic=" + mic + ")");
            });
        });
        monitor.start();
        schedulePrivacyCallbackWarmup();
    }

    /**
     * 首占用回调链预热：设备“空闲→占用”首次跳变会一次性付出整条链路的冷成本
     * （相关类初始化 + 解释执行 + EDT 冷唤醒 + 前台全屏检测里的 JNA/AWT-GDI 首调），
     * 游戏场景下即使扩展岛被全屏抑制拦住未弹，该突发仍会抢走游戏渲染线程的调度预算
     * （实测特征：每进程仅首次开语音卡顿，换新对局不复现；预热主体链路后卡顿变短但未消失，
     * 说明 firstUsage 上升沿分支体也是冷成本来源，一并补跑）。
     * 启动 3 秒后以“无状态变化”的合成回调 + 上升沿预热把全部一次性冷成本
     * 挪到无感知时刻，不触发任何日志/标志变更/动画/弹出。
     */
    private void schedulePrivacyCallbackWarmup() {
        Timer warmup = new Timer(3000, null);
        warmup.setRepeats(false);
        warmup.addActionListener(e -> {
            // EDT 预热抑制分支的两个前置件：前台全屏检测（JNA user32 + GraphicsEnvironment 屏幕枚举）
            Win32WindowUtil.isForegroundFullscreenWindow();
            // 预热 firstUsage 分支体：判定 getter + 指示器状态机上升沿（内部已按真实状态收敛）
            expandedController.warmUpFirstUsageEdges();
            // 预热 轮询线程→invokeLater→EDT 的 updateUsage 回调链（值与当前一致 → 全程无副作用）
            if (privacyMonitor != null) {
                privacyMonitor.resendCurrentState();
            }
        });
        warmup.start();
    }

    /** 注入微信消息通知监控器，由 IslandApplication 调用 */
    public void setWechatMonitor(WechatNotificationMonitor monitor) {
        if (monitor == null) return;
        this.wechatMonitor = monitor;
        AppLogger.info("IslandWindow", "WechatNotificationMonitor 已注入，开始监听微信消息");
        monitor.setListener(notification -> {
            // 微信 exe 路径由 daemon 采集；回调在工作线程，切 EDT 展示
            wechatExePath = notification.getWechatExe();
            // 设置中关闭微信消息弹窗后直接丢弃，不再展示
            if (!AppConstants.isWechatNotificationEnabled()) {
                return;
            }
            // 时间判定统一对齐整秒：避免毫秒边界抖动导致抑制窗口计算不准而漏弹/误弹
            long now = System.currentTimeMillis() / 1000 * 1000;
            // 抑制写入时间窗口：窗口内消息直接丢弃；截止时间是时间戳，到期后判定自然放行，
            // 不存在残留阻塞状态，窗口结束后的新消息立即允许弹窗
            long suppressUntil = wechatLaunchSuppressUntilMs;
            if (suppressUntil > 0 && now < suppressUntil) {
                AppLogger.info("IslandWindow", "微信通知丢弃：处于跳转微信后抑制窗口内（剩余 "
                        + (suppressUntil - now) / 1000 + " 秒）");
                return;
            }
            // 连续消息去重/合并：5 秒窗口内最多弹一次，后续连续消息不再重复弹窗；
            // 窗口过期后新消息到达则重新允许弹窗。被抑制丢弃的消息不计入弹窗时间。
            synchronized (wechatNotifyLock) {
                if (now - lastWechatNotifyShownMs < WECHAT_NOTIFY_DEDUP_WINDOW_MS) {
                    AppLogger.info("IslandWindow", "微信通知合并：处于连续消息 5 秒去重窗口内，不重复弹窗");
                    return;
                }
                lastWechatNotifyShownMs = now;
            }
            SwingUtilities.invokeLater(IslandWindow.this::showWechatNotification);
        });
        monitor.start();
    }

    /** 注入 QQ 消息通知监控器，由 IslandApplication 调用 */
    public void setQqMonitor(QqNotificationMonitor monitor) {
        if (monitor == null) return;
        this.qqMonitor = monitor;
        AppLogger.info("IslandWindow", "QqNotificationMonitor 已注入，开始监听 QQ 消息");
        monitor.setListener(notification -> {
            // 设置中关闭 QQ 消息弹窗后直接丢弃，不再展示
            if (!AppConstants.isQqNotificationEnabled()) {
                return;
            }
            // 时间判定统一对齐整秒：避免毫秒边界抖动导致抑制窗口计算不准而漏弹/误弹
            long now = System.currentTimeMillis() / 1000 * 1000;
            // 抑制写入时间窗口：跳转 QQ 后窗口内消息直接丢弃，到期自然放行
            long suppressUntil = qqLaunchSuppressUntilMs;
            if (suppressUntil > 0 && now < suppressUntil) {
                AppLogger.info("IslandWindow", "QQ 通知丢弃：处于跳转 QQ 后抑制窗口内（剩余 "
                        + (suppressUntil - now) / 1000 + " 秒）");
                return;
            }
            // 连续消息去重/合并：5 秒窗口内最多弹一次；被抑制丢弃的消息不计入弹窗时间
            synchronized (qqNotifyLock) {
                if (now - lastQqNotifyShownMs < QQ_NOTIFY_DEDUP_WINDOW_MS) {
                    AppLogger.info("IslandWindow", "QQ 通知合并：处于连续消息 5 秒去重窗口内，不重复弹窗");
                    return;
                }
                lastQqNotifyShownMs = now;
            }
            SwingUtilities.invokeLater(IslandWindow.this::showQqNotification);
        });
        monitor.start();
    }

    /** 供 SystemTrayManager 获取 LyricsService 引用。 */
    public LyricsService getLyricsService() {
        return expandedController.getLyricsService();
    }

    public boolean isShowingNotification() {
        return showingNotification;
    }

    public void setHiding(boolean hiding) {
        this.isHiding = hiding;
    }

    /** 扩展岛当前是否可见（供 SystemTrayManager 判断主岛是否响应鼠标） */
    public boolean isExpandedIslandVisible() {
        return expandedController.isVisible();
    }

    /** 扩展岛窗口（供 SystemTrayManager 在可见期间重申不抢焦点置顶；未创建时为 null） */
    public JWindow getExpandedWindow() {
        return expandedController.getExpandedWindow();
    }

    /** 扩展岛是否可见或处于展开/收起动画中（供 SystemTrayManager 全屏抑制判断） */
    public boolean isExpandedIslandVisibleOrAnimating() {
        return expandedController.isVisibleOrAnimating();
    }

    /** 全屏抑制：收起可见/动画中的扩展岛（供 SystemTrayManager 全屏检测切 EDT 调用） */
    public void collapseExpandedIslandForFullscreen() {
        expandedController.collapseByFullscreen();
    }

    public void restoreTimeDisplay() {
        isHiding = false;
        isFinishingNotification = false;

        // 通知展示期间不覆盖通知文字：鼠标检测会反复调用本方法，
        // 若无条件重设 timeLabel 会把通知标题覆盖回时间（修复岛上不显示通知文字）
        if (isNotificationActive) {
            return;
        }

        timeLabel.setText(getCurrentTime());
        dateLabel.setText(getCurrentDate());
        timeLabel.setFont(IslandUiStyle.TIME_FONT);
        // 还原时间显示样式（防御通知期间可能残留的样式改动）
        timeLabel.setMinimumSize(new Dimension(10, 24));
        timeLabel.setPreferredSize(new Dimension(Short.MAX_VALUE, 24));
        timeLabel.setMaximumSize(new Dimension(Short.MAX_VALUE, 24));
        timeLabel.setHorizontalAlignment(SwingConstants.CENTER);
        timeLabel.setVerticalAlignment(SwingConstants.CENTER);
        timeLabel.setForeground(Color.WHITE);
        // 恢复标准左边距（微信通知会把左边距改为 2px，保证时间岛布局不受影响）
        textPanel.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));

        SwingUtilities.invokeLater(() -> {
            if (!isHiding && !isNotificationActive && !isFinishingNotification) {
                // 完全重建标准布局：微信通知期间 timeLabel 被以 weighty=1.0/fill=BOTH
                // 的隐藏占位约束重新 add 过，残留约束会让时间行拉伸、日期行移位
                textPanel.removeAll();
                textConstraints.gridx = 0;
                textConstraints.gridy = 0;
                textConstraints.weightx = 1.0;
                textConstraints.weighty = 0.0;
                textConstraints.insets = new Insets(0, 0, 0, 0);
                textConstraints.fill = GridBagConstraints.HORIZONTAL;
                textConstraints.anchor = GridBagConstraints.CENTER;
                textPanel.add(timeLabel, textConstraints);

                textConstraints.gridy = 1;
                textConstraints.weighty = 0.0;
                textConstraints.fill = GridBagConstraints.HORIZONTAL;
                textConstraints.anchor = GridBagConstraints.CENTER;
                textPanel.add(dateLabel, textConstraints);

                textPanel.setVisible(true);
                timeLabel.setVisible(true);
                dateLabel.setVisible(true);
            } else if (!isHiding && isNotificationActive) {
                // 同上：重建布局避免 timeLabel 约束残留（防御性，正常路径开头已 return）
                textPanel.removeAll();
                textConstraints.gridx = 0;
                textConstraints.gridy = 0;
                textConstraints.weightx = 1.0;
                textConstraints.weighty = 0.0;
                textConstraints.insets = new Insets(0, 0, 0, 0);
                textConstraints.fill = GridBagConstraints.HORIZONTAL;
                textConstraints.anchor = GridBagConstraints.CENTER;
                textPanel.add(timeLabel, textConstraints);

                textConstraints.gridy = 1;
                textConstraints.fill = GridBagConstraints.HORIZONTAL;
                textConstraints.anchor = GridBagConstraints.CENTER;
                textPanel.add(deviceNameLabel, textConstraints);

                timeLabel.setVisible(true);
                deviceNameLabel.setVisible(true);
                dateLabel.setVisible(false);
            } else if (isFinishingNotification) {
                timeLabel.setVisible(false);
                dateLabel.setVisible(false);
                deviceNameLabel.setVisible(false);
                return;
            }
            if (isHiding) {
                return;
            }
            textPanel.revalidate();
            textPanel.repaint();
            updateTextVisibility();
        });
    }

    // ═══════════════════════════════════════════
    //  ExpandedIslandHost 实现
    // ═══════════════════════════════════════════

    @Override
    public java.awt.Window getMainIslandWindow() {
        return this;
    }

    @Override
    public DynamicIslandService getService() {
        return service;
    }

    @Override
    public void onCollapseFinished(boolean slideUp) {
        if (slideUp) {
            // 直接隐藏：不强制恢复主岛，交由鼠标检测逻辑按需显示，
            // 避免主岛闪现后又被立即隐藏造成"重复隐藏动画"的闪烁
            service.onAnimationComplete();
            setVisible(false);
        } else {
            service.show();
            service.onAnimationComplete();
            restoreTimeDisplay();
            setVisible(true);
            updateTextVisibility();
        }
    }

    @Override
    public void refreshWeatherNow(Runnable onDone) {
        HybridWeatherService monitor = weatherMonitor;
        if (monitor == null || onDone == null) {
            return;
        }
        // 刷新完成回调在工作线程触发，切回 EDT 再恢复刷新按钮状态
        monitor.refreshNow(() -> SwingUtilities.invokeLater(onDone));
    }

    // ═══════════════════════════════════════════
    //  主岛 UI 构建
    // ═══════════════════════════════════════════

    private void initUI() {
        IslandConfig config = service.getConfig();

        loadIcons();

        setBackground(IslandUiStyle.TRANSPARENT_BLACK);
        setAlwaysOnTop(true);
        setSize(config.width, config.height);

        JPanel panel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);

                Graphics2D g2d = null;
                try {
                    g2d = (Graphics2D) g.create();
                    if (g2d == null) {
                        return;
                    }

                    g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                    g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);

                    int arc = getHeight();
                    g2d.setColor(IslandUiStyle.BACKGROUND_COLOR);
                    g2d.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    if (g2d != null) {
                        g2d.dispose();
                    }
                }
            }

            @Override
            public void update(Graphics g) {
                paint(g);
            }
        };
        panel.setOpaque(false);
        panel.setLayout(new BorderLayout());

        animPanel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);

                if (animProgress <= 0f && !animPanel.isVisible()) {
                    return;
                }

                Graphics2D g2d = null;
                try {
                    g2d = (Graphics2D) g.create();
                    if (g2d == null) {
                        return;
                    }

                    g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2d.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                    g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

                    int w = getWidth(), h = getHeight();
                    int cx = w / 2, cy = h / 2;

                    // 微信/QQ 通知：无圆形底板、无环形进度，直接显示原始应用图标
                    if (showingWechatNotification || showingQqNotification) {
                        Image icon = showingQqNotification
                                ? (qqIconHq != null ? qqIconHq : qqIcon)
                                : (wechatIconHq != null ? wechatIconHq : wechatIcon);
                        if (icon != null) {
                            int iconSize = Math.min(w, h) - 6;
                            // 图标右移 2px；从 4x 高清源缩小绘制，边缘更锐利
                            g2d.drawImage(icon, cx - iconSize / 2 + 2, cy - iconSize / 2,
                                    iconSize, iconSize, null);
                        }
                        return;
                    }

                    int r = Math.min(w, h) / 2 - 3;

                    g2d.setColor(IslandUiStyle.SEMI_TRANSPARENT_BLACK);
                    g2d.fillOval(cx - r, cy - r, r * 2, r * 2);

                    g2d.setStroke(new BasicStroke(2.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g2d.setColor(IslandUiStyle.GREEN);
                    int angle = (int) (360 * animProgress);
                    g2d.draw(new Arc2D.Double(cx - r, cy - r, r * 2, r * 2, 90, -angle, Arc2D.OPEN));

                    Image currentIcon = showingWifiNotification ? wifiIcon : bluetoothIcon;
                    if (animProgress >= 1.0f) {
                        // 蓝牙/WiFi：动画完成后画对勾
                        g2d.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                        g2d.setColor(IslandUiStyle.GREEN);
                        Path2D check = new Path2D.Float();
                        check.moveTo(cx - 5, cy + 1);
                        check.lineTo(cx - 1, cy + 5);
                        check.lineTo(cx + 6, cy - 4);
                        g2d.draw(check);
                    } else if (currentIcon != null) {
                        int iconSize = r * 2 - 6;

                        if (showingWifiNotification && wifiIcon != null && currentIcon == wifiIcon) {
                            RenderingHints oldHints = g2d.getRenderingHints();
                            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                            g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                            g2d.drawImage(currentIcon, cx - iconSize / 2, cy - iconSize / 2 - 1,
                                    iconSize, iconSize, null);

                            g2d.setRenderingHints(oldHints);
                        } else {
                            RenderingHints oldHints = g2d.getRenderingHints();
                            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                            g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                            g2d.drawImage(currentIcon, cx - iconSize / 2, cy - iconSize / 2,
                                    iconSize, iconSize, null);

                            g2d.setRenderingHints(oldHints);
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    if (g2d != null) {
                        g2d.dispose();
                    }
                }
            }
        };
        animPanel.setOpaque(false);
        animPanel.setPreferredSize(new Dimension(36, 36));
        animPanel.setVisible(false);
        panel.add(animPanel, BorderLayout.WEST);

        textPanel = new JPanel(new GridBagLayout()) {
            @Override
            public Dimension getPreferredSize() {
                Dimension superSize = super.getPreferredSize();
                return new Dimension(Math.max(superSize.width, 100), superSize.height);
            }
        };
        textPanel.setOpaque(false);
        textPanel.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));

        timeLabel = new JLabel(getCurrentTime(), SwingConstants.CENTER);
        timeLabel.setForeground(Color.WHITE);
        timeLabel.setFont(IslandUiStyle.TIME_FONT);
        timeLabel.setMinimumSize(new Dimension(10, 24));
        timeLabel.setPreferredSize(new Dimension(Short.MAX_VALUE, 24));
        timeLabel.setMaximumSize(new Dimension(Short.MAX_VALUE, 24));

        dateLabel = new JLabel(getCurrentDate(), SwingConstants.CENTER);
        dateLabel.setForeground(IslandUiStyle.LIGHT_GRAY);
        dateLabel.setFont(IslandUiStyle.DATE_FONT);
        dateLabel.setMinimumSize(new Dimension(10, 16));
        dateLabel.setPreferredSize(new Dimension(Short.MAX_VALUE, 16));
        dateLabel.setMaximumSize(new Dimension(Short.MAX_VALUE, 16));

        deviceNameLabel = new JLabel("", SwingConstants.CENTER);
        deviceNameLabel.setForeground(IslandUiStyle.LIGHT_GRAY);
        deviceNameLabel.setFont(IslandUiStyle.DATE_FONT);
        deviceNameLabel.setMinimumSize(new Dimension(10, 16));
        deviceNameLabel.setPreferredSize(new Dimension(Short.MAX_VALUE, 16));
        deviceNameLabel.setMaximumSize(new Dimension(Short.MAX_VALUE, 16));

        textConstraints = new GridBagConstraints();
        textConstraints.gridx = 0;
        textConstraints.gridy = 0;
        textConstraints.anchor = GridBagConstraints.CENTER;
        textConstraints.weightx = 1.0;
        textConstraints.fill = GridBagConstraints.HORIZONTAL;
        textPanel.add(timeLabel, textConstraints);

        textConstraints.gridy = 1;
        textConstraints.weightx = 1.0;
        textConstraints.fill = GridBagConstraints.HORIZONTAL;
        textPanel.add(dateLabel, textConstraints);

        panel.add(textPanel, BorderLayout.CENTER);

        weatherPanel = new JPanel(new GridBagLayout());
        weatherPanel.setOpaque(false);
        weatherPanel.setPreferredSize(new Dimension(76, 36));
        weatherPanel.setVisible(true);

        weatherIconLabel = new JLabel("", SwingConstants.CENTER);
        weatherIconLabel.setForeground(IslandUiStyle.LIGHT_GRAY);
        Font iconFont = WeatherIconMapper.getIconFont(12f);
        if (iconFont != null) {
            weatherIconLabel.setFont(iconFont);
        }
        weatherIconLabel.setText(String.valueOf(WeatherIconMapper.getIconChar("未知")));

        GridBagConstraints weatherConstraints = new GridBagConstraints();
        weatherConstraints.gridx = 0;
        weatherConstraints.gridy = 0;
        weatherConstraints.gridwidth = 1;
        weatherConstraints.insets = new Insets(2, 2, 0, 2);
        weatherConstraints.fill = GridBagConstraints.HORIZONTAL;
        weatherConstraints.anchor = GridBagConstraints.CENTER;
        weatherTempLabel = new JLabel("--°", SwingConstants.CENTER);
        weatherTempLabel.setForeground(Color.WHITE);
        weatherTempLabel.setFont(IslandUiStyle.WEATHER_TEMP_FONT);
        weatherPanel.add(weatherTempLabel, weatherConstraints);

        JPanel iconCondRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 2, 0));
        iconCondRow.setOpaque(false);
        iconCondRow.add(weatherIconLabel);

        weatherConditionLabel = new JLabel("加载中", SwingConstants.CENTER);
        weatherConditionLabel.setForeground(IslandUiStyle.LIGHT_GRAY);
        weatherConditionLabel.setFont(IslandUiStyle.WEATHER_COND_FONT);
        iconCondRow.add(weatherConditionLabel);

        weatherConstraints.gridy = 1;
        weatherConstraints.insets = new Insets(0, 2, 2, 2);
        weatherPanel.add(iconCondRow, weatherConstraints);

        panel.add(weatherPanel, BorderLayout.EAST);

        getContentPane().add(panel);

        clockTimer = new Timer(1000, e -> {
            if (!isNotificationActive && !isFinishingNotification && isVisible()) {
                String currentTime = getCurrentTime();
                String currentDate = getCurrentDate();

                if (!currentTime.equals(timeLabel.getText())) {
                    timeLabel.setText(currentTime);
                }
                if (!currentDate.equals(dateLabel.getText())) {
                    dateLabel.setText(currentDate);
                }

                updateTextVisibility();
            }
        });
        clockTimer.start();

        bluetoothMonitor = new BluetoothMonitor();
        bluetoothMonitor.setListener(new BluetoothMonitor.BluetoothListener() {
            @Override
            public void onDeviceConnected(String deviceName) {
                SwingUtilities.invokeLater(() -> showBluetoothNotification(deviceName));
            }

            @Override
            public void onDeviceDisconnected(String deviceName) {
            }
        });
        bluetoothMonitor.start();
        AppLogger.info("Bluetooth", "蓝牙监控已启动");

        wifiMonitor = new WifiMonitor();
        wifiMonitor.setListener(new WifiMonitor.WifiListener() {
            @Override
            public void onWifiConnected(String networkName) {
                SwingUtilities.invokeLater(() -> showWifiNotification(networkName));
            }

            @Override
            public void onWifiDisconnected(String networkName) {
            }
        });
        wifiMonitor.start();
        AppLogger.info("Wifi", "WiFi 监控已启动");

        weatherMonitor = new HybridWeatherService();
        weatherMonitor.setListener(new HybridWeatherService.WeatherListener() {
            @Override
            public void onWeatherUpdated(WeatherInfo weather) {
                SwingUtilities.invokeLater(() -> {
                    weatherTempLabel.setText(weather.getFormattedTemperature());
                    weatherConditionLabel.setText(weather.getCondition());
                    char iconChar;
                    if (weather.getWeatherCode() >= 0) {
                        iconChar = WeatherIconMapper.getIconChar(weather.getWeatherCode());
                    } else {
                        iconChar = WeatherIconMapper.getIconChar(weather.getCondition());
                    }
                    weatherIconLabel.setText(String.valueOf(iconChar));

                    // 同步刷新扩展岛天气条与天气详情卡片
                    expandedController.onWeatherInfoChanged(weather);

                    if (!isNotificationActive && !isFinishingNotification && isVisible()) {
                        weatherPanel.setVisible(true);
                    }
                    panel.revalidate();
                    panel.repaint();
                });
            }

            @Override
            public void onWeatherError(String error) {
                AppLogger.warn("Weather", "天气更新失败: " + error);
                SwingUtilities.invokeLater(() -> {
                    weatherTempLabel.setText("--°");
                    weatherConditionLabel.setText("错误");
                    weatherIconLabel.setText(String.valueOf(WeatherIconMapper.getIconChar("未知")));
                    // 扩展岛天气条与详情卡片进入兜底状态
                    expandedController.onWeatherInfoChanged(null);
                    if (!isNotificationActive && !isFinishingNotification && isVisible()) {
                        weatherPanel.setVisible(true);
                    }
                });
            }
        });
        // 首次拉取已在服务内部调度线程异步执行，不阻塞 EDT，启用后不影响通知实时性
        weatherMonitor.start();

        addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e) {
                // 微信/QQ 通知展示期间点击岛 → 跳转对应 App（灵动岛交互），而非展开扩展岛
                if (showingWechatNotification && isNotificationActive) {
                    // 工作线程执行（内部有等待微信响应热键的 sleep，不能阻塞 EDT）
                    new Thread(() -> launchWechat(), "wechat-launch").start();
                } else if (showingQqNotification && isNotificationActive) {
                    // 工作线程执行（窗口枚举与前置不阻塞 EDT）
                    new Thread(() -> launchQq(), "qq-launch").start();
                } else {
                    expandedController.toggle();
                }
            }
        });
    }

    // ═══════════════════════════════════════════
    //  通知展示与动画
    // ═══════════════════════════════════════════

    private String getCurrentTime() {
        return LocalTime.now().format(TIME_FORMATTER);
    }

    private String getCurrentDate() {
        return LocalDate.now().format(DATE_FORMATTER);
    }

    /** 主岛通知类型（决定左侧圆形区域图标与动画收尾样式） */
    private enum NotificationKind { BLUETOOTH, WIFI, WECHAT, QQ }

    /** IM 消息类通知（微信/QQ）：共用无底板图标、宋体文案、快速动画等视觉与交互行为 */
    private static boolean isImNotification(NotificationKind kind) {
        return kind == NotificationKind.WECHAT || kind == NotificationKind.QQ;
    }

    private void showNotification(String title, String content, NotificationKind kind, int displayMs) {
        // 连续微信通知去抖：岛已展开（通知展示/收尾中或窗口可见）时
        // 只更新文案与计时器、不重播动画，避免群聊连发消息导致岛动画闪烁
        final boolean islandExpanded;
        final boolean wasHiding;
        synchronized (notificationLock) {
            islandExpanded = isNotificationActive || isFinishingNotification;
            wasHiding = isHiding;
            showingNotification = true;
            showingWifiNotification = (kind == NotificationKind.WIFI);
            showingWechatNotification = (kind == NotificationKind.WECHAT);
            showingQqNotification = (kind == NotificationKind.QQ);
            isNotificationActive = true;
        }
        // 新通知打断进行中的收起流程（收尾标志由通知完成路径统一复位）
        isHiding = false;
        isFinishingNotification = false;
        final boolean skipAnim = isImNotification(kind) && (islandExpanded || isVisible());

        try {
            if (notificationTimer != null && notificationTimer.isRunning()) {
                notificationTimer.stop();
            }

            // 取消上一个通知的收尾定时器：蓝牙+WiFi 连续通知时防止旧收尾把新通知的岛提前隐藏
            if (finishNotificationTimer != null) {
                finishNotificationTimer.stop();
                finishNotificationTimer = null;
            }

            if (weatherPanel != null) {
                SwingUtilities.invokeLater(() -> {
                    weatherPanel.setVisible(false);
                });
            }

            SwingUtilities.invokeLater(() -> {
                if (isImNotification(kind)) {
                    // 微信/QQ 通知视觉：无标题标签，仅主文案大字在文字区水平垂直居中；
                    // 主文案字体为宋体（Windows 内置，简体字形全覆盖）
                    deviceNameLabel.setFont(kind == NotificationKind.QQ
                            ? IslandUiStyle.QQ_CONTENT_FONT
                            : IslandUiStyle.WECHAT_CONTENT_FONT);
                    animPanel.setPreferredSize(new Dimension(32, 32));
                    // 左边距 8→2：文字区中心左移 3px，文案整体左移并给大字体腾宽
                    textPanel.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 0));
                    deviceNameLabel.setHorizontalAlignment(SwingConstants.CENTER);
                    deviceNameLabel.setVerticalAlignment(SwingConstants.CENTER);
                    deviceNameLabel.setForeground(Color.WHITE);
                } else {
                    // 恢复标准左边距（防御上次微信/QQ 通知残留）
                    textPanel.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));
                    timeLabel.setFont(IslandUiStyle.NOTIFY_TITLE_FONT);
                    deviceNameLabel.setFont(IslandUiStyle.DATE_FONT);
                    animPanel.setPreferredSize(new Dimension(36, 36));
                    timeLabel.setMinimumSize(new Dimension(10, 24));
                    timeLabel.setPreferredSize(new Dimension(Short.MAX_VALUE, 24));
                    timeLabel.setMaximumSize(new Dimension(Short.MAX_VALUE, 24));
                    timeLabel.setHorizontalAlignment(SwingConstants.CENTER);
                    timeLabel.setVerticalAlignment(SwingConstants.CENTER);
                    timeLabel.setForeground(Color.WHITE);
                    deviceNameLabel.setHorizontalAlignment(SwingConstants.CENTER);
                    deviceNameLabel.setVerticalAlignment(SwingConstants.CENTER);
                    deviceNameLabel.setForeground(IslandUiStyle.LIGHT_GRAY);
                }
                // 上一次通知收尾把标签设为不可见：这里显式恢复，
                // 不能依赖 restoreTimeDisplay 副作用（通知期间该方法提前返回）
                textPanel.setVisible(true);
                // 微信/QQ 通知无标题标签：timeLabel 隐藏不参与布局，文案独占文字区
                timeLabel.setVisible(!isImNotification(kind));
                deviceNameLabel.setVisible(true);
                dateLabel.setVisible(false);
                timeLabel.setText(title);
                deviceNameLabel.setText(content);

                // 显式重排布局（不能依赖旧约束）：微信/QQ 通知时文案占满整个文字区居中
                textPanel.removeAll();
                textConstraints.gridx = 0;
                textConstraints.gridy = 0;
                textConstraints.weightx = 1.0;
                textConstraints.weighty = isImNotification(kind) ? 1.0 : 0.0;
                textConstraints.insets = new Insets(0, 0, 0, 0);
                textConstraints.fill = isImNotification(kind)
                        ? GridBagConstraints.BOTH : GridBagConstraints.HORIZONTAL;
                textConstraints.anchor = GridBagConstraints.CENTER;
                textPanel.add(timeLabel, textConstraints);

                textConstraints.gridy = 1;
                textConstraints.weighty = isImNotification(kind) ? 1.0 : 0.0;
                textConstraints.fill = GridBagConstraints.HORIZONTAL;
                textConstraints.anchor = GridBagConstraints.CENTER;
                textPanel.add(deviceNameLabel, textConstraints);
            });

            if (!skipAnim) {
                if (isImNotification(kind)) {
                    // 微信/QQ 通知：无环形进度动画，直接显示原始应用图标
                    animProgress = 1.0f;
                } else {
                    startAnimInternal();
                }
            } else {
                // 已展开：保持动画完成态（图标 + 满环），不重播进度动画
                animProgress = 1.0f;
                animPanel.repaint();
            }

            SwingUtilities.invokeLater(() -> {
                animPanel.setVisible(true);
                panelRelayout();
            });

            updateTextVisibility();

            SwingUtilities.invokeLater(() -> {
                if (!isVisible() || wasHiding) {
                    // 窗口不可见或收起动画进行中：打断收起流程重新弹出；
                    // 微信/QQ 通知用快速动画（约 100ms），保证即时弹出
                    if (trayManager != null) {
                        if (isImNotification(kind)) {
                            trayManager.animateShowFast();
                        } else {
                            trayManager.animateShow();
                        }
                    } else {
                        setVisible(true);
                    }
                } else {
                    revalidate();
                    repaint();
                }
            });

            if (notificationTimer != null && notificationTimer.isRunning()) {
                notificationTimer.stop();
            }
            notificationTimer = new Timer(displayMs, e -> {
                stopAnim();

                synchronized (notificationLock) {
                    showingWifiNotification = false;
                    showingWechatNotification = false;
                    showingQqNotification = false;
                }

                SwingUtilities.invokeLater(() -> {
                    animPanel.setVisible(false);
                });

                finishNotificationTimer = new Timer(IslandUiStyle.ANIM_FRAME_MS * 2, switchTask -> {
                    isFinishingNotification = true;
                    isHiding = true;
                    service.hide();
                    SwingUtilities.invokeLater(() -> {
                        timeLabel.setVisible(false);
                        dateLabel.setVisible(false);
                        deviceNameLabel.setVisible(false);
                        textPanel.setVisible(false);

                        if (weatherPanel != null) {
                            weatherPanel.setVisible(false);
                        }

                        if (trayManager != null) {
                            trayManager.animateHide();
                            synchronized (notificationLock) {
                                showingNotification = false;
                                isNotificationActive = false;
                            }
                            // isFinishingNotification/isHiding 由 hide 动画完成回调统一复位，
                            // 修复此前托盘路径标志永不复位导致时钟停更/文字可见性异常的缺陷
                        } else {
                            setVisible(false);
                            onNotificationFinished();
                        }
                    });

                    finishNotificationTimer = null;
                });
                finishNotificationTimer.setRepeats(false);
                finishNotificationTimer.start();
            });
            notificationTimer.setRepeats(false);
            notificationTimer.start();
        } catch (Exception ex) {
            AppLogger.error("IslandWindow", "通知展示异常", ex);
            synchronized (notificationLock) {
                showingNotification = false;
                showingWifiNotification = false;
                showingWechatNotification = false;
                showingQqNotification = false;
                isNotificationActive = false;
            }
        }
    }

    private void showBluetoothNotification(String deviceName) {
        if (deviceName == null || deviceName.trim().isEmpty()) {
            return;
        }
        showNotification("蓝牙已连接", deviceName, NotificationKind.BLUETOOTH,
                IslandUiStyle.NOTIFICATION_DISPLAY_TIME);
    }

    private void showWifiNotification(String networkName) {
        if (networkName == null || networkName.trim().isEmpty()) {
            return;
        }
        showNotification("WiFi已连接", networkName, NotificationKind.WIFI,
                IslandUiStyle.NOTIFICATION_DISPLAY_TIME);
    }

    /** 微信消息通知（灵动岛风格：微信图标 + 固定文案，5 秒后自动收起，点击跳转微信） */
    private void showWechatNotification() {
        AppLogger.info("IslandWindow", "微信消息通知展示（5 秒后自动收起）");
        showNotification("微信", "你收到了一条微信消息", NotificationKind.WECHAT,
                IslandUiStyle.WECHAT_NOTIFICATION_DISPLAY_TIME);
    }

    /** QQ 消息通知（灵动岛风格：QQ 图标 + 固定文案，5 秒后自动收起，点击跳转 QQ） */
    private void showQqNotification() {
        AppLogger.info("IslandWindow", "QQ 消息通知展示（5 秒后自动收起）");
        showNotification("QQ", "你收到了一条 QQ 消息", NotificationKind.QQ,
                IslandUiStyle.QQ_NOTIFICATION_DISPLAY_TIME);
    }

    /** 点击岛跳转微信：模拟官方全局热键唤起，失败弹报错窗口提示手动打开 */
    private void launchWechat() {
        // 模拟微信官方全局热键 Ctrl+Alt+W（设置→快捷键的默认“打开微信”）：
        // 通过微信自身机制显示主窗口，Qt 状态正确窗口正常响应；
        // 外部 ShowWindow 强制显示隐藏的 Qt 窗口会让其变成“僵尸”窗口（点击无反应）
        try {
            Robot robot = new Robot();
            robot.setAutoDelay(25);
            robot.keyPress(KeyEvent.VK_CONTROL);
            robot.keyPress(KeyEvent.VK_ALT);
            robot.keyPress(KeyEvent.VK_W);
            robot.keyRelease(KeyEvent.VK_W);
            robot.keyRelease(KeyEvent.VK_ALT);
            robot.keyRelease(KeyEvent.VK_CONTROL);
            Thread.sleep(400); // 等微信响应全局热键
            WinDef.HWND wnd = findWechatWindow(true);
            if (wnd != null) {
                User32.INSTANCE.SetForegroundWindow(wnd);
                User32.INSTANCE.BringWindowToTop(wnd);
                // 抑制后续 30s 内的消息库写入通知（已读/会话更新）；
                // 对齐整秒（截断到秒再 +30 秒），与监听端秒级判定保持一致，窗口到期即自然解除
                wechatLaunchSuppressUntilMs = (System.currentTimeMillis() / 1000 + 30) * 1000;
                AppLogger.info("IslandWindow", "已通过全局热键唤起微信主窗口");
                return;
            }
        } catch (Exception e) {
            AppLogger.warn("IslandWindow", "模拟微信热键失败: " + e.getMessage());
        }

        // 唤起失败：弹报错窗口提示手动打开。不做强制显示兜底（产生僵尸窗口）
        // 也不启动 exe（微信 4.x 多进程模型下会弹登录界面）
        AppLogger.warn("IslandWindow", "微信窗口唤起失败，提示手动打开");
        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                this,
                "微信窗口打开失败，请手动打开微信",
                "微信跳转失败",
                JOptionPane.WARNING_MESSAGE));
    }

    /**
     * 查找微信主窗口：遍历微信进程（Weixin/WeChat）的顶层窗口，
     * 标题精确「微信」优先，含「微信」备选；requireVisible=true 时只找可见窗口。
     * 微信 4.x 最小化到托盘后主窗口隐藏（MainWindowHandle=0），故默认不要求可见。
     */
    private WinDef.HWND findWechatWindow(boolean requireVisible) {
        try {
            Set<Long> wechatPids = new HashSet<>();
            ProcessHandle.allProcesses().forEach(p -> {
                String cmd = p.info().command().orElse("");
                String lower = cmd.toLowerCase();
                if (lower.contains("weixin") || lower.contains("wechat")) {
                    wechatPids.add(p.pid());
                }
            });
            if (wechatPids.isEmpty()) {
                return null;
            }

            final WinDef.HWND[] exact = new WinDef.HWND[1];
            final WinDef.HWND[] fuzzy = new WinDef.HWND[1];
            User32.INSTANCE.EnumWindows((hWnd, data) -> {
                if (hWnd == null) return true;
                IntByReference pidRef = new IntByReference();
                User32.INSTANCE.GetWindowThreadProcessId(hWnd, pidRef);
                if (!wechatPids.contains((long) pidRef.getValue())) return true;
                if (requireVisible && !User32.INSTANCE.IsWindowVisible(hWnd)) return true;
                int len = User32.INSTANCE.GetWindowTextLength(hWnd);
                if (len <= 0) return true;
                char[] buf = new char[len + 1];
                User32.INSTANCE.GetWindowText(hWnd, buf, len + 1);
                String title = new String(buf, 0, len);
                if (title.equals("微信")) {
                    exact[0] = hWnd;
                    return false; // 精确主窗口，停止枚举
                }
                if (title.contains("微信") && fuzzy[0] == null) {
                    fuzzy[0] = hWnd;
                }
                return true;
            }, null);
            return exact[0] != null ? exact[0] : fuzzy[0];
        } catch (Throwable t) {
            AppLogger.warn("IslandWindow", "查找微信窗口失败: " + t.getMessage());
            return null;
        }
    }

    /**
     * 点击岛跳转 QQ：
     * 1) QQ 主窗口可见（含最小化）：恢复并前置，安全无副作用；
     * 2) 窗口隐藏（常驻托盘）：不可强制 ShowWindow——Electron 隐藏窗口被外部强显会
     *    渲染黑屏，改用 QQ 自身全局热键唤起（默认 Ctrl+Alt+Z「打开主面板」）；
     * 失败弹报错窗口提示，不主动拉起 QQ（避免弹登录界面）。
     */
    private void launchQq() {
        WinDef.HWND wnd = findQqWindow(false);
        if (wnd != null && User32.INSTANCE.IsWindowVisible(wnd)) {
            User32.INSTANCE.ShowWindow(wnd, WinUser.SW_RESTORE);
            User32.INSTANCE.SetForegroundWindow(wnd);
            User32.INSTANCE.BringWindowToTop(wnd);
            suppressAfterQqLaunch();
            AppLogger.info("IslandWindow", "已前置 QQ 主窗口");
            return;
        }

        // 窗口隐藏（或尚未找到）：模拟 QQ 官方全局热键唤起主面板，随后确认窗口可见再前置
        if (isQqProcessRunning() && invokeQqHotkeyAndWait()) {
            WinDef.HWND shown = findQqWindow(true);
            if (shown != null) {
                User32.INSTANCE.SetForegroundWindow(shown);
                User32.INSTANCE.BringWindowToTop(shown);
                suppressAfterQqLaunch();
                AppLogger.info("IslandWindow", "已通过全局热键唤起 QQ 主窗口");
                return;
            }
        }

        // 唤起失败：弹报错窗口提示手动打开；不启动 exe（会弹登录界面）
        AppLogger.warn("IslandWindow", "QQ 窗口唤起失败，提示手动打开");
        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                this,
                "QQ 窗口打开失败，请手动打开 QQ；\n若 QQ 常驻托盘，请确认设置中「打开主面板」快捷键为 Ctrl+Alt+Z。",
                "QQ 跳转失败",
                JOptionPane.WARNING_MESSAGE));
    }

    /** 跳转成功后的 30s 通知抑制（用户正在看 QQ）；对齐整秒，与监听端秒级判定一致 */
    private void suppressAfterQqLaunch() {
        qqLaunchSuppressUntilMs = (System.currentTimeMillis() / 1000 + 30) * 1000;
    }

    /** 模拟 QQ 全局热键 Ctrl+Alt+Z（默认「打开主面板」）并等待其响应 */
    private boolean invokeQqHotkeyAndWait() {
        try {
            Robot robot = new Robot();
            robot.setAutoDelay(25);
            robot.keyPress(KeyEvent.VK_CONTROL);
            robot.keyPress(KeyEvent.VK_ALT);
            robot.keyPress(KeyEvent.VK_Z);
            robot.keyRelease(KeyEvent.VK_Z);
            robot.keyRelease(KeyEvent.VK_ALT);
            robot.keyRelease(KeyEvent.VK_CONTROL);
            Thread.sleep(400); // 等 QQ 响应全局热键
            return true;
        } catch (Exception e) {
            AppLogger.warn("IslandWindow", "模拟 QQ 热键失败: " + e.getMessage());
            return false;
        }
    }

    /** QQ 进程是否在运行（热键唤起的前提；进程不在时热键无意义） */
    private boolean isQqProcessRunning() {
        try {
            return ProcessHandle.allProcesses().anyMatch(p -> "qq".equalsIgnoreCase(
                    p.info().command().map(cmd -> {
                        int i = cmd.lastIndexOf('\\');
                        int j = cmd.lastIndexOf('/');
                        String base = cmd.substring(Math.max(i, j) + 1);
                        int dot = base.lastIndexOf('.');
                        return dot > 0 ? base.substring(0, dot) : base;
                    }).orElse("")));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 查找 QQ 主窗口：遍历 QQ 进程（QQ.exe，NTQQ）的顶层窗口，
     * 标题精确「QQ」优先，含「QQ」备选。匹配进程名精确为 qq，
     * 避免误伤 QQ音乐（QQMusic）等名称含 QQ 的进程；
     * requireVisible=true 时只找可见窗口（热键唤起后确认用）。
     */
    private WinDef.HWND findQqWindow(boolean requireVisible) {
        try {
            Set<Long> qqPids = new HashSet<>();
            ProcessHandle.allProcesses().forEach(p -> {
                String name = p.info().command().map(cmd -> {
                    int i = cmd.lastIndexOf('\\');
                    int j = cmd.lastIndexOf('/');
                    String base = cmd.substring(Math.max(i, j) + 1);
                    int dot = base.lastIndexOf('.');
                    return dot > 0 ? base.substring(0, dot) : base;
                }).orElse("");
                // 仅匹配进程名 qq（不含扩展名），排除 QQMusic 等
                if ("qq".equalsIgnoreCase(name)) {
                    qqPids.add(p.pid());
                }
            });
            if (qqPids.isEmpty()) {
                return null;
            }

            final WinDef.HWND[] exact = new WinDef.HWND[1];
            final WinDef.HWND[] fuzzy = new WinDef.HWND[1];
            User32.INSTANCE.EnumWindows((hWnd, data) -> {
                if (hWnd == null) return true;
                IntByReference pidRef = new IntByReference();
                User32.INSTANCE.GetWindowThreadProcessId(hWnd, pidRef);
                if (!qqPids.contains((long) pidRef.getValue())) return true;
                if (requireVisible && !User32.INSTANCE.IsWindowVisible(hWnd)) return true;
                int len = User32.INSTANCE.GetWindowTextLength(hWnd);
                if (len <= 0) return true;
                char[] buf = new char[len + 1];
                User32.INSTANCE.GetWindowText(hWnd, buf, len + 1);
                String title = new String(buf, 0, len);
                if (title.equals("QQ")) {
                    exact[0] = hWnd;
                    return false; // 精确主窗口，停止枚举
                }
                if (title.contains("QQ") && fuzzy[0] == null) {
                    fuzzy[0] = hWnd;
                }
                return true;
            }, null);
            return exact[0] != null ? exact[0] : fuzzy[0];
        } catch (Throwable t) {
            AppLogger.warn("IslandWindow", "查找 QQ 窗口失败: " + t.getMessage());
            return null;
        }
    }

    private void startAnimInternal() {
        stopAnim();
        animProgress = 0f;
        final long startTime = System.currentTimeMillis();
        animTimer = new Timer(IslandUiStyle.ANIM_FRAME_MS, e -> {
            float elapsed = (System.currentTimeMillis() - startTime) / (float) IslandUiStyle.ANIM_DURATION_MS;
            animProgress = Math.min(elapsed, 1.0f);
            animPanel.repaint();
            if (animProgress >= 1.0f) {
                ((Timer) e.getSource()).stop();
            }
        });
        animTimer.start();
    }

    private void stopAnim() {
        if (animTimer != null) {
            animTimer.stop();
            animTimer = null;
        }
        animProgress = 0f;
    }

    /** 通知收尾统一复位：显示/隐藏/通知状态标志全部复位（无托盘分支直接调用） */
    private void onNotificationFinished() {
        isFinishingNotification = false;
        isHiding = false;
        synchronized (notificationLock) {
            showingNotification = false;
            isNotificationActive = false;
        }
    }

    /** 托盘管理器 hide 动画完成回调：复位通知收尾标志（通知锁状态由通知流程自行维护） */
    public void onTrayHideAnimationFinished() {
        isFinishingNotification = false;
        isHiding = false;
    }

    private void panelRelayout() {
        Container c = getContentPane();
        if (c.getComponentCount() > 0) {
            c.getComponent(0).revalidate();
        }
    }

    @Override
    public void setSize(int width, int height) {
        isAnimating = true;
        super.setSize(width, height);

        if (textPanel != null) {
            updateTextVisibility();
        }
    }

    @Override
    public void setBounds(int x, int y, int width, int height) {
        isAnimating = true;
        super.setBounds(x, y, width, height);

        if (textPanel != null) {
            updateTextVisibility();
        }
    }

    private void updateTextVisibility() {
        if (!isVisible() || isHiding) {
            return;
        }

        IslandConfig config = service.getConfig();

        double widthRatio = (double) getWidth() / config.width;
        double heightRatio = (double) getHeight() / config.height;

        boolean shouldHideText = widthRatio <= IslandUiStyle.TEXT_VISIBLE_THRESHOLD_RATIO ||
                heightRatio <= IslandUiStyle.TEXT_VISIBLE_THRESHOLD_RATIO;

        boolean newTextVisible = !shouldHideText && isVisible();
        boolean newWeatherVisible = !isNotificationActive && !isFinishingNotification && !shouldHideText && isVisible();
        boolean newAnimVisible = showingNotification && !shouldHideText && isVisible();

        boolean textChanged = textPanel.isVisible() != newTextVisible;
        boolean weatherChanged = weatherPanel != null && weatherPanel.isVisible() != newWeatherVisible;
        boolean animChanged = animPanel != null && animPanel.isVisible() != newAnimVisible;

        if (textChanged) {
            textPanel.setVisible(newTextVisible);
            textPanel.invalidate();
        }
        if (weatherChanged) {
            weatherPanel.setVisible(newWeatherVisible);
            weatherPanel.invalidate();
        }
        if (animChanged) {
            animPanel.setVisible(newAnimVisible);
            animPanel.invalidate();
        }

        if (textChanged || weatherChanged || animChanged) {
            if (textChanged) textPanel.repaint();
            if (weatherChanged) weatherPanel.repaint();
            if (animChanged) animPanel.repaint();
        }
    }

    // ═══════════════════════════════════════════
    //  图标加载与资源清理
    // ═══════════════════════════════════════════

    @Override
    public void dispose() {
        try {
            if (clockTimer != null) {
                clockTimer.stop();
                clockTimer = null;
            }

            if (notificationTimer != null) {
                notificationTimer.stop();
                notificationTimer = null;
            }

            if (finishNotificationTimer != null) {
                finishNotificationTimer.stop();
                finishNotificationTimer = null;
            }

            if (animTimer != null) {
                animTimer.stop();
                animTimer = null;
            }

            // 停止扩展岛各类定时器并触发收起（与原 dispose 顺序一致：先全部定时器）
            expandedController.dispose();

            if (musicMonitor != null) {
                musicMonitor.stop();
            }

            if (batteryMonitor != null) {
                batteryMonitor.stop();
            }

            if (bluetoothMonitor != null) {
                bluetoothMonitor.stop();
            }

            if (wifiMonitor != null) {
                wifiMonitor.stop();
            }

            if (privacyMonitor != null) {
                privacyMonitor.stop();
            }

            if (wechatMonitor != null) {
                wechatMonitor.stop();
            }

            if (weatherMonitor != null) {
                weatherMonitor.stop();
            }

            cleanupImageResources();
        } catch (Exception e) {
            AppLogger.error("IslandWindow", "窗口销毁异常", e);
        }

        super.dispose();
    }

    private Image loadImage(String path) {
        try {
            return new ImageIcon(getClass().getResource(path)).getImage();
        } catch (Exception e) {
            AppLogger.warn("IslandWindow", "图标加载失败: " + path + " - " + e.getMessage());
            return null;
        }
    }

    private void flushImage(Image image) {
        if (image instanceof java.awt.image.BufferedImage) {
            ((java.awt.image.BufferedImage) image).flush();
        }
    }

    private void loadIcons() {
        bluetoothIcon = loadImage("/icons/bluetooth.png");
        wifiIcon = loadImage("/icons/wifi.png");
        // 内置微信图标（从微信 exe 提取）：daemon 未能提取实时图标时的回退
        wechatIcon = loadImage("/icons/wechat.png");
        // 生成 4x 高清副本：避免 32→26 非整数比例缩放导致的边缘模糊
        if (wechatIcon != null) {
            wechatIconHq = createHqIcon(wechatIcon, 128);
        }
        // 内置 QQ 图标（从 QQ.exe 提取的原生图标）：与微信图标同规格，同样生成高清副本
        qqIcon = loadImage("/icons/qq.png");
        if (qqIcon != null) {
            qqIconHq = createHqIcon(qqIcon, 128);
        }
        cameraInUseIcon = loadImage("/icons/摄像头使用中.png");
        micInUseIcon = loadImage("/icons/麦克风使用中.png");
        returnIcon = loadImage("/icons/返回_return.png");
        refreshIcon = loadImage("/icons/刷新_refresh.png");
        expandedController.setUsageIcons(cameraInUseIcon, micInUseIcon);
        expandedController.setReturnIcon(returnIcon);
        expandedController.setRefreshIcon(refreshIcon);
    }

    private void cleanupImageResources() {
        flushImage(bluetoothIcon);
        bluetoothIcon = null;
        flushImage(wifiIcon);
        wifiIcon = null;
        flushImage(cameraInUseIcon);
        cameraInUseIcon = null;
        flushImage(micInUseIcon);
        micInUseIcon = null;
        flushImage(returnIcon);
        returnIcon = null;
        flushImage(refreshIcon);
        refreshIcon = null;
        flushImage(wechatIconHq);
        wechatIconHq = null;
        flushImage(wechatIcon);
        wechatIcon = null;
        flushImage(qqIconHq);
        qqIconHq = null;
        flushImage(qqIcon);
        qqIcon = null;
    }

    /** 图标高清化：BICUBIC 放大为高分辨率副本，绘制时缩小获得更锐利的边缘 */
    private static Image createHqIcon(Image src, int size) {
        BufferedImage hq = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = hq.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, size, size, null);
        } finally {
            g.dispose();
        }
        return hq;
    }
}
