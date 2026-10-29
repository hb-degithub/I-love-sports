package com.vloc.motion.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

import com.vloc.motion.R;
import com.vloc.motion.core.CameraClips;
import com.vloc.motion.core.RouteStore;
import com.vloc.motion.ui.CameraOverlay;
import com.vloc.motion.ui.MainActivity;

import java.util.List;

/**
 * 虚拟摄像头悬浮窗的宿主服务。
 *
 * <p>只要虚拟摄像头处于启用状态，就以前台服务常驻并显示 {@link CameraOverlay}，
 * 这样切到别的 App（例如被 LSPosed 接管的相机 App）时也能看到"正在用哪个视频"，
 * 并且能一键切换 / 关闭。悬浮窗与定位悬浮窗互斥：定位会话运行时由定位悬浮窗统一显示，
 * 避免屏幕上出现两个胶囊。
 *
 * <p>生命周期：
 * <ul>
 *   <li>主界面开启虚拟摄像头 → {@link #start(Context)}；关闭 → {@link #stop(Context)}</li>
 *   <li>没有悬浮窗权限 / 用户点了 ✕ → 只停悬浮窗，不改变摄像头开关</li>
 *   <li>摄像头被关闭（悬浮窗长按或主界面）→ 自动退出服务</li>
 * </ul>
 */
public final class CameraOverlayService extends Service {

