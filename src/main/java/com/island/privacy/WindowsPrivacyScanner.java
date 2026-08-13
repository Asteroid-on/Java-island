package com.island.privacy;

import com.sun.jna.platform.win32.Advapi32;
import com.sun.jna.platform.win32.WinError;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinReg;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;

import java.util.Arrays;

/**
 * 通过 JNA 读取注册表检测摄像头/麦克风使用状态。
 *
 * <p>数据源：{@code CapabilityAccessManager\ConsentStore\{webcam|microphone}}，
 * 与 Windows 系统托盘"摄像头/麦克风使用中"指示灯使用完全相同的数据，
 * 无需管理员权限。应用级（尤其 UWP 应用）的使用记录写入用户级注册表
 * （HKEY_CURRENT_USER），系统级服务（如 svchost）记录写入机器级
 * （HKEY_LOCAL_MACHINE），因此需同时扫描两个 hive。</p>
 *
 * <p>判定规则：某应用的 {@code LastUsedTimeStart} 非零，且
 * {@code LastUsedTimeStop} 为 0 或小于开始时间，即认为该设备正在被占用。
 * {@code NonPackaged} 子键下嵌套了按可执行文件路径哈希划分的条目，需递归一层。</p>
 */
public final class WindowsPrivacyScanner {

    private static final int KEY_READ = WinNT.KEY_READ | WinNT.KEY_WOW64_64KEY;
    private static final int REG_QWORD = 11;
    private static final int REG_DWORD = 4;
    private static final int MAX_KEY_LENGTH = 256;
    private static final String CONSENT_STORE =
            "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\CapabilityAccessManager\\ConsentStore";

    private WindowsPrivacyScanner() {
    }

    public static boolean isCameraInUse() {
        return isDeviceInUse("webcam");
    }

    public static boolean isMicrophoneInUse() {
        return isDeviceInUse("microphone");
    }

    /**
     * 检查某个同意存储设备键下是否存在正在使用该设备的应用。
     * 同时扫描机器级（HKLM）与用户级（HKCU）注册表。
     */
    public static boolean isDeviceInUse(String deviceName) {
        return scanDeviceRoot(WinReg.HKEY_LOCAL_MACHINE, deviceName)
                || scanDeviceRoot(WinReg.HKEY_CURRENT_USER, deviceName);
    }

    /**
     * 在指定 hive 下打开设备同意存储键并扫描应用条目。
     */
    private static boolean scanDeviceRoot(WinReg.HKEY root, String deviceName) {
        WinReg.HKEYByReference phKey = new WinReg.HKEYByReference();
        int rc = Advapi32.INSTANCE.RegOpenKeyEx(root,
                CONSENT_STORE + "\\" + deviceName, 0, KEY_READ, phKey);
        if (rc != WinError.ERROR_SUCCESS) {
            return false;
        }
        try {
            return scanConsentKey(phKey.getValue(), 0);
        } finally {
            Advapi32.INSTANCE.RegCloseKey(phKey.getValue());
        }
    }

    /**
     * 枚举同意存储键下的应用子键，检查任一应用是否正在使用设备。
     */
    private static boolean scanConsentKey(WinReg.HKEY key, int depth) {
        char[] nameBuf = new char[MAX_KEY_LENGTH];
        for (int i = 0; ; i++) {
            Arrays.fill(nameBuf, '\0');
            IntByReference nameLen = new IntByReference(nameBuf.length);
            int rc = Advapi32.INSTANCE.RegEnumKeyEx(key, i, nameBuf, nameLen,
                    null, null, null, null);
            if (rc == WinError.ERROR_NO_MORE_ITEMS || rc != WinError.ERROR_SUCCESS) {
                break;
            }
            String appKey = new String(nameBuf, 0, nameLen.getValue());

            Long start = queryQword(key, appKey, "LastUsedTimeStart");
            Long stop = queryQword(key, appKey, "LastUsedTimeStop");
            if (start != null && start != 0 && (stop == null || stop == 0 || stop < start)) {
                return true;
            }

            // NonPackaged 子键下是按可执行文件路径哈希划分的条目，递归一层
            if (depth == 0 && "NonPackaged".equals(appKey)) {
                WinReg.HKEYByReference phSub = new WinReg.HKEYByReference();
                if (Advapi32.INSTANCE.RegOpenKeyEx(key, appKey, 0, KEY_READ, phSub)
                        == WinError.ERROR_SUCCESS) {
                    try {
                        if (scanConsentKey(phSub.getValue(), depth + 1)) {
                            return true;
                        }
                    } finally {
                        Advapi32.INSTANCE.RegCloseKey(phSub.getValue());
                    }
                }
            }
        }
        return false;
    }

    /**
     * 读取某个应用子键下的 QWORD（64 位 FILETIME）值，兼容 DWORD 类型。
     */
    private static Long queryQword(WinReg.HKEY parent, String subKey, String valueName) {
        WinReg.HKEYByReference phKey = new WinReg.HKEYByReference();
        int rc = Advapi32.INSTANCE.RegOpenKeyEx(parent, subKey, 0, KEY_READ, phKey);
        if (rc != WinError.ERROR_SUCCESS) {
            return null;
        }
        try {
            IntByReference type = new IntByReference();
            IntByReference size = new IntByReference(8);
            LongByReference data = new LongByReference();
            rc = Advapi32.INSTANCE.RegQueryValueEx(phKey.getValue(), valueName, 0,
                    type, data.getPointer(), size);
            if (rc != WinError.ERROR_SUCCESS) {
                return null;
            }
            if (type.getValue() == REG_QWORD) {
                return data.getValue();
            }
            if (type.getValue() == REG_DWORD) {
                return data.getValue() & 0xFFFFFFFFL;
            }
            return null;
        } finally {
            Advapi32.INSTANCE.RegCloseKey(phKey.getValue());
        }
    }
}
