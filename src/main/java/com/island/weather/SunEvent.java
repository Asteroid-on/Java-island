package com.island.weather;

/**
 * 日出/日落事件（来自彩云 daily.astro 数组，覆盖未来数天）。
 * 用于逐时预报时间轴上的日出/日落细线插值，以及时间轴下方日出/日落信息行展示。
 */
public class SunEvent {

    /** 相对今天的天数偏移（0=今天，1=明天，2=后天） */
    private final int dayOffset;
    /** true=日出，false=日落 */
    private final boolean rising;
    /** 时刻标签，如 "05:32" */
    private final String time;

    public SunEvent(int dayOffset, boolean rising, String time) {
        this.dayOffset = dayOffset;
        this.rising = rising;
        this.time = time;
    }

    public int getDayOffset() {
        return dayOffset;
    }

    public boolean isRising() {
        return rising;
    }

    public String getTime() {
        return time;
    }
}
