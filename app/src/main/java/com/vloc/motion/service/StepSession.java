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
    /**
     * system_server 内的模块最近一次读取"定位/卫星快照"的时间（0=从未读取）。
     *
     * <p>与 {@link #moduleHeartbeatAt} 的区别：心跳只能证明模块在**某个目标 App 进程**里
     * 注入成功（{@code hidden/StepReplay.java} 的 sendHeartbeatOnce），**不能**证明系统级
     * 注入（系统框架作用域）已生效；这里记录的是 system_server 读取
     * {@code content://com.vloc.motion.env/location|gnss} 的时间，是系统级接管的直接证据。
     *
     * <p>只在读取方 uid == {@code Process.SYSTEM_UID} 时更新（见 EnvProvider），
     * 避免目标进程内模块（{@code hidden/AntiDetect.java} 读同一 URI）造成的假阳性。
     */
    public volatile long moduleSystemReadAt;

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
        moduleSystemReadAt = 0;
    }

    /** system_server 内的模块读了一次定位/卫星快照（系统级注入接管中的直接证据）。 */
    public void markModuleSystemRead() {
        moduleSystemReadAt = System.currentTimeMillis();
    }

    public void markModuleRead() {
        moduleLastReadAt = System.currentTimeMillis();
    }

    public void markModuleHeartbeat() {
        moduleHeartbeatAt = System.currentTimeMillis();
    }
}
