package com.island.util;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/**
 * Windows 11 系统级 WiFi+GPS+基站三角定位（C# + WinRT Geolocator）。
 *
 * <h3>原理</h3>
 * <ol>
 *   <li>嵌入式 C# 源码写出到临时目录</li>
 *   <li>csc.exe 编译为 EXE（引用 Windows.Foundation.winmd + Windows.Devices.winmd）</li>
 *   <li>首次编译后缓存 EXE，后续复用</li>
 *   <li>Java ProcessBuilder 调 EXE，解析 stdout 的坐标</li>
 * </ol>
 *
 * <h3>避坑要点</h3>
 * <ul>
 *   <li>❌ 不引用 System.Runtime.WindowsRuntime.dll（Win11 拆分包不兼容）</li>
 *   <li>❌ 不用 .AsTask() / await（__ComObject 不解析扩展方法）</li>
 *   <li>✅ 手动轮询 IAsyncOperation.Status + GetResults()</li>
 *   <li>✅ 编译引用 Windows.Foundation.winmd + Windows.Devices.winmd</li>
 * </ul>
 */
public final class WindowsLocationProvider {

    private WindowsLocationProvider() {}

    private static final Path TMP = Paths.get(System.getProperty("java.io.tmpdir"));
    private static final Path EXE_PATH = TMP.resolve("java-island-locator.exe");
    private static final Path CS_PATH  = TMP.resolve("java-island-locator.cs");
    private static final Object LOCK = new Object();
    private static volatile boolean exeReady = false;

    private static final int LOCATION_TIMEOUT_SEC = 12;

    /** 定位结果缓存：10 分钟内复用，避免天气刷新重复拉起定位 EXE */
    private static final long LOCATION_CACHE_TTL_MS = 10 * 60 * 1000;
    private static volatile LocationResult cachedLocation;
    private static volatile long cachedLocationAt;

    // ═══════════════════════════════════════════
    // 嵌入式 C# 源码（写临时文件 → csc 编译）
    // ═══════════════════════════════════════════
    private static final String CS_CODE =
        "using System;\n" +
        "using System.Threading;\n" +
        "using Windows.Devices.Geolocation;\n" +
        "using Windows.Foundation;\n" +
        "\n" +
        "class Program\n" +
        "{\n" +
        "    [STAThread]\n" +
        "    static int Main()\n" +
        "    {\n" +
        "        try\n" +
        "        {\n" +
        "            var locator = new Geolocator();\n" +
        "            locator.DesiredAccuracy = PositionAccuracy.High;\n" +
        "\n" +
        "            // 1. 请求权限（手动轮询，不用 .AsTask()）\n" +
        "            var aop = Geolocator.RequestAccessAsync();\n" +
        "            int waited = 0;\n" +
        "            while (aop.Status == AsyncStatus.Started && waited < 30)\n" +
        "            {\n" +
        "                Thread.Sleep(200);\n" +
        "                waited++;\n" +
        "            }\n" +
        "            if (aop.Status != AsyncStatus.Completed)\n" +
        "            {\n" +
        "                Console.Error.WriteLine(\"ACCESS_TIMEOUT\");\n" +
        "                return 1;\n" +
        "            }\n" +
        "            if (aop.GetResults() != GeolocationAccessStatus.Allowed)\n" +
        "            {\n" +
        "                Console.Error.WriteLine(\"ACCESS_DENIED\");\n" +
        "                return 2;\n" +
        "            }\n" +
        "\n" +
        "            // 2. 获取位置（手动轮询 IAsyncOperation.Status）\n" +
        "            var op = locator.GetGeopositionAsync();\n" +
        "            var startTime = Environment.TickCount;\n" +
        "            while (op.Status == AsyncStatus.Started)\n" +
        "            {\n" +
        "                Thread.Sleep(200);\n" +
        "                if (Environment.TickCount - startTime > 12000) break;\n" +
        "            }\n" +
        "\n" +
        "            if (op.Status == AsyncStatus.Completed)\n" +
        "            {\n" +
        "                var pos = op.GetResults();\n" +
        "                double lat = pos.Coordinate.Point.Position.Latitude;\n" +
        "                double lon = pos.Coordinate.Point.Position.Longitude;\n" +
        "                double acc = pos.Coordinate.Accuracy;\n" +
        "                Console.WriteLine(lat.ToString(\"F6\") + \",\" + lon.ToString(\"F6\") + \",\" + acc.ToString(\"F1\"));\n" +
        "                return 0;\n" +
        "            }\n" +
        "            else if (op.Status == AsyncStatus.Error)\n" +
        "            {\n" +
        "                Console.Error.WriteLine(\"ERROR:\" + op.ErrorCode.Message);\n" +
        "                return 1;\n" +
        "            }\n" +
        "            else\n" +
        "            {\n" +
        "                Console.Error.WriteLine(\"TIMEOUT\");\n" +
        "                return 1;\n" +
        "            }\n" +
        "        }\n" +
        "        catch (Exception ex)\n" +
        "        {\n" +
        "            Console.Error.WriteLine(\"ERROR:\" + ex.Message);\n" +
        "            return 1;\n" +
        "        }\n" +
        "    }\n" +
        "}\n";

    // ═══════════════════════════════════════════
    // 公开 API
    // ═══════════════════════════════════════════

