package com.island.music;

import com.island.config.AppConstants;
import com.island.music.model.LyricItem;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LRU + TTL 歌词/封面二级缓存（内存 L1 → 磁盘 L2）。
 *
 * <p>以 {@code (sourceAppId, title, artist)} 三元组为联合缓存键，
 * 确保不同播放器之间的缓存相互独立。支持独立缓存歌词与封面 URL。</p>
 *
 * <h3>淘汰策略</h3>
 * <ul>
 *   <li><b>LRU</b>：基于 {@link LinkedHashMap} 访问顺序，最大容量 {@value #MAX_ENTRIES} 首</li>
 *   <li><b>TTL</b>：每条记录自创建起 {@value #TTL_MS}ms 后过期</li>
 *   <li>过期条目在访问时惰性淘汰，LRU 触发时同步清理对应磁盘文件</li>
 * </ul>
 *
 * <h3>磁盘持久化</h3>
 * <p>默认写入 {@link AppConstants#CACHE_DIR} 目录；设为空字符串则仅使用内存缓存。
 * 启动时自动预加载磁盘中有效条目到内存，应用重启后无需重复拉取。</p>
 *
 * <p>所有公开方法均为线程安全。</p>
 */
final class LyricsCache {

    /** 最大缓存歌曲数 */
    private static final int MAX_ENTRIES = 50;
    /** 缓存过期时间（毫秒），默认 30 分钟 */
    private static final long TTL_MS = 30 * 60 * 1000;

    private final LinkedHashMap<CacheKey, CacheEntry> map;
    private final Path cacheDir;
    private final boolean diskEnabled;

    LyricsCache() {
        this.map = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<CacheKey, CacheEntry> eldest) {
                if (size() > MAX_ENTRIES) {
                    deleteDiskFile(eldest.getKey());
                    return true;
                }
                return false;
            }
        };

        String dir = AppConstants.getCacheDir();
        if (dir != null && !dir.isBlank()) {
            this.cacheDir = Path.of(dir);
            this.diskEnabled = true;
            try {
                Files.createDirectories(cacheDir);
            } catch (IOException e) {
                System.err.println("[Cache] 无法创建缓存目录: " + cacheDir + " - " + e.getMessage());
            }
            preloadFromDisk();
        } else {
            this.cacheDir = null;
            this.diskEnabled = false;
        }
    }

    // ═══════════════════════════════════════════
    //  L1 内存 → L2 磁盘 查找
    // ═══════════════════════════════════════════

    /**
     * 从缓存中获取歌词行列表（先查内存，再查磁盘）。
     *
     * @return 缓存的歌词列表（防御性拷贝），未命中或已过期返回 {@code null}
     */
    synchronized List<LyricItem> getLyrics(String sourceAppId, String title, String artist) {
        CacheKey key = new CacheKey(sourceAppId, title, artist);
        CacheEntry entry = resolveEntry(key);
        if (entry == null) return null;
        return entry.lyrics.isEmpty() ? null : new ArrayList<>(entry.lyrics);
    }

    /**
     * 从缓存中获取封面 URL（先查内存，再查磁盘）。
     *
     * @return 缓存的封面 URL，未命中或已过期返回 {@code null}
     */
    synchronized String getCoverUrl(String sourceAppId, String title, String artist) {
        CacheKey key = new CacheKey(sourceAppId, title, artist);
        CacheEntry entry = resolveEntry(key);
        if (entry == null) return null;
        return entry.coverUrl.isEmpty() ? null : entry.coverUrl;
    }

    /**
     * 两级解析：先查内存，未命中则从磁盘加载并回填内存。
     * 过期条目同步清理内存 + 磁盘。
     */
    private CacheEntry resolveEntry(CacheKey key) {
        CacheEntry entry = map.get(key);
        if (entry != null) {
            if (isExpired(entry)) {
                map.remove(key);
                deleteDiskFile(key);
                return null;
            }
            return entry;
        }
        // L1 miss → L2 disk
        if (diskEnabled) {
            CacheEntry diskEntry = loadFromDisk(key);
            if (diskEntry != null) {
                if (isExpired(diskEntry)) {
                    deleteDiskFile(key);
                    return null;
                }
                map.put(key, diskEntry);
                return diskEntry;
            }
        }
        return null;
    }

    // ═══════════════════════════════════════════
    //  写入（内存 + 磁盘）
    // ═══════════════════════════════════════════

    /**
     * 将歌词存入缓存。若已有相同键的条目则更新歌词并刷新 TTL；
     * 否则新建条目（封面 URL 留空）。同时持久化到磁盘。
     * <p>磁盘写入在锁外执行（持锁仅覆盖内存更新），避免写盘阻塞并发读。</p>
     */
    void putLyrics(String sourceAppId, String title, String artist,
                   List<LyricItem> lyrics) {
        if (lyrics == null || lyrics.isEmpty()) return;
        CacheKey key = new CacheKey(sourceAppId, title, artist);
        CacheEntry snapshot;
        synchronized (this) {
            CacheEntry existing = map.get(key);
            if (existing != null) {
                existing.lyrics = new ArrayList<>(lyrics);
                existing.createdAt = System.currentTimeMillis();
            } else {
                map.put(key, new CacheEntry(new ArrayList<>(lyrics), "",
                        System.currentTimeMillis()));
            }
            CacheEntry cur = map.get(key);
            snapshot = new CacheEntry(cur.lyrics, cur.coverUrl, cur.createdAt);
        }
        writeToDisk(key, snapshot);
    }

    /**
     * 将封面 URL 存入缓存。若已有相同键的条目则更新封面并刷新 TTL；
     * 否则新建条目（歌词留空）。同时持久化到磁盘。
     * <p>磁盘写入在锁外执行（持锁仅覆盖内存更新），避免写盘阻塞并发读。</p>
     */
    void putCoverUrl(String sourceAppId, String title, String artist,
                     String coverUrl) {
        if (coverUrl == null || coverUrl.isEmpty()) return;
        CacheKey key = new CacheKey(sourceAppId, title, artist);
        CacheEntry snapshot;
        synchronized (this) {
            CacheEntry existing = map.get(key);
            if (existing != null) {
                existing.coverUrl = coverUrl;
                existing.createdAt = System.currentTimeMillis();
            } else {
                map.put(key, new CacheEntry(Collections.emptyList(), coverUrl,
                        System.currentTimeMillis()));
            }
            CacheEntry cur = map.get(key);
            snapshot = new CacheEntry(cur.lyrics, cur.coverUrl, cur.createdAt);
        }
        writeToDisk(key, snapshot);
    }

    /** 清空内存缓存（磁盘不受影响，下次请求会从磁盘 L2 回填）。 */
    synchronized void clear() {
        map.clear();
    }

    // ═══════════════════════════════════════════
    //  磁盘 I/O
    // ═══════════════════════════════════════════

    /** 启动时从磁盘预加载有效条目到内存，过期/超额文件自动清理。 */
    private void preloadFromDisk() {
        if (!diskEnabled || cacheDir == null) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(cacheDir, "*.json")) {
            List<Path> files = new ArrayList<>();
            for (Path p : stream) files.add(p);
            files.sort((a, b) -> Long.compare(
                    b.toFile().lastModified(), a.toFile().lastModified()));

            for (Path file : files) {
                if (map.size() >= MAX_ENTRIES) {
                    try { Files.delete(file); } catch (IOException ignored) {}
                    continue;
                }
                try {
                    String content = Files.readString(file, StandardCharsets.UTF_8);
                    JSONObject json = new JSONObject(content);
                    long createdAt = json.optLong("createdAt", 0);
                    if (System.currentTimeMillis() - createdAt > TTL_MS) {
                        Files.delete(file);
                        continue;
                    }
                    CacheKey key = new CacheKey(
                            json.optString("sourceAppId", ""),
                            json.optString("title", ""),
                            json.optString("artist", ""));
                    List<LyricItem> lyrics = new ArrayList<>();
                    JSONArray arr = json.optJSONArray("lyrics");
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject li = arr.getJSONObject(i);
                            lyrics.add(new LyricItem(li.getLong("t"), li.getString("c")));
                        }
                    }
                    String coverUrl = json.optString("coverUrl", "");
                    map.put(key, new CacheEntry(lyrics, coverUrl, createdAt));
                } catch (Exception e) {
                    try { Files.delete(file); } catch (IOException ignored) {}
                }
            }
        } catch (IOException e) {
            System.err.println("[Cache] 磁盘预加载失败: " + e.getMessage());
        }
    }

    private CacheEntry loadFromDisk(CacheKey key) {
        if (!diskEnabled || cacheDir == null) return null;
        Path file = cacheDir.resolve(hashKey(key) + ".json");
        if (!Files.exists(file)) return null;
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(content);
            long createdAt = json.optLong("createdAt", 0);
            List<LyricItem> lyrics = new ArrayList<>();
            JSONArray arr = json.optJSONArray("lyrics");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject li = arr.getJSONObject(i);
                    lyrics.add(new LyricItem(li.getLong("t"), li.getString("c")));
                }
            }
            String coverUrl = json.optString("coverUrl", "");
            return new CacheEntry(lyrics, coverUrl, createdAt);
        } catch (Exception e) {
            try { Files.delete(file); } catch (IOException ignored) {}
            return null;
        }
    }

    /** 原子写入：先写临时文件，再重命名为正式文件。 */
    private void writeToDisk(CacheKey key, CacheEntry entry) {
        if (!diskEnabled || cacheDir == null || entry == null) return;
        try {
            JSONObject json = toJson(key, entry);
            Path tmp = cacheDir.resolve(hashKey(key) + ".tmp");
            Path target = cacheDir.resolve(hashKey(key) + ".json");
            Files.writeString(tmp, json.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            System.err.println("[Cache] 磁盘写入失败: " + e.getMessage());
        }
    }

    private void deleteDiskFile(CacheKey key) {
        if (!diskEnabled || cacheDir == null) return;
        try {
            Path target = cacheDir.resolve(hashKey(key) + ".json");
            Files.deleteIfExists(target);
            Path tmp = cacheDir.resolve(hashKey(key) + ".tmp");
            Files.deleteIfExists(tmp);
        } catch (IOException ignored) {}
    }

    // ── JSON 序列化 ──

    private static JSONObject toJson(CacheKey key, CacheEntry entry) {
        JSONObject json = new JSONObject();
        json.put("sourceAppId", key.sourceAppId());
        json.put("title", key.title());
        json.put("artist", key.artist());
        JSONArray arr = new JSONArray();
        for (LyricItem item : entry.lyrics) {
            JSONObject li = new JSONObject();
            li.put("t", item.startTime);
            li.put("c", item.content);
            arr.put(li);
        }
        json.put("lyrics", arr);
        json.put("coverUrl", entry.coverUrl != null ? entry.coverUrl : "");
        json.put("createdAt", entry.createdAt);
        return json;
    }

    // ── 文件名散列 ──

    private static String hashKey(CacheKey key) {
        String raw = key.sourceAppId() + "|" + key.title() + "|" + key.artist();
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return raw.replaceAll("[^a-zA-Z0-9._-]", "_");
        }
    }

    // ── TTL ──

    private static boolean isExpired(CacheEntry entry) {
        return System.currentTimeMillis() - entry.createdAt > TTL_MS;
    }

    // ═══════════════════════════════════════════
    //  内部类型
    // ═══════════════════════════════════════════

    /**
     * 缓存键：播放器来源 + 歌曲唯一标识（曲目标题 + 艺术家）。
     * 键值经过标准化（trim + lowercase），提高命中率。
     */
    private record CacheKey(String sourceAppId, String title, String artist) {
        CacheKey {
            sourceAppId = norm(sourceAppId);
            title = norm(title);
            artist = norm(artist);
        }

        private static String norm(String s) {
            return s == null ? "" : s.trim().toLowerCase();
        }
    }

    /** 缓存条目：歌词列表 + 封面 URL + 创建时间戳。 */
    private static final class CacheEntry {
        List<LyricItem> lyrics;
        String coverUrl;
        long createdAt;

        CacheEntry(List<LyricItem> lyrics, String coverUrl, long createdAt) {
            this.lyrics = lyrics;
            this.coverUrl = coverUrl;
            this.createdAt = createdAt;
        }
    }
}
