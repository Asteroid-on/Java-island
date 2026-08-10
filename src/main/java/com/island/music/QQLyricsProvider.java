package com.island.music;

import com.island.music.model.LyricItem;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * QQ音乐歌词提供者 — QQMusicapi (localhost:3300)。
 *
 * <p>QQ 音乐歌词使用 QRC（逐字歌词）格式，解密后为紧凑文本：
 * {@code [startMs,durationMs]歌词文本(wordStart,wordDur)...}
 * 本类负责将其转换为标准 LRC 格式后再交由 {@link LyricsService#parseLrc} 解析。</p>
 */
public final class QQLyricsProvider implements LyricsProvider {

    private static final String QQ_API = "http://localhost:3300";

    // 紧凑 QRC 行: [数字,数字]...
    private static final Pattern QRC_LINE = Pattern.compile("^\\[(\\d+),(\\d+)\\](.*)$");
    // 逐字时间: (数字,数字)
    private static final Pattern WORD_TIME = Pattern.compile("\\(\\d+,\\d+\\)");
    // XML QRC 中的 LyricContent 属性
    private static final Pattern XML_LYRIC_CONTENT =
            Pattern.compile("LyricContent\\s*=\\s*\"([^\"]*)\"", Pattern.DOTALL);

    @Override public String name() { return "QQ音乐"; }

    @Override
    public boolean supports(String sourceAppId) {
        if (sourceAppId == null) return false;
        return sourceAppId.toLowerCase().contains("qqmusic");
    }

    @Override
    public List<LyricItem> fetchLyrics(String title, String artist) {
        try {
            String songMid = search(title, artist);
            if (songMid.isEmpty()) return Collections.emptyList();

            String lrc = fetchLrc(songMid);
            if (lrc.isEmpty()) return Collections.emptyList();

            // ── 关键：QRC → LRC 格式转换 ──
            System.out.println("[QQMusic] 原始歌词前500字符: " + truncate(lrc, 500));
            String converted = convertQrcToLrc(lrc);
            if (!converted.equals(lrc)) {
                System.out.println("[QQMusic] QRC→LRC 已转换, 转换后前500字符: " + truncate(converted, 500));
            }

            List<LyricItem> items = LyricsService.parseLrc(converted);
            System.out.println("[QQMusic] parseLrc 结果: " + items.size() + "行 (原始="
                    + lrc.length() + "字符)");

            if (!items.isEmpty()) {
                System.out.println("[QQMusic] 歌词获取成功 (" + items.size() + "行)");
            } else {
                System.err.println("[QQMusic] parseLrc 返回空! 转换后全文: " + truncate(converted, 800));
            }
            return items;
        } catch (Exception e) {
            System.err.println("[QQMusic] 异常: " + e);
            e.printStackTrace();
            return Collections.emptyList();
        }
    }

    @Override public String fetchCoverUrl(String title, String artist) { return ""; }

    // ── 搜索 ──

    private static String search(String title, String artist) throws Exception {
        String keyword = title + " " + artist;
        String body = "{\"keyword\":\"" + LyricsService.escJson(keyword)
                + "\",\"type\":0,\"num\":5}";
        System.out.println("[QQMusic] 搜索: " + keyword);

        HttpResponse<String> resp = LyricsService.HTTP.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(QQ_API + "/search/byType"))
                        .timeout(Duration.ofMillis(5000))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        if (resp.statusCode() != 200) {
            System.err.println("[QQMusic] 搜索HTTP " + resp.statusCode()
                    + " body=" + truncate(resp.body(), 400));
            return "";
        }
        String mid = extractSongMid(resp.body(), title, artist);
        System.out.println("[QQMusic] 搜索结果 mid=" + (mid.isEmpty() ? "(空)" : mid));
        return mid;
    }

    // ── 歌词获取 ──

    private static String fetchLrc(String songMid) throws Exception {
        String url = QQ_API + "/song/lyric?mid=" + songMid + "&decode=1";
        System.out.println("[QQMusic] 请求歌词 " + url);

        HttpResponse<String> resp = LyricsService.HTTP.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofMillis(4000))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        if (resp.statusCode() != 200) {
            System.err.println("[QQMusic] 歌词HTTP " + resp.statusCode()
                    + " body=" + truncate(resp.body(), 400));
            return "";
        }

        String respBody = resp.body();
        // 检查外层 code
        String outerCode = LyricsService.extractJsonField(respBody, "code");
        if (!"0".equals(outerCode)) {
            String msg = LyricsService.extractJsonField(respBody, "message");
            System.err.println("[QQMusic] 歌词业务错误 code=" + outerCode
                    + " msg=" + msg);
            return "";
        }

        // 提取 data 对象
        String dataObj = LyricsService.extractJsonField(respBody, "data");
        if (dataObj.isEmpty()) {
            System.err.println("[QQMusic] 歌词响应无 data 字段");
            return "";
        }

        // 检查解码错误（顶层字段，extractJsonField 可安全使用）
        String decodeErr = LyricsService.extractJsonField(dataObj, "decodeError");
        if (!decodeErr.isEmpty()) {
            System.err.println("[QQMusic] QRC解码失败(服务器侧): " + decodeErr);
        }

        // ★ 关键修复: 用 extractTopLevelField 而非 extractJsonField
        //    服务器响应 data = {"raw":{"lyric":"HEX密文"},"lyric":"解密后QRC文本"}
        //    extractJsonField 用 indexOf 会先命中 raw 内的 hex 密文
        //    extractTopLevelField 只在 depth==1 匹配，正确取到顶层解密文本
        String lyric = LyricsService.extractTopLevelField(dataObj, "lyric");
        if (!lyric.isEmpty() && !"null".equals(lyric)) {
            // 安全检查: 如果取到的仍是纯hex密文，说明服务器解密确实失败了
            if (lyric.matches("^[0-9A-Fa-f]{100,}$")) {
                System.err.println("[QQMusic] lyric 为未解密的hex密文(" + lyric.length() + "字符), 服务器解密失败");
                System.err.println("[QQMusic] data顶层keys: " + listTopLevelKeys(dataObj));
                return "";
            }
            lyric = LyricsService.unescape(lyric);
            System.out.println("[QQMusic] 歌词原始长度=" + lyric.length()
                    + " 是否XML=" + isXmlQrc(lyric)
                    + " 是否紧凑QRC=" + isCompactQrc(lyric));
            return lyric;
        }

        // 回退: 检查 trans (翻译, 通常也是 QRC 格式)
        String trans = LyricsService.extractTopLevelField(dataObj, "trans");
        if (!trans.isEmpty() && !"null".equals(trans)) {
            trans = LyricsService.unescape(trans);
            System.out.println("[QQMusic] 使用 trans 字段, 长度=" + trans.length());
            return trans;
        }

        System.err.println("[QQMusic] lyric/trans 均为空, data前400字符: "
                + truncate(dataObj, 400));
        return "";
    }

    // ── 歌曲 mid 提取 ──

    private static String extractSongMid(String json, String title, String artist) {
        String tl = title.toLowerCase().replaceAll("[\\s()（）《》\"\"''·,，、]", "");
        String al = artist.toLowerCase().replaceAll("[\\s()（）《》\"\"''·,，、]", "");

        int listIdx = json.indexOf("\"item_song\"");
        if (listIdx < 0) {
            listIdx = json.indexOf("\"list\"");
        }
        if (listIdx < 0) {
            System.err.println("[QQMusic] 响应中无 item_song/list 键");
            return "";
        }

        String bestMid = "";
        int bestScore = 0;
        int pos = listIdx;
        while (pos < json.length()) {
            int s = json.indexOf('{', pos);
            if (s < 0 || s > listIdx + 20000) break;
            int e = LyricsService.findMatchingBrace(json, s);
            if (e < 0) break;
            String item = json.substring(s, e + 1);

            String mid = LyricsService.extractTopLevelField(item, "mid");
            String name = LyricsService.extractTopLevelField(item, "name");
            if (name.isEmpty()) name = LyricsService.extractTopLevelField(item, "songname");
            if (name.isEmpty()) name = LyricsService.extractTopLevelField(item, "title");
            if (mid.isEmpty()) mid = LyricsService.extractTopLevelField(item, "songmid");

            String singerName = "";
            int singerIdx = item.indexOf("\"singer\"");
            if (singerIdx >= 0) {
                int singerObj = item.indexOf('{', singerIdx);
                if (singerObj >= 0 && singerObj < item.length()) {
                    int singerEnd = LyricsService.findMatchingBrace(item, singerObj);
                    if (singerEnd >= 0) {
                        String sObj = item.substring(singerObj, singerEnd + 1);
                        singerName = LyricsService.extractTopLevelField(sObj, "name");
                    }
                }
            }

            if (!mid.isEmpty() && !name.isEmpty()) {
                String snl = name.toLowerCase().replaceAll("[\\s()（）《》\"\"''·,，、]", "");
                String sal = singerName.toLowerCase().replaceAll("[\\s()（）《》\"\"''·,，、]", "");
                int sc = 0;
                if (snl.equals(tl)) sc += 5;
                else if (snl.contains(tl) || tl.contains(snl)) sc += 3;
                if (sal.equals(al)) sc += 5;
                else if (sal.contains(al) || al.contains(sal)) sc += 2;
                if (sc > bestScore) { bestScore = sc; bestMid = mid; }
            }
            pos = e + 1;
        }
        return bestMid;
    }

    // ═══════════════════════════════════════════
    //  QRC → LRC 格式转换
    // ═══════════════════════════════════════════

    /** 检测是否为 XML 包裹的 QRC */
    private static boolean isXmlQrc(String text) {
        return text != null && (text.trim().startsWith("<?xml")
                || text.contains("<QrcInfos>") || text.contains("<LyricContent"));
    }

    /** 检测是否为紧凑 QRC 格式（至少2行匹配 [数字,数字]...） */
    private static boolean isCompactQrc(String text) {
        if (text == null || text.isEmpty()) return false;
        int count = 0;
        for (String line : text.split("\\n")) {
            if (QRC_LINE.matcher(line.trim()).matches()) {
                if (++count >= 2) return true;
            }
        }
        return false;
    }

    /**
     * 将 QQ 音乐 QRC 格式转换为标准 LRC 格式。
     * 支持两种 QRC 形态：
     * 1. XML 包裹：{@code <Lyric_1 LyricContent="[ms,dur]text..."/>}
     * 2. 紧凑文本：{@code [startMs,durationMs]text(wordTiming)}
     * 同时兼容已是标准 LRC 的文本（原样返回）。
     */
    static String convertQrcToLrc(String text) {
        if (text == null || text.isEmpty()) return text;

        // 1. XML 包裹 → 提取 LyricContent 属性值
        if (isXmlQrc(text)) {
            StringBuilder extracted = new StringBuilder();
            Matcher m = XML_LYRIC_CONTENT.matcher(text);
            while (m.find()) {
                String content = m.group(1);
                // XML 属性中换行可能被编码为 &#10; 或 &#xa;
                content = content.replace("&#10;", "\n").replace("&#xa;", "\n");
                if (extracted.length() > 0) extracted.append('\n');
                extracted.append(content);
            }
            if (extracted.length() > 0) {
                text = extracted.toString();
            }
        }

        // 2. 已经是标准 LRC 格式 → 原样返回
        if (!isCompactQrc(text)) return text;

        // 3. 紧凑 QRC → LRC 转换
        StringBuilder lrc = new StringBuilder();
        for (String line : text.split("\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            Matcher qm = QRC_LINE.matcher(trimmed);
            if (qm.matches()) {
                long startMs = Long.parseLong(qm.group(1));
                String content = qm.group(3);

                // 去除逐字时间标记: (offset,duration)
                content = WORD_TIME.matcher(content).replaceAll("").trim();
                if (content.isEmpty()) continue;

                // ms → [mm:ss.xx]  (xx = 百分秒，两位)
                long totalSec = startMs / 1000;
                long min = totalSec / 60;
                long sec = totalSec % 60;
                long cs = (startMs % 1000) / 10; // 百分秒
                lrc.append(String.format("[%02d:%02d.%02d]%s\n", min, sec, cs, content));
            } else {
                // 元数据行 [ti:...] [ar:...] 等，保留
                if (trimmed.startsWith("[")) {
                    lrc.append(trimmed).append('\n');
                }
            }
        }
        return lrc.length() > 0 ? lrc.toString() : text;
    }

    // ── 工具 ──

    /** 列出 JSON 对象的顶层 key 名称，用于调试 */
    private static String listTopLevelKeys(String json) {
        if (json == null || json.isEmpty()) return "(empty)";
        StringBuilder sb = new StringBuilder();
        Matcher m = Pattern.compile("\"([^\"]+)\"\\s*:").matcher(json);
        int depth = 0;
        while (m.find()) {
            // 只统计 depth==1 的字段（顶层）
            String before = json.substring(0, m.start());
            depth = 0;
            for (int i = 0; i < before.length(); i++) {
                char c = before.charAt(i);
                if (c == '{' || c == '[') depth++;
                else if (c == '}' || c == ']') depth--;
            }
            if (depth == 1) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(m.group(1));
            }
        }
        return sb.length() > 0 ? sb.toString() : "(none)";
    }

    private static String truncate(String s, int max) {
        if (s == null) return "(null)";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "...(" + s.length() + "字符)";
    }
}
