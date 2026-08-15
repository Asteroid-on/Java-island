package com.island.perf;

import com.island.bluetooth.WindowsBluetoothScanner;
import com.island.monitor.AbstractPollingMonitor;
import com.island.privacy.WindowsPrivacyScanner;
import com.island.music.WindowsMediaManager;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Structure;
import com.sun.jna.win32.W32APIOptions;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 各后台监控器单次轮询成本基准（真实 Windows 调用，不模拟）：
 * - BatteryMonitor: kernel32 GetSystemPowerStatus JNA 调用
 * - PrivacyMonitor: 注册表 CapabilityAccessManager 读取（摄像头+麦克风）
 * - BluetoothMonitor: 蓝牙开关状态 + 已连接设备枚举
 * - WifiMonitor: cmd/netsh 子进程调用（真实生产路径）
 * - MusicMonitor: media_info.json 读取解析（由 MediaQueryPerfTest 覆盖）
 * - 轮询间隔稳定性：AbstractPollingMonitor 调度器实际回调间隔漂移
 */
public class PollCostPerfTest {

    // ── 与 BatteryMonitor 相同的 JNA 结构 ──
    @Structure.FieldOrder({"ACLineStatus", "BatteryFlag", "BatteryLifePercent",
            "SystemStatusFlag", "BatteryLifeTime", "BatteryFullLifeTime"})
    public static class SYSTEM_POWER_STATUS extends Structure {
        public byte ACLineStatus;
        public byte BatteryFlag;
        public byte BatteryLifePercent;
        public byte SystemStatusFlag;
        public int BatteryLifeTime;
        public int BatteryFullLifeTime;
        @Override public List<String> getFieldOrder() {
            return List.of("ACLineStatus", "BatteryFlag", "BatteryLifePercent",
                    "SystemStatusFlag", "BatteryLifeTime", "BatteryFullLifeTime");
        }
    }

    private interface Kernel32 extends Library {
        Kernel32 INSTANCE = Native.load("kernel32", Kernel32.class, W32APIOptions.DEFAULT_OPTIONS);
        boolean GetSystemPowerStatus(SYSTEM_POWER_STATUS result);
    }

    public static void main(String[] args) throws Exception {
        PerfUtil.header("后台监控器轮询成本基准（真实 Windows 调用）");

        // ── 1. Battery: GetSystemPowerStatus ──
        List<Double> battery = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            long t0 = System.nanoTime();
            SYSTEM_POWER_STATUS sps = new SYSTEM_POWER_STATUS();
            Kernel32.INSTANCE.GetSystemPowerStatus(sps);
            battery.add((System.nanoTime() - t0) / 1e6);
        }
        PerfUtil.print("Battery JNA GetSystemPowerStatus (1000次)", PerfUtil.stats(battery));

