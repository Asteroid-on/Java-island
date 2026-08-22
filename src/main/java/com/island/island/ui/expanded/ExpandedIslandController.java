package com.island.island.ui.expanded;

import com.island.battery.BatteryMonitor;
import com.island.config.AppConstants;
import com.island.island.model.IslandConfig;
import com.island.island.ui.ExpandedIslandHost;
import com.island.island.ui.IslandUiStyle;
import com.island.music.LyricsService;
import com.island.music.model.MusicInfo;
import com.island.util.AnimationUtil;
import com.island.util.AppLogger;
import com.island.util.ScreenUtil;
import com.island.weather.WeatherInfo;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JWindow;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Toolkit;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;

/**
 * 扩展岛控制器：管理深黑色大号药丸窗口（第二个 JWindow）的
 * 展开/收起动画、卡片切换布局（电池 → 音乐/占位）、滚轮/单击交互，
 * 以及设备占用 5 秒、音乐停止 2 分钟、空闲 10 分钟三类自动收起计时。
 * 与 BatteryRingPanel / DeviceUsagePanel / MusicPanel / MusicSessionController
 * 同包协作；对主岛仅通过 ExpandedIslandHost 接口单向依赖。
 * 所有 Swing 访问均在 EDT。
 */
public class ExpandedIslandController {

    private final ExpandedIslandHost host;
    private final BatteryRingPanel batteryPanel = new BatteryRingPanel();
    private final DeviceUsagePanel deviceUsagePanel;
    private final MusicSessionController musicSessionController;
    private final MusicPanel musicPanel;
    private final WeatherDetailPanel weatherDetailPanel;

    private JWindow expandedWindow;
    private boolean isExpanding = false;
    private boolean isCollapsing = false;
    /** dispose() 触发的折叠完成后是否销毁窗口（正常折叠仅隐藏复用） */
    private boolean disposeWindowAfterCollapse = false;
    /** 展开/折叠动画定时器（持有引用：dispose/收起时能及时停止，避免双动画并存与窗口销毁后继续驱动 EDT） */
    private Timer expandAnimTimer;
    private Timer collapseAnimTimer;

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

    // ── 卡片切换 ──
    private float gestureSlideProgress = 0f;
    private Timer gestureSlideAnimTimer;
    /** 音乐面板是否已通过滚轮切换在扩展岛中显示 */
    private boolean musicPanelShownInExpanded = false;
    private JPanel placeholderPanel;

    private Image cameraInUseIcon;
    private Image micInUseIcon;
    /** 天气详情展开时替换右上角气温的返回图标（由 IslandWindow 加载注入） */
    private Image returnIcon;
    /** 天气详情手动刷新按钮图标（由 IslandWindow 加载注入） */
    private Image refreshIcon;

    // ── 天气详情卡片（点击天气条后窗口向下延伸展开） ──
    /** 最近一次天气数据（null 表示获取失败/暂无数据），由 IslandWindow 在 EDT 推送 */
    private WeatherInfo latestWeather;
    /** 天气详情卡片当前是否处于展开态（含动画中） */
    private boolean weatherDetailOpen = false;
    private boolean weatherDetailAnimating = false;
    /** 天气详情卡片延伸/收起动画定时器（持有引用：折叠/销毁时能及时停止） */
    private Timer weatherDetailAnimTimer;

    public ExpandedIslandController(ExpandedIslandHost host) {
        this.host = host;
        this.musicSessionController = new MusicSessionController(this);
        this.musicPanel = new MusicPanel(musicSessionController);
        this.deviceUsagePanel = new DeviceUsagePanel(this);
        this.weatherDetailPanel = new WeatherDetailPanel(this);
    }

    // ═══════════════════════════════════════════
    //  对外接口（IslandWindow 调用）
    // ═══════════════════════════════════════════

    /** 主岛单击：切换扩展岛展开/折叠 */
    public void toggle() {
        if (isExpanding || isCollapsing) {
            return;
        }
        if (expandedWindow != null && expandedWindow.isVisible()) {
            hideByUser();
        } else {
            show();
        }
    }

    public boolean isVisible() {
        return expandedWindow != null && expandedWindow.isVisible();
    }

    /** 摄像头/麦克风使用状态回调（EDT），由 IslandWindow 转发 */
    public void onDeviceUsageChanged(boolean camera, boolean mic) {
        deviceUsagePanel.updateUsage(camera, mic);
    }

    /**
     * 天气数据刷新回调（EDT），由 IslandWindow 转发；
     * info 为 null 表示获取失败/暂无数据，天气条与详情卡片显示兜底状态。
     */
    public void onWeatherInfoChanged(WeatherInfo info) {
        this.latestWeather = info;
        deviceUsagePanel.updateWeather(info);
        weatherDetailPanel.updateWeather(info);
    }

    /** 电池状态回调（EDT），由 IslandWindow 转发 */
    public void onBatteryInfoChanged(BatteryMonitor.BatteryInfo info) {
        batteryPanel.updateBatteryInfo(info);
    }

    /** 音乐监控回调（EDT），由 IslandWindow 转发 */
    public void onMusicInfoChanged(MusicInfo info) {
        musicSessionController.onMusicInfoChanged(info);
    }

    /** 供 SystemTrayManager 通过 IslandWindow 获取 LyricsService 引用 */
    public LyricsService getLyricsService() {
        return musicSessionController.getLyricsService();
    }

    /** 注入设备占用图标资源（由 IslandWindow 在加载图标后调用） */
    public void setUsageIcons(Image cameraIcon, Image micIcon) {
        this.cameraInUseIcon = cameraIcon;
        this.micInUseIcon = micIcon;
    }

    /** 注入返回图标资源（由 IslandWindow 在加载图标后调用） */
    public void setReturnIcon(Image icon) {
        this.returnIcon = icon;
    }

