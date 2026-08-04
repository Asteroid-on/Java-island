package com.island.music;

import com.island.monitor.AbstractPollingMonitor;
import com.island.music.model.MusicInfo;

import java.util.concurrent.TimeUnit;

/**
 * 音乐播放器检测轮询监控器。
 *
 * <p>每 300ms 读取一次 {@code MediaInfoDaemon} 输出的文件，
 * 当媒体信息或播放状态变化时回调监听器。</p>
 */
public final class MusicMonitor extends AbstractPollingMonitor {

    private static final long POLL_INTERVAL_MS = 300;

    private MusicListener listener;
    private volatile MusicInfo lastMusicInfo = MusicInfo.EMPTY;

    public MusicMonitor() {
        super("MusicMonitor", POLL_INTERVAL_MS, TimeUnit.MILLISECONDS, false);
    }

    public void setListener(MusicListener listener) {
        this.listener = listener;
    }

    @Override
    protected void poll() {
        MusicInfo current = WindowsMediaManager.queryMediaInfo();

        // 活跃会话（Playing 或 Paused）→ 始终触发。
        // 每次触发都会通过 updateMusicInfo 重置 lastMusicSnapshotTicks/Time，
        // 这样 seek 后 daemon 推送的新位置会自然覆盖旧基准，歌词立即跳转。
        if (current.hasSession() && current.isPlaying()) {
            lastMusicInfo = current;
            fireUpdate(current);
            return;
        }

        // 无活跃会话 → 仅在状态变化时触发（去重优化）
        if (current.hasSession() == lastMusicInfo.hasSession()
                && current.isSameSong(lastMusicInfo)) {
            return;
        }

        lastMusicInfo = current;
        fireUpdate(current);
    }

    private void fireUpdate(MusicInfo info) {
        if (listener != null) {
            try {
                listener.onMusicInfoChanged(info);
            } catch (Exception e) {
                logError("回调监听器异常: " + e.getMessage());
            }
        }
    }

    /** 音乐信息变化监听器 */
    @FunctionalInterface
    public interface MusicListener {
        /** 当媒体信息发生变化时回调（在守护线程中调用，需自行切换到 EDT） */
        void onMusicInfoChanged(MusicInfo info);
    }
}
