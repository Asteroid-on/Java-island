package com.island.util;

import java.io.File;
import java.nio.charset.StandardCharsets;

/**
 * Windows 开机自启管理。
 *
 * <p>首选项：通过 PowerShell 调用 COM {@code WScript.Shell}
 * （注意：这是 Windows Script Host 的 COM 接口，来自 {@code wshom.ocx}，
 * 与 VBScript <em>语言解释器</em>不同——目前只有 VBScript 语言被微软弃用，
 * 底层 COM 组件仍受支持）在 Startup 文件夹创建 .lnk 快捷方式，
 * 会自动出现在任务管理器的"启动"标签页。</p>
 *
 * <p>回退方案：写入注册表 {@code HKCU\Software\Microsoft\Windows\CurrentVersion\Run}。</p>
 *
 * <p>自启命令中携带 {@code --autostart} 参数，使应用能以正确模式启动。</p>
 *
 * <h3>方法概览</h3>
 * <ul>
 *   <li>{@link #isRegistered()} — 同时校验快捷方式目标有效性</li>
 *   <li>{@link #register()} — 创建 Startup 快捷方式或注册表项</li>
 *   <li>{@link #unregister()} — 清除所有注册方式</li>
 * </ul>
 */
public final class WindowsStartupManager {

    private static final String APP_NAME = "Java-Island";
    private static final String REG_KEY =
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";

    private WindowsStartupManager() {}

    // ═══════════════════════════════════════════
    //  公开 API
    // ═══════════════════════════════════════════

    /**
     * 检查开机自启是否已启用。
     * 对快捷方式方案额外校验目标路径有效性，避免残留无效 .lnk 导致误报。
     */
    public static boolean isRegistered() {
        if (shortcutExists() && isShortcutValid()) return true;
        return isRegistryRegistered();
    }

    /**
     * 注册开机自启：创建 Startup 文件夹快捷方式（携带 --autostart 参数）。
     * 若快捷方式创建失败则回退到注册表。
     *
     * @throws Exception 两种方式均失败时抛出
     */
    public static void register() throws Exception {
        try {
            createStartupShortcut();
            return;
        } catch (Exception e) {
            System.out.println(
                    "[Startup] shortcut failed, fallback to registry: "
                            + e.getMessage());
        }

        try {
            addRegistryEntry();
        } catch (Exception ex) {
            throw new RuntimeException(
                    "注册启动项失败（快捷方式与注册表均失败）: "
                            + ex.getMessage(),
                    ex);
        }
    }

    /**
     * 注销开机自启：清除所有可能的注册方式。
     *
     * @throws Exception 清除失败时抛出
     */
    public static void unregister() throws Exception {
        boolean cleared = false;
        String lastError = "";

        // 删除 Startup 快捷方式
        try {
            deleteStartupShortcut();
        } catch (Exception e) {
            lastError = "快捷方式: " + e.getMessage();
        }

        // 删除注册表项
        try {
            deleteRegistryEntry();
            cleared = true;
        } catch (Exception e) {
            lastError = (lastError.isEmpty() ? "" : lastError + "; ")
                    + "注册表: " + e.getMessage();
        }

        if (cleared) return;

        // 检查是否确实没有任何残留
        if (!isRegistered()) return;

        throw new RuntimeException("取消启动项失败: " + lastError);
    }

    // ═══════════════════════════════════════════
    //  Startup 文件夹快捷方式（首选项）
    // ═══════════════════════════════════════════

    private static String getStartupFolder() {
        String appData = System.getenv("APPDATA");
        if (appData == null) {
            appData = System.getProperty("user.home")
                    + "\\AppData\\Roaming";
        }
        return appData
                + "\\Microsoft\\Windows\\Start Menu\\Programs\\Startup";
    }

    private static String getShortcutPath() {
        return getStartupFolder() + "\\" + APP_NAME + ".lnk";
    }

    private static boolean shortcutExists() {
        return new File(getShortcutPath()).exists();
    }

