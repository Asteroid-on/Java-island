package com.island.music;

import com.island.perf.PerfUtil;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.List;
import java.util.Random;

/**
 * WindowsMediaManager.queryMediaInfo 轮询成本基准。
 *
 * MusicMonitor 每 300ms 调用一次；本基准分别测量
 * 「无会话小文件(191B)」与「播放中+1.5MB base64 封面缩略图」两种真实场景的
 * 文件读取 + JSON 解析 + MusicInfo 构造开销。
 */
public class MediaQueryPerfTest {

    private static final Path POS_FILE = Paths.get(System.getProperty("java.io.tmpdir"), "media_info.json");

    public static void main(String[] args) throws Exception {
        PerfUtil.header("WindowsMediaManager.queryMediaInfo 轮询成本基准");

        Path backup = null;
        if (Files.exists(POS_FILE)) {
            backup = POS_FILE.resolveSibling("media_info.json.perf-bak");
            Files.copy(POS_FILE, backup, StandardCopyOption.REPLACE_EXISTING);
        }

        try {
            // ── 场景1：无会话小文件（191B，与当前生产文件一致）──
            String small = "{\"hasSession\":false,\"hasMusicProcess\":false,\"title\":\"\",\"artist\":\"\",\"album\":\"\"," +
                    "\"playbackStatus\":\"Closed\",\"positionTicks\":0,\"endTimeTicks\":0,\"sourceAppId\":\"\",\"thumbnail\":\"\",\"isMinimized\":false}";
            atomicWrite(small);
            List<Double> s1 = PerfUtil.sample(5000, () -> {
                if (WindowsMediaManager.queryMediaInfo() == null) throw new IllegalStateException();
            });
            PerfUtil.print("queryMediaInfo 无会话 191B (5000次)", PerfUtil.stats(s1));

            // ── 场景2：播放中 + 真实尺寸 base64 缩略图 ──
            byte[] coverBytes = fakeJpeg(1200, 1200); // ~1.5MB 随机像素 JPEG 模拟
            String b64 = Base64.getEncoder().encodeToString(coverBytes);
            System.out.println("[PERF] 生成模拟缩略图: " + (coverBytes.length / 1024) + "KB, base64 " + (b64.length() / 1024) + "KB");
            String playing = "{\"hasSession\":true,\"hasMusicProcess\":true,\"title\":\"晴天\",\"artist\":\"周杰伦\"," +
                    "\"album\":\"叶惠美\",\"playbackStatus\":\"Playing\",\"positionTicks\":1234567890,\"endTimeTicks\":2690000000," +
                    "\"sourceAppId\":\"QQMusic\",\"thumbnail\":\"" + b64 + "\",\"isMinimized\":false}";
            atomicWrite(playing);
            List<Double> s2 = PerfUtil.sample(300, () -> {
                WindowsMediaManager.queryMediaInfo();
            });
            PerfUtil.print("queryMediaInfo 播放中+1.5MB封面 (300次)", PerfUtil.stats(s2));

            // ── 场景3：播放中无缩略图（最热路径：严格播放时每300ms触发）──
            String noThumb = playing.replace("\"thumbnail\":\"" + b64 + "\"", "\"thumbnail\":\"\"");
            atomicWrite(noThumb);
            List<Double> s3 = PerfUtil.sample(5000, () -> WindowsMediaManager.queryMediaInfo());
            PerfUtil.print("queryMediaInfo 播放中无封面 (5000次)", PerfUtil.stats(s3));

            // ── 场景4：文件不存在（daemon 未启动）──
            Files.deleteIfExists(POS_FILE);
            List<Double> s4 = PerfUtil.sample(5000, () -> WindowsMediaManager.queryMediaInfo());
            PerfUtil.print("queryMediaInfo 文件不存在 (5000次)", PerfUtil.stats(s4));

            // ── 场景5：JSON 截断（daemon 原子写瞬间的读竞争）──
            atomicWrite(small.substring(0, small.length() / 2));
            List<Double> s5 = PerfUtil.sample(2000, () -> WindowsMediaManager.queryMediaInfo());
            PerfUtil.print("queryMediaInfo JSON截断+3次重试 (2000次)", PerfUtil.stats(s5));

            // ── 场景6：新版协议（thumbFile+thumbHash 独立文件，无内嵌 base64）──
            Path thumbPath = POS_FILE.resolveSibling("media_thumb.bin");
            Files.write(thumbPath, coverBytes);
            String newProto = "{\"hasSession\":true,\"hasMusicProcess\":true,\"title\":\"晴天\",\"artist\":\"周杰伦\",\"album\":\"叶惠美\",\"playbackStatus\":\"Playing\",\"positionTicks\":1234567890,\"endTimeTicks\":2690000000,\"sourceAppId\":\"QQMusic\",\"thumbnail\":\"\",\"thumbFile\":\"media_thumb.bin\",\"thumbHash\":\"A1B2C3D4E5F60718\",\"isMinimized\":false}";
            atomicWrite(newProto);
            List<Double> s6 = PerfUtil.sample(5000, () -> WindowsMediaManager.queryMediaInfo());
            PerfUtil.print("queryMediaInfo 新版协议+独立封面文件 (5000次)", PerfUtil.stats(s6));
        } finally {
            if (backup != null && Files.exists(backup)) {
                Files.move(backup, POS_FILE, StandardCopyOption.REPLACE_EXISTING);
            }
            System.out.println("\n=== 媒体查询基准完成 ===");
        }
    }

    /** 模拟 daemon 的原子写入：先写 tmp 再 rename。 */
    private static void atomicWrite(String content) throws Exception {
        Path tmp = POS_FILE.resolveSibling("media_info.json.tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        Files.move(tmp, POS_FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** 生成指定尺寸的伪 JPEG 字节（不可解码但体积真实，用于衡量字符串解析成本）。 */
    private static byte[] fakeJpeg(int w, int h) {
        byte[] data = new byte[w * h / 2];
        new Random(42).nextBytes(data);
        return data;
    }
}
