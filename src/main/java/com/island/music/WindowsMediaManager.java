package com.island.music;

import com.island.music.model.MusicInfo;
import com.island.util.AppLogger;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Base64;

/**
 * Java ↔ .NET 8 MediaInfoDaemon 桥接层。
 *
 * <p>MediaInfoDaemon 通过原子写入（tmp + rename）持续将 SMTC 媒体信息写入
 * {@code %TEMP%/media_info.json}，本类负责读取并解析为 {@link MusicInfo}。</p>
 *
 * <p>封面传输协议：新版 daemon 不再内嵌 base64 于 JSON，而是写入独立文件
 * {@code %TEMP%/media_thumb.bin} 并在 JSON 中携带 {@code thumbFile}/{@code thumbHash}；
 * 仅当 hash 变化时才读取文件（旧版内嵌 {@code thumbnail} 字段仍兼容）。</p>
 */
public final class WindowsMediaManager {

    private static final Path POS_FILE = Paths.get(
            System.getProperty("java.io.tmpdir"), "media_info.json");

    private static final int MAX_RETRIES = 3;
    /** 读取/解析失败时的重试间隔：短间隔避免轮询线程长时间停滞 */
    private static final long RETRY_DELAY_MS = 5;

    /** 封面独立文件缓存：仅在 thumbHash 变化时重新读盘 + base64 */
    private static volatile String cachedThumbHash = "";
    private static volatile String cachedThumbBase64 = "";

    /** 上次读取的文件指纹（mtime+size）：daemon 未重写时短路复用解析结果 */
    private static volatile String lastFingerprint = "";
    /** 上次解析结果：与指纹配对缓存，命中时零读盘零 JSON 解析 */
    private static volatile MusicInfo lastMusicInfo = null;

    private WindowsMediaManager() {}

    /** 检查 daemon 是否在运行（JSON 文件是否存在） */
    public static boolean isDaemonRunning() {
        return Files.exists(POS_FILE);
    }