        // ── 2. Privacy: 注册表读取（摄像头+麦克风各一次）──
        List<Double> camera = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            long t0 = System.nanoTime();
            WindowsPrivacyScanner.isCameraInUse();
            camera.add((System.nanoTime() - t0) / 1e6);
        }
        PerfUtil.print("Privacy 注册表读摄像头状态 (100次)", PerfUtil.stats(camera));

        List<Double> mic = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            long t0 = System.nanoTime();
            WindowsPrivacyScanner.isMicrophoneInUse();
            mic.add((System.nanoTime() - t0) / 1e6);
        }
        PerfUtil.print("Privacy 注册表读麦克风状态 (100次)", PerfUtil.stats(mic));

        // ── 3. Bluetooth: 开关状态（带 2s 内部缓存）+ 设备枚举 ──
        List<Double> btOn = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            long t0 = System.nanoTime();
            WindowsBluetoothScanner.isBluetoothEnabled();
            btOn.add((System.nanoTime() - t0) / 1e6);
        }
        PerfUtil.print("BT isBluetoothEnabled 缓存命中 (100次)", PerfUtil.stats(btOn));

        List<Double> btScan = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            long t0 = System.nanoTime();
            Set<String> devs = WindowsBluetoothScanner.getConnectedDeviceNames();
            btScan.add((System.nanoTime() - t0) / 1e6);
            if (i == 0) System.out.println("[PERF] BT 当前已连接设备: " + devs);
        }
        PerfUtil.print("BT getConnectedDeviceNames 全量扫描 (10次)", PerfUtil.stats(btScan));

        // ── 4. WiFi: netsh 子进程（旧实现对照）──
        List<Double> wifi = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            long t0 = System.nanoTime();
            getCurrentWifiNetwork();
            wifi.add((System.nanoTime() - t0) / 1e6);
        }
        PerfUtil.print("WiFi netsh wlan show interfaces 子进程 (10次)", PerfUtil.stats(wifi));

        // ── 4b. WiFi: 新版 WLAN API（反射调用私有方法，真实实现）──
        com.island.wifi.WifiMonitor wm = new com.island.wifi.WifiMonitor();
        java.lang.reflect.Method wlanMethod = com.island.wifi.WifiMonitor.class.getDeclaredMethod("getCurrentWifiNetwork");
        wlanMethod.setAccessible(true);
        List<Double> wlan = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            long t0 = System.nanoTime();
            wlanMethod.invoke(wm);
            wlan.add((System.nanoTime() - t0) / 1e6);
        }
        PerfUtil.print("WiFi WLAN API getCurrentWifiNetwork (1000次)", PerfUtil.stats(wlan));

        // ── 5. Music 轮询（真实文件，daemon 已停止则文件为上次快照）──
        List<Double> music = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            long t0 = System.nanoTime();
            WindowsMediaManager.queryMediaInfo();
            music.add((System.nanoTime() - t0) / 1e6);
        }
        PerfUtil.print("Music queryMediaInfo (1000次)", PerfUtil.stats(music));

        // ── 6. 调度器间隔稳定性：300ms 与 1000ms 两种周期各跑 60s ──
        measureSchedulerDrift("300ms 调度", 300, 200);
        measureSchedulerDrift("1000ms 调度", 1000, 60);

        System.out.println("\n=== 轮询成本基准完成 ===");
    }

    /** 与 WifiMonitor 相同的 netsh 调用路径（复制测量，不改业务代码）。 */
    private static String getCurrentWifiNetwork() throws Exception {
        ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "netsh wlan show interfaces");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(p.getInputStream(), Charset.forName("GBK")));
        String line;
        String ssid = null;
        while ((line = reader.readLine()) != null) {
            if (line.trim().startsWith("SSID") && line.contains(":")) {
                String[] parts = line.split(":", 2);
                if (parts.length > 1 && !parts[1].trim().isEmpty()) ssid = parts[1].trim();
            }
        }
        p.waitFor();
        return ssid;
    }

    /** 测量 AbstractPollingMonitor 调度器的实际回调间隔漂移。 */
    private static void measureSchedulerDrift(String label, int intervalMs, int samples) throws Exception {
        List<Long> arrivals = new ArrayList<>();
        Object lock = new Object();
        AbstractPollingMonitor m = new AbstractPollingMonitor("PerfProbe-" + intervalMs, intervalMs, TimeUnit.MILLISECONDS, false) {
            @Override protected void poll() {
                synchronized (lock) { arrivals.add(System.nanoTime()); }
            }
        };
        m.start();
        Thread.sleep((long) samples * intervalMs + 2000);
        m.stop();
        synchronized (lock) {
            if (arrivals.size() < 2) { System.out.println("[PERF] " + label + " 样本不足"); return; }
            List<Double> deltas = new ArrayList<>();
            for (int i = 1; i < arrivals.size(); i++) {
                deltas.add((arrivals.get(i) - arrivals.get(i - 1)) / 1e6);
            }
            PerfUtil.Stats st = PerfUtil.stats(deltas);
            System.out.printf("[PERF] %-46s 期望=%dms 实际=%s 漂移=%.1fms%n",
                    label + " 回调间隔", intervalMs, st, st.avg() - intervalMs);
        }
    }
}
