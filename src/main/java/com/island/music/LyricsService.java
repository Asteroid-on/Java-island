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
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 自研 LRC 歌词获取与解析服务。
 *
 * <p>优先适配网易云音乐 → QQ音乐 → LRCLIB 三级兜底。</p>
 */
public final class LyricsService {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(3000))
            .build();

    // 修复：支持 1-2 位数字的分和秒（如 [0:05.50] 或 [00:5.50]）
    //        也支持 2 位或 3 位毫秒
    private static final Pattern LRC_TIME = Pattern.compile(
            "\\[(\\d{1,2}):(\\d{1,2})[.:](\\d{2,3})\\]");

    // 使用本地 ncm-server (ncm-api-rs) 替代直连网易云 API
    // 启动: ncm-server.exe 默认监听 http://localhost:3000
    private static final String NCM_SERVER = "http://localhost:3000";
    private static final String NCM_SEARCH  = NCM_SERVER + "/cloudsearch";
    private static final String NCM_LYRIC   = NCM_SERVER + "/lyric";
    private static final String NCM_LYRIC_NEW = NCM_SERVER + "/lyric/new";

    private static final String QQ_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/120.0.0.0 Safari/537.36";
    private static final String QQ_REFERER = "https://y.qq.com/portal/player.html";

    // ── 缓存 ──
    private String lastArtist = "";
    private String lastTitle = "";
    private List<LyricItem> cachedLines = Collections.emptyList();
    private String cachedCoverUrl = "";

    // ═══════════════════════════════════════════
    //  公共 API
    // ═══════════════════════════════════════════

    public List<LyricItem> getLyrics(String title, String artist, String sourceAppId) {
        if (title == null || artist == null || title.isEmpty()) {
            System.out.println("[Lyrics] getLyrics: 参数无效 title=" + title + " artist=" + artist);
            return Collections.emptyList();
        }
        if (title.equals(lastTitle) && artist.equals(lastArtist) && !cachedLines.isEmpty()) {
            System.out.println("[Lyrics] 命中缓存: " + title + " - " + artist + " (" + cachedLines.size() + "行)");
            return cachedLines;
        }

        System.out.println("[Lyrics] === 开始获取歌词: " + title + " - " + artist + " sourceAppId=" + sourceAppId + " ===");

        List<LyricItem> lines;
        String appLower = sourceAppId != null ? sourceAppId.toLowerCase() : "";

        if (appLower.contains("cloudmusic") || appLower.contains("netease")) {
            // ── 网易云优先 ──
            System.out.println("[Lyrics] 匹配到网易云 → 尝试网易云API");
            lines = fetchNeteaseLyric(title, artist);
            if (lines.isEmpty()) {
                System.out.println("[Lyrics] 网易云失败 → 尝试 LRCLIB 兜底");
                lines = fetchLrclib(title, artist);
            }
        } else if (appLower.contains("qqmusic")) {
            System.out.println("[Lyrics] 匹配到QQ音乐 → 尝试QQ音乐API");
            lines = fetchQQMusicLyric(title, artist);
            if (lines.isEmpty()) {
                System.out.println("[Lyrics] QQ音乐失败 → 尝试 LRCLIB 兜底");
                lines = fetchLrclib(title, artist);
            }
        } else {
            // 未知平台：网易云 → QQ → LRCLIB 三级尝试
            System.out.println("[Lyrics] 未知平台 → 三级尝试: 网易云 → QQ → LRCLIB");
            lines = fetchNeteaseLyric(title, artist);
            if (lines.isEmpty()) {
                lines = fetchQQMusicLyric(title, artist);
            }
            if (lines.isEmpty()) {
                lines = fetchLrclib(title, artist);
            }
        }

        if (!lines.isEmpty()) {
            lastTitle = title;
            lastArtist = artist;
            cachedLines = lines;
            System.out.println("[Lyrics] ✅ 成功获取 " + lines.size() + " 行歌词");
        } else {
            System.out.println("[Lyrics] ❌ 所有来源均失败，歌词获取失败");
        }
        return lines;
    }

    /** 二分查找当前歌词行索引 */
    public int findLineIndex(List<LyricItem> lines, long positionMillis) {
        if (lines.isEmpty()) return -1;
        int left = 0, right = lines.size() - 1, target = 0;
        while (left <= right) {
            int mid = (left + right) >>> 1;
            if (lines.get(mid).startTime <= positionMillis) {
                target = mid;
                left = mid + 1;
            } else {
                right = mid - 1;
            }
        }
        while (target >= 0 && isMetadataLine(lines.get(target).content)) {
            target--;
        }
        if (target < 0 && !lines.isEmpty()) {
            for (int i = 0; i < lines.size(); i++) {
                if (!isMetadataLine(lines.get(i).content)) return i;
            }
            return -1;
        }
        return target;
    }

    public void clear() {
        lastTitle = "";
        lastArtist = "";
        cachedLines = Collections.emptyList();
    }

    // ═══════════════════════════════════════════
    //  封面获取
    // ═══════════════════════════════════════════

    public String fetchCoverUrl(String title, String artist) {
        if (title == null || artist == null || title.isEmpty()) return "";
        if (title.equals(lastTitle) && artist.equals(lastArtist) && !cachedCoverUrl.isEmpty()) {
            return cachedCoverUrl;
        }
        try {
            String query = urlEncode(artist + " " + title);
            String reqBody = "term=" + query + "&country=cn&media=music&limit=1";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://itunes.apple.com/search"))
                    .timeout(Duration.ofMillis(1500))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(reqBody))
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200 || resp.body().isEmpty()) return "";

            String artUrl = extractJsonField(resp.body(), "artworkUrl100");
            if (artUrl.isEmpty()) return "";
            artUrl = artUrl.replace("100x100bb", "1200x1200bb");
            cachedCoverUrl = artUrl;
            return artUrl;
        } catch (Exception e) {
            return "";
        }
    }

    // ═══════════════════════════════════════════
    //  网易云音乐 API（通过本地 ncm-server）
    // ═══════════════════════════════════════════

    private List<LyricItem> fetchNeteaseLyric(String title, String artist) {
        try {
            String searchUrl = NCM_SEARCH + "?keywords=" + urlEncode(title + " " + artist)
                    + "&type=1&limit=5";
            System.out.println("[Lyrics] ncm-server 搜索: " + searchUrl);
            HttpRequest searchReq = HttpRequest.newBuilder()
                    .uri(URI.create(searchUrl))
                    .timeout(Duration.ofMillis(3000))
                    .GET()
                    .build();
            HttpResponse<String> searchResp = HTTP.send(searchReq, HttpResponse.BodyHandlers.ofString());
            System.out.println("[Lyrics] ncm-server 搜索响应: HTTP " + searchResp.statusCode()
                    + " bodyLen=" + searchResp.body().length());
            if (searchResp.statusCode() != 200 || searchResp.body().isEmpty()) {
                return Collections.emptyList();
            }

            String songId = extractNcmSongIdFromCloudsearch(searchResp.body(), title, artist);
            if (songId.isEmpty()) {
                System.out.println("[Lyrics] ncm-server: 未匹配到歌曲ID");
                return Collections.emptyList();
            }
            System.out.println("[Lyrics] ncm-server songId=" + songId);

            String lyricUrl = NCM_LYRIC + "?id=" + songId;
            System.out.println("[Lyrics] ncm-server 歌词请求: " + lyricUrl);
            HttpRequest lyricReq = HttpRequest.newBuilder()
                    .uri(URI.create(lyricUrl))
                    .timeout(Duration.ofMillis(3000))
                    .GET()
                    .build();
            HttpResponse<String> lyricResp = HTTP.send(lyricReq, HttpResponse.BodyHandlers.ofString());
            System.out.println("[Lyrics] ncm-server 歌词响应: HTTP " + lyricResp.statusCode()
                    + " bodyLen=" + lyricResp.body().length());
            if (lyricResp.statusCode() != 200 || lyricResp.body().isEmpty()) {
                return Collections.emptyList();
            }

            String lyricText = extractNcmLyricText(lyricResp.body());
            if (!lyricText.isEmpty()) {
                System.out.println("[Lyrics] ncm-server 歌词原文(" + lyricText.length() + "字符): "
                        + lyricText.substring(0, Math.min(200, lyricText.length())).replace("\n", "\\n"));
                return parseLrc(lyricText);
            }

            // 老接口无歌词，尝试 /lyric/new（v1 接口，含逐字歌词 yrc）
            System.out.println("[Lyrics] ncm-server /lyric 无歌词 → 尝试 /lyric/new");
            String lyricNewUrl = NCM_LYRIC_NEW + "?id=" + songId;
            HttpRequest lyricNewReq = HttpRequest.newBuilder()
                    .uri(URI.create(lyricNewUrl))
                    .timeout(Duration.ofMillis(3000))
                    .GET()
                    .build();
            HttpResponse<String> lyricNewResp = HTTP.send(lyricNewReq, HttpResponse.BodyHandlers.ofString());
            System.out.println("[Lyrics] ncm-server /lyric/new 响应: HTTP " + lyricNewResp.statusCode()
                    + " bodyLen=" + lyricNewResp.body().length());
            if (lyricNewResp.statusCode() != 200 || lyricNewResp.body().isEmpty()) {
                return Collections.emptyList();
            }

            lyricText = extractNcmLyricText(lyricNewResp.body());
            if (lyricText.isEmpty()) {
                System.out.println("[Lyrics] ncm-server: /lyric 和 /lyric/new 均无歌词, body前500: "
                        + lyricResp.body().substring(0, Math.min(500, lyricResp.body().length())));
                return Collections.emptyList();
            }

            System.out.println("[Lyrics] ncm-server /lyric/new 歌词原文(" + lyricText.length() + "字符): "
                    + lyricText.substring(0, Math.min(200, lyricText.length())).replace("\n", "\\n"));
            return parseLrc(lyricText);
        } catch (Exception e) {
            System.err.println("[Lyrics] ncm-server 异常: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 从 cloudsearch 接口返回的 JSON 中匹配最佳歌曲 ID。
     * cloudsearch 用 "ar" 表示歌手数组，而非旧版 "artists"。
     */
    private static String extractNcmSongIdFromCloudsearch(String json, String title, String artist) {
        String titleLower = title.toLowerCase().replaceAll("[\\s()（）《》\"\"''·]", "");
        String artistLower = artist.toLowerCase().replaceAll("[\\s()（）《》\"\"''·]", "");

        int songsIdx = json.indexOf("\"songs\"");
        if (songsIdx < 0) {
            System.out.println("[Lyrics] cloudsearch: 搜索结果无 songs 字段");
            return "";
        }

        String bestId = "";
        int bestScore = 0;
        int pos = songsIdx;
        while (pos < json.length()) {
            int objStart = json.indexOf('{', pos);
            if (objStart < 0) break;
            int objEnd = findMatchingBrace(json, objStart);
            if (objEnd < 0) break;
            String item = json.substring(objStart, objEnd + 1);

            // 使用深度感知提取，只取歌曲对象顶层的 "id"/"name"，
            // 避免误取 ar[0].id（艺术家ID）或 al.id（专辑ID）
            String sid = extractTopLevelField(item, "id");
            String sname = extractTopLevelField(item, "name");
            // cloudsearch 用 "ar" 而非 "artists"
            int arIdx = item.indexOf("\"ar\"");
            String sartist = "";
            if (arIdx >= 0) {
                sartist = extractJsonField(item.substring(arIdx), "name");
            }

            if (!sid.isEmpty() && !sname.isEmpty()) {
                String snLower = sname.toLowerCase().replaceAll("[\\s()（）《》\"\"''·]", "");
                String saLower = sartist.toLowerCase().replaceAll("[\\s()（）《》\"\"''·]", "");

                int score = 0;
                if (snLower.equals(titleLower)) score += 5;
                else if (snLower.contains(titleLower) || titleLower.contains(snLower)) score += 3;

                if (saLower.equals(artistLower)) score += 5;
                else if (saLower.contains(artistLower) || artistLower.contains(saLower)) score += 2;

                System.out.println("[Lyrics] cloudsearch 候选: id=" + sid + " name=" + sname + " artist=" + sartist + " score=" + score);

                if (score > bestScore) {
                    bestScore = score;
                    bestId = sid;
                }
            }
            pos = objEnd + 1;
            if (pos > songsIdx + 5000) break;
        }
        if (bestScore == 0) {
            System.out.println("[Lyrics] cloudsearch: 无匹配结果 (score=0)");
        }
        return bestId;
    }

    // ═══════════════════════════════════════════
    //  QQ音乐 API
    // ═══════════════════════════════════════════

    private List<LyricItem> fetchQQMusicLyric(String title, String artist) {
        try {
            String q = title + " " + artist;
            String jsonBody = "{\"req_1\":{\"method\":\"DoSearchForQQMusicDesktop\","
                    + "\"module\":\"music.search.SearchCgiService\","
                    + "\"param\":{\"num_per_page\":\"5\",\"page_num\":\"1\","
                    + "\"query\":\"" + escJson(q) + "\","
                    + "\"search_type\":0}}}";
            System.out.println("[Lyrics] QQ音乐搜索 query=\"" + q + "\"");
            HttpRequest searchReq = HttpRequest.newBuilder()
                    .uri(URI.create("https://u.y.qq.com/cgi-bin/musicu.fcg"))
                    .timeout(Duration.ofMillis(2000))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();
            HttpResponse<String> searchResp = HTTP.send(searchReq, HttpResponse.BodyHandlers.ofString());
            System.out.println("[Lyrics] QQ音乐搜索响应: HTTP " + searchResp.statusCode()
                    + " bodyLen=" + searchResp.body().length());
            if (searchResp.statusCode() != 200) return Collections.emptyList();

            String songMid = extractNestedValue(searchResp.body(), "mid");
            if (songMid.isEmpty()) {
                System.out.println("[Lyrics] QQ音乐: 未匹配到 songMid");
                return Collections.emptyList();
            }
            System.out.println("[Lyrics] QQ音乐 songMid=" + songMid);

            String lyricUrl = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg"
                    + "?songmid=" + urlEncode(songMid)
                    + "&format=json&inCharset=utf8&outCharset=utf8"
                    + "&notice=0&platform=yqq&needNewCode=0"
                    + "&nobase64=1";
            System.out.println("[Lyrics] QQ音乐歌词请求: " + lyricUrl);
            HttpRequest lyricReq = HttpRequest.newBuilder()
                    .uri(URI.create(lyricUrl))
                    .timeout(Duration.ofMillis(2000))
                    .header("Referer", QQ_REFERER)
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .header("User-Agent", QQ_UA)
                    .GET()
                    .build();
            HttpResponse<String> lyricResp = HTTP.send(lyricReq, HttpResponse.BodyHandlers.ofString());
            System.out.println("[Lyrics] QQ音乐歌词响应: HTTP " + lyricResp.statusCode()
                    + " bodyLen=" + lyricResp.body().length());
            if (lyricResp.statusCode() != 200) return Collections.emptyList();

            String body = lyricResp.body();
            if (body.isEmpty()) return Collections.emptyList();

            String json = body.trim();
            int braceIdx = json.indexOf('{');
            if (braceIdx > 0) json = json.substring(braceIdx);
            int lastBrace = json.lastIndexOf('}');
            if (lastBrace >= 0) json = json.substring(0, lastBrace + 1);

            String retcode = extractJsonField(json, "retcode");
            if (retcode.isEmpty()) retcode = extractJsonField(json, "code");
            System.out.println("[Lyrics] QQ音乐歌词 retcode=" + retcode);
            if (!retcode.isEmpty() && !retcode.equals("0")) return Collections.emptyList();

            String lyric = extractJsonField(json, "lyric");
            if (lyric.isEmpty()) {
                System.out.println("[Lyrics] QQ音乐: lyric 字段为空");
                return Collections.emptyList();
            }
            try {
                lyric = new String(Base64.getDecoder().decode(lyric), StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                // nobase64=1 应返回明文，base64 解码失败说明已经是明文
            }

            System.out.println("[Lyrics] QQ音乐歌词原文(" + lyric.length() + "字符): "
                    + lyric.substring(0, Math.min(200, lyric.length())).replace("\n", "\\n"));
            return parseLrc(lyric);
        } catch (Exception e) {
            System.err.println("[Lyrics] QQ音乐异常: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            return Collections.emptyList();
        }
    }

    // ═══════════════════════════════════════════
    //  LRCLIB
    // ═══════════════════════════════════════════

    private List<LyricItem> fetchLrclib(String title, String artist) {
        List<LyricItem> lines = fetchLrclibGet(title, artist);
        if (!lines.isEmpty()) return lines;
        return fetchLrclibSearch(title, artist);
    }

    private List<LyricItem> fetchLrclibGet(String title, String artist) {
        try {
            String reqBody = "artist_name=" + urlEncode(artist)
                    + "&track_name=" + urlEncode(title);
            System.out.println("[Lyrics] LRCLIB GET: " + reqBody);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://lrclib.net/api/get"))
                    .timeout(Duration.ofMillis(5000))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(reqBody))
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            System.out.println("[Lyrics] LRCLIB GET 响应: HTTP " + resp.statusCode()
                    + " bodyLen=" + resp.body().length());
            if (resp.statusCode() != 200 || resp.body().isEmpty()) return Collections.emptyList();
            System.out.println("[Lyrics] LRCLIB GET body(前200): "
                    + resp.body().substring(0, Math.min(200, resp.body().length())).replace("\n", "\\n"));
            return extractLrcFromJson(resp.body());
        } catch (Exception e) {
            System.err.println("[Lyrics] LRCLIB GET 异常: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<LyricItem> fetchLrclibSearch(String title, String artist) {
        try {
            String url = "https://lrclib.net/api/search?q="
                    + urlEncode(title + " " + artist);
            System.out.println("[Lyrics] LRCLIB Search: " + url);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(5000))
                    .GET()
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            System.out.println("[Lyrics] LRCLIB Search 响应: HTTP " + resp.statusCode()
                    + " bodyLen=" + resp.body().length());
            if (resp.statusCode() != 200 || resp.body().isEmpty()) return Collections.emptyList();

            String body = resp.body().trim();
            if (!body.startsWith("[")) {
                System.out.println("[Lyrics] LRCLIB Search: 响应非JSON数组");
                return Collections.emptyList();
            }

            String titleLower = title.toLowerCase().replaceAll("[\\s()（）《》\"\"'']", "");
            String artistLower = artist.toLowerCase().replaceAll("[\\s()（）《》\"\"'']", "");
            String bestRaw = null;
            int bestScore = 0;
            int pos = 1;
            while (pos < body.length()) {
                int objStart = body.indexOf('{', pos);
                if (objStart < 0) break;
                int objEnd = findMatchingBrace(body, objStart);
                if (objEnd < 0) break;
                String item = body.substring(objStart, objEnd + 1);

                // 先用轻量字段评分，延后提取 syncedLyrics（避免对数百条结果做高成本字符串操作）
                String trackName = extractJsonField(item, "trackName");
                String lrcArtist = extractJsonField(item, "artistName");

                if (!trackName.isEmpty()) {
                    String tnLower = trackName.toLowerCase().replaceAll("[\\s()（）《》\"\"'']", "");
                    String anLower = lrcArtist.toLowerCase().replaceAll("[\\s()（）《》\"\"'']", "");

                    int score = 0;
                    if (tnLower.equals(titleLower)) score += 10;
                    else if (tnLower.contains(titleLower) || titleLower.contains(tnLower)) score += 5;

                    if (!anLower.isEmpty()) {
                        if (anLower.equals(artistLower)) score += 10;
                        else if (anLower.contains(artistLower) || artistLower.contains(anLower)) score += 5;
                    } else {
                        score += 2;
                    }

                    if (score > bestScore) {
                        // 仅对胜出候选项提取完整歌词，避免大量 CPU/mem 浪费
                        String synced = extractJsonField(item, "syncedLyrics");
                        if (!synced.isEmpty()) {
                            System.out.println("[Lyrics] LRCLIB 候选: track=" + trackName + " artist=" + lrcArtist
                                    + " score=" + score + " hasLyrics=" + synced.length());
                            bestScore = score;
                            bestRaw = synced;
                        }
                    }
                }
                pos = objEnd + 1;
                if (pos > body.length()) break;
            }
            if (bestRaw == null || bestRaw.isEmpty()) {
                System.out.println("[Lyrics] LRCLIB Search: 无匹配结果");
                return Collections.emptyList();
            }
            System.out.println("[Lyrics] LRCLIB 选中 bestScore=" + bestScore);
            return parseLrc(bestRaw);
        } catch (Exception e) {
            System.err.println("[Lyrics] LRCLIB Search 异常: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<LyricItem> extractLrcFromJson(String body) {
        String lrc = extractJsonField(body, "syncedLyrics");
        if (lrc.isEmpty()) {
            lrc = extractJsonField(body, "plainLyrics");
            System.out.println("[Lyrics] 使用 plainLyrics 兜底");
        }
        if (lrc.isEmpty()) {
            System.out.println("[Lyrics] extractLrcFromJson: 无 syncedLyrics 也无 plainLyrics");
            return Collections.emptyList();
        }
        return parseLrc(lrc);
    }

    // ═══════════════════════════════════════════
    //  LRC 解析（增强版：支持单数字时间 + 错误日志）
    // ═══════════════════════════════════════════

    static List<LyricItem> parseLrc(String lrc) {
        if (lrc == null || lrc.isEmpty()) {
            System.out.println("[Lyrics] parseLrc: 输入为空");
            return Collections.emptyList();
        }
        System.out.println("[Lyrics] parseLrc: 开始解析, 输入长度=" + lrc.length());

        List<LyricItem> lines = new ArrayList<>();
        int totalLines = 0, skipped = 0;
        try (BufferedReader r = new BufferedReader(new StringReader(lrc))) {
            String line;
            while ((line = r.readLine()) != null) {
                totalLines++;
                Matcher m = LRC_TIME.matcher(line);
                List<long[]> times = new ArrayList<>();
                while (m.find()) {
                    int min = Integer.parseInt(m.group(1));
                    int sec = Integer.parseInt(m.group(2));
                    int ms = Integer.parseInt(m.group(3));
                    if (m.group(3).length() == 2) ms *= 10;
                    times.add(new long[]{min * 60L * 1000L + sec * 1000L + ms, m.end()});
                }
                if (times.isEmpty()) {
                    skipped++;
                    continue;
                }
                long textStart = times.get(times.size() - 1)[1];
                String text = line.substring((int) textStart).trim();
                if (text.isEmpty() || isMetadataLine(text)) {
                    skipped++;
                    continue;
                }
                for (long[] t : times) {
                    lines.add(new LyricItem(t[0], text));
                }
            }
        } catch (Exception e) {
            System.err.println("[Lyrics] parseLrc 解析异常: " + e.getClass().getSimpleName()
                    + " - " + e.getMessage() + " (已解析" + lines.size() + "行)");
        }

        lines.sort((a, b) -> Long.compare(a.startTime, b.startTime));
        System.out.println("[Lyrics] parseLrc 完成: 总行=" + totalLines + " 跳过=" + skipped
                + " 有效行=" + lines.size());
        return lines;
    }

    // ═══════════════════════════════════════════
    //  文本过滤
    // ═══════════════════════════════════════════

    private static boolean isMetadataLine(String text) {
        if (text.isEmpty()) return true;
        String t = text.trim();
        if (isPlaceholderLyric(t)) return true;
        if (t.matches("^[\\d：:：\\-–—/._，,、\\s]+$")) return true;

        String[] metaPrefixes = {
            "编曲", "作曲", "作词", "填词", "谱曲", "和声", "和音",
            "混音", "录音", "母带", "监制", "制作", "出品", "发行",
            "吉他", "贝斯", "钢琴", "键盘", "鼓", "鼓手", "弦乐",
            "小提琴", "大提琴", "中提琴", "长笛", "萨克斯", "小号",
            "Program", "制作人", "录音师", "混音师", "母带师",
            "合声", "编写", "统筹", "企划", "封面", "插画", "视觉",
            "录音棚", "录音室", "混音室", "母带工作室",
            "OP", "SP", "原唱", "翻唱", "演唱", "歌手",
        };
        for (String prefix : metaPrefixes) {
            if (t.startsWith(prefix)) return true;
        }
        String[] enPrefixes = {
            "Lyrics by", "Composed by", "Arranged by", "Produced by",
            "Mixed by", "Mastered by", "Recorded by", "Programmed by",
            "Guitar", "Bass", "Drums", "Piano", "Keyboard", "Strings",
            "Vocal", "Chorus", "Backing", "Edited by", "Engineered by",
            "Performed by", "Written by", "Music by", "Words by",
        };
        String tLower = t.toLowerCase();
        for (String prefix : enPrefixes) {
            if (tLower.startsWith(prefix.toLowerCase())) return true;
        }
        return false;
    }

    private static boolean isPlaceholderLyric(String text) {
        if (text.isEmpty()) return false;
        String t = text.replaceAll("[，,。！!~～\\s]+", "").toLowerCase();
        return t.contains("暂无歌词") || t.contains("没有歌词") || t.contains("无歌词")
                || t.contains("纯音乐") || t.contains("没有填词") || t.contains("未收录")
                || t.contains("暂无lrc") || t.contains("歌词暂未")
                || t.contains("no lyric") || t.contains("instrumental")
                || t.contains("no lyrics");
    }

    // ═══════════════════════════════════════════
    //  工具方法
    // ═══════════════════════════════════════════

    /**
     * 从 JSON 对象中提取第一层（depth=1）的字段值。
     * 通过跟踪括号深度，只匹配对象直接子字段，排除嵌套对象/数组中的同名 key。
     *
     * <p>典型场景：cloudsearch 歌曲对象 {"id":123,"ar":[{"id":456}]}
     * 中提取顶层的 "id"（123），而非 ar[0].id（456）。</p>
     */
    private static String extractTopLevelField(String json, String fieldName) {
        String key = "\"" + fieldName + "\"";
        int depth = 0;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{' || c == '[') depth++;
            else if (c == '}' || c == ']') depth--;
            else if (depth == 1 && json.startsWith(key, i)) {
                int ci = json.indexOf(':', i + key.length());
                if (ci < 0) return "";
                int start = ci + 1;
                while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
                if (start >= json.length()) return "";
                char first = json.charAt(start);
                if (first == '"') {
                    int end = start + 1;
                    while (end < json.length()) {
                        char ec = json.charAt(end);
                        if (ec == '\\' && end + 1 < json.length()) { end += 2; }
                        else if (ec == '"') { break; }
                        else { end++; }
                    }
                    if (end >= json.length()) return "";
                    return unescapeJson(json.substring(start + 1, end));
                }
                int end2 = start;
                while (end2 < json.length() && !Character.isWhitespace(json.charAt(end2))
                        && json.charAt(end2) != ',' && json.charAt(end2) != '}'
                        && json.charAt(end2) != ']') end2++;
                return json.substring(start, end2);
            }
        }
        return "";
    }

    /**
     * 从网易云歌词 API 响应中提取歌词文本。
     * 尝试顺序：lrc.lyric → klyric.lyric → tlyric.lyric → yrc.lyric。
     * 排除 JSON null 值（extractJsonField 会返回字符串 "null"）。
     */
    private static String extractNcmLyricText(String body) {
        // 1) 原文歌词 lrc.lyric
        String lrcField = extractJsonField(body, "lrc");
        if (!lrcField.isEmpty()) {
            String text = extractJsonField(lrcField, "lyric");
            if (!text.isEmpty() && !"null".equals(text)) return text;
        }
        // 2) 逐字歌词 klyric.lyric（卡拉OK格式）
        String kField = extractJsonField(body, "klyric");
        if (!kField.isEmpty()) {
            String text = extractJsonField(kField, "lyric");
            if (!text.isEmpty() && !"null".equals(text)) {
                System.out.println("[Lyrics] ncm-server: 使用 klyric 作为歌词源");
                return text;
            }
        }
        // 3) 翻译歌词 tlyric.lyric
        String tField = extractJsonField(body, "tlyric");
        if (!tField.isEmpty()) {
            String text = extractJsonField(tField, "lyric");
            if (!text.isEmpty() && !"null".equals(text)) {
                System.out.println("[Lyrics] ncm-server: 使用 tlyric 作为歌词源");
                return text;
            }
        }
        // 4) 逐字歌词 yrc.lyric（/lyric/new v1 接口特有）
        String yField = extractJsonField(body, "yrc");
        if (!yField.isEmpty()) {
            String text = extractJsonField(yField, "lyric");
            if (!text.isEmpty() && !"null".equals(text)) {
                System.out.println("[Lyrics] ncm-server: 使用 yrc 作为歌词源");
                return text;
            }
        }
        return "";
    }

    private static String extractJsonField(String json, String fieldName) {
        String key = "\"" + fieldName + "\"";
        int ki = json.indexOf(key);
        if (ki < 0) return "";
        int ci = json.indexOf(':', ki + key.length());
        if (ci < 0) return "";
        int start = ci + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        if (start >= json.length()) return "";
        char first = json.charAt(start);
        if (first == '"') {
            int end = start + 1;
            while (end < json.length()) {
                char c = json.charAt(end);
                if (c == '\\' && end + 1 < json.length()) {
                    end += 2;
                } else if (c == '"') {
                    break;
                } else {
                    end++;
                }
            }
            if (end >= json.length()) return "";
            return unescapeJson(json.substring(start + 1, end));
        }
        // 修复：对象/数组值使用括号匹配，避免被内部逗号截断
        // 例："lrc":{"version":7,"lyric":"..."} 不会再截断为 {"version":7
        if (first == '{' || first == '[') {
            int end = findMatchingBraceOrBracket(json, start);
            if (end < 0) return "";
            return json.substring(start, end + 1);
        }
        int end2 = start;
        while (end2 < json.length() && !Character.isWhitespace(json.charAt(end2))
                && json.charAt(end2) != ',' && json.charAt(end2) != '}'
                && json.charAt(end2) != ']') end2++;
        return json.substring(start, end2);
    }

    private static String unescapeJson(String s) {
        if (s.indexOf('\\') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                i++;
                switch (s.charAt(i)) {
                    case 'n':  sb.append('\n'); break;
                    case 'r':  sb.append('\r'); break;
                    case 't':  sb.append('\t'); break;
                    case '"':  sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case 'u':
                        if (i + 4 < s.length()) {
                            try {
                                sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                                i += 4;
                            } catch (NumberFormatException ignored) {
                                sb.append("\\u");
                            }
                        } else sb.append("\\u");
                        break;
                    default: sb.append('\\').append(s.charAt(i)); break;
                }
            } else {
                sb.append(c);
            }
            i++;
        }
        return sb.toString();
    }

    private static String extractNestedValue(String json, String key) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return "";
        int colon = json.indexOf(':', idx + search.length());
        if (colon < 0) return "";
        int s = colon + 1;
        while (s < json.length() && Character.isWhitespace(json.charAt(s))) s++;
        if (s >= json.length()) return "";
        char c = json.charAt(s);
        if (c == '"') {
            int e = json.indexOf('"', s + 1);
            return e >= 0 ? json.substring(s + 1, e) : "";
        }
        int e = s;
        while (e < json.length() && !Character.isWhitespace(json.charAt(e))
                && json.charAt(e) != ',' && json.charAt(e) != '}' && json.charAt(e) != ']') e++;
        return json.substring(s, e);
    }

    private static int findMatchingBrace(String s, int start) {
        int depth = 0;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') { depth--; if (depth == 0) return i; }
        }
        return -1;
    }

    /** 找到与 start 位置的 { 或 [ 匹配的 } 或 ]，用于 extractJsonField 提取对象/数组值 */
    private static int findMatchingBraceOrBracket(String s, int start) {
        char open = s.charAt(start);
        char close = (open == '{') ? '}' : ']';
        int depth = 0;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == open) depth++;
            else if (c == close) { depth--; if (depth == 0) return i; }
        }
        return -1;
    }

    private static String escJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String urlEncode(String s) {
        try {
            return URLEncoder.encode(s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }
}
