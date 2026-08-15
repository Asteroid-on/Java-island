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
import com.island.tray.SystemTrayManager;
import com.island.util.AppLogger;
import com.island.weather.HybridWeatherService;
import com.island.weather.WeatherIconMapper;
import com.island.weather.WeatherInfo;
import com.island.wifi.WifiMonitor;

import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
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
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Arc2D;
import java.awt.geom.Path2D;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;

/**
 * 动态岛主窗口（类似iOS动态岛的通知与时间显示）：
 * 负责时间/日期/天气显示、蓝牙/WiFi 通知动画，以及对外注入接口。
 * 扩展岛（展开大窗口、音乐面板、电池、设备占用指示）委托给
 * {@link ExpandedIslandController} 管理。
 */
@SuppressWarnings({"this-escape"})
public class IslandWindow extends JWindow implements Serializable, ExpandedIslandHost {

    private static final long serialVersionUID = 1L;

    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault());
    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault());

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
    private transient SystemTrayManager trayManager;
    private transient WifiMonitor wifiMonitor;
    private transient HybridWeatherService weatherMonitor;
    private transient MusicMonitor musicMonitor;
    private transient BatteryMonitor batteryMonitor;
    private transient PrivacyMonitor privacyMonitor;

    private transient Image bluetoothIcon;
    private transient Image wifiIcon;
    private transient Image cameraInUseIcon;
    private transient Image micInUseIcon;

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
        monitor.setListener((camera, mic) ->
                SwingUtilities.invokeLater(() -> expandedController.onDeviceUsageChanged(camera, mic)));
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

    public void restoreTimeDisplay() {
        isHiding = false;
        isFinishingNotification = false;

        timeLabel.setText(getCurrentTime());
        dateLabel.setText(getCurrentDate());
        timeLabel.setFont(IslandUiStyle.TIME_FONT);

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
                    int r = Math.min(w, h) / 2 - 3;
                    int cx = w / 2, cy = h / 2;

                    g2d.setColor(IslandUiStyle.SEMI_TRANSPARENT_BLACK);
                    g2d.fillOval(cx - r, cy - r, r * 2, r * 2);

                    g2d.setStroke(new BasicStroke(2.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g2d.setColor(IslandUiStyle.GREEN);
                    int angle = (int) (360 * animProgress);
                    g2d.draw(new Arc2D.Double(cx - r, cy - r, r * 2, r * 2, 90, -angle, Arc2D.OPEN));

                    Image currentIcon = showingWifiNotification ? wifiIcon : bluetoothIcon;
                    if (animProgress >= 1.0f) {
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
        // 首次拉取已在服务内部调度线程异步执行，不阻塞 EDT，启用后不影响通知实时性
        weatherMonitor.start();

        addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e) {
                expandedController.toggle();
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
                timeLabel.setFont(IslandUiStyle.NOTIFY_TITLE_FONT);
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
            notificationTimer = new Timer(IslandUiStyle.NOTIFICATION_DISPLAY_TIME, e -> {
                stopAnim();

                synchronized (notificationLock) {
                    showingWifiNotification = false;
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
        cameraInUseIcon = loadImage("/icons/摄像头使用中.png");
        micInUseIcon = loadImage("/icons/麦克风使用中.png");
        expandedController.setUsageIcons(cameraInUseIcon, micInUseIcon);
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
    }
}
