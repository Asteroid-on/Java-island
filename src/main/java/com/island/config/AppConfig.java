package com.island.config;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * 应用外部服务配置加载器（classpath /config.properties）。
 *
 * <p>优先级：JVM 系统属性同名键 → 配置文件 → 默认值。
 * 配置缺失或读取失败时静默回退默认值，保证核心功能可用。</p>
 */
public final class AppConfig {

    private static final Properties PROPS = load();

    private AppConfig() {
        // 工具类，禁止实例化
    }

    private static Properties load() {
        Properties props = new Properties();
        // 用 Reader + UTF-8 加载，避免 InputStream 默认 ISO-8859-1 解码导致中文值乱码
        try (InputStream in = AppConfig.class.getResourceAsStream("/config.properties")) {
            if (in != null) {
                try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    props.load(reader);
                }
            }
        } catch (Exception ignored) {
            // 配置读取失败时按默认值运行
        }
        return props;
    }

    /** 获取配置：系统属性 → config.properties → 默认值。 */
    public static String get(String key, String defaultValue) {
        String sys = System.getProperty(key);
        if (sys != null && !sys.isBlank()) {
            return sys.trim();
        }
        String value = PROPS.getProperty(key);
        return (value == null || value.isBlank()) ? defaultValue : value.trim();
    }
}
