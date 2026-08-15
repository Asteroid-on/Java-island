package com.island.music;

import com.island.config.AppConstants;
import com.island.music.model.LyricItem;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.prefs.Preferences;

/**
 * LyricsCache 磁盘 I/O 性能基准（同包，可访问包级 LyricsCache）。
 *
 * 覆盖：写入/读取、coverUrl、clear 后磁盘回填、TTL 过期、LRU 淘汰、并发访问。
 * 不修改业务代码，仅测量。
 */
public class LyricsCachePerfTest {

    private static final Path TEMP_DIR = Path.of(System.getProperty("java.io.tmpdir"), "lyrics-cache-perf");

    public static void main(String[] args) throws Exception {
        System.out.println("=== LyricsCache I/O 性能基准 ===");

        // 保存用户原始缓存目录设置，测试结束后恢复，避免污染真实缓存/用户配置
        final String savedPref = Preferences.userNodeForPackage(AppConstants.class)
                .get("cache.dir", null);
        Path isoCache = TEMP_DIR.resolve("iso-cache").resolve("lyrics");
        AppConstants.setCacheDir(isoCache.toString());

        try {
            run(isoCache);
        } finally {
            if (savedPref == null) AppConstants.setCacheDir("");
            else AppConstants.setCacheDir(savedPref);
            System.out.println("[PERF] 已恢复用户缓存目录设置: " + (savedPref == null ? "默认" : savedPref));
        }
    }

    private static void run(Path isoCache) throws Exception {
        // 使用独立临时目录，避免污染真实缓存
        cleanupDir();
        Files.createDirectories(isoCache);

        LyricsCache cache = new LyricsCache();

        // ── 1. putLyrics 写入延迟（含磁盘原子写）──
        List<Double> putSamples = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            List<LyricItem> items = fakeLyrics(50 + (i % 3) * 40);
            long t0 = System.nanoTime();
            cache.putLyrics("src" + (i % 3), "歌曲标题" + i, "歌手" + i, items);
            putSamples.add((System.nanoTime() - t0) / 1e6);
        }
        System.out.println("[PERF] putLyrics(50~130行, 写盘+原子rename) " + stat(putSamples));

