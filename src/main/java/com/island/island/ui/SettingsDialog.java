package com.island.island.ui;

import com.island.config.AppConfig;
import com.island.config.AppConstants;
import com.island.util.AppLogger;
import com.island.util.WindowsStartupManager;
import com.formdev.flatlaf.FlatLaf;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicScrollBarUI;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 设置对话框 ：卡片分区 + 深色主题。
 */
public class SettingsDialog extends JDialog {

    // ── 主题调色板：跟随 FlatLaf（Windows 深色/浅色模式）自动切换 ──
    private static final boolean DARK = FlatLaf.isLafDark();
    private static final Color C_BG      = DARK ? new Color(28, 28, 30)   : new Color(245, 246, 248);
    private static final Color C_SIDEBAR = DARK ? new Color(34, 34, 38)   : new Color(232, 233, 237);
    private static final Color C_HOVER   = DARK ? new Color(44, 44, 50)   : new Color(222, 223, 228);
    private static final Color C_SEL     = DARK ? new Color(48, 48, 54)   : new Color(210, 211, 217);
    private static final Color C_CARD    = DARK ? new Color(38, 38, 42)   : new Color(255, 255, 255);
    private static final Color C_SECTION = DARK ? new Color(44, 44, 48)   : new Color(240, 240, 243);
    private static final Color C_TEXT    = DARK ? new Color(235, 235, 240) : new Color(30, 30, 35);
    private static final Color C_SUBTEXT = DARK ? new Color(150, 150, 160) : new Color(110, 110, 120);
    private static final Color C_MUTED   = DARK ? new Color(110, 110, 120) : new Color(140, 140, 150);
    private static final Color C_ACCENT  = new Color(0, 122, 204);
    private static final Color C_ACCENT2 = new Color(0, 140, 230);
    private static final Color C_BORDER  = DARK ? new Color(54, 54, 60)   : new Color(215, 215, 220);
    private static final Color C_GLASS       = DARK ? new Color(58, 58, 64)   : new Color(238, 238, 242);
    private static final Color C_GLASS_HOVER = DARK ? new Color(72, 72, 80)   : new Color(228, 228, 234);
    private static final Color C_GLASS_BORDER = DARK ? new Color(68, 68, 76)  : new Color(210, 210, 216);

    private static final Font F_TITLE   = new Font("Microsoft YaHei", Font.BOLD, 22);
    private static final Font F_SECTION = new Font("Microsoft YaHei", Font.BOLD, 14);
    private static final Font F_BODY    = new Font("Microsoft YaHei", Font.PLAIN, 13);
    private static final Font F_SMALL   = new Font("Microsoft YaHei", Font.PLAIN, 11);
    private static final Font F_SIDEBAR = new Font("Microsoft YaHei", Font.PLAIN, 14);

    private CardLayout cardLayout;
    private JPanel cardsPanel;
    private JList<NavItem> navList;
    private final Runnable onCacheDirChanged;

    private JLabel cachePathLabel;
    private JLabel cacheFileCountLabel;
    private JLabel logPathLabel;
    private JTextArea logTextArea;
    private JLabel logFileInfoLabel;

    private static class NavItem {
        final String label;
        NavItem(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    public SettingsDialog(Frame owner, Runnable onCacheDirChanged) {
        super(owner, "设置", true);
        this.onCacheDirChanged = onCacheDirChanged;
        // 关闭即销毁：配合托盘菜单的单例激活语义，关闭后下次点击重新创建
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        buildUI();
        setSize(800, 620);
        setLocationRelativeTo(owner);
        setResizable(false);
        getRootPane().setBorder(BorderFactory.createLineBorder(C_BORDER, 1));
        getRootPane().setBackground(C_BG);
        setBackground(C_BG);
    }

    private void buildUI() {
        ((JComponent) getContentPane()).setBorder(null);
        getContentPane().setBackground(C_BG);
        JPanel root = new JPanel(new BorderLayout(0, 0));
        root.setBackground(C_BG);
        root.setBorder(null);

        NavItem[] items = {
            new NavItem("常规"),
            new NavItem("下载与缓存"),
            new NavItem("关于"),
            new NavItem("日志与错误报告"),
        };
        navList = new JList<>(items);
        navList.setBackground(C_SIDEBAR);
        navList.setFont(F_SIDEBAR);
        navList.setFixedCellHeight(44);
        navList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        navList.setCellRenderer(new NavRenderer());
        navList.setSelectedIndex(0);
        navList.addMouseMotionListener(new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent e) {
                navList.putClientProperty("hoverIndex", navList.locationToIndex(e.getPoint()));
                navList.repaint();
            }
        });
        navList.addMouseListener(new MouseAdapter() {
            @Override public void mouseExited(MouseEvent e) {
                navList.putClientProperty("hoverIndex", -1); navList.repaint();
            }
        });