    /** 启动悬浮窗（幂等）。 */
    public static void start(Context context) {
        Intent intent = new Intent(context, CameraOverlayService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (RuntimeException ignored) {
            // 后台启动受限或系统不允许：静默降级，主界面下次打开会再尝试
        }
    }

    /** 停止服务并移除悬浮窗。 */
    public static void stop(Context context) {
        try {
            context.stopService(new Intent(context, CameraOverlayService.class));
        } catch (RuntimeException ignored) {
            // ignore
        }
    }

    private static final String CHANNEL_ID = "camera_overlay";
    private static final int NOTIFICATION_ID = 3001;

    private static volatile boolean running;

    private CameraOverlay overlay;
    private Handler handler;
    private Runnable ticker;

    /** 悬浮窗是否正在显示（供主界面判断是否需要重新拉起）。 */
    public static boolean isRunning() {
        return running;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ensureNotificationChannel();
        handler = new Handler(Looper.getMainLooper());
        overlay = new CameraOverlay(this, new CameraOverlay.Actions() {
            @Override
            public void onSwitchClip() {
                switchNextClip();
            }

            @Override
            public void onToggleCamera() {
                boolean enabled = RouteStore.loadCameraEnabled(CameraOverlayService.this);
                RouteStore.saveCameraEnabled(CameraOverlayService.this, !enabled);
                refresh();
                if (!enabled) {
                    // 刚刚关掉：收起悬浮窗并退出
                    stopSelf();
                }
            }

            @Override
            public void onPrevClip() {
                stepClip(-1);
            }

            @Override
            public void onNextClip() {
                stepClip(1);
            }

            @Override
            public void onPlayPause() {
                boolean playing = !RouteStore.loadCameraPlaying(CameraOverlayService.this);
                RouteStore.saveCameraPlaying(CameraOverlayService.this, playing);
                refresh();
            }

            @Override
            public void onDismissOverlay() {
                // 只收起悬浮窗，摄像头保持启用；记录"用户手动隐藏"，避免立刻又弹回来
                RouteStore.saveCameraOverlayHidden(CameraOverlayService.this, true);
                stopSelf();
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundSafe();
        if (!RouteStore.loadCameraEnabled(this)) {
            // 摄像头没开就不该有悬浮窗
            stopSelf();
            return START_NOT_STICKY;
        }
        if (Session.INSTANCE.mode != Session.Mode.IDLE) {
            // 定位会话在跑：由定位悬浮窗统一显示相机状态，这里不重复
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!Settings.canDrawOverlays(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!overlay.show()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        running = true;
        refresh();
        startTicker();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        stopTicker();
        if (overlay != null) {
            overlay.hide();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startTicker() {
        stopTicker();
        ticker = new Runnable() {
            @Override
            public void run() {
                if (!RouteStore.loadCameraEnabled(CameraOverlayService.this)) {
                    stopSelf();
                    return;
                }
                if (Session.INSTANCE.mode != Session.Mode.IDLE) {
                    // 定位会话接管了悬浮窗
                    stopSelf();
                    return;
                }
                refresh();
                handler.postDelayed(this, 1000L);
            }
        };
        handler.postDelayed(ticker, 1000L);
    }

    private void stopTicker() {
        if (handler != null && ticker != null) {
            handler.removeCallbacks(ticker);
        }
        ticker = null;
    }

    private void refresh() {
        boolean enabled = RouteStore.loadCameraEnabled(this);
        List<CameraClips.Clip> clips = RouteStore.loadCameraClips(this);
        int index = RouteStore.loadCameraIndex(this);
        boolean playing = RouteStore.loadCameraPlaying(this);
        if (overlay != null) {
            overlay.update(CameraClips.overlayLabel(enabled, index, clips),
                    takeoverStatus(), playing,
                    CameraSession.INSTANCE.positionMs, CameraSession.INSTANCE.durationMs);
        }
    }

    /** 从模块上报的 CameraSession 归纳成一行人话，显示在悬浮窗第二行。 */
    private String takeoverStatus() {
        com.vloc.motion.service.CameraSession state =
                com.vloc.motion.service.CameraSession.INSTANCE;
        long reportAt = state.reportAt;
        if (reportAt == 0L) {
            return "接管：模块未注入（查 LSPosed 作用域）";
        }
        long now = System.currentTimeMillis();
        if (state.sessionAt == 0L) {
            String event = state.lastEvent == null ? "" : state.lastEvent;
            if (event.startsWith("camera2 open") || event.startsWith("camera1 open")) {
                return String.format(java.util.Locale.US,
                        "接管：相机已打开但没拦到会话（%s）", event);
            }
            String reason = state.lastError == null ? "" : state.lastError;
            if (!reason.isEmpty()) {
                return String.format(java.util.Locale.US,
                        "接管：未拦到相机 · %s", reason);
            }
            return String.format(java.util.Locale.US,
                    "接管：已注入（hooks=%d），未拦到相机", state.hooks);
        }
        long age = Math.max(0L, (now - state.sessionAt) / 1000L);
        if (age < 90L) {
            return "接管：运行中 · " + age + "s 前";
        }
        return "接管：最近 " + (age / 60L == 0L ? age + "s" : (age / 60L) + "m") + " 前";
    }

    /** 前后切换视频（环形）。 */
    private void stepClip(int delta) {
        List<CameraClips.Clip> clips = RouteStore.loadCameraClips(this);
        if (clips.isEmpty()) {
            return;
        }
        int index = CameraClips.clampIndex(clips, RouteStore.loadCameraIndex(this) + delta);
        RouteStore.saveCameraIndex(this, index);
        refresh();
    }

    private void switchNextClip() {
        List<CameraClips.Clip> clips = RouteStore.loadCameraClips(this);
        if (clips.isEmpty()) {
            return;
        }
        int next = CameraClips.nextIndex(clips, RouteStore.loadCameraIndex(this));
        RouteStore.saveCameraIndex(this, next);
        refresh();
    }

    private void ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "虚拟摄像头悬浮窗", NotificationManager.IMPORTANCE_MIN);
            channel.setDescription("虚拟摄像头启用时显示在其它应用上层");
            channel.setShowBadge(false);
            nm.createNotificationChannel(channel);
        }
    }

    private void startForegroundSafe() {
        Notification notification = buildNotification();
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, 0);
            }
        } catch (RuntimeException ignored) {
            // 前台服务被系统拒绝时退化为普通服务（悬浮窗仍可用）
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, open, flags);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_app)
                .setContentTitle("虚拟摄像头已启用")
                .setContentText("悬浮窗可切换视频 / 关闭")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setContentIntent(contentIntent)
                .build();
    }
}