    /** 注入刷新按钮图标资源（由 IslandWindow 在加载图标后调用） */
    public void setRefreshIcon(Image icon) {
        this.refreshIcon = icon;
    }

    /** 刷新按钮图标（供 WeatherDetailPanel 构建时使用） */
    Image getRefreshIcon() {
        return refreshIcon;
    }

    /**
     * 天气详情卡片当前是否展开（供 DeviceUsagePanel 绘制时查询，EDT）
     */
    boolean isWeatherDetailOpen() {
        return weatherDetailOpen;
    }

    /** 手动触发一次天气数据立即刷新；onDone 在刷新全部完成后于 EDT 回调（供刷新按钮恢复状态） */
    void refreshWeatherNow(Runnable onDone) {
        host.refreshWeatherNow(onDone);
    }

    /** 停止各类定时器并触发收起（与窗口销毁配套） */
    public void dispose() {
        musicPanel.stopCoverRotation();
        musicPanel.stopLyricScrollTimer();
        deviceUsagePanel.stopUsageAnimTimer();
        musicPanel.flushCoverImage();
        cancelDeviceAutoHideTimer();
        cancelMusicStopAutoHideTimer();
        stopIdleAutoCollapseTimer();
        if (gestureSlideAnimTimer != null) {
            gestureSlideAnimTimer.stop();
            gestureSlideAnimTimer = null;
        }
        // 终止进行中的展开动画（展开 Timer 与折叠/销毁互斥，避免双动画并存）
        if (expandAnimTimer != null) {
            expandAnimTimer.stop();
            expandAnimTimer = null;
        }
        isExpanding = false;
        // 终止天气详情卡片延伸动画并清理卡片面板
        if (weatherDetailAnimTimer != null) {
            weatherDetailAnimTimer.stop();
            weatherDetailAnimTimer = null;
        }
        weatherDetailOpen = false;
        weatherDetailAnimating = false;
        weatherDetailPanel.clearPanel();
        if (expandedWindow != null && expandedWindow.isVisible()) {
            // 展开态销毁：折叠动画结束后再销毁窗口
            disposeWindowAfterCollapse = true;
            hide();
        } else if (expandedWindow != null) {
            // 已隐藏：直接销毁复用窗口
            expandedWindow.dispose();
            expandedWindow = null;
        }
    }

    // ═══════════════════════════════════════════
    //  包级接口（供同包组件协作）
    // ═══════════════════════════════════════════

    boolean isExpandingOrCollapsing() {
        return isExpanding || isCollapsing;
    }

