package com.island.island.ui;

import com.island.bluetooth.BluetoothMonitor;
import com.island.island.model.IslandConfig;
import com.island.island.service.DynamicIslandService;
import com.island.island.service.impl.DynamicIslandServiceImpl;
import com.island.music.MusicMonitor;
import com.island.music.LyricsService;
import com.island.music.WindowsMediaManager;
import com.island.music.model.LyricItem;
import com.island.music.model.MusicInfo;
import com.island.tray.SystemTrayManager;
import com.island.util.AnimationUtil;
import com.island.util.AppLogger;

import com.island.wifi.WifiMonitor;
import com.island.battery.BatteryMonitor;
import com.island.config.AppConstants;
import com.island.privacy.PrivacyMonitor;
import com.island.weather.HybridWeatherService;
import com.island.weather.WeatherIconMapper;
import com.island.weather.WeatherInfo;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Arc2D;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import java.awt.MediaTracker;
import java.io.Serializable;
import java.net.URL;
import javax.imageio.ImageIO;

/**
 * 动态岛窗口类，提供类似iOS动态岛的通知和时间显示功能。
 */
@SuppressWarnings({"this-escape"})
public class IslandWindow extends JWindow implements Serializable {

    private static final long serialVersionUID = 1L;

    private static final Color GREEN = new Color(0, 200, 80);

    private final transient DynamicIslandService service;
    private JLabel timeLabel;
    private JLabel dateLabel;
    private JLabel deviceNameLabel;
    private Timer clockTimer;
    private transient BluetoothMonitor bluetoothMonitor;
    private Timer notificationTimer;
    private volatile boolean showingNotification = false;
    private volatile boolean showingWifiNotification = false;
    private transient SystemTrayManager trayManager;
    private transient WifiMonitor wifiMonitor;
    private transient HybridWeatherService weatherMonitor;

    private static final Color BACKGROUND_COLOR = new Color(30, 30, 30, 200);
    private static final Color TRANSPARENT_BLACK = new Color(0, 0, 0, 0);
    private static final Color SEMI_TRANSPARENT_BLACK = new Color(0, 0, 0, 60);
    private static final Color LIGHT_GRAY = new Color(200, 200, 200);
    private static final Color DEEP_BLACK = new Color(10, 10, 10, 240);

