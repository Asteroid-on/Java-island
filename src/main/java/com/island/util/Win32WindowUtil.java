package com.island.util;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.win32.W32APIOptions;

import java.awt.Component;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;

/**
 * Win32 窗口工具：不抢焦点置顶（SWP_NOACTIVATE）与前台全屏窗口检测。
 *
 * <p>游戏以全屏/无边框窗口运行时，其窗口通常是 TOPMOST 且处于激活态，
 * Windows 会把激活的 TOPMOST 窗口排到 Z 序最前。Swing 的 toFront 对不可激活的
 * JWindow 无效，且激活类操作会引发焦点大战（游戏反复夺回焦点导致岛抖动）。
 * 改用 SetWindowPos(HWND_TOPMOST | SWP_NOACTIVATE)：岛盖在游戏之上，
 * 同时不改变焦点状态，游戏不会失焦/暂停。</p>
 */
public final class Win32WindowUtil {

    private static final int GWL_STYLE = -16;
    private static final long WS_CAPTION = 0x00C00000L;
    private static final long WS_THICKFRAME = 0x00040000L;

    private static final int SWP_NOSIZE = 0x0001;
    private static final int SWP_NOMOVE = 0x0002;
    private static final int SWP_NOACTIVATE = 0x0010;

    /** HWND_TOPMOST = -1 */
    private static final HWND HWND_TOPMOST = new HWND(Pointer.createConstant(-1L));

    private interface User32Win extends Library {
        User32Win INSTANCE = Native.load("user32", User32Win.class, W32APIOptions.DEFAULT_OPTIONS);
        boolean SetWindowPos(HWND hWnd, HWND hWndInsertAfter, int X, int Y, int cx, int cy, int uFlags);
        HWND GetForegroundWindow();
        boolean GetWindowRect(HWND hWnd, RECT rect);
        /** 返回 LONG_PTR：本项目仅支持 64 位 JVM（JDK 25 x64），long 按 64 位读取正确 */
        long GetWindowLongPtrW(HWND hWnd, int nIndex);
    }

    private Win32WindowUtil() {
        // 工具类，禁止实例化
    }

    /**
     * 将窗口置顶但不激活（不抢焦点）。
     * 失败（非 Windows 或句柄不可用）时静默降级，不影响功能。
     */
    public static void topmostNoActivate(Component component) {
        try {
            if (component == null || !component.isDisplayable()) return;
            HWND hwnd = new HWND(Native.getComponentPointer(component));
            if (hwnd == null) return;
            User32Win.INSTANCE.SetWindowPos(hwnd, HWND_TOPMOST, 0, 0, 0, 0,
                    SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 检测前台窗口是否为无边框全屏窗口（覆盖某块屏幕且无标题栏/可调整边框）。
     * 用于诊断：独占全屏游戏接管显示输出时任何窗口都无法覆盖，
     * 无边框全屏（borderless）窗口则可通过反复重申 TOPMOST 置顶。
     */
    public static boolean isForegroundFullscreenWindow() {
        try {
            HWND fg = User32Win.INSTANCE.GetForegroundWindow();
            if (fg == null) return false;
            RECT rect = new RECT();
            if (!User32Win.INSTANCE.GetWindowRect(fg, rect)) return false;

            long style = User32Win.INSTANCE.GetWindowLongPtrW(fg, GWL_STYLE);
            boolean borderless = (style & (WS_CAPTION | WS_THICKFRAME)) == 0;
            if (!borderless) return false;

            int w = rect.right - rect.left;
            int h = rect.bottom - rect.top;
            for (java.awt.GraphicsDevice device :
                    GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
                Rectangle bounds = device.getDefaultConfiguration().getBounds();
                // 允许少量误差（任务栏自动隐藏/舍入）
                if (w >= bounds.width - 8 && h >= bounds.height - 8) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}
