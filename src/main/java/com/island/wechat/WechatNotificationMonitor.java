package com.island.wechat;

import com.island.monitor.AbstractPollingMonitor;

import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.concurrent.TimeUnit;

/**
 * 微信消息通知监控器（WatchService 事件驱动 + 轮询兜底双通道）。
 *
 * <p>主通道：{@link WatchService} 监听 {@code %TEMP%} 目录，daemon 原子写入
 * {@code wechat_notify.json} 的瞬间（毫秒级）即触发读取与 seq 检测，消除轮询的
 * 0~100ms 抖动；轮询兜底 500ms（WatchService 失效时仍可用，正常时做一致性检查）。
 * 通过自增 {@code seq} 检测新通知（基线 seq=0 被 seq&gt;0 条件过滤，daemon 重启后
 * 第一条真实消息正常触发），仅在变化时回调监听器。</p>
 */
public final class WechatNotificationMonitor extends AbstractPollingMonitor {

    /** 轮询兜底间隔（主通道为 WatchService 事件驱动，此值仅作故障兜底） */
    private static final long POLL_INTERVAL_MS = 500;

    private WechatListener listener;
    private volatile long lastSeq = -1;
    /** 最近一次状态快照（点击跳转/图标加载等场景复用） */
    private volatile WechatNotification lastSnapshot = WechatNotification.EMPTY;

    private WatchService watchService;
    private volatile boolean watching = true;

    public WechatNotificationMonitor() {
        super("WechatNotificationMonitor", POLL_INTERVAL_MS, TimeUnit.MILLISECONDS, false);
        startWatchThread();
    }

    public void setListener(WechatListener listener) {
        this.listener = listener;
    }

    /** 最近一次完整状态快照 */
    public WechatNotification getLastSnapshot() {
        return lastSnapshot;
    }

    /** 启动 WatchService 事件监听线程（失败时仅轮询兜底） */
    private void startWatchThread() {
        try {
            watchService = FileSystems.getDefault().newWatchService();
            Path dir = Paths.get(System.getProperty("java.io.tmpdir"));
            dir.register(watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY);
            Thread t = new Thread(this::watchLoop, "WechatNotifyWatch");
            t.setDaemon(true);
            t.start();
        } catch (Exception e) {
            logError("WatchService 初始化失败，仅轮询兜底: " + e.getMessage());
            watchService = null;
        }
    }

    /** 事件循环：%TEMP% 目录事件量大，仅匹配 wechat_notify 相关事件才读取状态 */
    private void watchLoop() {
        while (watching) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException e) {
                return;
            } catch (ClosedWatchServiceException e) {
                return;
            } catch (Exception e) {
                continue;
            }
            boolean match = false;
            for (WatchEvent<?> ev : key.pollEvents()) {
                WatchEvent.Kind<?> kind = ev.kind();
                if (kind == StandardWatchEventKinds.OVERFLOW) {
                    // 事件队列溢出：主动查询一次保证不漏
                    match = true;
                    continue;
                }
                Object ctx = ev.context();
                if (ctx != null && ctx.toString().contains("wechat_notify")) {
                    match = true;
                }
            }
            key.reset();
            if (match) {
                try {
                    poll();
                } catch (Exception ignored) {
                }
            }
        }
    }

    @Override
    protected void onStart() {
        // 启动基线对齐：Java 重启而 daemon 仍在运行时，状态文件里可能是历史通知，
        // 先对齐 seq 基线，避免把旧通知当新通知展示
        try {
            WechatNotification cur = WechatNotificationBridge.query();
            if (cur.isValid()) {
                lastSeq = cur.getSeq();
                lastSnapshot = cur;
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void poll() {
        // WatchService 线程与轮询兜底线程并发调用，seq 检测与回调必须互斥
        synchronized (this) {
            WechatNotification current = WechatNotificationBridge.query();
            if (!current.isValid()) {
                return;
            }
            lastSnapshot = current;

            // seq 变化处理：基线 seq=0 也更新 lastSeq（但不触发）——否则 daemon 重启
            // 后 seq 归零重计数时，残留 JSON 的 seq 与重启后第一条消息的 seq 相同，
            // 会被误判为“已处理”导致拉起进程后的第一次弹窗不显示
            long seq = current.getSeq();
            if (seq != lastSeq) {
                lastSeq = seq;
                if (seq > 0) {
                    fireNotification(current);
                }
            }
        }
    }

    @Override
    protected void onStop() {
        watching = false;
        if (watchService != null) {
            try {
                watchService.close();
            } catch (Exception ignored) {
            }
        }
    }

    private void fireNotification(WechatNotification n) {
        if (listener != null) {
            try {
                listener.onWechatNotification(n);
            } catch (Exception e) {
                logError("回调监听器异常: " + e.getMessage());
            }
        }
    }

    /** 微信通知监听器 */
    @FunctionalInterface
    public interface WechatListener {
        /** 收到新的微信消息通知（在守护线程中调用，需自行切换到 EDT） */
        void onWechatNotification(WechatNotification notification);
    }
}
