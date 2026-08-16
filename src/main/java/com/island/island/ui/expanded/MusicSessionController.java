package com.island.island.ui.expanded;

import com.island.island.ui.IslandUiStyle;
import com.island.music.LyricsService;
import com.island.music.model.LyricItem;
import com.island.music.model.MusicInfo;
import com.island.config.AppConstants;
import com.island.util.AppLogger;

import javax.imageio.ImageIO;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.MediaTracker;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.net.URL;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

/**
 * 音乐会话状态机：曲目切换检测、歌词/封面异步获取、进度展示、
 * 音乐岛自动弹出与停止后 2 分钟自动收回决策。
 * 不直接操作 Swing 组件，UI 更新通过 MusicPanel 与 ExpandedIslandController 完成。
 * 所有状态访问与更新均在 EDT（异步获取线程仅通过 invokeLater 回写）。
 */
class MusicSessionController {

    private final ExpandedIslandController controller;
    private final transient LyricsService lyricsService = new LyricsService();

    private List<LyricItem> lrcLines = Collections.emptyList();
    private volatile MusicInfo currentMusicInfo = MusicInfo.EMPTY;
    private int currentLyricIndex = -1;
    private volatile boolean fetchingLyrics = false;
    private volatile boolean fetchingCover = false;
    private volatile String lastFetchedTrackId = "";
    private volatile String lastFetchedCoverTrackId = "";
    private String lastCoverBase64 = "";
    /** 最近一次尝试解码的 SMTC Base64：解码后无论是否应用都记录，防止每轮重复解码 */
    private String lastTriedCoverBase64 = "";
    /** 切歌前上一曲目的 SMTC Base64：新曲目仍上报相同缩略图时判定为 daemon 旧图，不信任 */
    private String prevTrackCoverBase64 = "";
    /** SMTC Base64 封面成功应用对应的曲目标识（title|artist），用于 URL 源封面跳过判断 */
    private String smTcCoverAppliedTrackId = "";
    /** 上一次处于严格播放状态的来源播放器标识，用于检测活跃播放器切换 */
    private String lastActiveSourceAppId = "";
    // 仅使用 daemon 汇报的 positionTicks 作为歌词进度
    // fallback 字段已废弃，wall-clock 自推进机制已移除
    @Deprecated private long fallbackBaseMs = 0;
    @Deprecated private long fallbackStartMs = 0;
    private long lastDaemonEndTimeMs = 0;

    MusicSessionController(ExpandedIslandController controller) {
        this.controller = controller;
    }

    // ── 包级状态访问器（供 MusicPanel / ExpandedIslandController 读取） ──

    List<LyricItem> getLrcLines() {
        return lrcLines;
    }

    int getCurrentLyricIndex() {
        return currentLyricIndex;
    }

    MusicInfo currentInfo() {
        return currentMusicInfo;
    }

    void setLastDaemonEndTimeMs(long ms) {
        lastDaemonEndTimeMs = ms;
    }

    boolean isStrictlyPlaying() {
        return currentMusicInfo != null && currentMusicInfo.isStrictlyPlaying();
    }

    boolean hasSession() {
        return currentMusicInfo != null && currentMusicInfo.hasSession();
    }

    /** 是否有活跃媒体会话（有会话且曲目非空） */
    boolean hasActiveSession() {
        return currentMusicInfo.hasSession() && !currentMusicInfo.getTitle().isEmpty();
    }

    /** 供 SystemTrayManager 通过 IslandWindow 获取 LyricsService 引用 */
    LyricsService getLyricsService() {
        return lyricsService;
    }

    // ═══════════════════════════════════════════
    //  音乐状态机
    // ═══════════════════════════════════════════

    /** 音乐监控回调（EDT） */
    void onMusicInfoChanged(MusicInfo info) {
        if (info == null) return;
        boolean wasPlaying = currentMusicInfo.isPlaying();
        boolean isPlaying = info.isPlaying();
        boolean wasStrictly = currentMusicInfo.isStrictlyPlaying();
        boolean isStrictly = info.isStrictlyPlaying();
        boolean wasSession = currentMusicInfo.hasSession();
        currentMusicInfo = info;
        MusicPanel mp = controller.getMusicPanel();

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
            // 切歌：记录上一曲缩略图用于旧图识别，清空旧封面确保与新曲目严格对应
            prevTrackCoverBase64 = lastCoverBase64;
            lastCoverBase64 = "";
            lastTriedCoverBase64 = "";
            smTcCoverAppliedTrackId = "";
            lastFetchedTrackId = "";
            lastFetchedCoverTrackId = "";
            mp.flushCoverImage();
            mp.repaintCover();
            mp.setLyricsText(" ");
        }

