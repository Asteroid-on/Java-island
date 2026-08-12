package com.island.util;

import com.island.config.AppConstants;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 轻量文件日志工具（无第三方依赖）。
 *
 * <p>主日志：单行精简格式 {@code 时间戳 [级别] [模块] 描述}，按日期滚动为
 * {@code java-island-yyyy-MM-dd.log}；ERROR 的完整堆栈单独写入
 * {@code stacktrace-yyyy-MM-dd.log}，主日志仅保留异常类名与消息摘要。</p>
 *
 * <p>日志目录默认位于用户目录（见 {@link AppConstants#getLogDir()}），可在设置中修改。
 * 写入失败时自动降级为仅控制台输出，目录恢复可写后自动重试。</p>
 */
public final class AppLogger {

    public static final String MAIN_LOG_PREFIX = "java-island-";
    public static final String STACK_LOG_PREFIX = "stacktrace-";

    private static final DateTimeFormatter LINE_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static final Object LOCK = new Object();
    private static BufferedWriter writer;
    private static BufferedWriter stackWriter;
    private static LocalDate currentDate;   // 主日志文件对应的日期
    private static LocalDate stackDate;     // 堆栈文件对应的日期（独立跟踪，保证跨天各自滚动）
    private static String openDir;          // 当前打开的日志目录
    private static volatile boolean fileLoggingDisabled;
    private static String disabledDir;      // 触发降级时的目录（目录更换后可恢复文件日志）
    private static LocalDate lastCleanupDate;   // 上次执行过期清理的日期（每天最多一次）

    // ── WARN 高频重复抑制（防止轮询类监控器在持续故障时刷屏） ──
    private static final long DEDUP_WINDOW_MS = 10_000;
    private static String dupKey;
    private static long dupTime;
    private static int dupCount;

    private AppLogger() { }

    public static void info(String module, String msg)  { log(Level.INFO,  module, msg, null); }
    public static void warn(String module, String msg)  { log(Level.WARN,  module, msg, null); }
    public static void error(String module, String msg) { log(Level.ERROR, module, msg, null); }

    /** WARN 且携带异常：主日志只记一行摘要，完整堆栈写入独立堆栈文件。 */
    public static void warn(String module, String msg, Throwable t) { log(Level.WARN, module, msg, t); }

    /** ERROR 且携带异常：主日志只记一行摘要（异常类名 + 消息），完整堆栈写入独立堆栈文件。 */
    public static void error(String module, String msg, Throwable t) { log(Level.ERROR, module, msg, t); }

    private enum Level { INFO, WARN, ERROR }

    private static void log(Level level, String module, String msg, Throwable t) {
        String ts = LocalDateTime.now().format(LINE_TS);
        String mod = (module == null || module.isBlank()) ? "?" : module.trim();
        String msgText = msg == null ? "" : msg;
        StringBuilder line = new StringBuilder(ts)
                .append(" [").append(level.name()).append("] [").append(mod).append("] ")
                .append(msgText);
        if (t != null) {
            line.append(" | ").append(t.getClass().getSimpleName()).append(": ").append(t.getMessage());
        }

        synchronized (LOCK) {
            // 每天首次写日志时惰性执行一次过期清理（无需后台定时线程）
            maybeCleanup();

            // WARN 高频重复抑制：相同消息 10 秒内只记一条，结束后补一条省略摘要
            String key = level.name() + "|" + mod + "|" + msgText;
            long now = System.currentTimeMillis();
            if (level == Level.WARN && key.equals(dupKey) && now - dupTime < DEDUP_WINDOW_MS) {
                if (dupCount < Integer.MAX_VALUE) dupCount++;
                return;
            }
            String suppressedSummary = null;
            if (dupCount > 0) {
                suppressedSummary = LocalDateTime.now().format(LINE_TS)
                        + " [WARN] [AppLogger] 上一条日志重复 " + dupCount + " 次，已省略";
                dupCount = 0;
            }
            dupKey = key;
            dupTime = now;

            if (suppressedSummary != null) {
                System.err.println(suppressedSummary);
                try { writeMain(suppressedSummary); } catch (IOException ignored) { }
            }

            // 控制台镜像，保持开发时可见
            if (level == Level.INFO) System.out.println(line);
            else System.err.println(line);

            if (fileLoggingDisabled) {
                // 目录已更换 → 清掉降级标记，重新尝试文件日志
                if (disabledDir == null || !disabledDir.equals(AppConstants.getLogDir())) {
                    fileLoggingDisabled = false;
                } else {
                    return;
                }
            }
            try {
                writeMain(line.toString());
                if (t != null) writeStack(ts, mod, msg, t);
            } catch (IOException e) {
                // 文件日志不可写时降级为仅控制台输出，避免拖垮业务线程
                System.err.println("[AppLogger] 文件日志写入失败，已降级为控制台输出: " + e.getMessage());
                fileLoggingDisabled = true;
                disabledDir = AppConstants.getLogDir();
                closeQuietly(writer);
                closeQuietly(stackWriter);
                writer = null;
                stackWriter = null;
            }
        }
    }

    private static void writeMain(String line) throws IOException {
        String dir = AppConstants.getLogDir();
        if (writer == null || currentDate == null || openDir == null
                || !currentDate.equals(LocalDate.now()) || !dir.equals(openDir)) {
            closeQuietly(writer);
            currentDate = LocalDate.now();
            File f = new File(ensureLogDir(dir), MAIN_LOG_PREFIX + currentDate.format(FILE_TS) + ".log");
            writer = Files.newBufferedWriter(f.toPath(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            openDir = dir;
        }
        writer.write(line);
        writer.newLine();
        writer.flush();
    }

    private static void writeStack(String ts, String module, String msg, Throwable t) throws IOException {
        String dir = AppConstants.getLogDir();
        if (stackWriter == null || stackDate == null || !dir.equals(openDir)
                || !stackDate.equals(LocalDate.now())) {
            closeQuietly(stackWriter);
            stackDate = LocalDate.now();
            File f = new File(ensureLogDir(dir), STACK_LOG_PREFIX + stackDate.format(FILE_TS) + ".log");
            stackWriter = Files.newBufferedWriter(f.toPath(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        stackWriter.write("==== " + ts + " [" + module + "] " + msg + " ====");
        stackWriter.newLine();
        stackWriter.write(sw.toString());
        stackWriter.newLine();
        stackWriter.flush();
    }

    private static File ensureLogDir(String dir) throws IOException {
        File d = new File(dir);
        if (!d.isDirectory() && !d.mkdirs()) {
            throw new IOException("无法创建日志目录: " + d.getAbsolutePath());
        }
        return d;
    }

    /** 今天的主日志文件（可能尚不存在）。 */
    public static File getCurrentLogFile() {
        return new File(AppConstants.getLogDir(),
                MAIN_LOG_PREFIX + LocalDate.now().format(FILE_TS) + ".log");
    }

    /** 全部主日志文件（按文件名倒序，最新在前）。 */
    public static List<File> listLogFiles() {
        return listFilesInternal(MAIN_LOG_PREFIX);
    }

    /** 全部堆栈文件（按文件名倒序，最新在前）。 */
    public static List<File> listStackTraceFiles() {
        return listFilesInternal(STACK_LOG_PREFIX);
    }

    private static List<File> listFilesInternal(String prefix) {
        File dir = new File(AppConstants.getLogDir());
        List<File> result = new ArrayList<>();
        if (!dir.isDirectory()) return result;
        File[] files = dir.listFiles((d, n) -> n.startsWith(prefix) && n.endsWith(".log"));
        if (files != null) {
            result.addAll(List.of(files));
            result.sort(Comparator.comparing(File::getName).reversed());
        }
        return result;
    }

    /** 读取今天主日志末尾 {@code maxLines} 行。 */
    public static String readCurrentLog(int maxLines) {
        return readTail(getCurrentLogFile(), maxLines);
    }

    /** 汇总所有主日志中的 ERROR 行（最新在前），用于错误报告。 */
    public static String buildErrorReport(int maxLines) {
        StringBuilder sb = new StringBuilder();
        int collected = 0;
        for (File f : listLogFiles()) {
            List<String> lines = readAllLinesSafe(f);
            for (int i = lines.size() - 1; i >= 0 && collected < maxLines; i--) {
                if (lines.get(i).contains(" [ERROR] ")) {
                    sb.append(lines.get(i)).append(System.lineSeparator());
                    collected++;
                }
            }
            if (collected >= maxLines) break;
        }
        return sb.toString();
    }

    /** 清空日志目录下所有主日志与堆栈文件（先关闭当前文件句柄）。 */
    public static void clearLogs() {
        synchronized (LOCK) {
            closeQuietly(writer);
            closeQuietly(stackWriter);
            writer = null;
            stackWriter = null;
            currentDate = null;
            stackDate = null;
            openDir = null;
            for (File f : listLogFiles()) f.delete();
            for (File f : listStackTraceFiles()) f.delete();
        }
    }

    /**
     * 按保留天数删除过期日志（主日志与堆栈文件一并处理）。
     * <p>规则：文件名中的日期早于「今天 - 保留天数」即删除；保留天数 ≤ 0 表示不自动清理。</p>
     *
     * @return 删除的文件数
     */
    public static int cleanupOldLogs() {
        int retentionDays = AppConstants.getLogRetentionDays();
        if (retentionDays <= 0) return 0;
        LocalDate cutoff = LocalDate.now().minusDays(retentionDays);
        int removed = 0;
        synchronized (LOCK) {
            for (File f : listLogFiles()) {
                LocalDate d = parseDateFromName(MAIN_LOG_PREFIX, f.getName());
                if (d != null && d.isBefore(cutoff) && f.delete()) removed++;
            }
            for (File f : listStackTraceFiles()) {
                LocalDate d = parseDateFromName(STACK_LOG_PREFIX, f.getName());
                if (d != null && d.isBefore(cutoff) && f.delete()) removed++;
            }
        }
        return removed;
    }

    /** 每天首次调用时执行一次过期清理。 */
    private static void maybeCleanup() {
        LocalDate today = LocalDate.now();
        if (lastCleanupDate == null || !lastCleanupDate.equals(today)) {
            lastCleanupDate = today;
            int removed = cleanupOldLogs();
            if (removed > 0) {
                System.out.println("[AppLogger] 已自动清理过期日志 " + removed + " 个");
            }
        }
    }

    /** 从文件名中解析日志日期（{@code prefix + yyyy-MM-dd + .log}），失败返回 null。 */
    private static LocalDate parseDateFromName(String prefix, String name) {
        if (name == null || !name.startsWith(prefix) || !name.endsWith(".log")) return null;
        String datePart = name.substring(prefix.length(), name.length() - 4);
        try {
            return LocalDate.parse(datePart, FILE_TS);
        } catch (Exception e) {
            return null;
        }
    }

    private static String readTail(File f, int maxLines) {
        if (f == null || !f.isFile()) return "";
        try {
            List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
            int from = Math.max(0, lines.size() - maxLines);
            StringBuilder sb = new StringBuilder();
            for (int i = from; i < lines.size(); i++) {
                sb.append(lines.get(i)).append(System.lineSeparator());
            }
            return sb.toString();
        } catch (IOException e) {
            return "";
        }
    }

    private static List<String> readAllLinesSafe(File f) {
        try {
            return Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return List.of();
        }
    }

    private static void closeQuietly(BufferedWriter w) {
        if (w != null) {
            try { w.close(); } catch (IOException ignored) { }
        }
    }
}
