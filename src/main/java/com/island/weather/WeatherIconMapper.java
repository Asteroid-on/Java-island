package com.island.weather;

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
                System.err.println("[WeatherIcon] 找不到字体文件 /fonts/qweather-icons.ttf");
                loaded = true;
                return null;
            }
            iconFont = Font.createFont(Font.TRUETYPE_FONT, is);
            loaded = true;
            System.out.println("[WeatherIcon] QWeather 图标字体加载成功");
        } catch (FontFormatException | IOException e) {
            System.err.println("[WeatherIcon] 字体加载失败: " + e.getMessage());
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
        // 映射来自 qweather-icons.css v1.8.0
        CODE_TO_CHAR.put(100, '\uf101');   // 晴
        CODE_TO_CHAR.put(101, '\uf102');   // 多云
        CODE_TO_CHAR.put(102, '\uf103');   // 少云
        CODE_TO_CHAR.put(103, '\uf104');   // 晴间多云
        CODE_TO_CHAR.put(104, '\uf105');   // 阴
        CODE_TO_CHAR.put(150, '\uf106');   // 晴-夜
        CODE_TO_CHAR.put(151, '\uf107');   // 多云-夜
        CODE_TO_CHAR.put(152, '\uf108');   // 少云-夜
        CODE_TO_CHAR.put(153, '\uf109');   // 阴-夜
        CODE_TO_CHAR.put(300, '\uf10a');   // 阵雨
        CODE_TO_CHAR.put(301, '\uf10b');
        CODE_TO_CHAR.put(302, '\uf10c');
        CODE_TO_CHAR.put(303, '\uf10d');
        CODE_TO_CHAR.put(304, '\uf10e');
        CODE_TO_CHAR.put(305, '\uf10f');   // 小雨
        CODE_TO_CHAR.put(306, '\uf110');   // 中雨
        CODE_TO_CHAR.put(307, '\uf111');   // 大雨
        CODE_TO_CHAR.put(308, '\uf112');   // 暴雨
        CODE_TO_CHAR.put(309, '\uf113');   // 大暴雨
        CODE_TO_CHAR.put(350, '\uf11d');   // 阵雨
        CODE_TO_CHAR.put(351, '\uf11e');   // 大雨
        CODE_TO_CHAR.put(399, '\uf11f');   // 雨
        CODE_TO_CHAR.put(400, '\uf120');   // 小雪
        CODE_TO_CHAR.put(401, '\uf121');   // 中雪
        CODE_TO_CHAR.put(402, '\uf122');   // 大雪
        CODE_TO_CHAR.put(403, '\uf123');   // 暴雪
        CODE_TO_CHAR.put(500, '\uf12e');   // 雾/霾
        CODE_TO_CHAR.put(501, '\uf12f');   // 雾
        CODE_TO_CHAR.put(502, '\uf130');   // 霾
        CODE_TO_CHAR.put(503, '\uf131');   // 扬沙
        CODE_TO_CHAR.put(900, '\uf144');   // 热
        CODE_TO_CHAR.put(901, '\uf145');   // 冷
        CODE_TO_CHAR.put(999, '\uf146');   // 未知
    }
}