        if (AppConstants.DEBUG_CONSOLE) {
            System.out.println("[IslandWindow] updateMusicInfo: wasPlaying=" + wasPlaying
                    + " isPlaying=" + isPlaying + " expandedVisible=" + controller.isVisible()
                    + " srcSwitched=" + activeSourceSwitched);
        }

        // 歌词进度完全依赖 daemon 汇报的 positionTicks

        String trackId = info.getTitle() + "|" + info.getArtist();
        if (info.hasSession() && !trackId.equals(lastFetchedTrackId) && !info.getTitle().isEmpty()) {
            lastFetchedTrackId = trackId;
            if (!activeSourceSwitched) {
                // 切歌：重置歌词状态；记录上一曲缩略图并清空旧封面，确保封面与新曲目严格对应
                lyricsService.clear();
                lrcLines = Collections.emptyList();
                currentLyricIndex = -1;
                lastDaemonEndTimeMs = 0;
                fetchingLyrics = false;
                fetchingCover = false;
                prevTrackCoverBase64 = lastCoverBase64;
                lastCoverBase64 = "";
                lastTriedCoverBase64 = "";
                smTcCoverAppliedTrackId = "";
                mp.flushCoverImage();
                mp.repaintCover();
                mp.setLyricsText(" ");
            }
            fetchLyricsAsync(info.getTitle(), info.getArtist());
            fetchCoverAsync(info.getTitle(), info.getArtist());
            // 面板已显示时立即应用新曲目信息（歌名/艺术家/SMTC 封面）
            if (controller.isMusicPanelShown() && mp.isInitialized()) {
                updateMusicPanelContent();
            }
        }

        // SMTC 缩略图优先：b64 到达/变化时立即应用，覆盖可能先到的 URL 封面
        if (controller.isMusicPanelShown() && mp.isInitialized()
                && !info.getThumbnailBase64().isEmpty()
                && !info.getThumbnailBase64().equals(lastCoverBase64)) {
            updateMusicPanelContent();
        }

        // 媒体会话出现：占位面板 → 自动切换到音乐面板
        if (info.hasSession() && !wasSession && controller.isMusicPanelShown()
                && controller.isPlaceholderShown()) {
            System.out.println("[IslandWindow] 媒体会话出现，自动切换到音乐面板");
            controller.ensureMusicPanelInExpandedWindow();
        }

        // ── 音乐岛自动弹出与常驻 ──
        if (activeSourceSwitched) {
            controller.setMusicPopupSuppressedByUser(false);
            controller.setMusicPanelAutoShownForSession(false);
        }
        updateMusicIslandAutoPopup(info);

