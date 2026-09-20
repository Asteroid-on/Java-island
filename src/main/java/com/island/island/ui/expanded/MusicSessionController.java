package com.island.island.ui.expanded;

import com.island.island.ui.IslandUiStyle;
import com.island.music.LyricsService;
import com.island.music.model.LyricItem;
import com.island.music.model.MusicInfo;
import com.island.config.AppConstants;
import com.island.util.AppLogger;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URL;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 音乐会话状态机：曲目切换检测、歌词/封面异步获取、进度展示、
 * 音乐岛自动弹出与停止后 2 分钟自动收回决策。
 * 不直接操作 Swing 组件，UI 更新通过 MusicPanel 与 ExpandedIslandController 完成。
 * 所有状态访问与更新均在 EDT（异步获取线程仅通过 invokeLater 回写）。
 */
class MusicSessionController {

    private final ExpandedIslandController controller;
    private final transient LyricsService lyricsService = new LyricsService();
    /** SMTC 封面解码专用单线程执行器（daemon）：避免每次新封面都新建一条线程，
     *  也保证解码串行、不与会话切换竞态 */
    private final ExecutorService coverDecodeExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "SmtcCoverDecoder");
                t.setDaemon(true);
                return t;
            });

    private List<LyricItem> lrcLines = Collections.emptyList();
    private volatile MusicInfo currentMusicInfo = MusicInfo.EMPTY;
    private int currentLyricIndex = -1;
    private volatile boolean fetchingLyrics = false;
    /** 当前曲目歌词获取已结束但无结果（全部来源均未命中）：占位显示"暂无歌词"而非永远"加载中" */
    private volatile boolean lyricsFetchFailed = false;
    private volatile boolean fetchingCover = false;
    private volatile String lastFetchedTrackId = "";
    private volatile String lastFetchedCoverTrackId = "";
    /** SMTC Base64 封面成功应用对应的曲目标识（title|artist），用于 URL 源封面跳过判断 */
    private String smTcCoverAppliedTrackId = "";
    /**
     * 封面指纹（length+hashCode）而非整串 Base64：旧实现三份 MB 级巨串字段常驻，
     * 长期驻留 Old 区；指纹足以区分"同一张图/不同图"，比较逻辑不变。
     */
    private String lastCoverFp = "";
    /** 最近一次尝试解码的 SMTC 封面指纹：解码后无论是否应用都记录，防止每轮重复解码 */
    private String lastTriedCoverFp = "";
    /** 切歌前上一曲目的 SMTC 封面指纹：新曲目仍上报相同缩略图时判定为 daemon 旧图，不信任 */
    private String prevTrackCoverFp = "";

    /** 封面 Base64 指纹：长度+hashCode 组合，避免字段持有整串巨字符串 */
    private static String coverFingerprint(String b64) {
        return b64 == null || b64.isEmpty() ? "" : b64.length() + ":" + b64.hashCode();
    }
    /** 上一次处于严格播放状态的来源播放器标识，用于检测活跃播放器切换 */
    private String lastActiveSourceAppId = "";
    /** 已应用到面板的内容签名（歌名|艺术家|封面指纹）：无变化时跳过重复重设文本与整窗重绘 */
    private String lastPanelContentSig = "";
    // 歌词进度基于 daemon 汇报的 positionTicks；仅汽水音乐例外：
    // 其 SMTC 位置只在事件时刻更新（播放中静止、状态高频抖动），由本地估计器外推推进。
    // fallback 字段已废弃，wall-clock 自推进机制已移除（仅恢复为汽水专用形式）
    @Deprecated private long fallbackBaseMs = 0;
    @Deprecated private long fallbackStartMs = 0;
    private long lastDaemonEndTimeMs = 0;

    // ── 汽水音乐播放位置本地估计器 ──
    // 同步点：以 daemon 位置为锚，严格播放中按挂钟外推；暂停冻结；
    // daemon 位置与估计偏差超容差（拖动 seek/切歌）→ 立即重新同步。
    private static final long SODA_POS_SYNC_TOLERANCE_MS = 1500;
    private long sodaSyncPosMs = -1;     // 同步点位置（毫秒），-1=未同步
    private long sodaSyncWallMs = -1;    // 同步点系统时刻；-1=已冻结（暂停）
    private long sodaFrozenPosMs = -1;   // 暂停冻结位置（恢复播放时作为新锚）
    private String sodaSyncTrackId = "";
    private long sodaLastDaemonPosMs = -1; // daemon 上次汇报值：仅“daemon 自身跳变”才是真 seek，
                                           // 不能拿估计值与静态旧值比较（播放中旧值永远落后，会被误判为跳变反复拉回）

    MusicSessionController(ExpandedIslandController controller) {
        this.controller = controller;
    }

    /**
     * UI 是否处于展开/收起/切卡动画中。
     * 这些动画本身已逐帧重绘整扇离屏缓冲（封面一同被画），封面旋转定时器再发一次 repaint
     * 只会在同一帧造成两次全量重绘，两个 60FPS 定时器互抢 EDT → 观感为动画卡顿。
     */
    boolean isUiAnimationBusy() {
        return controller.isExpandingOrCollapsing() || controller.isSlideAnimating();
    }

    // ── 包级状态访问器（供 MusicPanel / ExpandedIslandController 读取） ──

    List<LyricItem> getLrcLines() {
        return lrcLines;
    }

    int getCurrentLyricIndex() {
        return currentLyricIndex;
    }

    /** 歌词获取是否进行中（供 MusicPanel 占位文案区分加载中/暂无歌词） */
    boolean isFetchingLyrics() {
        return fetchingLyrics;
    }

    /** 当前曲目歌词是否已确认获取失败（全部来源未命中） */
    boolean isLyricsFetchFailed() {
        return lyricsFetchFailed;
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

    /**
     * 是否满足音乐岛自动弹出条件（严格正在播放 + 播放器主窗口最小化/不可见）。
     * 供 ExpandedIslandController 复用，作为“因音乐而弹出/保持音乐岛”的唯一依据。
     */
    boolean shouldAutoPopupMusicIsland() {
        return currentMusicInfo != null && currentMusicInfo.canAutoPopupMusicIsland();
    }

    /**
     * 音乐岛是否应保持常驻（不因设备占用超时、停止收回计时、空闲收起而消失）：
     * 仅当扩展岛正在展示音乐面板且仍在播放，或已满足自动弹出条件时成立。
     * 旧实现只看“是否在播放”，导致播放器窗口仍可见时（不应弹出的场景）
     * 任何来源弹出的扩展岛也被音乐钉住不消失，等价于“只要播放就弹出且常驻”。
     */
    boolean shouldKeepMusicIslandResident() {
        if (currentMusicInfo == null || !currentMusicInfo.isStrictlyPlaying()) return false;
        return controller.isMusicPanelShown() || currentMusicInfo.canAutoPopupMusicIsland();
    }

    boolean hasSession() {
        return currentMusicInfo != null && currentMusicInfo.hasSession();
    }

    /** 是否有活跃媒体会话（有会话且曲目非空） */
    boolean hasActiveSession() {
        return currentMusicInfo != null && currentMusicInfo.hasActiveSession();
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
            lyricsFetchFailed = false;
            fetchingCover = false;
            resetSodaPositionEstimator();
            // 切歌：记录上一曲缩略图指纹用于旧图识别，清空旧封面确保与新曲目严格对应
            prevTrackCoverFp = lastCoverFp;
            lastCoverFp = "";
            lastTriedCoverFp = "";
            smTcCoverAppliedTrackId = "";
            lastPanelContentSig = "";
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
                lyricsFetchFailed = false;
                fetchingCover = false;
                resetSodaPositionEstimator();
                prevTrackCoverFp = lastCoverFp;
                lastCoverFp = "";
                lastTriedCoverFp = "";
                smTcCoverAppliedTrackId = "";
                lastPanelContentSig = "";
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
                && !coverFingerprint(info.getThumbnailBase64()).equals(lastCoverFp)) {
            updateMusicPanelContent();
        }

        // 媒体会话出现：占位面板 → 自动切换到音乐面板
        if (info.hasSession() && !wasSession && controller.isMusicPanelShown()
                && controller.isPlaceholderShown()) {
            if (AppConstants.DEBUG_CONSOLE) {
                System.out.println("[IslandWindow] 媒体会话出现，自动切换到音乐面板");
            }
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
     * 音乐岛自动弹出逻辑（严格受两个条件约束）：
     * <ol>
     *   <li>存在活跃媒体会话且播放状态严格为 Playing（暂停/停止/无会话不弹）；</li>
     *   <li>播放器主窗口处于最小化或不可见状态（窗口仍可见时不弹）。</li>
     * </ol>
     * 两者同时成立且扩展岛未显示音乐面板时，才主动弹出并展示音乐面板。
     * 播放期间的“常驻”（取消设备占用 5 秒自动隐藏与停止收回计时）只在
     * 扩展岛已在展示音乐面板（即音乐岛已弹出）或已满足上述弹出条件时维持，
     * 不因“单纯在播放”钉住其它方式弹出的扩展岛；手动显示与托盘操作不受影响。
     */
    private void updateMusicIslandAutoPopup(MusicInfo info) {
        if (!info.isStrictlyPlaying()) return;
        if (controller.isExpandingOrCollapsing()) return;
        boolean popupEligible = info.canAutoPopupMusicIsland();
        if (controller.isMusicPanelShown() || popupEligible) {
            // 音乐岛已弹出/即将弹出：播放期间扩展岛常驻，取消设备 5 秒自动隐藏与停止收回计时
            controller.cancelDeviceAutoHideTimer();
            controller.clearDeviceAutoExpanded();
            controller.cancelMusicStopAutoHideTimer();
        }
        if (!popupEligible) {
            // 播放器窗口仍可见（无会话/仅暂停已由上面的 isStrictlyPlaying 拦住）：不自动弹出
            if (AppConstants.DEBUG_CONSOLE) {
                System.out.println("[IslandWindow] 播放中但播放器窗口未最小化/不可见，不自动弹出音乐岛: "
                        + info.getSourceAppId());
            }
            return;
        }
        if (controller.isMusicPopupSuppressedByUser()) return;
        if (controller.isMusicPanelShown()) {
            controller.setMusicPanelAutoShownForSession(true);
            return;
        }
        if (!controller.isVisible()) {
            AppLogger.info("IslandWindow", "检测到正在播放且播放器已最小化，自动弹出音乐岛");
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
        updateMusicPanelContent(true);
    }

    /**
     * @param force false 时若内容签名（歌名/艺术家/封面）无变化，则只按播放位置推进歌词游标，
     *              不重复重设文本（含字体回退解析）也不触发封面重绘。
     *              面板重新挂载、以及 SMTC 封面异步解码未回来的窗口期内轮询再次进入时，
     *              内容其实未变：无条件重设与重绘会在展开/切卡动画中插进全量重绘帧。
     */
    void updateMusicPanelContent(boolean force) {
        MusicPanel mp = controller.getMusicPanel();
        if (!mp.isInitialized() || currentMusicInfo == null) return;
        String title = currentMusicInfo.getTitle();
        String artist = currentMusicInfo.getArtist();
        String fullTitle = title, fullArtist = artist;
        String b64 = currentMusicInfo.getThumbnailBase64();
        String sig = fullTitle + '|' + fullArtist + '|' + b64.length() + ':' + b64.hashCode();
        if (!force && sig.equals(lastPanelContentSig)) {
            if (!lrcLines.isEmpty()) updateProgressDisplay(currentMusicInfo);
            return;
        }
        lastPanelContentSig = sig;
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
        if (!b64.isEmpty()) {
            String fp = coverFingerprint(b64);
            if (fp.equals(prevTrackCoverFp)) {
                // 新曲目仍上报上一曲的缩略图 → 判定为 daemon 旧图，不信任，等待网络封面补位
                if (AppConstants.DEBUG_CONSOLE) {
                    System.out.println("[IslandWindow] SMTC缩略图与上一曲相同，判定为旧图，等待网络封面");
                }
                smTcCoverAppliedTrackId = "";
            } else if (fp.equals(lastTriedCoverFp)) {
                // 已提交解码/已应用：仅当该图确实已应用时标记，避免每轮重复解码
                smTcCoverAppliedTrackId = fp.equals(lastCoverFp) ? fullTitle + "|" + fullArtist : "";
            } else {
                lastTriedCoverFp = fp;
                smTcCoverAppliedTrackId = "";
                // 解码与超采样裁剪移出 EDT：旧实现用 MediaTracker.waitForID(0, 1000) 在 EDT 阻塞等图，
                // 时机恰与新封面到达 + 音乐岛弹出重合，直接吃掉展开/滑动动画的若干帧
                startSmtcCoverDecode(b64, fp, fullTitle + "|" + fullArtist);
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

    /**
     * SMTC Base64 封面后台解码 + 超采样圆形裁剪，完成后回 EDT 应用。
     *
     * <p>与旧版行为完全一致（同样优先 SMTC、低分辨率插值提升、失败时等 URL 补位），
     * 仅把 base64 解码、MediaTracker 等图与双三次插值从 EDT 搬走；
     * 应用前按指纹严格校验“仍是当前会话上报的图且未被判为旧图”，避免解码回来的旧图覆盖新图。</p>
     *
     * <p>旧实现每次 `new Thread` + `Toolkit.createImage` + `MediaTracker(new JLabel())`：
     * 隐形 Component 与 Toolkit 托管图片徒增堆外/注册表开销；改用 ImageIO 直接解码
     * 到 BufferedImage，任务投到专用单线程 daemon 执行器串行执行。</p>
     */
    private void startSmtcCoverDecode(String b64, String fp, String trackId) {
        coverDecodeExecutor.execute(() -> {
            Image circular = null;
            try {
                byte[] data = Base64.getDecoder().decode(b64);
                BufferedImage raw = ImageIO.read(new ByteArrayInputStream(data));
                if (raw != null && raw.getWidth() > 0) {
                    // 强制使用 SMTC 缩略图：低分辨率由 createCircularCover 双三次插值提升至 COVER_HIRES(144px)
                    if (raw.getWidth() < 200 && AppConstants.DEBUG_CONSOLE) {
                        System.out.println("[IslandWindow] SMTC缩略图分辨率较低(" + raw.getWidth() + "px)，已插值提升显示");
                    }
                    circular = createCircularCover(raw, IslandUiStyle.COVER_HIRES);
                }
            } catch (Exception ex) {
                // 解码失败：保留当前封面显示，标记保持未应用，让 URL 源补位，避免封面卡死
            }
            if (circular == null) return;
            final Image toApply = circular;
            SwingUtilities.invokeLater(() -> {
                MusicInfo info = currentMusicInfo;
                if (info == null || !fp.equals(coverFingerprint(info.getThumbnailBase64()))) return;
                if (fp.equals(prevTrackCoverFp) || fp.equals(lastCoverFp)) return;
                MusicPanel mp = controller.getMusicPanel();
                mp.setCoverImage(toApply);
                lastCoverFp = fp;
                smTcCoverAppliedTrackId = trackId;
                mp.repaintCover();
            });
        });
    }

    /** 关闭解码执行器（窗口销毁链路调用，避免销毁后仍有排队任务访问已失效面板） */
    void shutdown() {
        coverDecodeExecutor.shutdownNow();
    }

    /** 根据 daemon 汇报的 positionTicks 推进歌词游标（EDT） */
    void updateProgressDisplay(MusicInfo info) {
        MusicPanel mp = controller.getMusicPanel();
        if (!mp.isInitialized() || info == null || lrcLines.isEmpty()) return;
        long daemonPos = info.getPositionTicks() / 10_000;
        long pos = Math.max(effectivePositionMs(info, daemonPos), 0) + 900;  // 提前0.9秒显示歌词
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

    // ═════════════════════════════════════
    //  汽水音乐位置本地估计器（仅汽水生效，QQ音乐/网易云直接用 daemon 位置，零影响）
    // ═════════════════════════════════════

    /** 是否汽水音乐会话（与 QishuiLyricsProvider.supports 同规则） */
    private static boolean isSodaSource(MusicInfo info) {
        String src = info.getSourceAppId();
        if (src == null || src.isEmpty()) return false;
        String lower = src.toLowerCase();
        return lower.contains("sodamusic") || lower.contains("qishui")
                || lower.contains("luna.music") || src.contains("汽水音乐");
    }

    /** 切歌/切播放器/会话丢失时重置估计器 */
    void resetSodaPositionEstimator() {
        sodaSyncPosMs = -1;
        sodaSyncWallMs = -1;
        sodaFrozenPosMs = -1;
        sodaSyncTrackId = "";
        sodaLastDaemonPosMs = -1;
    }

    /**
     * 歌词游标用的有效播放位置（毫秒）：
     * 非汽水 → daemon 位置；汽水 → 同步点 + 挂钟外推，暂停冻结，
     * daemon 位置跳变（拖动 seek）超容差时立即重新同步。
     */
    private long effectivePositionMs(MusicInfo info, long daemonPosMs) {
        if (!isSodaSource(info)) return daemonPosMs;

        String trackId = info.getTitle() + "|" + info.getArtist();
        long now = System.currentTimeMillis();

        // 首次同步 / 切歌：以 daemon 位置为锚（未报位置时从 0 开始外推）
        if (sodaSyncPosMs < 0 || !trackId.equals(sodaSyncTrackId)) {
            sodaSyncTrackId = trackId;
            sodaSyncPosMs = Math.max(daemonPosMs, 0);
            sodaSyncWallMs = info.isStrictlyPlaying() ? now : -1;
            sodaFrozenPosMs = info.isStrictlyPlaying() ? -1 : sodaSyncPosMs;
            sodaLastDaemonPosMs = daemonPosMs;
            return sodaSyncPosMs;
        }

        // daemon 自身跳变检测：仅当汇报值相对上次变化超容差才是真 seek/缓冲跳变；
        // 播放中 daemon 静态旧值保持不变 → 不触发重新同步，外推得以持续累积。
        boolean daemonJumped = sodaLastDaemonPosMs >= 0 && daemonPosMs > 0
                && Math.abs(daemonPosMs - sodaLastDaemonPosMs) > SODA_POS_SYNC_TOLERANCE_MS;
        if (daemonPosMs > 0) sodaLastDaemonPosMs = daemonPosMs;

        if (info.isStrictlyPlaying()) {
            // 恢复播放：从冻结值重建锚点续推（若暂停期间有 seek，冻结值已是新位置）
            if (sodaSyncWallMs < 0) {
                sodaSyncPosMs = sodaFrozenPosMs > 0 ? sodaFrozenPosMs : sodaSyncPosMs;
                sodaSyncWallMs = now;
                sodaFrozenPosMs = -1;
            }
            long estimated = sodaSyncPosMs + (now - sodaSyncWallMs);
            // daemon 值跳变（拖动进度条）→ 立即重新同步，游标跳到新位置并继续外推。
            if (daemonJumped) {
                if (AppConstants.DEBUG_CONSOLE) {
                    System.out.println("[LyricProgress] 汽水位置重新同步: daemon=" + daemonPosMs
                            + "ms estimated=" + estimated + "ms");
                }
                sodaSyncPosMs = daemonPosMs;
                sodaSyncWallMs = now;
                return daemonPosMs;
            }
            return estimated;
        }

        // 暂停/非严格播放：冻结当前估计值（游标停在暂停行）；
        // daemon 跳变（暂停中拖动）时接受新值。
        long frozen = sodaSyncWallMs > 0
                ? sodaSyncPosMs + (now - sodaSyncWallMs)
                : (sodaFrozenPosMs > 0 ? sodaFrozenPosMs : sodaSyncPosMs);
        if (daemonJumped) {
            frozen = daemonPosMs;
        }
        sodaFrozenPosMs = frozen;
        sodaSyncWallMs = -1;
        return frozen;
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
        if (AppConstants.DEBUG_CONSOLE) {
            System.out.println("[IslandWindow] 开始异步获取歌词: " + title + " - " + artist + " src=" + srcAppId);
        }
        new Thread(() -> {
            try {
                List<LyricItem> lines = lyricsService.getLyrics(title, artist, srcAppId);
                if (AppConstants.DEBUG_CONSOLE) {
                    System.out.println("[IslandWindow] 歌词获取结果: " + lines.size() + " 行");
                }
                if (!lines.isEmpty()) {
                    SwingUtilities.invokeLater(() -> {
                        // stale-track 校验：歌词只属于发起请求时的曲目
                        String currentTrackId = currentMusicInfo.getTitle() + "|" + currentMusicInfo.getArtist();
                        if (!trackId.equals(currentTrackId)) {
                            if (AppConstants.DEBUG_CONSOLE) {
                                System.out.println("[IslandWindow] 歌词已过期（曲目已切换），丢弃");
                            }
                            return;
                        }
                        lrcLines = lines;
                        currentLyricIndex = -1;
                        if (AppConstants.DEBUG_CONSOLE) {
                            System.out.println("[LyricProgress] 歌词异步加载完成: " + lines.size() + " 行");
                        }
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
                } else {
                    // 全部来源未命中：标记失败，占位文案从"歌词加载中..."切为"暂无歌词"
                    SwingUtilities.invokeLater(() -> {
                        String currentTrackId = currentMusicInfo.getTitle() + "|" + currentMusicInfo.getArtist();
                        if (trackId.equals(currentTrackId)) {
                            lyricsFetchFailed = true;
                            MusicPanel mp = controller.getMusicPanel();
                            if (mp.isInitialized()) mp.repaintLyrics();
                        }
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
            if (AppConstants.DEBUG_CONSOLE) {
                System.out.println("[IslandWindow] 封面获取已在进行中，跳过重复请求");
            }
            return;
        }
        fetchingCover = true;
        final String trackId = title + "|" + artist;
        lastFetchedCoverTrackId = trackId;
        final String srcAppId = currentMusicInfo.getSourceAppId();
        if (AppConstants.DEBUG_CONSOLE) {
            System.out.println("[IslandWindow] 开始异步获取封面: " + title + " - " + artist
                    + " src=" + srcAppId);
        }
        new Thread(() -> {
            try {
                String url = lyricsService.fetchCoverUrl(title, artist, srcAppId);
                if (!url.isEmpty()) {
                    if (AppConstants.DEBUG_CONSOLE) {
                        System.out.println("[IslandWindow] 封面URL: " + url);
                    }
                    Image cover = downloadImageFromUrl(url);
                    if (cover != null) {
                        // 超采样圆形裁剪在后台线程完成（与旧版同参数同结果），EDT 只做赋值与重绘
                        final Image circular = createCircularCover(cover, IslandUiStyle.COVER_HIRES);
                        SwingUtilities.invokeLater(() -> {
                            // stale-track 校验：封面只属于发起请求时的曲目
                            String currentTrackId = currentMusicInfo.getTitle() + "|" + currentMusicInfo.getArtist();
                            if (!trackId.equals(currentTrackId)) {
                                if (AppConstants.DEBUG_CONSOLE) {
                                    System.out.println("[IslandWindow] 封面已过期（曲目已切换），丢弃");
                                }
                                return;
                            }
                            // SMTC 缩略图优先：当前曲目已有 SMTC 缩略图时立即尝试应用，
                            // 应用成功则跳过 URL 结果；解码失败则仍用 URL 结果补位，防止封面卡死
                            if (!currentMusicInfo.getThumbnailBase64().isEmpty()) {
                                updateMusicPanelContent();
                                if (currentTrackId.equals(smTcCoverAppliedTrackId)) {
                                    if (AppConstants.DEBUG_CONSOLE) {
                                        System.out.println("[IslandWindow] SMTC封面已应用，跳过URL封面");
                                    }
                                    return;
                                }
                            }
                            MusicPanel mp = controller.getMusicPanel();
                            mp.setCoverImage(circular);
                            mp.repaintCover();
                        });
                    }
                } else {
                    if (AppConstants.DEBUG_CONSOLE) {
                        System.out.println("[IslandWindow] 封面获取失败（无结果）");
                    }
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