    /**
     * 从 daemon 输出文件读取最新媒体信息。
     *
     * @return 解析后的 MusicInfo，若文件不存在或解析失败返回 {@link MusicInfo#EMPTY}
     */
    public static MusicInfo queryMediaInfo() {
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                if (!Files.exists(POS_FILE)) {
                    lastFingerprint = "";
                    lastMusicInfo = null;
                    return MusicInfo.EMPTY;
                }

                // 未变化短路：daemon 只在 SMTC 事件/位置更新时重写文件，
                // 暂停/无会话的空转轮次 mtime+size 不变，直接复用上次的解析结果，
                // 消除 300ms 轮询链路里占绝大多数的重复读盘 + 全量 JSON 解析分配
                String fingerprint = fileFingerprint();
                MusicInfo cached = lastMusicInfo;
                if (fingerprint != null && !fingerprint.isEmpty()
                        && fingerprint.equals(lastFingerprint) && cached != null) {
                    return cached;
                }

                byte[] raw = Files.readAllBytes(POS_FILE);
                if (raw.length == 0) {
                    if (attempt < MAX_RETRIES - 1) {
                        Thread.sleep(RETRY_DELAY_MS);
                        continue;
                    }
                    return MusicInfo.EMPTY;
                }

                String content = new String(raw, StandardCharsets.UTF_8).trim();
                // 剥离 UTF-8 BOM
                if (!content.isEmpty() && content.charAt(0) == '\uFEFF') {
                    content = content.substring(1);
                }
                if (content.isEmpty()) {
                    return MusicInfo.EMPTY;
                }

                JSONObject json = new JSONObject(content);
                MusicInfo info = parseJson(json);
                // 指纹在读盘前采集：若读期间文件被替换，下一轮指纹不一致会重新解析，无脏读风险
                lastFingerprint = fingerprint == null ? "" : fingerprint;
                lastMusicInfo = info;
                return info;
            } catch (org.json.JSONException e) {
                if (attempt < MAX_RETRIES - 1) {
                    try { Thread.sleep(RETRY_DELAY_MS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); break; }
                    continue;
                }
                AppLogger.warn("MusicManager", "JSON 解析失败: " + e.getMessage());
                return MusicInfo.EMPTY;
            } catch (IOException e) {
                if (attempt < MAX_RETRIES - 1) {
                    try { Thread.sleep(RETRY_DELAY_MS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); break; }
                    continue;
                }
                AppLogger.warn("MusicManager", "媒体信息读取失败: " + e.getMessage());
                return MusicInfo.EMPTY;
            } catch (Exception e) {
                AppLogger.warn("MusicManager", "未预期异常: " + e.getMessage());
                return MusicInfo.EMPTY;
            }
        }
        return MusicInfo.EMPTY;
    }

    /** 读文件 mtime+size 组合为指纹；读取失败返回 null（视为已变化，走完整读盘路径） */
    private static String fileFingerprint() {
        try {
            BasicFileAttributes attrs = Files.readAttributes(POS_FILE, BasicFileAttributes.class);
            return attrs.lastModifiedTime().toMillis() + ":" + attrs.size();
        } catch (IOException e) {
            return null;
        }
    }

    private static MusicInfo parseJson(JSONObject json) {
        boolean hasSession = json.optBoolean("hasSession", false);
        // 会话消失时释放封面常驻：cachedThumbBase64 是 MB 级大字符串，
        // 无会话后再也不会被命中，立即清空避免长期驻留 Old 区
        if (!hasSession && !cachedThumbBase64.isEmpty()) {
            cachedThumbHash = "";
            cachedThumbBase64 = "";
        }
        return MusicInfo.builder()
                .hasSession(hasSession)
                .hasMusicProcess(json.optBoolean("hasMusicProcess", false))
                .title(json.optString("title", ""))
                .artist(json.optString("artist", ""))
                .album(json.optString("album", ""))
                .playbackStatus(json.optString("playbackStatus", "Closed"))
                .positionTicks(json.optLong("positionTicks", 0))
                .endTimeTicks(json.optLong("endTimeTicks", 0))
                .sourceAppId(json.optString("sourceAppId", ""))
                .thumbnailBase64(resolveThumbnail(json))
                .playerMinimized(json.optBoolean("isMinimized", false))
                .build();
    }

    /**
     * 解析封面字段：优先兼容旧版内嵌 base64；新版独立文件仅在 hash 变化时读盘。
     * 避免每 300ms 轮询重复解析 MB 级 base64 字符串（实测 17ms/次）。
     */
    private static String resolveThumbnail(JSONObject json) {
        // 1. 旧版协议：JSON 内嵌 base64（升级过渡期兼容）
        String inline = json.optString("thumbnail", "");
        if (!inline.isEmpty()) {
            return inline;
        }

        // 2. 新版协议：thumbFile + thumbHash，hash 未变化时复用缓存
        String thumbFile = json.optString("thumbFile", "");
        String thumbHash = json.optString("thumbHash", "");
        if (thumbFile.isEmpty()) {
            if (!cachedThumbHash.isEmpty()) {
                cachedThumbHash = "";
                cachedThumbBase64 = "";
            }
            return "";
        }
        if (thumbHash.equals(cachedThumbHash)) {
            return cachedThumbBase64;
        }
        try {
            Path thumbPath = Paths.get(System.getProperty("java.io.tmpdir"), thumbFile);
            byte[] data = Files.readAllBytes(thumbPath);
            cachedThumbBase64 = Base64.getEncoder().encodeToString(data);
            cachedThumbHash = thumbHash;
            return cachedThumbBase64;
        } catch (IOException e) {
            // 读盘失败（daemon 正在原子替换）：返回旧缓存，下轮重试
            return cachedThumbBase64;
        }
    }
}
