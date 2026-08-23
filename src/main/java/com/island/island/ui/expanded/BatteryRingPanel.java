package com.island.island.ui.expanded;

import com.island.battery.BatteryMonitor;
import com.island.island.ui.IslandUiStyle;

import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;

/**
 * 扩展岛左侧的圆环电池仪表：弧线进度 + 居中百分比 + 状态文字。
 * 由电池监控回调驱动显示与重绘（所有访问均在 EDT）。
 */
class BatteryRingPanel {

    private JPanel panel;
    private volatile BatteryMonitor.BatteryInfo currentInfo = BatteryMonitor.BatteryInfo.ABSENT;

    // ── 绘制资源缓存（跨帧复用：动画期间每帧绘制不再 new 字体/颜色/笔触，降低 GC 停顿概率）──
    private static final Font PCT_FONT = new Font("Microsoft YaHei", Font.PLAIN, 13);
    private static final Font STATUS_FONT = new Font("Microsoft YaHei", Font.PLAIN, 9);
    private static final Color BG_RING_COLOR = new Color(70, 70, 70);
    private static final Color STATUS_TEXT_COLOR = new Color(200, 200, 200);
    private static final Color CHARGING_ARC_COLOR = new Color(0x50, 0xDC, 0x64);
    private static final Color LOW_ARC_COLOR = new Color(0xFF, 0x8C, 0x3C);
    private static final BasicStroke RING_STROKE = new BasicStroke(5, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);

    // ── 渲染状态缓存（仅在电池数据变化时重算；paintComponent 逐帧只读，零逐帧分配）──
    private volatile boolean infoPresent;
    private volatile int infoPercentage;
    private volatile String pctText = "";
    private volatile String statusText = "";
    private volatile Color arcColor = Color.WHITE;

    /** 构建圆环电池仪表面板 */
    JPanel build() {
        int panelSize = IslandUiStyle.EXPANDED_HEIGHT - 6;

        JPanel pnl = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                if (!infoPresent) return;

                Graphics2D g2d = (Graphics2D) g.create();
                try {
                    g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2d.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

                    int w = getWidth(), h = getHeight();
                    int ringDiam = Math.min(w, h) - 8;
                    int cx = w / 2, cy = h / 2;
                    int ringOuter = ringDiam / 2;

                    // 背景圆环
                    g2d.setColor(BG_RING_COLOR);
                    g2d.setStroke(RING_STROKE);
                    g2d.draw(new Arc2D.Double(cx - ringOuter, cy - ringOuter, ringOuter * 2, ringOuter * 2, 0, 360, Arc2D.OPEN));

                    // 前景弧（从12点顺时针）
                    double sweep = -360.0 * infoPercentage / 100.0;
                    g2d.setColor(arcColor);
                    g2d.draw(new Arc2D.Double(cx - ringOuter, cy - ringOuter, ringOuter * 2, ringOuter * 2, 90, sweep, Arc2D.OPEN));

                    // ── 居中百分比数字 ──
                    g2d.setColor(Color.WHITE);
                    g2d.setFont(PCT_FONT);
                    FontMetrics pfm = g2d.getFontMetrics(PCT_FONT);
                    int pctW = pfm.stringWidth(pctText);
                    int pctY = cy - 2;
                    g2d.drawString(pctText, cx - pctW / 2, pctY);

                    // ── 状态文字（百分比下方）──
                    g2d.setColor(STATUS_TEXT_COLOR);
                    g2d.setFont(STATUS_FONT);
                    FontMetrics sfm = g2d.getFontMetrics(STATUS_FONT);
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
        panel = pnl;
        pnl.setVisible(currentInfo.present);
        return pnl;
    }

    /** 电池仪表面板（由扩展岛控制器挂载） */
    JPanel getPanel() {
        return panel;
    }

    /** 电池状态更新回调（EDT）：数据变化时一次性重算渲染状态缓存，paintComponent 逐帧零分配 */
    void updateBatteryInfo(BatteryMonitor.BatteryInfo info) {
        currentInfo = info;
        infoPresent = info.present;
        infoPercentage = info.percentage;
        pctText = String.valueOf(info.percentage);
        if (info.charging || info.percentage >= 100) {
            arcColor = CHARGING_ARC_COLOR;
        } else if (info.percentage <= 20) {
            arcColor = LOW_ARC_COLOR;
        } else {
            arcColor = Color.WHITE;
        }
        if (!info.present) {
            statusText = "无电池";
        } else if (info.charging) {
            statusText = info.percentage >= 100 ? "满电" : "充电中";
        } else if (info.percentage >= 100) {
            statusText = "满电";
        } else {
            statusText = "放电";
        }
        if (panel != null) {
            panel.setVisible(info.present);
            panel.repaint();
        }
    }
}
