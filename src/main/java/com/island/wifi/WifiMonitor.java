package com.island.wifi;

import com.island.monitor.AbstractPollingMonitor;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.W32APIOptions;

import java.util.concurrent.TimeUnit;

/**
 * WiFi连接监听器 - 通过 wlanapi.dll 的 WLAN API 检测当前连接的 WiFi 网络。
 *
 * <p>替代原有的 cmd/netsh 子进程轮询：实测 netsh 方式单次 131~941ms 且每 2s
 * 创建一个子进程（占 6.5~10.7% 单核），WLAN API 方式单次查询 <1ms。</p>
 */
public class WifiMonitor extends AbstractPollingMonitor {

    private static final long POLL_INTERVAL_MS = 2000; // 2秒轮询一次

    private WifiListener listener;
    private String lastKnownNetwork = null;

    public interface WifiListener {
        void onWifiConnected(String networkName);
        void onWifiDisconnected(String networkName);
    }

    public WifiMonitor() {
        super("WiFi-Monitor", POLL_INTERVAL_MS, TimeUnit.MILLISECONDS, true);
    }

    public void setListener(WifiListener listener) {
        this.listener = listener;
    }

    @Override
    protected void onStart() {
        // 启动时立即检查一次
        try {
            poll();
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void poll() {
        try {
            String currentNetwork = getCurrentWifiNetwork();

            if (currentNetwork != null && lastKnownNetwork == null) {
                // WiFi从断开变为连接
                debug("WiFi已连接到: " + currentNetwork);
                lastKnownNetwork = currentNetwork;
                if (listener != null) listener.onWifiConnected(currentNetwork);
            } else if (currentNetwork == null && lastKnownNetwork != null) {
                // WiFi从连接变为断开
                debug("WiFi已断开，之前连接的是: " + lastKnownNetwork);
                if (listener != null) listener.onWifiDisconnected(lastKnownNetwork);
                lastKnownNetwork = null;
            } else if (currentNetwork != null && lastKnownNetwork != null && !currentNetwork.equals(lastKnownNetwork)) {
                // WiFi网络切换
                debug("WiFi网络切换: " + lastKnownNetwork + " -> " + currentNetwork);
                String oldNetwork = lastKnownNetwork;
                lastKnownNetwork = currentNetwork;
                if (listener != null) {
                    listener.onWifiDisconnected(oldNetwork);
                    listener.onWifiConnected(currentNetwork);
                }
            }
        } catch (Exception e) {
            debug("WiFi监控错误: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════
    //  WLAN API（wlanapi.dll）
    // ═══════════════════════════════════════════

    private interface Wlanapi extends Library {
        Wlanapi INSTANCE = Native.load("wlanapi", Wlanapi.class, W32APIOptions.DEFAULT_OPTIONS);

        int WlanOpenHandle(int clientVersion, Pointer reserved,
                           IntByReference negotiatedVersion, PointerByReference clientHandle);

        int WlanCloseHandle(Pointer clientHandle, Pointer reserved);

        int WlanEnumInterfaces(Pointer clientHandle, Pointer reserved,
                               PointerByReference interfaceList);

        int WlanQueryInterface(Pointer clientHandle, Pointer interfaceGuid, int opCode,
                               Pointer reserved, IntByReference dataSize,
                               PointerByReference data, Pointer opcodeValueType);

        void WlanFreeMemory(Pointer memory);
    }

    private static final int ERROR_SUCCESS = 0;
    /** Windows 8+ 客户端版本 */
    private static final int CLIENT_VERSION = 2;
    /** wlan_intf_opcode_current_connection */
    private static final int OPCODE_CURRENT_CONNECTION = 7;
    /** wlan_interface_state_connected */
    private static final int STATE_CONNECTED = 1;
    /**
     * WLAN_INTERFACE_INFO_LIST 头部 8 字节（dwNumberOfItems + dwIndex），
     * 每项 WLAN_INTERFACE_INFO = GUID(16) + WCHAR[256](512) + state(4) = 532 字节。
     */
    private static final int INTERFACE_ENTRY_SIZE = 532;
    /** WLAN_CONNECTION_ATTRIBUTES 中 strProfileName 的偏移（isState int + wlanConnectionMode int） */
    private static final int PROFILE_NAME_OFFSET = 8;

    /**
     * 获取当前连接的WiFi网络名称（SSID/profileName）。
     * 使用 wlanapi.dll 原生调用，无子进程、无编码转换。
     *
     * @return 已连接的网络名称，未连接返回 null
     */
    private String getCurrentWifiNetwork() {
        Pointer handle = null;
        try {
            IntByReference negotiated = new IntByReference();
            PointerByReference handleRef = new PointerByReference();
            if (Wlanapi.INSTANCE.WlanOpenHandle(CLIENT_VERSION, null, negotiated, handleRef) != ERROR_SUCCESS) {
                return null;
            }
            handle = handleRef.getValue();

            PointerByReference listRef = new PointerByReference();
            if (Wlanapi.INSTANCE.WlanEnumInterfaces(handle, null, listRef) != ERROR_SUCCESS) {
                return null;
            }
            Pointer list = listRef.getValue();
            if (list == null) return null;
            try {
                int count = list.getInt(0);
                for (int i = 0; i < count; i++) {
                    Pointer interfaceGuid = list.share(8L + (long) i * INTERFACE_ENTRY_SIZE);
                    IntByReference dataSize = new IntByReference();
                    PointerByReference dataRef = new PointerByReference();
                    if (Wlanapi.INSTANCE.WlanQueryInterface(handle, interfaceGuid,
                            OPCODE_CURRENT_CONNECTION, null, dataSize, dataRef, null) != ERROR_SUCCESS) {
                        continue;
                    }
                    Pointer data = dataRef.getValue();
                    if (data == null) continue;
                    try {
                        if (data.getInt(0) != STATE_CONNECTED) continue;
                        String name = data.getWideString(PROFILE_NAME_OFFSET);
                        return (name == null || name.isEmpty()) ? null : name;
                    } finally {
                        Wlanapi.INSTANCE.WlanFreeMemory(data);
                    }
                }
                return null; // 未连接到任何WiFi
            } finally {
                Wlanapi.INSTANCE.WlanFreeMemory(list);
            }
        } catch (Throwable e) {
            debug("WLAN API 查询失败: " + e.getMessage());
            return null;
        } finally {
            if (handle != null) {
                Wlanapi.INSTANCE.WlanCloseHandle(handle, null);
            }
        }
    }
}
