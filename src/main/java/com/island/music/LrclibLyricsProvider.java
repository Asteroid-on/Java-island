package com.island.music;

import com.island.music.model.LyricItem;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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
        return lines.isEmpty() ? search(title, artist) : lines;
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

    private List<LyricItem> extract(String body) {
        String lrc = LyricsService.extractJsonField(body, "syncedLyrics");
        if (lrc.isEmpty()) lrc = LyricsService.extractJsonField(body, "plainLyrics");
        return lrc.isEmpty() ? Collections.emptyList() : LyricsService.parseLrc(lrc);
    }
}
