package com.island.weather;

import com.island.util.AppLogger;

import java.awt.Font;
import java.awt.FontFormatException;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * QWeather Icons 字体加载与天气条件→图标字符映射。
 * 
 * <p>通过加载 qweather-icons.ttf 图标字体，将中文天气描述或 WMO 天气码
 * 映射为对应的 Unicode 图标字符，供 Swing JLabel 使用。</p>
 */
public class WeatherIconMapper {

    private static Font iconFont;
    private static boolean loaded = false;

    /** 默认字号 */
    public static final float DEFAULT_ICON_SIZE = 20f;

    /** 天气条件 → QWeather icon 码映射 */
    private static final Map<String, Integer> CONDITION_TO_CODE = new HashMap<>();
    /** QWeather icon 码 → Unicode 字符映射 */
    private static final Map<Integer, Character> CODE_TO_CHAR = new HashMap<>();

    static {
        initConditionMappings();
        initCodeToCharMappings();
    }

    // ═══════════════════════════════════════════
    //  字体加载
    // ═══════════════════════════════════════════

    /**
     * 加载 QWeather 图标字体。首次调用时从 classpath 加载，后续调用直接返回。
     *
     * @return 图标 Font 对象，加载失败返回 null
     */
    public static synchronized Font loadFont() {
        if (loaded) return iconFont;
        try (InputStream is = WeatherIconMapper.class.getResourceAsStream("/fonts/qweather-icons.ttf")) {
            if (is == null) {
                AppLogger.warn("WeatherIcon", "找不到图标字体 /fonts/qweather-icons.ttf");
                loaded = true;
                return null;
            }
            iconFont = Font.createFont(Font.TRUETYPE_FONT, is);
            loaded = true;
            System.out.println("[WeatherIcon] QWeather 图标字体加载成功");
        } catch (FontFormatException | IOException e) {
            AppLogger.warn("WeatherIcon", "字体加载失败: " + e.getMessage());
            loaded = true;
        }
        return iconFont;
    }

    /**
     * 获取指定字号的图标 Font。
     */
    public static Font getIconFont(float size) {
        Font base = loadFont();
        if (base == null) return null;
        return base.deriveFont(size);
    }

    // ═══════════════════════════════════════════
    //  天气条件 → 图标字符映射
    // ═══════════════════════════════════════════

    /**
     * 根据中文天气描述获取对应的 QWeather 图标字符。
     *
     * @param condition 天气描述，如 "晴"、"多云"、"小雨"
     * @return Unicode 图标字符，未匹配到返回 '?'
     */
    public static char getIconChar(String condition) {
        if (condition == null || condition.isEmpty()) return '?';
        Integer code = CONDITION_TO_CODE.get(condition);
        if (code == null) {
            // 模糊匹配
            for (Map.Entry<String, Integer> e : CONDITION_TO_CODE.entrySet()) {
                if (condition.contains(e.getKey())) {
                    code = e.getValue();
                    break;
                }
            }
        }
        if (code == null) code = 999; // 未知
        Character ch = CODE_TO_CHAR.get(code);
        return ch != null ? ch : '?';
    }

    /**
     * 根据 WMO 天气码获取对应的 QWeather 图标字符。
     *
     * @param wmoCode WMO 天气码 (0-99)
     * @return Unicode 图标字符
     */
    public static char getIconChar(int wmoCode) {
        int qCode = wmoToQWeatherCode(wmoCode);
        Character ch = CODE_TO_CHAR.get(qCode);
        return ch != null ? ch : '?';
    }

    /**
     * WMO 天气码 → QWeather 图标码。
     */
    private static int wmoToQWeatherCode(int wmo) {
        switch (wmo) {
            case 0:  return 100;  // 晴
            case 1:  return 102;  // 少云
            case 2:  return 101;  // 多云
            case 3:  return 104;  // 阴
            case 45:
            case 48: return 501;  // 雾
            case 51:
            case 53:
            case 55: return 305;  // 小雨
            case 56:
            case 57: return 305;  // 冻雨→小雨图标
            case 61:
            case 63: return 306;  // 中雨
            case 65: return 307;  // 大雨
            case 66:
            case 67: return 306;  // 冻雨→中雨图标
            case 71:
            case 73: return 400;  // 小雪/中雪
            case 75: return 402;  // 大雪
            case 77: return 400;  // 霰→雪图标
            case 80:
            case 81: return 300;  // 阵雨
            case 82: return 307;  // 大阵雨
            case 85: return 400;  // 阵雪
            case 86: return 402;  // 大阵雪
            case 95:
            case 96:
            case 99: return 300;  // 雷暴→雨图标
            default: return 999;  // 未知
        }
    }

    // ═══════════════════════════════════════════
    //  静态映射表初始化
    // ═══════════════════════════════════════════

