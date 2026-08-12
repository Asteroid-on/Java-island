package com.island.music;

import com.island.music.model.MusicInfo;
import com.island.util.AppLogger;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Java ↔ .NET 8 MediaInfoDaemon 桥接层。
 *
 * <p>MediaInfoDaemon 通过原子写入（tmp + rename）持续将 SMTC 媒体信息写入
 * {@code %TEMP%/media_info.json}，本类负责读取并解析为 {@link MusicInfo}。</p>
 */
public final class WindowsMediaManager {

    private static final Path POS_FILE = Paths.get(
            System.getProperty("java.io.tmpdir"), "media_info.json");

    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 50;

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
                .thumbnailBase64(json.optString("thumbnail", ""))
                .build();
    }
}
