package com.island.island.ui.expanded;

import com.island.island.ui.IslandUiStyle;
import com.island.music.model.LyricItem;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.AlphaComposite;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * 扩展岛音乐面板组件：左侧 3x 超采样旋转封面，右侧歌词与歌名-艺术家行。
 * 纯展示职责：歌词/封面数据与播放进度由 MusicSessionController 提供；
 * 封面旋转与歌词滚动定时器亦由本组件持有。所有访问均在 EDT。
 */
class MusicPanel {

    /**
     * 占位封面的字体/几何/颜色：提升为静态常量，避免逐帧分配。
     * 封面旋转定时器以 16ms 跳一帧，旧写法每帧 new Font + new Ellipse2D 会直接转化为 GC 抖动。
     */
    private static final Font COVER_PLACEHOLDER_FONT = new Font("Microsoft YaHei", Font.PLAIN, 42);
    private static final Ellipse2D COVER_PLACEHOLDER_CIRCLE =
            new Ellipse2D.Double(0, 0, IslandUiStyle.COVER_HIRES, IslandUiStyle.COVER_HIRES);
    private static final String COVER_PLACEHOLDER_NOTE = "\u266B";
    private static final Color COVER_PLACEHOLDER_BG = new Color(60, 60, 60);
    private static final Color COVER_PLACEHOLDER_FG = new Color(140, 140, 140);

    private final MusicSessionController session;

    private JPanel panel;
    private JLabel coverLabel;
    private JLabel titleLabel;
    private JLabel artistLabel;
    private JLabel lyricsLabel;
    private boolean initialized = false;

    private Image coverImage;
    private double coverRotationAngle = 0.0;
    private Timer coverRotationTimer;
    private Timer lyricScrollTimer;

    MusicPanel(MusicSessionController session) {
        this.session = session;
    }

    boolean isInitialized() {
        return initialized;
    }

    JPanel getPanel() {
        return panel;
    }

