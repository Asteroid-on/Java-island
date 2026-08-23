package com.island.update;

import com.island.config.AppVersion;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 更新包下载器：下载 GitHub Release 的 zip 便携包到本地。
 * 先写入 .part 临时文件，完成后原子重命名，失败/取消时清理临时文件，避免残留半截包。
 * 为阻塞方法，调用方必须在后台线程执行；进度回调发生在下载线程，须自行切回 EDT。
 */
public final class UpdateDownloader {

    /** 下载进度监听：downloaded/total 为字节数，total<=0 表示总大小未知。 */
    public interface ProgressListener {
        void onProgress(long downloaded, long total);
    }

    /** 跟随重定向：GitHub 资产 URL 会 302 到对象存储。 */
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15)).build();

    private static final int BUFFER_SIZE = 64 * 1024;

    /** 进度回调节流阈值：每下载 512KB 至少回调一次。 */
    private static final long REPORT_STEP = 512 * 1024;

    private UpdateDownloader() {
        // 工具类，禁止实例化
    }

    /** 默认下载目录：优先用户 Downloads 文件夹，缺失时回退用户主目录。 */
    public static Path downloadDir() {
        Path downloads = Path.of(System.getProperty("user.home"), "Downloads");
        return Files.isDirectory(downloads) ? downloads : Path.of(System.getProperty("user.home"));
    }

    /**
     * 下载更新包到指定目录（不存在时自动创建）。
     *
     * @param url      资产下载地址
     * @param fileName 保存文件名
     * @param saveDir  保存目录
     * @param listener 进度回调（可为 null）
     * @param cancel   取消标志；置 true 后尽快中断并清理临时文件
     * @return 下载完成的文件
     * @throws IOException          网络错误或用户取消
     * @throws InterruptedException 等待被中断
     */
    public static File download(String url, String fileName, Path saveDir,
                                ProgressListener listener, AtomicBoolean cancel)
            throws IOException, InterruptedException {
        Files.createDirectories(saveDir);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofHours(2))
                .header("User-Agent", "Java-island-Updater/" + AppVersion.CURRENT)
                .GET().build();
        HttpResponse<InputStream> response =
                HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IOException("下载失败: HTTP " + response.statusCode());
        }
        long total = response.headers().firstValueAsLong("Content-Length").orElse(-1);

        File target = saveDir.resolve(fileName).toFile();
        File part = new File(target.getParentFile(), target.getName() + ".part");
        long downloaded = 0;
        long lastReport = 0;
        boolean completed = false;
        try (InputStream in = new BufferedInputStream(response.body(), BUFFER_SIZE);
             OutputStream out = new BufferedOutputStream(
                     Files.newOutputStream(part.toPath()), BUFFER_SIZE)) {
            byte[] buf = new byte[BUFFER_SIZE];
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (cancel.get()) {
                    throw new IOException("下载已取消");
                }
                out.write(buf, 0, n);
                downloaded += n;
                if (listener != null && (downloaded - lastReport >= REPORT_STEP
                        || (total > 0 && downloaded >= total))) {
                    lastReport = downloaded;
                    listener.onProgress(downloaded, total);
                }
            }
            completed = true;
        } finally {
            if (!completed) {
                try {
                    Files.deleteIfExists(part.toPath());
                } catch (IOException ignored) {
                }
            }
        }
        Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        if (listener != null) listener.onProgress(downloaded, total);
        return target;
    }
}