        // ── 2. 内存命中读取 ──
        List<Double> hitSamples = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            long t0 = System.nanoTime();
            cache.getLyrics("src0", "歌曲标题5", "歌手5");
            hitSamples.add((System.nanoTime() - t0) / 1e6);
        }
        System.out.println("[PERF] getLyrics L1内存命中(2000次)       " + stat(hitSamples));

        // ── 3. coverUrl 读写 ──
        List<Double> putCov = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            long t0 = System.nanoTime();
            cache.putCoverUrl("src0", "歌曲标题" + i, "歌手" + i, "https://cover.example/" + i + ".jpg");
            putCov.add((System.nanoTime() - t0) / 1e6);
        }
        System.out.println("[PERF] putCoverUrl(写盘)                  " + stat(putCov));

        List<Double> getCov = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            long t0 = System.nanoTime();
            cache.getCoverUrl("src0", "歌曲标题7", "歌手7");
            getCov.add((System.nanoTime() - t0) / 1e6);
        }
        System.out.println("[PERF] getCoverUrl L1命中(2000次)         " + stat(getCov));

        // ── 4. clear 后磁盘回填（L2 miss→load→回填内存）──
        cache.clear();
        List<Double> backfill = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            cache.clear();
            long t0 = System.nanoTime();
            cache.getLyrics("src1", "歌曲标题20", "歌手20");
            backfill.add((System.nanoTime() - t0) / 1e6);
        }
        System.out.println("[PERF] clear()后L2磁盘回填(200次)        " + stat(backfill));

        // ── 5. LRU 淘汰：写入 51 首（MAX=50），观察淘汰时的删除 I/O ──
        List<Double> lruSamples = new ArrayList<>();
        for (int round = 0; round < 10; round++) {
            for (int i = 0; i < 5; i++) {
                long t0 = System.nanoTime();
                cache.putLyrics("lru", "溢出歌" + (round * 5 + i), "歌手", fakeLyrics(40));
                lruSamples.add((System.nanoTime() - t0) / 1e6);
            }
        }
        System.out.println("[PERF] LRU淘汰触发的put(写盘+删旧文件)   " + stat(lruSamples));

        // ── 6. TTL 过期：手工构造过期文件，验证惰性清理路径耗时 ──
        Path cacheDir = isoCache;
        String expiredHash = sha256("expired|过期歌曲|过期歌手");
        Path expiredFile = cacheDir.resolve(expiredHash + ".json");
        String expiredJson = "{\"sourceAppId\":\"expired\",\"title\":\"过期歌曲\",\"artist\":\"过期歌手\"," +
                "\"lyrics\":[{\"t\":1000,\"c\":\"旧歌词\"}],\"coverUrl\":\"\",\"createdAt\":" +
                (System.currentTimeMillis() - 31L * 60 * 1000) + "}";
        Files.writeString(expiredFile, expiredJson, StandardCharsets.UTF_8);

        List<Double> ttlSamples = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            Files.writeString(expiredFile, expiredJson, StandardCharsets.UTF_8);
            long t0 = System.nanoTime();
            List<LyricItem> r = cache.getLyrics("expired", "过期歌曲", "过期歌手");
            ttlSamples.add((System.nanoTime() - t0) / 1e6);
            if (r != null) throw new AssertionError("过期条目应返回 null");
        }
        System.out.println("[PERF] TTL过期读取(解析+删除磁盘文件)     " + stat(ttlSamples));
        if (Files.exists(expiredFile)) throw new AssertionError("过期文件应被删除");

        // ── 7. 并发读写（8 线程 × 500 次混合操作）──
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicLong errors = new AtomicLong();
        List<Double> concSamples = new ArrayList<>();
        Object concLock = new Object();
        int threads = 8, ops = 500;
        for (int t = 0; t < threads; t++) {
            final int tid = t;
            pool.submit(() -> {
                try { start.await(); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                for (int i = 0; i < ops; i++) {
                    // 每 4 个操作共享同一键：先写后读，保证读命中（验证并发安全与吞吐）
                    String title = "并发歌" + (tid * 31 + (i / 4) % 20);
                    long t0 = System.nanoTime();
                    try {
                        if (i % 4 == 0) {
                            cache.putLyrics("con", title, "歌手", fakeLyrics(30));
                        } else if (i % 4 == 1) {
                            cache.putCoverUrl("con", title, "歌手", "https://c/" + title);
                        } else if (i % 4 == 2) {
                            List<LyricItem> r = cache.getLyrics("con", title, "歌手");
                            if (r == null) throw new AssertionError("并发读未命中");
                        } else {
                            String c = cache.getCoverUrl("con", title, "歌手");
                            if (c == null) throw new AssertionError("并发读封面未命中");
                        }
                    } catch (Throwable e) {
                        errors.incrementAndGet();
                    }
                    synchronized (concLock) {
                        concSamples.add((System.nanoTime() - t0) / 1e6);
                    }
                }
            });
        }
        long c0 = System.nanoTime();
        start.countDown();
        pool.shutdown();
        pool.awaitTermination(120, TimeUnit.SECONDS);
        long totalMs = (System.nanoTime() - c0) / 1_000_000;
        System.out.println("[PERF] 并发混合读写(8线程×500op)          " + stat(concSamples));
        System.out.printf("[PERF]   总耗时=%dms 吞吐=%.0f op/s 错误=%d%n",
                totalMs, (double) (threads * ops) * 1000 / totalMs, errors.get());

        // ── 8. 启动预加载：50 个有效 + 10 个过期 + 10 个超量文件 ──
        cleanupDir();
        Files.createDirectories(isoCache);
        Path preDir = isoCache;
        Files.createDirectories(preDir);
        long now = System.currentTimeMillis();
        for (int i = 0; i < 50; i++) {
            writeJson(preDir, "preload", "预加载歌" + i, "歌手", now - i * 1000, false);
        }
        for (int i = 0; i < 10; i++) {
            writeJson(preDir, "preload", "已过期歌" + i, "歌手", now - 40L * 60 * 1000, false);
        }
        for (int i = 0; i < 10; i++) {
            writeJson(preDir, "preload", "超量歌" + i, "歌手", now, false);
        }
        long p0 = System.nanoTime();
        LyricsCache cache2 = new LyricsCache();
        long preloadMs = (System.nanoTime() - p0) / 1_000_000;
        int filesAfter = 0;
        try (var s = Files.newDirectoryStream(preDir, "*.json")) { for (Path p : s) filesAfter++; }
        List<LyricItem> hit = cache2.getLyrics("preload", "预加载歌49", "歌手");
        List<LyricItem> hitOldest = cache2.getLyrics("preload", "预加载歌0", "歌手");
        System.out.printf("[PERF] 启动预加载70文件: 耗时=%.1fms 加载后磁盘文件数=%d (过期/超量已清理) 最新命中=%s 最旧(应被清理)=%s%n",
                (double) preloadMs, filesAfter, hit != null, hitOldest == null);

        cleanupDir();
        System.out.println("\n=== LyricsCache 基准完成 ===");
    }

    private static List<LyricItem> fakeLyrics(int n) {
        List<LyricItem> items = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            items.add(new LyricItem(i * 3000L, "这是第" + i + "行测试歌词内容"));
        }
        return items;
    }

    private static void writeJson(Path dir, String src, String title, String artist, long createdAt, boolean bad) {
        try {
            String json = "{\"sourceAppId\":\"" + src + "\",\"title\":\"" + title + "\",\"artist\":\"" + artist
                    + "\",\"lyrics\":[{\"t\":0,\"c\":\"x\"}],\"coverUrl\":\"\",\"createdAt\":" + createdAt + "}";
            String hash = sha256(src + "|" + title + "|" + artist);
            Files.writeString(dir.resolve(hash + ".json"), json, StandardCharsets.UTF_8);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static String sha256(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static String stat(List<Double> s) {
        var sorted = new ArrayList<>(s);
        java.util.Collections.sort(sorted);
        int n = sorted.size();
        double avg = sorted.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        return String.format(java.util.Locale.ROOT,
                "n=%d avg=%.3fms p50=%.3fms p95=%.3fms p99=%.3fms max=%.3fms",
                n, avg, sorted.get((int) (n * 0.5)), sorted.get((int) (n * 0.95)),
                sorted.get((int) (n * 0.99)), sorted.get(n - 1));
    }

    private static void cleanupDir() {
        try {
            Path dir = TEMP_DIR;
            if (Files.exists(dir)) {
                try (var walk = Files.walk(dir)) {
                    walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                    });
                }
            }
            Files.createDirectories(dir);
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
