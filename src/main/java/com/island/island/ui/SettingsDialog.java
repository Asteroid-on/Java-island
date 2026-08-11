package com.island.island.ui;

import com.island.config.AppConstants;
import com.island.util.WindowsStartupManager;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicScrollBarUI;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 设置对话框 ：卡片分区 + 深色主题。
 */
public class SettingsDialog extends JDialog {

    private static final Color C_BG      = new Color(28, 28, 30);
    private static final Color C_SIDEBAR = new Color(34, 34, 38);
    private static final Color C_HOVER   = new Color(44, 44, 50);
    private static final Color C_SEL     = new Color(48, 48, 54);
    private static final Color C_CARD    = new Color(38, 38, 42);
    private static final Color C_SECTION = new Color(44, 44, 48);
    private static final Color C_TEXT    = new Color(235, 235, 240);
    private static final Color C_SUBTEXT = new Color(150, 150, 160);
    private static final Color C_MUTED   = new Color(110, 110, 120);
    private static final Color C_ACCENT  = new Color(0, 122, 204);
    private static final Color C_ACCENT2 = new Color(0, 140, 230);
    private static final Color C_BORDER  = new Color(54, 54, 60);
    private static final Color C_GLASS       = new Color(58, 58, 64);
    private static final Color C_GLASS_HOVER = new Color(72, 72, 80);
    private static final Color C_GLASS_BORDER = new Color(68, 68, 76);

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

    private static class NavItem {
        final String label;
        NavItem(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    public SettingsDialog(Frame owner, Runnable onCacheDirChanged) {
        super(owner, "设置", true);
        this.onCacheDirChanged = onCacheDirChanged;
        buildUI();
        setSize(640, 460);
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

        JComboBox<String> langCb = new JComboBox<>(new String[]{"中文（简体）", "English"});
        langCb.setFont(F_BODY);
        langCb.setBackground(C_GLASS);
        langCb.setForeground(C_TEXT);
        langCb.setEnabled(false);
        langCb.setPreferredSize(new Dimension(160, 28));
        langCb.setMaximumSize(new Dimension(160, 28));

        p.add(sectionCard("语言", new Component[]{ fieldRow("界面语言", langCb) }));
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
            infoLine("版本", "1.0-SNAPSHOT"),
            Box.createVerticalStrut(6),
            infoLine("Java", System.getProperty("java.version")),
        }));
        p.add(Box.createVerticalStrut(12));
        p.add(sectionCard("项目信息", new Component[]{
            infoLine("开发者", "Asteroid_on"),
            Box.createVerticalStrut(6),
            infoLine("许可证", "MIT License"),
            Box.createVerticalStrut(6),
            infoLine("项目地址", "github.com/Java-island/Java-island"),
        }));
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
            int arc = r.width - 2;
            g2.fillRoundRect(r.x + 1, r.y + 2, arc, r.height - 4, arc, arc);
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
