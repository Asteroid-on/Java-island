package com.island.island.ui;

import com.formdev.flatlaf.FlatLaf;

import javax.swing.JToggleButton;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;

/**
 * iOS 风格滑块开关（自绘，深/浅色自适应）。
 *
 * <p>继承 {@link JToggleButton}：点击与空格键切换、{@code isSelected()} /
 * {@code addActionListener()} 语义与原生开关一致；滑块位置由 Swing Timer
 * 以 10ms 帧率插值，切换时呈现约 120ms 的平滑滑动过渡，EDT 上仅轻量 repaint。</p>
 */
public class ToggleSwitch extends JToggleButton {

    /** 是否处于系统深色模式（与 SettingsDialog 配色联动） */
    private static final boolean DARK = FlatLaf.isLafDark();

    private static final int WIDTH = 46;
    private static final int HEIGHT = 26;
    /** 滑块距轨道边缘的边距（垂直/水平两端一致），滑块直径 = 轨道高 - 2*PAD */
    private static final int PAD = 2;
    /** 动画帧间隔：10ms，与岛窗口动画同级流畅度 */
    private static final int ANIM_FRAME_MS = 10;

    private static final Color TRACK_ON = new Color(0, 140, 230);
    private static final Color TRACK_OFF = DARK ? new Color(64, 64, 70) : new Color(196, 196, 202);
    private static final Color TRACK_OFF_HOVER = DARK ? new Color(74, 74, 82) : new Color(180, 180, 188);
    private static final Color THUMB_COLOR = DARK ? new Color(235, 235, 240) : Color.WHITE;

    /** 滑块当前横坐标进度（0=关/左，1=开/右），动画期间为中间值 */
    private float thumbPos;
    /** 鼠标悬停态：关闭时轨道微亮，提供可点击反馈 */
    private boolean hovered;
    /** 滑动过渡定时器；每帧向目标逼近，到位后自动停止 */
    private final Timer animTimer;

    public ToggleSwitch(boolean selected) {
        super();
        thumbPos = selected ? 1f : 0f;
        Dimension d = new Dimension(WIDTH, HEIGHT);
        setPreferredSize(d);
        setMinimumSize(d);
        setMaximumSize(d);
        setOpaque(false);
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setRolloverEnabled(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { hovered = true; repaint(); }
            @Override public void mouseExited(MouseEvent e) { hovered = false; repaint(); }
        });

        animTimer = new Timer(ANIM_FRAME_MS, this::onAnimTick);
        setSelected(selected);
        addItemListener(e -> {
            if (!animTimer.isRunning()) animTimer.start();
        });
    }

    /** 动画帧回调：按缓动系数逼近目标位置，到位后停止定时器。 */
    private void onAnimTick(ActionEvent e) {
        float target = isSelected() ? 1f : 0f;
        float diff = target - thumbPos;
        if (Math.abs(diff) < 0.01f) {
            thumbPos = target;
            animTimer.stop();
        } else {
            thumbPos += diff * 0.35f;
        }
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        int w = getWidth(), h = getHeight();

        // 轨道固定高度并垂直居中：组件可能被外层布局（BoxLayout/BorderLayout）纵向拉伸，
        // 若直接用 getHeight 作为轨道高、固定 PAD 作滑块纵坐标，滑块会偏离中心、轨道被拉成高瘦椭圆。
        float trackH = Math.min(h, HEIGHT);
        float trackY = (h - trackH) / 2f;

        // 轨道：胶囊形，颜色随滑块进度在关/开两态间线性过渡，悬停时关闭态微亮
        g2.setColor(blend(hovered ? TRACK_OFF_HOVER : TRACK_OFF, TRACK_ON, thumbPos));
        g2.fill(new RoundRectangle2D.Float(0f, trackY, w, trackH, trackH, trackH));

        // 圆形滑块：直径 = 轨道高 - 上下各 PAD，垂直居中于轨道；矢量绘制保证任意 DPI 下都是正圆。
        float thumbD = trackH - PAD * 2f;
        float travel = w - thumbD - PAD * 2f;
        float tx = PAD + thumbPos * travel;
        float ty = trackY + (trackH - thumbD) / 2f;
        g2.setColor(THUMB_COLOR);
        g2.fill(new Ellipse2D.Float(tx, ty, thumbD, thumbD));
        g2.dispose();
    }

    /** 两色线性插值（t&lt;=0 取 a，t&gt;=1 取 b）。 */
    private static Color blend(Color a, Color b, float t) {
        if (t <= 0f) return a;
        if (t >= 1f) return b;
        int r = Math.round(a.getRed() + (b.getRed() - a.getRed()) * t);
        int gr = Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t);
        int bl = Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t);
        return new Color(r, gr, bl);
    }
}
