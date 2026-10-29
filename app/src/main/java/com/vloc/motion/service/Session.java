package com.vloc.motion.service;

import android.os.Handler;
import android.os.Looper;

import com.vloc.motion.core.Route;

import java.util.concurrent.CopyOnWriteArrayList;

/** 进程内共享的运行状态（Activity 与前台服务之间）。 */
public final class Session {

    public static final Session INSTANCE = new Session();

    public enum Mode {
        IDLE, ROUTE, SPOT
    }

    public interface Listener {
        void onSessionChanged(Session session);
    }

    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public volatile Mode mode = Mode.IDLE;
    public volatile boolean paused;
    /** 已停止移动但定位仍保持在原地（悬浮窗的「暂停/停止移动」，不是彻底停止） */
    public volatile boolean held;
    /** 路径播放完成、定位停留在终点（仍在持续上报，避免"定位冻结"特征） */
    public volatile boolean arrived;
    public volatile boolean rootOk;
    public volatile boolean prepared;
    public volatile String error;
    public volatile Route route;
    public volatile double lat;
    public volatile double lon;
    /** 本次模拟起点（用于地图标记） */
    public volatile double startLat;
    public volatile double startLon;
    public volatile boolean hasStart;
    public volatile double altitude;
    public volatile float accuracyMeters;
    public volatile int satellites;
    public volatile double speedKmh;
    public volatile double traveledMeters;
    public volatile double totalMeters;
    public volatile boolean loop;
    public volatile boolean goalMode;
    public volatile int goalLaps;
    public volatile double goalDistance;
    public volatile double goalSeconds;
    public volatile double goalAvgKmh;
    public volatile boolean goalFeasible;
    public volatile int completedLaps;
    public volatile long lastUpdateMs;
    /** 步数 / 步频 / 步幅（当前会话） */
    public volatile int steps;
    public volatile int sessionSteps;
    public volatile double cadenceSpm;
    public volatile double strideMeters;

    private Session() {
    }

    public boolean isActive() {
        return mode != Mode.IDLE;
    }

    public void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public void reset() {
        mode = Mode.IDLE;
        paused = false;
        held = false;
        arrived = false;
        prepared = false;
        error = null;
        lat = 0;
        lon = 0;
        startLat = 0;
        startLon = 0;
        hasStart = false;
        altitude = 0;
        accuracyMeters = 0;
        satellites = 0;
        speedKmh = 0;
        traveledMeters = 0;
        totalMeters = 0;
        loop = false;
        goalMode = false;
        goalLaps = 0;
        goalDistance = 0;
        goalSeconds = 0;
        goalAvgKmh = 0;
        goalFeasible = true;
        completedLaps = 0;
        lastUpdateMs = 0L;
        steps = 0;
        sessionSteps = 0;
        cadenceSpm = 0;
        strideMeters = 0;
    }

    /** 在主线程通知 UI 刷新。 */
    public void notifyChanged() {
        mainHandler.post(() -> {
            for (Listener listener : listeners) {
                try {
                    listener.onSessionChanged(this);
                } catch (RuntimeException ignored) {
                    // 单个监听器异常不影响其他监听器
                }
            }
        });
    }
}
