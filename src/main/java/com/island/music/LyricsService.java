package com.island.music;

import com.island.music.model.LyricItem;

import java.io.BufferedReader;
import java.io.StringReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 歌词服务门面：调度 → 缓存 → 进度查找。
 *
 * <p>所有平台适配逻辑已分离到 {@link LyricsProvider} 实现类，
 * 调度统一由 {@link LyricsDispatcher} 管理。</p>
 */
public final class LyricsService {

    static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(3000)).build();

    private static final Pattern LRC_TIME = Pattern.compile(
            "\\[(\\d{1,2}):(\\d{1,2})[.:](\\d{2,3})\\]");

    private final LyricsDispatcher dispatcher;
    private String lastArtist = "", lastTitle = "";
    private List<LyricItem> cachedLines = Collections.emptyList();
    private String cachedCoverUrl = "";

    public LyricsService() {
        this.dispatcher = new LyricsDispatcher()
                .register(new NeteaseLyricsProvider());
    }

    // ═══════════════════════════════════════════
    //  歌词
    // ═══════════════════════════════════════════

    public List<LyricItem> getLyrics(String title, String artist, String sourceAppId) {
        if (title == null || artist == null || title.isEmpty()) return Collections.emptyList();
        if (title.equals(lastTitle) && artist.equals(lastArtist) && !cachedLines.isEmpty()) {
            return cachedLines;
        }

        System.out.println("[Lyrics] " + title + " - " + artist + " src=" + sourceAppId);
        List<LyricItem> lines = dispatcher.dispatchLyrics(title, artist, sourceAppId);

        if (!lines.isEmpty()) {
            lastTitle = title; lastArtist = artist; cachedLines = lines;
            System.out.println("[Lyrics] ✅ " + lines.size() + "行");
        } else {
            System.out.println("[Lyrics] ❌ 所有来源均失败");
        }
        return lines;
    }

    // ═══════════════════════════════════════════
    //  封面
    // ═══════════════════════════════════════════

    public String fetchCoverUrl(String title, String artist, String sourceAppId) {
        if (title == null || artist == null || title.isEmpty()) return "";
        if (title.equals(lastTitle) && artist.equals(lastArtist) && !cachedCoverUrl.isEmpty())
            return cachedCoverUrl;

        // 1. 平台封面 API
        String url = dispatcher.dispatchCoverUrl(title, artist, sourceAppId);
        if (!url.isEmpty()) { cachedCoverUrl = url; return url; }

        // 2. iTunes 通用源
        try {
            String body = "term=" + urlEncode(artist + " " + title) + "&country=cn&media=music&limit=1";
            HttpResponse<String> resp = HTTP.send(
                    HttpRequest.newBuilder().uri(URI.create("https://itunes.apple.com/search"))
                            .timeout(Duration.ofMillis(1500))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200 || resp.body().isEmpty()) return "";
            String art = extractJsonField(resp.body(), "artworkUrl100");
            if (art.isEmpty()) return "";
            art = art.replace("100x100bb", "1200x1200bb");
            cachedCoverUrl = art;
            return art;
        } catch (Exception e) { return ""; }
    }

    // ═══════════════════════════════════════════
    //  二分查找
    // ═══════════════════════════════════════════

    public int findLineIndex(List<LyricItem> lines, long positionMillis) {
        if (lines.isEmpty()) return -1;
        int left = 0, right = lines.size() - 1, target = 0;
        while (left <= right) {
            int mid = (left + right) >>> 1;
            if (lines.get(mid).startTime <= positionMillis) { target = mid; left = mid + 1; }
            else right = mid - 1;
        }
        while (target >= 0 && isMetadataLine(lines.get(target).content)) target--;
        if (target < 0) {
            for (int i = 0; i < lines.size(); i++)
                if (!isMetadataLine(lines.get(i).content)) return i;
            return -1;
        }
        return target;
    }

    public void clear() { lastTitle = ""; lastArtist = ""; cachedLines = Collections.emptyList(); }

    // ═══════════════════════════════════════════
    //  LRC 解析（包内共享）
    // ═══════════════════════════════════════════

    static List<LyricItem> parseLrc(String lrc) {
        if (lrc == null || lrc.isEmpty()) return Collections.emptyList();
        List<LyricItem> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new StringReader(lrc))) {
            String line;
            while ((line = r.readLine()) != null) {
                Matcher m = LRC_TIME.matcher(line);
                List<long[]> times = new ArrayList<>();
                while (m.find()) {
                    int min = Integer.parseInt(m.group(1)), sec = Integer.parseInt(m.group(2));
                    int ms = Integer.parseInt(m.group(3));
                    if (m.group(3).length() == 2) ms *= 10;
                    times.add(new long[]{min * 60000L + sec * 1000L + ms, m.end()});
                }
                if (times.isEmpty()) continue;
                String text = line.substring((int) times.get(times.size() - 1)[1]).trim();
                if (text.isEmpty() || isMetadataLine(text)) continue;
                for (long[] t : times) lines.add(new LyricItem(t[0], text));
            }
        } catch (Exception ignored) { }
        lines.sort((a, b) -> Long.compare(a.startTime, b.startTime));
        return lines;
    }

    // ═══════════════════════════════════════════
    //  文本过滤（包内共享）
    // ═══════════════════════════════════════════

    private static boolean isMetadataLine(String text) {
        if (text.isEmpty()) return true;
        String t = text.trim();
        if (isPlaceholder(t)) return true;
        if (t.matches("^[\\d：:：\\-–—/._，,、\\s]+$")) return true;
        String[] zh = { "编曲","作曲","作词","填词","谱曲","和声","和音","混音","录音","母带","监制",
                "制作","出品","发行","吉他","贝斯","钢琴","键盘","鼓","鼓手","弦乐","小提琴","大提琴",
                "Program","制作人","录音师","混音师","合声","编写","统筹","企划","封面","插画","视觉",
                "录音棚","录音室","OP","SP","原唱","翻唱","演唱","歌手" };
        for (String p : zh) if (t.startsWith(p)) return true;
        String[] en = { "Lyrics by","Composed by","Arranged by","Produced by","Mixed by","Mastered by",
                "Recorded by","Programmed by","Guitar","Bass","Drums","Piano","Keyboard","Strings",
                "Vocal","Chorus","Backing","Edited by","Engineered by","Performed by","Written by",
                "Music by","Words by" };
        String tl = t.toLowerCase();
        for (String p : en) if (tl.startsWith(p.toLowerCase())) return true;
        return false;
    }

    private static boolean isPlaceholder(String text) {
        String t = text.replaceAll("[，,。！!~～\\s]+", "").toLowerCase();
        return t.contains("暂无歌词") || t.contains("没有歌词") || t.contains("无歌词")
                || t.contains("纯音乐") || t.contains("未收录") || t.contains("暂无lrc")
                || t.contains("歌词暂未") || t.contains("no lyric") || t.contains("instrumental");
    }

    // ═══════════════════════════════════════════
    //  JSON 工具（包内共享）
    // ═══════════════════════════════════════════

    static String extractTopLevelField(String json, String fieldName) {
        String key = "\"" + fieldName + "\"";
        int depth = 0;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{' || c == '[') depth++;
            else if (c == '}' || c == ']') depth--;
            else if (depth == 1 && json.startsWith(key, i)) {
                int ci = json.indexOf(':', i + key.length()); if (ci < 0) return "";
                int s = ci + 1; while (s < json.length() && Character.isWhitespace(json.charAt(s))) s++;
                if (s >= json.length()) return "";
                if (json.charAt(s) == '"') {
                    int e = s + 1;
                    while (e < json.length()) {
                        if (json.charAt(e) == '\\' && e + 1 < json.length()) e += 2;
                        else if (json.charAt(e) == '"') break;
                        else e++;
                    }
                    return e >= json.length() ? "" : unescape(json.substring(s + 1, e));
                }
                int e2 = s;
                while (e2 < json.length() && !Character.isWhitespace(json.charAt(e2))
                        && json.charAt(e2) != ',' && json.charAt(e2) != '}' && json.charAt(e2) != ']') e2++;
                return json.substring(s, e2);
            }
        }
        return "";
    }

    static String extractJsonField(String json, String fieldName) {
        String key = "\"" + fieldName + "\"";
        int ki = json.indexOf(key); if (ki < 0) return "";
        int ci = json.indexOf(':', ki + key.length()); if (ci < 0) return "";
        int s = ci + 1; while (s < json.length() && Character.isWhitespace(json.charAt(s))) s++;
        if (s >= json.length()) return "";
        char first = json.charAt(s);
        if (first == '"') {
            int e = s + 1;
            while (e < json.length()) {
                if (json.charAt(e) == '\\' && e + 1 < json.length()) e += 2;
                else if (json.charAt(e) == '"') break;
                else e++;
            }
            return e >= json.length() ? "" : unescape(json.substring(s + 1, e));
        }
        if (first == '{' || first == '[') {
            int e = findMatchingBraceOrBracket(json, s);
            return e < 0 ? "" : json.substring(s, e + 1);
        }
        int e2 = s;
        while (e2 < json.length() && !Character.isWhitespace(json.charAt(e2))
                && json.charAt(e2) != ',' && json.charAt(e2) != '}' && json.charAt(e2) != ']') e2++;
        return json.substring(s, e2);
    }

    static String extractNestedValue(String json, String key) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search); if (idx < 0) return "";
        int colon = json.indexOf(':', idx + search.length()); if (colon < 0) return "";
        int s = colon + 1; while (s < json.length() && Character.isWhitespace(json.charAt(s))) s++;
        if (s >= json.length()) return "";
        if (json.charAt(s) == '"') { int e = json.indexOf('"', s + 1); return e >= 0 ? json.substring(s + 1, e) : ""; }
        int e = s;
        while (e < json.length() && !Character.isWhitespace(json.charAt(e))
                && json.charAt(e) != ',' && json.charAt(e) != '}' && json.charAt(e) != ']') e++;
        return json.substring(s, e);
    }

    static int findMatchingBrace(String s, int start) {
        int depth = 0;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') depth++; else if (c == '}') { depth--; if (depth == 0) return i; }
        }
        return -1;
    }

    private static int findMatchingBraceOrBracket(String s, int start) {
        char open = s.charAt(start), close = open == '{' ? '}' : ']';
        int depth = 0;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == open) depth++; else if (c == close) { depth--; if (depth == 0) return i; }
        }
        return -1;
    }

    private static String unescape(String s) {
        if (s.indexOf('\\') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                i++;
                switch (s.charAt(i)) {
                    case 'n': sb.append('\n'); break; case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break; case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case 'u':
                        if (i + 4 < s.length()) {
                            try { sb.append((char) Integer.parseInt(s.substring(i+1, i+5), 16)); i += 4; }
                            catch (NumberFormatException ignored) { sb.append("\\u"); }
                        } else sb.append("\\u");
                        break;
                    default: sb.append('\\').append(s.charAt(i));
                }
            } else sb.append(c);
        }
        return sb.toString();
    }

    static String escJson(String s) { return s.replace("\\", "\\\\").replace("\"", "\\\""); }
    static String urlEncode(String s) { try { return URLEncoder.encode(s, StandardCharsets.UTF_8); } catch (Exception e) { return s; } }
}
