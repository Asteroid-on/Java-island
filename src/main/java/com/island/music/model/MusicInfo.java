package com.island.music.model;

import java.util.Objects;

/**
 * 音乐媒体信息模型 — 对应 MediaInfoDaemon 输出的 JSON 结构。
 */
public final class MusicInfo {

    /** 无会话时的空实例 */
    public static final MusicInfo EMPTY = new Builder().build();

    private final boolean hasSession;
    private final boolean hasMusicProcess;
    private final String title;
    private final String artist;
    private final String album;
    private final String playbackStatus;
    private final long positionTicks;
    private final long endTimeTicks;
    private final String sourceAppId;
    private final String thumbnailBase64;

    private MusicInfo(Builder builder) {
        this.hasSession = builder.hasSession;
        this.hasMusicProcess = builder.hasMusicProcess;
        this.title = builder.title;
        this.artist = builder.artist;
        this.album = builder.album;
        this.playbackStatus = builder.playbackStatus;
        this.positionTicks = builder.positionTicks;
        this.endTimeTicks = builder.endTimeTicks;
        this.sourceAppId = builder.sourceAppId;
        this.thumbnailBase64 = builder.thumbnailBase64;
    }

    public boolean hasSession() { return hasSession; }
    public boolean hasMusicProcess() { return hasMusicProcess; }
    public String getTitle() { return title; }
    public String getArtist() { return artist; }
    public String getAlbum() { return album; }
    public String getPlaybackStatus() { return playbackStatus; }
    public long getPositionTicks() { return positionTicks; }
    public long getEndTimeTicks() { return endTimeTicks; }
    public String getSourceAppId() { return sourceAppId; }
    public String getThumbnailBase64() { return thumbnailBase64; }

    /** 是否有活跃媒体会话（含 Paused） */
    public boolean isPlaying() {
        return hasSession && ("Playing".equalsIgnoreCase(playbackStatus)
                           || "Paused".equalsIgnoreCase(playbackStatus));
    }

    /** 是否严格在播放（仅 status=Playing，pause 不算；用于控制封面旋转等动画） */
    public boolean isStrictlyPlaying() {
        return hasSession && "Playing".equalsIgnoreCase(playbackStatus);
    }

    /** 是否同一首歌（去重用，含来源播放器标识） */
    public boolean isSameSong(MusicInfo other) {
        if (other == null) return false;
        return Objects.equals(title, other.title)
                && Objects.equals(artist, other.artist)
                && Objects.equals(playbackStatus, other.playbackStatus)
                && Objects.equals(sourceAppId, other.sourceAppId);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private boolean hasSession;
        private boolean hasMusicProcess;
        private String title = "";
        private String artist = "";
        private String album = "";
        private String playbackStatus = "Closed";
        private long positionTicks;
        private long endTimeTicks;
        private String sourceAppId = "";
        private String thumbnailBase64 = "";

        public Builder hasSession(boolean v) { hasSession = v; return this; }
        public Builder hasMusicProcess(boolean v) { hasMusicProcess = v; return this; }
        public Builder title(String v) { title = v != null ? v : ""; return this; }
        public Builder artist(String v) { artist = v != null ? v : ""; return this; }
        public Builder album(String v) { album = v != null ? v : ""; return this; }
        public Builder playbackStatus(String v) { playbackStatus = v != null ? v : "Closed"; return this; }
        public Builder positionTicks(long v) { positionTicks = v; return this; }
        public Builder endTimeTicks(long v) { endTimeTicks = v; return this; }
        public Builder sourceAppId(String v) { sourceAppId = v != null ? v : ""; return this; }
        public Builder thumbnailBase64(String v) { thumbnailBase64 = v != null ? v : ""; return this; }

        public MusicInfo build() {
            return new MusicInfo(this);
        }
    }

    @Override
    public String toString() {
        return "MusicInfo{title='" + title + "', artist='" + artist + "', status=" + playbackStatus + "}";
    }
}
