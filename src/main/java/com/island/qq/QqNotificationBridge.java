package com.island.qq;

import com.island.util.AppLogger;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Java ↔ QqNotifyDaemon 桥接层。
 *
 * <p>QqNotifyDaemon 通过原子写入（tmp + rename）持续将最新 QQ 通知写入
 * {@code %TEMP%/qq_notify.json}，本类负责读取解析为 {@link QqNotification}。
 * QQ 图标使用 Java 内置资源 {@code icons/qq.png}，不经 daemon 传输。</p>
 */
public final class QqNotificationBridge {

    private static final Path POS_FILE = Paths.get(
            System.getProperty("java.io.tmpdir"), "qq_notify.json");

    private static final int MAX_RETRIES = 3;
    /** 读取/解析失败时的重试间隔（daemon 正在原子替换文件时的短暂窗口） */
    private static final long RETRY_DELAY_MS = 5;

    private QqNotificationBridge() {}

    /** 检查 daemon 是否已输出过状态文件 */
    public static boolean isDaemonRunning() {
        return Files.exists(POS_FILE);
    }

    /**
     * 从 daemon 输出文件读取最新 QQ 通知状态。
     *
     * @return 解析结果；文件不存在或解析失败返回 {@link QqNotification#EMPTY}
     */
    public static QqNotification query() {
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                if (!Files.exists(POS_FILE)) {
                    return QqNotification.EMPTY;
                }
                byte[] raw = Files.readAllBytes(POS_FILE);
                if (raw.length == 0) {
                    if (attempt < MAX_RETRIES - 1) {
                        Thread.sleep(RETRY_DELAY_MS);
                        continue;
                    }
                    return QqNotification.EMPTY;
                }
                String content = new String(raw, StandardCharsets.UTF_8).trim();
                // 剥离 UTF-8 BOM
                if (!content.isEmpty() && content.charAt(0) == '\uFEFF') {
                    content = content.substring(1);
                }
                if (content.isEmpty()) {
                    return QqNotification.EMPTY;
                }
                JSONObject json = new JSONObject(content);
                return QqNotification.builder()
                        .valid(true)
                        .seq(json.optLong("seq", -1))
                        .appId(json.optString("appId", ""))
                        .sender(json.optString("sender", ""))
                        .content(json.optString("content", ""))
                        .groupName(json.optString("groupName", ""))
                        .timestamp(json.optLong("timestamp", 0))
                        .build();
            } catch (org.json.JSONException e) {
                if (attempt < MAX_RETRIES - 1) {
                    try { Thread.sleep(RETRY_DELAY_MS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                    continue;
                }
                AppLogger.warn("QqBridge", "JSON 解析失败: " + e.getMessage());
                return QqNotification.EMPTY;
            } catch (IOException e) {
                if (attempt < MAX_RETRIES - 1) {
                    try { Thread.sleep(RETRY_DELAY_MS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                    continue;
                }
                AppLogger.warn("QqBridge", "状态文件读取失败: " + e.getMessage());
                return QqNotification.EMPTY;
            } catch (Exception e) {
                AppLogger.warn("QqBridge", "未预期异常: " + e.getMessage());
                return QqNotification.EMPTY;
            }
        }
        return QqNotification.EMPTY;
    }
}