    private static final Font TIME_FONT = new Font("Microsoft YaHei", Font.BOLD, 24);
    private static final Font DATE_FONT = new Font("Microsoft YaHei", Font.PLAIN, 11);
    private static final Font WEATHER_TEMP_FONT = new Font("Microsoft YaHei", Font.BOLD, 14);
    private static final Font WEATHER_COND_FONT = new Font("Microsoft YaHei", Font.PLAIN, 10);
    private static final Font NOTIFY_TITLE_FONT = new Font("Microsoft YaHei", Font.BOLD, 16);

    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault());
    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault());

    private static final int ANIM_DURATION_MS = 650;
    private static final int ANIM_FRAME_MS = 16;
    private static final int NOTIFICATION_DISPLAY_TIME = 2000;
    private static final double TEXT_VISIBLE_THRESHOLD_RATIO = 2.0/3.0;

    private static final int EXPANDED_WIDTH = 450;
    private static final int EXPANDED_HEIGHT = 54;
    private static final int EXPAND_ANIM_DURATION_MS = 280;
    private static final int EXPAND_ANIM_FRAME_MS = 10;
    private static final int SLIDE_ANIM_DURATION_MS = 400;
    private static final int SLIDE_ANIM_FRAME_MS = 11; // 约 90 FPS（1000/11 ≈ 90.9）
    /** 直接隐藏阶段1（两边向中间收缩成小球）时长 */
    private static final int SLIDE_UP_SHRINK_MS = 400;
    /** 直接隐藏阶段2（小球向上滑出屏幕）时长：快速滑出，无需看清 */
    private static final int SLIDE_UP_RISE_MS = 100;

    // ── 音乐面板 ──
    private static final int COVER_SIZE = 48;
    private static final int COVER_SSAA = 3;
    private static final int COVER_HIRES = COVER_SIZE * COVER_SSAA;
    private static final double COVER_ROTATION_DEG_PER_FRAME = 0.2667; // 16.67°/s × 16ms，与原 30ms×0.5° 转速一致
    private static final int COVER_ROTATION_FRAME_MS = 16; // 约 60 FPS（1000/16 ≈ 62.5）
    private static final Font MUSIC_TITLE_FONT = new Font("Microsoft YaHei", Font.PLAIN, 11);
    private static final Font MUSIC_ARTIST_FONT = new Font("Microsoft YaHei", Font.PLAIN, 10);
    private static final Font MUSIC_LYRICS_FONT = new Font("Microsoft YaHei", Font.BOLD, 14);
    private static final int LYRIC_SCROLL_MS = 200;

    private transient Image bluetoothIcon;
    private transient Image wifiIcon;

    // ── 音乐 ──
    private transient Image musicCoverImage;
    private double coverRotationAngle = 0.0;
    private Timer coverRotationTimer;
    private JPanel musicPanel;
    private JLabel musicCoverLabel;
    private JLabel musicTitleLabel;
    private JLabel musicArtistLabel;
    private JLabel musicLyricsLabel;
    private boolean musicPanelInitialized = false;
    private transient MusicMonitor musicMonitor;
    private final transient LyricsService lyricsService = new LyricsService();
    private List<LyricItem> lrcLines = Collections.emptyList();
    private volatile MusicInfo currentMusicInfo = MusicInfo.EMPTY;
    private javax.swing.Timer lyricScrollTimer;
    private int currentLyricIndex = -1;
    private volatile boolean fetchingLyrics = false;
    private volatile boolean fetchingCover = false;
    private String lastFetchedTrackId = "";
    private String lastFetchedCoverTrackId = "";
    private String lastCoverBase64 = "";
    /** SMTC Base64 封面成功应用对应的曲目标识（title|artist），用于 URL 源封面跳过判断 */
    private String smTcCoverAppliedTrackId = "";
    /** 上一次处于严格播放状态的来源播放器标识，用于检测活跃播放器切换 */
    private String lastActiveSourceAppId = "";
    // 仅使用 daemon 汇报的 positionTicks 作为歌词进度
    // fallback 字段已废弃，wall-clock 自推进机制已移除
    @Deprecated private long fallbackBaseMs = 0;
    @Deprecated private long fallbackStartMs = 0;
    private long lastDaemonEndTimeMs = 0;

    private JPanel animPanel;
    private JPanel weatherPanel;
    private JLabel weatherIconLabel;
    private JLabel weatherTempLabel;
    private JLabel weatherConditionLabel;
    private JPanel textPanel;
    private GridBagConstraints textConstraints;

    // ── 扩展岛 ──
    private JWindow expandedWindow;
    private boolean isExpanding = false;
    private boolean isCollapsing = false;

    // ── 电池 ──
    private transient BatteryMonitor batteryMonitor;
    private JPanel batteryPanel;
    private volatile BatteryMonitor.BatteryInfo currentBatteryInfo = BatteryMonitor.BatteryInfo.ABSENT;

    // ── 摄像头 / 麦克风使用状态 ──
    private static final int USAGE_ICON_MS = 1500;
    private static final int USAGE_MORPH_MS = 400;
    private static final int USAGE_FADE_OUT_MS = 350;
    private static final int USAGE_ANIM_FRAME_MS = 16;
    /** 图标尺寸：定位在扩展岛右半圆圆心，放大 2 倍（14 → 28） */
    private static final int USAGE_ICON_SIZE = 28;
    private static final int USAGE_DOT_DIAMETER = 8;
    private static final int USAGE_SLOT_GAP = 6;
    private static final int USAGE_PANEL_PAD = 6;
    /** 设备占用自动弹出扩展岛后保持显示的时长 */
    private static final int DEVICE_AUTO_HIDE_MS = 5000;
    /** 音乐停止播放后自动收回扩展岛的等待时长（连续未恢复播放满 2 分钟） */
    private static final int MUSIC_STOP_AUTO_HIDE_MS = 2 * 60 * 1000;
    /** 扩展岛空闲自动收起：仅用户主动展开时启动，连续空闲满此时长后自动收起 */
    private static final int IDLE_AUTO_COLLAPSE_MS = 10 * 60 * 1000;
    /** 空闲自动收起巡检间隔 */
    private static final int IDLE_AUTO_COLLAPSE_CHECK_MS = 5000;

    private transient PrivacyMonitor privacyMonitor;
    private transient Image cameraInUseIcon;
    private transient Image micInUseIcon;
    private JPanel deviceUsagePanel;
    private Timer deviceUsageAnimTimer;
    /** 当前扩展岛是否由设备占用事件自动弹出（决定 5 秒自动隐藏是否生效） */
    private boolean deviceAutoExpanded = false;
    private Timer deviceAutoHideTimer;
    /** 当前扩展岛是否因音乐播放自动弹出而常驻 */
    private boolean musicAutoExpanded = false;
    private Timer musicStopAutoHideTimer;
    /** 本次播放会话内已自动显示过音乐面板（用户滚轮切回电池卡片后不再强制切回） */
    private boolean musicPanelAutoShownForSession = false;
    /** 用户在播放期间手动折叠音乐岛后，本次播放会话不再自动弹出 */
    private boolean musicPopupSuppressedByUser = false;
    /** 扩展岛空闲自动收起巡检定时器（仅用户主动展开时启动） */
    private Timer idleAutoCollapseTimer;
    /** 空闲计时起点：任一阻断条件（未勾选/歌词显示/设备监测指示）出现时重置 */
    private long idleExpandSince = 0;
    private final DeviceIndicator cameraIndicator = new DeviceIndicator();
    private final DeviceIndicator micIndicator = new DeviceIndicator();
    private volatile boolean cameraInUse = false;
    private volatile boolean micInUse = false;
    /** 面板级唯一绿点：进度 0~1（淡入/淡出插值），固定显示在右半圆圆心 */
    private float dotProgress = 0f;
    private boolean dotTargetVisible = false;
    /** 绿点在状态面板内的 x 坐标（面板超窗左移后与圆心仍保持一致） */
    private int usageDotCenterX = 20;

    /** 单个设备状态指示器的动画阶段（仅负责图标展示，绿点由面板统一绘制） */
    private enum UsagePhase { HIDDEN, ICON, MORPHING, FADING_OUT }

    /** 单个设备状态指示器（摄像头或麦克风） */
    private static final class DeviceIndicator {
        UsagePhase phase = UsagePhase.HIDDEN;
        long phaseStartMs;
        Image icon;
    }

    // ── 卡片切换 ──
    private float gestureSlideProgress = 0f;
    private Timer gestureSlideAnimTimer;
    /** 音乐面板是否已通过滚轮切换在扩展岛中显示 */
    private boolean musicPanelShownInExpanded = false;
    private JPanel placeholderPanel;

    private Timer animTimer;

    private final Object notificationLock = new Object();

    private boolean isAnimating = false;
    private float animProgress = 0f;

    private volatile boolean isFinishingNotification = false;
    private volatile boolean isNotificationActive = false;
    private volatile boolean isHiding = false;

    public IslandWindow() {
        this.service = DynamicIslandServiceImpl.getInstance();
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
            System.out.println("[IslandWindow] MusicMonitor 回调: " + info);
            SwingUtilities.invokeLater(() -> updateMusicInfo(info));
        });
        monitor.start();
    }

    /** 注入电池监控器，由 IslandApplication 调用 */
    public void setBatteryMonitor(BatteryMonitor monitor) {
        if (monitor == null) return;
        this.batteryMonitor = monitor;
        AppLogger.info("IslandWindow", "BatteryMonitor 已注入，开始监听");
        monitor.setListener(info -> {
            SwingUtilities.invokeLater(() -> updateBatteryDisplay(info));
        });
        monitor.start();
    }

    /** 注入摄像头/麦克风使用状态监控器，由 IslandApplication 调用 */
    public void setPrivacyMonitor(PrivacyMonitor monitor) {
        if (monitor == null) return;
        this.privacyMonitor = monitor;
        AppLogger.info("IslandWindow", "PrivacyMonitor 已注入，开始监听");
        monitor.setListener((camera, mic) ->
                SwingUtilities.invokeLater(() -> updateDeviceUsage(camera, mic)));
        monitor.start();
    }

    /** 供 SystemTrayManager 获取 LyricsService 引用。 */
    public LyricsService getLyricsService() {
        return lyricsService;
    }

    public boolean isShowingNotification() {
        return showingNotification;
    }

    public void setHiding(boolean hiding) {
        this.isHiding = hiding;
    }

    public void restoreTimeDisplay() {
        isHiding = false;
        isFinishingNotification = false;

        timeLabel.setText(getCurrentTime());
        dateLabel.setText(getCurrentDate());
        timeLabel.setFont(TIME_FONT);

        SwingUtilities.invokeLater(() -> {
            if (!isHiding && !isNotificationActive && !isFinishingNotification) {
                if (textPanel.getComponentCount() > 1) {
                    textPanel.remove(1);
                }
                textConstraints.gridy = 1;
                textConstraints.weightx = 1.0;
                textConstraints.fill = GridBagConstraints.HORIZONTAL;
                textConstraints.anchor = GridBagConstraints.CENTER;
                textPanel.add(dateLabel, textConstraints);
                
                textPanel.setVisible(true);
                timeLabel.setVisible(true);
                dateLabel.setVisible(true);
            } else if (!isHiding && isNotificationActive) {
                if (textPanel.getComponentCount() > 1) {
                    textPanel.remove(1);
                }
                textConstraints.gridy = 1;
                textConstraints.weightx = 1.0;
                textConstraints.fill = GridBagConstraints.HORIZONTAL;
                textConstraints.anchor = GridBagConstraints.CENTER;
                textPanel.add(deviceNameLabel, textConstraints);
                
                timeLabel.setVisible(true);
                deviceNameLabel.setVisible(true);
                dateLabel.setVisible(false);
            }
            else if (isFinishingNotification) {
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

    private void initUI() {
        IslandConfig config = service.getConfig();
        
        loadIcons();

        setBackground(TRANSPARENT_BLACK);
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
                    g2d.setColor(BACKGROUND_COLOR);
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
                    int r = Math.min(w, h) / 2 - 3;
                    int cx = w / 2, cy = h / 2;

                    g2d.setColor(SEMI_TRANSPARENT_BLACK);
                    g2d.fillOval(cx - r, cy - r, r * 2, r * 2);

                    g2d.setStroke(new BasicStroke(2.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g2d.setColor(GREEN);
                    int angle = (int) (360 * animProgress);
                    g2d.draw(new Arc2D.Double(cx - r, cy - r, r * 2, r * 2, 90, -angle, Arc2D.OPEN));

                    Image currentIcon = showingWifiNotification ? wifiIcon : bluetoothIcon;
                    if (animProgress >= 1.0f) {
                        g2d.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                        g2d.setColor(GREEN);
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
        timeLabel.setFont(TIME_FONT);
        timeLabel.setMinimumSize(new Dimension(10, 24));
        timeLabel.setPreferredSize(new Dimension(Short.MAX_VALUE, 24));
        timeLabel.setMaximumSize(new Dimension(Short.MAX_VALUE, 24));

        dateLabel = new JLabel(getCurrentDate(), SwingConstants.CENTER);
        dateLabel.setForeground(LIGHT_GRAY);
        dateLabel.setFont(DATE_FONT);
        dateLabel.setMinimumSize(new Dimension(10, 16));
        dateLabel.setPreferredSize(new Dimension(Short.MAX_VALUE, 16));
        dateLabel.setMaximumSize(new Dimension(Short.MAX_VALUE, 16));

        deviceNameLabel = new JLabel("", SwingConstants.CENTER);
        deviceNameLabel.setForeground(LIGHT_GRAY);
        deviceNameLabel.setFont(DATE_FONT);
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
        weatherIconLabel.setForeground(LIGHT_GRAY);
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
        weatherTempLabel.setFont(WEATHER_TEMP_FONT);
        weatherPanel.add(weatherTempLabel, weatherConstraints);

        JPanel iconCondRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 2, 0));
        iconCondRow.setOpaque(false);
        iconCondRow.add(weatherIconLabel);

        weatherConditionLabel = new JLabel("加载中", SwingConstants.CENTER);
        weatherConditionLabel.setForeground(LIGHT_GRAY);
        weatherConditionLabel.setFont(WEATHER_COND_FONT);
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
                    if (!isNotificationActive && !isFinishingNotification && isVisible()) {
                        weatherPanel.setVisible(true);
                    }
                });
            }
        });
        // weatherMonitor.start();

        addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e) {
                toggleExpandedIsland();
            }
        });
    }

    private String getCurrentTime() {
        return LocalTime.now().format(TIME_FORMATTER);
    }

    private String getCurrentDate() {
        return LocalDate.now().format(DATE_FORMATTER);
    }
    
    private void showNotification(String title, String content, boolean isWifiNotification) {
        synchronized (notificationLock) {
            showingNotification = true;
            showingWifiNotification = isWifiNotification;
            isNotificationActive = true;
        }

        try {
            if (notificationTimer != null && notificationTimer.isRunning()) {
                notificationTimer.stop();
            }

            if (weatherPanel != null) {
                SwingUtilities.invokeLater(() -> {
                    weatherPanel.setVisible(false);
                });
            }

            SwingUtilities.invokeLater(() -> {
                timeLabel.setFont(NOTIFY_TITLE_FONT);
                timeLabel.setText(title);
                
                deviceNameLabel.setText(content);
                
                if (textPanel.getComponentCount() > 1) {
                    textPanel.remove(1);
                }
                textConstraints.gridy = 1;
                textConstraints.weightx = 1.0;
                textConstraints.fill = GridBagConstraints.HORIZONTAL;
                textConstraints.anchor = GridBagConstraints.CENTER;
                textPanel.add(deviceNameLabel, textConstraints);
            });

            if (isWifiNotification) {
                startWifiAnim();
            } else {
                startAnim();
            }
            
            SwingUtilities.invokeLater(() -> {
                animPanel.setVisible(true);
                panelRelayout();
            });
            
            updateTextVisibility();
            
            SwingUtilities.invokeLater(() -> {
                if (!isVisible()) {
                    if (trayManager != null) {
                        trayManager.animateShow();
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
            notificationTimer = new Timer(NOTIFICATION_DISPLAY_TIME, e -> {
                stopAnim();
                            
                synchronized (notificationLock) {
                    showingWifiNotification = false;
                }
                            
                SwingUtilities.invokeLater(() -> {
                    animPanel.setVisible(false);
                });
                            
                Timer fontSwitchTimer = new Timer(ANIM_FRAME_MS * 2, switchTask -> {
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
                        } else {
                            setVisible(false);
                            isFinishingNotification = false;
                            isHiding = false;
                            synchronized (notificationLock) {
                                showingNotification = false;
                                isNotificationActive = false;
                            }
                        }
                    });
                                
                    ((Timer)switchTask.getSource()).stop();
                });
                fontSwitchTimer.setRepeats(false);
                fontSwitchTimer.start();
            });
            notificationTimer.setRepeats(false);
            notificationTimer.start();
        } catch (Exception ex) {
            AppLogger.error("IslandWindow", "通知展示异常", ex);
            synchronized (notificationLock) {
                showingNotification = false;
                showingWifiNotification = false;
                isNotificationActive = false;
            }
        }
    }

    private void showBluetoothNotification(String deviceName) {
        if (deviceName == null || deviceName.trim().isEmpty()) {
            return;
        }
        showNotification("蓝牙已连接", deviceName, false);
    }

    private void showWifiNotification(String networkName) {
        if (networkName == null || networkName.trim().isEmpty()) {
            return;
        }
        showNotification("WiFi已连接", networkName, true);
    }

    private void startAnim() {
        startAnimInternal();
    }

    private void startWifiAnim() {
        startAnimInternal();
    }

    private void startAnimInternal() {
        stopAnim();
        animProgress = 0f;
        final long startTime = System.currentTimeMillis();
        animTimer = new Timer(ANIM_FRAME_MS, e -> {
            float elapsed = (System.currentTimeMillis() - startTime) / (float) ANIM_DURATION_MS;
            animProgress = Math.min(elapsed, 1.0f);
            animPanel.repaint();
            if (animProgress >= 1.0f) {
                ((Timer)e.getSource()).stop();
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
        
        SwingUtilities.invokeLater(() -> {
            isAnimating = false;
        });
    }
    
    @Override
    public void setBounds(int x, int y, int width, int height) {
        isAnimating = true;
        super.setBounds(x, y, width, height);
        
        if (textPanel != null) {
            updateTextVisibility();
        }
        
        SwingUtilities.invokeLater(() -> {
            isAnimating = false;
        });
    }
    
    private void updateTextVisibility() {
        if (!isVisible() || isHiding) {
            return;
        }
        
        IslandConfig config = service.getConfig();
        
        double widthRatio = (double) getWidth() / config.width;
        double heightRatio = (double) getHeight() / config.height;
        
        boolean shouldHideText = widthRatio <= TEXT_VISIBLE_THRESHOLD_RATIO || 
                                heightRatio <= TEXT_VISIBLE_THRESHOLD_RATIO;
        
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
    //  扩展岛（深黑色大号药丸窗口）
    // ═══════════════════════════════════════════

    /** 电池状态更新回调（EDT） */
    private void updateBatteryDisplay(BatteryMonitor.BatteryInfo info) {
        currentBatteryInfo = info;
        if (batteryPanel != null) {
            batteryPanel.setVisible(info.present);
            batteryPanel.repaint();
        }
    }

    /** 构建圆环电池仪表（弧线进度 + 居中百分比 + 状态文字） */
    private JPanel buildBatteryPanel() {
        int panelSize = EXPANDED_HEIGHT - 6;

        JPanel pnl = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                BatteryMonitor.BatteryInfo info = currentBatteryInfo;
                if (!info.present) return;

                Graphics2D g2d = (Graphics2D) g.create();
                try {
                    g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2d.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

                    int w = getWidth(), h = getHeight();
                    int ringDiam = Math.min(w, h) - 8;
                    int cx = w / 2, cy = h / 2;
                    int ringOuter = ringDiam / 2;
                    int ringThickness = 5;

                    // 弧线颜色
                    Color arcColor;
                    if (info.charging || info.percentage >= 100) {
                        arcColor = new Color(0x50, 0xDC, 0x64);
                    } else if (info.percentage <= 20) {
                        arcColor = new Color(0xFF, 0x8C, 0x3C);
                    } else {
                        arcColor = Color.WHITE;
                    }

                    // 背景圆环
                    g2d.setColor(new Color(70, 70, 70));
                    g2d.setStroke(new BasicStroke(ringThickness, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g2d.draw(new Arc2D.Double(cx - ringOuter, cy - ringOuter, ringOuter * 2, ringOuter * 2, 0, 360, Arc2D.OPEN));

                    // 前景弧（从12点顺时针）
                    double sweep = -360.0 * info.percentage / 100.0;
                    g2d.setColor(arcColor);
                    g2d.draw(new Arc2D.Double(cx - ringOuter, cy - ringOuter, ringOuter * 2, ringOuter * 2, 90, sweep, Arc2D.OPEN));

                    // ── 居中百分比数字 ──
                    g2d.setColor(Color.WHITE);
                    g2d.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
                    FontMetrics pfm = g2d.getFontMetrics();
                    String pctText = String.valueOf(info.percentage);
                    int pctW = pfm.stringWidth(pctText);
                    int pctY = cy - 2;
                    g2d.drawString(pctText, cx - pctW / 2, pctY);

                    // ── 状态文字（百分比下方）──
                    String statusText;
                    if (!info.present) {
                        statusText = "无电池";
                    } else if (info.charging) {
                        statusText = info.percentage >= 100 ? "满电" : "充电中";
                    } else if (info.percentage >= 100) {
                        statusText = "满电";
                    } else {
                        statusText = "放电";
                    }
                    g2d.setColor(new Color(200, 200, 200));
                    g2d.setFont(new Font("Microsoft YaHei", Font.PLAIN, 9));
                    FontMetrics sfm = g2d.getFontMetrics();
                    int stW = sfm.stringWidth(statusText);
                    int stY = pctY + sfm.getAscent() + 2;
                    g2d.drawString(statusText, cx - stW / 2, stY);
                } finally {
                    g2d.dispose();
                }
            }

            @Override
            public Dimension getPreferredSize() {
                return new Dimension(panelSize, panelSize);
            }
        };
        pnl.setOpaque(false);
        batteryPanel = pnl;
        pnl.setVisible(currentBatteryInfo.present);
        return pnl;
    }

    // ═══════════════════════════════════════════
    //  摄像头 / 麦克风使用状态 — 图标 → 绿点过渡
    // ═══════════════════════════════════════════

    /** 摄像头/麦克风使用状态回调（EDT） */
    private void updateDeviceUsage(boolean camera, boolean mic) {
        if (camera != cameraInUse || mic != micInUse) {
            AppLogger.info("IslandWindow", "设备使用状态变化: camera=" + camera + ", mic=" + mic);
        }
        boolean wasAnyInUse = cameraInUse || micInUse;
        cameraInUse = camera;
        micInUse = mic;
        boolean isAnyInUse = camera || mic;

        // 设备从全部空闲变为首次占用：自动弹出扩展岛（先弹出、后显示图标）
        boolean firstUsage = !wasAnyInUse && isAnyInUse;
        if (firstUsage && !isExpandedIslandVisible() && !isExpanding && !isCollapsing) {
            AppLogger.info("IslandWindow", "检测到设备首次占用，自动弹出扩展岛");
            deviceAutoExpanded = true;
            cancelDeviceAutoHideTimer();
            showExpandedIsland();
            // 图标状态延后到展开动画完成时应用，避免图标在展开中途出现
            return;
        }
        applyUsageStates();
    }

    /** 将当前设备占用状态应用到指示器与绿点（EDT） */
    private void applyUsageStates() {
        applyDesiredState(cameraIndicator, cameraInUse);
        applyDesiredState(micIndicator, micInUse);
        updateDotTarget();
        refreshUsagePanelVisibility();
    }

    /**
     * 清理已结束的设备使用状态残留（仅当无设备占用时生效）：
     * 指示器未完成的淡出阶段与未归零的绿点进度会在扩展岛隐藏时被冻结，
     * 重新展开时会导致旧绿点/图标短暂闪现，需在此统一清零。
     */
    private void cleanupStaleUsageState() {
        if (!cameraInUse && !micInUse) {
            cameraIndicator.phase = UsagePhase.HIDDEN;
            micIndicator.phase = UsagePhase.HIDDEN;
            dotTargetVisible = false;
            dotProgress = 0f;
        }
    }

    /** 将指示器推向目标状态：占用→图标展示，释放→图标淡出消失 */
    private void applyDesiredState(DeviceIndicator ind, boolean inUse) {
        if (inUse) {
            if (ind.phase == UsagePhase.HIDDEN || ind.phase == UsagePhase.FADING_OUT) {
                ind.phase = UsagePhase.ICON;
                ind.phaseStartMs = System.currentTimeMillis();
            }
        } else if (ind.phase != UsagePhase.HIDDEN && ind.phase != UsagePhase.FADING_OUT) {
            if (ind.phase == UsagePhase.MORPHING) {
                // 从交叉渐变中途续接淡出，避免图标透明度跳变
                float e = (System.currentTimeMillis() - ind.phaseStartMs) / (float) USAGE_MORPH_MS;
                ind.phaseStartMs = System.currentTimeMillis() - (long) (e * USAGE_FADE_OUT_MS);
            } else {
                ind.phaseStartMs = System.currentTimeMillis();
            }
            ind.phase = UsagePhase.FADING_OUT;
        }
    }

    /**
     * 更新面板级绿点目标可见性：
     * 只要有设备占用、且所有占用设备的图标展示均已结束，就显示唯一绿点。
     */
    private void updateDotTarget() {
        boolean anyActive = cameraInUse || micInUse;
        boolean iconShowing = (cameraInUse && cameraIndicator.phase == UsagePhase.ICON)
                || (micInUse && micIndicator.phase == UsagePhase.ICON);
        dotTargetVisible = anyActive && !iconShowing;
    }

    /** 推进面板级绿点的淡入/淡出进度（线性插值） */
    private void advanceDotAnimation() {
        if (dotTargetVisible) {
            dotProgress = Math.min(1f, dotProgress + USAGE_ANIM_FRAME_MS / (float) USAGE_MORPH_MS);
        } else {
            dotProgress = Math.max(0f, dotProgress - USAGE_ANIM_FRAME_MS / (float) USAGE_FADE_OUT_MS);
        }
    }

    /** 根据指示器状态同步状态面板可见性与动画定时器 */
    private void refreshUsagePanelVisibility() {
        if (deviceUsagePanel == null) return;
        boolean anyVisible = cameraIndicator.phase != UsagePhase.HIDDEN
                || micIndicator.phase != UsagePhase.HIDDEN
                || dotProgress > 0f;
        boolean wasVisible = deviceUsagePanel.isVisible();
        if (anyVisible != wasVisible) {
            deviceUsagePanel.setVisible(anyVisible);
        }
        startOrStopUsageAnimTimer();
        if (anyVisible != wasVisible && isExpandedIslandVisible()) {
            layoutExpandedPanel();
        }
    }

    private void startOrStopUsageAnimTimer() {
        boolean needed = deviceUsagePanel != null
                && deviceUsagePanel.isVisible()
                && (cameraIndicator.phase != UsagePhase.HIDDEN
                    || micIndicator.phase != UsagePhase.HIDDEN
                    || dotProgress > 0f && (dotTargetVisible ? dotProgress < 1f : true));
        if (needed) {
            if (deviceUsageAnimTimer == null || !deviceUsageAnimTimer.isRunning()) {
                if (deviceUsageAnimTimer != null) deviceUsageAnimTimer.stop();
                deviceUsageAnimTimer = new Timer(USAGE_ANIM_FRAME_MS, e -> tickUsageAnim());
                deviceUsageAnimTimer.start();
            }
        } else {
            stopUsageAnimTimer();
        }
    }

    private void stopUsageAnimTimer() {
        if (deviceUsageAnimTimer != null) {
            deviceUsageAnimTimer.stop();
            deviceUsageAnimTimer = null;
        }
    }

    /** 设备占用自动弹出后，5 秒自动隐藏扩展岛 */
    private void startDeviceAutoHideTimer() {
        if (deviceAutoHideTimer != null) deviceAutoHideTimer.stop();
        deviceAutoHideTimer = new Timer(DEVICE_AUTO_HIDE_MS, e -> {
            deviceAutoHideTimer = null;
            deviceAutoExpanded = false;
            if (!isExpandedIslandVisible()) return;
            if (currentMusicInfo != null && currentMusicInfo.isStrictlyPlaying()) {
                // 音乐播放期间扩展岛保持常驻，跳过设备占用超时隐藏
                AppLogger.info("IslandWindow", "音乐播放中，跳过设备占用超时自动隐藏");
                return;
            }
            AppLogger.info("IslandWindow", "设备占用自动弹出超时，自动隐藏扩展岛");
            hideExpandedIslandSlideUp();
        });
        deviceAutoHideTimer.setRepeats(false);
        deviceAutoHideTimer.start();
    }

    private void cancelDeviceAutoHideTimer() {
        if (deviceAutoHideTimer != null) {
            deviceAutoHideTimer.stop();
            deviceAutoHideTimer = null;
        }
    }

    /** 动画帧：推进阶段状态机并重绘状态面板 */
    private void tickUsageAnim() {
        long now = System.currentTimeMillis();
        boolean sizeMayChange = advanceIndicator(cameraIndicator, now);
        sizeMayChange |= advanceIndicator(micIndicator, now);
        updateDotTarget();
        float dotBefore = dotProgress;
        advanceDotAnimation();
        // 绿点淡入完成或淡出结束时刷新可见性/定时器（淡出完成需隐藏面板）
        boolean dotReachedEnd = (dotBefore > 0f && dotProgress == 0f)
                || (dotBefore < 1f && dotProgress == 1f);
        if (deviceUsagePanel != null && deviceUsagePanel.isVisible()) {
            deviceUsagePanel.repaint();
        }
        if (sizeMayChange || dotReachedEnd) {
            refreshUsagePanelVisibility();
            if (isExpandedIslandVisible()) {
                layoutExpandedPanel();
            }
        }
    }

    /** 推进单个指示器的阶段状态机，返回布局尺寸是否可能变化 */
    private boolean advanceIndicator(DeviceIndicator ind, long now) {
        long elapsed = now - ind.phaseStartMs;
        switch (ind.phase) {
            case ICON:
                if (elapsed >= USAGE_ICON_MS) {
                    ind.phase = UsagePhase.MORPHING;
                    ind.phaseStartMs = now;
                    return true;
                }
                return false;
            case MORPHING:
                if (elapsed >= USAGE_MORPH_MS) {
                    ind.phase = UsagePhase.HIDDEN;
                    return true;
                }
                return false;
            case FADING_OUT:
                if (elapsed >= USAGE_FADE_OUT_MS) {
                    ind.phase = UsagePhase.HIDDEN;
                    return true;
                }
                return false;
            default:
                return false;
        }
    }

    /** 构建摄像头/麦克风使用状态面板（扩展岛最右侧） */
    private JPanel buildDeviceUsagePanel() {
        JPanel pnl = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2d = (Graphics2D) g.create();
                try {
                    g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    int cy = getHeight() / 2;

                    // 唯一绿色圆点：固定在右半圆圆心（面板内坐标 usageDotCenterX）
                    if (dotProgress > 0f) {
                        float e = easeInOutCubic(Math.min(dotProgress, 1f));
                        int dot = Math.max(1, Math.round(USAGE_DOT_DIAMETER * (0.5f + 0.5f * e)));
                        g2d.setColor(new Color(GREEN.getRed(), GREEN.getGreen(), GREEN.getBlue(), (int) (255 * e)));
                        g2d.fillOval(usageDotCenterX - dot / 2, cy - dot / 2, dot, dot);
                    }

                    // 图标并排显示（高度对齐），整体对称于圆心
                    boolean camIcon = cameraIndicator.phase != UsagePhase.HIDDEN;
                    boolean micIcon = micIndicator.phase != UsagePhase.HIDDEN;
                    int count = (camIcon ? 1 : 0) + (micIcon ? 1 : 0);
                    if (count > 0) {
                        int totalW = count * USAGE_ICON_SIZE + (count - 1) * USAGE_SLOT_GAP;
                        int x = (getWidth() - totalW) / 2;
                        if (camIcon) {
                            paintIndicator(g2d, cameraIndicator, x, cy);
                            x += USAGE_ICON_SIZE + USAGE_SLOT_GAP;
                        }
                        if (micIcon) {
                            paintIndicator(g2d, micIndicator, x, cy);
                        }
                    }
                } finally {
                    g2d.dispose();
                }
            }

            @Override
            public Dimension getPreferredSize() {
                int count = 0;
                if (cameraIndicator.phase != UsagePhase.HIDDEN) count++;
                if (micIndicator.phase != UsagePhase.HIDDEN) count++;
                int iconsW = count * USAGE_ICON_SIZE + Math.max(0, count - 1) * USAGE_SLOT_GAP;
                // 无图标时保留绿点尺寸，保持面板中心稳定在圆心
                int w = USAGE_PANEL_PAD * 2 + Math.max(iconsW, USAGE_ICON_SIZE);
                return new Dimension(w, EXPANDED_HEIGHT - 8);
            }
        };
        pnl.setOpaque(false);
        boolean anyVisible = cameraIndicator.phase != UsagePhase.HIDDEN
                || micIndicator.phase != UsagePhase.HIDDEN
                || dotProgress > 0f;
        pnl.setVisible(anyVisible);
        deviceUsagePanel = pnl;
        return pnl;
    }

    /**
     * 绘制单个状态项图标：
     * ICON 阶段弹入显示；MORPHING 阶段淡出缩小（绿点同步由面板绘制）；
     * FADING_OUT 阶段图标淡出消失。
     */
    private void paintIndicator(Graphics2D g2d, DeviceIndicator ind, int x, int cy) {
        long elapsed = System.currentTimeMillis() - ind.phaseStartMs;
        float iconAlpha;
        float iconScale;
        switch (ind.phase) {
            case ICON: {
                float pop = Math.min(elapsed / 160f, 1f);
                iconAlpha = 1f;
                iconScale = 0.7f + 0.3f * pop;
                break;
            }
            case MORPHING: {
                float p = Math.min(elapsed / (float) USAGE_MORPH_MS, 1f);
                float e = easeInOutCubic(p);
                iconAlpha = 1f - e;
                iconScale = 1f - 0.4f * e;
                break;
            }
            case FADING_OUT: {
                float p = Math.min(elapsed / (float) USAGE_FADE_OUT_MS, 1f);
                float e = easeInOutCubic(p);
                iconAlpha = 1f - e;
                iconScale = 1f - 0.4f * e;
                break;
            }
            default:
                return;
        }

        int cx = x + USAGE_ICON_SIZE / 2;
        if (iconAlpha > 0f && ind.icon != null) {
            int size = Math.max(1, Math.round(USAGE_ICON_SIZE * iconScale));
            g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, iconAlpha));
            g2d.drawImage(ind.icon, cx - size / 2, cy - size / 2, size, size, null);
            g2d.setComposite(AlphaComposite.SrcOver);
        }
    }

    private float easeInOutCubic(float x) {
        return x < 0.5f ? 4f * x * x * x : 1f - (float) Math.pow(-2f * x + 2f, 3) / 2f;
    }

    /** 滚轮向下触发：在扩展岛中显示占位面板或音乐面板 */
    private void showMusicPanelInExpandedIsland() {
        if (expandedWindow == null || !expandedWindow.isVisible()) return;
        if (musicPanelShownInExpanded) return;
        musicPanelShownInExpanded = true;

        boolean hasActiveSession = currentMusicInfo.hasSession()
                && !currentMusicInfo.getTitle().isEmpty();

        if (hasActiveSession) {
            // 已有媒体会话 → 直接显示音乐面板
            System.out.println("[IslandWindow] 滚轮向下：已有会话，直接显示音乐面板");
            buildMusicPanel();
            ensureMusicPanelInExpandedWindow();
            if (currentMusicInfo.isStrictlyPlaying()) {
                startCoverRotation();
                startLyricScrollTimer();
            }
            startSlideAnimation(1.0f);
        } else {
            // 无媒体会话 → 显示占位面板
            System.out.println("[IslandWindow] 滚轮向下：无会话，显示占位面板");
            if (placeholderPanel == null) {
                placeholderPanel = new JPanel(new GridBagLayout());
                placeholderPanel.setOpaque(false);
                JLabel msg = new JLabel("等待播放...", SwingConstants.CENTER);
                msg.setForeground(new Color(140, 140, 140));
                msg.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
                placeholderPanel.add(msg);
            }
            Container cp = expandedWindow.getContentPane();
            if (cp.getComponentCount() > 0) {
                Component root = cp.getComponent(0);
                if (root instanceof JPanel) {
                    JPanel rp = (JPanel) root;
                    // 移除旧音乐面板，确保右侧卡片只有占位面板
                    if (musicPanel != null && musicPanel.getParent() == rp) {
                        rp.remove(musicPanel);
                    }
                    boolean found = false;
                    for (Component c : rp.getComponents()) {
                        if (c == placeholderPanel) { found = true; break; }
                    }
                    if (!found) {
                        rp.add(placeholderPanel);
                        rp.revalidate();
                        layoutExpandedPanel();
                    }
                }
            }
            startSlideAnimation(1.0f);
        }
    }

    private void startSlideAnimation(float target) {
        if (gestureSlideAnimTimer != null) gestureSlideAnimTimer.stop();
        final float from = gestureSlideProgress;
        final float to = target;
        final long animStart = System.currentTimeMillis();
        gestureSlideAnimTimer = new Timer(SLIDE_ANIM_FRAME_MS, e -> {
            float elapsed = (System.currentTimeMillis() - animStart) / (float) SLIDE_ANIM_DURATION_MS;
            float t = Math.min(elapsed, 1.0f);
            float eased = 1 - (1 - t) * (1 - t) * (1 - t) * (1 - t); // quartic ease-out
            gestureSlideProgress = from + (to - from) * eased;
            layoutExpandedPanel();
            if (t >= 1.0f) { gestureSlideAnimTimer.stop(); gestureSlideAnimTimer = null; }
        });
        gestureSlideAnimTimer.start();
    }

    private void layoutExpandedPanel() {
        if (expandedWindow == null) return;
        Container cp = expandedWindow.getContentPane();
        if (cp.getComponentCount() == 0) return;
        Component root = cp.getComponent(0);
        if (!(root instanceof JPanel)) return;
        JPanel rp = (JPanel) root;
        int pw = rp.getWidth(), ph = rp.getHeight();
        Insets ins = rp.getInsets();
        if (ins == null) ins = new Insets(4, 8, 4, 12);
        int iw = pw - ins.left - ins.right, ih = ph - ins.top - ins.bottom;
        float progress = Math.max(0f, Math.min(1f, gestureSlideProgress));
        int offset = (int) (iw * progress);

        for (Component c : rp.getComponents()) {
            if (c == batteryPanel && batteryPanel != null) {
                // 电池圆环与左侧半圆圆心精确对齐
                int leftCenterX = EXPANDED_HEIGHT / 2;
                int leftCenterY = EXPANDED_HEIGHT / 2;
                int battSize = batteryPanel.getPreferredSize().width;
                int battX = leftCenterX - battSize / 2;
                int battY = leftCenterY - battSize / 2;
                batteryPanel.setBounds(battX - offset, battY, battSize, battSize);
            } else if (c == placeholderPanel && placeholderPanel != null) {
                placeholderPanel.setBounds(ins.left + iw - offset, ins.top, iw, ih);
                placeholderPanel.revalidate();
            } else if (c == deviceUsagePanel && deviceUsagePanel != null) {
                // 保持使用状态面板位于最顶层，避免被滑入的音乐面板遮挡
                rp.setComponentZOrder(deviceUsagePanel, 0);
                Dimension pref = deviceUsagePanel.getPreferredSize();
                // 面板中心对齐右半圆圆心；若超窗则整体左移保证完整可见
                int centerX = pw - EXPANDED_HEIGHT / 2;
                int x = centerX - pref.width / 2;
                if (x + pref.width > pw - ins.right) {
                    x = pw - ins.right - pref.width;
                }
                usageDotCenterX = centerX - x;
                deviceUsagePanel.setBounds(x, (ph - pref.height) / 2, pref.width, pref.height);
            } else if (c == musicPanel && musicPanel != null) {
                int coverCenterX = EXPANDED_HEIGHT / 2 + 4;
                int musicX = coverCenterX - COVER_SIZE / 2;
                // 为右侧使用状态面板预留空间，避免内容重叠
                int reserved = (deviceUsagePanel != null && deviceUsagePanel.isVisible())
                        ? deviceUsagePanel.getPreferredSize().width + USAGE_SLOT_GAP : 0;
                int musicW = pw - ins.right - musicX - reserved;
                musicPanel.setBounds(musicX + iw - offset, ins.top, musicW, ih);
                musicPanel.revalidate();
            }
        }
        rp.revalidate();
        rp.repaint();
    }

    private void toggleExpandedIsland() {
        if (isExpanding || isCollapsing) {
            return;
        }
        if (expandedWindow != null && expandedWindow.isVisible()) {
            hideExpandedIslandByUser();
        } else {
            showExpandedIsland();
        }
    }

    /** 用户手动折叠扩展岛：播放期间折叠后本次会话不再自动弹出音乐岛，避免打扰 */
    private void hideExpandedIslandByUser() {
        if (currentMusicInfo != null && currentMusicInfo.isStrictlyPlaying()) {
            musicPopupSuppressedByUser = true;
        }
        hideExpandedIsland();
    }

    private void showExpandedIsland() {
        if (expandedWindow != null) {
            expandedWindow.dispose();
        }
        // 记录本次展开是否由用户主动点击触发（设备/音乐自动弹出不参与空闲自动收起）
        final boolean userInitiated = !deviceAutoExpanded && !musicAutoExpanded;
        stopIdleAutoCollapseTimer();
        isExpanding = true;

        // 重置卡片切换状态
        gestureSlideProgress = 0f;
        musicPanelShownInExpanded = false;

        Point startLoc = getLocation();
        int startW = getWidth();
        int startH = getHeight();

        service.hide();
        setVisible(false);

        Toolkit toolkit = Toolkit.getDefaultToolkit();
        Dimension screenSize = toolkit.getScreenSize();
        int targetX = (screenSize.width - EXPANDED_WIDTH) / 2;
        int targetY = 0;
        int targetH = EXPANDED_HEIGHT;

        expandedWindow = new JWindow();
        expandedWindow.setBackground(TRANSPARENT_BLACK);
        expandedWindow.setAlwaysOnTop(true);
        expandedWindow.setLocation(startLoc);
        expandedWindow.setSize(startW, startH);

        JPanel panel = new JPanel(null) {
            @Override
            public void paint(Graphics g) {
                Shape oldClip = null;
                if (g instanceof Graphics2D) {
                    Graphics2D g2d = (Graphics2D) g;
                    g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    oldClip = g2d.getClip();
                    int arc = getHeight();
                    g2d.setClip(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), arc, arc));
                }
                try {
                    super.paint(g);
                } finally {
                    // 无条件恢复原 clip（可能为 null，setClip(null) 表示清除裁剪），防止圆角 clip 泄漏到后续绘制
                    if (g instanceof Graphics2D) {
                        ((Graphics2D) g).setClip(oldClip);
                    }
                }
            }

            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2d = (Graphics2D) g.create();
                try {
                    g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    int arc = getHeight();
                    g2d.setColor(DEEP_BLACK);
                    g2d.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
                } finally {
                    g2d.dispose();
                }
            }
        };
        panel.setOpaque(false);

        JPanel battPnl = buildBatteryPanel();
        panel.add(battPnl);

        // 构建前清理已结束的设备使用状态残留，避免旧绿点/图标在新展开时闪现
        cleanupStaleUsageState();
        deviceUsagePanel = buildDeviceUsagePanel();
        panel.add(deviceUsagePanel);

        expandedWindow.getContentPane().add(panel);

        // ── 滚轮监听：向下滚动 = 右滑（显示右侧卡片），向上滚动 = 左滑（返回左侧卡片）──
        expandedWindow.addMouseWheelListener(e -> {
            int rotation = e.getWheelRotation();
            if (rotation == 0) return;
            if (rotation > 0) {
                // 向下滚动 → 右滑 → 切换至音乐/占位面板
                System.out.println("[IslandWindow] 滚轮向下 → 右滑，切换至音乐/占位面板");
                showMusicPanelInExpandedIsland();
            } else if (musicPanelShownInExpanded) {
                // 向上滚动 → 左滑 → 返回电池卡片
                System.out.println("[IslandWindow] 滚轮向上 → 左滑，返回电池卡片");
                musicPanelShownInExpanded = false;
                startSlideAnimation(0f);
            }
        });

        // ── 单击：折叠扩展岛（与滚轮切换互不冲突）──
        expandedWindow.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                hideExpandedIslandByUser();
            }
        });

        expandedWindow.setVisible(true);

        Timer expandTimer = new Timer(EXPAND_ANIM_FRAME_MS, null);
        final long animStart = System.currentTimeMillis();
        expandTimer.addActionListener(e -> {
            float elapsed = System.currentTimeMillis() - animStart;
            float progress = Math.min(elapsed / EXPAND_ANIM_DURATION_MS, 1.0f);
            double eased = 1 - (1 - progress) * (1 - progress);

            int curW = (int) (startW + (EXPANDED_WIDTH - startW) * eased);
            int curH = (int) (startH + (targetH - startH) * eased);
            int curX = (int) (startLoc.x + (targetX - startLoc.x) * eased);
            int curY = (int) (startLoc.y + (targetY - startLoc.y) * eased);

            expandedWindow.setSize(curW, curH);
            expandedWindow.setLocation(curX, curY);

            if (progress >= 1.0f) {
                ((Timer) e.getSource()).stop();
                expandedWindow.setSize(EXPANDED_WIDTH, targetH);
                expandedWindow.setLocation(targetX, targetY);
                isExpanding = false;
                layoutExpandedPanel();
                startOrStopUsageAnimTimer();
                // 设备占用触发的自动弹出：展开完成后再显示图标，并启动 5 秒自动隐藏计时
                if (deviceAutoExpanded) {
                    applyUsageStates();
                    if (currentMusicInfo != null && currentMusicInfo.isStrictlyPlaying()) {
                        // 音乐播放中：取消设备 5 秒自动隐藏，改为展示音乐面板常驻
                        cancelDeviceAutoHideTimer();
                        deviceAutoExpanded = false;
                        musicAutoExpanded = true;
                        SwingUtilities.invokeLater(() -> {
                            musicPanelAutoShownForSession = true;
                            showMusicPanelInExpandedIsland();
                        });
                    } else {
                        startDeviceAutoHideTimer();
                    }
                } else if (musicAutoExpanded) {
                    // 音乐自动弹出：展开完成后展示音乐面板（封面/歌词/歌名）
                    SwingUtilities.invokeLater(() -> {
                        musicPanelAutoShownForSession = true;
                        showMusicPanelInExpandedIsland();
                    });
                } else if (userInitiated) {
                    // 用户主动展开：启动空闲自动收起巡检（勾选设置后生效）
                    startIdleAutoCollapseTimer();
                }
            }
        });
        expandTimer.setInitialDelay(0);
        expandTimer.start();
    }

    public boolean isExpandedIslandVisible() {
        return expandedWindow != null && expandedWindow.isVisible();
    }

    /** 间接隐藏：扩展岛收缩回主岛（往返形态过渡，保持原有尺寸/位置插值动画） */
    private void hideExpandedIsland() {
        hideExpandedIslandInternal(false);
    }

    /** 直接隐藏：扩展岛整体向上平移滑出屏幕顶部（自动隐藏场景，不做形态收缩） */
    private void hideExpandedIslandSlideUp() {
        hideExpandedIslandInternal(true);
    }

    private void hideExpandedIslandInternal(boolean slideUp) {
        if (expandedWindow == null || isCollapsing) {
            return;
        }

        // 手动折叠（或自动隐藏执行）时取消 5 秒自动隐藏计时
        cancelDeviceAutoHideTimer();
        deviceAutoExpanded = false;
        stopIdleAutoCollapseTimer();
        cancelMusicStopAutoHideTimer();
        musicAutoExpanded = false;
        musicPanelAutoShownForSession = false;

        // 重置卡片切换状态
        gestureSlideProgress = 0f;
        musicPanelShownInExpanded = false;
        if (gestureSlideAnimTimer != null) { gestureSlideAnimTimer.stop(); gestureSlideAnimTimer = null; }

        isCollapsing = true;

        stopCoverRotation();
        stopLyricScrollTimer();
        stopUsageAnimTimer();

        for (java.awt.event.MouseListener ml : expandedWindow.getMouseListeners()) {
            expandedWindow.removeMouseListener(ml);
        }

        for (java.awt.event.MouseWheelListener wl : expandedWindow.getMouseWheelListeners()) {
            expandedWindow.removeMouseWheelListener(wl);
        }

        // 直接隐藏：先隐藏扩展岛内部 UI 内容，避免收缩成小球过程中内容溢出或残影
        if (slideUp) {
            Container cp = expandedWindow.getContentPane();
            if (cp.getComponentCount() > 0) {
                Component root = cp.getComponent(0);
                if (root instanceof JPanel) {
                    for (Component c : ((JPanel) root).getComponents()) {
                        c.setVisible(false);
                    }
                }
            }
        }

        Point startLoc = expandedWindow.getLocation();
        int startW = expandedWindow.getWidth();
        int startH = expandedWindow.getHeight();

        Point targetLoc = this.getLocation();
        IslandConfig config = service.getConfig();
        int targetW = config.width;
        int targetH = config.height;

        Timer collapseTimer = new Timer(EXPAND_ANIM_FRAME_MS, null);
        final long animStart = System.currentTimeMillis();
        final int[] slideUpPhase = {0};
        final long[] slideUpPhaseStart = {animStart};
        final boolean[] windowHidden = {false};
        collapseTimer.addActionListener(e -> {
            float elapsed = System.currentTimeMillis() - animStart;
            float progress = Math.min(elapsed / EXPAND_ANIM_DURATION_MS, 1.0f);

            boolean animationDone;
            if (slideUp) {
                // 直接隐藏：先收缩成小球（保持中心点），再向上滑出屏幕顶部。
                // 全程窗口可见，收缩与上滑过程清晰呈现（收尾时统一隐藏窗口）
                long phaseElapsed = System.currentTimeMillis() - slideUpPhaseStart[0];
                int phaseMs = slideUpPhase[0] == 0 ? SLIDE_UP_SHRINK_MS : SLIDE_UP_RISE_MS;
                float phaseProgress = Math.min(phaseElapsed / (float) phaseMs, 1.0f);
                double pe = AnimationUtil.easeInOutQuad(phaseProgress);
                int ball = AppConstants.BALL_SIZE;
                int centerX = startLoc.x + startW / 2;
                int centerY = startLoc.y + startH / 2;
                if (slideUpPhase[0] == 0) {
                    // 阶段1：从两边向中间收缩成小球（保持中心点不变，全程可见）
                    int newW = (int) (startW - (startW - ball) * pe);
                    int newH = (int) (startH - (startH - ball) * pe);
                    expandedWindow.setSize(newW, newH);
                    expandedWindow.setLocation(centerX - newW / 2, centerY - newH / 2);
                    if (phaseProgress >= 1.0f) {
                        slideUpPhase[0] = 1;
                        slideUpPhaseStart[0] = System.currentTimeMillis();
                    }
                    animationDone = false;
                } else {
                    // 阶段2：小球向上滑出屏幕顶部（可见滑出，自然消失）
                    int startY = centerY - ball / 2;
                    int targetY = -ball;
                    int curY = (int) (startY + (targetY - startY) * pe);
                    expandedWindow.setLocation(centerX - ball / 2, curY);
                    animationDone = phaseProgress >= 1.0f;
                }
            } else {
                // 间接隐藏：收缩回主岛位置，展开动画的严格对称反向镜像
                // 二次加速缓动 progress²，与展开动画的二次减速缓动 1-(1-p)² 时间上完全对称；
                // 窗口全程可见（与展开动画一致），仅动画完成收尾时统一隐藏
                double eased = progress * progress;
                int curW = (int) (startW + (targetW - startW) * eased);
                int curH = (int) (startH + (targetH - startH) * eased);
                int curX = (int) (startLoc.x + (targetLoc.x - startLoc.x) * eased);
                int curY = (int) (startLoc.y + (targetLoc.y - startLoc.y) * eased);
                expandedWindow.setSize(curW, curH);
                expandedWindow.setLocation(curX, curY);
                animationDone = progress >= 1.0f;
            }

            if (animationDone) {
                ((Timer) e.getSource()).stop();
                if (!windowHidden[0]) {
                    expandedWindow.setVisible(false);
                }
                expandedWindow.dispose();
                expandedWindow = null;
                isCollapsing = false;
                // 收尾统一清理已结束的设备使用状态残留（设备仍在占用时不受影响）
                cleanupStaleUsageState();

                // 重置音乐面板状态
                musicPanelInitialized = false;
                musicPanel = null;
                musicCoverLabel = null;
                musicTitleLabel = null;
                musicArtistLabel = null;
                musicLyricsLabel = null;
                placeholderPanel = null;
                deviceUsagePanel = null;

                SwingUtilities.invokeLater(() -> {
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
                });
            }
        });
        collapseTimer.setInitialDelay(0);
        collapseTimer.start();
    }

    // ═══════════════════════════════════════════
    //  音乐面板 — 封面旋转 + UI 布局
    // ═══════════════════════════════════════════

    private void buildMusicPanel() {
        if (musicPanelInitialized) return;
        musicPanelInitialized = true;

        musicPanel = new JPanel(new BorderLayout(10, 0));
        musicPanel.setOpaque(false);

        // ── 左侧：旋转封面（3x 超采样 + 正圆形裁剪）──
        musicCoverLabel = new JLabel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                int w = getWidth(), h = getHeight();
                if (w <= 0 || h <= 0) return;

                // 3x 超采样：在 144px 画布上渲染，再缩至 48px，消除旋转锯齿
                int ssaaSize = COVER_HIRES;
                BufferedImage buffer = new BufferedImage(ssaaSize, ssaaSize, BufferedImage.TYPE_INT_ARGB);
                Graphics2D bg2d = buffer.createGraphics();
                try {
                    bg2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    bg2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    bg2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

                    Image cover = musicCoverImage;
                    if (cover == null) {
                        // 占位：深灰正圆 + ♫
                        int cx = ssaaSize / 2, cy = ssaaSize / 2, r = ssaaSize / 2;
                        bg2d.setColor(new Color(60, 60, 60));
                        bg2d.fill(new Ellipse2D.Double(0, 0, ssaaSize, ssaaSize));
                        bg2d.setColor(new Color(140, 140, 140));
                        bg2d.setFont(new Font("Microsoft YaHei", Font.PLAIN, 42));
                        FontMetrics fm = bg2d.getFontMetrics();
                        String note = "\u266B";
                        bg2d.drawString(note, cx - fm.stringWidth(note) / 2, cy + fm.getAscent() / 2 - 2);
                    } else {
                        // 旋转 + 绘制超采样封面
                        AffineTransform old = bg2d.getTransform();
                        bg2d.rotate(Math.toRadians(coverRotationAngle), ssaaSize / 2.0, ssaaSize / 2.0);
                        bg2d.drawImage(cover, 0, 0, ssaaSize, ssaaSize, null);
                        bg2d.setTransform(old);
                    }
                } finally {
                    bg2d.dispose();
                }

                // 高质量缩小至目标尺寸，消除旋转锯齿
                Graphics2D g2d = (Graphics2D) g.create();
                try {
                    g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                    int size = Math.min(w, h);
                    int x = (w - size) / 2, y = (h - size) / 2;
                    g2d.drawImage(buffer, x, y, size, size, null);
                } finally {
                    g2d.dispose();
                }
            }
        };
        musicCoverLabel.setOpaque(false);
        musicCoverLabel.setPreferredSize(new Dimension(COVER_SIZE, COVER_SIZE));
        musicCoverLabel.setMinimumSize(new Dimension(COVER_SIZE, COVER_SIZE));
        musicPanel.add(musicCoverLabel, BorderLayout.WEST);

        // ── 中央：歌词（上） + 歌名-艺术家（下）──
        JPanel infoPanel = new JPanel(new GridBagLayout());
        infoPanel.setOpaque(false);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0; gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;

        // 歌词行
        gbc.gridy = 0; gbc.insets = new Insets(2, 4, 0, 0);
        musicLyricsLabel = new JLabel(" ") {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2d = (Graphics2D) g.create();
                try {
                    g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    g2d.setFont(MUSIC_LYRICS_FONT);
                    FontMetrics fm = g2d.getFontMetrics();
                    List<LyricItem> lines = lrcLines;
                    if (lines.isEmpty() || currentLyricIndex < 0) {
                        g2d.setColor(new Color(255, 255, 255, 100));
                        String ph = currentMusicInfo != null && currentMusicInfo.hasSession() ? "歌词加载中..." : " ";
                        g2d.drawString(ph, 4, getHeight() / 2 + fm.getAscent() / 2 - 1);
                        return;
                    }
                    g2d.setColor(Color.WHITE);
                    g2d.drawString(lines.get(currentLyricIndex).content, 4, getHeight() / 2 + fm.getAscent() / 2 - 1);
                } finally {
                    g2d.dispose();
                }
            }
        };
        musicLyricsLabel.setForeground(Color.WHITE);
        musicLyricsLabel.setFont(MUSIC_LYRICS_FONT);
        musicLyricsLabel.setMinimumSize(new Dimension(100, 18));
        musicLyricsLabel.setPreferredSize(new Dimension(350, 18));
        infoPanel.add(musicLyricsLabel, gbc);

        // 歌名 + 艺术家
        gbc.gridy = 1; gbc.insets = new Insets(1, 4, 2, 0);
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        row.setOpaque(false);
        musicTitleLabel = new JLabel("");
        musicTitleLabel.setForeground(new Color(180, 180, 180));
        musicTitleLabel.setFont(MUSIC_TITLE_FONT);
        row.add(musicTitleLabel);
        JLabel dash = new JLabel(" - ");
        dash.setForeground(new Color(140, 140, 140));
        dash.setFont(MUSIC_ARTIST_FONT);
        row.add(dash);
        musicArtistLabel = new JLabel("");
        musicArtistLabel.setForeground(new Color(140, 140, 140));
        musicArtistLabel.setFont(MUSIC_ARTIST_FONT);
        row.add(musicArtistLabel);
        infoPanel.add(row, gbc);

        musicPanel.add(infoPanel, BorderLayout.CENTER);
    }

    // ═══════════════════════════════════════════
    //  音乐状态机
    // ═══════════════════════════════════════════

    private void updateMusicInfo(MusicInfo info) {
        if (info == null) return;
        boolean wasPlaying = currentMusicInfo.isPlaying();
        boolean isPlaying = info.isPlaying();
        boolean wasStrictly = currentMusicInfo.isStrictlyPlaying();
        boolean isStrictly = info.isStrictlyPlaying();
        boolean wasSession = currentMusicInfo.hasSession();
        currentMusicInfo = info;

        // 检测活跃播放器切换：另一个播放器开始播放了
        boolean activeSourceSwitched = info.isStrictlyPlaying()
                && !info.getSourceAppId().isEmpty()
                && !info.getSourceAppId().equals(lastActiveSourceAppId);
        if (activeSourceSwitched) {
            AppLogger.info("IslandWindow", "活跃播放器切换: "
                    + lastActiveSourceAppId + " → " + info.getSourceAppId());
            lastActiveSourceAppId = info.getSourceAppId();
            // 强制刷新歌词和封面，因为播放器来源变了
            lyricsService.clear();
            lrcLines = Collections.emptyList();
            currentLyricIndex = -1;
            lastDaemonEndTimeMs = 0;
            fetchingLyrics = false;
            fetchingCover = false;
            // 保留旧封面显示，新封面异步到达后无缝替换，避免切换瞬间封面闪失
            lastFetchedTrackId = "";
            lastFetchedCoverTrackId = "";
            if (musicLyricsLabel != null) { musicLyricsLabel.setText(" "); musicLyricsLabel.repaint(); }
        }

        System.out.println("[IslandWindow] updateMusicInfo: wasPlaying=" + wasPlaying
                + " isPlaying=" + isPlaying + " expandedVisible=" + isExpandedIslandVisible()
                + " srcSwitched=" + activeSourceSwitched);

        // 歌词进度完全依赖 daemon 汇报的 positionTicks

        String trackId = info.getTitle() + "|" + info.getArtist();
        if (info.hasSession() && !trackId.equals(lastFetchedTrackId) && !info.getTitle().isEmpty()) {
            lastFetchedTrackId = trackId;
            if (!activeSourceSwitched) {
                // 切歌时仅重置歌词状态；封面保留显示，新封面异步到达后无缝替换，避免闪失
                lyricsService.clear();
                lrcLines = Collections.emptyList();
                currentLyricIndex = -1;
                lastDaemonEndTimeMs = 0;
                fetchingLyrics = false;
                fetchingCover = false;
                if (musicLyricsLabel != null) { musicLyricsLabel.setText(" "); musicLyricsLabel.repaint(); }
            }
            fetchLyricsAsync(info.getTitle(), info.getArtist());
            fetchCoverAsync(info.getTitle(), info.getArtist());
            // 面板已显示时立即应用新曲目信息（歌名/艺术家/SMTC 封面）
            if (musicPanelShownInExpanded && musicPanel != null) {
                updateMusicPanelContent();
            }
        }

        // SMTC 缩略图优先：b64 到达/变化时立即应用，覆盖可能先到的 URL 封面
        if (musicPanelShownInExpanded && musicPanel != null
                && !info.getThumbnailBase64().isEmpty()
                && !info.getThumbnailBase64().equals(lastCoverBase64)) {
            updateMusicPanelContent();
        }

        // 媒体会话出现：占位面板 → 自动切换到音乐面板
        if (info.hasSession() && !wasSession && musicPanelShownInExpanded
                && placeholderPanel != null && placeholderPanel.getParent() != null) {
            System.out.println("[IslandWindow] 媒体会话出现，自动切换到音乐面板");
            ensureMusicPanelInExpandedWindow();
        }

        // ── 音乐岛自动弹出与常驻 ──
        if (activeSourceSwitched) {
            musicPopupSuppressedByUser = false;
            musicPanelAutoShownForSession = false;
        }
        updateMusicIslandAutoPopup(info);

        // 严格播放恢复（暂停→播放、会话恢复等）：取消停止满 2 分钟自动收回计时，继续常驻
        boolean playbackResumed = isStrictly && !wasStrictly;
        boolean sessionRestored = isPlaying && !wasPlaying;
        if (playbackResumed || sessionRestored) {
            cancelMusicStopAutoHideTimer();
            musicPopupSuppressedByUser = false;
            if (playbackResumed && musicPanelShownInExpanded) { startCoverRotation(); startLyricScrollTimer(); }
        } else if (wasStrictly && !isStrictly) {
            // 停止播放（变为暂停/停止/会话丢失）：每次暂停均启动 2 分钟自动收回计时，
            // 到期时仅在扩展岛显示音乐面板的情况下才真正收回（见 startMusicStopAutoHideTimer）
            stopCoverRotation(); stopLyricScrollTimer();
            // 仅重置歌词游标；保留 lastFetchedTrackId 与已拉取的歌词/封面，
            // 避免暂停后恢复播放同一首歌时被切歌检测误判，导致封面被清空重新拉取而短暂消失
            currentLyricIndex = -1;
            musicPopupSuppressedByUser = false;
            musicPanelAutoShownForSession = false;
            if (isExpandedIslandVisible()) startMusicStopAutoHideTimer();
        } else if (isPlaying && isExpandedIslandVisible()) {
            if (musicPanelShownInExpanded) {
                if (isStrictly) {
                    if (coverRotationTimer == null) startCoverRotation();
                    if (lyricScrollTimer == null) startLyricScrollTimer();
                }
                updateProgressDisplay(info);
            }
        }
    }

    /**
     * 音乐岛自动弹出逻辑：
     * 音乐严格播放且播放器窗口最小化/不可见时，若扩展岛未显示或尚未显示音乐面板，
     * 主动弹出扩展岛并展示音乐面板；播放期间同时取消设备占用自动隐藏，保证常驻。
     */
    private void updateMusicIslandAutoPopup(MusicInfo info) {
        if (!info.isStrictlyPlaying()) return;
        if (isExpanding || isCollapsing) return;
        // 播放期间扩展岛常驻：取消设备 5 秒自动隐藏与停止收回计时
        cancelDeviceAutoHideTimer();
        deviceAutoExpanded = false;
        cancelMusicStopAutoHideTimer();
        if (!info.isPlayerMinimized()) return;
        if (musicPopupSuppressedByUser) return;
        if (musicPanelShownInExpanded) {
            musicPanelAutoShownForSession = true;
            return;
        }
        if (!isExpandedIslandVisible()) {
            AppLogger.info("IslandWindow", "检测到音乐播放且播放器最小化，自动弹出音乐岛");
            musicAutoExpanded = true;
            musicPanelAutoShownForSession = true;
            showExpandedIsland();
        } else if (!musicPanelAutoShownForSession) {
            musicPanelAutoShownForSession = true;
            showMusicPanelInExpandedIsland();
        }
    }

    private void ensureMusicPanelInExpandedWindow() {
        if (expandedWindow == null || !expandedWindow.isVisible()) return;
        buildMusicPanel();
        if (musicPanel == null) return;
        Container cp = expandedWindow.getContentPane();
        if (cp.getComponentCount() == 0) return;
        Component root = cp.getComponent(0);
        if (!(root instanceof JPanel)) return;
        JPanel rp = (JPanel) root;

        // 移除占位面板
        boolean replacedPlaceholder = false;
        if (placeholderPanel != null && placeholderPanel.getParent() == rp) {
            rp.remove(placeholderPanel);
            replacedPlaceholder = true;
            System.out.println("[IslandWindow] 占位面板已移除，切换到音乐面板");
        }

        // 添加音乐面板（如未添加）
        boolean found = false;
        for (Component c : rp.getComponents()) {
            if (c == musicPanel) { found = true; break; }
        }
        if (!found) {
            updateMusicPanelContent();
            rp.add(musicPanel);
            System.out.println("[IslandWindow] 音乐面板已添加到扩展岛");
        } else {
            updateMusicPanelContent();
        }
        // 仅布局变更时才触发全量重排，避免歌词闪烁
        if (replacedPlaceholder || !found) {
            layoutExpandedPanel();
        }
    }

    private void updateMusicPanelContent() {
        if (musicTitleLabel == null || currentMusicInfo == null) return;
        String title = currentMusicInfo.getTitle();
        String artist = currentMusicInfo.getArtist();
        String fullTitle = title, fullArtist = artist;
        if (title.length() > 15) title = title.substring(0, 14) + "...";
        musicTitleLabel.setText(title.isEmpty() ? "未知歌曲" : title);
        if (artist.length() > 12) artist = artist.substring(0, 11) + "...";
        musicArtistLabel.setText(artist.isEmpty() ? "未知艺术家" : artist);

        System.out.println("[IslandWindow] updateMusicPanelContent: title=" + fullTitle
                + " artist=" + fullArtist + " hasLyrics=" + !lrcLines.isEmpty()
                + " hasCover=" + (currentMusicInfo.getThumbnailBase64().length() > 0));

        if (!lrcLines.isEmpty()) { updateProgressDisplay(currentMusicInfo); }
        else { if (musicLyricsLabel != null) musicLyricsLabel.setText(" "); fetchLyricsAsync(fullTitle, fullArtist); }

        // 封面：SMTC Base64 优先，未变化时跳过重复解码
        String b64 = currentMusicInfo.getThumbnailBase64();
        if (!b64.isEmpty()) {
            if (!b64.equals(lastCoverBase64)) {
                smTcCoverAppliedTrackId = "";
                try {
                    byte[] data = Base64.getDecoder().decode(b64);
                    Image raw = Toolkit.getDefaultToolkit().createImage(data);
                    MediaTracker mt = new MediaTracker(new JLabel());
                    mt.addImage(raw, 0); mt.waitForID(0, 1000);
                    if (raw.getWidth(null) > 0) {
                        musicCoverImage = createCircularCover(raw, COVER_HIRES);
                        lastCoverBase64 = b64;
                        smTcCoverAppliedTrackId = fullTitle + "|" + fullArtist;
                    }
                } catch (Exception ex) {
                    // 解码失败：保留当前封面显示，标记保持未应用，让 URL 源补位，避免封面卡死
                }
            } else {
                smTcCoverAppliedTrackId = fullTitle + "|" + fullArtist;
            }
        } else {
            smTcCoverAppliedTrackId = "";
            // 避免每轮询重复发起请求或清空已显示的封面
            String currentTrackId = fullTitle + "|" + fullArtist;
            boolean alreadyFetching = fetchingCover && currentTrackId.equals(lastFetchedCoverTrackId);
            if (!alreadyFetching && musicCoverImage == null) {
                fetchCoverAsync(fullTitle, fullArtist);
            }
            // 仅在曲目切换时才清空旧封面（由 updateMusicInfo 切歌流程处理）
        }
        if (musicCoverLabel != null) musicCoverLabel.repaint();
    }

    private void updateProgressDisplay(MusicInfo info) {
        if (musicLyricsLabel == null || info == null || lrcLines.isEmpty()) return;
        long daemonPos = info.getPositionTicks() / 10_000;
        long pos = Math.max(daemonPos, 0) + 900;  // 提前0.9秒显示歌词
        long end = info.getEndTimeTicks() / 10_000;
        if (end <= 0 && lastDaemonEndTimeMs > 0) {
            end = lastDaemonEndTimeMs;
        }
        if (end > 0 && pos > end) pos = end;
        int idx = lyricsService.findLineIndex(lrcLines, pos);
        System.out.printf("[LyricProgress] position=%dms idx=%d/%d '%s'%n",
                pos, idx, lrcLines.size(),
                idx >= 0 && idx < lrcLines.size() ? lrcLines.get(idx).content : "N/A");
        if (idx != currentLyricIndex) {
            currentLyricIndex = idx;
            musicLyricsLabel.repaint();
        }
    }

    private void startLyricScrollTimer() {
        if (lyricScrollTimer != null && lyricScrollTimer.isRunning()) return;
        if (lyricScrollTimer != null) { lyricScrollTimer.stop(); lyricScrollTimer = null; }
        // 暂停状态不启动定时器
        if (currentMusicInfo == null || !currentMusicInfo.isStrictlyPlaying()) return;
        if (currentMusicInfo.getEndTimeTicks() > 0) {
            lastDaemonEndTimeMs = currentMusicInfo.getEndTimeTicks() / 10_000;
        }
        System.out.println("[LyricProgress] start timer interval=" + LYRIC_SCROLL_MS + "ms");
        lyricScrollTimer = new javax.swing.Timer(LYRIC_SCROLL_MS, e -> {
            if (lrcLines.isEmpty()) return;
            updateProgressDisplay(currentMusicInfo);
        });
        lyricScrollTimer.start();
    }

    private void stopLyricScrollTimer() {
        if (lyricScrollTimer != null) { lyricScrollTimer.stop(); lyricScrollTimer = null; }
    }

    // ═══════════════════════════════════════════
    //  封面渲染 & 异步获取
    // ═══════════════════════════════════════════

    private void fetchLyricsAsync(String title, String artist) {
        if (title.isEmpty() || artist.isEmpty()) return;
        if (!lrcLines.isEmpty() || fetchingLyrics) return;
        fetchingLyrics = true;
        final String srcAppId = currentMusicInfo.getSourceAppId();
        System.out.println("[IslandWindow] 开始异步获取歌词: " + title + " - " + artist + " src=" + srcAppId);
        new Thread(() -> {
            try {
                List<LyricItem> lines = lyricsService.getLyrics(title, artist, srcAppId);
                System.out.println("[IslandWindow] 歌词获取结果: " + lines.size() + " 行");
                if (!lines.isEmpty()) {
                    SwingUtilities.invokeLater(() -> {
                        lrcLines = lines; currentLyricIndex = -1;
                        System.out.println("[LyricProgress] 歌词异步加载完成: " + lines.size() + " 行");
                        if (musicLyricsLabel != null) {
                            updateProgressDisplay(currentMusicInfo);
                            Container p = musicLyricsLabel.getParent();
                            if (p != null) { p.revalidate(); p.repaint(); }
                        }
                        // 确保定时器在运行（可能在歌词加载前已启动但因 lrcLines 为空而空转）
                        startLyricScrollTimer();
                    });
                }
            } finally { fetchingLyrics = false; }
        }, "LyricsFetcher").start();
    }

    private void fetchCoverAsync(String title, String artist) {
        if (title.isEmpty() || artist.isEmpty()) return;
        if (fetchingCover) {
            System.out.println("[IslandWindow] 封面获取已在进行中，跳过重复请求");
            return;
        }
        fetchingCover = true;
        final String trackId = title + "|" + artist;
        lastFetchedCoverTrackId = trackId;
        final String srcAppId = currentMusicInfo.getSourceAppId();
        System.out.println("[IslandWindow] 开始异步获取封面: " + title + " - " + artist
                + " src=" + srcAppId);
        new Thread(() -> {
            try {
                String url = lyricsService.fetchCoverUrl(title, artist, srcAppId);
                if (!url.isEmpty()) {
                    System.out.println("[IslandWindow] 封面URL: " + url);
                    Image cover = downloadImageFromUrl(url);
                    if (cover != null) SwingUtilities.invokeLater(() -> {
                        // stale-track 校验：封面只属于发起请求时的曲目
                        String currentTrackId = currentMusicInfo.getTitle() + "|" + currentMusicInfo.getArtist();
                        if (!trackId.equals(currentTrackId)) {
                            System.out.println("[IslandWindow] 封面已过期（曲目已切换），丢弃");
                            return;
                        }
                        // SMTC 缩略图优先：当前曲目已有 SMTC 缩略图时立即尝试应用，
                        // 应用成功则跳过 URL 结果；解码失败则仍用 URL 结果补位，防止封面卡死
                        if (!currentMusicInfo.getThumbnailBase64().isEmpty()) {
                            updateMusicPanelContent();
                            if (currentTrackId.equals(smTcCoverAppliedTrackId)) {
                                System.out.println("[IslandWindow] SMTC封面已应用，跳过URL封面");
                                return;
                            }
                        }
                        musicCoverImage = createCircularCover(cover, COVER_HIRES);
                        if (musicCoverLabel != null) musicCoverLabel.repaint();
                    });
                } else {
                    System.out.println("[IslandWindow] 封面获取失败（无结果）");
                }
            } finally {
                // 仅当此请求仍为"当前活跃请求"时才释放锁，防止旧曲目线程误清标志
                if (trackId.equals(lastFetchedCoverTrackId)) {
                    fetchingCover = false;
                }
            }
        }, "CoverFetcher").start();
    }

    private Image downloadImageFromUrl(String urlStr) {
        try { return ImageIO.read(new URL(urlStr)); }
        catch (Exception e) { AppLogger.warn("IslandWindow", "封面下载失败: " + e.getMessage()); }
        return null;
    }

    private Image createCircularCover(Image source, int hiResSize) {
        // 1. 先缩放到高分辨率
        BufferedImage scaled = new BufferedImage(hiResSize, hiResSize, BufferedImage.TYPE_INT_ARGB);
        Graphics2D sg = scaled.createGraphics();
        sg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        sg.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        sg.drawImage(source, 0, 0, hiResSize, hiResSize, null);
        sg.dispose();

        // 2. 像素级精确正圆形蒙版（抗锯齿）
        BufferedImage mask = new BufferedImage(hiResSize, hiResSize, BufferedImage.TYPE_INT_ARGB);
        Graphics2D mg = mask.createGraphics();
        mg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        mg.fill(new Ellipse2D.Double(0, 0, hiResSize, hiResSize));
        mg.dispose();

        // 3. DstIn 裁剪：保留缩放图在圆形蒙版内的像素
        Graphics2D g2d = scaled.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setComposite(AlphaComposite.DstIn);
        g2d.drawImage(mask, 0, 0, null);
        g2d.dispose();

        // 4. 半透明圆形边框（1.5px，抗锯齿）
        Graphics2D fg = scaled.createGraphics();
        fg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        fg.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        fg.setColor(new Color(255, 255, 255, 35));
        fg.setStroke(new BasicStroke(1.5f));
        double inset = 1.0;
        fg.draw(new Ellipse2D.Double(inset, inset, hiResSize - inset * 2, hiResSize - inset * 2));
        fg.dispose();

        return scaled;
    }

    private void startCoverRotation() {
        if (coverRotationTimer != null && coverRotationTimer.isRunning()) return;
        coverRotationTimer = new Timer(COVER_ROTATION_FRAME_MS, e -> {
            coverRotationAngle = (coverRotationAngle + COVER_ROTATION_DEG_PER_FRAME) % 360.0;
            if (musicCoverLabel != null) musicCoverLabel.repaint();
        });
        coverRotationTimer.start();
    }

    private void stopCoverRotation() {
        if (coverRotationTimer != null) { coverRotationTimer.stop(); coverRotationTimer = null; }
    }

    // ═══════════════════════════════════════════
    //  音乐停止后自动收回（连续未恢复播放满 2 分钟）
    // ═══════════════════════════════════════════

    private void startMusicStopAutoHideTimer() {
        cancelMusicStopAutoHideTimer();
        musicStopAutoHideTimer = new Timer(MUSIC_STOP_AUTO_HIDE_MS, e -> {
            musicStopAutoHideTimer = null;
            musicAutoExpanded = false;
            // 2 分钟自动收回仅在扩展岛显示音乐面板时生效：用户已滚轮切回电池卡片
            // 则不按音乐停止规则收起，交由空闲自动收起巡检处理
            if (isExpandedIslandVisible()
                    && musicPanelShownInExpanded
                    && (currentMusicInfo == null || !currentMusicInfo.isStrictlyPlaying())) {
                AppLogger.info("IslandWindow", "音乐停止播放已满 2 分钟，自动收回扩展岛");
                hideExpandedIslandSlideUp();
            }
        });
        musicStopAutoHideTimer.setRepeats(false);
        musicStopAutoHideTimer.start();
    }

    private void cancelMusicStopAutoHideTimer() {
        if (musicStopAutoHideTimer != null) {
            musicStopAutoHideTimer.stop();
            musicStopAutoHideTimer = null;
        }
    }

    /** 音乐严格播放期间扩展岛需常驻，阻断空闲自动收起 */
    private boolean isMusicPlaybackResident() {
        return currentMusicInfo != null && currentMusicInfo.isStrictlyPlaying();
    }

    // ═══════════════════════════════════════════
    //  扩展岛空闲自动收起（仅用户主动展开时生效）
    // ═══════════════════════════════════════════

    /**
     * 启动空闲自动收起巡检。每 5 秒检查一次：
     * 设置未勾选、显示歌词、显示摄像头/麦克风监测指示任一阻断条件成立即重置空闲计时；
     * 连续空闲满 10 分钟自动收起扩展岛。
     */
    private void startIdleAutoCollapseTimer() {
        stopIdleAutoCollapseTimer();
        idleExpandSince = System.currentTimeMillis();
        idleAutoCollapseTimer = new Timer(IDLE_AUTO_COLLAPSE_CHECK_MS, e -> checkIdleAutoCollapse());
        idleAutoCollapseTimer.start();
    }

    private void checkIdleAutoCollapse() {
        if (!isExpandedIslandVisible() || isExpanding || isCollapsing) {
            stopIdleAutoCollapseTimer();
            return;
        }
        if (!AppConstants.isAutoCollapseExpandedEnabled()
                || isLyricsShowing()
                || isDeviceUsageIndicatorShowing()
                || isMusicPlaybackResident()) {
            // 任一阻断条件成立：重置空闲起点，不触发自动收起
            idleExpandSince = System.currentTimeMillis();
            return;
        }
        if (System.currentTimeMillis() - idleExpandSince >= IDLE_AUTO_COLLAPSE_MS) {
            stopIdleAutoCollapseTimer();
            AppLogger.info("IslandWindow", "扩展岛已连续空闲 10 分钟，自动收起");
            hideExpandedIslandSlideUp();
        }
    }

    private void stopIdleAutoCollapseTimer() {
        if (idleAutoCollapseTimer != null) {
            idleAutoCollapseTimer.stop();
            idleAutoCollapseTimer = null;
        }
    }

    /** 扩展岛当前是否显示歌词内容（音乐卡片可见且有活跃媒体会话，含"歌词加载中"占位） */
    private boolean isLyricsShowing() {
        return musicPanelShownInExpanded
                && currentMusicInfo != null
                && currentMusicInfo.hasSession();
    }

    /** 扩展岛当前是否显示摄像头/麦克风使用监测指示 */
    private boolean isDeviceUsageIndicatorShowing() {
        return cameraInUse || micInUse
                || (deviceUsagePanel != null && deviceUsagePanel.isVisible());
    }

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
            
            if (animTimer != null) {
                animTimer.stop();
                animTimer = null;
            }

            stopCoverRotation();
            stopLyricScrollTimer();
            stopUsageAnimTimer();
            cancelDeviceAutoHideTimer();
            cancelMusicStopAutoHideTimer();
            stopIdleAutoCollapseTimer();
            if (gestureSlideAnimTimer != null) { gestureSlideAnimTimer.stop(); gestureSlideAnimTimer = null; }

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
            
            if (weatherMonitor != null) {
                weatherMonitor.stop();
            }
            
            cleanupImageResources();
            hideExpandedIsland();
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
        cameraInUseIcon = loadImage("/icons/摄像头使用中.png");
        micInUseIcon = loadImage("/icons/麦克风使用中.png");
        cameraIndicator.icon = cameraInUseIcon;
        micIndicator.icon = micInUseIcon;
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
        flushImage(musicCoverImage);
        musicCoverImage = null;
    }
}
