package com.island.island.ui.expanded;

import com.island.island.ui.IslandUiStyle;
import com.island.util.AppLogger;
import com.island.weather.WeatherIconMapper;
import com.island.weather.WeatherInfo;

import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * 扩展岛摄像头/麦克风使用状态面板：
 * 图标弹入 → 变形为面板级唯一绿点 → 释放时淡出 的阶段状态机。
 * 面板内还固定显示天气条（天气图标 + 当前温度），始终位于绿点左侧相邻，
 * 不随设备占用状态变化；点击天气条可展开/收起天气详情卡片。
 * 所有状态访问与更新均在 EDT；设备首次占用时通过控制器自动弹出扩展岛。
 */
class DeviceUsagePanel {

    // ── 动画透明度色板：256 级预计算（类加载时构建一次，动画帧间零分配） ──
    private static final AlphaComposite[] ALPHA_COMPOSITES = new AlphaComposite[256];
    private static final java.awt.Color[] GREEN_ALPHAS = new java.awt.Color[256];

    static {
        for (int i = 0; i < 256; i++) {
            ALPHA_COMPOSITES[i] = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, i / 255f);
            GREEN_ALPHAS[i] = new java.awt.Color(IslandUiStyle.GREEN.getRed(),
                    IslandUiStyle.GREEN.getGreen(), IslandUiStyle.GREEN.getBlue(), i);
        }
    }

    /** 透明度 → 预计算 AlphaComposite（浮点 alpha 离散化到 256 级，视觉无差） */
    private static AlphaComposite alphaComposite(float alpha) {
        int a = (int) Math.round(alpha * 255f);
        if (a <= 0) return ALPHA_COMPOSITES[0];
        if (a >= 255) return ALPHA_COMPOSITES[255];
        return ALPHA_COMPOSITES[a];
    }

    /** 透明度 → 预计算绿色（绿点逐帧 alpha 变化，零每帧 Color 分配） */
    private static java.awt.Color greenColor(float alpha) {
        int a = (int) Math.round(alpha * 255f);
        if (a <= 0) return GREEN_ALPHAS[0];
        if (a >= 255) return GREEN_ALPHAS[255];
        return GREEN_ALPHAS[a];
    }

    private final ExpandedIslandController controller;

    private JPanel panel;
    private Timer animTimer;
    private final DeviceIndicator cameraIndicator = new DeviceIndicator();
    private final DeviceIndicator micIndicator = new DeviceIndicator();
    private volatile boolean cameraInUse = false;
    private volatile boolean micInUse = false;
    /** 面板级唯一绿点：进度 0~1（淡入/淡出插值），固定显示在右半圆圆心 */
    private float dotProgress = 0f;
    private boolean dotTargetVisible = false;
    /** 绿点在状态面板内的 x 坐标（面板超窗左移后与圆心仍保持一致） */
    private int dotCenterX = 20;

    /** 最近一次天气数据（null 表示获取失败/暂无数据，显示"--°"兜底） */
    private WeatherInfo weatherInfo;
    /** 天气条图标字体（构建时缓存，避免逐帧 deriveFont） */
    private Font weatherIconFont;
    /** 天气详情展开时替换上行温度的返回图标（由控制器注入） */
    private Image returnIcon;
    /** 滚轮切卡滑动进度（控制器逐帧同步）：天气条据此淡出/淡入，绿点与图标不受影响 */
    private float slideProgress = 0f;

    // ── 天气条渲染缓存（updateWeather 时重算，paint/布局帧间复用避免逐帧 getFontMetrics 与字符串拼接） ──
    private String tempText = "--°";
    private String condText = "天气不可用";
    private String weatherIconText = String.valueOf(WeatherIconMapper.getIconChar("未知"));
    private int tempTextWidth = 26;
    private int condTextWidth = 40;
    /** 初始值与默认计算一致：max(tempW, 图标12 + 间隙2 + condW) */
    private int weatherAreaWidth = 54;

    /** 单个设备状态指示器的动画阶段（仅负责图标展示，绿点由面板统一绘制） */
    private enum UsagePhase { HIDDEN, ICON, MORPHING, FADING_OUT }

    /** 单个设备状态指示器（摄像头或麦克风） */
    private static final class DeviceIndicator {
        UsagePhase phase = UsagePhase.HIDDEN;
        long phaseStartMs;
        Image icon;
    }

    DeviceUsagePanel(ExpandedIslandController controller) {
        this.controller = controller;
    }

    JPanel getPanel() {
        return panel;
    }

    /** 收起扩展岛后清空面板引用（下次展开时重建） */
    void clearPanel() {
        panel = null;
    }

    /** 当前是否有任一设备占用（用于空闲自动收起巡检） */
    boolean isAnyUsage() {
        return cameraInUse || micInUse;
    }

    /**
     * 当前是否正在显示设备占用指示内容（图标或绿点）。
     * 面板因天气条常驻可见，空闲收起巡检须以实际占用指示为准。
     */
    boolean hasUsageIndicatorContent() {
        return cameraIndicator.phase != UsagePhase.HIDDEN
                || micIndicator.phase != UsagePhase.HIDDEN
                || dotProgress > 0f;
    }

    /** 天气数据刷新回调（EDT）：同步天气条显示，宽度变化时触发扩展岛重布局 */
    void updateWeather(WeatherInfo info) {
        this.weatherInfo = info;
        refreshWeatherRenderCache();
        if (panel != null) {
            panel.repaint();
            if (controller.isVisible()) {
                controller.layoutExpandedPanel();
            }
        }
    }

    /** 重算天气条文本与宽度缓存（面板已构建时用组件 FontMetrics 度量，与绘制坐标一致） */
    private void refreshWeatherRenderCache() {
        if (weatherInfo == null) {
            tempText = "--°";
            condText = "天气不可用";
            weatherIconText = String.valueOf(WeatherIconMapper.getIconChar("未知"));
        } else {
            tempText = weatherInfo.getFormattedTemperature();
            String cond = weatherInfo.getCondition();
            condText = cond != null && !cond.isEmpty() ? cond : "天气不可用";
            weatherIconText = String.valueOf(weatherInfo.getWeatherCode() >= 0
                    ? WeatherIconMapper.getIconChar(weatherInfo.getWeatherCode())
                    : WeatherIconMapper.getIconChar(weatherInfo.getCondition()));
        }
        if (panel != null) {
            tempTextWidth = panel.getFontMetrics(IslandUiStyle.WEATHER_TEMP_FONT)
                    .stringWidth(tempText);
            condTextWidth = panel.getFontMetrics(IslandUiStyle.WEATHER_COND_FONT)
                    .stringWidth(condText);
            weatherAreaWidth = Math.max(tempTextWidth,
                    IslandUiStyle.WEATHER_ICON_SIZE + 2 + condTextWidth);
        }
    }

    /** 构建摄像头/麦克风使用状态面板（扩展岛最右侧） */
    JPanel build(Image cameraIcon, Image micIcon, Image returnIcon) {
        cameraIndicator.icon = cameraIcon;
        micIndicator.icon = micIcon;
        this.returnIcon = returnIcon;
        // 重置切卡进度：避免上次停留在音乐页的残留进度在新展开动画期间隐藏天气条
        slideProgress = 0f;

        JPanel pnl = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                // 直接复用传入 Graphics：设置提示后绘制，结束恢复（省一次 create/dispose）
                Graphics2D g2d = (Graphics2D) g;
                Object oldAA = g2d.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
                Object oldInterp = g2d.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                try {
                    int cy = getHeight() / 2;

                    // 天气条：固定在绿点左侧相邻，始终显示（设备图标弹入时自动左移避让）
                    paintWeatherStrip(g2d, cy);

                    // 唯一绿色圆点：固定在右半圆圆心（面板内坐标 dotCenterX）
                    if (dotProgress > 0f) {
                        float e = easeInOutCubic(Math.min(dotProgress, 1f));
                        int dot = Math.max(1, Math.round(IslandUiStyle.USAGE_DOT_DIAMETER * (0.5f + 0.5f * e)));
                        g2d.setColor(greenColor(e));
                        g2d.fillOval(dotCenterX - dot / 2, cy - dot / 2, dot, dot);
                    }

                    // 图标并排显示（高度对齐），整体对称于绿点圆心（非面板中心，避免天气条参与居中）
                    boolean camIcon = cameraIndicator.phase != UsagePhase.HIDDEN;
                    boolean micIcon = micIndicator.phase != UsagePhase.HIDDEN;
                    int count = (camIcon ? 1 : 0) + (micIcon ? 1 : 0);
                    if (count > 0) {
                        int totalW = count * IslandUiStyle.USAGE_ICON_SIZE
                                + (count - 1) * IslandUiStyle.USAGE_SLOT_GAP;
                        int x = dotCenterX - totalW / 2;
                        if (camIcon) {
                            paintIndicator(g2d, cameraIndicator, x, cy);
                            x += IslandUiStyle.USAGE_ICON_SIZE + IslandUiStyle.USAGE_SLOT_GAP;
                        }
                        if (micIcon) {
                            paintIndicator(g2d, micIndicator, x, cy);
                        }
                    }
                } finally {
                    // 旧值可能为 null（提示从未设置过，setRenderingHint 不接受 null），仅在非 null 时恢复
                    if (oldAA != null) {
                        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAA);
                    }
                    if (oldInterp != null) {
                        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, oldInterp);
                    }
                }
            }

            @Override
            public Dimension getPreferredSize() {
                // 面板以绿点为锚点向左预留天气条/图标空间（getDotOffsetX），
                // 右侧仅需容纳图标右半宽，宽度随图标数量动态变化
                int rightExtent = Math.max(iconsFullHalfExtent(), IslandUiStyle.USAGE_DOT_DIAMETER)
                        + IslandUiStyle.USAGE_PANEL_PAD;
                return new Dimension(getDotOffsetX() + rightExtent, IslandUiStyle.EXPANDED_HEIGHT - 8);
            }
        };
        pnl.setOpaque(false);
        // 天气条常驻显示：面板无论设备是否占用都保持可见
        pnl.setVisible(true);
        weatherIconFont = WeatherIconMapper.getIconFont(IslandUiStyle.WEATHER_ICON_SIZE);
        panel = pnl;
        // 面板就绪后重算天气条文本宽度缓存（getPreferredSize 布局前保证可用）
        refreshWeatherRenderCache();
        // 点击天气条展开/收起天气详情卡片；点击面板其余区域保持原有折叠行为
        pnl.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int weatherRight = dotCenterX - weatherLeftOffset();
                int weatherLeft = weatherRight - getWeatherAreaWidth();
                // 仅电池卡片当前显示（滑动进度为 0）时天气条点击才生效，
                // 切到音乐页后天气条已隐藏，点击不再触发详情展开
                if (slideProgress <= 0f
                        && e.getX() >= weatherLeft - 4 && e.getX() <= dotCenterX - IslandUiStyle.USAGE_DOT_DIAMETER / 2 - 1) {
                    controller.toggleWeatherDetail();
                } else {
                    controller.hideByUser();
                }
            }
        });
        return pnl;
    }

    /** 摄像头/麦克风使用状态回调（EDT） */
    void updateUsage(boolean camera, boolean mic) {
        if (camera != cameraInUse || mic != micInUse) {
            AppLogger.info("IslandWindow", "设备使用状态变化: camera=" + camera + ", mic=" + mic);
        }
        boolean wasAnyInUse = cameraInUse || micInUse;
        cameraInUse = camera;
        micInUse = mic;
        boolean isAnyInUse = camera || mic;

        // 设备从全部空闲变为首次占用：自动弹出扩展岛（先弹出、后显示图标）
        boolean firstUsage = !wasAnyInUse && isAnyInUse;
        if (firstUsage && !controller.isVisible() && !controller.isExpandingOrCollapsing()) {
            AppLogger.info("IslandWindow", "检测到设备首次占用，自动弹出扩展岛");
            controller.setDeviceAutoExpanded(true);
            controller.cancelDeviceAutoHideTimer();
            controller.show();
            // 图标状态延后到展开动画完成时应用，避免图标在展开中途出现
            return;
        }
        applyUsageStates();
    }

    /** 将当前设备占用状态应用到指示器与绿点（EDT） */
    void applyUsageStates() {
        applyDesiredState(cameraIndicator, cameraInUse);
        applyDesiredState(micIndicator, micInUse);
        updateDotTarget();
        refreshPanelVisibility();
    }

    /**
     * 清理已结束的设备使用状态残留（仅当无设备占用时生效）：
     * 指示器未完成的淡出阶段与未归零的绿点进度会在扩展岛隐藏时被冻结，
     * 重新展开时会导致旧绿点/图标短暂闪现，需在此统一清零。
     */
    void cleanupStaleUsageState() {
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
                float e = (System.currentTimeMillis() - ind.phaseStartMs) / (float) IslandUiStyle.USAGE_MORPH_MS;
                ind.phaseStartMs = System.currentTimeMillis() - (long) (e * IslandUiStyle.USAGE_FADE_OUT_MS);
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
            dotProgress = Math.min(1f,
                    dotProgress + IslandUiStyle.USAGE_ANIM_FRAME_MS / (float) IslandUiStyle.USAGE_MORPH_MS);
        } else {
            dotProgress = Math.max(0f,
                    dotProgress - IslandUiStyle.USAGE_ANIM_FRAME_MS / (float) IslandUiStyle.USAGE_FADE_OUT_MS);
        }
    }

    /** 根据指示器状态同步动画定时器与布局（面板因天气条常驻可见，不再随占用状态隐藏） */
    private void refreshPanelVisibility() {
        if (panel == null) return;
        startOrStopUsageAnimTimer();
        if (controller.isVisible()) {
            // 图标数量变化导致面板首选宽度变化：重算音乐面板预留空间与卡片布局
            controller.layoutExpandedPanel();
        }
    }

    /** 根据指示器状态同步动画定时器（展开动画完成时也由控制器调用） */
    void startOrStopUsageAnimTimer() {
        boolean needed = panel != null
                && panel.isVisible()
                && (cameraIndicator.phase != UsagePhase.HIDDEN
                    || micIndicator.phase != UsagePhase.HIDDEN
                    || dotProgress > 0f && (dotTargetVisible ? dotProgress < 1f : true));
        if (needed) {
            if (animTimer == null || !animTimer.isRunning()) {
                if (animTimer != null) animTimer.stop();
                animTimer = new Timer(IslandUiStyle.USAGE_ANIM_FRAME_MS, e -> tickUsageAnim());
                animTimer.start();
            }
        } else {
            stopUsageAnimTimer();
        }
    }

    void stopUsageAnimTimer() {
        if (animTimer != null) {
            animTimer.stop();
            animTimer = null;
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
        if (panel != null && panel.isVisible()) {
            panel.repaint();
        }
        if (sizeMayChange || dotReachedEnd) {
            refreshPanelVisibility();
            if (controller.isVisible()) {
                controller.layoutExpandedPanel();
            }
        }
    }

    /** 推进单个指示器的阶段状态机，返回布局尺寸是否可能变化 */
    private boolean advanceIndicator(DeviceIndicator ind, long now) {
        long elapsed = now - ind.phaseStartMs;
        switch (ind.phase) {
            case ICON:
                if (elapsed >= IslandUiStyle.USAGE_ICON_MS) {
                    ind.phase = UsagePhase.MORPHING;
                    ind.phaseStartMs = now;
                    return true;
                }
                return false;
            case MORPHING:
                if (elapsed >= IslandUiStyle.USAGE_MORPH_MS) {
                    ind.phase = UsagePhase.HIDDEN;
                    return true;
                }
                return false;
            case FADING_OUT:
                if (elapsed >= IslandUiStyle.USAGE_FADE_OUT_MS) {
                    ind.phase = UsagePhase.HIDDEN;
                    return true;
                }
                return false;
            default:
                return false;
        }
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
                float p = Math.min(elapsed / (float) IslandUiStyle.USAGE_MORPH_MS, 1f);
                float e = easeInOutCubic(p);
                iconAlpha = 1f - e;
                iconScale = 1f - 0.4f * e;
                break;
            }
            case FADING_OUT: {
                float p = Math.min(elapsed / (float) IslandUiStyle.USAGE_FADE_OUT_MS, 1f);
                float e = easeInOutCubic(p);
                iconAlpha = 1f - e;
                iconScale = 1f - 0.4f * e;
                break;
            }
            default:
                return;
        }

        int cx = x + IslandUiStyle.USAGE_ICON_SIZE / 2;
        if (iconAlpha > 0f && ind.icon != null) {
            int size = Math.max(1, Math.round(IslandUiStyle.USAGE_ICON_SIZE * iconScale));
            g2d.setComposite(alphaComposite(iconAlpha));
            g2d.drawImage(ind.icon, cx - size / 2, cy - size / 2, size, size, null);
            g2d.setComposite(AlphaComposite.SrcOver);
        }
    }

    /** 绿点在状态面板内的 x 坐标（由扩展岛布局计算，保证与右半圆圆心对齐） */
    void setDotCenterX(int x) {
        dotCenterX = x;
    }

    /** 滚轮切卡滑动进度（由控制器 layoutExpandedPanel 逐帧同步，0=电池卡片 1=音乐卡片） */
    void setSlideProgress(float progress) {
        slideProgress = Math.max(0f, Math.min(1f, progress));
    }

    // ═══════════════════════════════════════
    //  天气条（温度 + 图标 + 中文天气状况）绘制与几何
    // ═══════════════════════════════════════

    /**
     * 绘制天气条：与主岛天气区一致的两行布局——
     * 上行温度（右对齐于绿点左侧），下行图标 + 中文天气状况；
     * 滚轮切卡时位置保持固定，透明度随滑动进度反向变化：
     * 电池卡片（进度 0）完全显示，滑向音乐面板时逐渐淡出，进度 1 完全隐藏；
     * 滑回电池面板时随进度平滑淡入。仅绘制层隐藏，不参与布局尺寸。
     */
    private void paintWeatherStrip(Graphics2D g2d, int cy) {
        // 天气条仅属于电池卡片：切卡进度大于 0 时按进度淡出，完全切到音乐面板后不绘制
        float alpha = 1f - slideProgress;
        if (alpha <= 0f) {
            return;
        }
        int right = dotCenterX - weatherLeftOffset();

        java.awt.Composite oldComposite = g2d.getComposite();
        if (alpha < 1f) {
            g2d.setComposite(alphaComposite(alpha));
        }
        try {
            if (controller.isWeatherDetailOpen()) {
                // 天气详情展开：天气条整体（温度+图标+状况）替换为返回图标，
                // 垂直居中、右对齐于绿点左侧；点击天气条区域收起详情回到扩展岛
                if (returnIcon != null) {
                    int size = IslandUiStyle.WEATHER_RETURN_ICON_SIZE;
                    g2d.drawImage(returnIcon, right - size, cy - size / 2, size, size, null);
                }
                return;
            }
            // 上行：温度（文本与宽度均为缓存，帧内零分配）
            g2d.setFont(IslandUiStyle.WEATHER_TEMP_FONT);
            g2d.setColor(Color.WHITE);
            g2d.drawString(tempText, right - tempTextWidth, cy);

            // 下行：图标 + 中文天气状况（与主岛同行排布，基线对齐，整体右对齐）
            g2d.setFont(IslandUiStyle.WEATHER_COND_FONT);
            g2d.setColor(IslandUiStyle.LIGHT_GRAY);
            g2d.drawString(condText, right - condTextWidth, cy + 14);
            if (weatherIconFont != null) {
                g2d.setFont(weatherIconFont);
            }
            g2d.drawString(weatherIconText,
                    right - condTextWidth - IslandUiStyle.WEATHER_ICON_SIZE - 2, cy + 14);
        } finally {
            g2d.setComposite(oldComposite);
        }
    }

    private int getCondTextWidth() {
        return condTextWidth;
    }

    /** 天气条总宽度：取温度行与图标+状况行的较大者（两行均右对齐于同一右缘） */
    private int getWeatherAreaWidth() {
        return weatherAreaWidth;
    }

    /**
     * 天气条右缘到绿点圆心的水平偏移：无设备图标时为固定间距；
     * 图标展示期间（含淡出）按图标整体左半宽 + 安全间隙确定性左移，
     * 任意帧都不与设备图标/绿点重叠。
     */
    private int weatherLeftOffset() {
        return Math.max(IslandUiStyle.WEATHER_TO_DOT_GAP, iconsFullHalfExtent() + 4);
    }

    /** 当前非隐藏状态的设备图标（按完整尺寸）以绿点为中心占据的左半宽 */
    private int iconsFullHalfExtent() {
        int count = 0;
        if (cameraIndicator.phase != UsagePhase.HIDDEN) count++;
        if (micIndicator.phase != UsagePhase.HIDDEN) count++;
        int iconsW = count * IslandUiStyle.USAGE_ICON_SIZE
                + Math.max(0, count - 1) * IslandUiStyle.USAGE_SLOT_GAP;
        return iconsW / 2;
    }

    /**
     * 面板左缘到绿点圆心的预留宽度（控制器布局锚点）：
     * 需同时容纳天气条（含避让偏移）与设备图标的左半宽。
     */
    int getDotOffsetX() {
        int weatherLeft = getWeatherAreaWidth() + weatherLeftOffset();
        return IslandUiStyle.USAGE_PANEL_PAD + Math.max(weatherLeft, iconsFullHalfExtent());
    }

    private float easeInOutCubic(float x) {
        return x < 0.5f ? 4f * x * x * x : 1f - (float) Math.pow(-2f * x + 2f, 3) / 2f;
    }
}
