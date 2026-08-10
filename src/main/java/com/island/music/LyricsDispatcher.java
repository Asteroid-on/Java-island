package com.island.music;

import com.island.music.model.LyricItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 歌词统一调度器：注册所有 Provider，按 sourceAppId 路由，LRCLIB 兜底。
 *
 * <pre>
 * 调度策略：
 *   匹配 Provider → 成功返回
 *                → 失败 → LRCLIB 兜底
 *   无匹配 Provider → 遍历所有平台 → LRCLIB 兜底
 * </pre>
 */
public final class LyricsDispatcher {

    private final List<LyricsProvider> providers = new ArrayList<>();
    private final LyricsProvider lrclib;

    public LyricsDispatcher() {
        this.lrclib = new LrclibLyricsProvider();
    }

    /** 注册平台 Provider（调用顺序决定未知平台时的遍历优先级） */
    public LyricsDispatcher register(LyricsProvider provider) {
        providers.add(provider);
        return this;
    }

    // ═══════════════════════════════════════════
    //  歌词调度
    // ═══════════════════════════════════════════

    /**
     * 根据 sourceAppId 调度歌词获取。
     *
     * @return 歌词行列表，获取失败返回空列表
     */
    public List<LyricItem> dispatchLyrics(String title, String artist, String sourceAppId) {
        if (title == null || artist == null || title.isEmpty()) {
            return Collections.emptyList();
        }

        // 1. 匹配到 Provider → 优先使用
        LyricsProvider matched = find(sourceAppId);
        if (matched != null) {
            System.out.println("[Dispatch] 匹配 " + matched.name() + " → 获取歌词");
            List<LyricItem> lines = matched.fetchLyrics(title, artist);
            if (!lines.isEmpty()) {
                System.out.println("[Dispatch] " + matched.name() + " ✅ " + lines.size() + "行");
                return lines;
            }
            System.out.println("[Dispatch] " + matched.name() + " 失败 → LRCLIB 兜底");
            return lrclib.fetchLyrics(title, artist);
        }

        // 2. 未知平台 → 遍历所有注册的 Provider
        System.out.println("[Dispatch] 未知平台 → 遍历 " + providers.size() + " 个 Provider");
        for (LyricsProvider p : providers) {
            List<LyricItem> lines = p.fetchLyrics(title, artist);
            if (!lines.isEmpty()) {
                System.out.println("[Dispatch] " + p.name() + " ✅ " + lines.size() + "行");
                return lines;
            }
        }
        // 3. 全部失败 → LRCLIB
        System.out.println("[Dispatch] 所有 Provider 失败 → LRCLIB 兜底");
        return lrclib.fetchLyrics(title, artist);
    }

    // ═══════════════════════════════════════════
    //  封面调度
    // ═══════════════════════════════════════════

    /**
     * 根据 sourceAppId 调度封面获取。
     * 平台有封面API的优先使用，否则返回空串让调用方降级到 iTunes。
     *
     * @return 封面 URL，获取失败返回空串
     */
    public String dispatchCoverUrl(String title, String artist, String sourceAppId) {
        if (title == null || artist == null || title.isEmpty()) return "";

        LyricsProvider matched = find(sourceAppId);
        if (matched != null) {
            String url = matched.fetchCoverUrl(title, artist);
            if (!url.isEmpty()) {
                System.out.println("[Dispatch] " + matched.name() + " 封面: " + url);
                return url;
            }
        }
        return "";
    }

    // ── 内部 ──

    private LyricsProvider find(String sourceAppId) {
        if (sourceAppId == null || sourceAppId.isEmpty()) return null;
        for (LyricsProvider p : providers) {
            if (p.supports(sourceAppId)) return p;
        }
        return null;
    }
}
