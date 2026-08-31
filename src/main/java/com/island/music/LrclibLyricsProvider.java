package com.island.music;

import com.island.music.model.LyricItem;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * LRCLIB 歌词兜底 Provider — 不绑定任何平台 sourceAppId。
 */
public final class LrclibLyricsProvider implements LyricsProvider {

    @Override public String name() { return "LRCLIB"; }

    @Override public boolean supports(String sourceAppId) { return false; }

    @Override
    public List<LyricItem> fetchLyrics(String title, String artist) {
        List<LyricItem> lines = get(title, artist);
        if (!lines.isEmpty()) return lines;
        // 多歌手串（"尹未来, Bizzy, Tiger JK"）全量查询命中率低，逐级截断重试：
        // 完整串 → 前两位 → 仅首位（实测首艺术家即可命中，组合名/别名由评分兼容）
        List<LyricItem> bySearch = search(title, artist);
        if (bySearch.isEmpty()) {
            for (String reduced : reducedArtists(artist)) {
                bySearch = search(title, reduced);
                if (!bySearch.isEmpty()) break;
            }
        }
        return bySearch;
    }

    /** 逗号分隔的多歌手逐级截断（不含自身与空结果），如 "a, b, c" → ["a, b", "a"] */
    private static List<String> reducedArtists(String artist) {
        List<String> out = new ArrayList<>();
        String[] parts = artist.split(",");
        if (parts.length < 2) return out;
        for (int n = parts.length - 1; n >= 1; n--) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < n; i++) {
                if (i > 0) sb.append(", ");
                sb.append(parts[i].trim());
            }
            out.add(sb.toString());
        }
        return out;
    }

    @Override public String fetchCoverUrl(String title, String artist) { return ""; }

    // ── GET /api/get ──

    private List<LyricItem> get(String title, String artist) {
        try {
            String body = "artist_name=" + LyricsService.urlEncode(artist)
                    + "&track_name=" + LyricsService.urlEncode(title);
            HttpResponse<String> resp = LyricsService.HTTP.send(
                    HttpRequest.newBuilder().uri(URI.create("https://lrclib.net/api/get"))
                            .timeout(Duration.ofMillis(5000))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200 || resp.body().isEmpty()) return Collections.emptyList();
            return extract(resp.body());
        } catch (Exception e) { return Collections.emptyList(); }
    }

    // ── GET /api/search ──

    private List<LyricItem> search(String title, String artist) {
        try {
            String url = "https://lrclib.net/api/search?q="
                    + LyricsService.urlEncode(title + " " + artist);
            HttpResponse<String> resp = LyricsService.HTTP.send(
                    HttpRequest.newBuilder().uri(URI.create(url))
                            .timeout(Duration.ofMillis(5000)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200 || resp.body().isEmpty()) return Collections.emptyList();

            String body = resp.body().trim();
            if (!body.startsWith("[")) return Collections.emptyList();

            String tl = title.toLowerCase().replaceAll("[\\s()（）《》\"\"'']", "");
            String al = artist.toLowerCase().replaceAll("[\\s()（）《》\"\"'']", "");

            String bestRaw = null;
            int bestScore = 0, pos = 1;
            while (pos < body.length()) {
                int s = body.indexOf('{', pos); if (s < 0) break;
                int e = LyricsService.findMatchingBrace(body, s); if (e < 0) break;
                String item = body.substring(s, e + 1);

                String tn = LyricsService.extractJsonField(item, "trackName");
                String an = LyricsService.extractJsonField(item, "artistName");
                if (!tn.isEmpty()) {
                    String tnl = tn.toLowerCase().replaceAll("[\\s()（）《》\"\"'']", "");
                    String anl = an.toLowerCase().replaceAll("[\\s()（）《》\"\"'']", "");
                    int score = 0;
                    if (tnl.equals(tl)) score += 10;
                    else if (tnl.contains(tl) || tl.contains(tnl)) score += 5;
                    if (!anl.isEmpty()) {
                        if (anl.equals(al)) score += 10;
                        else if (anl.contains(al) || al.contains(anl)) score += 5;
                        else if (artistTokensOverlap(al, anl)) score += 4;
                    } else score += 2;
                    if (score > bestScore) {
                        String synced = LyricsService.extractJsonField(item, "syncedLyrics");
                        if (!synced.isEmpty()) { bestScore = score; bestRaw = synced; }
                    }
                }
                pos = e + 1;
            }
            return bestRaw != null ? LyricsService.parseLrc(bestRaw) : Collections.emptyList();
        } catch (Exception e) { return Collections.emptyList(); }
    }

    /**
     * 多歌手部分匹配：两侧歌手串按逗号/斜杠/& 拆分为个体，任一对存在包含关系即命中。
     * 应对库侧用组合名/别名登记（如 MFBTY(尹未来,타이거JK,Bizzy) vs 上报的 "尹未来, Bizzy, Tiger JK"），
     * 括号别名部分一并参与匹配。为避免"jk"类短词误匹配，个体长度至少 3 才参与比较。
     */
    private static boolean artistTokensOverlap(String al, String anl) {
        String[] mine = al.split("[,/＆&]+");
        String[] theirs = anl.split("[,/＆&]+");
        for (String a : mine) {
            String at = a.replaceAll("[\\s()]", "");
            if (at.length() < 3) continue;
            for (String b : theirs) {
                String bt = b.replaceAll("[\\s()]", "");
                if (bt.length() < 3) continue;
                if (at.contains(bt) || bt.contains(at)) return true;
            }
        }
        return false;
    }

    private List<LyricItem> extract(String body) {
        String lrc = LyricsService.extractJsonField(body, "syncedLyrics");
        if (lrc.isEmpty()) lrc = LyricsService.extractJsonField(body, "plainLyrics");
        return lrc.isEmpty() ? Collections.emptyList() : LyricsService.parseLrc(lrc);
    }
}