    /** 展开扩展岛（用户点击 / 设备占用 / 音乐自动弹出共用入口） */
    void show() {
        if (disposeWindowAfterCollapse) {
            return; // 销毁流程进行中，不允许再次展开
        }
        // 复用已隐藏的窗口（避免每次展开重建 JWindow 原生窗口，点击→首帧 90~115ms → 30ms 内）
        final boolean reuseWindow = expandedWindow != null;
        if (!reuseWindow) {
            expandedWindow = new JWindow();
            expandedWindow.setBackground(IslandUiStyle.TRANSPARENT_BLACK);
            expandedWindow.setAlwaysOnTop(true);
        }
        // 记录本次展开是否由用户主动点击触发（设备/音乐自动弹出不参与空闲自动收起）
        final boolean userInitiated = !deviceAutoExpanded && !musicAutoExpanded;
        stopIdleAutoCollapseTimer();
        isExpanding = true;

        // 重置卡片切换状态
        gestureSlideProgress = 0f;
        musicPanelShownInExpanded = false;

        // 重置天气详情卡片状态（窗口内容随复用前的 removeAll 一并清除）
        resetWeatherDetailState();

        // 展开动画起点采用主岛规范静态位置：宿主窗口经隐藏动画后会停在离屏球位
        // （runHideAnimation 收尾 setBounds 至 y=-ballSize 并 setVisible(false)），
        // 实时几何不可信，直接读取会导致展开动画起点漂移
        // 所在屏按主岛窗口位置反查：用户点击时主岛可见位置可靠；设备/音乐自动弹出时主岛
        // 停在离屏球位（x 仍在所属屏水平范围内），由 ScreenUtil 兜底匹配，不依赖鼠标瞬态位置
        Rectangle screenBounds = ScreenUtil.getScreenBoundsAt(host.getMainIslandWindow().getLocation());
        IslandConfig config = host.getService().getConfig();
        int startW = config.width;
        int startH = config.height;
        int startX = screenBounds.x + (screenBounds.width - startW) / 2;
        int startY = screenBounds.y + config.positionY;

        host.getService().hide();
        host.getMainIslandWindow().setVisible(false);

        int targetX = screenBounds.x + (screenBounds.width - IslandUiStyle.EXPANDED_WIDTH) / 2;
        int targetY = screenBounds.y;
        int targetH = IslandUiStyle.EXPANDED_HEIGHT;

        if (reuseWindow) {
            expandedWindow.getContentPane().removeAll();
        }
        expandedWindow.setLocation(startX, startY);
        expandedWindow.setSize(startW, startH);

        JPanel panel = new JPanel(null) {
            /** 手动双缓冲离屏画布：per-pixel 透明窗口无 Swing 默认双缓冲，
             *  直写屏幕会暴露“背景先清、卡片后画”的中间态，导致切换时背景闪烁 */
            private BufferedImage buffer;
            /** 缓冲区对应的设备缩放倍率（HiDPI）：与当前屏幕变换不一致时按新倍率重建，自适应跨屏/改缩放 */
            private double bufferScaleX = 1.0;
            private double bufferScaleY = 1.0;

            @Override
            public void paint(Graphics g) {
                int w = getWidth(), h = getHeight();
                if (w <= 0 || h <= 0 || !(g instanceof Graphics2D)) {
                    super.paint(g);
                    return;
                }
                // 自适应 HiDPI：每帧从屏幕 Graphics 读当前设备倍率（窗口跨屏/系统改缩放后自动生效），
                // 按设备像素分辨率建缓冲并应用同倍率变换，文字/圆环以设备分辨率栅格化后 1:1 blit 保持清晰；
                // 逻辑分辨率缓冲会被屏幕变换放大插值 → 文字电池整体发糊
                AffineTransform deviceTx = ((Graphics2D) g).getTransform();
                double sx = deviceTx.getScaleX();
                double sy = deviceTx.getScaleY();
                if (sx <= 0 || sy <= 0) {
                    super.paint(g);
                    return;
                }
                int bufW = (int) Math.ceil(w * sx);
                int bufH = (int) Math.ceil(h * sy);
                // 倍率变化或尺寸超出现有缓冲时重建；按展开态最大尺寸（设备像素）分配，
                // 展开/收起 resize 与卡片滑动全程复用不重新分配
                if (buffer == null
                        || bufferScaleX != sx || bufferScaleY != sy
                        || buffer.getWidth() < bufW || buffer.getHeight() < bufH) {
                    int maxBufW = Math.max(bufW, (int) Math.ceil(IslandUiStyle.EXPANDED_WIDTH * sx));
                    // 按展开态最大尺寸（含天气详情向下无缝延伸高度）分配，延伸动画中不重新分配
                    int extendedH = IslandUiStyle.EXPANDED_HEIGHT + IslandUiStyle.WEATHER_DETAIL_HEIGHT;
                    int maxBufH = Math.max(bufH, (int) Math.ceil(extendedH * sy));
                    buffer = new BufferedImage(maxBufW, maxBufH, BufferedImage.TYPE_INT_ARGB);
                    bufferScaleX = sx;
                    bufferScaleY = sy;
                }
                Graphics2D bg = buffer.createGraphics();
                try {
                    bg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    // 每帧从全透明底开始（设备像素区）：半透明背景不与上一帧残留内容叠加，帧间亮度稳定
                    bg.setComposite(AlphaComposite.Clear);
                    bg.fillRect(0, 0, bufW, bufH);
                    bg.setComposite(AlphaComposite.SrcOver);
                    // 应用设备倍率：子组件仍按逻辑坐标绘制，落库为设备分辨率像素
                    bg.setTransform(AffineTransform.getScaleInstance(sx, sy));
                    // 圆角几何统一内缩 1px：为 AA 过渡留出透明带，避免贴边绘制被窗口边界截断成台阶状硬边
                    Shape oldClip = bg.getClip();
                    bg.setClip(buildWindowShape(w, h));
                    try {
                        // 背景 + 全部子卡片一次性合成进离屏画布
                        super.paint(bg);
                    } finally {
                        // 恢复原 clip（可能为 null，setClip(null) 表示清除裁剪），防止圆角 clip 泄漏
                        bg.setClip(oldClip);
                    }
                } finally {
                    bg.dispose();
                }
                // 设备像素 1:1 映射回屏幕：目标矩形写逻辑坐标，屏幕变换把源像素按原倍率精确还原，
                // 全程无放大插值；面板小于缓冲区（展开/收起 resize 中）只取左上有效子区域
                g.drawImage(buffer, 0, 0, w, h, 0, 0, bufW, bufH, null);
            }

            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2d = (Graphics2D) g.create();
                try {
                    g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    // 清除 paint() 传入的圆角 clip：背景填充需要完整的 AA 过渡像素，
                    // 否则边缘被硬边 clip 二次裁切，圆角退化成台阶状锯齿（主岛无 clip 故光滑）
                    g2d.setClip(null);
                    // 与 paint() 的 clip 使用同一几何：常态为药丸胶囊（arc = 高 - 2 保持两端正半圆帽）；
                    // 天气详情向下延伸后为「顶部半圆帽 + 直边 + 底部统一圆角」的整体形状，
                    // 背景一次性填充，药丸与延伸区无缝连为一体，连接处无割裂圆角/缝隙
                    int w = getWidth(), h = getHeight();
                    g2d.setColor(IslandUiStyle.DEEP_BLACK);
                    g2d.fill(buildWindowShape(w, h));
                } finally {
                    g2d.dispose();
                }
            }
        };
        panel.setOpaque(false);

        JPanel battPnl = batteryPanel.build();
        panel.add(battPnl);

        // 构建前清理已结束的设备使用状态残留，避免旧绿点/图标在新展开时闪现
        deviceUsagePanel.cleanupStaleUsageState();
        deviceUsagePanel.build(cameraInUseIcon, micInUseIcon, returnIcon);
        // 应用最近一次天气数据：无数据时天气条显示"--°"兜底
        deviceUsagePanel.updateWeather(latestWeather);
        panel.add(deviceUsagePanel.getPanel());

        expandedWindow.getContentPane().add(panel);

        // ── 滚轮监听：向下滚动 = 右滑（显示右侧卡片），向上滚动 = 左滑（返回左侧卡片）──
        expandedWindow.addMouseWheelListener(e -> {
            int rotation = e.getWheelRotation();
            if (rotation == 0) return;
            // 延伸动画期间不响应；天气详情展开时滚轮改为横向滚动 24 小时逐时预报，不再切卡
            if (weatherDetailAnimating) return;
            if (weatherDetailOpen) {
                weatherDetailPanel.scrollHourly(rotation * 24);
                return;
            }
            if (rotation > 0) {
                // 向下滚动 → 右滑 → 切换至音乐/占位面板
                System.out.println("[IslandWindow] 滚轮向下 → 右滑，切换至音乐/占位面板");
                showMusicPanelInExpanded();
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
                hideByUser();
            }
        });

        expandedWindow.setVisible(true);