    /**
     * 校验快捷方式目标是否指向当前 JAR。
     * 通过 PowerShell 解析 .lnk 的 TargetPath 和 Arguments，
     * 与当前环境对比。
     */
    private static boolean isShortcutValid() {
        try {
            String currentJar = detectJarPath();
            String target = resolveShortcutTarget(getShortcutPath());
            String arguments = resolveShortcutArguments(getShortcutPath());
            if (target == null || arguments == null) return false;
            if (!target.toLowerCase().endsWith("javaw.exe")) return false;
            // 校验 Arguments 中是否包含当前 JAR 路径
            return arguments.contains(currentJar);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 通过 PowerShell 解析 .lnk 快捷方式的目标路径。
     */
    private static String resolveShortcutTarget(String lnkPath) {
        try {
            String psCmd = String.format(
                    "$w=New-Object -ComObject WScript.Shell;"
                            + "$s=$w.CreateShortcut('%s');"
                            + "Write-Output $s.TargetPath",
                    escPS(lnkPath));
            Process p = new ProcessBuilder(
                    "powershell.exe", "-NoProfile",
                    "-ExecutionPolicy", "Bypass", "-Command", psCmd)
                    .redirectErrorStream(true)
                    .start();
            String output = new String(
                    p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor();
            return output.trim().isEmpty() ? null : output.trim();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 通过 PowerShell 解析 .lnk 快捷方式的 Arguments 字段。
     */
    private static String resolveShortcutArguments(String lnkPath) {
        try {
            String psCmd = String.format(
                    "$w=New-Object -ComObject WScript.Shell;"
                            + "$s=$w.CreateShortcut('%s');"
                            + "Write-Output $s.Arguments",
                    escPS(lnkPath));
            Process p = new ProcessBuilder(
                    "powershell.exe", "-NoProfile",
                    "-ExecutionPolicy", "Bypass", "-Command", psCmd)
                    .redirectErrorStream(true)
                    .start();
            String output = new String(
                    p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor();
            return output.trim().isEmpty() ? null : output.trim();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 通过 PowerShell + WScript.Shell COM 创建 .lnk 快捷方式。
     *
     * <p>携带 {@code --autostart} 参数，控件启动时区分手动/自启模式。</p>
     */
    private static void createStartupShortcut() throws Exception {
        String javawExe = getJavawPath();
        String jarPath = detectJarPath();
        String workDir = new File(jarPath).getParent();

        String lnkPath = getShortcutPath();
        // 先删除旧快捷方式，确保路径变更时能更新
        File oldLnk = new File(lnkPath);
        if (oldLnk.exists()) {
            boolean deleted = oldLnk.delete();
            if (!deleted) {
                throw new RuntimeException("无法删除旧快捷方式: " + lnkPath);
            }
        }

        String psCmd = String.format(
                "$w=New-Object -ComObject WScript.Shell;"
                        + "$s=$w.CreateShortcut('%s');"
                        + "$s.TargetPath='%s';"
                        + "$s.Arguments='-jar \"%s\" --autostart';"
                        + "$s.WorkingDirectory='%s';"
                        + "$s.Description='云隙泡 (Java-Island)';"
                        + "$s.Save()",
                escPS(lnkPath),
                escPS(javawExe),
                escPS(jarPath),
                escPS(workDir));

        Process p = new ProcessBuilder(
                "powershell.exe", "-NoProfile",
                "-ExecutionPolicy", "Bypass", "-Command", psCmd)
                .redirectErrorStream(true)
                .start();
        String output = new String(
                p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = p.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException(
                    "PowerShell 创建快捷方式失败: " + output.trim());
        }

        System.out.println("[Startup] shortcut created: " + lnkPath);
    }

    private static void deleteStartupShortcut() throws Exception {
        File lnk = new File(getShortcutPath());
        if (lnk.exists()) {
            boolean deleted = lnk.delete();
            if (!deleted) {
                throw new RuntimeException("无法删除快捷方式: " + lnk);
            }
            System.out.println("[Startup] shortcut deleted: " + lnk);
        }
    }

    // ═══════════════════════════════════════════
    //  注册表 Run 键（回退方案）
    // ═══════════════════════════════════════════

    private static boolean isRegistryRegistered() {
        try {
            Process p = new ProcessBuilder(
                    "reg", "query", REG_KEY, "/v", APP_NAME)
                    .redirectErrorStream(true)
                    .start();
            String output = new String(
                    p.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            p.waitFor();
            return p.exitValue() == 0 && output.contains(APP_NAME);
        } catch (Exception e) {
            return false;
        }
    }

    private static void addRegistryEntry() throws Exception {
        String command = buildRegistryCommand();
        Process p = new ProcessBuilder(
                "reg", "add", REG_KEY, "/v", APP_NAME,
                "/t", "REG_SZ", "/d", command, "/f")
                .redirectErrorStream(true)
                .start();
        String output = new String(
                p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = p.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("注册表写入失败: " + output.trim());
        }
        System.out.println("[Startup] registry entry added");
    }

    private static void deleteRegistryEntry() throws Exception {
        Process p = new ProcessBuilder(
                "reg", "delete", REG_KEY, "/v", APP_NAME, "/f")
                .redirectErrorStream(true)
                .start();
        p.waitFor(); // exitCode 可能非 0（项本身不存在），忽略
    }

    /**
     * 构建注册表 Run 键的启动命令。
     * 使用 {@code cmd /c start /d} 设置工作目录后启动 javaw，
     * 并携带 {@code --autostart} 参数。
     */
    private static String buildRegistryCommand() {
        String javawExe = getJavawPath();
        String jarPath = detectJarPath();
        String workDir = new File(jarPath).getParent();

        // start 命令的约定：第一个引号参数是窗口标题，留空 ""
        return "cmd /c start /d \""
                + workDir + "\" \"\" \""
                + javawExe + "\" -jar \""
                + jarPath + "\" --autostart";
    }

    // ═══════════════════════════════════════════
    //  路径探测
    // ═══════════════════════════════════════════

    private static String getJavawPath() {
        String javaHome = System.getProperty("java.home");
        return javaHome + File.separator + "bin"
                + File.separator + "javaw.exe";
    }

    /**
     * 探测当前运行的 JAR 路径。
     *
     * <p>探测顺序：
     * <ol>
     *   <li>从 class 保护域获取（运行在 JAR 中时最可靠）</li>
     *   <li>从 {@code java.class.path} 中查找 .jar 条目</li>
     *   <li>搜索当前工作目录下的 .jar 文件</li>
     *   <li>兜底：工作目录 + 标准 Maven 输出名</li>
     * </ol>
     *
     * @return JAR 文件的绝对路径，始终非空
     */
    private static String detectJarPath() {
        // 1. ProtectionDomain（生产环境最可靠）
        try {
            File jarFile = new File(
                    WindowsStartupManager.class.getProtectionDomain()
                            .getCodeSource().getLocation().toURI());
            String path = jarFile.getAbsolutePath();
            if (path.endsWith(".jar") && jarFile.exists()) {
                return path;
            }
        } catch (Exception ignored) { }

        // 2. java.class.path 中查找 jar
        String classPath = System.getProperty("java.class.path");
        if (classPath != null) {
            for (String entry : classPath.split(File.pathSeparator)) {
                if (entry.endsWith(".jar")) {
                    File f = new File(entry);
                    if (f.exists()) return f.getAbsolutePath();
                }
            }
        }

        // 3. 搜索工作目录下的 .jar（取第一个不是源码依赖的）
        String userDir = System.getProperty("user.dir");
        File dir = new File(userDir);
        File[] jars = dir.listFiles((d, name) ->
                name.endsWith(".jar") && !name.contains("original")
                        && !name.contains("sources") && !name.contains("javadoc"));
        if (jars != null && jars.length > 0) {
            // 优先选择体积最大的（最可能是 fat-jar）
            File best = jars[0];
            for (File f : jars) {
                if (f.length() > best.length()) best = f;
            }
            return best.getAbsolutePath();
        }

        // 4. 深入 target/ 目录（IDE 环境）
        File targetDir = new File(userDir, "target");
        if (targetDir.isDirectory()) {
            File[] targetJars = targetDir.listFiles((d, name) ->
                    name.endsWith(".jar") && !name.contains("original"));
            if (targetJars != null && targetJars.length > 0) {
                File best = targetJars[0];
                for (File f : targetJars) {
                    if (f.length() > best.length()) best = f;
                }
                return best.getAbsolutePath();
            }
        }

        // 5. 绝对兜底
        return userDir + File.separator + "target"
                + File.separator + "Java-island-1.0-SNAPSHOT.jar";
    }

    // ═══════════════════════════════════════════
    //  工具方法
    // ═══════════════════════════════════════════

    /**
     * PowerShell 单引号字符串转义：将单引号替换为 ''。
     */
    private static String escPS(String s) {
        return s.replace("'", "''");
    }
}
