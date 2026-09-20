package com.island.util;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.win32.W32APIOptions;

import java.awt.Component;
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
        /** 桌面 shell 窗口（Progman）句柄，用于全屏判定豁免 */
        HWND GetShellWindow();
        /** 取窗口类名（JNA Windows 下 char[] 映射为 WCHAR 数组，失败降级见调用处） */
        int GetClassNameW(HWND hWnd, char[] lpClassName, int nMaxCount);
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
     *
     * <p>桌面 shell 窗口（Progman/WorkerW）豁免：点击桌面空白处后前台窗口变为
     * 桌面窗口，其同样"无标题栏 + 覆盖整屏"，若不豁免会被误判为无边框全屏游戏，
     * 导致全屏抑制逻辑持续拦截主岛鼠标触发（须再激活任意窗口才恢复）。
     * 桌面永远不是需要抑制岛显示的全屏场景，直接放行。</p>
     */
    public static boolean isForegroundFullscreenWindow() {
        try {
            HWND fg = User32Win.INSTANCE.GetForegroundWindow();
            if (fg == null) return false;
            if (isDesktopShellWindow(fg)) return false;
            RECT rect = new RECT();
            if (!User32Win.INSTANCE.GetWindowRect(fg, rect)) return false;

            long style = User32Win.INSTANCE.GetWindowLongPtrW(fg, GWL_STYLE);
            boolean borderless = (style & (WS_CAPTION | WS_THICKFRAME)) == 0;
            if (!borderless) return false;

            int w = rect.right - rect.left;
            int h = rect.bottom - rect.top;
            // 屏幕边界走 ScreenUtil 的 5s TTL 缓存快照：本方法在鼠标轮询热路径与
            // 展开动画每帧被调用，旧实现每次全量枚举 GraphicsEnvironment
            for (Rectangle bounds : ScreenUtil.getScreenBoundsSnapshot()) {
                // 允许少量误差（任务栏自动隐藏/舍入）
                if (w >= bounds.width - 8 && h >= bounds.height - 8) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * 判断窗口是否为桌面 shell 窗口：句柄等于 GetShellWindow()（Progman），
     * 或类名为 Progman / WorkerW（Win10/11 桌面壁纸与图标由这两类窗口承载，
     * 点击桌面空白处后前台可能是其中任意一个）。
     */
    private static boolean isDesktopShellWindow(HWND hwnd) {
        try {
            HWND shell = User32Win.INSTANCE.GetShellWindow();
            if (shell != null && shell.equals(hwnd)) return true;
            String cls = getClassName(hwnd);
            return "Progman".equals(cls) || "WorkerW".equals(cls);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 取窗口类名，失败返回 null（静默降级）。 */
    private static String getClassName(HWND hwnd) {
        char[] buf = new char[64];
        int n = User32Win.INSTANCE.GetClassNameW(hwnd, buf, buf.length);
        if (n <= 0) return null;
        return new String(buf, 0, n);
    }

    /**
     * 诊断辅助：返回前台窗口的 "hwnd=类名" 描述，供全屏抑制日志区分真实
     * 全屏游戏与误判；取不到时返回 "unknown"。
     */
    public static String getForegroundWindowDesc() {
        try {
            HWND fg = User32Win.INSTANCE.GetForegroundWindow();
            if (fg == null) return "unknown";
            String cls = getClassName(fg);
            return fg + "=" + (cls != null ? cls : "?");
        } catch (Throwable ignored) {
            return "unknown";
        }
    }
}