        if (expandAnimTimer != null) {
            expandAnimTimer.stop();
            expandAnimTimer = null;
        }
        Timer expandTimer = expandAnimTimer = new Timer(IslandUiStyle.EXPAND_ANIM_FRAME_MS, null);
        final long animStart = System.currentTimeMillis();
        expandTimer.addActionListener(e -> {
            float elapsed = System.currentTimeMillis() - animStart;
            float progress = Math.min(elapsed / IslandUiStyle.EXPAND_ANIM_DURATION_MS, 1.0f);
            double eased = 1 - (1 - progress) * (1 - progress);

            int curW = (int) (startW + (IslandUiStyle.EXPANDED_WIDTH - startW) * eased);
            int curH = (int) (startH + (targetH - startH) * eased);
            int curX = (int) (startX + (targetX - startX) * eased);
            int curY = (int) (startY + (targetY - startY) * eased);

            expandedWindow.setBounds(curX, curY, curW, curH);

            if (progress >= 1.0f) {
                ((Timer) e.getSource()).stop();
                expandAnimTimer = null;
                expandedWindow.setBounds(targetX, targetY, IslandUiStyle.EXPANDED_WIDTH, targetH);
                isExpanding = false;
                layoutExpandedPanel();
                deviceUsagePanel.startOrStopUsageAnimTimer();
                // 设备占用触发的自动弹出：展开完成后再显示图标，并启动 5 秒自动隐藏计时
                if (deviceAutoExpanded) {
                    deviceUsagePanel.applyUsageStates();
                    if (musicSessionController.isStrictlyPlaying()) {
                        // 音乐播放中：取消设备 5 秒自动隐藏，改为展示音乐面板常驻
                        cancelDeviceAutoHideTimer();
                        deviceAutoExpanded = false;
                        musicAutoExpanded = true;
                        SwingUtilities.invokeLater(() -> {
                            musicPanelAutoShownForSession = true;
                            showMusicPanelInExpanded();
                        });
                    } else {
                        startDeviceAutoHideTimer();
                    }
                } else if (musicAutoExpanded) {
                    // 音乐自动弹出：展开完成后展示音乐面板（封面/歌词/歌名）
                    SwingUtilities.invokeLater(() -> {
                        musicPanelAutoShownForSession = true;
                        showMusicPanelInExpanded();
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

    /** 用户手动折叠扩展岛：播放期间折叠后本次会话不再自动弹出音乐岛，避免打扰 */
    void hideByUser() {
        if (musicSessionController.isStrictlyPlaying()) {
            musicPopupSuppressedByUser = true;
        }
        hide();
    }

    /** 间接隐藏：扩展岛收缩回主岛（往返形态过渡，保持原有尺寸/位置插值动画） */
    void hide() {
        hideExpandedIslandInternal(false);
    }

    /** 直接隐藏：扩展岛整体向上平移滑出屏幕顶部（自动隐藏场景，不做形态收缩） */
    void hideSlideUp() {
        hideExpandedIslandInternal(true);
    }

    private void hideExpandedIslandInternal(boolean slideUp) {
        if (expandedWindow == null || isCollapsing) {
            return;
        }

        // 捕获本地引用：动画期间窗口对象不再变化，避免与 dispose 流程竞争
        final JWindow win = expandedWindow;

        // 收起前立即关闭天气详情卡片：恢复药丸规范高度，避免收起动画从扩展高度开始
        closeWeatherDetailImmediate();

        // 终止仍在进行的展开动画：避免展开/折叠双 Timer 并存（dispose 或反向触发竞态）
        if (expandAnimTimer != null) {
            expandAnimTimer.stop();
            expandAnimTimer = null;
        }
        isExpanding = false;

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
        if (gestureSlideAnimTimer != null) {
            gestureSlideAnimTimer.stop();
            gestureSlideAnimTimer = null;
        }

        isCollapsing = true;

        musicPanel.stopCoverRotation();
        musicPanel.stopLyricScrollTimer();
        deviceUsagePanel.stopUsageAnimTimer();

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

        // 收起终点采用主岛规范静态位置（与 show() 的展开起点一致），
        // 保证收起是展开的严格对称反向镜像；宿主窗口实时几何可能停在隐藏动画后的离屏球位
        // 所在屏按扩展岛窗口当前位置反查（展开后窗口即位于展开屏，与展开起点天然对称）
        Rectangle screenBounds = ScreenUtil.getScreenBoundsAt(startLoc);
        IslandConfig config = host.getService().getConfig();
        int collapseTargetX = screenBounds.x + (screenBounds.width - config.width) / 2;
        int collapseTargetY = screenBounds.y + config.positionY;
        int targetW = config.width;
        int targetH = config.height;

        if (collapseAnimTimer != null) {
            collapseAnimTimer.stop();
            collapseAnimTimer = null;
        }
        Timer collapseTimer = collapseAnimTimer = new Timer(IslandUiStyle.EXPAND_ANIM_FRAME_MS, null);
        final long animStart = System.currentTimeMillis();
        final int[] slideUpPhase = {0};
        final long[] slideUpPhaseStart = {animStart};
        final boolean[] windowHidden = {false};
        collapseTimer.addActionListener(e -> {
            float elapsed = System.currentTimeMillis() - animStart;
            float progress = Math.min(elapsed / IslandUiStyle.EXPAND_ANIM_DURATION_MS, 1.0f);

            boolean animationDone;
            if (slideUp) {
                // 直接隐藏：先收缩成小球（保持中心点），再向上滑出屏幕顶部。
                // 全程窗口可见，收缩与上滑过程清晰呈现（收尾时统一隐藏窗口）
                long phaseElapsed = System.currentTimeMillis() - slideUpPhaseStart[0];
                int phaseMs = slideUpPhase[0] == 0 ? IslandUiStyle.SLIDE_UP_SHRINK_MS : IslandUiStyle.SLIDE_UP_RISE_MS;
                float phaseProgress = Math.min(phaseElapsed / (float) phaseMs, 1.0f);
                double pe = AnimationUtil.easeInOutQuad(phaseProgress);
                int ball = AppConstants.BALL_SIZE;
                int centerX = startLoc.x + startW / 2;
                int centerY = startLoc.y + startH / 2;
                if (slideUpPhase[0] == 0) {
                    // 阶段1：从两边向中间收缩成小球（保持中心点不变，全程可见）
                    int newW = (int) (startW - (startW - ball) * pe);
                    int newH = (int) (startH - (startH - ball) * pe);
                    win.setBounds(centerX - newW / 2, centerY - newH / 2, newW, newH);
                    if (phaseProgress >= 1.0f) {
                        slideUpPhase[0] = 1;
                        slideUpPhaseStart[0] = System.currentTimeMillis();
                    }
                    animationDone = false;
                } else {
                    // 阶段2：小球向上滑出所在屏幕顶部（可见滑出，自然消失）
                    int startY = centerY - ball / 2;
                    int targetY = screenBounds.y - ball;
                    int curY = (int) (startY + (targetY - startY) * pe);
                    win.setLocation(centerX - ball / 2, curY);
                    animationDone = phaseProgress >= 1.0f;
                }
            } else {
                // 间接隐藏：收缩回主岛位置，展开动画的严格对称反向镜像
                // 二次加速缓动 progress²，与展开动画的二次减速缓动 1-(1-p)² 时间上完全对称；
                // 窗口全程可见（与展开动画一致），仅动画完成收尾时统一隐藏
                double eased = progress * progress;
                int curW = (int) (startW + (targetW - startW) * eased);
                int curH = (int) (startH + (targetH - startH) * eased);
                int curX = (int) (startLoc.x + (collapseTargetX - startLoc.x) * eased);
                int curY = (int) (startLoc.y + (collapseTargetY - startLoc.y) * eased);
                win.setBounds(curX, curY, curW, curH);
                animationDone = progress >= 1.0f;
            }

            if (animationDone) {
                ((Timer) e.getSource()).stop();
                collapseAnimTimer = null;
                if (disposeWindowAfterCollapse) {
                    // dispose 流程：动画完成后真正销毁窗口
                    win.dispose();
                    if (expandedWindow == win) expandedWindow = null;
                    disposeWindowAfterCollapse = false;
                } else {
                    // 正常折叠：隐藏并复用窗口（不再 dispose，避免每次展开重建原生窗口）
                    if (!windowHidden[0] && win.isDisplayable()) {
                        win.setVisible(false);
                    }
                }
                isCollapsing = false;
                // 收尾统一清理已结束的设备使用状态残留（设备仍在占用时不受影响）
                deviceUsagePanel.cleanupStaleUsageState();

                // 重置音乐面板状态
                musicPanel.reset();
                placeholderPanel = null;
                deviceUsagePanel.clearPanel();

                SwingUtilities.invokeLater(() -> host.onCollapseFinished(slideUp));
            }
        });
        collapseTimer.setInitialDelay(0);
        collapseTimer.start();
    }

    // ═══════════════════════════════════════════
    //  卡片切换与布局
    // ═══════════════════════════════════════════

    /** 滚轮向下触发：在扩展岛中显示占位面板或音乐面板 */
    void showMusicPanelInExpanded() {
        if (expandedWindow == null || !expandedWindow.isVisible()) return;
        if (musicPanelShownInExpanded) return;
        musicPanelShownInExpanded = true;

        boolean hasActiveSession = musicSessionController.hasActiveSession();

        if (hasActiveSession) {
            // 已有媒体会话 → 直接显示音乐面板
            System.out.println("[IslandWindow] 滚轮向下：已有会话，直接显示音乐面板");
            musicPanel.build();
            ensureMusicPanelInExpandedWindow();
            if (musicSessionController.isStrictlyPlaying()) {
                musicPanel.startCoverRotation();
                musicPanel.startLyricScrollTimer();
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
                    if (musicPanel.getPanel() != null && musicPanel.getPanel().getParent() == rp) {
                        rp.remove(musicPanel.getPanel());
                    }
                    boolean found = false;
                    for (Component c : rp.getComponents()) {
                        if (c == placeholderPanel) {
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        rp.add(placeholderPanel);
                        rp.revalidate();
                        ensureUsagePanelOnTop();
                        layoutExpandedPanel();
                    }
                }
            }
            startSlideAnimation(1.0f);
        }
    }

    /** 将音乐面板挂载到扩展岛（移除占位面板），并应用当前曲目内容 */
    void ensureMusicPanelInExpandedWindow() {
        if (expandedWindow == null || !expandedWindow.isVisible()) return;
        musicPanel.build();
        if (musicPanel.getPanel() == null) return;
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
            if (c == musicPanel.getPanel()) {
                found = true;
                break;
            }
        }
        if (!found) {
            musicSessionController.updateMusicPanelContent();
            rp.add(musicPanel.getPanel());
            System.out.println("[IslandWindow] 音乐面板已添加到扩展岛");
        } else {
            musicSessionController.updateMusicPanelContent();
        }
        // 仅布局变更时才触发全量重排，避免歌词闪烁
        if (replacedPlaceholder || !found) {
            ensureUsagePanelOnTop();
            layoutExpandedPanel();
        }
    }

    private void startSlideAnimation(float target) {
        if (gestureSlideAnimTimer != null) gestureSlideAnimTimer.stop();
        final float from = gestureSlideProgress;
        final float to = target;
        final long animStart = System.currentTimeMillis();
        gestureSlideAnimTimer = new Timer(IslandUiStyle.SLIDE_ANIM_FRAME_MS, e -> {
            float elapsed = (System.currentTimeMillis() - animStart) / (float) IslandUiStyle.SLIDE_ANIM_DURATION_MS;
            float t = Math.min(elapsed, 1.0f);
            float eased = 1 - (1 - t) * (1 - t) * (1 - t) * (1 - t); // quartic ease-out
            gestureSlideProgress = from + (to - from) * eased;
            layoutExpandedPanel();
            if (t >= 1.0f) {
                gestureSlideAnimTimer.stop();
                gestureSlideAnimTimer = null;
            }
        });
        gestureSlideAnimTimer.start();
    }

    /**
     * 使用状态面板保持最顶层，避免被滑入的音乐/占位面板遮挡。
     * 组件增删会重排 z-order，仅在增删后调用一次，动画每帧不再操作。
     */
    private void ensureUsagePanelOnTop() {
        if (expandedWindow == null) return;
        Container cp = expandedWindow.getContentPane();
        if (cp.getComponentCount() == 0) return;
        Component root = cp.getComponent(0);
        if (!(root instanceof JPanel)) return;
        JPanel rp = (JPanel) root;
        JPanel usagePanelCmp = deviceUsagePanel.getPanel();
        if (usagePanelCmp != null && usagePanelCmp.getParent() == rp) {
            rp.setComponentZOrder(usagePanelCmp, 0);
        }
    }

    /** 按滑动进度同步定位各卡片（电池 / 占位 / 设备状态 / 音乐面板 / 天气详情卡片） */
    void layoutExpandedPanel() {
        if (expandedWindow == null) return;
        Container cp = expandedWindow.getContentPane();
        if (cp.getComponentCount() == 0) return;
        Component root = cp.getComponent(0);
        if (!(root instanceof JPanel)) return;
        JPanel rp = (JPanel) root;
        int pw = rp.getWidth();
        // 天气详情卡片展开/收起时窗口向下延伸：各内容卡片仍锚定在上方药丸区域，不随高度漂移
        int ph = (weatherDetailOpen || weatherDetailAnimating)
                ? IslandUiStyle.EXPANDED_HEIGHT : rp.getHeight();
        Insets ins = rp.getInsets();
        if (ins == null) ins = new Insets(4, 8, 4, 12);
        int iw = pw - ins.left - ins.right, ih = ph - ins.top - ins.bottom;
        float progress = Math.max(0f, Math.min(1f, gestureSlideProgress));
        int offset = (int) (iw * progress);
        // 逐帧同步切卡进度：天气条据此淡出/淡入（仅绘制层，不影响绿点/图标与布局预留）
        deviceUsagePanel.setSlideProgress(progress);

        JPanel batteryPanelCmp = batteryPanel.getPanel();
        JPanel usagePanelCmp = deviceUsagePanel.getPanel();
        JPanel musicPanelCmp = musicPanel.getPanel();
        JPanel weatherCardCmp = weatherDetailPanel.getPanel();

        for (Component c : rp.getComponents()) {
            if (c == batteryPanelCmp && batteryPanelCmp != null) {
                // 电池圆环与左侧半圆圆心精确对齐
                int leftCenterX = IslandUiStyle.EXPANDED_HEIGHT / 2;
                int leftCenterY = IslandUiStyle.EXPANDED_HEIGHT / 2;
                int battSize = batteryPanelCmp.getPreferredSize().width;
                int battX = leftCenterX - battSize / 2;
                int battY = leftCenterY - battSize / 2;
                batteryPanelCmp.setBounds(battX - offset, battY, battSize, battSize);
            } else if (c == placeholderPanel && placeholderPanel != null) {
                placeholderPanel.setBounds(ins.left + iw - offset, ins.top, iw, ih);
            } else if (c == usagePanelCmp && usagePanelCmp != null) {
                Dimension pref = usagePanelCmp.getPreferredSize();
                // 绿点锚定右半圆圆心：面板左缘按天气条/图标预留宽度（getDotOffsetX）偏移；
                // 若超窗则整体左移保证完整可见，绿点仍保持在圆心
                int centerX = pw - IslandUiStyle.EXPANDED_HEIGHT / 2;
                int x = centerX - deviceUsagePanel.getDotOffsetX();
                if (x + pref.width > pw - ins.right) {
                    x = pw - ins.right - pref.width;
                }
                deviceUsagePanel.setDotCenterX(centerX - x);
                usagePanelCmp.setBounds(x, (ph - pref.height) / 2, pref.width, pref.height);
            } else if (c == musicPanelCmp && musicPanelCmp != null) {
                int coverCenterX = IslandUiStyle.EXPANDED_HEIGHT / 2 + 4;
                int musicX = coverCenterX - IslandUiStyle.COVER_SIZE / 2;
                // 为右侧使用状态面板预留空间，避免内容重叠
                int reserved = (usagePanelCmp != null && usagePanelCmp.isVisible())
                        ? usagePanelCmp.getPreferredSize().width + IslandUiStyle.USAGE_SLOT_GAP : 0;
                int musicW = pw - ins.right - musicX - reserved;
                musicPanelCmp.setBounds(musicX + iw - offset, ins.top, musicW, ih);
            } else if (c == weatherCardCmp && weatherCardCmp != null) {
                // 天气详情延伸区：全宽紧贴药丸底部无缝衔接（背景由窗口统一形状填充）
                weatherCardCmp.setBounds(0, IslandUiStyle.EXPANDED_HEIGHT,
                        pw, IslandUiStyle.WEATHER_DETAIL_HEIGHT);
            }
        }
        rp.revalidate();
        rp.repaint();
    }

    // ═══════════════════════════════════════
    //  天气详情：点击天气条 → 扩展岛本体原位向下平滑延伸
    // ═══════════════════════════════════════

    /** 天气条点击：切换扩展岛向下延伸展开/收起天气详情（EDT） */
    void toggleWeatherDetail() {
        if (expandedWindow == null || !expandedWindow.isVisible()) return;
        // 展开/收起主动画进行中不响应，避免双动画竞争窗口几何
        if (isExpandingOrCollapsing() || weatherDetailAnimating) return;
        animateWeatherDetail(!weatherDetailOpen);
    }

    /**
     * 天气详情延伸动画：窗口位置与宽度不变，仅高度在药丸基础上向下平滑变化，
     * 展开/收起过程即扩展岛本体在平滑变高（内容随窗口生长逐帧显现，无独立淡入淡出）。
     */
    private void animateWeatherDetail(boolean open) {
        if (expandedWindow == null) return;
        final JWindow win = expandedWindow;
        weatherDetailAnimating = true;

        final int baseH = IslandUiStyle.EXPANDED_HEIGHT;
        final int fullH = baseH + IslandUiStyle.WEATHER_DETAIL_HEIGHT;
        final int winX = win.getX();
        final int winY = win.getY();
        final int winW = win.getWidth();

        if (open) {
            // 提前置位：动画期间 layoutExpandedPanel 即按药丸高度锚定各内容卡片
            weatherDetailOpen = true;
            Container cp = win.getContentPane();
            if (cp.getComponentCount() > 0 && cp.getComponent(0) instanceof JPanel) {
                JPanel rp = (JPanel) cp.getComponent(0);
                JPanel detail = weatherDetailPanel.build();
                weatherDetailPanel.updateWeather(latestWeather);
                rp.add(detail);
                rp.revalidate();
                layoutExpandedPanel();
            }
        }

        final int fromH = open ? baseH : fullH;
        final int toH = open ? fullH : baseH;
        if (weatherDetailAnimTimer != null) {
            weatherDetailAnimTimer.stop();
        }
        final long animStart = System.currentTimeMillis();
        Timer timer = weatherDetailAnimTimer = new Timer(IslandUiStyle.EXPAND_ANIM_FRAME_MS, null);
        timer.addActionListener(e -> {
            float progress = Math.min((System.currentTimeMillis() - animStart)
                    / (float) IslandUiStyle.WEATHER_DETAIL_ANIM_MS, 1.0f);
            double eased = 1 - (1 - progress) * (1 - progress);
            int curH = (int) (fromH + (toH - fromH) * eased);
            win.setBounds(winX, winY, winW, curH);
            if (progress >= 1.0f) {
                ((Timer) e.getSource()).stop();
                weatherDetailAnimTimer = null;
                weatherDetailAnimating = false;
                win.setBounds(winX, winY, winW, toH);
                if (!open) {
                    weatherDetailOpen = false;
                    JPanel detail = weatherDetailPanel.getPanel();
                    if (detail != null && detail.getParent() != null) {
                        Container parent = detail.getParent();
                        parent.remove(detail);
                        parent.revalidate();
                    }
                    weatherDetailPanel.clearPanel();
                }
                layoutExpandedPanel();
            }
        });
        timer.setInitialDelay(0);
        timer.start();
    }

    /** 立即收起天气详情延伸（无动画）：收起/销毁扩展岛前调用，恢复药丸规范高度 */
    private void closeWeatherDetailImmediate() {
        if (weatherDetailAnimTimer != null) {
            weatherDetailAnimTimer.stop();
            weatherDetailAnimTimer = null;
        }
        weatherDetailAnimating = false;
        boolean wasOpen = weatherDetailOpen;
        weatherDetailOpen = false;
        JPanel detail = weatherDetailPanel.getPanel();
        if (detail != null && detail.getParent() != null) {
            detail.getParent().remove(detail);
        }
        weatherDetailPanel.clearPanel();
        if (wasOpen && expandedWindow != null && expandedWindow.isVisible()) {
            expandedWindow.setSize(expandedWindow.getWidth(), IslandUiStyle.EXPANDED_HEIGHT);
        }
    }

    /** 重置天气详情卡片状态（重新展开扩展岛时调用，窗口内容已随 removeAll 清除） */
    private void resetWeatherDetailState() {
        if (weatherDetailAnimTimer != null) {
            weatherDetailAnimTimer.stop();
            weatherDetailAnimTimer = null;
        }
        weatherDetailOpen = false;
        weatherDetailAnimating = false;
        weatherDetailPanel.clearPanel();
    }

    /**
     * 构造窗口形状（背景填充与裁剪共用同一几何，均内缩 1px 保留 AA 透明带）：
     * 常态为药丸胶囊；向下延伸后为「顶部药丸半圆帽 + 直边 + 底部统一圆角」，
     * 三段并集形成连续轮廓，药丸与延伸区无缝连为一体。
     */
    private Shape buildWindowShape(int w, int h) {
        if (h <= IslandUiStyle.EXPANDED_HEIGHT + 1) {
            int arc = Math.max(0, h - 2);
            return new RoundRectangle2D.Float(1, 1, Math.max(0, w - 2), Math.max(0, h - 2), arc, arc);
        }
        // 顶部半圆帽半径与药丸绘制高度一致，保证延伸前后顶部轮廓不变
        float topR = (IslandUiStyle.EXPANDED_HEIGHT - 2) / 2f;
        float botR = IslandUiStyle.WEATHER_BOTTOM_ARC;
        Area area = new Area(new RoundRectangle2D.Float(
                1, 1, Math.max(0, w - 2), topR * 2, topR * 2, topR * 2));
        // 中部直边段覆盖顶部胶囊的下方圆角，使轮廓在连接处保持连续
        area.add(new Area(new Rectangle2D.Float(
                1, 1 + topR, Math.max(0, w - 2), Math.max(0, h - 2 - topR - botR))));
        // 底部统一圆角收尾
        area.add(new Area(new RoundRectangle2D.Float(
                1, h - 1 - botR * 2, Math.max(0, w - 2), botR * 2, botR * 2, botR * 2)));
        return area;
    }

    // ═══════════════════════════════════════════
    //  自动弹出/收起计时与状态标志
    // ═══════════════════════════════════════════

    /** 设备占用自动弹出后，5 秒自动隐藏扩展岛 */
    void startDeviceAutoHideTimer() {
        if (deviceAutoHideTimer != null) deviceAutoHideTimer.stop();
        deviceAutoHideTimer = new Timer(IslandUiStyle.DEVICE_AUTO_HIDE_MS, e -> {
            deviceAutoHideTimer = null;
            deviceAutoExpanded = false;
            if (!isVisible()) return;
            if (weatherDetailOpen) {
                // 用户正在查看天气详情：跳过超时隐藏，转入空闲自动收起巡检兼容后续回收
                AppLogger.info("IslandWindow", "天气详情卡片展开中，跳过设备占用超时自动隐藏");
                startIdleAutoCollapseTimer();
                return;
            }
            if (musicSessionController.isStrictlyPlaying()) {
                // 音乐播放期间扩展岛保持常驻，跳过设备占用超时隐藏
                AppLogger.info("IslandWindow", "音乐播放中，跳过设备占用超时自动隐藏");
                return;
            }
            AppLogger.info("IslandWindow", "设备占用自动弹出超时，自动隐藏扩展岛");
            hideSlideUp();
        });
        deviceAutoHideTimer.setRepeats(false);
        deviceAutoHideTimer.start();
    }

    void cancelDeviceAutoHideTimer() {
        if (deviceAutoHideTimer != null) {
            deviceAutoHideTimer.stop();
            deviceAutoHideTimer = null;
        }
    }

    void setDeviceAutoExpanded(boolean value) {
        deviceAutoExpanded = value;
    }

    void clearDeviceAutoExpanded() {
        deviceAutoExpanded = false;
    }

    void setMusicAutoExpanded(boolean value) {
        musicAutoExpanded = value;
    }

    /** 音乐停止播放后自动收回扩展岛（连续未恢复播放满 2 分钟） */
    void startMusicStopAutoHideTimer() {
        cancelMusicStopAutoHideTimer();
        musicStopAutoHideTimer = new Timer(IslandUiStyle.MUSIC_STOP_AUTO_HIDE_MS, e -> {
            musicStopAutoHideTimer = null;
            musicAutoExpanded = false;
            // 2 分钟自动收回仅在扩展岛显示音乐面板时生效：用户已滚轮切回电池卡片
            // 则不按音乐停止规则收起，交由空闲自动收起巡检处理
            if (isVisible()
                    && musicPanelShownInExpanded
                    && !musicSessionController.isStrictlyPlaying()) {
                AppLogger.info("IslandWindow", "音乐停止播放已满 2 分钟，自动收回扩展岛");
                hideSlideUp();
            }
        });
        musicStopAutoHideTimer.setRepeats(false);
        musicStopAutoHideTimer.start();
    }

    void cancelMusicStopAutoHideTimer() {
        if (musicStopAutoHideTimer != null) {
            musicStopAutoHideTimer.stop();
            musicStopAutoHideTimer = null;
        }
    }

    /** 音乐严格播放期间扩展岛需常驻，阻断空闲自动收起 */
    private boolean isMusicPlaybackResident() {
        return musicSessionController.isStrictlyPlaying();
    }

    /**
     * 启动空闲自动收起巡检。每 5 秒检查一次：
     * 设置未勾选、显示歌词、显示摄像头/麦克风监测指示任一阻断条件成立即重置空闲计时；
     * 连续空闲满 10 分钟自动收起扩展岛。
     */
    private void startIdleAutoCollapseTimer() {
        stopIdleAutoCollapseTimer();
        idleExpandSince = System.currentTimeMillis();
        idleAutoCollapseTimer = new Timer(IslandUiStyle.IDLE_AUTO_COLLAPSE_CHECK_MS,
                e -> checkIdleAutoCollapse());
        idleAutoCollapseTimer.start();
    }

    private void checkIdleAutoCollapse() {
        if (!isVisible() || isExpanding || isCollapsing) {
            stopIdleAutoCollapseTimer();
            return;
        }
        if (!AppConstants.isAutoCollapseExpandedEnabled()
                || isLyricsShowing()
                || isDeviceUsageIndicatorShowing()
                || weatherDetailOpen
                || isMusicPlaybackResident()) {
            // 任一阻断条件成立：重置空闲起点，不触发自动收起
            idleExpandSince = System.currentTimeMillis();
            return;
        }
        if (System.currentTimeMillis() - idleExpandSince >= IslandUiStyle.IDLE_AUTO_COLLAPSE_MS) {
            stopIdleAutoCollapseTimer();
            AppLogger.info("IslandWindow", "扩展岛已连续空闲 10 分钟，自动收起");
            hideSlideUp();
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
        return musicPanelShownInExpanded && musicSessionController.hasSession();
    }

    /** 扩展岛当前是否显示摄像头/麦克风使用监测指示（面板因天气条常驻可见，须以实际指示内容为准） */
    private boolean isDeviceUsageIndicatorShowing() {
        return deviceUsagePanel.isAnyUsage() || deviceUsagePanel.hasUsageIndicatorContent();
    }

    // ── 包级状态访问器（供 MusicSessionController 读取） ──

    boolean isMusicPanelShown() {
        return musicPanelShownInExpanded;
    }

    boolean isMusicPanelAutoShownForSession() {
        return musicPanelAutoShownForSession;
    }

    void setMusicPanelAutoShownForSession(boolean value) {
        musicPanelAutoShownForSession = value;
    }

    boolean isMusicPopupSuppressedByUser() {
        return musicPopupSuppressedByUser;
    }

    void setMusicPopupSuppressedByUser(boolean value) {
        musicPopupSuppressedByUser = value;
    }

    boolean isPlaceholderShown() {
        return placeholderPanel != null && placeholderPanel.getParent() != null;
    }

    MusicPanel getMusicPanel() {
        return musicPanel;
    }
}
