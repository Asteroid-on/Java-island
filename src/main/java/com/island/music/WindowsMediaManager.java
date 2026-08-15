package com.island.music;

import com.island.music.model.MusicInfo;
import com.island.util.AppLogger;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
                    return MusicInfo.EMPTY;
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
                return parseJson(json);
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

    private static MusicInfo parseJson(JSONObject json) {
        return MusicInfo.builder()
                .hasSession(json.optBoolean("hasSession", false))
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