    private static void initConditionMappings() {
        // 日间天气
        CONDITION_TO_CODE.put("晴", 100);
        CONDITION_TO_CODE.put("少云", 102);
        CONDITION_TO_CODE.put("多云", 101);
        CONDITION_TO_CODE.put("晴间多云", 103);
        CONDITION_TO_CODE.put("阴", 104);
        // 雨
        CONDITION_TO_CODE.put("阵雨", 300);
        CONDITION_TO_CODE.put("小雨", 305);
        CONDITION_TO_CODE.put("中雨", 306);
        CONDITION_TO_CODE.put("大雨", 307);
        CONDITION_TO_CODE.put("暴雨", 308);
        CONDITION_TO_CODE.put("大暴雨", 309);
        CONDITION_TO_CODE.put("雨", 300);
        CONDITION_TO_CODE.put("雷阵雨", 300);
        CONDITION_TO_CODE.put("雷暴", 300);
        CONDITION_TO_CODE.put("冻雨", 305);
        // 雪
        CONDITION_TO_CODE.put("雪", 400);
        CONDITION_TO_CODE.put("小雪", 400);
        CONDITION_TO_CODE.put("中雪", 401);
        CONDITION_TO_CODE.put("大雪", 402);
        CONDITION_TO_CODE.put("暴雪", 403);
        CONDITION_TO_CODE.put("阵雪", 400);
        CONDITION_TO_CODE.put("霰", 400);
        // 雾/霾
        CONDITION_TO_CODE.put("雾", 501);
        CONDITION_TO_CODE.put("霾", 502);
        CONDITION_TO_CODE.put("沙尘", 503);
        CONDITION_TO_CODE.put("扬沙", 503);
        // 极端
        CONDITION_TO_CODE.put("热", 900);
        CONDITION_TO_CODE.put("冷", 901);
        CONDITION_TO_CODE.put("未知", 999);
    }

    private static void initCodeToCharMappings() {
        // QWeather icon 码 → Unicode Private Use Area 字符
        // 映射来自 qweather-icons.css v1.8.0 的 -fill 填充系列
        CODE_TO_CHAR.put(100, '\uf1cc');   // 晴-填充
        CODE_TO_CHAR.put(101, '\uf1cd');   // 多云-填充
        CODE_TO_CHAR.put(102, '\uf1ce');   // 少云-填充
        CODE_TO_CHAR.put(103, '\uf1cf');   // 晴间多云-填充
        CODE_TO_CHAR.put(104, '\uf1d0');   // 阴-填充
        CODE_TO_CHAR.put(150, '\uf1d1');   // 晴-夜-填充
        CODE_TO_CHAR.put(151, '\uf1d2');   // 多云-夜-填充
        CODE_TO_CHAR.put(152, '\uf1d3');   // 少云-夜-填充
        CODE_TO_CHAR.put(153, '\uf1d4');   // 阴-夜-填充
        CODE_TO_CHAR.put(300, '\uf1d5');   // 阵雨-填充
        CODE_TO_CHAR.put(301, '\uf1d6');   // 强阵雨-填充
        CODE_TO_CHAR.put(302, '\uf1d7');   // 雷阵雨-填充
        CODE_TO_CHAR.put(303, '\uf1d8');   // 强雷阵雨-填充
        CODE_TO_CHAR.put(304, '\uf1d9');   // 雷阵雨伴有冰雹-填充
        CODE_TO_CHAR.put(305, '\uf1da');   // 小雨-填充
        CODE_TO_CHAR.put(306, '\uf1db');   // 中雨-填充
        CODE_TO_CHAR.put(307, '\uf1dc');   // 大雨-填充
        CODE_TO_CHAR.put(308, '\uf1dd');   // 暴雨-填充
        CODE_TO_CHAR.put(309, '\uf1de');   // 大暴雨-填充
        CODE_TO_CHAR.put(350, '\uf1e8');   // 阵雨-夜-填充
        CODE_TO_CHAR.put(351, '\uf1e9');   // 强阵雨-夜-填充
        CODE_TO_CHAR.put(399, '\uf1ea');   // 雨-填充
        CODE_TO_CHAR.put(400, '\uf1eb');   // 小雪-填充
        CODE_TO_CHAR.put(401, '\uf1ec');   // 中雪-填充
        CODE_TO_CHAR.put(402, '\uf1ed');   // 大雪-填充
        CODE_TO_CHAR.put(403, '\uf1ee');   // 暴雪-填充
        CODE_TO_CHAR.put(500, '\uf1f9');   // 薄雾-填充
        CODE_TO_CHAR.put(501, '\uf1fa');   // 雾-填充
        CODE_TO_CHAR.put(502, '\uf1fb');   // 霾-填充
        CODE_TO_CHAR.put(503, '\uf1fc');   // 扬沙-填充
        CODE_TO_CHAR.put(900, '\uf207');   // 热-填充
        CODE_TO_CHAR.put(901, '\uf208');   // 冷-填充
        CODE_TO_CHAR.put(999, '\uf209');   // 未知-填充
    }
}
