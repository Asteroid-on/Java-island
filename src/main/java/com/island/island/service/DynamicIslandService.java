package com.island.island.service;

import com.island.island.model.IslandConfig;
import com.island.island.model.IslandNotification;
import com.island.island.model.IslandState;

import java.awt.*;

/**
 * 云隙泡业务服务接口
 */
public interface DynamicIslandService {

    /**
     * 显示云隙泡
     */
    void show();

    /**
     * 隐藏云隙泡
     */
    void hide();

    /**
     * 动画完成回调
     */
    void onAnimationComplete();

    /**
     * 添加通知
     */
    void addNotification(String title, String message);

    /**
     * 获取当前状态
     */
    IslandState getState();

    /**
     * 获取配置
     */
    IslandConfig getConfig();

    /**
     * 获取最新通知
     */
    IslandNotification getLatestNotification();

    /**
     * 添加状态监听器
     */
    void addStateListener(StateChangeListener listener);

    /**
     * 移除状态监听器
     */
    void removeStateListener(StateChangeListener listener);

    /**
     * 计算窗口位置
     */
    Point calculateLocation();

    /**
     * 状态变化监听器接口
     */
    interface StateChangeListener {
        void onStateChanged(IslandState oldState, IslandState newState);
    }
}
