package com.island.update;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 更新包解压器：将便携包 zip 解压到目标目录，带字节进度回调。
 * 安全防护：跳过路径穿越条目；失败/取消时清理半成品目录。
 * 为阻塞方法，调用方必须在后台线程执行；进度回调发生在解压线程，须自行切回 EDT。
 */
public final class UpdateExtractor {

    /** 解压进度监听：extracted/total 为字节数，total<=0 表示总大小未知。 */
    public interface ProgressListener {
        void onProgress(long extracted, long total);
    }

    private static final int BUFFER_SIZE = 64 * 1024;

    /** 进度回调节流阈值：每解压 512KB 至少回调一次。 */
    private static final long REPORT_STEP = 512 * 1024;

    private UpdateExtractor() {
        // 工具类，禁止实例化
    }

    /**
     * 解压 zip 到目标目录（目录必须不存在，由本方法创建）。
     *
     * @param zipFile  zip 便携包
     * @param destDir  解压目标目录
     * @param listener 进度回调（可为 null）
     * @param cancel   取消标志；置 true 后尽快中断并清理半成品目录
     * @throws IOException 解压失败或用户取消
     */
    public static void extract(File zipFile, Path destDir,
                               ProgressListener listener, AtomicBoolean cancel)
            throws IOException {
        if (Files.exists(destDir)) {
            throw new IOException("目标目录已存在: " + destDir);
        }
        Files.createDirectories(destDir);

        try (ZipFile zip = new ZipFile(zipFile)) {
            // 预扫描总解压大小（条目 size 未知时按 0 计，进度条回退为不定态）
            long total = 0;
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                long size = entries.nextElement().getSize();
                if (size > 0) total += size;
            }

            long extracted = 0;
            long lastReport = 0;
            Enumeration<? extends ZipEntry> all = zip.entries();
            while (all.hasMoreElements()) {
                if (cancel.get()) {
                    throw new IOException("解压已取消");
                }
                ZipEntry entry = all.nextElement();
                Path out = destDir.resolve(entry.getName()).normalize();
                if (!out.startsWith(destDir)) {
                    continue; // 路径穿越防护：跳过 ".." 等恶意条目
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                    continue;
                }
                Files.createDirectories(out.getParent());
                try (InputStream in = zip.getInputStream(entry);
                     OutputStream os = Files.newOutputStream(out)) {
                    byte[] buf = new byte[BUFFER_SIZE];
                    int n;
                    while ((n = in.read(buf)) >= 0) {
                        if (cancel.get()) {
                            throw new IOException("解压已取消");
                        }
                        os.write(buf, 0, n);
                        extracted += n;
                        if (listener != null && extracted - lastReport >= REPORT_STEP) {
                            lastReport = extracted;
                            listener.onProgress(extracted, total);
                        }
                    }
                }
            }
            if (listener != null) listener.onProgress(extracted, total);
        } catch (IOException | RuntimeException e) {
            // 失败/取消：清理半成品目录（尽力而为）
            try {
                deleteRecursively(destDir.toFile());
            } catch (Exception ignored) {
            }
            throw e;
        }
    }

    /** 递归删除目录（含内容），失败静默忽略。 */
    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }
}
