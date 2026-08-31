package com.island.wechat;

/**
 * 微信消息通知模型 — 对应 WechatNotifyDaemon 输出的 JSON 结构。
 *
 * <p>daemon 通过 Windows UserNotificationListener 读取通知中心内的微信 Toast，
 * 将最新一条微信通知写入 {@code %TEMP%/wechat_notify.json}；本类为解析结果。</p>
 */
public final class WechatNotification {

    /** daemon 未启动或输出不可读时的空实例 */
    public static final WechatNotification EMPTY = new Builder().build();

    /** 解析是否成功（JSON 存在且结构合法） */
    private final boolean valid;
    /** 自增序号：每次新通知 +1，轮询端以此检测新通知 */
    private final long seq;
    /** 通知来源 AppUserModelId（诊断用） */
    private final String appId;
    /** 通知标题（微信 Toast 第一行） */
    private final String title;
    /** 通知正文（微信 Toast 第二行起合并） */
    private final String body;
    /** 微信进程 exe 路径（通知到达时微信必然在运行，由 daemon 采集；点击跳转用） */
    private final String wechatExe;
    /** daemon 输出时间戳（epoch millis） */
    private final long timestamp;

    private WechatNotification(Builder b) {
        this.valid = b.valid;
        this.seq = b.seq;
        this.appId = b.appId;
        this.title = b.title;
        this.body = b.body;
        this.wechatExe = b.wechatExe;
        this.timestamp = b.timestamp;
    }

    public boolean isValid() { return valid; }
    public long getSeq() { return seq; }
    public String getAppId() { return appId; }
    public String getTitle() { return title; }
    public String getBody() { return body; }
    public String getWechatExe() { return wechatExe; }
    public long getTimestamp() { return timestamp; }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private boolean valid;
        private long seq = -1;
        private String appId = "";
        private String title = "";
        private String body = "";
        private String wechatExe = "";
        private long timestamp;

        public Builder valid(boolean v) { valid = v; return this; }
        public Builder seq(long v) { seq = v; return this; }
        public Builder appId(String v) { appId = v != null ? v : ""; return this; }
        public Builder title(String v) { title = v != null ? v : ""; return this; }
        public Builder body(String v) { body = v != null ? v : ""; return this; }
        public Builder wechatExe(String v) { wechatExe = v != null ? v : ""; return this; }
        public Builder timestamp(long v) { timestamp = v; return this; }

        public WechatNotification build() {
            return new WechatNotification(this);
        }
    }

    @Override
    public String toString() {
        return "WechatNotification{seq=" + seq
                + ", title='" + title + "', body='" + body + "'}";
    }
}
