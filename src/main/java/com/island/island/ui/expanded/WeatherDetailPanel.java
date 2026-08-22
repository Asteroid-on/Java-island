package com.island.island.ui.expanded;

import com.island.island.ui.IslandUiStyle;
import com.island.weather.DailyForecast;
import com.island.weather.HourlyForecast;
import com.island.weather.SunEvent;
import com.island.weather.WeatherIconMapper;
import com.island.weather.WeatherInfo;

import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.image.BaseMultiResolutionImage;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * 扩展岛天气详情延伸区：点击天气条后随窗口向下无缝延伸展开的内容面板。
 * 自上而下：当前天气（位置/温度/状况/体感/湿度）、24 小时逐时预报
 * （横向排列、滚轮驱动横向滚动，未来 48 小时内的日出/日落以彩色细线标注）、
 * 当天与第二天日出时刻信息行、7 天多日天气预报列表。
 * 面板本身透明、无独立背景与圆角——背景由扩展岛窗口按「药丸 + 延伸区」
 * 统一形状整体填充，视觉上与药丸连为一体。
 * 数据由控制器在天气刷新时通过 {@link #updateWeather(WeatherInfo)} 推送；
 * 获取失败或暂无数据时显示"--°"等兜底文案。
 * 所有 Swing 访问均在 EDT。
 */
class WeatherDetailPanel {

    private final ExpandedIslandController controller;

    WeatherDetailPanel(ExpandedIslandController controller) {
        this.controller = controller;
    }

    // ── 排版几何（与 IslandUiStyle.WEATHER_DETAIL_HEIGHT=560 配套） ──
    private static final int MARGIN_X = 24;
    /** 逐时预报单格宽度 */
    private static final int HOURLY_CELL_W = 56;
    /** 逐时预报行裁剪区域 */
    private static final int HOURLY_CLIP_Y = 152;
    private static final int HOURLY_CLIP_H = 92;
    /** 日出标记颜色（暖橙） */
    private static final Color SUNRISE_COLOR = new Color(255, 183, 77);
    /** 日落标记颜色（橙红） */
    private static final Color SUNSET_COLOR = new Color(255, 112, 67);
    /** 多日预报最低温/最高温分隔符颜色 */
    private static final Color DAILY_SEP_COLOR = new Color(120, 120, 120);
    /** 日出/日落信息行首行基线（时间轴卡片下方） */
    private static final int SUN_INFO_Y = 250;
    /** 日出/日落信息行行距 */
    private static final int SUN_INFO_ROW_H = 18;
    /** 多日预报标题基线 */
    private static final int DAILY_TITLE_Y = 300;
    /** 多日预报首行基线与行高 */
    private static final int DAILY_ROW_START_Y = 326;
    private static final int DAILY_ROW_H = 35;

    private JPanel panel;
    /** 最近一次天气数据（null 表示获取失败/暂无数据） */
    private WeatherInfo weather;
    /** 逐时预报行横向滚动偏移（逻辑像素） */
    private int hourlyScroll = 0;

    private JLabel locationLabel;
    private JLabel iconLabel;
    private JLabel tempLabel;
    private JLabel conditionLabel;
    private JLabel humidityLabel;
    /** 空气质量（湿度行右侧，彩云数据源提供，无数据时留空） */
    private JLabel airQualityLabel;
    /** 紫外线（空气质量右侧，彩云数据源提供，无数据时留空） */
    private JLabel uvLabel;
    /** 城市名同行的手动刷新按钮（刷新期间禁用防重复点击） */
    private JButton refreshButton;

    // ── 帧间复用渲染资源（build/updateWeather 时准备，paint 帧内零分配） ──
    /** 16px 天气图标字体（build 时缓存，避免逐帧 deriveFont） */
    private Font iconFont16;
    /** 逐时行裁剪矩形（setBounds 复用，避免每帧 new Rectangle） */
    private final Rectangle hourlyClip = new Rectangle();
    /** 缓存的字体度量：仅在 Graphics 缩放倍率变化时重建 */
    private double metricsScale = Double.NaN;
    private FontMetrics timeFm;
    private FontMetrics tempFm;
    private FontMetrics dailyFm;
    private FontMetrics icon16Fm;
    /** 逐时行渲染缓存（updateWeather 时重建，与逐时列表同序） */
    private char[] hourlyIconChars;
    private String[] hourlyTempTexts;
    /** 日出/日落渲染缓存：轴零点分钟、轴末端分钟与事件分钟（含天数偏移，-1 无效） */
    private int hour0MinCache = -1;
    private int axisEndMinCache = -1;
    private int[] sunMarkMinutes;
    /** 日出/日落信息行渲染缓存（已拼接文本，null 表示无该数据） */
    private String todaySunriseText;
    private String todaySunsetText;
    private String tomorrowSunriseText;
    /** 多日预报渲染缓存（与多日列表同序） */
    private char[] dailyIconChars;
    private String[] dailyCondTexts;
    private String[] dailyMinTexts;
    private String[] dailyMaxTexts;

    JPanel getPanel() {
        return panel;
    }

    /** 收起扩展岛后清空面板引用（下次展开时重建） */
    void clearPanel() {
        panel = null;
    }

    /**
     * 滚轮驱动逐时预报行横向滚动（详情展开时由控制器转发）。
     * 支持来回滚动：向下滚轮向右查看未来时段，向上滚轮向左回滚；
     * 左边界固定为"现在"（偏移钳制为 0，过去时段永不出现），右边界为第二天的现在；
     * 重新展开面板或数据刷新时偏移复位回"现在"。
     */
    void scrollHourly(int delta) {
        if (panel == null || hourlyCount() == 0) {
            return;
        }
        int visibleW = Math.max(0, panel.getWidth() - MARGIN_X * 2);
        int maxScroll = Math.max(0, hourlyCount() * HOURLY_CELL_W - visibleW);
        // 下界钳制为 0：向左最多滚回"现在"，历史时段不会暴露；上界收敛至最右端
        hourlyScroll = Math.max(0, Math.min(hourlyScroll + delta, maxScroll));
        panel.repaint();
    }

    private int hourlyCount() {
        return weather != null ? weather.getHourlyForecasts().size() : 0;
    }

    /** 构建天气详情内容面板（透明，背景由窗口统一形状填充，宽度随布局铺满扩展岛） */
    JPanel build() {
        // 重新展开时逐时行复位回"现在"（单向滑动不提供反向回退）
        hourlyScroll = 0;
        // 图标字体在面板构建时缓存一次；度量缓存标记失效，新面板首帧按当前缩放重建
        iconFont16 = WeatherIconMapper.getIconFont(16f);
        metricsScale = Double.NaN;
        JPanel pnl = new JPanel(null) {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                // 直接复用传入 Graphics：设置抗锯齿后绘制，结束恢复原提示（省一次 create/dispose）
                Graphics2D g2d = (Graphics2D) g;
                Object oldAA = g2d.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                try {
                    ensureMetrics(g2d);
                    paintHourlyForecast(g2d);
                    paintSunInfo(g2d);
                    paintDailyForecast(g2d);
                } finally {
                    // 旧值可能为 null（提示从未设置过，setRenderingHint 不接受 null），仅在非 null 时恢复
                    if (oldAA != null) {
                        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAA);
                    }
                }
            }
        };
        pnl.setOpaque(false);
        // 空监听器消费延伸区上的点击：避免冒泡到窗口级监听导致扩展岛整体折叠
        pnl.addMouseListener(new MouseAdapter() { });

        locationLabel = createLabel(IslandUiStyle.LIGHT_GRAY, IslandUiStyle.WEATHER_CARD_TITLE_FONT);
        locationLabel.setBounds(MARGIN_X, 12, 300, 20);

        refreshButton = buildRefreshButton();
        pnl.add(refreshButton);

        iconLabel = createLabel(IslandUiStyle.LIGHT_GRAY, null);
        Font iconFont = WeatherIconMapper.getIconFont(36f);
        if (iconFont != null) {
            iconLabel.setFont(iconFont);
        }
        iconLabel.setBounds(MARGIN_X, 40, 48, 48);

        tempLabel = createLabel(Color.WHITE, IslandUiStyle.WEATHER_CARD_TEMP_FONT);
        tempLabel.setBounds(84, 36, 240, 56);

        conditionLabel = createLabel(IslandUiStyle.LIGHT_GRAY, IslandUiStyle.WEATHER_CARD_INFO_FONT);
        conditionLabel.setBounds(86, 94, 260, 20);

        // 湿度 / 空气质量 / 紫外线：同一行等宽三段等间距排列
        // （每段 = (面板宽 - 2*MARGIN_X) / 3，起点依次错开一个段宽）
        int infoW = (IslandUiStyle.EXPANDED_WIDTH - MARGIN_X * 2) / 3;
        humidityLabel = createLabel(IslandUiStyle.LIGHT_GRAY, IslandUiStyle.WEATHER_CARD_INFO_FONT);
        humidityLabel.setBounds(MARGIN_X, 116, infoW, 20);

        airQualityLabel = createLabel(IslandUiStyle.LIGHT_GRAY, IslandUiStyle.WEATHER_CARD_INFO_FONT);
        airQualityLabel.setBounds(MARGIN_X + infoW, 116, infoW, 20);

        uvLabel = createLabel(IslandUiStyle.LIGHT_GRAY, IslandUiStyle.WEATHER_CARD_INFO_FONT);
        uvLabel.setBounds(MARGIN_X + infoW * 2, 116, infoW, 20);

        pnl.add(locationLabel);
        pnl.add(iconLabel);
        pnl.add(tempLabel);
        pnl.add(conditionLabel);
        pnl.add(humidityLabel);
        pnl.add(airQualityLabel);
        pnl.add(uvLabel);

        panel = pnl;
        updateWeather(weather);
        return pnl;
    }

    /**
     * 缓存字体度量：仅当 Graphics 缩放倍率变化时重建，帧间复用避免逐帧分配 FontMetrics。
     * paint 各路径绘制前自行 setFont，此处的字体切换不构成依赖。
     */
    private void ensureMetrics(Graphics2D g2d) {
        double sx = g2d.getTransform().getScaleX();
        if (sx == metricsScale) {
            return;
        }
        metricsScale = sx;
        g2d.setFont(IslandUiStyle.WEATHER_FORECAST_TIME_FONT);
        timeFm = g2d.getFontMetrics();
        g2d.setFont(IslandUiStyle.WEATHER_FORECAST_TEMP_FONT);
        tempFm = g2d.getFontMetrics();
        g2d.setFont(IslandUiStyle.WEATHER_DAILY_FONT);
        dailyFm = g2d.getFontMetrics();
        if (iconFont16 != null) {
            g2d.setFont(iconFont16);
            icon16Fm = g2d.getFontMetrics();
        } else {
            icon16Fm = null;
        }
    }

    /**
     * 构建手动刷新按钮：与城市名同行（右上角、同高垂直居中），
     * 样式与卡片一致（透明背景、浅灰前景、无边框）；点击仅手动触发一次刷新，
     * 期间禁用防重复点击，刷新全部完成后于 EDT 恢复可点击。
     */
    private JButton buildRefreshButton() {
        Image icon = controller.getRefreshIcon();
        JButton btn;
        ImageIcon normalIcon = null;
        ImageIcon hoverIcon = null;
        if (icon != null) {
            // 图标模式：高质量缩放 + 按设备倍率超采样（多分辨率变体），构建时生成一次；
            // 禁用时由 Swing 默认灰化派生 disabled 图标
            int size = IslandUiStyle.WEATHER_REFRESH_ICON_SIZE;
            normalIcon = new ImageIcon(buildIcon(icon, size, false));
            hoverIcon = new ImageIcon(buildIcon(icon, size, true));
            btn = new JButton(normalIcon);
        } else {
            // 图标加载失败时回退文字模式
            btn = new JButton("刷新");
            btn.setFont(IslandUiStyle.WEATHER_CARD_TITLE_FONT);
        }
        final ImageIcon normal = normalIcon;
        final ImageIcon hover = hoverIcon;
        btn.setForeground(IslandUiStyle.LIGHT_GRAY);
        btn.setContentAreaFilled(false);
        btn.setBorderPainted(false);
        btn.setFocusPainted(false);
        btn.setFocusable(false);
        btn.setOpaque(false);
        btn.setBorder(null);
        btn.setMargin(new Insets(0, 0, 0, 0));
        btn.setRolloverEnabled(true);
        // 悬停高亮（与文字一致：变白）：图标模式切换白色图标，文字模式切换白色前景；仅在可点击时生效
        btn.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                if (btn.isEnabled()) {
                    if (hover != null) {
                        btn.setIcon(hover);
                    }
                    btn.setForeground(Color.WHITE);
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                if (normal != null) {
                    btn.setIcon(normal);
                }
                btn.setForeground(btn.isEnabled() ? IslandUiStyle.LIGHT_GRAY
                        : new Color(120, 120, 120));
            }
        });
        // 右上角固定位置：面板宽度恒定（窗口宽 EXPANDED_WIDTH），不遮挡城市名与其他信息
        btn.setBounds(IslandUiStyle.EXPANDED_WIDTH - MARGIN_X - 40, 12, 40, 20);
        btn.addActionListener(e -> {
            // 手动刷新：禁用按钮（灰色前景）避免重复点击并发多次请求；两源均完成后恢复
            btn.setEnabled(false);
            btn.setForeground(new Color(120, 120, 120));
            controller.refreshWeatherNow(() -> {
                if (refreshButton != null) {
                    refreshButton.setEnabled(true);
                    refreshButton.setForeground(IslandUiStyle.LIGHT_GRAY);
                }
            });
        });
        return btn;
    }

    /**
     * 高质量构建按钮图标：BICUBIC 缩放到目标逻辑尺寸，并按设备缩放倍率生成
     * 2x 高清变体（BaseMultiResolutionImage，高分屏自动选用），避免一步缩小与放大双重模糊；
     * white=true 时将像素染白（保留 alpha），用于悬停高亮态。构建时调用一次，帧间不参与。
     */
    private static Image buildIcon(Image src, int logicalSize, boolean white) {
        int scale = 1;
        try {
            AffineTransform tx = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice().getDefaultConfiguration().getDefaultTransform();
            scale = Math.max(1, (int) Math.ceil(tx.getScaleX()));
        } catch (Exception ignored) {
            // 设备倍率读取失败按 1x 处理
        }
        BufferedImage base = renderIconScaled(src, logicalSize, white);
        if (scale <= 1) {
            return base;
        }
        BufferedImage hiRes = renderIconScaled(src, logicalSize * scale, white);
        return new BaseMultiResolutionImage(base, hiRes);
    }

    /** 高质量缩放单个变体（可选染白） */
    private static BufferedImage renderIconScaled(Image src, int size, boolean white) {
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, size, size, null);
        } finally {
            g.dispose();
        }
        if (white) {
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    int argb = out.getRGB(x, y);
                    int a = (argb >>> 24) & 0xFF;
                    if (a != 0) {
                        out.setRGB(x, y, (a << 24) | 0xFFFFFF);
                    }
                }
            }
        }
        return out;
    }

    // ═══════════════════════════════════════
    //  24 小时逐时预报（横向滚动行）
    // ═══════════════════════════════════════

    private void paintHourlyForecast(Graphics2D g2d) {
        List<HourlyForecast> list = weather != null ? weather.getHourlyForecasts() : null;
        if (panel == null) {
            return;
        }
        int w = panel.getWidth();
        int visibleW = w - MARGIN_X * 2;
        if (visibleW <= 0) {
            return;
        }
        if (list == null || list.isEmpty()) {
            // 兑底状态：过滤后无逐时预报时显示提示文案，不绘制逐时行，其余布局不受影响
            g2d.setFont(IslandUiStyle.WEATHER_FORECAST_TIME_FONT);
            g2d.setColor(IslandUiStyle.LIGHT_GRAY);
            String emptyText = "暂无逐时预报";
            g2d.drawString(emptyText, MARGIN_X + (visibleW - timeFm.stringWidth(emptyText)) / 2,
                    HOURLY_CLIP_Y + 20);
            return;
        }
        // 数据刷新后项数变化时收敛滚动偏移：上界收敛至最右端，下界钳制为 0
        // （最小值必须为 0，禁止向左侧滚动，过去时段永远不会渲染在"现在"左侧）
        int maxScroll = Math.max(0, list.size() * HOURLY_CELL_W - visibleW);
        if (hourlyScroll > maxScroll) {
            hourlyScroll = maxScroll;
        }
        if (hourlyScroll < 0) {
            hourlyScroll = 0;
        }

        Shape oldClip = g2d.getClip();
        hourlyClip.setBounds(MARGIN_X, HOURLY_CLIP_Y, visibleW, HOURLY_CLIP_H);
        g2d.setClip(hourlyClip);
        try {
            // 图标字体不可用时回退逐时时间字体绘制（与历史行为一致）
            FontMetrics iconFm = icon16Fm != null ? icon16Fm : timeFm;
            // 仅遍历可见区间的格子：上下界按滚动偏移解析（first/last 含部分可见格），
            // 跳过全量 48 项裁剪判断，滚动动画期间降低循环开销
            int first = Math.max(0, (hourlyScroll - 1) / HOURLY_CELL_W);
            int last = Math.min(list.size() - 1, (visibleW + hourlyScroll) / HOURLY_CELL_W);
            for (int i = first; i <= last; i++) {
                int cx = MARGIN_X - hourlyScroll + i * HOURLY_CELL_W + HOURLY_CELL_W / 2;
                HourlyForecast f = list.get(i);

                // 时间标签（首格为"现在"）
                g2d.setFont(IslandUiStyle.WEATHER_FORECAST_TIME_FONT);
                g2d.setColor(IslandUiStyle.LIGHT_GRAY);
                drawCentered(g2d, timeFm, i == 0 ? "现在" : f.getTimeLabel(),
                        cx, HOURLY_CLIP_Y + 20);

                // 天气图标（drawChars 单字符绘制，零字符串分配）
                if (iconFont16 != null) {
                    g2d.setFont(iconFont16);
                }
                g2d.setColor(IslandUiStyle.LIGHT_GRAY);
                g2d.drawChars(hourlyIconChars, i, 1,
                        cx - iconFm.charWidth(hourlyIconChars[i]) / 2, HOURLY_CLIP_Y + 52);

                // 温度（预拼接文本缓存）
                g2d.setFont(IslandUiStyle.WEATHER_FORECAST_TEMP_FONT);
                g2d.setColor(Color.WHITE);
                drawCentered(g2d, tempFm, hourlyTempTexts[i], cx, HOURLY_CLIP_Y + 78);
            }
            // 日出/日落细线：按时间比例插值到相邻整点格之间，覆盖绘制在逐时格之上
            paintSunMarks(g2d);
        } finally {
            g2d.setClip(oldClip);
        }
    }

    // ═══════════════════════════════════════
    //  日出/日落时间轴细线
    // ═══════════════════════════════════════

    /**
     * 在逐时时间轴上插入未来 48 小时内的日出/日落细线（含未来两天）。
     * 时间轴首格为当前整点（取首项时间标签作为轴零点），事件分钟数（天数偏移×1440+当日分钟）
     * 相对零点的比例映射为 x 位置，如 06:07 日出即落在 6 点格与 7 点格之间的 7/60 处；
     * 已过去或超出时间轴末端的事件不绘制，与时间轴"只显示未来"的规则一致。
     */
    private void paintSunMarks(Graphics2D g2d) {
        List<SunEvent> events = weather != null ? weather.getSunEvents() : null;
        if (panel == null || events == null || events.isEmpty()
                || sunMarkMinutes == null || hour0MinCache < 0) {
            return;
        }
        for (int i = 0; i < events.size(); i++) {
            int minutes = sunMarkMinutes[i];
            if (minutes < 0 || minutes < hour0MinCache || minutes > axisEndMinCache) {
                continue; // 解析失败、已过去或超出时间轴末端
            }
            // 与逐时格中心公式对齐：格 i 中心 = MARGIN_X - scroll + i*W + W/2，
            // 事件位置同样加半格宽，确保分钟比例落在相邻整点格之间
            int x = (int) Math.round(MARGIN_X - hourlyScroll
                    + (minutes - hour0MinCache) / 60.0 * HOURLY_CELL_W + HOURLY_CELL_W / 2.0);
            int w = panel.getWidth();
            if (x < MARGIN_X || x > w - MARGIN_X) {
                continue; // 细线完全移出裁剪区
            }
            g2d.setColor(events.get(i).isRising() ? SUNRISE_COLOR : SUNSET_COLOR);
            g2d.fillRect(x, HOURLY_CLIP_Y, 1, HOURLY_CLIP_H);
        }
    }

    // ═══════════════════════════════════════
    //  日出/日落信息行（时间轴卡片下方）
    // ═══════════════════════════════════════

    /**
     * 在逐时时间轴卡片下方绘制当天与第二天的日出/日落时刻。
     * 第一行（今天）：日期标签（浅灰）+ 日出时刻（暖橙）+ 日落时刻（橙红）；
     * 第二行（明天）：日期标签（浅灰）+ 日出时刻（暖橙）。
     * 文字颜色与时间轴细线颜色语义一致；缺数据时仅绘制有数据的一侧。
     */
    private void paintSunInfo(Graphics2D g2d) {
        if (panel == null) {
            return;
        }
        if (todaySunriseText == null && todaySunsetText == null && tomorrowSunriseText == null) {
            return;
        }
        g2d.setFont(IslandUiStyle.WEATHER_FORECAST_TIME_FONT);
        int x0 = MARGIN_X + 2;
        // 第一行：今天的日出/日落
        if (todaySunriseText != null || todaySunsetText != null) {
            paintSunInfoRow(g2d, timeFm, x0, SUN_INFO_Y, "今天", todaySunriseText, todaySunsetText);
        }
        // 第二行：第二天的日出
        if (tomorrowSunriseText != null) {
            paintSunInfoRow(g2d, timeFm, x0, SUN_INFO_Y + SUN_INFO_ROW_H, "明天", tomorrowSunriseText, null);
        }
    }

    /** 绘制单行日出/日落信息：日期标签 + 日出时刻（暖橙）+ 日落时刻（橙红，null 则不绘） */
    private void paintSunInfoRow(Graphics2D g2d, FontMetrics fm, int x0, int y,
                                 String dayLabel, String sunriseText, String sunsetText) {
        g2d.setColor(IslandUiStyle.LIGHT_GRAY);
        g2d.drawString(dayLabel, x0, y);
        int x = x0 + fm.stringWidth(dayLabel) + 14;
        // 日出时刻（暖橙，与时间轴日出细线同色）
        if (sunriseText != null) {
            g2d.setColor(SUNRISE_COLOR);
            g2d.drawString(sunriseText, x, y);
            x += fm.stringWidth(sunriseText) + 16;
        }
        // 日落时刻（橙红，与时间轴日落细线同色）
        if (sunsetText != null) {
            g2d.setColor(SUNSET_COLOR);
            g2d.drawString(sunsetText, x, y);
        }
    }

    /** 解析 "HH:mm" 为当天分钟数；格式异常返回 -1 */
    private static int parseTimeToMinutes(String time) {
        if (time == null || time.isEmpty()) {
            return -1;
        }
        String[] parts = time.split(":");
        if (parts.length < 2) {
            return -1;
        }
        try {
            return Integer.parseInt(parts[0].trim()) * 60 + Integer.parseInt(parts[1].trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ═══════════════════════════════════════
    //  7 天多日天气预报列表
    // ═══════════════════════════════════════

    private void paintDailyForecast(Graphics2D g2d) {
        List<DailyForecast> list = weather != null ? weather.getDailyForecasts() : null;
        if (panel == null || list == null || list.isEmpty() || dailyIconChars == null) {
            return;
        }
        int w = panel.getWidth();

        g2d.setFont(IslandUiStyle.WEATHER_CARD_TITLE_FONT);
        g2d.setColor(IslandUiStyle.LIGHT_GRAY);
        g2d.drawString("多日天气预报", MARGIN_X, DAILY_TITLE_Y);

        String sep = " / ";
        int sepW = dailyFm.stringWidth(sep);
        int maxX = w - MARGIN_X;
        for (int i = 0; i < list.size(); i++) {
            int y = DAILY_ROW_START_Y + i * DAILY_ROW_H;
            DailyForecast d = list.get(i);

            // 左：日期 + 星期
            g2d.setFont(IslandUiStyle.WEATHER_DAILY_FONT);
            g2d.setColor(Color.WHITE);
            g2d.drawString(d.getDateLabel(), MARGIN_X, y);
            g2d.setColor(IslandUiStyle.LIGHT_GRAY);
            g2d.drawString(d.getWeekLabel(), MARGIN_X + dailyFm.stringWidth(d.getDateLabel()) + 14, y);

            // 中：天气图标 + 中文天气状况（图标右侧，与天气条下行风格一致）
            if (iconFont16 != null) {
                g2d.setFont(iconFont16);
            }
            g2d.setColor(IslandUiStyle.LIGHT_GRAY);
            int iconX = w / 2;
            g2d.drawChars(dailyIconChars, i, 1, iconX, y);
            if (dailyCondTexts[i] != null) {
                int iconW = icon16Fm != null ? icon16Fm.charWidth(dailyIconChars[i])
                        : dailyFm.charWidth(dailyIconChars[i]);
                g2d.setFont(IslandUiStyle.WEATHER_COND_FONT);
                g2d.drawString(dailyCondTexts[i], iconX + iconW + 4, y);
            }

            // 右：最低温 / 最高温（最高温白色右对齐，分隔与最低温灰色）
            g2d.setFont(IslandUiStyle.WEATHER_DAILY_FONT);
            String minT = dailyMinTexts[i];
            String maxT = dailyMaxTexts[i];
            int maxW = dailyFm.stringWidth(maxT);
            int minW = dailyFm.stringWidth(minT);
            g2d.setColor(Color.WHITE);
            g2d.drawString(maxT, maxX - maxW, y);
            g2d.setColor(DAILY_SEP_COLOR);
            g2d.drawString(sep, maxX - maxW - sepW, y);
            g2d.setColor(IslandUiStyle.LIGHT_GRAY);
            g2d.drawString(minT, maxX - maxW - sepW - minW, y);
        }
    }

    private void drawCentered(Graphics2D g2d, FontMetrics fm, String text, int centerX, int baselineY) {
        g2d.drawString(text, centerX - fm.stringWidth(text) / 2, baselineY);
    }

    private JLabel createLabel(Color foreground, Font font) {
        JLabel label = new JLabel();
        label.setOpaque(false);
        label.setForeground(foreground);
        if (font != null) {
            label.setFont(font);
        }
        return label;
    }

    /** 应用最新天气数据（EDT）；info 为 null 时显示兑底状态 */
    void updateWeather(WeatherInfo info) {
        this.weather = info;
        // 数据刷新会重建逐时列表（首项为新的当前时段），复位滚动偏移
        // 让"现在"始终回到可见区域最左侧，历史时段不会因旧偏移残留而可见
        hourlyScroll = 0;
        rebuildRenderCaches(info);
        if (panel == null) {
            return;
        }
        if (info == null) {
            locationLabel.setText("未知位置");
            tempLabel.setText("--°");
            conditionLabel.setText("天气不可用");
            humidityLabel.setText("湿度 --%");
            airQualityLabel.setText("");
            uvLabel.setText("");
            iconLabel.setText(String.valueOf(WeatherIconMapper.getIconChar("未知")));
        } else {
            locationLabel.setText(info.getLocation() != null && !info.getLocation().isEmpty()
                    ? info.getLocation() : "未知位置");
            tempLabel.setText(info.getFormattedTemperature());
            conditionLabel.setText(info.getCondition() != null && !info.getCondition().isEmpty()
                    ? info.getCondition() : "未知");
            humidityLabel.setText(info.hasHumidity()
                    ? "湿度 " + Math.round(info.getHumidity()) + "%" : "湿度 --%");
            // 空气质量与紫外线（彩云提供，缺失时留空保持等间距布局）
            if (info.hasAirQuality()) {
                airQualityLabel.setText("空气质量 " + info.getAirQualityIndex() + " "
                        + info.getAirQualityDesc());
            } else {
                airQualityLabel.setText("");
            }
            if (info.hasUltraviolet()) {
                String uv = "紫外线 " + Math.round(info.getUvIndex());
                if (info.getUvDesc() != null && !info.getUvDesc().isEmpty()) {
                    uv += " " + info.getUvDesc();
                }
                uvLabel.setText(uv);
            } else {
                uvLabel.setText("");
            }
            char iconChar = info.getWeatherCode() >= 0
                    ? WeatherIconMapper.getIconChar(info.getWeatherCode())
                    : WeatherIconMapper.getIconChar(info.getCondition());
            iconLabel.setText(String.valueOf(iconChar));
        }
        panel.repaint();
    }

    /**
     * 重建逐时/日出日落/多日预报的渲染缓存（数据刷新时调用，paint 帧内零分配）：
     * 图标字符、温度文本、事件分钟数、信息行文本等一次算好，帧间直接引用。
     */
    private void rebuildRenderCaches(WeatherInfo info) {
        // 逐时行：图标字符与温度文本
        List<HourlyForecast> hourly = info != null ? info.getHourlyForecasts() : null;
        if (hourly != null && !hourly.isEmpty()) {
            int n = hourly.size();
            hourlyIconChars = new char[n];
            hourlyTempTexts = new String[n];
            for (int i = 0; i < n; i++) {
                HourlyForecast f = hourly.get(i);
                hourlyIconChars[i] = f.getWeatherCode() >= 0
                        ? WeatherIconMapper.getIconChar(f.getWeatherCode())
                        : WeatherIconMapper.getIconChar(f.getCondition());
                hourlyTempTexts[i] = Math.round(f.getTemperature()) + "°";
            }
            hour0MinCache = parseTimeToMinutes(hourly.get(0).getTimeLabel());
            axisEndMinCache = hour0MinCache >= 0 ? hour0MinCache + n * 60 : -1;
        } else {
            hourlyIconChars = null;
            hourlyTempTexts = null;
            hour0MinCache = -1;
            axisEndMinCache = -1;
        }
        // 日出/日落：事件分钟数与信息行文本
        List<SunEvent> events = info != null ? info.getSunEvents() : null;
        if (events != null && !events.isEmpty()) {
            sunMarkMinutes = new int[events.size()];
            String sr = null;
            String ss = null;
            String tr = null;
            for (int i = 0; i < events.size(); i++) {
                SunEvent e = events.get(i);
                int m = parseTimeToMinutes(e.getTime());
                sunMarkMinutes[i] = m < 0 ? -1 : m + e.getDayOffset() * 1440;
                if (e.getDayOffset() == 0) {
                    if (e.isRising()) {
                        sr = e.getTime();
                    } else {
                        ss = e.getTime();
                    }
                } else if (e.getDayOffset() == 1 && e.isRising()) {
                    tr = e.getTime();
                }
            }
            todaySunriseText = sr != null ? "日出 " + sr : null;
            todaySunsetText = ss != null ? "日落 " + ss : null;
            tomorrowSunriseText = tr != null ? "日出 " + tr : null;
        } else {
            sunMarkMinutes = null;
            todaySunriseText = null;
            todaySunsetText = null;
            tomorrowSunriseText = null;
        }
        // 多日预报：图标字符、中文状况与最低温/最高温文本
        List<DailyForecast> daily = info != null ? info.getDailyForecasts() : null;
        if (daily != null && !daily.isEmpty()) {
            int n = daily.size();
            dailyIconChars = new char[n];
            dailyCondTexts = new String[n];
            dailyMinTexts = new String[n];
            dailyMaxTexts = new String[n];
            for (int i = 0; i < n; i++) {
                DailyForecast d = daily.get(i);
                dailyIconChars[i] = d.getWeatherCode() >= 0
                        ? WeatherIconMapper.getIconChar(d.getWeatherCode())
                        : WeatherIconMapper.getIconChar(d.getCondition());
                String cond = d.getCondition();
                dailyCondTexts[i] = cond != null && !cond.isEmpty() ? cond : null;
                dailyMinTexts[i] = d.hasTempMin() ? Math.round(d.getTempMin()) + "°" : "--°";
                dailyMaxTexts[i] = d.hasTempMax() ? Math.round(d.getTempMax()) + "°" : "--°";
            }
        } else {
            dailyIconChars = null;
            dailyCondTexts = null;
            dailyMinTexts = null;
            dailyMaxTexts = null;
        }
    }
}
