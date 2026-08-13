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
