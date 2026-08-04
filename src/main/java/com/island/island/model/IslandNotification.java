package com.island.island.model;

/**
 * 云隙泡通知
 */
public class IslandNotification {
    public String title;
    public String message;
    public long timestamp;

    public IslandNotification(String title, String message) {
        this.title = title;
        this.message = message;
        this.timestamp = System.currentTimeMillis();
    }
}
