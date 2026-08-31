package com.island.config;

/**
 * 应用版本信息：统一版本号定义与版本比较逻辑。
 * 发布新版本时需同步更新 {@link #CURRENT}（与 pom.xml 的 {@code <version>} 保持一致）。
 */
public final class AppVersion {

    /** 当前应用版本号。 */
    public static final String CURRENT = "1.2.2";

    private AppVersion() {
        // 工具类，禁止实例化
    }

    /**
     * 比较两个版本号：支持前导 "v/V"（如 v1.2.3）与不等长分段（1.1 与 1.1.0 视为相等）。
     *
     * @return a 大于 b 返回正数，a 小于 b 返回负数，相等返回 0
     */
    public static int compare(String a, String b) {
        int[] va = parse(a);
        int[] vb = parse(b);
        int n = Math.max(va.length, vb.length);
        for (int i = 0; i < n; i++) {
            int x = i < va.length ? va[i] : 0;
            int y = i < vb.length ? vb[i] : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    /** 判断最新版本号是否比当前版本新。 */
    public static boolean isNewerThanCurrent(String latest) {
        return compare(latest, CURRENT) > 0;
    }

    /** 解析版本号为数字分段（去除前导 v/V，按非数字字符切分）。 */
    private static int[] parse(String version) {
        if (version == null) return new int[0];
        String v = version.trim();
        // 去除 "v1.2.3" 形式的前导 v（仅当后一位是数字，避免误伤合法段名）
        while (v.length() > 1 && (v.charAt(0) == 'v' || v.charAt(0) == 'V')
                && Character.isDigit(v.charAt(1))) {
            v = v.substring(1);
        }
        String[] parts = v.split("[^0-9]+");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }
}
