package com.island.music;

import com.island.music.model.LyricItem;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 汽水音乐歌词提供者 — qishui-api (localhost:3300)。
 *
 * <p>链路：{@code /search/mixed} 按歌名+歌手搜索取最佳曲目 track_id，
 * 再经 {@code /lyric?track_id=} 获取歌词（H5 SEO 源提取）。</p>
 * <p>歌词为 YRC 逐字格式：{@code [行起始ms,行时长ms]<字偏移,字时长,0>字...}，
 * 本类先将其转换为标准 LRC 再交由 {@link LyricsService#parseLrc} 解析。</p>
 * <p>端口说明：使用 qishui-api 默认端口 3300，QQMusicapi 已改让到 3301。</p>
 */
public final class QishuiLyricsProvider implements LyricsProvider {

    private static final String QISHUI_API = "http://localhost:3300";

    // YRC 行: [数字,数字]...
    private static final Pattern YRC_LINE = Pattern.compile("^\\[(\\d+),(\\d+)\\](.*)$");
    // YRC 逐字时间标记: <偏移,时长[,标记]>
    private static final Pattern YRC_WORD = Pattern.compile("<\\d+,\\d+(?:,\\d+)?>");

    @Override public String name() { return "汽水音乐"; }

    @Override
    public boolean supports(String sourceAppId) {
        if (sourceAppId == null) return false;
        String lower = sourceAppId.toLowerCase();
        // 实测汽水音乐 SMTC AUMID 为中文名"汽水音乐"，进程名 SodaMusic 亦兜底匹配
        return lower.contains("sodamusic") || lower.contains("qishui")
                || lower.contains("luna.music") || sourceAppId.contains("汽水音乐");
    }

    @Override
    public List<LyricItem> fetchLyrics(String title, String artist) {
        try {
            String item = bestSearchItem(title, artist);
            if (item.isEmpty()) return Collections.emptyList();
            String trackId = LyricsService.extractTopLevelField(item, "id");
            if (trackId.isEmpty()) {
                System.err.println("[Qishui] 搜索结果无 track id");
                return Collections.emptyList();
            }

            String lrc = fetchLrc(trackId);
            if (lrc.isEmpty()) return Collections.emptyList();

            // ── 关键：YRC 逐字格式 → LRC 格式转换 ──
            String converted = convertYrcToLrc(lrc);
            if (!converted.equals(lrc)) {
                System.out.println("[Qishui] YRC→LRC 已转换 (" + lrc.length() + "→" + converted.length() + "字符)");
            }

            List<LyricItem> items = LyricsService.parseLrc(converted);
            System.out.println("[Qishui] 歌词获取成功 (" + items.size() + "行)");
            return items;
        } catch (Exception e) {
            System.err.println("[Qishui] 异常: " + e);
            return Collections.emptyList();
        }
    }

    @Override
    public String fetchCoverUrl(String title, String artist) {
        try {
            String item = bestSearchItem(title, artist);
            if (item.isEmpty()) return "";
            String cover = LyricsService.extractJsonField(item, "cover_url");
            if (cover.isEmpty() || "null".equals(cover)) {
                cover = LyricsService.extractJsonField(item, "url_cover");
            }
            if (cover.isEmpty() || "null".equals(cover)) return "";
            System.out.println("[Qishui] 封面: " + cover);
            return cover;
        } catch (Exception e) {
            return "";
        }
    }

    // ── 搜索 ──

    /** /search/mixed 搜索并按歌名/歌手评分返回最佳曲目 item JSON，失败返回空串 */
    private static String bestSearchItem(String title, String artist) throws Exception {
        String keyword = title + " " + artist;
        String url = QISHUI_API + "/search/mixed?keywords="
                + LyricsService.urlEncode(keyword);
        System.out.println("[Qishui] 搜索: " + keyword);

        HttpResponse<String> resp = LyricsService.HTTP.send(
                HttpRequest.newBuilder().uri(URI.create(url))
                        .timeout(Duration.ofMillis(5000)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            System.err.println("[Qishui] 搜索HTTP " + resp.statusCode()
                    + " body=" + truncate(resp.body(), 400));
            return "";
        }
        String body = resp.body();
        String code = LyricsService.extractJsonField(body, "code");
        if (!"0".equals(code)) {
            String msg = LyricsService.extractJsonField(body, "message");
            System.err.println("[Qishui] 搜索业务错误 code=" + code + " msg=" + msg);
            return "";
        }
        String data = LyricsService.extractJsonField(body, "data");
        if (data.isEmpty()) return "";
        String best = bestTrack(data, title, artist);
        System.out.println("[Qishui] 搜索结果 "
                + (best.isEmpty() ? "(无匹配)" : "id="
                        + LyricsService.extractTopLevelField(best, "id")));
        return best;
    }

    /** 按歌名/歌手评分从 data.tracks 中选出最佳曲目 item JSON，未匹配返回空串 */
    private static String bestTrack(String data, String title, String artist) {
        String tl = norm(title);
        String al = norm(artist);
        String tracks = LyricsService.extractJsonField(data, "tracks");
        int arrStart = tracks.indexOf('[');
        if (tracks.isEmpty() || arrStart < 0) {
            System.err.println("[Qishui] 搜索响应无 tracks");
            return "";
        }

        String best = ""; int bestScore = 0; int pos = arrStart;
        while (pos < tracks.length()) {
            int s = tracks.indexOf('{', pos);
            if (s < 0) break;
            int e = LyricsService.findMatchingBrace(tracks, s);
            if (e < 0) break;
            String item = tracks.substring(s, e + 1);

            String id = LyricsService.extractTopLevelField(item, "id");
            String sn = LyricsService.extractTopLevelField(item, "name");
            if (sn.isEmpty()) sn = LyricsService.extractTopLevelField(item, "title");
            String sa = firstArtistName(item);

            if (!id.isEmpty() && !sn.isEmpty()) {
                String snl = norm(sn);
                String sal = norm(sa);
                int sc = 0;
                if (snl.equals(tl)) sc += 5;
                else if (snl.contains(tl) || tl.contains(snl)) sc += 3;
                if (!sal.isEmpty() && !al.isEmpty()) {
                    if (sal.equals(al)) sc += 5;
                    else if (sal.contains(al) || al.contains(sal)) sc += 2;
                }
                if (sc > bestScore) { bestScore = sc; best = item; }
            }
            pos = e + 1;
        }
        return best;
    }

    /** 提取 item.artists 数组首个歌手名（无则空串） */
    private static String firstArtistName(String item) {
        int idx = item.indexOf("\"artists\"");
        if (idx < 0) return "";
        int objStart = item.indexOf('{', idx);
        if (objStart < 0) return "";
        int objEnd = LyricsService.findMatchingBrace(item, objStart);
        if (objEnd < 0) return "";
        return LyricsService.extractTopLevelField(item.substring(objStart, objEnd + 1), "name");
    }

    // ── 歌词获取 ──

    private static String fetchLrc(String trackId) throws Exception {
        String url = QISHUI_API + "/lyric?track_id=" + trackId;
        System.out.println("[Qishui] 请求歌词 " + url);

        HttpResponse<String> resp = LyricsService.HTTP.send(
                HttpRequest.newBuilder().uri(URI.create(url))
                        .timeout(Duration.ofMillis(5000)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            System.err.println("[Qishui] 歌词HTTP " + resp.statusCode()
                    + " body=" + truncate(resp.body(), 400));
            return "";
        }
        String body = resp.body();
        String code = LyricsService.extractJsonField(body, "code");
        if (!"0".equals(code)) {
            String msg = LyricsService.extractJsonField(body, "message");
            System.err.println("[Qishui] 歌词业务错误 code=" + code + " msg=" + msg);
            return "";
        }
        String data = LyricsService.extractJsonField(body, "data");
        if (data.isEmpty()) {
            System.err.println("[Qishui] 歌词响应无 data 字段");
            return "";
        }

        // 标准形态：{lrc: "LRC文本", lines: [...], raw: {...}}
        String lrc = LyricsService.extractTopLevelField(data, "lrc");
        if (lrc.isEmpty() || "null".equals(lrc)) {
            // 兼容 song.lyrics 直返形态（可能是含 lyric/lrc 的对象）
            String lyricObj = LyricsService.extractJsonField(data, "lyric");
            if (!lyricObj.isEmpty()) {
                lrc = lyricObj.startsWith("{")
                        ? LyricsService.extractJsonField(lyricObj, "content")
                        : lyricObj;
            }
        }
        if (lrc.isEmpty() || "null".equals(lrc)) {
            System.err.println("[Qishui] lrc 为空, data前400字符: " + truncate(data, 400));
            return "";
        }
        return LyricsService.unescape(lrc);
    }

    // ═════════════════════════════════════════
    //  YRC → LRC 格式转换
    // ═════════════════════════════════════════

    /**
     * 将汽水音乐的 YRC 逐字歌词转为标准 LRC。
     * 行格式：{@code [行起始ms,行时长ms]<字偏移,字时长,0>字...}，
     * 取行起始时间作为行时间戳，去除逐字标记后拼接文本。
     * 非 YRC 文本（已是标准 LRC 等）原样返回。
     */
    static String convertYrcToLrc(String text) {
        if (text == null || text.isEmpty()) return text;
        // 无逐字标记 → 非 YRC，原样交给 parseLrc
        if (!YRC_WORD.matcher(text).find()) return text;

        StringBuilder lrc = new StringBuilder();
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            Matcher m = YRC_LINE.matcher(trimmed);
            if (m.matches()) {
                long startMs = Long.parseLong(m.group(1));
                String content = YRC_WORD.matcher(m.group(3)).replaceAll("").trim();
                if (content.isEmpty()) continue;

                // ms → [mm:ss.xx] (xx = 百分秒，两位)
                long totalSec = startMs / 1000;
                long min = totalSec / 60;
                long sec = totalSec % 60;
                long cs = (startMs % 1000) / 10;
                lrc.append(String.format("[%02d:%02d.%02d]%s\n", min, sec, cs, content));
            } else if (trimmed.startsWith("[")) {
                // 元数据行 [ti:...] 或已是标准 LRC 的行，保留
                lrc.append(trimmed).append('\n');
            }
        }
        return lrc.length() > 0 ? lrc.toString() : text;
    }

    // ── 工具 ──

    /** 归一化歌名/歌手用于评分比较 */
    private static String norm(String s) {
        return s.toLowerCase().replaceAll("[\\s()（）《》\"\"''·,，、]", "");
    }

    private static String truncate(String s, int max) {
        if (s == null) return "(null)";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "...(" + s.length() + "字符)";
    }
}
