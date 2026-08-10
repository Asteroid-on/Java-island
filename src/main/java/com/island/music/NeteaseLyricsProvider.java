package com.island.music;

import com.island.music.model.LyricItem;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.List;

/**
 * 网易云音乐歌词提供者 — ncm-server (/lyric/new → yrc, /lyric → lrc)。
 */
public final class NeteaseLyricsProvider implements LyricsProvider {

    private static final String NCM = "http://localhost:3000";

    @Override public String name() { return "网易云"; }

    @Override
    public boolean supports(String sourceAppId) {
        if (sourceAppId == null) return false;
        String lower = sourceAppId.toLowerCase();
        return lower.contains("cloudmusic") || lower.contains("netease");
    }

    @Override
    public List<LyricItem> fetchLyrics(String title, String artist) {
        try {
            String songId = search(title, artist);
            if (songId.isEmpty()) return Collections.emptyList();

            // /lyric/new 含 lrc + yrc，直接用 lrc（标准 LRC 格式，parseLrc 可正确解析）
            // yrc 是逐字时间格式 [123,456](0,120)字...，不可被 parseLrc 解析
            String lrc = lyricField(songId, "/lyric/new", "lrc");
            if (!lrc.isEmpty()) {
                System.out.println("[Netease] /lyric/new lrc (" + lrc.length() + "字符)");
                return LyricsService.parseLrc(lrc);
            }
            // fallback to /lyric
            lrc = lyricField(songId, "/lyric", "lrc");
            if (!lrc.isEmpty()) {
                System.out.println("[Netease] /lyric lrc (" + lrc.length() + "字符)");
                return LyricsService.parseLrc(lrc);
            }
            return Collections.emptyList();
        } catch (Exception e) {
            System.err.println("[Netease] " + e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override public String fetchCoverUrl(String title, String artist) { return ""; }

    // ── 内部 ──

    private static String search(String title, String artist) throws Exception {
        String url = NCM + "/cloudsearch?keywords="
                + LyricsService.urlEncode(title + " " + artist) + "&type=1&limit=5";
        HttpResponse<String> resp = LyricsService.HTTP.send(
                HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofMillis(3000)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) return "";
        return extractSongId(resp.body(), title, artist);
    }

    private static String lyricField(String songId, String endpoint, String field) throws Exception {
        HttpResponse<String> resp = LyricsService.HTTP.send(
                HttpRequest.newBuilder().uri(URI.create(NCM + endpoint + "?id=" + songId))
                        .timeout(Duration.ofMillis(3000)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) return "";
        String obj = LyricsService.extractJsonField(resp.body(), field);
        if (obj.isEmpty()) return "";
        String text = LyricsService.extractJsonField(obj, "lyric");
        return (!text.isEmpty() && !"null".equals(text)) ? text : "";
    }

    private static String extractSongId(String json, String title, String artist) {
        String tl = title.toLowerCase().replaceAll("[\\s()（）《》\"\"''·]", "");
        String al = artist.toLowerCase().replaceAll("[\\s()（）《》\"\"''·]", "");
        int songsIdx = json.indexOf("\"songs\"");
        if (songsIdx < 0) return "";

        String bestId = ""; int bestScore = 0; int pos = songsIdx;
        while (pos < json.length()) {
            int s = json.indexOf('{', pos); if (s < 0 || s > songsIdx + 5000) break;
            int e = LyricsService.findMatchingBrace(json, s); if (e < 0) break;
            String item = json.substring(s, e + 1);

            String sid = LyricsService.extractTopLevelField(item, "id");
            String sn = LyricsService.extractTopLevelField(item, "name");
            int ar = item.indexOf("\"ar\"");
            String sa = ar >= 0 ? LyricsService.extractJsonField(item.substring(ar), "name") : "";

            if (!sid.isEmpty() && !sn.isEmpty()) {
                String snl = sn.toLowerCase().replaceAll("[\\s()（）《》\"\"''·]", "");
                String sal = sa.toLowerCase().replaceAll("[\\s()（）《》\"\"''·]", "");
                int sc = 0;
                if (snl.equals(tl)) sc += 5; else if (snl.contains(tl) || tl.contains(snl)) sc += 3;
                if (sal.equals(al)) sc += 5; else if (sal.contains(al) || al.contains(sal)) sc += 2;
                if (sc > bestScore) { bestScore = sc; bestId = sid; }
            }
            pos = e + 1;
        }
        return bestId;
    }
}
