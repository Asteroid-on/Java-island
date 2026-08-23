package com.island.update;

import com.island.config.AppConfig;
import com.island.config.AppVersion;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 更新检测器：查询 GitHub Releases API 获取最新版本信息。
 * 为阻塞方法，调用方必须在后台线程执行（避免阻塞 EDT）。
 */
public final class UpdateChecker {

    /** 目标仓库：优先级 系统属性 update.repo → config.properties → 默认仓库 */
    private static final String REPO = AppConfig.get("update.repo", "Asteroid-on/Java-island");
    private static final String API_URL =
            "https://api.github.com/repos/" + REPO + "/releases/latest";

    /** 共享 HTTP 客户端（避免每次请求新建连接池/线程） */
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    /** 最新版本信息（不可变值对象）。 */
    public record UpdateInfo(String version, String name, String body,
                             String assetUrl, String assetName, long assetSize) {
    }

    private UpdateChecker() {
        // 工具类，禁止实例化
    }

    /** GitHub Releases 页面地址（浏览器访问，API 检查不可用时的手动兜底路径）。 */
    public static String releasesPageUrl() {
        return "https://github.com/" + REPO + "/releases";
    }

    /**
     * 查询最新 Release 及其 zip 便携包附件。
     *
     * @return 最新版本信息，仓库无 Release 时返回 {@code null}
     * @throws IOException          网络错误、非 200 响应或数据缺失
     * @throws InterruptedException 等待被中断
     */
    public static UpdateInfo checkLatest() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Java-island-Updater/" + AppVersion.CURRENT)
                .GET().build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("GitHub API 响应异常: HTTP " + response.statusCode());
        }
        return parseResponse(response.body());
    }

    /**
     * 解析 GitHub Releases API 的 JSON 响应（包内可见，便于测试）。
     *
     * @throws IOException 数据缺失或未找到 zip 附件
     */
    static UpdateInfo parseResponse(String jsonBody) throws IOException {
        JSONObject json = new JSONObject(jsonBody);
        String tag = json.optString("tag_name", "");
        if (tag.isEmpty()) {
            throw new IOException("Release 数据缺少 tag_name");
        }

        // 挑选 zip 便携包附件（多个 zip 时优先名字含 portable 的）
        JSONArray assets = json.optJSONArray("assets");
        JSONObject zipAsset = null;
        for (int i = 0; assets != null && i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            String name = asset.optString("name", "");
            if (!name.toLowerCase().endsWith(".zip")) continue;
            if (zipAsset == null || name.toLowerCase().contains("portable")) {
                zipAsset = asset;
            }
        }
        if (zipAsset == null) {
            throw new IOException("Release 未找到 zip 便携包附件");
        }

        String version = stripV(tag);
        return new UpdateInfo(
                version,
                json.optString("name", ""),
                json.optString("body", ""),
                zipAsset.optString("browser_download_url", ""),
                zipAsset.optString("name", ""),
                zipAsset.optLong("size", 0));
    }

    /** 去除版本号前导 "v/V"（仅当后一位是数字）。 */
    private static String stripV(String tag) {
        String t = tag.trim();
        if (t.length() > 1 && (t.charAt(0) == 'v' || t.charAt(0) == 'V')
                && Character.isDigit(t.charAt(1))) {
            return t.substring(1);
        }
        return t;
    }
}
