package com.island.music;

import com.island.music.model.LyricItem;

import java.util.List;

/**
 * 歌词 + 封面提供者接口。
 * 每种音乐平台实现自己的搜索与提取逻辑。
 */
public interface LyricsProvider {

    /** 根据歌名/歌手搜索歌词 */
    List<LyricItem> fetchLyrics(String title, String artist);

    /** 根据歌名/歌手获取封面 URL，失败返回空串 */
    String fetchCoverUrl(String title, String artist);

    /** 是否匹配给定的 SMTC sourceAppId */
    boolean supports(String sourceAppId);

    /** Provider 标识名（日志用） */
    String name();
}
