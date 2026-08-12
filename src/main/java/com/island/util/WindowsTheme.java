package com.island.util;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Windows 系统主题检测。
 *
 * <p>通过读取注册表 {@code HKCU\...\Themes\Personalize\AppsUseLightTheme}
 * 判断当前系统为深色还是浅色模式。</p>
 */
public final class WindowsTheme {

    private WindowsTheme() { }

    /**
     * 系统是否为深色模式。
     * <p>读取失败（非 Windows 环境等）时默认返回 {@code true}，保持应用深色外观。</p>
     *
     * @return {@code true} 深色模式，{@code false} 浅色模式
     */
    public static boolean isDarkMode() {
        try {
            Process p = new ProcessBuilder(
                    "reg", "query",
                    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                    "/v", "AppsUseLightTheme")
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.US_ASCII))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String trimmed = line.trim();
                    // 输出形如：AppsUseLightTheme  REG_DWORD  0x1（1=浅色，0=深色）
                    if (trimmed.startsWith("AppsUseLightTheme")) {
                        return !trimmed.toLowerCase().contains("0x1");
                    }
                }
            }
            p.waitFor();
        } catch (Exception ignored) { }
        return true;
    }
}
