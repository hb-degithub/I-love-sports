package com.vloc.motion;

import android.app.Application;
import android.preference.PreferenceManager;

import org.osmdroid.config.Configuration;
import org.osmdroid.config.IConfigurationProvider;

import com.vloc.motion.core.RouteStore;
import com.vloc.motion.location.TestProviderInjector;
import com.vloc.motion.service.Session;

import java.io.File;

/** 应用入口：初始化 osmdroid 的缓存目录与 User-Agent（OSM 瓦片服务要求）。 */
public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
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
