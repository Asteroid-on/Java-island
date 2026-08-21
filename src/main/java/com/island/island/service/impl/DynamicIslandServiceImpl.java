package com.island.island.service.impl;

import com.island.island.model.IslandConfig;
import com.island.island.model.IslandNotification;
import com.island.island.model.IslandState;
import com.island.island.service.DynamicIslandService;
import com.island.util.ScreenUtil;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 云隙泡后端服务实现 - 管理状态和业务逻辑
 */
public class DynamicIslandServiceImpl implements DynamicIslandService {

    private static DynamicIslandServiceImpl instance;

    // 云隙泡状态
    private IslandState currentState = IslandState.HIDDEN;

    // 通知列表
    private final List<IslandNotification> notifications = new ArrayList<>();

    // 配置
    private final IslandConfig config = new IslandConfig();

    // 监听器
    private final List<StateChangeListener> listeners = new ArrayList<>();

    private DynamicIslandServiceImpl() {
    }

    public static synchronized DynamicIslandServiceImpl getInstance() {
        if (instance == null) {
            instance = new DynamicIslandServiceImpl();
        }
        return instance;
    }

    @Override
    public void show() {
        if (currentState == IslandState.VISIBLE || currentState == IslandState.SHOWING) {
            return;
        }

        IslandState oldState = currentState;
        currentState = IslandState.SHOWING;
        notifyStateChange(oldState, currentState);

        // 动画完成后设置为 VISIBLE
        // 这由前端动画完成后调用
    }

    @Override
    public void hide() {
        if (currentState == IslandState.HIDDEN || currentState == IslandState.HIDING) {
            return;
        }

        IslandState oldState = currentState;
        currentState = IslandState.HIDING;
        notifyStateChange(oldState, currentState);
    }

    @Override
    public void onAnimationComplete() {
        if (currentState == IslandState.SHOWING) {
            currentState = IslandState.VISIBLE;
        } else if (currentState == IslandState.HIDING) {
            currentState = IslandState.HIDDEN;
        }
        notifyStateChange(null, currentState);
    }

    @Override
    public void addNotification(String title, String message) {
        IslandNotification notification = new IslandNotification(title, message);
        notifications.add(notification);

        // 如果当前隐藏，自动显示
        if (currentState == IslandState.HIDDEN) {
            show();
        }
    }

    @Override
    public IslandState getState() {
        return currentState;
    }

    @Override
    public IslandConfig getConfig() {
        return config;
    }

    @Override
    public IslandNotification getLatestNotification() {
        if (notifications.isEmpty()) {
            return null;
        }
        return notifications.get(notifications.size() - 1);
    }

    @Override
    public void addStateListener(StateChangeListener listener) {
        listeners.add(listener);
    }

    @Override
    public void removeStateListener(StateChangeListener listener) {
        listeners.remove(listener);
    }

    private void notifyStateChange(IslandState oldState, IslandState newState) {
        for (StateChangeListener listener : listeners) {
            listener.onStateChanged(oldState, newState);
        }
    }

    @Override
    public Point calculateLocation() {
        // 按鼠标所在显示器居中（托盘点击时即任务栏所在屏，鼠标触发时即触发屏），多显示器自适应
        Rectangle screenBounds = ScreenUtil.getScreenBoundsAtMouse();
        int x = screenBounds.x + (screenBounds.width - config.width) / 2;
        int y = screenBounds.y + config.positionY;
        return new Point(x, y);
    }
}
