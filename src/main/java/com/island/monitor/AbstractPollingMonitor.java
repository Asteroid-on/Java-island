package com.island.monitor;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 轮询监控器抽象基类 — 封装 {@link ScheduledExecutorService} 的启动/停止生命周期。
 *
 * <p>子类只需实现 {@link #poll()} 方法和提供轮询参数即可获得统一的线程管理和调试日志。</p>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * public class MyMonitor extends AbstractPollingMonitor {
 *     public MyMonitor() { super("MyMonitor", 2, TimeUnit.SECONDS, true); }
 *     protected void poll() { /* 轮询逻辑 * / }
 * }
 * }</pre>
 */
public abstract class AbstractPollingMonitor {

    /** 调度器名称前缀 */
    private final String name;

    /** 轮询间隔 */
    private final long interval;

    /** 轮询间隔时间单位 */
    private final TimeUnit timeUnit;

    /** 初始延迟（等于 interval） */
    private final long initialDelay;

    /** 是否开启调试日志 */
    private final boolean debug;

    private final ScheduledExecutorService scheduler;

    /** 运行状态标志，volatile 保证跨线程可见性 */
    private volatile boolean running = false;

    /**
     * 构造轮询监控器。
     *
     * @param name     线程名称（用于调试）
     * @param interval 轮询间隔
     * @param timeUnit 时间单位
     * @param debug    是否开启调试日志
     */
    @SuppressWarnings("this-escape")
    protected AbstractPollingMonitor(String name, long interval, TimeUnit timeUnit, boolean debug) {
        this.name = name;
        this.interval = interval;
        this.timeUnit = timeUnit;
        this.initialDelay = interval;
        this.debug = debug;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 同 {@link #AbstractPollingMonitor(String, long, TimeUnit, boolean)}，
     * 初始延迟固定为 1 秒。
     */
    protected AbstractPollingMonitor(String name, long interval, TimeUnit timeUnit,
                                      boolean debug, long initialDelay) {
        this.name = name;
        this.interval = interval;
        this.timeUnit = timeUnit;
        this.initialDelay = initialDelay;
        this.debug = debug;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 启动监控。若已启动则忽略。
     */
    public void start() {
        if (running) return;
        running = true;
        onStart();
        scheduler.scheduleWithFixedDelay(this::pollSafe, initialDelay, interval, timeUnit);
    }

    /**
     * 停止监控。释放线程资源。
     */
    public void stop() {
        running = false;
        scheduler.shutdownNow();
        onStop();
    }

    /**
     * 子类实现具体的轮询逻辑（在守护线程中调用）。
     */
    protected abstract void poll() throws Exception;

    /**
     * 启动时的回调（子类可重写，比如执行首次轮询）。
     */
    protected void onStart() {
    }

    /**
     * 停止时的回调（子类可重写，用于清理资源）。
     */
    protected void onStop() {
    }

    /**
     * 安全轮询包装 — 统一异常处理，防止未捕获异常导致调度器静默停止。
     */
    private void pollSafe() {
        if (!running) return;
        try {
            poll();
        } catch (InterruptedException e) {
            // 调度器被 shutdownNow() 中断，正常情况，不记录日志
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            debug("poll 异常: " + e.getMessage());
        }
    }

    /**
     * 调试日志（仅在 debug 模式下输出）。
     */
    protected void debug(String msg) {
        if (debug) {
            System.out.println("[" + name + "] " + msg);
        }
    }

    /**
     * 错误日志（总是输出到 stderr）。
     */
    protected void logError(String msg) {
        System.err.println("[" + name + "] " + msg);
    }

    /**
     * 是否正在运行。
     */
    protected boolean isRunning() {
        return running;
    }

    /**
     * 获取调度器名称。
     */
    protected String getName() {
        return name;
    }
}
