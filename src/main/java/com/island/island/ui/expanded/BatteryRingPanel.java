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

    /** 构建圆环电池仪表面板 */
    JPanel build() {
        int panelSize = IslandUiStyle.EXPANDED_HEIGHT - 6;

        JPanel pnl = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                BatteryMonitor.BatteryInfo info = currentInfo;
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
        panel = pnl;
        pnl.setVisible(currentInfo.present);
        return pnl;
    }

    /** 电池仪表面板（由扩展岛控制器挂载） */
    JPanel getPanel() {
        return panel;
    }

    /** 电池状态更新回调（EDT） */
    void updateBatteryInfo(BatteryMonitor.BatteryInfo info) {
        currentInfo = info;
        if (panel != null) {
            panel.setVisible(info.present);
            panel.repaint();
        }
    }
}
