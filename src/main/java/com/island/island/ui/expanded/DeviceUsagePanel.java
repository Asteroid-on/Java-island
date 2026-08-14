package com.island.island.ui.expanded;

import com.island.island.ui.IslandUiStyle;
import com.island.util.AppLogger;

import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.AlphaComposite;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;

/**
 * 扩展岛摄像头/麦克风使用状态面板：
 * 图标弹入 → 变形为面板级唯一绿点 → 释放时淡出 的阶段状态机。
 * 所有状态访问与更新均在 EDT；设备首次占用时通过控制器自动弹出扩展岛。
 */
class DeviceUsagePanel {

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

    boolean isPanelVisible() {
        return panel != null && panel.isVisible();
    }

    /** 当前是否有任一设备占用（用于空闲自动收起巡检） */
    boolean isAnyUsage() {
        return cameraInUse || micInUse;
    }

    /** 构建摄像头/麦克风使用状态面板（扩展岛最右侧） */
    JPanel build(Image cameraIcon, Image micIcon) {
        cameraIndicator.icon = cameraIcon;
        micIndicator.icon = micIcon;

        JPanel pnl = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2d = (Graphics2D) g.create();
                try {
                    g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    int cy = getHeight() / 2;

                    // 唯一绿色圆点：固定在右半圆圆心（面板内坐标 dotCenterX）
                    if (dotProgress > 0f) {
                        float e = easeInOutCubic(Math.min(dotProgress, 1f));
                        int dot = Math.max(1, Math.round(IslandUiStyle.USAGE_DOT_DIAMETER * (0.5f + 0.5f * e)));
                        g2d.setColor(new java.awt.Color(IslandUiStyle.GREEN.getRed(),
                                IslandUiStyle.GREEN.getGreen(), IslandUiStyle.GREEN.getBlue(), (int) (255 * e)));
                        g2d.fillOval(dotCenterX - dot / 2, cy - dot / 2, dot, dot);
                    }

                    // 图标并排显示（高度对齐），整体对称于圆心
                    boolean camIcon = cameraIndicator.phase != UsagePhase.HIDDEN;
                    boolean micIcon = micIndicator.phase != UsagePhase.HIDDEN;
                    int count = (camIcon ? 1 : 0) + (micIcon ? 1 : 0);
                    if (count > 0) {
                        int totalW = count * IslandUiStyle.USAGE_ICON_SIZE
                                + (count - 1) * IslandUiStyle.USAGE_SLOT_GAP;
                        int x = (getWidth() - totalW) / 2;
                        if (camIcon) {
                            paintIndicator(g2d, cameraIndicator, x, cy);
                            x += IslandUiStyle.USAGE_ICON_SIZE + IslandUiStyle.USAGE_SLOT_GAP;
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
                int iconsW = count * IslandUiStyle.USAGE_ICON_SIZE
                        + Math.max(0, count - 1) * IslandUiStyle.USAGE_SLOT_GAP;
                // 无图标时保留绿点尺寸，保持面板中心稳定在圆心
                int w = IslandUiStyle.USAGE_PANEL_PAD * 2
                        + Math.max(iconsW, IslandUiStyle.USAGE_ICON_SIZE);
                return new Dimension(w, IslandUiStyle.EXPANDED_HEIGHT - 8);
            }
        };
        pnl.setOpaque(false);
        boolean anyVisible = cameraIndicator.phase != UsagePhase.HIDDEN
                || micIndicator.phase != UsagePhase.HIDDEN
                || dotProgress > 0f;
        pnl.setVisible(anyVisible);
        panel = pnl;
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

    /** 根据指示器状态同步状态面板可见性与动画定时器 */
    private void refreshPanelVisibility() {
        if (panel == null) return;
        boolean anyVisible = cameraIndicator.phase != UsagePhase.HIDDEN
                || micIndicator.phase != UsagePhase.HIDDEN
                || dotProgress > 0f;
        boolean wasVisible = panel.isVisible();
        if (anyVisible != wasVisible) {
            panel.setVisible(anyVisible);
        }
        startOrStopUsageAnimTimer();
        if (anyVisible != wasVisible && controller.isVisible()) {
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
            g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, iconAlpha));
            g2d.drawImage(ind.icon, cx - size / 2, cy - size / 2, size, size, null);
            g2d.setComposite(AlphaComposite.SrcOver);
        }
    }

    /** 绿点在状态面板内的 x 坐标（由扩展岛布局计算，保证与右半圆圆心对齐） */
    void setDotCenterX(int x) {
        dotCenterX = x;
    }

    private float easeInOutCubic(float x) {
        return x < 0.5f ? 4f * x * x * x : 1f - (float) Math.pow(-2f * x + 2f, 3) / 2f;
    }
}
