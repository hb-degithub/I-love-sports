package com.vloc.motion.service;

/** GNSS 卫星状态通道（供 LSPosed 模块回放 GnssStatus）。 */
public final class GnssSession {

    public static final GnssSession INSTANCE = new GnssSession();

    public volatile String json;
    public volatile long updatedAt;

    private GnssSession() {
    }

    public void set(String json) {
        this.json = json;
        this.updatedAt = System.currentTimeMillis();
    }

    public void clear() {
        json = null;
        updatedAt = 0;
    }
}
