package com.island.wechat;

import com.island.util.AppLogger;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Java ↔ WechatNotifyDaemon 桥接层。
 *
 * <p>WechatNotifyDaemon 通过原子写入（tmp + rename）持续将最新微信通知写入
 * {@code %TEMP%/wechat_notify.json}，本类负责读取解析为 {@link WechatNotification}。
 * 微信图标使用 Java 内置资源 {@code icons/wechat.png}，不经 daemon 传输。</p>
 */
public final class WechatNotificationBridge {

    private static final Path POS_FILE = Paths.get(
            System.getProperty("java.io.tmpdir"), "wechat_notify.json");

    private static final int MAX_RETRIES = 3;
    /** 读取/解析失败时的重试间隔（daemon 正在原子替换文件时的短暂窗口） */
    private static final long RETRY_DELAY_MS = 5;

    private WechatNotificationBridge() {}

    /** 检查 daemon 是否已输出过状态文件 */
    public static boolean isDaemonRunning() {
        return Files.exists(POS_FILE);
    }

    /**
     * 从 daemon 输出文件读取最新微信通知状态。
     *
     * @return 解析结果；文件不存在或解析失败返回 {@link WechatNotification#EMPTY}
     */
    public static WechatNotification query() {
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                if (!Files.exists(POS_FILE)) {
                    return WechatNotification.EMPTY;
                }
                byte[] raw = Files.readAllBytes(POS_FILE);
                if (raw.length == 0) {
                    if (attempt < MAX_RETRIES - 1) {
                        Thread.sleep(RETRY_DELAY_MS);
                        continue;
                    }
                    return WechatNotification.EMPTY;
                }
                String content = new String(raw, StandardCharsets.UTF_8).trim();
                // 剥离 UTF-8 BOM
                if (!content.isEmpty() && content.charAt(0) == '\uFEFF') {
                    content = content.substring(1);
                }
                if (content.isEmpty()) {
                    return WechatNotification.EMPTY;
                }
                JSONObject json = new JSONObject(content);
                return WechatNotification.builder()
                        .valid(true)
                        .seq(json.optLong("seq", -1))
                        .appId(json.optString("appId", ""))
                        .title(json.optString("title", ""))
                        .body(json.optString("body", ""))
                        .wechatExe(json.optString("wechatExe", ""))
                        .timestamp(json.optLong("timestamp", 0))
                        .build();
            } catch (org.json.JSONException e) {
                if (attempt < MAX_RETRIES - 1) {
                    try { Thread.sleep(RETRY_DELAY_MS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                    continue;
                }
                AppLogger.warn("WechatBridge", "JSON 解析失败: " + e.getMessage());
                return WechatNotification.EMPTY;
            } catch (IOException e) {
                if (attempt < MAX_RETRIES - 1) {
                    try { Thread.sleep(RETRY_DELAY_MS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                    continue;
                }
                AppLogger.warn("WechatBridge", "状态文件读取失败: " + e.getMessage());
                return WechatNotification.EMPTY;
            } catch (Exception e) {
                AppLogger.warn("WechatBridge", "未预期异常: " + e.getMessage());
                return WechatNotification.EMPTY;
            }
        }
        return WechatNotification.EMPTY;
    }
}