    /**
     * 构建音乐面板（封面 + 歌词 + 歌名-艺术家）。
     * 收起扩展岛后通过 reset() 重置，下次展开时重建。
     */
    void build() {
        if (initialized) return;
        initialized = true;

        panel = new JPanel(new BorderLayout(10, 0));
        panel.setOpaque(false);

        // ── 左侧：旋转封面（3x 超采样 + 正圆形裁剪）──
        coverLabel = new JLabel() {
            /** 3x 超采样画布跨帧复用：旧实现每帧 new BufferedImage(144×144)（约 83KB），
             *  60FPS 旋转下相当于每秒 5MB 分配，GC 抖动直接表现为封面与切卡动画掉帧 */
            private BufferedImage hires;

            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                int w = getWidth(), h = getHeight();
                if (w <= 0 || h <= 0) return;

                // 3x 超采样：在 144px 画布上渲染，再缩至 48px，消除旋转锯齿
                int ssaaSize = IslandUiStyle.COVER_HIRES;
                BufferedImage buffer = hires;
                if (buffer == null || buffer.getWidth() != ssaaSize || buffer.getHeight() != ssaaSize) {
                    buffer = hires = new BufferedImage(ssaaSize, ssaaSize, BufferedImage.TYPE_INT_ARGB);
                }
                Graphics2D bg2d = buffer.createGraphics();
                try {
                    // 每帧从全透明起画：旋转边缘不得残留上一帧像素
                    bg2d.setComposite(AlphaComposite.Clear);
                    bg2d.fillRect(0, 0, ssaaSize, ssaaSize);
                    bg2d.setComposite(AlphaComposite.SrcOver);
                    bg2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    bg2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    bg2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

                    Image cover = coverImage;
                    if (cover == null) {
                        // 占位：深灰正圆 + ♫
                        int cx = ssaaSize / 2, cy = ssaaSize / 2;
                        bg2d.setColor(COVER_PLACEHOLDER_BG);
                        bg2d.fill(COVER_PLACEHOLDER_CIRCLE);
                        bg2d.setColor(COVER_PLACEHOLDER_FG);
                        bg2d.setFont(COVER_PLACEHOLDER_FONT);
                        FontMetrics fm = bg2d.getFontMetrics();
                        bg2d.drawString(COVER_PLACEHOLDER_NOTE,
                                cx - fm.stringWidth(COVER_PLACEHOLDER_NOTE) / 2, cy + fm.getAscent() / 2 - 2);
                    } else {
                        // 旋转 + 绘制超采样封面（封面已是 COVER_HIRES 的圆形位图，等尺寸落画布）
                        AffineTransform old = bg2d.getTransform();
                        bg2d.rotate(Math.toRadians(coverRotationAngle), ssaaSize / 2.0, ssaaSize / 2.0);
                        bg2d.drawImage(cover, 0, 0, ssaaSize, ssaaSize, null);
                        bg2d.setTransform(old);
                    }
                } finally {
                    bg2d.dispose();
                }

                // 高质量缩小至目标尺寸：直接复用传入的 Graphics（g.create() 每帧都要复制一份上下文）
                Graphics2D g2d = (Graphics2D) g;
                Object oldAa = g2d.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
                Object oldInterp = g2d.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
                Object oldQuality = g2d.getRenderingHint(RenderingHints.KEY_RENDERING);
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                int size = Math.min(w, h);
                int x = (w - size) / 2, y = (h - size) / 2;
                g2d.drawImage(buffer, x, y, size, size, null);
                // 旧值可能从未设置过（为 null），setRenderingHint(key, null) 会抛 IllegalArgumentException
                if (oldAa != null) g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAa);
                if (oldInterp != null) g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, oldInterp);
                if (oldQuality != null) g2d.setRenderingHint(RenderingHints.KEY_RENDERING, oldQuality);
            }
        };
        coverLabel.setOpaque(false);
        coverLabel.setPreferredSize(new Dimension(IslandUiStyle.COVER_SIZE, IslandUiStyle.COVER_SIZE));
        coverLabel.setMinimumSize(new Dimension(IslandUiStyle.COVER_SIZE, IslandUiStyle.COVER_SIZE));
        panel.add(coverLabel, BorderLayout.WEST);

        // ── 中央：歌词（上） + 歌名-艺术家（下）──
        JPanel infoPanel = new JPanel(new GridBagLayout());
        infoPanel.setOpaque(false);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;

        // 歌词行
        gbc.gridy = 0;
        gbc.insets = new Insets(2, 4, 0, 0);
        lyricsLabel = new JLabel(" ") {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2d = (Graphics2D) g.create();
                try {
                    g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    List<LyricItem> lines = session.getLrcLines();
                    if (lines.isEmpty() || session.getCurrentLyricIndex() < 0) {
                        g2d.setFont(IslandUiStyle.MUSIC_LYRICS_FONT);
                        FontMetrics fm = g2d.getFontMetrics();
                        g2d.setColor(new Color(255, 255, 255, 100));
                        String ph;
                        if (session.currentInfo() == null || !session.currentInfo().hasSession()) ph = " ";
                        else if (session.isLyricsFetchFailed()) ph = "暂无歌词";
                        else if (session.isFetchingLyrics()) ph = "歌词加载中...";
                        else ph = " ";
                        g2d.drawString(ph, 4, getHeight() / 2 + fm.getAscent() / 2 - 1);
                        return;
                    }
                    String content = lines.get(session.getCurrentLyricIndex()).content;
                    // 韩/日等非中文字形回退：主字体无法完整显示时自动换候选字体，避免方框
                    g2d.setFont(IslandUiStyle.resolveDisplayFont(IslandUiStyle.MUSIC_LYRICS_FONT, content));
                    FontMetrics fm = g2d.getFontMetrics();
                    g2d.setColor(Color.WHITE);
                    g2d.drawString(content,
                            4, getHeight() / 2 + fm.getAscent() / 2 - 1);
                } finally {
                    g2d.dispose();
                }
            }
        };
        lyricsLabel.setForeground(Color.WHITE);
        lyricsLabel.setFont(IslandUiStyle.MUSIC_LYRICS_FONT);
        lyricsLabel.setMinimumSize(new Dimension(100, 18));
        lyricsLabel.setPreferredSize(new Dimension(350, 18));
        infoPanel.add(lyricsLabel, gbc);

        // 歌名 + 艺术家
        gbc.gridy = 1;
        gbc.insets = new Insets(1, 4, 2, 0);
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        row.setOpaque(false);
        titleLabel = new JLabel("");
        titleLabel.setForeground(new Color(180, 180, 180));
        titleLabel.setFont(IslandUiStyle.MUSIC_TITLE_FONT);
        row.add(titleLabel);
        JLabel dash = new JLabel(" - ");
        dash.setForeground(new Color(140, 140, 140));
        dash.setFont(IslandUiStyle.MUSIC_ARTIST_FONT);
        row.add(dash);
        artistLabel = new JLabel("");
        artistLabel.setForeground(new Color(140, 140, 140));
        artistLabel.setFont(IslandUiStyle.MUSIC_ARTIST_FONT);
        row.add(artistLabel);
        infoPanel.add(row, gbc);

        panel.add(infoPanel, BorderLayout.CENTER);
    }

    /** 收起扩展岛后重置面板状态（下次展开时重建） */
    void reset() {
        stopCoverRotation();
        stopLyricScrollTimer();
        initialized = false;
        panel = null;
        coverLabel = null;
        titleLabel = null;
        artistLabel = null;
        lyricsLabel = null;
    }

    void setCoverImage(Image image) {
        coverImage = image;
    }

    Image getCoverImage() {
        return coverImage;
    }

    void repaintCover() {
        if (coverLabel != null) coverLabel.repaint();
    }

    /** 释放封面图像资源（窗口销毁时调用，与原 IslandWindow.cleanupImageResources 行为一致） */
    void flushCoverImage() {
        if (coverImage instanceof java.awt.image.BufferedImage) {
            ((java.awt.image.BufferedImage) coverImage).flush();
        }
        coverImage = null;
    }

    void setTitleText(String text) {
        if (titleLabel != null) {
            titleLabel.setText(text);
            // 韩/日等非中文字形回退，避免方框（歌名原文未被截断时按全文判断）
            titleLabel.setFont(IslandUiStyle.resolveDisplayFont(IslandUiStyle.MUSIC_TITLE_FONT, text));
        }
    }

    void setArtistText(String text) {
        if (artistLabel != null) {
            artistLabel.setText(text);
            artistLabel.setFont(IslandUiStyle.resolveDisplayFont(IslandUiStyle.MUSIC_ARTIST_FONT, text));
        }
    }

    void setLyricsText(String text) {
        if (lyricsLabel != null) {
            lyricsLabel.setText(text);
            lyricsLabel.repaint();
        }
    }

    void repaintLyrics() {
        if (lyricsLabel != null) lyricsLabel.repaint();
    }

    /** 歌词异步到达后重排歌词标签所在容器，确保尺寸更新 */
    void revalidateLyricsParent() {
        if (lyricsLabel == null) return;
        Container p = lyricsLabel.getParent();
        if (p != null) {
            p.revalidate();
            p.repaint();
        }
    }

    boolean isCoverRotationRunning() {
        return coverRotationTimer != null && coverRotationTimer.isRunning();
    }

    boolean isLyricScrollRunning() {
        return lyricScrollTimer != null && lyricScrollTimer.isRunning();
    }

    void startCoverRotation() {
        if (coverRotationTimer != null && coverRotationTimer.isRunning()) return;
        coverRotationTimer = new Timer(IslandUiStyle.COVER_ROTATION_FRAME_MS, e -> {
            coverRotationAngle = (coverRotationAngle + IslandUiStyle.COVER_ROTATION_DEG_PER_FRAME) % 360.0;
            // 展开/收起/切卡动画进行中：这些动画本身已逐帧整窗重绘（封面同步按当前角度被画），
            // 此处再 repaint 只会在同一帧造成两次全量重绘，两个 60FPS 定时器互抢 EDT → 动画卡顿。
            // 仅跳过重复绘制，角度照常累计，动画结束后下一拍自然接上，无跳变
            if (session.isUiAnimationBusy()) return;
            if (coverLabel != null) coverLabel.repaint();
        });
        coverRotationTimer.start();
    }

    void stopCoverRotation() {
        if (coverRotationTimer != null) {
            coverRotationTimer.stop();
            coverRotationTimer = null;
        }
    }

    void startLyricScrollTimer() {
        if (lyricScrollTimer != null && lyricScrollTimer.isRunning()) return;
        if (lyricScrollTimer != null) {
            lyricScrollTimer.stop();
            lyricScrollTimer = null;
        }
        // 暂停状态不启动定时器
        if (session.currentInfo() == null || !session.currentInfo().isStrictlyPlaying()) return;
        if (session.currentInfo().getEndTimeTicks() > 0) {
            session.setLastDaemonEndTimeMs(session.currentInfo().getEndTimeTicks() / 10_000);
        }
        System.out.println("[LyricProgress] start timer interval=" + IslandUiStyle.LYRIC_SCROLL_MS + "ms");
        lyricScrollTimer = new Timer(IslandUiStyle.LYRIC_SCROLL_MS, e -> {
            if (session.getLrcLines().isEmpty()) return;
            session.updateProgressDisplay(session.currentInfo());
        });
        lyricScrollTimer.start();
    }

    void stopLyricScrollTimer() {
        if (lyricScrollTimer != null) {
            lyricScrollTimer.stop();
            lyricScrollTimer = null;
        }
    }
}