    public static class LocationResult {
        public final double latitude;
        public final double longitude;
        public final double accuracy;
        LocationResult(double lat, double lon, double acc) { latitude = lat; longitude = lon; accuracy = acc; }
    }

    /**
     * 获取 Windows 系统级定位（WiFi+GPS+基站三角定位）。
     * 首次调用会编译 C# EXE（约 1-2 秒），后续直接复用。
     *
     * @return 坐标，失败返回 null
     */
    public static LocationResult getLocation() {
        long now = System.currentTimeMillis();
        LocationResult cached = cachedLocation;
        if (cached != null && now - cachedLocationAt < LOCATION_CACHE_TTL_MS) {
            return cached;
        }
        if (!ensureExe()) return null;
        try {
            Process p = new ProcessBuilder(EXE_PATH.toAbsolutePath().toString()).start();
            // 关闭子进程 stdin，避免管道句柄依赖 GC 回收（句柄泄漏治理）
            try { p.getOutputStream().close(); } catch (Exception ignored) { }
            StringBuilder out = new StringBuilder(), err = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String l; while ((l = r.readLine()) != null) out.append(l);
            }
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream()))) {
                String l; while ((l = r.readLine()) != null) err.append(l);
            }
            if (!p.waitFor(LOCATION_TIMEOUT_SEC + 5, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                System.err.println("[Windows定位] EXE 超时，已强制终止");
                return null;
            }
            String errStr = err.toString().trim();
            if (p.exitValue() != 0) {
                if (errStr.contains("ACCESS_DENIED")) {
                    System.err.println("[Windows定位] 系统位置权限未授予，请在 Windows 设置中开启定位");
                } else {
                    System.err.println("[Windows定位] 失败 (exit=" + p.exitValue() + "): " + errStr);
                }
                return null;
            }
            String result = out.toString().trim();
            String[] parts = result.split(",");
            if (parts.length >= 2) {
                double lat = Double.parseDouble(parts[0]);
                double lon = Double.parseDouble(parts[1]);
                double acc = parts.length >= 3 ? Double.parseDouble(parts[2]) : 999;
                System.out.printf("[Windows定位] 成功: %.6f, %.6f (精度 %.0fm)%n", lat, lon, acc);
                LocationResult location = new LocationResult(lat, lon, acc);
                cachedLocation = location;
                cachedLocationAt = now;
                return location;
            }
            System.err.println("[Windows定位] 无法解析输出: " + result);
            return null;
        } catch (Exception e) {
            System.err.println("[Windows定位] 异常: " + e.getMessage());
            return null;
        }
    }

    // ═══════════════════════════════════════════
    // 编译缓存
    // ═══════════════════════════════════════════

    private static boolean ensureExe() {
        if (exeReady && Files.exists(EXE_PATH)) return true;
        synchronized (LOCK) {
            if (exeReady && Files.exists(EXE_PATH)) return true;
            try {
                // 写入 C# 源码
                Files.writeString(CS_PATH, CS_CODE);

                // 定位 csc.exe
                String frameworkDir = System.getenv("SystemRoot") + "\\Microsoft.NET\\Framework64\\v4.0.30319";
                Path cscPath = Paths.get(frameworkDir, "csc.exe");
                if (!Files.exists(cscPath)) {
                    // 回退到 32 位
                    frameworkDir = System.getenv("SystemRoot") + "\\Microsoft.NET\\Framework\\v4.0.30319";
                    cscPath = Paths.get(frameworkDir, "csc.exe");
                }
                if (!Files.exists(cscPath)) {
                    System.err.println("[Windows定位] 找不到 csc.exe，请安装 .NET Framework 4.x");
                    return false;
                }

                // WinMD 元数据
                String winmdDir = System.getenv("SystemRoot") + "\\System32\\WinMetadata";
                String foundationWinmd = winmdDir + "\\Windows.Foundation.winmd";
                String devicesWinmd = winmdDir + "\\Windows.Devices.winmd";

                System.out.println("[Windows定位] 正在编译 C# EXE（首次约 1-2 秒）...");

                ProcessBuilder pb = new ProcessBuilder(
                    cscPath.toString(),
                    "/nologo",
                    "/target:exe",
                    "/out:" + EXE_PATH.toAbsolutePath(),
                    "/reference:" + foundationWinmd,
                    "/reference:" + devicesWinmd,
                    "/reference:" + frameworkDir + "\\System.Runtime.dll",
                    CS_PATH.toAbsolutePath().toString()
                );
                pb.redirectErrorStream(true);
                Process p = pb.start();
                // 关闭子进程 stdin，避免管道句柄依赖 GC 回收（句柄泄漏治理）
                try { p.getOutputStream().close(); } catch (Exception ignored) { }
                StringBuilder compileOut = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String l; while ((l = r.readLine()) != null) compileOut.append(l).append('\n');
                }
                if (!p.waitFor(30, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    System.err.println("[Windows定位] csc 编译超时");
                    return false;
                }
                if (p.exitValue() != 0) {
                    System.err.println("[Windows定位] csc 编译失败:\n" + compileOut);
                    return false;
                }
                exeReady = true;
                System.out.println("[Windows定位] 编译完成: " + EXE_PATH);
                return true;
            } catch (Exception e) {
                System.err.println("[Windows定位] 编译异常: " + e.getMessage());
                return false;
            }
        }
    }
}
