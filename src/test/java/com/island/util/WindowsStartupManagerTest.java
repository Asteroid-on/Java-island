package com.island.util;

import com.island.config.AppConstants;
import java.io.File;
import java.util.Scanner;

/**
 * 测试 {@link WindowsStartupManager} 开机自启功能的完整流程。
 *
 * <p>测试内容：</p>
 * <ul>
 *   <li>注册前状态检查</li>
 *   <li>注册 → 验证 isRegistered() 为 true</li>
 *   <li>二次注册的幂等性</li>
 *   <li>快捷方式目标有效性校验</li>
 *   <li>注销 → 验证 isRegistered() 为 false</li>
 *   <li>二次注销的安全性</li>
 *   <li>注册/注销周期循环</li>
 * </ul>
 *
 * <pre>
 * 用法（在项目根目录执行）：
 *   javac -encoding UTF-8 -cp "target/classes;lib/json.jar;lib/opencc4j-1.8.1.jar" \
 *        -d target/test-classes \
 *        src/test/java/com/island/util/WindowsStartupManagerTest.java
 *
 *   java -cp "target/classes;target/test-classes;lib/json.jar;lib/opencc4j-1.8.1.jar" \
 *        com.island.util.WindowsStartupManagerTest
 * </pre>
 *
 * <p>⚠ 注意：本测试会临时修改开机自启注册状态。
 *    测试结束后会尽力恢复到测试前的状态。
 *    请勿在测试期间强制终止进程。</p>
 */
public class WindowsStartupManagerTest {

    private static int passed, failed;
    private static boolean originalState;

    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════════╗");
        System.out.println("║   WindowsStartupManager 开机自启功能测试     ║");
        System.out.println("╚══════════════════════════════════════════════╝");
        System.out.println();

        // ── 确认 ──
        System.out.println("⚠ 此测试会临时修改 Windows 开机自启注册状态。");
        System.out.println("  测试结束后会尽力恢复到测试前的状态。");
        System.out.print("  是否继续？[y/N] ");

        Scanner scanner = new Scanner(System.in);
        String input = scanner.nextLine().trim();
        if (!"y".equalsIgnoreCase(input) && !"yes".equalsIgnoreCase(input)) {
            System.out.println("  已取消。");
            return;
        }

        // ── 保存原始状态 ──
        originalState = WindowsStartupManager.isRegistered();
        System.out.println("\n📌 测试前状态: isRegistered() = " + originalState);
        System.out.println();

        try {
            runAllTests();
        } finally {
            // ── 恢复原始状态 ──
            System.out.println("\n🔄 正在恢复测试前状态...");
            try {
                if (originalState) {
                    WindowsStartupManager.register();
                    System.out.println("  已重新注册开机自启。");
                } else {
                    WindowsStartupManager.unregister();
                    System.out.println("  已清除开机自启。");
                }
                boolean restored = WindowsStartupManager.isRegistered();
                System.out.println("  恢复后状态: isRegistered() = " + restored);
                if (restored != originalState) {
                    System.out.println("  ⚠ 警告：状态恢复不一致！");
                } else {
                    System.out.println("  ✅ 状态已恢复。");
                }
            } catch (Exception e) {
                System.out.println("  ❌ 状态恢复失败: " + e.getMessage());
            }
        }

