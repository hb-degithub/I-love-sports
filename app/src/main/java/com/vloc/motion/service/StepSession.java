package com.vloc.motion.service;

/** 步数数据通道（供 LSPosed 模块通过 ContentProvider 读取）。 */
public final class StepSession {

    public static final StepSession INSTANCE = new StepSession();

    public volatile String json;
    public volatile boolean active;
    public volatile long updatedAt;
    /** 模块最近一次读取本数据的时间（0=从未读取） */
    public volatile long moduleLastReadAt;
    /** 模块最近一次心跳（注入到目标进程即上报，0=从未注入） */
    public volatile long moduleHeartbeatAt;

    private StepSession() {
    }

    public void update(String json, boolean active) {
        this.json = json;
        this.active = active;
        this.updatedAt = System.currentTimeMillis();
    }

    public void clear() {
        json = null;
        active = false;
        updatedAt = 0;
        moduleLastReadAt = 0;
        moduleHeartbeatAt = 0;
    }

    public void markModuleRead() {
        moduleLastReadAt = System.currentTimeMillis();
    }

    public void markModuleHeartbeat() {
        moduleHeartbeatAt = System.currentTimeMillis();
    }
}
