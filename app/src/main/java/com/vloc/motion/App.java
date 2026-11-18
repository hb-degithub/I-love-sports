package com.vloc.motion;

import android.app.Application;
import android.preference.PreferenceManager;

import org.osmdroid.config.Configuration;
import org.osmdroid.config.IConfigurationProvider;

import com.vloc.motion.core.RemotePrefs;
import com.vloc.motion.core.RouteStore;
import com.vloc.motion.location.TestProviderInjector;
import com.vloc.motion.service.EnvProvider;
import com.vloc.motion.service.Session;

import java.io.File;

/** 应用入口：初始化 osmdroid 的缓存目录与 User-Agent（OSM 瓦片服务要求）。 */
public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        // 新 API 后端的"应用 → 模块"快照通道：向 libxposed 框架登记服务监听。
        // 只登记回调，不做 IPC、不排任务（幂等），可以放在最前面，不阻塞启动；
        // 绑定成功后快照会由 service/SnapshotPrefs 双写进远程偏好组 vloc_snapshot。
        // 旧 world-readable prefs 通道（旧后端用）不受影响，两条通道互不依赖。
        RemotePrefs.start(this);
        IConfigurationProvider cfg = Configuration.getInstance();
        cfg.load(this, PreferenceManager.getDefaultSharedPreferences(this));
        cfg.setUserAgentValue(getPackageName());
        File base = new File(getCacheDir(), "osmdroid");
        //noinspection ResultOfMethodCallIgnored
        base.mkdirs();
        cfg.setOsmdroidBasePath(base);
        File tiles = new File(base, "tiles");
        //noinspection ResultOfMethodCallIgnored
        tiles.mkdirs();
        cfg.setOsmdroidTileCache(tiles);

        // 内置路线（用户提供的田径场坐标）写入路线库
        RouteStore.ensureBuiltinRoutes(this);

        // 注：曾尝试在空闲期把导出的 EnvProvider 置为 DISABLED 以降低可发现性，但同一 authority
        // 还承载模块心跳（hookbeat）与作用域名单（targets）。禁用会让模块在开机加载时上报失败、
        // 作用域在 prefs/套接字都不可用时静默失效；收益（隐藏一个 authority，而包名本身仍可见）
        // 远小于代价，故保持常驻启用。

        // 上次进程被强杀可能残留测试 Provider（会一直顶掉真实 GPS），启动时清理一次
        new Thread(() -> {
            try {
                Thread.sleep(1200L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (Session.INSTANCE.mode == Session.Mode.IDLE) {
                TestProviderInjector.forceCleanup(this, RouteStore.loadProviders(this));
            }
        }, "vloc-cleanup").start();
    }
}
