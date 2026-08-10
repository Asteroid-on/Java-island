package com.island.util;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * 轻量 SVG 图标渲染器 —— 解析 path/d 属性 → Path2D → BufferedImage。
 * 支持 M/L/H/V/C/Z 命令，覆盖绝大多数常见图标。
 */
public final class SvgIcon {

    private SvgIcon() {}

    /**
     * 从 classpath 资源或文件系统加载 SVG，渲染为指定尺寸的图标。
     * @param resourcePath  classpath 路径（如 "/icons/logout.svg"）或文件系统绝对路径
     * @param size          输出图像边长（像素）
     * @param strokeColor   描边色（SVG fill="none" 时生效）
     */
    public static BufferedImage load(String resourcePath, int size, Color strokeColor) {
        String svg = readSvg(resourcePath);
        if (svg == null || svg.isEmpty()) return fallback(size, strokeColor);

        float[] vb = parseViewBox(svg);
        float vbW = vb[2], vbH = vb[3];
        float scale = Math.min(size / vbW, size / vbH) * 0.82f; // 留 18% 边距
        float offsetX = (size - vbW * scale) / 2f;
        float offsetY = (size - vbH * scale) / 2f;

        List<Shape> shapes = new ArrayList<>();
        extractPaths(svg, shapes);

        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g2.setStroke(new BasicStroke(2f * scale, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setColor(strokeColor);

            AffineTransform at = AffineTransform.getTranslateInstance(offsetX, offsetY);
            at.scale(scale, scale);

            for (Shape s : shapes) {
                g2.draw(at.createTransformedShape(s));
            }
        } finally {
            g2.dispose();
        }
        return img;
    }

    // ── 文件读取 ──

    private static String readSvg(String path) {
        // 优先 classpath
        InputStream is = SvgIcon.class.getResourceAsStream(path);
        if (is == null) {
            try { is = Files.newInputStream(Path.of(path)); }
            catch (IOException e) { return null; }
        }
        try (Scanner s = new Scanner(is, StandardCharsets.UTF_8).useDelimiter("\\A")) {
            return s.hasNext() ? s.next() : null;
        }
    }

    // ── viewBox 解析 ──

    private static float[] parseViewBox(String svg) {
        String key = "viewBox=\"";
        int i = svg.indexOf(key);
        if (i < 0) { key = "viewBox='"; i = svg.indexOf(key); }
        if (i < 0) return new float[]{0, 0, 48, 48};

        int s = i + key.length();
        int e = svg.indexOf('"', s);
        if (e < 0) e = svg.indexOf('\'', s);
        if (e < 0) return new float[]{0, 0, 48, 48};

        String[] parts = svg.substring(s, e).trim().split("[\\s,]+");
        return new float[]{
            Float.parseFloat(parts[0]), Float.parseFloat(parts[1]),
            Float.parseFloat(parts[2]), Float.parseFloat(parts[3])
        };
    }

    // ── path 提取 ──

    private static void extractPaths(String svg, List<Shape> out) {
        int pos = 0;
        while ((pos = svg.indexOf(" d=\"", pos)) >= 0) {
            int start = pos + 4;
            int end = svg.indexOf('"', start);
            if (end < 0) break;
            out.add(parsePath(svg.substring(start, end)));
            pos = end + 1;
        }
    }

    // ── SVG path d 属性 → Path2D ──

    static Shape parsePath(String d) {
        Path2D.Float p = new Path2D.Float();
        float cx = 0, cy = 0, sx = 0, sy = 0;
        int i = 0, n = d.length();
        char cmd = 0;

        while (i < n) {
            while (i < n && isSep(d.charAt(i))) i++;
            if (i >= n) break;

            char c = d.charAt(i);
            if (isCmd(c)) { cmd = c; i++; continue; }

            switch (cmd) {
                case 'M': case 'm': {
                    float[] xy = parse2(d, i); i = (int) xy[2];
                    float x = xy[0], y = xy[1];
                    if (cmd == 'm') { x += cx; y += cy; }
                    p.moveTo(x, y); cx = x; cy = y; sx = x; sy = y;
                    cmd = (cmd == 'M') ? 'L' : 'l';
                    break;
                }
                case 'L': case 'l': {
                    float[] xy = parse2(d, i); i = (int) xy[2];
                    float x = xy[0], y = xy[1];
                    if (cmd == 'l') { x += cx; y += cy; }
                    p.lineTo(x, y); cx = x; cy = y;
                    break;
                }
                case 'H': case 'h': {
                    float[] ns = parseN(d, i, 1); i = (int) ns[1];
                    float x = ns[0];
                    if (cmd == 'h') x += cx;
                    p.lineTo(x, cy); cx = x;
                    break;
                }
                case 'V': case 'v': {
                    float[] ns = parseN(d, i, 1); i = (int) ns[1];
                    float y = ns[0];
                    if (cmd == 'v') y += cy;
                    p.lineTo(cx, y); cy = y;
                    break;
                }
                case 'C': case 'c': {
                    float[] ns = parseN(d, i, 6); i = (int) ns[6];
                    float x1 = ns[0], y1 = ns[1], x2 = ns[2], y2 = ns[3], x = ns[4], y = ns[5];
                    if (cmd == 'c') { x1 += cx; y1 += cy; x2 += cx; y2 += cy; x += cx; y += cy; }
                    p.curveTo(x1, y1, x2, y2, x, y); cx = x; cy = y;
                    break;
                }
                case 'Z': case 'z':
                    p.closePath(); cx = sx; cy = sy;
                    i++; break;
                default:
                    i++; break; // 跳过未知命令
            }
        }
        return p;
    }

    // ── 数字解析辅助 ──

    private static boolean isSep(char c) { return c == ',' || Character.isWhitespace(c); }
    private static boolean isCmd(char c) { return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'); }

    /** 解析两个浮点数，返回 [x, y, nextIndex] */
    private static float[] parse2(String s, int i) {
        float[] ns = parseN(s, i, 2);
        return new float[]{ns[0], ns[1], ns[2]};
    }

    /** 解析 count 个浮点数，返回 [n0, n1, ..., nextIndex] */
    private static float[] parseN(String s, int i, int count) {
        float[] out = new float[count + 1];
        for (int k = 0; k < count; k++) {
            while (i < s.length() && isSep(s.charAt(i))) i++;
            int end = i;
            if (end < s.length() && (s.charAt(end) == '-' || s.charAt(end) == '+')) end++;
            while (end < s.length() && (Character.isDigit(s.charAt(end)) || s.charAt(end) == '.')) end++;
            out[k] = Float.parseFloat(s.substring(i, end));
            i = end;
        }
        out[count] = i;
        return out;
    }

    // ── Fallback ──

    private static BufferedImage fallback(int size, Color c) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(c);
        g.fillOval(2, 2, size - 4, size - 4);
        g.dispose();
        return img;
    }
}