        // ── 报告 ──
        System.out.println("\n" + "=".repeat(48));
        System.out.printf("  测试结果: %d 通过, %d 失败%n", passed, failed);
        System.out.println(failed == 0 ? "  ALL PASSED ✅" : "  SOME FAILED ❌");
        System.out.println("=".repeat(48));
    }

    private static void runAllTests() {
        // ── 阶段 1: 初始状态 ──
        test("1. 先确保处于未注册状态（unregister）", () -> {
            WindowsStartupManager.unregister();
            boolean state = WindowsStartupManager.isRegistered();
            check(!state, "unregister 后应为 false，实际 " + state);
        });

        // ── 阶段 2: 注册 ──
        test("2. register() 后 isRegistered() 返回 true", () -> {
            WindowsStartupManager.register();
            boolean state = WindowsStartupManager.isRegistered();
            check(state, "register 后应为 true，实际 " + state);
        });

        test("3. 快捷方式文件应存在于 Startup 文件夹", () -> {
            String appData = System.getenv("APPDATA");
            if (appData == null) {
                appData = System.getProperty("user.home") + "\\AppData\\Roaming";
            }
            String shortcutPath = appData
                    + "\\Microsoft\\Windows\\Start Menu\\Programs\\Startup\\Java-Island.lnk";
            File lnk = new File(shortcutPath);
            check(lnk.exists(), "快捷方式应存在: " + shortcutPath);
            if (lnk.exists()) {
                System.out.println("    快捷方式路径: " + shortcutPath);
                System.out.println("    文件大小: " + lnk.length() + " bytes");
            }
        });

        // ── 阶段 3: 幂等性 ──
        test("4. 二次 register() 应幂等（不抛异常，状态仍为 true）", () -> {
            WindowsStartupManager.register();
            boolean state = WindowsStartupManager.isRegistered();
            check(state, "二次 register 后应为 true，实际 " + state);
        });

        // ── 阶段 4: Preferences 开关状态 ──
        test("5. AppConstants.isAutoStartEnabled() 应与注册状态独立", () -> {
            // Preferences 中的开关是用户意图，与实际注册状态解耦
            // 这里只验证读取不抛异常
            boolean enabled = AppConstants.isAutoStartEnabled();
            System.out.println("    AppConstants.isAutoStartEnabled() = " + enabled);
            // 此测试仅验证方法可正常调用，不做真假断言
        });

        // ── 阶段 5: 注销 ──
        test("6. unregister() 后 isRegistered() 返回 false", () -> {
            WindowsStartupManager.unregister();
            boolean state = WindowsStartupManager.isRegistered();
            check(!state, "unregister 后应为 false，实际 " + state);
        });

        test("7. 快捷方式文件应已被删除", () -> {
            String appData = System.getenv("APPDATA");
            if (appData == null) {
                appData = System.getProperty("user.home") + "\\AppData\\Roaming";
            }
            String shortcutPath = appData
                    + "\\Microsoft\\Windows\\Start Menu\\Programs\\Startup\\Java-Island.lnk";
            File lnk = new File(shortcutPath);
            check(!lnk.exists(), "快捷方式应已被删除: " + shortcutPath);
        });

        // ── 阶段 6: 二次注销安全性 ──
        test("8. 二次 unregister() 应安全（不抛异常，状态仍为 false）", () -> {
            WindowsStartupManager.unregister();
            boolean state = WindowsStartupManager.isRegistered();
            check(!state, "二次 unregister 后应为 false，实际 " + state);
        });

        // ── 阶段 7: 注册/注销周期循环 ──
        test("9. register → unregister → register 循环", () -> {
            // 第 1 轮
            WindowsStartupManager.register();
            boolean s1 = WindowsStartupManager.isRegistered();
            check(s1, "第 1 轮 register 后应为 true");

            WindowsStartupManager.unregister();
            boolean s2 = WindowsStartupManager.isRegistered();
            check(!s2, "第 1 轮 unregister 后应为 false");

            // 第 2 轮
            WindowsStartupManager.register();
            boolean s3 = WindowsStartupManager.isRegistered();
            check(s3, "第 2 轮 register 后应为 true");

            WindowsStartupManager.unregister();
            boolean s4 = WindowsStartupManager.isRegistered();
            check(!s4, "第 2 轮 unregister 后应为 false");
        });
    }

    // ── 辅助 ──

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static void test(String name, ThrowingRunnable r) {
        try {
            r.run();
            passed++;
            System.out.println("  ✅ " + name);
        } catch (Throwable e) {
            failed++;
            System.out.println("  ❌ " + name);
            System.out.println("     异常: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
        }
    }

    private static void check(boolean condition, String msg) {
        if (!condition) throw new AssertionError(msg);
    }
}

