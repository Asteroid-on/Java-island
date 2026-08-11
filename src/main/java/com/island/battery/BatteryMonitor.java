package com.island.battery;

import com.island.monitor.AbstractPollingMonitor;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Structure;
import com.sun.jna.win32.W32APIOptions;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 电池状态轮询监控器。
 *
 * <p>每 5 秒通过 JNA 调用 Windows kernel32.dll 的
 * {@code GetSystemPowerStatus} 获取电池信息，
 * 与 Windows 系统托盘电池图标使用完全相同的 API 和数据源。
 * 若设备无电池（台式机），自动标记 absent。</p>
 */
public class BatteryMonitor extends AbstractPollingMonitor {

    private static final long POLL_INTERVAL_MS = 5000;

    private BatteryListener listener;
    private volatile BatteryInfo lastBattery = BatteryInfo.ABSENT;

    /** Windows SYSTEM_POWER_STATUS 结构体 */
    @Structure.FieldOrder({"ACLineStatus", "BatteryFlag", "BatteryLifePercent",
            "SystemStatusFlag", "BatteryLifeTime", "BatteryFullLifeTime"})
    public static class SYSTEM_POWER_STATUS extends Structure {
        public byte ACLineStatus;       // 0=offline(电池供电), 1=online(AC), 255=unknown
        public byte BatteryFlag;        // 1=high, 2=low, 4=critical, 8=charging, 128=no battery
        public byte BatteryLifePercent; // 0-100, 255=unknown
        public byte SystemStatusFlag;
        public int BatteryLifeTime;
        public int BatteryFullLifeTime;

        @Override
        public List<String> getFieldOrder() {
            return List.of("ACLineStatus", "BatteryFlag", "BatteryLifePercent",
                    "SystemStatusFlag", "BatteryLifeTime", "BatteryFullLifeTime");
        }
    }

    private interface Kernel32 extends Library {
        Kernel32 INSTANCE = Native.load("kernel32", Kernel32.class, W32APIOptions.DEFAULT_OPTIONS);
        boolean GetSystemPowerStatus(SYSTEM_POWER_STATUS result);
    }

    /** 电池状态信息 */
    public static final class BatteryInfo {
        public static final BatteryInfo ABSENT = new BatteryInfo(0, false, false);

        public final int percentage;
        public final boolean charging;
        public final boolean present;

        public BatteryInfo(int percentage, boolean charging, boolean present) {
            this.percentage = percentage;
            this.charging = charging;
            this.present = present;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof BatteryInfo other)) return false;
            return percentage == other.percentage
                    && charging == other.charging
                    && present == other.present;
        }

        @Override
        public int hashCode() {
            return Integer.hashCode(percentage) * 31 + Boolean.hashCode(charging);
        }

        @Override
        public String toString() {
            if (!present) return "BatteryInfo{absent}";
            return "BatteryInfo{" + percentage + "%, charging=" + charging + "}";
        }
    }

    @FunctionalInterface
    public interface BatteryListener {
        void onBatteryChanged(BatteryInfo info);
    }

    public BatteryMonitor() {
        super("Battery-Monitor", POLL_INTERVAL_MS, TimeUnit.MILLISECONDS, false);
    }

    public void setListener(BatteryListener listener) {
        this.listener = listener;
    }

    @Override
    protected void onStart() {
        try { poll(); } catch (Exception ignored) { }
    }

    @Override
    protected void poll() {
        BatteryInfo current = queryBattery();
        if (current == null) {
            current = BatteryInfo.ABSENT;
        }
        if (!current.equals(lastBattery)) {
            lastBattery = current;
            fireChanged(current);
        }
    }

    /**
     * 通过 kernel32 GetSystemPowerStatus 获取电池信息。
     * Windows 系统托盘电池图标使用同一 API。
     */
    private BatteryInfo queryBattery() {
        try {
            SYSTEM_POWER_STATUS sps = new SYSTEM_POWER_STATUS();
            if (!Kernel32.INSTANCE.GetSystemPowerStatus(sps)) {
                return null;
            }

            // BatteryFlag bit 7 (128) → 无电池; LifePercent=255 → unknown
            if ((sps.BatteryFlag & 0x80) != 0 || sps.BatteryLifePercent == (byte) 255) {
                return BatteryInfo.ABSENT;
            }

            int pct = sps.BatteryLifePercent & 0xFF;
            pct = Math.max(0, Math.min(100, pct));

            // ACLineStatus=1 → 插电; BatteryFlag bit 3 (8) → 正在充电
            boolean charging = sps.ACLineStatus == 1 || (sps.BatteryFlag & 0x08) != 0;

            return new BatteryInfo(pct, charging, true);
        } catch (Exception e) {
            return null;
        }
    }

    private void fireChanged(BatteryInfo info) {
        if (listener != null) {
            try {
                listener.onBatteryChanged(info);
            } catch (Exception ex) {
                logError("回调监听器异常: " + ex.getMessage());
            }
        }
    }
}
