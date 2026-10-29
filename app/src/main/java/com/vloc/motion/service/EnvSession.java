package com.vloc.motion.service;

import com.vloc.motion.core.EnvSnapshot;

/**
 * 环境模拟状态（进程内单例）：
 * 由「开工向导」设置，ContentProvider 只在 REPLAY/HIDE 时把数据暴露给 LSPosed 模块。
 */
public final class EnvSession {

    public enum Mode {
        OFF, REPLAY, HIDE
    }

    public static final EnvSession INSTANCE = new EnvSession();

    public volatile Mode mode = Mode.OFF;
    public volatile String json;
    public volatile long updatedAt;

    private EnvSession() {
    }

    public void set(Mode mode, EnvSnapshot snapshot) {
        this.mode = mode == null ? Mode.OFF : mode;
        this.json = snapshot == null ? null : snapshot.toJson();
        this.updatedAt = System.currentTimeMillis();
    }

    public void clear() {
        mode = Mode.OFF;
        json = null;
        updatedAt = 0;
    }

    public boolean isActive() {
        return mode != Mode.OFF;
    }
}
