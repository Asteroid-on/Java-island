package com.island.music.model;

/**
 * 单行歌词实体：开始时间（毫秒）+ 文本内容。
 */
public final class LyricItem {
    public final long startTime;  // 毫秒
    public final String content;

    public LyricItem(long startTime, String content) {
        this.startTime = startTime;
        this.content = content;
    }

    @Override
    public String toString() {
        return "[" + startTime + "] " + content;
    }
}
