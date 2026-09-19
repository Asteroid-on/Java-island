package com.island.privacy;

import com.island.monitor.AbstractPollingMonitor;

import java.util.concurrent.TimeUnit;

/**
 * 摄像头/麦克风使用状态轮询监控器。
 *
 * <p>每秒通过 {@link WindowsPrivacyScanner} 读取注册表
 * {@code CapabilityAccessManager\ConsentStore} 判断设备占用状态，
 * 仅在状态发生变化时回调监听器。</p>
 */
public class PrivacyMonitor extends AbstractPollingMonitor {

    private static final long POLL_INTERVAL_MS = 1000;

    private PrivacyListener listener;
    private volatile boolean lastCameraInUse = false;
    private volatile boolean lastMicInUse = false;

    @FunctionalInterface
    public interface PrivacyListener {
        void onUsageChanged(boolean cameraInUse, boolean micInUse);
    }

    public PrivacyMonitor() {
        super("Privacy-Monitor", POLL_INTERVAL_MS, TimeUnit.MILLISECONDS, false);
    }

    public void setListener(PrivacyListener listener) {
        this.listener = listener;
    }

    @Override
    protected void onStart() {
        try { poll(); } catch (Exception ignored) { }
    }

    @Override
    protected void poll() {
        boolean camera = WindowsPrivacyScanner.isCameraInUse();
        boolean mic = WindowsPrivacyScanner.isMicrophoneInUse();
        if (camera != lastCameraInUse || mic != lastMicInUse) {
            lastCameraInUse = camera;
            lastMicInUse = mic;
            fireChanged(camera, mic);
        }
    }

    /**
     * 以当前状态重发一次回调：状态与监听器侧一致时下游 updateUsage 全程无副作用
     * （不记日志、不改标志、不启动动画/弹出）。供启动预热首占用回调链使用，
     * 把类加载/JIT/EDT 冷唤醒等一次性成本移出真实设备占用时刻。
     */
    public void resendCurrentState() {
        fireChanged(lastCameraInUse, lastMicInUse);
    }

    private void fireChanged(boolean camera, boolean mic) {
        PrivacyListener l = listener;
        if (l != null) {
            try {
                l.onUsageChanged(camera, mic);
            } catch (Exception ex) {
                logError("回调监听器异常: " + ex.getMessage());
            }
        }
    }
}
