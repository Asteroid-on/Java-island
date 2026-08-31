package com.island.qq;

/**
 * QQ 消息通知模型 — 对应 QqNotifyDaemon 输出的 JSON 结构。
 *
 * <p>daemon 通过 WinRT UserNotificationListener（Management 命名空间激活名）读取
 * 通知中心内的 QQ Toast，解析发送者/内容/群名后原子写入
 * {@code %TEMP%/qq_notify.json}；本类为解析结果。</p>
 */
public final class QqNotification {

    /** daemon 未启动或输出不可读时的空实例 */
    public static final QqNotification EMPTY = new Builder().build();

    /** 解析是否成功（JSON 存在且结构合法） */
    private final boolean valid;
    /** 自增序号：每次新通知 +1，轮询端以此检测新通知 */
    private final long seq;
    /** 通知来源应用显示名（诊断用） */
    private final String appId;
    /** 发送者（QQ Toast 第一行，群消息时为群内昵称） */
    private final String sender;
    /** 消息内容（QQ Toast 第二行） */
    private final String content;
    /** 群名（仅群消息有值，私聊为空串） */
    private final String groupName;
    /** daemon 输出时间戳（epoch millis） */
    private final long timestamp;

    private QqNotification(Builder b) {
        this.valid = b.valid;
        this.seq = b.seq;
        this.appId = b.appId;
        this.sender = b.sender;
        this.content = b.content;
        this.groupName = b.groupName;
        this.timestamp = b.timestamp;
    }

    public boolean isValid() { return valid; }
    public long getSeq() { return seq; }
    public String getAppId() { return appId; }
    public String getSender() { return sender; }
    public String getContent() { return content; }
    public String getGroupName() { return groupName; }
    public long getTimestamp() { return timestamp; }

    /** 是否为群消息 */
    public boolean isGroupMessage() { return !groupName.isEmpty(); }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private boolean valid;
        private long seq = -1;
        private String appId = "";
        private String sender = "";
        private String content = "";
        private String groupName = "";
        private long timestamp;

        public Builder valid(boolean v) { valid = v; return this; }
        public Builder seq(long v) { seq = v; return this; }
        public Builder appId(String v) { appId = v != null ? v : ""; return this; }
        public Builder sender(String v) { sender = v != null ? v : ""; return this; }
        public Builder content(String v) { content = v != null ? v : ""; return this; }
        public Builder groupName(String v) { groupName = v != null ? v : ""; return this; }
        public Builder timestamp(long v) { timestamp = v; return this; }

        public QqNotification build() {
            return new QqNotification(this);
        }
    }

    @Override
    public String toString() {
        return "QqNotification{seq=" + seq + ", sender='" + sender
                + "', content='" + content + "', groupName='" + groupName + "'}";
    }
}
