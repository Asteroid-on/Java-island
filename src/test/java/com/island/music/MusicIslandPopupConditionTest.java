package com.island.music;

import com.island.music.model.MusicInfo;

/**
 * 测试音乐岛自动弹出条件 {@link MusicInfo#canAutoPopupMusicIsland()}。
 *
 * <p>自动弹出必须同时满足：① 存在活跃媒体会话且播放状态严格为 Playing；
 * ② 播放器主窗口处于最小化/不可见。任一条件不成立都不得弹出。</p>
 *
 * <pre>
 * 用法（在项目根目录执行）：
 *   javac -encoding UTF-8 -cp "target/classes" -d target/test-classes ^
 *        src/test/java/com/island/music/MusicIslandPopupConditionTest.java
 *
 *   java -cp "target/classes;target/test-classes" ^
 *        com.island.music.MusicIslandPopupConditionTest
 * </pre>
 */
public class MusicIslandPopupConditionTest {

    private static int passed, failed;

    public static void main(String[] args) {
        System.out.println("════════ 音乐岛自动弹出条件测试 ════════");

        // ── 条件齐全：活跃会话 + 严格播放 + 播放器最小化 → 允许弹出 ──
        check("正在播放 + 播放器最小化", info("Playing", "晴天", "周杰伦", true), true);

        // ── 条件①缺失：窗口仍可见 ──
        check("正在播放 + 播放器窗口可见", info("Playing", "晴天", "周杰伦", false), false);
        // ── 条件①缺失：仅暂停 ──
        check("暂停 + 播放器最小化", info("Paused", "晴天", "周杰伦", true), false);
        // ── 条件①缺失：已停止 ──
        check("停止 + 播放器最小化", info("Stopped", "晴天", "周杰伦", true), false);
        // ── 条件①缺失：无会话（daemon 无数据） ──
        check("无会话 + 播放器最小化",
                MusicInfo.builder().playbackStatus("Playing").playerMinimized(true).build(), false);
        // ── 条件①缺失：有会话但无曲目 ──
        check("有会话无曲目 + 播放器最小化", info("Playing", "", "周杰伦", true), false);
        // ── 大小写与空值鲁棒性 ──
        check("状态大小写不敏感（PLAYING）", info("PLAYING", "晴天", "周杰伦", true), true);
        check("状态含前后空白不计为播放（保守不弹）", info(" Playing ", "晴天", "周杰伦", true), false);
        check("曲目仅空白字符", info("Playing", "   ", "周杰伦", true), false);
        // ── daemon 未上报窗口状态时默认按不弹出处理 ──
        check("daemon 未上报 isMinimized（缺字段）",
                MusicInfo.builder().hasSession(true).title("晴天").artist("周杰伦")
                        .playbackStatus("Playing").build(), false);

        // ── 辅助判定：isStrictlyPlaying 单独不可作为弹出依据 ──
        MusicInfo playingVisible = info("Playing", "晴天", "周杰伦", false);
        check("isStrictlyPlaying 仍为 true（动画控制语义不变）",
                playingVisible.isStrictlyPlaying(), true);
        check("canAutoPopupMusicIsland 为 false（弹出语义已收紧）",
                playingVisible.canAutoPopupMusicIsland(), false);

        System.out.printf("%n通过 %d 项，失败 %d 项%n", passed, failed);
        System.out.println(failed == 0 ? "RESULT=PASS" : "RESULT=FAIL");
        if (failed > 0) System.exit(1);
    }

    private static MusicInfo info(String status, String title, String artist, boolean minimized) {
        return MusicInfo.builder()
                .hasSession(true)
                .hasMusicProcess(true)
                .title(title)
                .artist(artist)
                .playbackStatus(status)
                .sourceAppId("cloudmusic")
                .playerMinimized(minimized)
                .build();
    }

    private static void check(String name, boolean actual, boolean expected) {
        boolean ok = actual == expected;
        if (ok) {
            passed++;
            System.out.println("  PASS  " + name);
        } else {
            failed++;
            System.out.println("  FAIL  " + name + "  实际=" + actual + " 期望=" + expected);
        }
    }

    /** 重载：传入 MusicInfo 时按自动弹出条件判定 */
    private static void check(String name, MusicInfo info, boolean expected) {
        check(name, info.canAutoPopupMusicIsland(), expected);
    }
}
