package com.island.bluetooth;

import com.sun.jna.*;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import java.util.HashSet;
import java.util.Set;

/**
 * 通过 JNA 直接调用 Windows 原生蓝牙 API（bthprops.cpl）枚举已连接设备。
 * 相比 PowerShell 轮询 PnP，此方案直接读取 Windows 蓝牙栈的连接状态，
 * 真正实现与 Windows 系统联动。
 */
public class WindowsBluetoothScanner {

    /** 加载 BluetoothApis.dll */
    private static BthPropsLib loadLibrary() {
        try {
            BthPropsLib lib = Native.load("BluetoothApis", BthPropsLib.class, W32APIOptions.DEFAULT_OPTIONS);
            System.out.println("[BT-Scanner] DLL 加载成功: BluetoothApis");
            return lib;
        } catch (Throwable t) {
            System.err.println("[BT-Scanner] 无法加载 BluetoothApis.dll: " + t.getMessage());
            return null;
        }
    }

    public interface BthPropsLib extends StdCallLibrary {
        BthPropsLib INSTANCE = loadLibrary();
        // ── 结构体定义 ──

        @Structure.FieldOrder({"dwSize", "fReturnAuthenticated", "fReturnRemembered",
                "fReturnConnected", "fReturnUnknown", "fIssueInquiry",
                "cTimeoutMultiplier", "hRadio"})
        @SuppressWarnings("this-escape")
        class BluetoothDeviceSearchParams extends Structure {
            public int    dwSize;
            public int    fReturnAuthenticated;   // BOOL (4 bytes on Win32)
            public int    fReturnRemembered;
            public int    fReturnConnected;        // TRUE = 仅返回已连接设备
            public int    fReturnUnknown;
            public int    fIssueInquiry;
            public byte   cTimeoutMultiplier;
            public WinNT.HANDLE hRadio;

            public BluetoothDeviceSearchParams() {
                this.dwSize = size();
                this.fReturnConnected = 1;
                this.fReturnRemembered = 1;   // 必须设为1才能枚举到已配对设备
                this.fReturnAuthenticated = 1;
                this.fReturnUnknown = 0;
                this.fIssueInquiry = 0;
                this.cTimeoutMultiplier = 0;
                this.hRadio = null;
            }
        }

        @Structure.FieldOrder({"wYear", "wMonth", "wDayOfWeek", "wDay",
                "wHour", "wMinute", "wSecond", "wMilliseconds"})
        class SystemTime extends Structure {
            public short wYear, wMonth, wDayOfWeek, wDay;
            public short wHour, wMinute, wSecond, wMilliseconds;
        }

        @Structure.FieldOrder({"dwSize", "Address", "ulClassofDevice",
                "fConnected", "fRemembered", "fAuthenticated",
                "stLastSeen", "stLastUsed", "szName"})
        @SuppressWarnings("this-escape")
        class BluetoothDeviceInfo extends Structure {
            public int       dwSize;
            public long      Address;             // BLUETOOTH_ADDRESS = ULONGLONG
            public int       ulClassofDevice;
            public int       fConnected;           // ★ 关键字段：是否已连接
            public int       fRemembered;
            public int       fAuthenticated;
            public SystemTime stLastSeen;
            public SystemTime stLastUsed;
            public char[]    szName = new char[248]; // WCHAR[248]

            public BluetoothDeviceInfo() {
                this.dwSize = size();
            }

            public String getDeviceName() {
                return Native.toString(szName).trim();
            }
        }

        // ── API 函数 ──

        WinNT.HANDLE BluetoothFindFirstDevice(
                BluetoothDeviceSearchParams pbtsp,
                BluetoothDeviceInfo pbtdi);

        boolean BluetoothFindNextDevice(
                WinNT.HANDLE hFind,
                BluetoothDeviceInfo pbtdi);

        boolean BluetoothFindDeviceClose(WinNT.HANDLE hFind);

        // ── 蓝牙开关检测 ──

        @Structure.FieldOrder({"dwSize"})
        @SuppressWarnings("this-escape")
        class BluetoothFindRadioParams extends Structure {
            public int dwSize;
            public BluetoothFindRadioParams() { this.dwSize = size(); }
        }

        WinNT.HANDLE BluetoothFindFirstRadio(
                BluetoothFindRadioParams pbtfrp,
                PointerByReference phRadio);

        boolean BluetoothFindRadioClose(WinNT.HANDLE hFind);

        boolean BluetoothIsConnectable(WinNT.HANDLE hRadio);
    }

    /**
     * 调用 Windows 蓝牙 API 获取当前已连接的蓝牙设备名称集合。
     * fReturnConnected=TRUE 确保只返回实时连接状态的设备。
     */
    public static Set<String> getConnectedDeviceNames() {
        Set<String> devices = new HashSet<>();
        BthPropsLib lib = BthPropsLib.INSTANCE;
        if (lib == null) {
            System.err.println("[BT-Scanner] 无法加载蓝牙 API DLL，扫描中止");
            return devices;
        }

        try {
            BthPropsLib.BluetoothDeviceSearchParams params =
                    new BthPropsLib.BluetoothDeviceSearchParams();
            BthPropsLib.BluetoothDeviceInfo info =
                    new BthPropsLib.BluetoothDeviceInfo();

            WinNT.HANDLE hFind = lib.BluetoothFindFirstDevice(params, info);
            if (hFind == null) {
                return devices;
            }

            try {
                do {
                    if (info.fConnected != 0) {
                        String name = info.getDeviceName();
                        if (!name.isEmpty()) {
                            devices.add(name);
                        }
                    }
                    info.dwSize = info.size();
                } while (lib.BluetoothFindNextDevice(hFind, info));
            } finally {
                lib.BluetoothFindDeviceClose(hFind);
            }
        } catch (Throwable e) {
            System.err.println("[BT-Scanner] JNA 蓝牙扫描异常: " + e.getMessage());
            e.printStackTrace();
        }
        return devices;
    }

    // ── 蓝牙开关检测（带缓存） ──
    private static Boolean lastRadioState = null;
    private static long lastRadioCheckTime = 0;
    private static final long RADIO_CHECK_CACHE_MS = 2000;

    /**
     * 检测 Windows 蓝牙开关是否已开启。
     * 结果缓存 2 秒，避免频繁调用 API。
     */
    public static boolean isBluetoothEnabled() {
        long now = System.currentTimeMillis();
        if (lastRadioState != null && (now - lastRadioCheckTime) < RADIO_CHECK_CACHE_MS) {
            return lastRadioState;
        }
        BthPropsLib lib = BthPropsLib.INSTANCE;
        if (lib == null) return false;
        try {
            BthPropsLib.BluetoothFindRadioParams params = new BthPropsLib.BluetoothFindRadioParams();
            PointerByReference phRadio = new PointerByReference();
            WinNT.HANDLE hFind = lib.BluetoothFindFirstRadio(params, phRadio);
            if (hFind == null) { lastRadioState = false; lastRadioCheckTime = now; return false; }
            try {
                WinNT.HANDLE hRadio = new WinNT.HANDLE(phRadio.getValue());
                lastRadioState = lib.BluetoothIsConnectable(hRadio);
            } finally { lib.BluetoothFindRadioClose(hFind); }
        } catch (Throwable t) { lastRadioState = false; }
        lastRadioCheckTime = now;
        return lastRadioState != null && lastRadioState;
    }
}
