package com.island.util;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

/**
 * Windows DPI 感知工具：进程级启用 Per-Monitor V2 高 DPI 感知。
 *
 * <p>打包后的启动器（jpackage 生成的 exe）清单可能未声明 dpiAware，
 * 此时 JVM 以 DPI 不感知模式运行，窗口由 DWM 按位图拉伸到各缩放比例，
 * 透明窗口圆角边缘出现严重锯齿。在创建任何窗口前调用本工具，
 * 让 AWT 按物理像素渲染，125%/150%/200% 缩放比例下均自适应清晰。</p>
 */
public final class DpiUtil {

    /** DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2（Win10 1703+，user32 导出，HANDLE 值 -4） */
    private static final Pointer PER_MONITOR_AWARE_V2 = Pointer.createConstant(-4L);
    /** PROCESS_PER_MONITOR_DPI_AWARE = 2（Shcore，Win8.1+，V2 不可用时的降级） */
    private static final int PROCESS_PER_MONITOR_DPI_AWARE = 2;

    private interface User32Dpi extends Library {
        User32Dpi INSTANCE = Native.load("user32", User32Dpi.class);
        boolean SetProcessDpiAwarenessContext(Pointer context);
    }

    private interface Shcore extends Library {
        Shcore INSTANCE = Native.load("Shcore", Shcore.class);
        int SetProcessDpiAwareness(int value);
    }

    private DpiUtil() { }

    /**
     * 启用 Per-Monitor V2 DPI 感知。必须在创建任何 AWT 窗口之前调用；
     * 非 Windows 平台或调用失败时静默降级（仅保留旧渲染路径，不影响功能）。
     */
    public static void enablePerMonitorDpi() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("win")) return;
        try {
            if (User32Dpi.INSTANCE.SetProcessDpiAwarenessContext(PER_MONITOR_AWARE_V2)) {
                System.out.println("[DpiUtil] Per-Monitor V2 DPI 感知已启用");
                return;
            }
        } catch (Throwable ignored) { }
        try {
            Shcore.INSTANCE.SetProcessDpiAwareness(PROCESS_PER_MONITOR_DPI_AWARE);
            System.out.println("[DpiUtil] 降级启用 Per-Monitor DPI 感知");
        } catch (Throwable ignored) { }
    }
}