        // 严格播放恢复（暂停→播放、会话恢复等）：取消停止满 2 分钟自动收回计时，继续常驻
        boolean playbackResumed = isStrictly && !wasStrictly;
        boolean sessionRestored = isPlaying && !wasPlaying;
        if (playbackResumed || sessionRestored) {
            controller.cancelMusicStopAutoHideTimer();
            controller.setMusicPopupSuppressedByUser(false);
            if (controller.isMusicPanelShown()) {
                // 按当前播放位置刷新歌词游标：恢复播放时从暂停时定位的歌词行继续正常滚动，
                // 暂停状态下恢复会话时同样定位到当前播放位置对应的歌词行
                updateProgressDisplay(info);
                if (playbackResumed) {
                    mp.startCoverRotation();
                    mp.startLyricScrollTimer();
                }
            }
        } else if (wasStrictly && !isStrictly) {
            // 停止播放（变为暂停/停止/会话丢失）：每次暂停均启动 2 分钟自动收回计时，
            // 到期时仅在扩展岛显示音乐面板的情况下才真正收回（见 startMusicStopAutoHideTimer）
            mp.stopCoverRotation();
            mp.stopLyricScrollTimer();
            // 保留 lastFetchedTrackId 与已拉取的歌词/封面，
            // 避免暂停后恢复播放同一首歌时被切歌检测误判，导致封面被清空重新拉取而短暂消失
            if (info.isPlaying()) {
                // 暂停：按 daemon 汇报的暂停位置定位歌词游标，保持该行高亮显示
                updateProgressDisplay(info);
            } else {
                // 停止/会话丢失：重置歌词游标退回占位（保持原有处理不变）
                currentLyricIndex = -1;
                mp.repaintLyrics();
            }
            controller.setMusicPopupSuppressedByUser(false);
            controller.setMusicPanelAutoShownForSession(false);
            if (controller.isVisible()) controller.startMusicStopAutoHideTimer();
        } else if (wasPlaying && !info.isPlaying()) {
            // 暂停后再停止/关闭播放器：清空残留歌词游标，退回占位显示
            currentLyricIndex = -1;
            mp.repaintLyrics();
        } else if (isPlaying && controller.isVisible()) {
            if (controller.isMusicPanelShown()) {
                if (isStrictly) {
                    if (!mp.isCoverRotationRunning()) mp.startCoverRotation();
                    if (!mp.isLyricScrollRunning()) mp.startLyricScrollTimer();
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
        if (controller.isExpandingOrCollapsing()) return;
        // 播放期间扩展岛常驻：取消设备 5 秒自动隐藏与停止收回计时
        controller.cancelDeviceAutoHideTimer();
        controller.clearDeviceAutoExpanded();
        controller.cancelMusicStopAutoHideTimer();
        if (!info.isPlayerMinimized()) return;
        if (controller.isMusicPopupSuppressedByUser()) return;
        if (controller.isMusicPanelShown()) {
            controller.setMusicPanelAutoShownForSession(true);
            return;
        }
        if (!controller.isVisible()) {
            AppLogger.info("IslandWindow", "检测到音乐播放且播放器最小化，自动弹出音乐岛");
            controller.setMusicAutoExpanded(true);
            controller.setMusicPanelAutoShownForSession(true);
            controller.show();
        } else if (!controller.isMusicPanelAutoShownForSession()) {
            controller.setMusicPanelAutoShownForSession(true);
            controller.showMusicPanelInExpanded();
        }
    }

    /** 应用当前曲目信息到音乐面板（歌名/艺术家/歌词/封面），EDT */
    void updateMusicPanelContent() {
        MusicPanel mp = controller.getMusicPanel();
        if (!mp.isInitialized() || currentMusicInfo == null) return;
        String title = currentMusicInfo.getTitle();
        String artist = currentMusicInfo.getArtist();
        String fullTitle = title, fullArtist = artist;
        if (title.length() > 15) title = title.substring(0, 14) + "...";
        mp.setTitleText(title.isEmpty() ? "未知歌曲" : title);
        if (artist.length() > 12) artist = artist.substring(0, 11) + "...";
        mp.setArtistText(artist.isEmpty() ? "未知艺术家" : artist);

        if (AppConstants.DEBUG_CONSOLE) {
            System.out.println("[IslandWindow] updateMusicPanelContent: title=" + fullTitle
                    + " artist=" + fullArtist + " hasLyrics=" + !lrcLines.isEmpty()
                    + " hasCover=" + (currentMusicInfo.getThumbnailBase64().length() > 0));
        }

        if (!lrcLines.isEmpty()) {
            updateProgressDisplay(currentMusicInfo);
        } else {
            mp.setLyricsText(" ");
            fetchLyricsAsync(fullTitle, fullArtist);
        }

        // 封面：SMTC Base64 强制优先；daemon 时序滞后的旧图不信任，低分辨率缩略图插值提升后使用
        String b64 = currentMusicInfo.getThumbnailBase64();
        if (!b64.isEmpty()) {
            if (b64.equals(prevTrackCoverBase64)) {
                // 新曲目仍上报上一曲的缩略图 → 判定为 daemon 旧图，不信任，等待网络封面补位
                if (AppConstants.DEBUG_CONSOLE) {
                    System.out.println("[IslandWindow] SMTC缩略图与上一曲相同，判定为旧图，等待网络封面");
                }
                smTcCoverAppliedTrackId = "";
            } else if (b64.equals(lastTriedCoverBase64)) {
                // 已尝试解码：仅当该图确实已应用时标记，避免每轮重复解码
                smTcCoverAppliedTrackId = b64.equals(lastCoverBase64) ? fullTitle + "|" + fullArtist : "";
            } else {
                lastTriedCoverBase64 = b64;
                smTcCoverAppliedTrackId = "";
                try {
                    byte[] data = Base64.getDecoder().decode(b64);
                    Image raw = Toolkit.getDefaultToolkit().createImage(data);
                    MediaTracker mt = new MediaTracker(new JLabel());
                    mt.addImage(raw, 0);
                    mt.waitForID(0, 1000);
                    int w = raw.getWidth(null);
                    if (w > 0) {
                        // 强制使用 SMTC 缩略图：低分辨率由 createCircularCover 双三次插值提升至 COVER_HIRES(144px)
                        if (w < 200) {
                            System.out.println("[IslandWindow] SMTC缩略图分辨率较低(" + w + "px)，已插值提升显示");
                        }
                        mp.setCoverImage(createCircularCover(raw, IslandUiStyle.COVER_HIRES));
                        lastCoverBase64 = b64;
                        smTcCoverAppliedTrackId = fullTitle + "|" + fullArtist;
                    }
                } catch (Exception ex) {
                    // 解码失败：保留当前封面显示，标记保持未应用，让 URL 源补位，避免封面卡死
                }
            }
        } else {
            smTcCoverAppliedTrackId = "";
            // 避免每轮询重复发起请求或清空已显示的封面
            String currentTrackId = fullTitle + "|" + fullArtist;
            boolean alreadyFetching = fetchingCover && currentTrackId.equals(lastFetchedCoverTrackId);
            if (!alreadyFetching && mp.getCoverImage() == null) {
                fetchCoverAsync(fullTitle, fullArtist);
            }
            // 仅在曲目切换时才清空旧封面（由 onMusicInfoChanged 切歌流程处理）
        }
        mp.repaintCover();
    }

    /** 根据 daemon 汇报的 positionTicks 推进歌词游标（EDT） */
    void updateProgressDisplay(MusicInfo info) {
        MusicPanel mp = controller.getMusicPanel();
        if (!mp.isInitialized() || info == null || lrcLines.isEmpty()) return;
        long daemonPos = info.getPositionTicks() / 10_000;
        long pos = Math.max(daemonPos, 0) + 900;  // 提前0.9秒显示歌词
        long end = info.getEndTimeTicks() / 10_000;
        if (end <= 0 && lastDaemonEndTimeMs > 0) {
            end = lastDaemonEndTimeMs;
        }
        if (end > 0 && pos > end) pos = end;
        int idx = lyricsService.findLineIndex(lrcLines, pos);
        // 高频日志（播放期间每 300ms 一次）：默认关闭输出，需诊断时用 -Disland.debug=true 开启
        if (AppConstants.DEBUG_CONSOLE) {
            System.out.printf("[LyricProgress] position=%dms idx=%d/%d '%s'%n",
                    pos, idx, lrcLines.size(),
                    idx >= 0 && idx < lrcLines.size() ? lrcLines.get(idx).content : "N/A");
        }
        if (idx != currentLyricIndex) {
            currentLyricIndex = idx;
            mp.repaintLyrics();
        }
    }

    // ═══════════════════════════════════════════
    //  封面渲染 & 异步获取
    // ═══════════════════════════════════════════

    private void fetchLyricsAsync(String title, String artist) {
        if (title.isEmpty() || artist.isEmpty()) return;
        if (!lrcLines.isEmpty() || fetchingLyrics) return;
        fetchingLyrics = true;
        final String trackId = title + "|" + artist;
        final String srcAppId = currentMusicInfo.getSourceAppId();
        System.out.println("[IslandWindow] 开始异步获取歌词: " + title + " - " + artist + " src=" + srcAppId);
        new Thread(() -> {
            try {
                List<LyricItem> lines = lyricsService.getLyrics(title, artist, srcAppId);
                System.out.println("[IslandWindow] 歌词获取结果: " + lines.size() + " 行");
                if (!lines.isEmpty()) {
                    SwingUtilities.invokeLater(() -> {
                        // stale-track 校验：歌词只属于发起请求时的曲目
                        String currentTrackId = currentMusicInfo.getTitle() + "|" + currentMusicInfo.getArtist();
                        if (!trackId.equals(currentTrackId)) {
                            System.out.println("[IslandWindow] 歌词已过期（曲目已切换），丢弃");
                            return;
                        }
                        lrcLines = lines;
                        currentLyricIndex = -1;
                        System.out.println("[LyricProgress] 歌词异步加载完成: " + lines.size() + " 行");
                        MusicPanel mp = controller.getMusicPanel();
                        if (mp.isInitialized()) {
                            // 暂停期间加载完成：仍按暂停时的播放位置立即定位并显示对应歌词行
                            updateProgressDisplay(currentMusicInfo);
                            mp.revalidateLyricsParent();
                        }
                        // 确保定时器在运行（可能在歌词加载前已启动但因 lrcLines 为空而空转；
                        // 暂停状态下不会启动，见 startLyricScrollTimer 的 isStrictlyPlaying 守卫）
                        mp.startLyricScrollTimer();
                    });
                }
            } finally {
                // 仅当此请求仍为"当前活跃请求"时才释放锁，防止旧曲目线程误清标志
                if (trackId.equals(lastFetchedTrackId)) {
                    fetchingLyrics = false;
                }
            }
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
                        MusicPanel mp = controller.getMusicPanel();
                        mp.setCoverImage(createCircularCover(cover, IslandUiStyle.COVER_HIRES));
                        mp.repaintCover();
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

    private static Image downloadImageFromUrl(String urlStr) {
        try {
            return ImageIO.read(new URL(urlStr));
        } catch (Exception e) {
            AppLogger.warn("IslandWindow", "封面下载失败: " + e.getMessage());
        }
        return null;
    }

    /** 像素级正圆形裁剪 + 半透明描边的高分辨率封面 */
    private static Image createCircularCover(Image source, int hiResSize) {
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
        fg.setColor(new java.awt.Color(255, 255, 255, 35));
        fg.setStroke(new BasicStroke(1.5f));
        double inset = 1.0;
        fg.draw(new Ellipse2D.Double(inset, inset, hiResSize - inset * 2, hiResSize - inset * 2));
        fg.dispose();

        return scaled;
    }
}
