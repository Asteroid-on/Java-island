package com.island.wifi;

import com.island.monitor.AbstractPollingMonitor;

import java.util.concurrent.TimeUnit;

/**
 * WiFi连接监听器 - 通过Windows命令行工具检测WiFi连接状态
 * 注意：由于JNA实现WLAN API较为复杂，暂时保留原有的netsh实现
 */
public class WifiMonitor extends AbstractPollingMonitor {

    private static final long POLL_INTERVAL_MS = 2000; // 2秒轮询一次

    private WifiListener listener;
    private String lastKnownNetwork = null;

    public interface WifiListener {
        void onWifiConnected(String networkName);
        void onWifiDisconnected(String networkName);
    }

    public WifiMonitor() {
        super("WiFi-Monitor", POLL_INTERVAL_MS, TimeUnit.MILLISECONDS, true);
    }

    public void setListener(WifiListener listener) {
        this.listener = listener;
    }

    @Override
    protected void onStart() {
        // 启动时立即检查一次
        try {
            poll();
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void poll() {
        try {
            String currentNetwork = getCurrentWifiNetwork();
            
            if (currentNetwork != null && lastKnownNetwork == null) {
                // WiFi从断开变为连接
                debug("WiFi已连接到: " + currentNetwork);
                lastKnownNetwork = currentNetwork;
                if (listener != null) listener.onWifiConnected(currentNetwork);
            } else if (currentNetwork == null && lastKnownNetwork != null) {
                // WiFi从连接变为断开
                debug("WiFi已断开，之前连接的是: " + lastKnownNetwork);
                if (listener != null) listener.onWifiDisconnected(lastKnownNetwork);
                lastKnownNetwork = null;
            } else if (currentNetwork != null && lastKnownNetwork != null && !currentNetwork.equals(lastKnownNetwork)) {
                // WiFi网络切换
                debug("WiFi网络切换: " + lastKnownNetwork + " -> " + currentNetwork);
                String oldNetwork = lastKnownNetwork;
                lastKnownNetwork = currentNetwork;
                if (listener != null) {
                    listener.onWifiDisconnected(oldNetwork);
                    listener.onWifiConnected(currentNetwork);
                }
            }
        } catch (Exception e) {
            debug("WiFi监控错误: " + e.getMessage());
        }
    }

    /**
     * 获取当前连接的WiFi网络名称
     * 使用Windows命令行工具netsh wlan show interfaces
     */
    private String getCurrentWifiNetwork() {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder("cmd", "/c", "netsh wlan show interfaces");
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream(), "GBK")); // Windows中文系统通常使用GBK编码
            
            String line;
            while ((line = reader.readLine()) != null) {
                // 寻找SSID行，格式通常是 "SSID                   : 网络名称"
                if (line.trim().startsWith("SSID") && line.contains(":")) {
                    String[] parts = line.split(":", 2);
                    if (parts.length > 1) {
                        String ssid = parts[1].trim();
                        if (!ssid.isEmpty()) {
                            return ssid;
                        }
                    }
                }
            }
            
            process.waitFor();
        } catch (Exception e) {
            debug("获取WiFi信息失败: " + e.getMessage());
        }
        return null; // 未连接到任何WiFi
    }
}