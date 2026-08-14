package com.island.island.ui.expanded;

import com.island.island.ui.IslandUiStyle;
import com.island.music.model.LyricItem;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
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
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                int w = getWidth(), h = getHeight();
                if (w <= 0 || h <= 0) return;

                // 3x 超采样：在 144px 画布上渲染，再缩至 48px，消除旋转锯齿
                int ssaaSize = IslandUiStyle.COVER_HIRES;
                BufferedImage buffer = new BufferedImage(ssaaSize, ssaaSize, BufferedImage.TYPE_INT_ARGB);
                Graphics2D bg2d = buffer.createGraphics();
                try {
                    bg2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    bg2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    bg2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

                    Image cover = coverImage;
                    if (cover == null) {
                        // 占位：深灰正圆 + ♫
                        int cx = ssaaSize / 2, cy = ssaaSize / 2;
                        bg2d.setColor(new Color(60, 60, 60));
                        bg2d.fill(new Ellipse2D.Double(0, 0, ssaaSize, ssaaSize));
                        bg2d.setColor(new Color(140, 140, 140));
                        bg2d.setFont(new java.awt.Font("Microsoft YaHei", java.awt.Font.PLAIN, 42));
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
                    g2d.setFont(IslandUiStyle.MUSIC_LYRICS_FONT);
                    FontMetrics fm = g2d.getFontMetrics();
                    List<LyricItem> lines = session.getLrcLines();
                    if (lines.isEmpty() || session.getCurrentLyricIndex() < 0) {
                        g2d.setColor(new Color(255, 255, 255, 100));
                        String ph = session.currentInfo() != null && session.currentInfo().hasSession()
                                ? "歌词加载中..." : " ";
                        g2d.drawString(ph, 4, getHeight() / 2 + fm.getAscent() / 2 - 1);
                        return;
                    }
                    g2d.setColor(Color.WHITE);
                    g2d.drawString(lines.get(session.getCurrentLyricIndex()).content,
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
        if (titleLabel != null) titleLabel.setText(text);
    }

    void setArtistText(String text) {
        if (artistLabel != null) artistLabel.setText(text);
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
