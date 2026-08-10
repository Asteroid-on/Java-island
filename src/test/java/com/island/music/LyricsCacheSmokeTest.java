package com.island.music;

import com.island.music.model.LyricItem;
import java.util.List;

/**
 * 烟雾测试：验证 LyricsCache 核心行为。
 * <pre>
 * 用法：
 *   cd D:\Java-island\Java-island
 *   $env:JAVA_HOME="D:\Program Files\Java\jdk-25.0.3"
 *   & "$env:JAVA_HOME\bin\javac" -encoding UTF-8 -cp "target\classes;lib\*" -d target\test-classes src\test\java\com\island\music\LyricsCacheSmokeTest.java
 *   & "$env:JAVA_HOME\bin\java" -cp "target\classes;target\test-classes;lib\*" com.island.music.LyricsCacheSmokeTest
 * </pre>
 */
public class LyricsCacheSmokeTest {

    private static int passed, failed;

    public static void main(String[] args) {
        System.out.println("=== LyricsCache 烟雾测试 ===\n");

        LyricsCache cache = new LyricsCache();

        // ── 1. 写入 + 读取 ──
        test("1. 写入歌词后读取命中", () -> {
            var items = List.of(
                    new LyricItem(1000, "hello"),
                    new LyricItem(5000, "world"));
            cache.putLyrics("cloudmusic", "晴天", "周杰伦", items);
            var result = cache.getLyrics("cloudmusic", "晴天", "周杰伦");
            check(result != null && result.size() == 2,
                    "期望 2 行，实际 " + (result == null ? "null" : result.size()));
        });

        test("2. 写入封面后读取命中", () -> {
            cache.putCoverUrl("cloudmusic", "晴天", "周杰伦", "https://cover.example.com/art.jpg");
            var result = cache.getCoverUrl("cloudmusic", "晴天", "周杰伦");
            check("https://cover.example.com/art.jpg".equals(result),
                    "期望 cover URL，实际 " + result);
        });

        // ── 2. 跨播放器隔离 ──
        test("3. 不同播放器缓存隔离", () -> {
            var result = cache.getLyrics("qqmusic", "晴天", "周杰伦");
            check(result == null, "QQ音乐不应命中网易云的缓存");
        });

        // ── 3. 标准化键命中 ──
        test("4. 大小写/空白标准化后命中", () -> {
            var result = cache.getLyrics("CLOUDMUSIC", " 晴天 ", " 周杰伦 ");
            check(result != null && result.size() == 2, "标准化后应命中");
        });

        test("5. 封面大小写标准化后命中", () -> {
            var result = cache.getCoverUrl("CLOUDMUSIC", " 晴天 ", " 周杰伦 ");
            check("https://cover.example.com/art.jpg".equals(result),
                    "封面标准化后应命中，实际 " + result);
        });

        // ── 4. L2 磁盘回填 ──
        test("6. 清内存后从磁盘回填", () -> {
            cache.clear();
            var result = cache.getLyrics("cloudmusic", "晴天", "周杰伦");
            check(result != null && result.size() == 2,
                    "clear() 后应能从磁盘重新加载");
        });

        test("7. 清内存后封面从磁盘回填", () -> {
            var result = cache.getCoverUrl("cloudmusic", "晴天", "周杰伦");
            check("https://cover.example.com/art.jpg".equals(result),
                    "clear() 后封面应能从磁盘重新加载");
        });

        // ── 5. 未命中返回 null ──
        test("8. 未知歌曲返回 null", () -> {
            var result = cache.getLyrics("cloudmusic", "不存在", "不存在");
            check(result == null, "未知歌曲应为 null");
        });

        test("9. 未知封面返回 null", () -> {
            var result = cache.getCoverUrl("cloudmusic", "不存在", "不存在");
            check(result == null, "未知封面应为 null");
        });

        // ── 6. 独立缓存更新 ──
        test("10. 先歌词后封面合并更新", () -> {
            cache.putLyrics("netease", "七里香", "周杰伦",
                    List.of(new LyricItem(0, "窗外的麻雀")));
            // 此时封面应为空
            var cov1 = cache.getCoverUrl("netease", "七里香", "周杰伦");
            check(cov1 == null, "仅写歌词时封面应为 null");

            cache.putCoverUrl("netease", "七里香", "周杰伦",
                    "https://cover.example.com/qilixiang.jpg");
            // 歌词应仍存在
            var lyr = cache.getLyrics("netease", "七里香", "周杰伦");
            var cov2 = cache.getCoverUrl("netease", "七里香", "周杰伦");
            check(lyr != null && lyr.size() == 1, "歌词应保留");
            check("https://cover.example.com/qilixiang.jpg".equals(cov2),
                    "封面应已更新");
        });

        // ── 报告 ──
        System.out.println("\n" + "=".repeat(40));
        System.out.printf("结果: %d 通过, %d 失败%n", passed, failed);
        System.out.println(failed == 0 ? "ALL PASSED ✅" : "SOME FAILED ❌");
    }

    // ── 辅助 ──

    private static void test(String name, Runnable r) {
        try {
            r.run();
            passed++;
            System.out.println("  ✅ " + name);
        } catch (Throwable e) {
            failed++;
            System.out.println("  ❌ " + name + " — " + e.getMessage());
        }
    }

    private static void check(boolean condition, String msg) {
        if (!condition) throw new AssertionError(msg);
    }
}
