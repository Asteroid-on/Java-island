package com.island.bluetooth;

import com.island.monitor.AbstractPollingMonitor;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 蓝牙设备监听器 - 响应 Windows 蓝牙开关，实时检测设备连接/断开。
 * 蓝牙关闭时停止扫描；扫描持续超过5分钟自动休眠，节省资源。
 */
public class BluetoothMonitor extends AbstractPollingMonitor {

    private static final long SCAN_TIMEOUT_MS = 5 * 60 * 1000; // 5 分钟

    private enum State { IDLE, SCANNING, COOLDOWN }

    private final Set<String> knownDevices = Collections.synchronizedSet(new HashSet<>());
    private BluetoothListener listener;

    private State state = State.IDLE;
    private long scanStartedAt = 0;
    private boolean wasBluetoothOn = false;

    public interface BluetoothListener {
        void onDeviceConnected(String deviceName);
        void onDeviceDisconnected(String deviceName);
    }

    public BluetoothMonitor() {
        super("BT-Monitor", 1, TimeUnit.SECONDS, true);
    }

    public void setListener(BluetoothListener listener) {
        this.listener = listener;
    }

    @Override
    protected void poll() {
        try {
            boolean bluetoothOn = WindowsBluetoothScanner.isBluetoothEnabled();

            // ── 蓝牙开关状态变化处理 ──
            if (bluetoothOn && !wasBluetoothOn) {
                // 蓝牙从关闭→开启
                System.out.println("[BT-Monitor] 检测到蓝牙已开启");
                if (state == State.COOLDOWN) {
                    System.out.println("[BT-Monitor] 休眠后蓝牙重新开启，恢复扫描");
                }
                state = State.SCANNING;
                scanStartedAt = System.currentTimeMillis();
                knownDevices.clear();
            } else if (!bluetoothOn && wasBluetoothOn) {
                // 蓝牙从开启→关闭
                System.out.println("[BT-Monitor] 检测到蓝牙已关闭，停止扫描");
                state = State.IDLE;
                knownDevices.clear();
            }
            wasBluetoothOn = bluetoothOn;

            // ── 非扫描状态，不做设备检测 ──
            if (state != State.SCANNING) {
                // 如果蓝牙仍开着但处于 COOLDOWN（5分钟超时），等待关闭再开才恢复
                return;
            }

            // ── 5 分钟超时检查 ──
            if (System.currentTimeMillis() - scanStartedAt > SCAN_TIMEOUT_MS) {
                System.out.println("[BT-Monitor] 扫描已持续5分钟，进入休眠（关闭蓝牙再开即可恢复）");
                state = State.COOLDOWN;
                knownDevices.clear();
                return;
            }

            // ── 设备扫描与变更检测 ──
            Set<String> current = getConnectedDevices();

            // 断开检测
            for (String device : new HashSet<>(knownDevices)) {
                if (!current.contains(device)) {
                    debug("DISCONNECTED: " + device);
                    knownDevices.remove(device);
                    if (listener != null) listener.onDeviceDisconnected(device);
                }
            }

            // 新连接检测
            for (String device : current) {
                if (!knownDevices.contains(device)) {
                    debug("CONNECTED: " + device);
                    knownDevices.add(device);
                    fireConnected(device);
                }
            }
        } catch (Exception e) {
            debug("ERROR " + e.getMessage());
        }
    }

    private void fireConnected(String device) {
        if (listener != null) {
            debug("poll: FIRING onDeviceConnected: " + device);
            listener.onDeviceConnected(device);
        }
    }

    private Set<String> getConnectedDevices() {
        return WindowsBluetoothScanner.getConnectedDeviceNames();
    }
}
