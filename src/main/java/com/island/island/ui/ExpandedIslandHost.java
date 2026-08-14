package com.island.island.ui;

import com.island.island.service.DynamicIslandService;

import java.awt.Window;

/**
 * 扩展岛控制器对主岛窗口（IslandWindow）的依赖接口。
 * 由 IslandWindow 实现：提供展开/收起动画所需的起终点几何、
 * 动态岛服务引用，以及收起动画完成后的主岛恢复回调。
 */
public interface ExpandedIslandHost {

    /** 主岛窗口（展开/收起动画的起终点几何来源） */
    Window getMainIslandWindow();

    /** 动态岛服务 */
    DynamicIslandService getService();

    /**
     * 扩展岛收起动画完成回调。
     * slideUp=true 表示直接隐藏（小球滑出屏幕顶部），主岛不强制恢复，
     * 交由鼠标检测逻辑按需显示，避免主岛闪现后被立即隐藏造成闪烁；
     * slideUp=false 表示收缩回主岛（对称反向动画），需恢复主岛时间显示。
     */
    void onCollapseFinished(boolean slideUp);
}