        JScrollPane navScroll = new JScrollPane(navList);
        navScroll.setBorder(null);
        navScroll.getViewport().setBorder(null);
        navScroll.setPreferredSize(new Dimension(170, 0));
        navScroll.getViewport().setBackground(C_SIDEBAR);
        root.add(navScroll, BorderLayout.WEST);

        cardLayout = new CardLayout();
        cardsPanel = new JPanel(cardLayout);
        cardsPanel.setBackground(C_BG);
        cardsPanel.add(scrollWrap(buildGeneralPanel()), "常规");
        cardsPanel.add(scrollWrap(buildCachePanel()), "下载与缓存");
        cardsPanel.add(scrollWrap(buildAboutPanel()), "关于");
        cardsPanel.add(scrollWrap(buildLogPanel()), "日志与错误报告");
        root.add(cardsPanel, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout());
        footer.setBackground(C_BG);
        footer.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, C_BORDER),
                new EmptyBorder(10, 20, 10, 20)));
        JButton okBtn = glassBtn("确定");
        okBtn.addActionListener(e -> dispose());
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
        right.add(okBtn);
        footer.add(right, BorderLayout.EAST);
        root.add(footer, BorderLayout.SOUTH);

        getContentPane().add(root);

        navList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                NavItem sel = navList.getSelectedValue();
                if (sel != null) {
                    cardLayout.show(cardsPanel, sel.label);
                    if (sel.label.contains("缓存")) refreshCachePanel();
                    else if (sel.label.contains("日志")) refreshLogPanel();
                }
            }
        });
        addWindowListener(new WindowAdapter() {
            @Override public void windowOpened(WindowEvent e) { refreshCachePanel(); }
        });
    }

    // ═══════════════════════════════════════════
    //  常规
    // ═══════════════════════════════════════════

    private JPanel buildGeneralPanel() {
        JPanel p = contentPanel("常规", "应用的基础行为设置");

        JCheckBox trayCb = new JCheckBox("启动时最小化到系统托盘");
        trayCb.setFont(F_BODY);
        trayCb.setForeground(C_TEXT);
        trayCb.setBackground(C_CARD);
        trayCb.setFocusPainted(false);
        trayCb.setBorder(new EmptyBorder(2, 0, 2, 0));
        trayCb.setSelected(AppConstants.isMinimizeToTrayEnabled());
        trayCb.addActionListener(e ->
                AppConstants.setMinimizeToTrayEnabled(trayCb.isSelected()));

        JCheckBox autoStartCb = new JCheckBox("开机时自动启动");
        autoStartCb.setFont(F_BODY);
        autoStartCb.setForeground(C_TEXT);
        autoStartCb.setBackground(C_CARD);
        autoStartCb.setFocusPainted(false);
        autoStartCb.setBorder(new EmptyBorder(2, 0, 2, 0));

        // 以注册表/快捷方式实际状态为准，修正持久化
        boolean actualRegistered = WindowsStartupManager.isRegistered();
        if (AppConstants.isAutoStartEnabled() != actualRegistered) {
            AppConstants.setAutoStartEnabled(actualRegistered);
        }
        autoStartCb.setSelected(actualRegistered);

        autoStartCb.addActionListener(e -> {
            boolean enabled = autoStartCb.isSelected();
            AppConstants.setAutoStartEnabled(enabled);
            try {
                if (enabled) {
                    WindowsStartupManager.register();
                } else {
                    WindowsStartupManager.unregister();
                }
            } catch (Exception ex) {
                // 回滚 UI 与持久化状态
                autoStartCb.setSelected(!enabled);
                AppConstants.setAutoStartEnabled(!enabled);
                JOptionPane.showMessageDialog(SettingsDialog.this,
                        "操作失败：" + ex.getMessage()
                                + "\n请尝试以管理员身份运行。",
                        "开机自启", JOptionPane.ERROR_MESSAGE);
            }
        });

        p.add(sectionCard("启动", new Component[]{
                trayCb, Box.createVerticalStrut(6), autoStartCb
        }));
        p.add(Box.createVerticalStrut(12));

        JCheckBox autoCollapseCb = new JCheckBox("扩展岛空闲 10 分钟后自动收起");
        autoCollapseCb.setFont(F_BODY);
        autoCollapseCb.setForeground(C_TEXT);
        autoCollapseCb.setBackground(C_CARD);
        autoCollapseCb.setFocusPainted(false);
        autoCollapseCb.setBorder(new EmptyBorder(2, 0, 2, 0));
        autoCollapseCb.setSelected(AppConstants.isAutoCollapseExpandedEnabled());
        autoCollapseCb.addActionListener(e ->
                AppConstants.setAutoCollapseExpandedEnabled(autoCollapseCb.isSelected()));

        JLabel autoCollapseTip = new JLabel("仅主动点击展开时生效；显示歌词或摄像头/麦克风监测指示期间不会自动收起。");
        autoCollapseTip.setFont(F_SMALL);
        autoCollapseTip.setForeground(C_MUTED);

        p.add(Box.createVerticalStrut(12));
        p.add(sectionCard("扩展岛", new Component[]{
                autoCollapseCb, Box.createVerticalStrut(4), autoCollapseTip
        }));
        return p;
    }

    // ═══════════════════════════════════════════
    //  下载与缓存
    // ═══════════════════════════════════════════

    private JPanel buildCachePanel() {
        JPanel p = contentPanel("下载与缓存", "管理歌词及封面的本地缓存数据");

        cachePathLabel = new JLabel();
        cachePathLabel.setFont(F_SMALL);
        cachePathLabel.setForeground(C_SUBTEXT);

        JButton changeBtn = glassBtn("更改目录...");
        changeBtn.addActionListener(e -> chooseCacheDir());

        JPanel pathRow = new JPanel(new BorderLayout(10, 0));
        pathRow.setOpaque(false);
        pathRow.add(cachePathLabel, BorderLayout.CENTER);
        pathRow.add(changeBtn, BorderLayout.EAST);

        cacheFileCountLabel = new JLabel();
        cacheFileCountLabel.setFont(F_SMALL);
        cacheFileCountLabel.setForeground(C_MUTED);

        JButton clearBtn = glassBtn("清空缓存");
        clearBtn.addActionListener(e -> clearCache());

        p.add(sectionCard("缓存位置", new Component[]{
            pathRow, Box.createVerticalStrut(4), cacheFileCountLabel
        }));
        p.add(Box.createVerticalStrut(12));
        p.add(sectionCard("管理", new Component[]{ clearBtn }));
        p.add(Box.createVerticalStrut(16));

        JLabel tip = new JLabel("缓存包含歌词数据和封面 URL，不包含图片文件。");
        tip.setFont(F_SMALL);
        tip.setForeground(C_MUTED);
        tip.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(tip);
        return p;
    }

    // ═══════════════════════════════════════════
    //  关于
    // ═══════════════════════════════════════════

    private JPanel buildAboutPanel() {
        JPanel p = contentPanel("关于", "版本与开发者信息");

        p.add(sectionCard("应用信息", new Component[]{
            infoLine("应用名称", "云隙泡（Java-Island）"),
            Box.createVerticalStrut(6),
            infoLine("版本", "1.1"),
            Box.createVerticalStrut(6),
            infoLine("Java", System.getProperty("java.version")),
        }));
        p.add(Box.createVerticalStrut(12));
        p.add(sectionCard("项目信息", new Component[]{
            infoLine("开发者", "Asteroid_on"),
            Box.createVerticalStrut(6),
            infoLine("许可证", "MIT License"),
            Box.createVerticalStrut(6),
            infoLine("项目地址", "github.com/Asteroid-on/Java-island"),
        }));
        return p;
    }

    // ═══════════════════════════════════════════
    //  日志与错误报告
    // ═══════════════════════════════════════════

    private JPanel buildLogPanel() {
        JPanel p = contentPanel("日志与错误报告", "查看运行日志、导出或提交错误报告");

        logPathLabel = new JLabel();
        logPathLabel.setFont(F_SMALL);
        logPathLabel.setForeground(C_SUBTEXT);

        JButton changeLogDirBtn = sizedBtn("更改目录...", 110, 30);
        changeLogDirBtn.addActionListener(e -> chooseLogDir());

        // 北部锚定容器：防止路径标签换行增高时按钮被纵向拉伸
        JPanel eastWrap = new JPanel(new BorderLayout());
        eastWrap.setOpaque(false);
        eastWrap.add(changeLogDirBtn, BorderLayout.NORTH);

        JPanel pathRow = new JPanel(new BorderLayout(10, 0));
        pathRow.setOpaque(false);
        pathRow.add(logPathLabel, BorderLayout.CENTER);
        pathRow.add(eastWrap, BorderLayout.EAST);

        logFileInfoLabel = new JLabel();
        logFileInfoLabel.setFont(F_SMALL);
        logFileInfoLabel.setForeground(C_MUTED);

        String[] retentionItems = {"7 天", "14 天", "30 天（推荐）", "60 天", "90 天", "不自动清理"};
        int[] retentionValues = {7, 14, 30, 60, 90, 0};
        JComboBox<String> retentionCb = new JComboBox<>(retentionItems);
        retentionCb.setFont(F_BODY);
        retentionCb.setPreferredSize(new Dimension(160, 28));
        retentionCb.setMaximumSize(new Dimension(160, 28));
        int currentRetention = AppConstants.getLogRetentionDays();
        for (int i = 0; i < retentionValues.length; i++) {
            if (retentionValues[i] == currentRetention) { retentionCb.setSelectedIndex(i); break; }
        }
        retentionCb.addActionListener(e -> {
            int days = retentionValues[retentionCb.getSelectedIndex()];
            AppConstants.setLogRetentionDays(days);
            AppLogger.info("Settings", "日志保留天数已设置为: "
                    + (days > 0 ? days + " 天" : "不自动清理"));
            int removed = AppLogger.cleanupOldLogs();
            refreshLogPanel();
            if (removed > 0) {
                JOptionPane.showMessageDialog(this,
                        "已清理过期日志文件 " + removed + " 个。",
                        "自动清理", JOptionPane.INFORMATION_MESSAGE);
            }
        });

        p.add(sectionCard("日志目录", new Component[]{
                pathRow, Box.createVerticalStrut(4), logFileInfoLabel,
                Box.createVerticalStrut(8), fieldRow("自动清理", retentionCb)
        }));
        p.add(Box.createVerticalStrut(12));

        logTextArea = new JTextArea();
        logTextArea.setEditable(false);
        logTextArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        Color logBg = DARK ? new Color(22, 22, 25) : new Color(250, 250, 252);
        logTextArea.setBackground(logBg);
        logTextArea.setForeground(DARK ? new Color(205, 205, 210) : new Color(40, 40, 45));
        logTextArea.setCaretColor(C_TEXT);

        JScrollPane logScroll = new JScrollPane(logTextArea);
        logScroll.setBorder(BorderFactory.createLineBorder(C_BORDER));
        logScroll.getViewport().setBackground(logBg);
        logScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        logScroll.setPreferredSize(new Dimension(10, 150));
        logScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 150));

        // 纵/横向滚动条统一为纤细圆角样式（宽 6px / 高 6px）
        logScroll.getVerticalScrollBar().setUI(new ThinScrollBarUI());
        logScroll.getVerticalScrollBar().setPreferredSize(new Dimension(6, 0));
        logScroll.getVerticalScrollBar().setUnitIncrement(16);
        logScroll.getHorizontalScrollBar().setUI(new ThinScrollBarUI());
        logScroll.getHorizontalScrollBar().setPreferredSize(new Dimension(0, 6));
        logScroll.getHorizontalScrollBar().setUnitIncrement(16);

        JLabel logTitle = new JLabel("当前日志（最近 500 行）");
        logTitle.setFont(F_BODY);
        logTitle.setForeground(C_TEXT);
        JButton refreshBtn = glassBtn("刷新");
        refreshBtn.addActionListener(e -> refreshLogPanel());
        JPanel logTitleRow = new JPanel(new BorderLayout());
        logTitleRow.setOpaque(false);
        logTitleRow.add(logTitle, BorderLayout.WEST);
        logTitleRow.add(refreshBtn, BorderLayout.EAST);

        p.add(sectionCard("当前日志", new Component[]{
                logTitleRow, Box.createVerticalStrut(8), logScroll
        }));
        p.add(Box.createVerticalStrut(12));

        JButton copyBtn = glassBtn("复制日志");
        copyBtn.addActionListener(e -> copyLogToClipboard());
        JButton exportBtn = glassBtn("导出日志...");
        exportBtn.addActionListener(e -> exportLog());
        JButton clearLogBtn = glassBtn("清空日志");
        clearLogBtn.addActionListener(e -> clearLogs());

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        btnRow.setOpaque(false);
        btnRow.add(copyBtn);
        btnRow.add(exportBtn);
        btnRow.add(clearLogBtn);

        p.add(sectionCard("管理", new Component[]{ btnRow }));
        p.add(Box.createVerticalStrut(12));

        JButton githubBtn = glassBtn("提交到 GitHub");
        githubBtn.addActionListener(e -> openGitHubIssue());
        JButton webMailBtn = glassBtn("网页版邮箱发送");
        webMailBtn.addActionListener(e -> openWebMailDraft());

        JPanel reportBtnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        reportBtnRow.setOpaque(false);
        reportBtnRow.add(githubBtn);
        reportBtnRow.add(webMailBtn);

        JLabel reportTip = new JLabel("汇总所有 ERROR 日志：GitHub Issues 预填表单 / QQ 邮箱网页版写信页，均只需浏览器");
        reportTip.setFont(F_SMALL);
        reportTip.setForeground(C_MUTED);

        p.add(sectionCard("错误报告", new Component[]{
                reportBtnRow, Box.createVerticalStrut(6), reportTip
        }));
        p.add(Box.createVerticalStrut(16));

        JLabel tip = new JLabel("主日志仅记录错误摘要，完整堆栈保存在同目录 stacktrace-*.log 文件中；过期日志按保留天数自动清理。");
        tip.setFont(F_SMALL);
        tip.setForeground(C_MUTED);
        tip.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(tip);
        return p;
    }

    // ═══════════════════════════════════════════
    //  辅助组件
    // ═══════════════════════════════════════════

    private JPanel contentPanel(String title, String desc) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(C_BG);
        p.setBorder(new EmptyBorder(28, 32, 28, 32));

        JLabel tl = new JLabel(title);
        tl.setFont(F_TITLE);
        tl.setForeground(C_TEXT);
        tl.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel dl = new JLabel(desc);
        dl.setFont(F_BODY);
        dl.setForeground(C_SUBTEXT);
        dl.setAlignmentX(Component.LEFT_ALIGNMENT);

        p.add(tl);
        p.add(Box.createVerticalStrut(4));
        p.add(dl);
        p.add(Box.createVerticalStrut(24));
        return p;
    }

    private JPanel sectionCard(String title, Component[] children) {
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(C_CARD);
        card.setBorder(new EmptyBorder(14, 18, 14, 18));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel st = new JLabel(title);
        st.setFont(F_SECTION);
        st.setForeground(C_TEXT);
        st.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(st);
        card.add(Box.createVerticalStrut(10));
        for (Component c : children) {
            if (c instanceof JComponent) ((JComponent) c).setAlignmentX(Component.LEFT_ALIGNMENT);
            card.add(c);
        }
        return card;
    }

    private JPanel fieldRow(String label, Component field) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        row.setOpaque(false);
        JLabel lbl = new JLabel(label);
        lbl.setFont(F_BODY);
        lbl.setForeground(C_SUBTEXT);
        row.add(lbl);
        row.add(field);
        return row;
    }

    private JPanel infoLine(String key, String val) {
        JPanel row = new JPanel(new BorderLayout(20, 0));
        row.setOpaque(false);
        JLabel kl = new JLabel(key);
        kl.setFont(F_BODY);
        kl.setForeground(C_MUTED);
        JLabel vl = new JLabel(val);
        vl.setFont(F_BODY);
        vl.setForeground(C_TEXT);
        row.add(kl, BorderLayout.WEST);
        row.add(vl, BorderLayout.EAST);
        return row;
    }

    private JPanel scrollWrap(JPanel inner) {
        JScrollPane sp = new JScrollPane(inner);
        sp.setBorder(null);
        sp.getViewport().setBackground(C_BG);
        sp.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        sp.getVerticalScrollBar().setPreferredSize(new Dimension(6, 0));
        sp.getVerticalScrollBar().setUI(new ThinScrollBarUI());
        JPanel w = new JPanel(new BorderLayout());
        w.setBackground(C_BG);
        w.add(sp, BorderLayout.CENTER);
        return w;
    }

    // ── 纤细圆角滚动条 UI ──

    private static class ThinScrollBarUI extends BasicScrollBarUI {
        @Override
        protected void configureScrollBarColors() {
            thumbColor = new Color(90, 90, 100);
            trackColor = new Color(0, 0, 0, 0);
        }

        @Override
        protected JButton createDecreaseButton(int orientation) {
            return zeroButton();
        }

        @Override
        protected JButton createIncreaseButton(int orientation) {
            return zeroButton();
        }

        private static JButton zeroButton() {
            JButton b = new JButton();
            Dimension z = new Dimension(0, 0);
            b.setPreferredSize(z);
            b.setMinimumSize(z);
            b.setMaximumSize(z);
            return b;
        }

        @Override
        protected void paintThumb(Graphics g, JComponent c, Rectangle r) {
            if (r.isEmpty() || !scrollbar.isEnabled()) return;
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(isThumbRollover() ? new Color(110, 110, 120) : new Color(90, 90, 100));
            // 取较短边计算圆角，纵/横两个方向都渲染为统一胶囊样式
            int arc = Math.min(r.width, r.height) - 2;
            g2.fillRoundRect(r.x + 1, r.y + 2, r.width - 2, r.height - 4, arc, arc);
            g2.dispose();
        }

        @Override
        protected void paintTrack(Graphics g, JComponent c, Rectangle trackBounds) {
            // 透明轨道 — 融入页面背景
        }
    }

    // ── 按钮 — 统一玻璃风格（自绘背景，绕开 L&F 干扰）──

    private JButton glassBtn(String text) {
        JButton b = new JButton(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(getBackground());
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                g2.setColor(C_GLASS_BORDER);
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        b.setFont(F_BODY);
        b.setBackground(C_GLASS);
        b.setForeground(C_TEXT);
        b.setFocusPainted(false);
        b.setContentAreaFilled(false);
        b.setBorder(new EmptyBorder(5, 18, 5, 18));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { b.setBackground(C_GLASS_HOVER); }
            @Override public void mouseExited(MouseEvent e)  { b.setBackground(C_GLASS); }
        });
        return b;
    }

    /** 固定尺寸的玻璃按钮：与其他 glassBtn 同风格，且不会被布局拉伸变形。 */
    private JButton sizedBtn(String text, int w, int h) {
        JButton b = glassBtn(text);
        Dimension d = new Dimension(w, h);
        b.setPreferredSize(d);
        b.setMaximumSize(d);
        return b;
    }

    // ═══════════════════════════════════════════
    //  缓存操作
    // ═══════════════════════════════════════════

    private void refreshCachePanel() {
        if (cachePathLabel == null) return;
        String dir = AppConstants.getCacheDir();
        String display = dir.isEmpty() ? "（已禁用磁盘缓存）"
                : Path.of(dir).toAbsolutePath().toString();
        cachePathLabel.setText("当前路径：" + display);

        new Thread(() -> {
            int count = 0;
            if (!dir.isEmpty()) {
                Path p = Path.of(dir);
                if (Files.exists(p)) {
                    try (var s = Files.list(p)) { count = (int) s.count(); }
                    catch (IOException ignored) {}
                }
            }
            final int c = count;
            SwingUtilities.invokeLater(() -> cacheFileCountLabel.setText("缓存文件数：" + c + " 首"));
        }).start();
    }

    private void chooseCacheDir() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择缓存目录");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        String cur = AppConstants.getCacheDir();
        if (!cur.isEmpty()) {
            Path p = Path.of(cur);
            if (Files.exists(p)) chooser.setCurrentDirectory(p.toFile());
        }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            String newDir = chooser.getSelectedFile().getAbsolutePath();
            AppConstants.setCacheDir(newDir);
            if (onCacheDirChanged != null) onCacheDirChanged.run();
            refreshCachePanel();
        }
    }

    private void clearCache() {
        int confirm = JOptionPane.showConfirmDialog(this,
                "确定要清空所有缓存的歌词和封面数据吗？\n此操作不可撤销。",
                "清空缓存", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) return;

        new Thread(() -> {
            String dir = AppConstants.getCacheDir();
            if (!dir.isEmpty()) {
                Path p = Path.of(dir);
                if (Files.exists(p)) {
                    try (var s = Files.list(p)) {
                        s.forEach(f -> { try { Files.delete(f); } catch (IOException ignored) {} });
                    } catch (IOException ignored) {}
                }
            }
            SwingUtilities.invokeLater(this::refreshCachePanel);
        }).start();
    }

    // ═══════════════════════════════════════════
    //  日志操作
    // ═══════════════════════════════════════════

    /** 错误报告接收邮箱：仅从配置 report.mail 读取，未配置时为空（邮箱路径降级为剪贴板） */
    private static final String REPORT_MAIL = AppConfig.get("report.mail", "");

    private void refreshLogPanel() {
        if (logPathLabel == null) return;
        String dir = AppConstants.getLogDir();
        logPathLabel.setText("当前路径：" + Path.of(dir).toAbsolutePath());
        logPathLabel.setToolTipText(Path.of(dir).toAbsolutePath().toString());
        logTextArea.setText("正在加载...");
        new Thread(() -> {
            String content = AppLogger.readCurrentLog(500);
            List<File> logs = AppLogger.listLogFiles();
            int stackCount = AppLogger.listStackTraceFiles().size();
            long totalBytes = 0;
            for (File f : logs) totalBytes += f.length();
            final long bytes = totalBytes;
            final int logCount = logs.size();
            final int stCount = stackCount;
            SwingUtilities.invokeLater(() -> {
                logTextArea.setText(content.isEmpty() ? "（暂无日志）" : content);
                logTextArea.setCaretPosition(logTextArea.getDocument().getLength());
                int retention = AppConstants.getLogRetentionDays();
                String policy = retention > 0 ? "保留 " + retention + " 天" : "不自动清理";
                logFileInfoLabel.setText(String.format(
                        "日志文件：%d 个（共 %.1f KB）· 堆栈文件：%d 个 · %s",
                        logCount, bytes / 1024.0, stCount, policy));
            });
        }).start();
    }

    private void chooseLogDir() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择日志保存目录");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        String cur = AppConstants.getLogDir();
        if (!cur.isEmpty()) {
            Path p = Path.of(cur);
            if (Files.exists(p)) chooser.setCurrentDirectory(p.toFile());
        }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            String newDir = chooser.getSelectedFile().getAbsolutePath();
            AppConstants.setLogDir(newDir);
            AppLogger.info("Settings", "日志目录已更改为: " + newDir);
            refreshLogPanel();
        }
    }

    private void copyLogToClipboard() {
        String content = AppLogger.readCurrentLog(2000);
        if (content.isEmpty()) {
            JOptionPane.showMessageDialog(this, "当前没有日志内容。",
                    "复制日志", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        copyToClipboard(content);
        JOptionPane.showMessageDialog(this, "今日日志已复制到剪贴板。",
                "复制日志", JOptionPane.INFORMATION_MESSAGE);
    }

    private void exportLog() {
        List<File> files = new ArrayList<>(AppLogger.listLogFiles());
        if (files.isEmpty()) {
            JOptionPane.showMessageDialog(this, "当前没有可导出的日志文件。",
                    "导出日志", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("导出日志");
        chooser.setSelectedFile(new File(System.getProperty("user.home"),
                "java-island-logs-" + LocalDate.now() + ".txt"));
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            File target = chooser.getSelectedFile();
            files.sort(Comparator.comparing(File::getName));
            new Thread(() -> {
                try (BufferedWriter w = Files.newBufferedWriter(
                        target.toPath(), StandardCharsets.UTF_8)) {
                    for (File f : files) {
                        w.write("========== " + f.getName() + " ==========");
                        w.newLine();
                        w.write(Files.readString(f.toPath(), StandardCharsets.UTF_8));
                        w.newLine();
                    }
                    SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
                            "日志已导出到：" + target.getAbsolutePath(),
                            "导出日志", JOptionPane.INFORMATION_MESSAGE));
                } catch (IOException ex) {
                    AppLogger.warn("Settings", "导出日志失败", ex);
                    SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
                            "导出失败：" + ex.getMessage(),
                            "导出日志", JOptionPane.ERROR_MESSAGE));
                }
            }).start();
        }
    }

    private void clearLogs() {
        int confirm = JOptionPane.showConfirmDialog(this,
                "确定要清空所有日志文件吗？\n此操作不可撤销。",
                "清空日志", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) return;
        AppLogger.clearLogs();
        AppLogger.info("Settings", "用户清空了所有日志");
        refreshLogPanel();
    }

    // ── 错误报告（GitHub Issues / QQ 邮箱网页版，均只需浏览器） ──

    /** 主路径：打开 GitHub Issues 预填表单（labels=bug + 标题 + Markdown 正文）。 */
    private void openGitHubIssue() {
        String subject = buildReportSubject();
        String bodyText = buildGitHubBody();
        copyToClipboard(subject + System.lineSeparator() + System.lineSeparator() + bodyText);
        try {
            String url = "https://github.com/Asteroid-on/Java-island/issues/new"
                    + "?labels=bug"
                    + "&title=" + URLEncoder.encode(subject, StandardCharsets.UTF_8)
                    + "&body=" + URLEncoder.encode(bodyText, StandardCharsets.UTF_8);
            Desktop.getDesktop().browse(URI.create(url));
            JOptionPane.showMessageDialog(this,
                    "已打开 GitHub Issues 提交页面。\n报告同时已复制到剪贴板，登录后可直接提交。",
                    "提交错误报告", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            AppLogger.warn("Settings", "打开 GitHub Issues 失败", ex);
            JOptionPane.showMessageDialog(this,
                    "无法打开浏览器。\n报告已复制到剪贴板，请手动访问 "
                            + "github.com/Asteroid-on/Java-island/issues 提交。",
                    "提交错误报告", JOptionPane.WARNING_MESSAGE);
        }
    }

    /** 次路径：跳转 QQ 邮箱网页版写信页（预填依赖登录 sid，不可靠，弹窗提供手动填写指引）。 */
    private void openWebMailDraft() {
        String subject = buildReportSubject();
        String bodyText = buildPlainBody();
        copyToClipboard(subject + System.lineSeparator() + System.lineSeparator() + bodyText);
        if (REPORT_MAIL.isEmpty()) {
            AppLogger.warn("Settings", "未配置 report.mail，跳过打开邮箱，报告已复制到剪贴板");
            JOptionPane.showMessageDialog(this,
                    "未配置报告接收邮箱（config.properties 的 report.mail）。\n报告已复制到剪贴板，请手动粘贴发送。",
                    "提交错误报告", JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            String url = "https://mail.qq.com/cgi-bin/frame_html?url=compose"
                    + "&to=" + URLEncoder.encode(REPORT_MAIL, StandardCharsets.UTF_8)
                    + "&subject=" + URLEncoder.encode(subject, StandardCharsets.UTF_8)
                    + "&body=" + URLEncoder.encode(bodyText, StandardCharsets.UTF_8);
            Desktop.getDesktop().browse(URI.create(url));
            JOptionPane.showMessageDialog(this,
                    "已打开 QQ 邮箱写信页。\n\n"
                    + "受邮箱登录会话限制，收件人/主题可能未自动填入，\n"
                    + "请按下表手动填写（主题即剪贴板内容第一行）：\n\n"
                    + "收件人：" + REPORT_MAIL + "\n"
                    + "主题：" + subject + "\n"
                    + "正文：在写信页按 Ctrl+V 粘贴",
                    "提交错误报告", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            AppLogger.warn("Settings", "打开网页版邮箱失败", ex);
            JOptionPane.showMessageDialog(this,
                    "无法打开浏览器。\n报告已复制到剪贴板，请手动发送至：" + REPORT_MAIL + "\n主题：" + subject,
                    "提交错误报告", JOptionPane.WARNING_MESSAGE);
        }
    }

    private String buildReportSubject() {
        return "[Java-island] 错误报告 "
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }

    /** 纯文本版正文（用于网页邮箱写信页）。 */
    private String buildPlainBody() {
        String errors = AppLogger.buildErrorReport(80);
        StringBuilder body = new StringBuilder();
        body.append("感谢您的反馈！以下是应用最近记录的错误摘要：")
                .append(System.lineSeparator()).append(System.lineSeparator());
        if (errors.isEmpty()) {
            body.append("（当前没有 ERROR 级别的日志）")
                    .append(System.lineSeparator()).append(System.lineSeparator());
        } else {
            body.append(errors);
        }
        body.append(System.lineSeparator())
                .append("日志目录：").append(Path.of(AppConstants.getLogDir()).toAbsolutePath())
                .append(System.lineSeparator())
                .append("应用版本：1.0")
                .append(System.lineSeparator())
                .append("Java：").append(System.getProperty("java.version"));
        String text = body.toString();
        if (text.length() > 4000) {
            text = text.substring(0, 4000)
                    + System.lineSeparator() + "（内容过长已截断，完整日志请导出后作为附件发送）";
        }
        return text;
    }

    /** Markdown 版正文（用于 GitHub Issues，日志放进代码块便于阅读）。 */
    private String buildGitHubBody() {
        String errors = AppLogger.buildErrorReport(80);
        StringBuilder body = new StringBuilder();
        body.append("### 问题描述").append(System.lineSeparator())
                .append(System.lineSeparator())
                .append("（请简要描述发现问题时正在做什么，便于定位）")
                .append(System.lineSeparator()).append(System.lineSeparator())
                .append("### 错误摘要").append(System.lineSeparator())
                .append(System.lineSeparator());
        if (errors.isEmpty()) {
            body.append("（当前没有 ERROR 级别的日志）");
        } else {
            body.append("```").append(System.lineSeparator())
                    .append(errors)
                    .append("```");
        }
        body.append(System.lineSeparator()).append(System.lineSeparator())
                .append("### 环境信息").append(System.lineSeparator())
                .append(System.lineSeparator())
                .append("- 应用版本：1.0").append(System.lineSeparator())
                .append("- Java：").append(System.getProperty("java.version"))
                .append(System.lineSeparator())
                .append("- 日志目录：").append(Path.of(AppConstants.getLogDir()).toAbsolutePath());
        String text = body.toString();
        if (text.length() > 4000) {
            text = text.substring(0, 4000)
                    + System.lineSeparator() + System.lineSeparator() + "（内容过长已截断）";
        }
        return text;
    }

    private void copyToClipboard(String text) {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(text), null);
        } catch (Exception ex) {
            AppLogger.warn("Settings", "复制到剪贴板失败", ex);
        }
    }

    // ═══════════════════════════════════════════
    //  导航渲染器（悬停高亮 + 选中态蓝色色条）
    // ═══════════════════════════════════════════

    private class NavRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value,
                                                      int index, boolean isSelected,
                                                      boolean cellHasFocus) {
            JPanel row = new JPanel(new BorderLayout(12, 0)) {
                @Override protected void paintComponent(Graphics g) {
                    int hoverIdx = list.getClientProperty("hoverIndex") instanceof Integer i ? i : -1;
                    boolean hover = (index == hoverIdx);
                    g.setColor(isSelected ? C_SEL : hover ? C_HOVER : C_SIDEBAR);
                    g.fillRect(0, 0, getWidth(), getHeight());
                    if (isSelected) {
                        g.setColor(C_ACCENT);
                        g.fillRect(0, 0, 3, getHeight());
                    }
                }
            };
            row.setOpaque(false);
            row.setBorder(new EmptyBorder(0, 14, 0, 14));

            NavItem item = (NavItem) value;
            JLabel lbl = new JLabel(item.toString());
            lbl.setFont(F_SIDEBAR);
            lbl.setForeground(isSelected ? C_TEXT : C_SUBTEXT);
            row.add(lbl, BorderLayout.CENTER);
            return row;
        }
    }
}
