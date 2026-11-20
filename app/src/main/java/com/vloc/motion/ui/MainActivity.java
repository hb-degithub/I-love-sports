package com.vloc.motion.ui;

import android.Manifest;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.util.Log;
import android.graphics.Paint;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.text.InputType;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.VideoView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.vloc.motion.BuildConfig;
import com.vloc.motion.R;
import com.vloc.motion.core.GeoMath;
import com.vloc.motion.core.GoalPlanner;
import com.vloc.motion.core.GpxCodec;
import com.vloc.motion.core.PacePlan;
import com.vloc.motion.core.PresetRoutes;
import com.vloc.motion.core.TileSources;
import com.vloc.motion.core.Route;
import com.vloc.motion.core.RouteStore;
import com.vloc.motion.core.TleUpdater;
import com.vloc.motion.core.TraceCleaner;
import com.vloc.motion.core.UpdateChecker;
import com.vloc.motion.location.RealLocationProvider;
import com.vloc.motion.location.ModuleCheck;
import com.vloc.motion.location.RootShell;
import com.vloc.motion.location.TestProviderInjector;
import com.vloc.motion.service.EnvSession;
import com.vloc.motion.service.HookHeartbeatStore;
import com.vloc.motion.service.SnapshotPrefs;
import com.vloc.motion.service.StepSession;
import com.vloc.motion.service.LocationService;
import com.vloc.motion.service.Session;

import org.osmdroid.events.MapEventsReceiver;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.MapEventsOverlay;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polyline;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 主界面：地图选点、路径编辑、播放控制。 */
public class MainActivity extends AppCompatActivity implements Session.Listener {

    private static final String TAG = "VLocMain";
    private static final int REQ_PERMISSIONS = 2001;

    /**
     * 系统级注入下判断"系统框架是否还在接管"的静默门槛（毫秒）。
     *
     * <p>必须与 {@code LocationService.SYSTEM_MODULE_SILENCE_WARN_MS} 保持一致（30 秒）：
     * 那里是运行期"模块完全没动静"的告警门槛，UI 用同一个窗口才不会出现"服务已经告警、
     * 界面还显示在线"（或相反）的矛盾。LocationService 里该常量是 private，无法直接引用，
     * 改动其中一个必须同步另一个。
     */
    private static final long SYSTEM_MODULE_SILENCE_MS = 30_000L;

    /**
     * 前台服务通知 / 系统级注入告警通知的 id。
     *
     * <p>必须与 {@code LocationService.NOTIFICATION_ID = 1001} /
     * {@code ALERT_NOTIFICATION_ID = 1002} 保持一致：那两个常量是 private，而设置页的
     * 「立即清理痕迹」要在 Activity 侧撤掉这两条通知（{@link TraceCleaner.Options#notificationId}），
     * 只能在这里复刻一份。改动其中一个必须同步另一个。
     */
    private static final int SERVICE_NOTIFICATION_ID = 1001;
    private static final int ALERT_NOTIFICATION_ID = 1002;

    /**
     * 更新 APK 的 FileProvider authority，必须与 {@code AndroidManifest.xml} 里的
     * {@code <provider android:authorities="...">} 完全一致（写错只会在安装时抛
     * IllegalArgumentException，编译期发现不了）。
     */
    private static final String FILE_PROVIDER_AUTHORITY = "com.vloc.motion.fileprovider";

    /** 安装前预检 APK 签名的三种结果（{@link #checkApkSignature}）。 */
    private static final int SIGNATURE_UNKNOWN = 0;
    private static final int SIGNATURE_MATCH = 1;
    private static final int SIGNATURE_MISMATCH = 2;

    private MapView mapView;
    /** 是否已 detach 过 MapView：onDestroy 只释放一次，避免重复 onDetach。 */
    private boolean mapViewDetached;
    private Polyline routePolyline;
    private final List<Marker> pointMarkers = new ArrayList<>();
    private Marker currentMarker;
    /** 选点时的临时图钉（确认前不加入路径） */
    private Marker pendingMarker;
    /** 真实位置标记（蓝色，与注入位置的绿色标记区分） */
    private Marker realLocationMarker;
    private Marker startMarker;
    private boolean hasSavedMapView;
    private boolean pendingLocatePermission;

    private Route route = new Route();

    private SeekBar speedBar;
    private TextView speedValue;
    private TextView statusText;
    private TextView rootText;
    private TextView pointInfo;
    private TextView hintText;
    private SwitchMaterial loopSwitch;
    private MaterialButton btnStartStop;
    private MaterialButton btnStop;

    private ActivityResultLauncher<String[]> importLauncher;
    private ActivityResultLauncher<String> exportLauncher;
    private String lastErrorShown;
    /** null=检测中，TRUE=已授权，FALSE=未授权 */
    private Boolean rootGranted;

    /** 星表更新是否进行中：启动提醒 / 设置里的「检查更新」共用一次任务，防止重复下载。 */
    private boolean tleUpdating;
    /** 启动期星表检查只做一次（Activity 因配置变化重建时不重复弹提醒）。 */
    private boolean tleReminderChecked;

    /** 启动期版本检查只做一次（同一 Activity 内不重复发起）。 */
    private boolean autoUpdateChecked;
    /** 设置里的「检查更新」正在请求中：避免连点重复起后台任务。 */
    private boolean manualUpdateChecking;
    /** 本次更新下载的 DownloadManager id（<=0 = 没有进行中的下载）。 */
    private long updateDownloadId = -1L;
    /** 本次更新下载的 APK 直链与落盘文件名：完成回调里要按名字回查文件。 */
    private String updateApkUrl;
    private String updateApkName;
    /** 下载完成广播是否已注册（重复注册会收到两次）。 */
    private boolean updateReceiverRegistered;

    /**
     * DownloadManager 下载完成广播：只认本次更新下载的 id，其余（别的应用/本应用其它下载、
     * 或伪造的广播）一律忽略——真正的状态以 {@code DownloadManager.query()} 为准。
     */
    private final BroadcastReceiver updateDownloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null
                    || !DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) {
                return;
            }
            final long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
            if (id <= 0L || updateDownloadId <= 0L || id != updateDownloadId) {
                return;
            }
            handleUpdateDownloadComplete(id);
        }
    };

    // 目标配速选择器候选值（用选择器代替手工输入，避免非法数据）
    private static final int[] LAP_OPTIONS = {200, 300, 350, 398, 400, 500, 800, 1000};
    private static final String[] LAP_LABELS = {"200 m", "300 m", "350 m", "398 m（标准跑道）",
            "400 m", "500 m", "800 m", "1000 m"};
    private static final double[] DISTANCE_OPTIONS = {1000, 2000, 3000, 5000, 10000, 21097.5};
    private static final String[] DISTANCE_LABELS = {"1 km", "2 km", "3 km", "5 km", "10 km",
            "半程马拉松 21.1 km"};
    private static final int[] PACE_OPTIONS = {180, 210, 240, 270, 300, 330, 360, 390, 420, 480, 540, 600};
    private static final String[] PACE_LABELS = {"3:00 /km", "3:30 /km", "4:00 /km", "4:30 /km",
            "5:00 /km", "5:30 /km", "6:00 /km", "6:30 /km", "7:00 /km", "8:00 /km", "9:00 /km",
            "10:00 /km"};
    private static final int[] MAX_SPEED_OPTIONS = {8, 10, 12, 14, 15, 16, 18, 20};
    private static final String[] MAX_SPEED_LABELS = {"8 km/h", "10 km/h", "12 km/h", "14 km/h",
            "15 km/h（慢跑）", "16 km/h", "18 km/h", "20 km/h（较快）"};
    /** 用户点了悬浮窗开关但还没授权时，回到前台后自动补上 */
    private boolean pendingOverlayEnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        initMap();
        initViews();
        registerLaunchers();
        restoreState();
        refreshRouteOverlays();
        refreshUi();
        requestRuntimePermissions();
        checkRootAsync(false);
        if (!hasSavedMapView && RealLocationProvider.hasPermission(this)) {
            locateMe(false);
        }
        autoPickTileSource(false);
        watchTileLoading();
        maybeRemindTleUpdate();
        // 版本检查放最后：后台线程 + 24 小时节流，不阻塞启动，也不显示 loading；
        // 只有确实有新版本才弹窗（见 maybeAutoCheckUpdate）
        maybeAutoCheckUpdate();
        // 模块被 LSPosed 注入到 system_server / 各 App 进程后，APK 更新不会替换已运行进程里的
        // 旧代码：这里比对 system 心跳自报的模块版本，不一致就提示重启（非阻塞，见方法注释）
        maybePromptModuleRestart();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Session.INSTANCE.addListener(this);
        refreshUi();
        updateCurrentMarker();
        if (pendingOverlayEnable && Settings.canDrawOverlays(this)) {
            pendingOverlayEnable = false;
            RouteStore.saveOverlayEnabled(this, true);
            syncOverlayToService(true);
            invalidateOptionsMenu();
            toast("悬浮窗已开启");
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        Session.INSTANCE.removeListener(this);
        saveMapView();
    }

    @Override
    protected void onDestroy() {
        // 下载完成广播只在 Activity 存活期间收：销毁后不再回调已失效的界面
        unregisterUpdateReceiver();
        // osmdroid 的 MapView 持有瓦片缓存/下载器与原生资源，不 detach 会随 Activity 泄漏
        detachMapView();
        super.onDestroy();
    }

    /** 释放 MapView 的原生资源：判空 + 只 detach 一次，任何异常都不得让销毁流程崩溃。 */
    private void detachMapView() {
        MapView view = mapView;
        if (view == null || mapViewDetached) {
            return;
        }
        mapViewDetached = true;
        try {
            view.onDetach();
        } catch (RuntimeException e) {
            Log.w(TAG, "mapView.onDetach failed", e);
        }
    }

    // ------------------------------------------------------------ 初始化

    private void initMap() {
        android.widget.FrameLayout container = findViewById(R.id.mapContainer);
        mapView = new MapView(this, new org.osmdroid.tileprovider.MapTileProviderBasic(this, TileSources.load(this)));
        container.addView(mapView, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        TileProviderTuner.relaxNetworkCheck(mapView);
        mapView.setMultiTouchControls(true);
        mapView.setTilesScaledToDpi(true);
        mapView.setUseDataConnection(true);

        routePolyline = new Polyline(mapView);
        routePolyline.setWidth(8f);
        routePolyline.setColor(0xFF2962FF);
        routePolyline.getPaint().setStrokeCap(Paint.Cap.ROUND);
        mapView.getOverlays().add(routePolyline);

        currentMarker = new Marker(mapView);
        currentMarker.setIcon(ContextCompat.getDrawable(this, R.drawable.ic_current));
        currentMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
        currentMarker.setEnabled(false);
        mapView.getOverlays().add(currentMarker);

        startMarker = new Marker(mapView);
        startMarker.setIcon(ContextCompat.getDrawable(this, R.drawable.ic_start));
        startMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
        startMarker.setEnabled(false);
        startMarker.setTitle("模拟起点");
        mapView.getOverlays().add(startMarker);

        realLocationMarker = new Marker(mapView);
        realLocationMarker.setIcon(ContextCompat.getDrawable(this, R.drawable.ic_my_location));
        realLocationMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
        realLocationMarker.setEnabled(false);
        mapView.getOverlays().add(realLocationMarker);

        mapView.getOverlays().add(new MapEventsOverlay(new MapEventsReceiver() {
            @Override
            public boolean singleTapConfirmedHelper(GeoPoint p) {
                if (Session.INSTANCE.mode == Session.Mode.ROUTE) {
                    toast("播放中，暂停后可继续编辑路径");
                    return true;
                }
                Route.Point wgs = wgsPoint(p.getLatitude(), p.getLongitude());
                showPointPicker(wgs.lat, wgs.lon);
                return true;
            }

            @Override
            public boolean longPressHelper(GeoPoint p) {
                Route.Point wgs = wgsPoint(p.getLatitude(), p.getLongitude());
                confirmSpot(wgs.lat, wgs.lon);
                return true;
            }
        }));

        double[] savedView = RouteStore.loadMapView(this);
        hasSavedMapView = savedView != null;
        if (savedView != null) {
            mapView.getController().setZoom(savedView[2]);
            mapView.getController().setCenter(mapPoint(savedView[0], savedView[1]));
        } else {
            mapView.getController().setZoom(16.0);
            // 默认视野：北京天安门（WGS-84，显示时按当前底图转换）
            mapView.getController().setCenter(mapPoint(39.9042, 116.4074));
        }
    }

    private void initViews() {
        speedBar = findViewById(R.id.speedBar);
        speedValue = findViewById(R.id.speedValue);
        statusText = findViewById(R.id.statusText);
        rootText = findViewById(R.id.rootText);
        pointInfo = findViewById(R.id.pointInfo);
        hintText = findViewById(R.id.hintText);
        loopSwitch = findViewById(R.id.loopSwitch);
        btnStartStop = findViewById(R.id.btnStartStop);
        btnStop = findViewById(R.id.btnStop);

        speedBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int kmh = progress + 1;
                speedValue.setText(getString(R.string.speed_value, kmh));
                if (fromUser) {
                    RouteStore.saveSpeedKmh(MainActivity.this, kmh);
                    if (Session.INSTANCE.mode == Session.Mode.ROUTE) {
                        startService(new Intent(MainActivity.this, LocationService.class)
                                .setAction(LocationService.ACTION_SET_SPEED)
                                .putExtra(LocationService.EXTRA_KMH, (double) kmh));
                    }
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        loopSwitch.setOnCheckedChangeListener((buttonView, isChecked) ->
                RouteStore.saveLoop(MainActivity.this, isChecked));

        findViewById(R.id.btnUndo).setOnClickListener(v -> undoPoint());
        findViewById(R.id.btnClear).setOnClickListener(v -> confirmClear());
        findViewById(R.id.btnSpot).setOnClickListener(v -> {
            android.graphics.Point center = new android.graphics.Point(
                    mapView.getWidth() / 2, mapView.getHeight() / 2);
            org.osmdroid.api.IGeoPoint geoPoint = mapView.getProjection().fromPixels(center.x, center.y);
            confirmSpot(geoPoint.getLatitude(), geoPoint.getLongitude());
        });

        findViewById(R.id.fabLocate).setOnClickListener(v -> locateMe(true));
        rootText.setOnClickListener(v -> checkRootAsync(true));
        btnStartStop.setOnClickListener(v -> onStartPauseClicked());
        btnStop.setOnClickListener(v -> stopPlayback());
    }

    private void registerLaunchers() {
        importLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), uri -> {
                    if (uri == null) {
                        return;
                    }
                    try (InputStream in = getContentResolver().openInputStream(uri)) {
                        if (in == null) {
                            toast("无法读取文件");
                            return;
                        }
                        String xml = readAll(in);
                        Route imported = GpxCodec.fromGpx(xml);
                        if (imported.size() < 2) {
                            toast("未解析到有效轨迹点");
                            return;
                        }
                        route = imported;
                        refreshRouteOverlays();
                        fitRoute();
                        saveRoute();
                        toast("已导入 " + route.size() + " 个点");
                    } catch (Exception e) {
                        toast("导入失败：" + e.getMessage());
                    }
                });

        exportLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/gpx+xml"), uri -> {
                    if (uri == null) {
                        return;
                    }
                    try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                        if (out == null) {
                            toast("无法写入文件");
                            return;
                        }
                        String xml = GpxCodec.toGpx(route, "vloc-route", "VirtualMotion");
                        out.write(xml.getBytes(StandardCharsets.UTF_8));
                        toast("GPX 已导出");
                    } catch (Exception e) {
                        toast("导出失败：" + e.getMessage());
                    }
                });
    }

    private void restoreState() {
        route = RouteStore.loadRoute(this);
        int kmh = RouteStore.loadSpeedKmh(this);
        speedBar.setProgress(Math.max(0, Math.min(119, kmh - 1)));
        speedValue.setText(getString(R.string.speed_value, kmh));
        loopSwitch.setChecked(RouteStore.loadLoop(this));
    }

    // ------------------------------------------------------------ 路径编辑

    private void addPoint(double lat, double lon) {
        route.add(new Route.Point(lat, lon));
        refreshRouteOverlays();
        saveRoute();
        toast("已添加第 " + route.size() + " 个点");
    }

    private void undoPoint() {
        if (route.size() == 0) {
            return;
        }
        route.remove(route.size() - 1);
        refreshRouteOverlays();
        saveRoute();
    }

    private void confirmClear() {
        if (route.size() == 0) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("清空路径")
                .setMessage("确定删除全部 " + route.size() + " 个路径点？")
                .setPositiveButton("清空", (d, w) -> {
                    route.clear();
                    refreshRouteOverlays();
                    saveRoute();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** WGS-84 → 地图显示坐标（国内底图需 GCJ-02）。 */
    private GeoPoint mapPoint(double lat, double lon) {
        double[] converted = TileSources.toMapLatLon(this, lat, lon);
        return new GeoPoint(converted[0], converted[1]);
    }

    /** 地图坐标 → WGS-84。 */
    private Route.Point wgsPoint(double lat, double lon) {
        double[] converted = TileSources.toWgsLatLon(this, lat, lon);
        return new Route.Point(converted[0], converted[1]);
    }

    private void refreshRouteOverlays() {
        List<GeoPoint> geoPoints = new ArrayList<>();
        for (Route.Point p : route.points()) {
            geoPoints.add(mapPoint(p.lat, p.lon));
        }
        routePolyline.setPoints(geoPoints);

        for (Marker marker : pointMarkers) {
            mapView.getOverlays().remove(marker);
        }
        pointMarkers.clear();
        for (int i = 0; i < geoPoints.size(); i++) {
            final int index = i;
            Marker marker = new Marker(mapView);
            marker.setPosition(geoPoints.get(i));
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
            marker.setIcon(ContextCompat.getDrawable(this, R.drawable.ic_place));
            marker.setTitle(String.valueOf(i + 1));
            marker.setDraggable(true);
            marker.setOnMarkerClickListener((clicked, mapView1) -> {
                showPointActions(index);
                return true;
            });
            marker.setOnMarkerDragListener(new Marker.OnMarkerDragListener() {
                @Override
                public void onMarkerDragStart(Marker m) {
                    // 拖动开始时不做处理，保留原视觉
                }

                @Override
                public void onMarkerDrag(Marker m) {
                    // 拖动过程中让折线跟随，松手后再落库
                    updatePolylineFromMarkers();
                }

                @Override
                public void onMarkerDragEnd(Marker m) {
                    GeoPoint pos = m.getPosition();
                    Route.Point wgs = wgsPoint(pos.getLatitude(), pos.getLongitude());
                    route.remove(index);
                    route.add(index, wgs);
                    saveRoute();
                    // 等本次拖拽回调结束后整体重建图钉，避免在回调里移除自身
                    mapView.post(MainActivity.this::refreshRouteOverlays);
                    toast("已调整第 " + (index + 1) + " 个点");
                }
            });
            pointMarkers.add(marker);
            mapView.getOverlays().add(marker);
        }
        mapView.invalidate();
        updateInfoTexts();
    }

    private void fitRoute() {
        if (route.size() < 2) {
            return;
        }
        double north = -90, south = 90, east = -180, west = 180;
        for (Route.Point p : route.points()) {
            double[] mapped = TileSources.toMapLatLon(this, p.lat, p.lon);
            north = Math.max(north, mapped[0]);
            south = Math.min(south, mapped[0]);
            east = Math.max(east, mapped[1]);
            west = Math.min(west, mapped[1]);
        }
        if (east <= west) {
            east = west + 0.0001;
        }
        if (north <= south) {
            north = south + 0.0001;
        }
        mapView.zoomToBoundingBox(new BoundingBox(north, east, south, west), true, 80);
    }

    /** 地图选点：先落一个临时图钉，用户在弹窗里确认后再真正加入路径 / 临时定位。 */
    private void showPointPicker(final double lat, final double lon) {
        if (pendingMarker == null) {
            pendingMarker = new Marker(mapView);
            pendingMarker.setIcon(ContextCompat.getDrawable(this, R.drawable.ic_crosshair));
            pendingMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
            pendingMarker.setEnabled(false);
            pendingMarker.setTitle("选点");
        }
        pendingMarker.setPosition(mapPoint(lat, lon));
        if (!mapView.getOverlays().contains(pendingMarker)) {
            mapView.getOverlays().add(pendingMarker);
        }
        mapView.invalidate();

        new AlertDialog.Builder(this)
                .setTitle("选点")
                .setMessage(String.format(Locale.US,
                        "坐标\n%.6f, %.6f\n\n添加到路径后，可长按图钉拖动微调",
                        lat, lon))
                .setPositiveButton("添加为路径点", (dialog, which) -> {
                    clearPendingMarker();
                    addPoint(lat, lon);
                })
                .setNeutralButton("临时定位到这里", (dialog, which) -> {
                    clearPendingMarker();
                    startSpot(lat, lon);
                })
                .setNegativeButton("取消", null)
                .setOnDismissListener(dialog -> clearPendingMarker())
                .show();
    }

    /** 点选已有路径点：查看坐标 / 临时定位 / 删除。 */
    private void showPointActions(int index) {
        if (index < 0 || index >= route.size()) {
            return;
        }
        final Route.Point point = route.get(index);
        new AlertDialog.Builder(this)
                .setTitle("路径点 " + (index + 1))
                .setMessage(String.format(Locale.US, "%.6f, %.6f\n\n长按图钉可拖动调整位置",
                        point.lat, point.lon))
                .setPositiveButton("临时定位", (dialog, which) -> startSpot(point.lat, point.lon))
                .setNeutralButton("删除该点", (dialog, which) -> {
                    route.remove(index);
                    refreshRouteOverlays();
                    saveRoute();
                    toast("已删除第 " + (index + 1) + " 个点");
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void clearPendingMarker() {
        if (pendingMarker != null && mapView.getOverlays().contains(pendingMarker)) {
            mapView.getOverlays().remove(pendingMarker);
            mapView.invalidate();
        }
    }

    /** 拖拽过程中用图钉当前位置刷新折线（不落库）。 */
    private void updatePolylineFromMarkers() {
        List<GeoPoint> geoPoints = new ArrayList<>();
        for (Marker marker : pointMarkers) {
            GeoPoint position = marker.getPosition();
            if (position != null) {
                geoPoints.add(position);
            }
        }
        routePolyline.setPoints(geoPoints);
        mapView.invalidate();
    }

    private void saveRoute() {
        RouteStore.saveRoute(this, route);
        updateInfoTexts();
        // 添加/撤销/清空/删点/拖点/导入都走这里：落库后整屏刷新。
        // 否则状态栏的点数/里程/按钮可用状态要等下一次 session 回调才更新。
        refreshUi();
    }

    private void saveMapView() {
        org.osmdroid.api.IGeoPoint center = mapView.getMapCenter();
        if (Math.abs(center.getLatitude()) < 1e-6 && Math.abs(center.getLongitude()) < 1e-6) {
            return; // 未初始化不保存，避免下次打开跑到 (0,0)
        }
        Route.Point wgs = wgsPoint(center.getLatitude(), center.getLongitude());
        RouteStore.saveMapView(this, wgs.lat, wgs.lon, (float) mapView.getZoomLevelDouble());
    }

    // ------------------------------------------------------------ 播放控制

    private void onStartPauseClicked() {
        Session session = Session.INSTANCE;
        if (session.mode == Session.Mode.SPOT) {
            toast("临时定位中，请先停止");
            return;
        }
        if (session.mode == Session.Mode.ROUTE && !session.arrived) {
            startService(new Intent(this, LocationService.class)
                    .setAction(session.held
                            ? LocationService.ACTION_RESUME_MOVE
                            : LocationService.ACTION_TOGGLE_PAUSE));
            return;
        }
        if (route.size() < 2) {
            toast("请先在地图上添加至少 2 个路径点");
            return;
        }
        if (!hasLocationPermission()) {
            requestRuntimePermissions();
            return;
        }
        if (Boolean.FALSE.equals(rootGranted)) {
            showRootRequiredDialog();
            return;
        }
        EnvSession.INSTANCE.clear();
        List<String> providers = RouteStore.loadProviders(this);
        int kmh = speedBar.getProgress() + 1;
        Session.INSTANCE.route = route.copy();
        Session.INSTANCE.loop = loopSwitch.isChecked();
        Intent intent = new Intent(this, LocationService.class)
                .setAction(LocationService.ACTION_START_ROUTE)
                .putExtra(LocationService.EXTRA_SPEED_KMH, (double) kmh)
                .putExtra(LocationService.EXTRA_LOOP, loopSwitch.isChecked())
                .putExtra(LocationService.EXTRA_SPEED_VARIANCE, RouteStore.loadSpeedVariance(this))
                .putExtra(LocationService.EXTRA_GOAL_ENABLED, RouteStore.loadGoalEnabled(this))
                .putExtra(LocationService.EXTRA_GOAL_LAP_M, RouteStore.loadGoalLapMeters(this))
                .putExtra(LocationService.EXTRA_GOAL_DISTANCE_M, RouteStore.loadGoalDistanceMeters(this))
                .putExtra(LocationService.EXTRA_GOAL_TIME_S, RouteStore.loadGoalTimeSeconds(this))
                .putExtra(LocationService.EXTRA_GOAL_PACE_S, RouteStore.loadGoalPaceSeconds(this))
                .putExtra(LocationService.EXTRA_GOAL_MAX_KMH, RouteStore.loadGoalMaxKmh(this))
                .putExtra(LocationService.EXTRA_ACCURACY, RouteStore.loadAccuracy(this))
                .putStringArrayListExtra(LocationService.EXTRA_PROVIDERS,
                        new ArrayList<>(providers));
        ContextCompat.startForegroundService(this, intent);
    }

    private void confirmSpot(double lat, double lon) {
        if (Session.INSTANCE.mode == Session.Mode.ROUTE) {
            toast("路径播放中，请先停止");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("临时定位")
                .setMessage(String.format(Locale.US, "把系统定位设到\n%.6f, %.6f ？", lat, lon))
                .setPositiveButton("定位到这里", (d, w) -> startSpot(lat, lon))
                .setNegativeButton("取消", null)
                .show();
    }

    private void startSpot(double lat, double lon) {
        if (!hasLocationPermission()) {
            requestRuntimePermissions();
            return;
        }
        List<String> providers = RouteStore.loadProviders(this);
        Intent intent = new Intent(this, LocationService.class)
                .setAction(LocationService.ACTION_START_SPOT)
                .putExtra(LocationService.EXTRA_LAT, lat)
                .putExtra(LocationService.EXTRA_LON, lon)
                .putExtra(LocationService.EXTRA_ACCURACY, RouteStore.loadAccuracy(this))
                .putStringArrayListExtra(LocationService.EXTRA_PROVIDERS,
                        new ArrayList<>(providers));
        ContextCompat.startForegroundService(this, intent);
    }

    private void stopPlayback() {
        Session session = Session.INSTANCE;
        if (session.isActive()) {
            new AlertDialog.Builder(this)
                    .setTitle("彻底停止模拟？")
                    .setMessage("将移除模拟定位并恢复手机真实定位。\n"
                            + "悬浮窗上的「暂停 / 停止移动」只会冻结当前位置，不会恢复真实定位。")
                    .setPositiveButton("彻底停止", (d, w) -> doStop())
                    .setNegativeButton("返回", null)
                    .show();
            return;
        }
        doStop();
    }

    private void doStop() {
        startService(new Intent(this, LocationService.class)
                .setAction(LocationService.ACTION_STOP));
        Session.INSTANCE.removeListener(this);
        Session.INSTANCE.reset();
        Session.INSTANCE.addListener(this);
        refreshUi();
    }

    // ------------------------------------------------------------ 状态刷新

    @Override
    public void onSessionChanged(Session session) {
        refreshUi();
        updateCurrentMarker();
        if (session.error != null && !session.error.equals(lastErrorShown)) {
            lastErrorShown = session.error;
            toast(session.error);
        }
    }

    /**
     * system_server 里的模块是否还"在"（系统级注入下的存活判定，UI 侧）。
     *
     * <p>判定口径与 {@code LocationService#isSystemModuleOnline()} 略有不同：那里启动阶段用
     * 严格的 5 秒窗口防止"假成功"，UI 这里用运行期告警门槛 {@link #SYSTEM_MODULE_SILENCE_MS}
     * （30 秒），否则目标 App 暂时不请求定位时界面会闪"未接管"。
     *
     * <p>两条证据取**或**：快照读取新鲜（30 秒）**或** 24 小时内有 {@code kind=system} 的
     * 模块心跳（{@link HookHeartbeatStore}）。心跳按进程注入打一次、不是周期性的，
     * 所以窗口取 24 小时——不能用 30 秒去卡，否则注入后 30 秒就误报"未接管"。
     */
    private boolean isSystemModuleFresh() {
        long seenAt = StepSession.INSTANCE.moduleSystemReadAt;
        if (seenAt > 0L) {
            long age = System.currentTimeMillis() - seenAt;
            if (age >= 0L && age <= SYSTEM_MODULE_SILENCE_MS) {
                return true;
            }
        }
        return systemHeartbeatAgeMs() >= 0L;
    }

    /** 系统框架心跳距今多少毫秒；没有（或超过 24 小时）返回 -1。 */
    private long systemHeartbeatAgeMs() {
        HookHeartbeatStore.Beat beat = HookHeartbeatStore.freshestSystem(this,
                HookHeartbeatStore.FRESH_MS, Build.VERSION.SDK_INT);
        return beat == null ? -1L : Math.max(0L, System.currentTimeMillis() - beat.at);
    }

    private void refreshUi() {
        if (statusText == null) {
            return;
        }
        Session session = Session.INSTANCE;
        String state;
        switch (session.mode) {
            case ROUTE:
                if (session.arrived) {
                    state = "已到达终点";
                } else if (session.held && session.traveledMeters <= 0.5) {
                    state = "已定位到起点（待开始）";
                } else if (session.held) {
                    state = "已停止移动（定位保持）";
                } else if (session.paused) {
                    state = "已暂停";
                } else {
                    state = session.goalMode ? "配速中" : "运行中";
                }
                break;
            case SPOT:
                state = "临时定位中";
                break;
            default:
                state = "未开始";
        }
        String coords = "";
        if (session.mode != Session.Mode.IDLE
                && GeoMath.isValid(session.lat, session.lon)
                && (Math.abs(session.lat) > 1e-9 || Math.abs(session.lon) > 1e-9)) {
            coords = String.format(Locale.US, " · %s · 海拔 %.0f m",
                    GeoMath.format(session.lat, session.lon), session.altitude);
        }
        boolean goalMode = session.goalMode && session.mode == Session.Mode.ROUTE;
        String envTag = "";
        if (session.mode != Session.Mode.IDLE) {
            EnvSession.Mode envMode = EnvSession.INSTANCE.mode;
            if (envMode == EnvSession.Mode.REPLAY) {
                envTag = " · 基站回放";
            } else if (envMode == EnvSession.Mode.HIDE) {
                envTag = " · 基站隐藏";
            }
        }
        String stepTag = "";
        if (session.mode != Session.Mode.IDLE && session.sessionSteps > 0) {
            stepTag = String.format(Locale.US, " · 步 %d（%d 步/分）",
                    session.sessionSteps, Math.round(session.cadenceSpm));
        }
        // 注入模式决定"模块存活"的证据来源：root 注入看目标进程心跳，
        // 系统级注入看 system_server 的读取痕迹（stepTag / 提示文案必须二选一）
        final boolean systemInjectMode = RouteStore.loadSystemInject(this);
        if (session.mode != Session.Mode.IDLE) {
            if (systemInjectMode) {
                // 系统级注入推荐把模块勾进「系统框架」作用域，那种配置下目标进程心跳
                // 恒为 0；继续用心跳判定会长期显示"模块未注入"，还会把用户引向
                // "去勾目标 App"这种与实际要求相反的配置。这里先看 moduleSystemReadAt
                // （由 EnvProvider 记录 system_server 的 uid 1000 读取），再看系统框架心跳
                // （HookHeartbeatStore 的 kind=system，24 小时窗口）——两者任一即可。
                long systemRead = StepSession.INSTANCE.moduleSystemReadAt;
                long age = systemRead <= 0 ? -1 : System.currentTimeMillis() - systemRead;
                long heartbeatAge = systemHeartbeatAgeMs();
                if (systemRead > 0 && age >= 0 && age <= SYSTEM_MODULE_SILENCE_MS) {
                    stepTag += String.format(Locale.US, " · 系统框架已接管 %ds前", age / 1000);
                } else if (heartbeatAge >= 0L) {
                    stepTag += String.format(Locale.US, " · 系统框架已注入（心跳 %ds前）",
                            heartbeatAge / 1000);
                } else {
                    stepTag += " · 系统框架未接管";
                }
            } else {
                long lastRead = StepSession.INSTANCE.moduleLastReadAt;
                long heartbeat = StepSession.INSTANCE.moduleHeartbeatAt;
                if (lastRead > 0) {
                    long age = Math.max(0, (System.currentTimeMillis() - lastRead) / 1000);
                    stepTag += String.format(Locale.US, " · 模块读取中 %ds前", age);
                } else if (heartbeat > 0) {
                    stepTag += " · 模块已注入（目标App未读传感器）";
                } else {
                    stepTag += " · 模块未注入";
                }
            }
        }
        String status = String.format(Locale.US, "%s · %d 个路径点%s%s%s",
                state, route.size(), coords, envTag, stepTag);
        statusText.setText(status);
        updateInfoTexts();

        // ===== 交互重构：显示规则集中在 MotionUiState（可单元测试） =====
        boolean goalConfigured = RouteStore.loadGoalEnabled(this);
        final com.vloc.motion.core.MotionUiState uiState =
                com.vloc.motion.core.MotionUiState.compute(
                        session.mode == Session.Mode.ROUTE
                                ? com.vloc.motion.core.MotionUiState.Mode.ROUTE
                                : session.mode == Session.Mode.SPOT
                                        ? com.vloc.motion.core.MotionUiState.Mode.SPOT
                                        : com.vloc.motion.core.MotionUiState.Mode.IDLE,
                        session.paused, session.held, session.arrived,
                        session.traveledMeters, goalMode, goalConfigured);

        View speedRow = findViewById(R.id.speedRow);
        if (speedRow != null) {
            speedRow.setVisibility(uiState.settingsVisible ? View.VISIBLE : View.GONE);
        }
        loopSwitch.setVisibility(uiState.settingsVisible ? View.VISIBLE : View.GONE);
        speedBar.setEnabled(uiState.speedEnabled);
        loopSwitch.setEnabled(uiState.loopEnabled);

        final int editVisibility = uiState.editButtonsVisible ? View.VISIBLE : View.GONE;
        findViewById(R.id.btnUndo).setVisibility(editVisibility);
        findViewById(R.id.btnClear).setVisibility(editVisibility);
        findViewById(R.id.btnSpot).setVisibility(editVisibility);
        if (uiState.speedEnabled) {
            speedValue.setText(getString(R.string.speed_value,
                    speedBar.getProgress() + 1));
        }
        switch (uiState.primaryAction) {
            case START_MOVE:
                btnStartStop.setText("开始移动");
                break;
            case RESTART:
                btnStartStop.setText(R.string.action_restart);
                break;
            case RESUME:
                btnStartStop.setText(R.string.action_resume);
                break;
            case PAUSE:
                btnStartStop.setText(R.string.action_pause);
                break;
            case SPOT:
                btnStartStop.setText(R.string.action_spot);
                break;
            case START:
            default:
                btnStartStop.setText(R.string.action_start);
                break;
        }
        btnStartStop.setEnabled(uiState.primaryEnabled());
        btnStop.setEnabled(uiState.stopEnabled);
        btnStop.setText(session.mode == Session.Mode.IDLE ? "停止" : "彻底停止");
        if (hintText != null) {
            if (session.arrived) {
                hintText.setText(RouteStore.loadAutoStopOnFinish(this)
                        ? "已到达目标，正在自动停止并恢复真实定位"
                        : com.vloc.motion.core.ArrivalBehavior.overlayLabel(false)
                                + " · 点「彻底停止」才恢复真实定位");
            } else if (session.held && session.traveledMeters <= 0.5) {
                hintText.setText("已定位到起点（定位保持中）· 在悬浮窗点「开始」开始路线模拟");
            } else if (session.held) {
                hintText.setText("已停止移动，定位保持在原地 · 只有这里「彻底停止」才会恢复真实定位");
            } else if (session.mode != Session.Mode.IDLE
                    && systemInjectMode
                    && !isSystemModuleFresh()) {
                // 模式二选一：system_inject 走的是「系统框架」作用域，提示里绝不能再让
                // 用户去勾目标 App（那是 root/provider 注入的配置）。
                hintText.setText("系统框架未接管：请确认 LSPosed 已启用本模块，作用域勾选「系统框架（android）」"
                        + "后重启手机；系统级注入不需要勾选目标 App");
            } else if (session.mode != Session.Mode.IDLE
                    && !systemInjectMode
                    && session.sessionSteps > 0
                    && StepSession.INSTANCE.moduleLastReadAt <= 0
                    && StepSession.INSTANCE.moduleHeartbeatAt > 0) {
                hintText.setText("模块已注入目标 App，但它没有读取计步传感器（可能走系统健康数据，无法伪造）");
            } else if (session.mode != Session.Mode.IDLE
                    && !systemInjectMode
                    && session.sessionSteps > 0
                    && StepSession.INSTANCE.moduleHeartbeatAt <= 0) {
                hintText.setText("模块未注入：请确认 LSPosed 已启用本模块、作用域勾选目标 App，并重启目标 App");
            } else if (session.mode == Session.Mode.ROUTE) {
                hintText.setText("播放中：暂停后可继续添加 / 编辑路径点");
            } else if (goalConfigured) {
                hintText.setText("目标配速已启用：速度由配速自动控制（菜单 →「目标配速…」可修改）");
            } else {
                hintText.setText(getString(R.string.hint_pick));
            }
        }
    }

    private void updateInfoTexts() {
        if (pointInfo == null) {
            return;
        }
        Session session = Session.INSTANCE;
        if (session.mode != Session.Mode.IDLE && session.strideMeters > 0) {
            float stride = (float) session.strideMeters;
            if (session.mode == Session.Mode.ROUTE && session.goalMode) {
                pointInfo.setText(String.format(Locale.US, "已走 %s / %s · 第 %d/%d 圈 · 步幅 %.2f m",
                        formatDistance(session.traveledMeters), formatDistance(session.totalMeters),
                        session.completedLaps, session.goalLaps, stride));
            } else {
                pointInfo.setText(String.format(Locale.US, "已走 %s / %s · 步幅 %.2f m",
                        formatDistance(session.traveledMeters),
                        formatDistance(session.totalMeters), stride));
            }
            return;
        }
        if (session.mode == Session.Mode.ROUTE && session.goalMode) {
            pointInfo.setText(String.format(Locale.US, "已走 %s / %s · 第 %d/%d 圈",
                    formatDistance(session.traveledMeters), formatDistance(session.totalMeters),
                    session.completedLaps, session.goalLaps));
        } else if (session.mode == Session.Mode.ROUTE) {
            pointInfo.setText(String.format(Locale.US, "已走 %s / %s",
                    formatDistance(session.traveledMeters), formatDistance(session.totalMeters)));
        } else if (RouteStore.loadGoalEnabled(this) && route.size() >= 2) {
            PacePlan plan = PacePlan.create(RouteStore.loadGoalLapMeters(this),
                    RouteStore.loadGoalDistanceMeters(this),
                    RouteStore.loadGoalTimeSeconds(this),
                    RouteStore.loadGoalMaxKmh(this) / 3.6, route.totalMeters());
            pointInfo.setText(String.format(Locale.US,
                    "目标 %s · %d 圈 · %s · 均速 %.1f km/h",
                    formatDistance(plan.actualMeters), plan.laps,
                    formatDuration(plan.durationSeconds), plan.avgSpeedMps * 3.6));
        } else {
            pointInfo.setText("总里程 " + formatDistance(route.totalMeters()));
        }
    }

    private void updateCurrentMarker() {
        if (currentMarker == null) {
            return;
        }
        Session session = Session.INSTANCE;
        if (startMarker != null) {
            if (session.mode != Session.Mode.IDLE && session.hasStart) {
                startMarker.setPosition(mapPoint(session.startLat, session.startLon));
                startMarker.setEnabled(true);
            } else {
                startMarker.setEnabled(false);
            }
        }
        if (session.mode != Session.Mode.IDLE && GeoMath.isValid(session.lat, session.lon)) {
            currentMarker.setPosition(new GeoPoint(session.lat, session.lon));
            currentMarker.setEnabled(true);
        } else {
            currentMarker.setEnabled(false);
        }
        mapView.invalidate();
    }

    // ------------------------------------------------------------ 权限 / Root

    private boolean hasLocationPermission() {
        // Android 14 前台定位服务只要求粗略或精确其一即可
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestRuntimePermissions() {
        List<String> permissions = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!permissions.isEmpty()) {
            requestPermissions(permissions.toArray(new String[0]), REQ_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSIONS) {
            if (!hasLocationPermission()) {
                toast("未授予定位权限，无法启动前台定位服务");
                return;
            }
            if (pendingLocatePermission) {
                pendingLocatePermission = false;
                locateMe(false);
            }
        }
    }

    private void checkRootAsync(boolean userInitiated) {
        if (rootText != null) {
            rootText.setText(userInitiated
                    ? "Root：正在申请授权…（如弹窗请点允许）"
                    : "Root：检测中…");
        }
        new Thread(() -> {
            RootShell.Result result;
            try {
                result = RootShell.su("id", userInitiated ? 60000L : 12000L);
            } catch (Throwable t) {
                runOnUiThread(() -> {
                    rootGranted = false;
                    rootText.setTextColor(0xFFE53935);
                    rootText.setText("Root：检测异常：" + t);
                });
                return;
            }
            boolean rooted = RootShell.isRootResult(result);
            boolean appopAllowed = false;
            if (rooted) {
                appopAllowed = RootShell.isMockAppopAllowed(
                        RootShell.getMockAppop(getPackageName()));
            }
            boolean finalRooted = rooted;
            boolean finalAllowed = appopAllowed;
            runOnUiThread(() -> {
                rootGranted = finalRooted;
                String text;
                if (!finalRooted) {
                    text = "Root：未获得授权 ✗（点此重新检测/申请）";
                } else if (finalAllowed) {
                    text = "Root：已授权 ✓ · 模拟位置权限已就绪";
                } else {
                    text = "Root：已授权 ✓ · 模拟位置权限将在开始时自动授予";
                }
                rootText.setTextColor(finalRooted ? 0xFF00C853 : 0xFFE53935);
                rootText.setText(text);
                if (!finalRooted && !userInitiated) {
                    toast("未检测到 root，点顶部 Root 状态行可重新申请");
                }
                if (userInitiated) {
                    if (finalRooted) {
                        toast("Root 已授权");
                    } else {
                        toast("未获得 root：" + result.combined());
                    }
                }
            });
        }, "vloc-root-check").start();
    }

    private void showRootRequiredDialog() {
        new AlertDialog.Builder(this)
                .setTitle("未获得 Root 权限")
                .setMessage("注入定位需要 root 授权（Magisk / KernelSU / APatch）。\n"
                        + "请确认设备已 root，并在授权弹窗中允许本应用。")
                .setPositiveButton("重新申请", (d, w) -> checkRootAsync(true))
                .setNegativeButton("取消", null)
                .show();
    }

    /** 自动探测可达瓦片源；全部不可达时给出可复制的诊断。 */
    private void autoPickTileSource(boolean showFailDialog) {
        new Thread(() -> {
            String currentId = TileSources.loadSelectedId(this);
            if (Diagnostics.isSourceReachable(this, currentId)) {
                return; // 当前源可用则不动
            }
            String pick = Diagnostics.pickReachableSource(this);
            runOnUiThread(() -> {
                if (pick == null) {
                    if (showFailDialog || !RouteStore.prefs(this)
                            .getBoolean("map_trouble_shown", false)) {
                        RouteStore.prefs(this).edit().putBoolean("map_trouble_shown", true).apply();
                        showMapTroubleDialog();
                    }
                    return;
                }
                String current = TileSources.loadSelectedId(this);
                if (!pick.equals(current)) {
                    TileSources.saveSelectedId(this, pick);
                    mapView.setTileSource(TileSources.byId(pick).tileSource);
                    refreshRouteOverlays();
                    try {
                        mapView.getTileProvider().clearTileCache();
                    } catch (RuntimeException ignored) {
                        // 忽略
                    }
                    mapView.invalidate();
                    toast("已自动切换可用地图源：" + TileSources.byId(pick).label);
                }
            });
        }, "vloc-tile-probe").start();
    }

    /** 若 9 秒内瓦片缓存没有增长，说明实际没下载到瓦片（即使探测可达），提示诊断。 */
    private void watchTileLoading() {
        final int before = Diagnostics.cachedTileCount(this);
        mapView.postDelayed(() -> {
            if (Diagnostics.cachedTileCount(this) > before) {
                return;
            }
            if (RouteStore.prefs(this).getBoolean("map_trouble_shown", false)) {
                return;
            }
            RouteStore.prefs(this).edit().putBoolean("map_trouble_shown", true).apply();
            showMapTroubleDialog();
        }, 9000L);
    }

    private void showMapTroubleDialog() {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("地图瓦片加载失败")
                .setMessage("正在生成诊断…")
                .setPositiveButton("复制诊断", null)
                .setNegativeButton("关闭", null)
                .setNeutralButton("手动输入坐标", (d, w) -> showCoordinateDialog())
                .create();
        dialog.show();
        new Thread(() -> {
            String report = Diagnostics.mapReport(this);
            runOnUiThread(() -> {
                if (dialog.isShowing()) {
                    dialog.setMessage(report + "\n\n可先用「手动输入坐标」选点，不影响注入。");
                    android.widget.TextView view = dialog.findViewById(android.R.id.message);
                    if (view != null) {
                        view.setTextIsSelectable(true);
                    }
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                        copyToClipboard("地图诊断", report);
                        toast("诊断已复制");
                    });
                }
            });
        }, "vloc-map-diag").start();
    }

    private void copyToClipboard(String label, String text) {
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text));
        }
    }

    /**
     * 「模块状态」：以**模块心跳**为首选判据，LSPosed 管理器包名只作附加信息。
     *
     * <p>旧实现用 {@link ModuleCheck#detectManager}（查 {@code org.lsposed.manager} 包名）判断
     * "框架有没有生效"。用户装了 HideMyAppList 这类隐藏模块后包名查不到，界面就误报
     * "LSPosed 框架没有生效"，而 LSPosed 日志里模块明明已经注入（{@code hooks installed in
     * com.android.location.fused}）。心跳由模块自己 insert 上来，隐藏模块管不到，才是真信号：
     * <b>管理器不可见不等于模块未生效</b>。
     *
     * <p>三种状态（按优先级）：
     * <ol>
     *   <li>有 {@code kind=system} 且 24 小时内的心跳 → 「模块已生效（系统框架）」；</li>
     *   <li>否则有任何 {@code kind=app} 心跳 → 「已注入 N 个应用进程」；</li>
     *   <li>一条心跳都没有 → 「未检测到模块活动」。</li>
     * </ol>
     */
    private void showModuleStatusDialog() {
        final long now = System.currentTimeMillis();
        final List<HookHeartbeatStore.Beat> beats = HookHeartbeatStore.beats(this);
        final HookHeartbeatStore.Beat system =
                HookHeartbeatStore.freshestSystem(this, HookHeartbeatStore.FRESH_MS,
                        Build.VERSION.SDK_INT);
        int appCount = 0;
        for (HookHeartbeatStore.Beat beat : beats) {
            if (!beat.isSystem()) {
                appCount++;
            }
        }
        final long legacyBeat = StepSession.INSTANCE.moduleHeartbeatAt;
        StringBuilder sb = new StringBuilder();
        if (system != null) {
            sb.append("结论：模块已生效（系统框架）✓");
        } else if (appCount > 0) {
            sb.append("结论：已注入 ").append(appCount).append(" 个应用进程");
        } else if (legacyBeat > 0L) {
            // 旧版模块只走 call('module_heartbeat')，没有进程明细：别把"有活动"说成"没活动"
            sb.append("结论：已注入（旧版心跳，无进程详情）");
        } else {
            sb.append("结论：未检测到模块活动");
        }
        sb.append('\n');
        appendHookBeatLines(sb, beats, now);
        long lastBeat = beats.isEmpty() ? 0L : beats.get(0).at;
        if (lastBeat <= 0L) {
            lastBeat = legacyBeat;
        }
        if (lastBeat > 0L) {
            sb.append("最后心跳：").append(formatAge(now - lastBeat))
                    .append("（").append(formatClock(lastBeat, now)).append("）\n");
        }
        String manager = ModuleCheck.detectManager(this);
        sb.append("LSPosed 管理器：")
                .append(manager == null
                        ? "不可见（可能被隐藏模块屏蔽，不影响模块本身）"
                        : "可见（" + manager + "）")
                .append('\n');
        if (manager == null) {
            // 只有"查不到"才需要这句解释；查到了就没有歧义，省一行
            sb.append("管理器不可见 ≠ 模块未生效，请以模块心跳为准。");
        }
        if (system == null && appCount == 0 && legacyBeat <= 0L) {
            sb.append("\n\n排查：在 LSPosed 中启用本模块，作用域勾选「系统框架（android）」或目标 App，\n")
                    .append("然后重启手机 / 重启目标 App（注入成功的进程会留下心跳）。");
        }
        new AlertDialog.Builder(this)
                .setTitle("模块状态")
                .setMessage(sb.toString())
                .setPositiveButton("知道了", null)
                .show();
    }

    /** 逐行列出每个进程的心跳（超过 6 条折叠），供「模块状态」自助排查。 */
    private void appendHookBeatLines(StringBuilder sb, List<HookHeartbeatStore.Beat> beats,
                                     long now) {
        if (beats.isEmpty()) {
            return;
        }
        sb.append('\n').append("进程心跳：");
        final int limit = 6;
        for (int i = 0; i < beats.size() && i < limit; i++) {
            HookHeartbeatStore.Beat beat = beats.get(i);
            long age = Math.max(0L, now - beat.at);
            sb.append('\n').append("· ").append(beat.process)
                    .append(" · ").append(beat.kind)
                    .append(" · ").append(beat.hooks).append(" hooks")
                    .append(" · ").append(formatAge(age));
            if (age > HookHeartbeatStore.FRESH_MS) {
                sb.append("（已过期）");
            }
        }
        if (beats.size() > limit) {
            sb.append('\n').append("… 共 ").append(beats.size()).append(" 个进程");
        }
        sb.append('\n');   // 让后面的「最后心跳」另起一行
    }

    /** 毫秒差 → 简短"多久前"文案（心跳时间用）。 */
    private static String formatAge(long ageMs) {
        final long age = Math.max(0L, ageMs);
        if (age < 1500L) {
            return "刚刚";
        }
        long seconds = age / 1000L;
        if (seconds < 60L) {
            return seconds + " 秒前";
        }
        long minutes = seconds / 60L;
        if (minutes < 60L) {
            return minutes + " 分钟前";
        }
        long hours = minutes / 60L;
        if (hours < 48L) {
            return hours + " 小时前";
        }
        return (hours / 24L) + " 天前";
    }

    /** 绝对时间：一天内只显示时分秒，更早的带上日期。 */
    private static String formatClock(long at, long now) {
        String pattern = Math.abs(now - at) < 24L * 60L * 60L * 1000L
                ? "HH:mm:ss" : "MM-dd HH:mm";
        return new SimpleDateFormat(pattern, Locale.US).format(new Date(at));
    }

    private void showDiagnostics(boolean root) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(root ? "Root 诊断" : "地图诊断")
                .setMessage("检测中，请稍候…")
                .setNegativeButton("关闭", null)
                .create();
        dialog.show();
        android.widget.TextView messageView = dialog.findViewById(android.R.id.message);
        if (messageView != null) {
            messageView.setTextIsSelectable(true);
        }
        new Thread(() -> {
            String report = root ? Diagnostics.rootReport(this) : Diagnostics.mapReport(this);
            runOnUiThread(() -> {
                if (dialog.isShowing()) {
                    dialog.setMessage(report);
                    android.widget.TextView view = dialog.findViewById(android.R.id.message);
                    if (view != null) {
                        view.setTextIsSelectable(true);
                    }
                }
            });
        }, "vloc-diag").start();
    }

    // ------------------------------------------------------------ 真实定位

    /** 读取设备真实位置：地图居中显示，并可选"添加为路径点/临时定位"。 */
    private void locateMe(boolean showOptions) {
        if (!RealLocationProvider.hasPermission(this)) {
            pendingLocatePermission = true;
            requestRuntimePermissions();
            return;
        }
        toast("正在获取当前位置…");
        RealLocationProvider.request(this, new RealLocationProvider.Callback() {
            @Override
            public void onLocation(android.location.Location location) {
                showRealLocation(location, showOptions);
            }

            @Override
            public void onError(String message) {
                toast(message);
            }
        });
    }

    private void showRealLocation(android.location.Location location, boolean showOptions) {
        final double lat = location.getLatitude();
        final double lon = location.getLongitude();
        realLocationMarker.setPosition(mapPoint(lat, lon));
        realLocationMarker.setEnabled(true);
        if (!hasSavedMapView) {
            mapView.getController().setZoom(17.0);
            hasSavedMapView = true;
        }
        mapView.getController().animateTo(mapPoint(lat, lon));
        mapView.invalidate();

        String accuracy = location.hasAccuracy()
                ? String.format(Locale.US, "精度 %.0f m", location.getAccuracy())
                : "精度未知";
        String note = Session.INSTANCE.mode == Session.Mode.IDLE
                ? "" : "\n（当前正在模拟，读到的可能是注入位置）";

        if (!showOptions) {
            toast(String.format(Locale.US, "当前位置：%.6f, %.6f · %s%s", lat, lon, accuracy, note));
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("当前位置")
                .setMessage(String.format(Locale.US,
                        "%.6f, %.6f\n%s%s", lat, lon, accuracy, note))
                .setPositiveButton("添加为路径点", (d, w) -> addPoint(lat, lon))
                .setNeutralButton("临时定位到这里", (d, w) -> startSpot(lat, lon))
                .setNegativeButton("关闭", null)
                .show();
    }

    private void showPresetRoutesDialog() {
        final List<PresetRoutes.Preset> presets = PresetRoutes.all();
        String[] labels = new String[presets.size()];
        for (int i = 0; i < presets.size(); i++) {
            labels[i] = presets.get(i).name + "（约 "
                    + Math.round(presets.get(i).nominalMeters) + " m）";
        }
        Route.Point anchor = wgsPoint(mapView.getMapCenter().getLatitude(),
                mapView.getMapCenter().getLongitude());
        final double lat = anchor.lat;
        final double lon = anchor.lon;
        if (Math.abs(lat) < 1e-6 && Math.abs(lon) < 1e-6) {
            toast("地图尚未就绪，请稍后再试或用「定位」按钮");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("预设路线（以地图中心为基准）")
                .setItems(labels, (dialog, which) -> {
                    PresetRoutes.Preset preset = presets.get(which);
                    applyPresetRoute(preset, lat, lon);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void applyPresetRoute(PresetRoutes.Preset preset, double lat, double lon) {
        if (route.size() >= 2) {
            new AlertDialog.Builder(this)
                    .setTitle("替换当前路径？")
                    .setMessage("将用「" + preset.name + "」替换当前 " + route.size() + " 个路径点。")
                    .setPositiveButton("替换", (d, w) -> loadPreset(preset, lat, lon))
                    .setNegativeButton("取消", null)
                    .show();
        } else {
            loadPreset(preset, lat, lon);
        }
    }

    private void loadPreset(PresetRoutes.Preset preset, double lat, double lon) {
        route = PresetRoutes.create(preset.id, lat, lon);
        refreshRouteOverlays();
        fitRoute();
        // saveRoute() 内部已经 updateInfoTexts() + refreshUi()，这里不要重复刷新
        saveRoute();
        if (RouteStore.loadGoalEnabled(this)) {
            RouteStore.saveGoalLapMeters(this, (float) Math.round(route.totalMeters()));
            updateInfoTexts();
            toast("已生成：" + preset.name + " · " + Math.round(route.totalMeters())
                    + " m（单圈距离已同步）");
        } else {
            toast("已生成：" + preset.name + " · " + Math.round(route.totalMeters()) + " m");
        }
    }

    private void showGoalDialog() {
        View content = getLayoutInflater().inflate(R.layout.dialog_goal, null, false);
        SwitchMaterial enabled = content.findViewById(R.id.switchGoalEnabled);
        Spinner lapSpinner = content.findViewById(R.id.spinnerLap);
        Spinner distSpinner = content.findViewById(R.id.spinnerDistance);
        Spinner paceSpinner = content.findViewById(R.id.spinnerPace);
        Spinner maxSpinner = content.findViewById(R.id.spinnerMaxSpeed);
        TextView preview = content.findViewById(R.id.goalPreview);

        setupSpinner(lapSpinner, LAP_LABELS,
                nearestIndex(LAP_OPTIONS, Math.round(RouteStore.loadGoalLapMeters(this))));
        setupSpinner(distSpinner, DISTANCE_LABELS,
                nearestIndex(DISTANCE_OPTIONS, RouteStore.loadGoalDistanceMeters(this)));
        setupSpinner(paceSpinner, PACE_LABELS,
                nearestIndex(PACE_OPTIONS, RouteStore.loadGoalPaceSeconds(this)));
        setupSpinner(maxSpinner, MAX_SPEED_LABELS,
                nearestIndex(MAX_SPEED_OPTIONS, Math.round(RouteStore.loadGoalMaxKmh(this))));

        enabled.setChecked(RouteStore.loadGoalEnabled(this));

        final Runnable refresh = () -> updateGoalPreview(preview,
                LAP_OPTIONS[lapSpinner.getSelectedItemPosition()],
                DISTANCE_OPTIONS[distSpinner.getSelectedItemPosition()],
                PACE_OPTIONS[paceSpinner.getSelectedItemPosition()],
                MAX_SPEED_OPTIONS[maxSpinner.getSelectedItemPosition()]);
        AdapterView.OnItemSelectedListener listener = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                refresh.run();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        };
        lapSpinner.setOnItemSelectedListener(listener);
        distSpinner.setOnItemSelectedListener(listener);
        paceSpinner.setOnItemSelectedListener(listener);
        maxSpinner.setOnItemSelectedListener(listener);
        refresh.run();

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("目标配速（自动计算）")
                .setView(content)
                .setPositiveButton("保存", null)
                .setNegativeButton("取消", null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            int lap = LAP_OPTIONS[lapSpinner.getSelectedItemPosition()];
            double distance = DISTANCE_OPTIONS[distSpinner.getSelectedItemPosition()];
            int pace = PACE_OPTIONS[paceSpinner.getSelectedItemPosition()];
            int maxKmh = MAX_SPEED_OPTIONS[maxSpinner.getSelectedItemPosition()];
            GoalPlanner.Estimate estimate = GoalPlanner.estimate(lap, distance,
                    route.size() >= 2 ? route.totalMeters() : lap, pace, maxKmh);
            if (!estimate.feasible && enabled.isChecked()) {
                new AlertDialog.Builder(this)
                        .setTitle("目标不可达")
                        .setMessage(String.format(Locale.US,
                                "所选配速 %s/km 对应平均速度 %.1f km/h，超过最大速度 %d km/h。\n"
                                        + "按最大速度完成约需 %s。\n\n仍要启用吗？",
                                PACE_LABELS[paceSpinner.getSelectedItemPosition()].replace(" /km", ""),
                                estimate.avgSpeedMps * 3.6, maxKmh,
                                formatDuration(estimate.minPossibleSeconds)))
                        .setPositiveButton("仍要启用", (d, w) -> {
                            saveGoalSettings(lap, distance, pace, maxKmh,
                                    enabled.isChecked(), RouteStore.loadSpeedVariance(this));
                            dialog.dismiss();
                        })
                        .setNegativeButton("返回修改", null)
                        .show();
                return;
            }
            saveGoalSettings(lap, distance, pace, maxKmh,
                    enabled.isChecked(), RouteStore.loadSpeedVariance(this));
            dialog.dismiss();
        });
    }

    private void saveGoalSettings(int lap, double distance, int pace, int maxKmh,
                                  boolean enabled, boolean variance) {
        RouteStore.saveGoalLapMeters(this, lap);
        RouteStore.saveGoalDistanceMeters(this, (float) distance);
        RouteStore.saveGoalPaceSeconds(this, pace);
        RouteStore.saveGoalMaxKmh(this, maxKmh);
        RouteStore.saveGoalEnabled(this, enabled);
        // 速度混淆统一由「设置」管理；配速模式下播放器会自动启用，这里不再覆盖用户选择
        RouteStore.saveSpeedVariance(this, variance);
        // 时间由配速自动推导，这里只保存一个参考值，播放时按路线实际长度重算
        GoalPlanner.Estimate estimate = GoalPlanner.estimate(lap, distance,
                route.size() >= 2 ? route.totalMeters() : lap, pace, maxKmh);
        RouteStore.saveGoalTimeSeconds(this, (int) Math.round(estimate.timeSeconds));

        refreshUi();
        updateInfoTexts();
        String message = String.format(Locale.US, "%s · %d 圈 · 目标 %s · 配速 %s/km",
                enabled ? "已启用目标配速" : "目标配速已关闭",
                estimate.laps, formatDuration(estimate.timeSeconds),
                formatPace(pace));
        toast(message);
    }

    private void updateGoalPreview(TextView preview, int lap, double distance,
                                   int pace, int maxKmh) {
        if (preview == null) {
            return;
        }
        double routeMeters = route.size() >= 2 ? route.totalMeters() : lap;
        GoalPlanner.Estimate estimate = GoalPlanner.estimate(lap, distance, routeMeters,
                pace, maxKmh);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US, "圈数：%d 圈（计划 %s）\n",
                estimate.laps, formatDistance(estimate.plannedMeters)));
        sb.append(String.format(Locale.US, "实际距离：%s · 目标时间：%s\n",
                formatDistance(estimate.actualMeters), formatDuration(estimate.timeSeconds)));
        sb.append(String.format(Locale.US, "平均速度：%.1f km/h · 上限：%d km/h",
                estimate.avgSpeedMps * 3.6, maxKmh));
        if (Math.abs(routeMeters - lap) > Math.max(2.0, lap * 0.02)) {
            sb.append(String.format(Locale.US, "\n当前路线一圈 %s，与所选单圈 %d m 不一致，已按路线实际长度计算",
                    formatDistance(routeMeters), lap));
        }
        if (!estimate.feasible) {
            sb.append(String.format(Locale.US,
                    "\n⚠ 配速超过最大速度，最快约 %s 完成（启用时会提示）",
                    formatDuration(estimate.minPossibleSeconds)));
        }
        preview.setText(sb.toString());
    }

    private static void setupSpinner(Spinner spinner, String[] labels, int selection) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(spinner.getContext(),
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(Math.max(0, Math.min(labels.length - 1, selection)));
    }

    private static int nearestIndex(int[] values, int current) {
        int best = 0;
        int bestDiff = Integer.MAX_VALUE;
        for (int i = 0; i < values.length; i++) {
            int diff = Math.abs(values[i] - current);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = i;
            }
        }
        return best;
    }

    private static int nearestIndex(double[] values, double current) {
        int best = 0;
        double bestDiff = Double.MAX_VALUE;
        for (int i = 0; i < values.length; i++) {
            double diff = Math.abs(values[i] - current);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = i;
            }
        }
        return best;
    }

    private static String formatPace(int secondsPerKm) {
        return String.format(Locale.US, "%d:%02d", secondsPerKm / 60, secondsPerKm % 60);
    }

    /** 秒 → "分:秒"。 */
    private static String formatDuration(double seconds) {
        int total = (int) Math.round(seconds);
        if (total < 0) {
            total = 0;
        }
        return String.format(Locale.US, "%d:%02d", total / 60, total % 60);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private void showTileSourceDialog() {
        List<TileSources.Source> sources = TileSources.all();
        String[] labels = new String[sources.size()];
        String current = TileSources.loadSelectedId(this);
        int checked = 0;
        for (int i = 0; i < sources.size(); i++) {
            labels[i] = sources.get(i).label;
            if (sources.get(i).id.equals(current)) {
                checked = i;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("地图源")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    TileSources.saveSelectedId(this, sources.get(which).id);
                    mapView.setTileSource(sources.get(which).tileSource);
                    refreshRouteOverlays();
                    if (Session.INSTANCE.mode != Session.Mode.IDLE) {
                        updateCurrentMarker();
                    }
                    try {
                        mapView.getTileProvider().clearTileCache();
                    } catch (RuntimeException ignored) {
                        // 忽略
                    }
                    mapView.invalidate();
                    dialog.dismiss();
                    toast("地图源：" + labels[which]);
                })
                .show();
    }

    private void showCoordinateDialog() {
        float density = getResources().getDisplayMetrics().density;
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * density);
        container.setPadding(pad, pad / 2, pad, 0);

        EditText latInput = new EditText(this);
        latInput.setHint("纬度，例如 39.9042");
        latInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
                | InputType.TYPE_NUMBER_FLAG_SIGNED);
        EditText lonInput = new EditText(this);
        lonInput.setHint("经度，例如 116.4074");
        lonInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
                | InputType.TYPE_NUMBER_FLAG_SIGNED);
        container.addView(latInput);
        container.addView(lonInput);

        new AlertDialog.Builder(this)
                .setTitle("输入坐标添加路径点")
                .setView(container)
                .setPositiveButton("添加", (dialog, which) -> {
                    try {
                        double lat = Double.parseDouble(latInput.getText().toString().trim());
                        double lon = Double.parseDouble(lonInput.getText().toString().trim());
                        if (!GeoMath.isValid(lat, lon)) {
                            toast("坐标不合法");
                            return;
                        }
                        addPoint(lat, lon);
                        mapView.getController().animateTo(mapPoint(lat, lon));
                    } catch (NumberFormatException e) {
                        toast("请输入合法数字");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ------------------------------------------------------------ 菜单

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem floatItem = menu.findItem(R.id.action_float);
        if (floatItem != null) {
            floatItem.setChecked(RouteStore.loadOverlayEnabled(this));
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_import) {
            importLauncher.launch(new String[]{"application/gpx+xml", "application/xml",
                    "text/xml", "text/plain", "*/*"});
            return true;
        } else if (id == R.id.action_export) {
            if (route.size() < 1) {
                toast("路径为空，无法导出");
                return true;
            }
            String name = "vloc-" + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US)
                    .format(new Date()) + ".gpx";
            exportLauncher.launch(name);
            return true;
        } else if (id == R.id.action_module_status) {
            showModuleStatusDialog();
            return true;
        } else if (id == R.id.action_map_diag) {
            showDiagnostics(false);
            return true;
        } else if (id == R.id.action_root_diag) {
            showDiagnostics(true);
            return true;
        } else if (id == R.id.action_tile_source) {
            showTileSourceDialog();
            return true;
        } else if (id == R.id.action_presets) {
            showPresetRoutesDialog();
            return true;
        } else if (id == R.id.action_goal) {
            showGoalDialog();
            return true;
        } else if (id == R.id.action_locate) {
            locateMe(true);
            return true;
        } else if (id == R.id.action_add_coord) {
            showCoordinateDialog();
            return true;
        } else if (id == R.id.action_setup) {
            openSetupWizard();
            return true;
        } else if (id == R.id.action_save_route) {
            saveCurrentRouteDialog();
            return true;
        } else if (id == R.id.action_route_library) {
            showRouteLibrary();
            return true;
        } else if (id == R.id.action_float) {
            toggleOverlay();
            return true;
        } else if (id == R.id.action_settings) {
            showSettingsDialog();
            return true;
        } else if (id == R.id.action_usage) {
            startActivity(new Intent(this, UsageLogActivity.class));
            return true;
        } else if (id == R.id.action_scope) {
            startActivity(new Intent(this, ScopeActivity.class));
            return true;
        } else if (id == R.id.action_about) {
            showAboutDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void openSetupWizard() {
        Intent intent = new Intent(this, SetupActivity.class);
        if (route.size() >= 2) {
            intent.putExtra("route", route.encode());
        }
        startActivity(intent);
    }

    private void saveCurrentRouteDialog() {
        if (route.size() < 2) {
            toast("至少 2 个路径点才能保存为路线");
            return;
        }
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint("路线名称，例如：上班通勤");
        new AlertDialog.Builder(this)
                .setTitle("保存当前路线")
                .setView(input)
                .setPositiveButton("保存", (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        name = "路线 " + System.currentTimeMillis();
                    }
                    RouteStore.saveNamedRoute(this, name, route);
                    toast("已保存到路线库：" + name);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showRouteLibrary() {
        final List<RouteStore.NamedRoute> items = RouteStore.loadRouteLibrary(this);
        if (items.isEmpty()) {
            toast("路线库为空，先绘制并保存一条路线");
            return;
        }
        String[] labels = new String[items.size()];
        for (int i = 0; i < items.size(); i++) {
            labels[i] = items.get(i).name + " · " + items.get(i).route.size() + " 个点";
        }
        new AlertDialog.Builder(this)
                .setTitle("路线库")
                .setItems(labels, (dialog, which) -> {
                    route = items.get(which).route.copy();
                    refreshRouteOverlays();
                    fitRoute();
                    // saveRoute() 内部已刷新，避免一次操作刷两遍
                    saveRoute();
                    toast("已载入：" + items.get(which).name);
                })
                .setNeutralButton("删除…", (dialog, which) -> showDeleteRouteDialog(items))
                .setNegativeButton("取消", null)
                .show();
    }

    private void showDeleteRouteDialog(final List<RouteStore.NamedRoute> items) {
        String[] labels = new String[items.size()];
        for (int i = 0; i < items.size(); i++) {
            labels[i] = items.get(i).name;
        }
        new AlertDialog.Builder(this)
                .setTitle("删除路线")
                .setItems(labels, (dialog, which) -> {
                    RouteStore.deleteNamedRoute(this, items.get(which).name);
                    toast("已删除：" + items.get(which).name);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showSettingsDialog() {
        View content = getLayoutInflater().inflate(R.layout.dialog_settings, null, false);
        EditText editAltitude = content.findViewById(R.id.editAltitude);
        EditText editAccuracy = content.findViewById(R.id.editAccuracy);
        CheckBox cbGps = content.findViewById(R.id.cbGps);
        CheckBox cbNetwork = content.findViewById(R.id.cbNetwork);
        CheckBox cbFused = content.findViewById(R.id.cbFused);
        SwitchMaterial switchRealism = content.findViewById(R.id.switchRealism);
        SwitchMaterial switchSpeedVariance = content.findViewById(R.id.switchSpeedVariance);
        SwitchMaterial switchAttitude = content.findViewById(R.id.switchAttitude);
        SwitchMaterial switchRandomStart = content.findViewById(R.id.switchRandomStart);
        SwitchMaterial switchAutoStop = content.findViewById(R.id.switchAutoStop);
        SwitchMaterial switchSystemInject = content.findViewById(R.id.switchSystemInject);
        TextView textInjectMode = content.findViewById(R.id.textInjectMode);
        // 「GNSS 完全模拟」：不改布局 XML（本次只动 Java），运行时插进「定位参数」之前
        CheckBox cbGnssSynthetic = addGnssSyntheticOption(content);
        // 「强制接管 GNSS」：构造替换失败时由模块就地改写系统卫星表（默认开）
        final CheckBox cbGnssForce = addGnssForceOption(content);
        // 打开设置就把当前开关同步给模块通道：痕迹清理会删掉 vloc_snapshot.xml，删完之后
        // 模块读不到 key → 退回默认（开）；用户重开设置即恢复自己的选择，不会"设置显示关、
        // 实际却按开执行"
        RouteStore.publishGnssForce(this, RouteStore.loadGnssForce(this));
        // 「GNSS 星表」信息行 + 检查更新按钮：同样运行时插进布局末尾
        addTleUpdateRow(content);
        // 「注入环境」只读状态面板 + 「立即清理痕迹」：追加在既有 9 项之后，不重排
        final InjectionPanel injectPanel = addInjectionStatusPanel(content);
        // 「停止时清理日志（logcat）」：新增开关（SettingsSchema 里登记为 clear_logcat_on_stop）
        SwitchMaterial switchClearLogcat = addClearLogcatOption(content);
        // 「应用更新」：当前版本 + 检查更新（追加在最后，不插入、不重排既有项）
        addAppUpdateRow(content);

        // ---- 回填当前值 ----
        editAltitude.setText(String.format(Locale.US, "%.0f", RouteStore.loadAltitude(this)));
        editAccuracy.setText(String.format(Locale.US, "%.1f", RouteStore.loadAccuracy(this)));
        List<String> providers = RouteStore.loadProviders(this);
        cbGps.setChecked(providers.contains("gps"));
        cbNetwork.setChecked(providers.contains("network"));
        cbFused.setChecked(providers.contains("fused"));
        switchRealism.setChecked(RouteStore.loadRealism(this));
        switchSpeedVariance.setChecked(RouteStore.loadSpeedVariance(this));
        switchAttitude.setChecked(RouteStore.loadAttitudeReplay(this));
        switchRandomStart.setChecked(RouteStore.loadRandomStart(this));
        switchAutoStop.setChecked(RouteStore.loadAutoStopOnFinish(this));
        switchSystemInject.setChecked(RouteStore.loadSystemInject(this));
        cbGnssSynthetic.setChecked(RouteStore.loadGnssSynthetic(this));
        cbGnssForce.setChecked(RouteStore.loadGnssForce(this));
        switchClearLogcat.setChecked(RouteStore.loadClearLogcatOnStop(this));
        updateInjectModeHint(textInjectMode, switchSystemInject.isChecked());
        // 系统级注入时 Provider 勾选无意义，直接禁用避免误解
        switchSystemInject.setOnCheckedChangeListener((v, checked) -> {
            updateInjectModeHint(textInjectMode, checked);
            setProvidersEnabled(cbGps, cbNetwork, cbFused, !checked);
        });
        setProvidersEnabled(cbGps, cbNetwork, cbFused, !switchSystemInject.isChecked());

        // 先 create() 再 show()：异步查询（appop / 心跳）回调里要用 dialog.isShowing()
        // 判断"面板还在不在"，拿到 dialog 引用后才能安全回写视图
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("设置")
                .setView(content)
                .setPositiveButton("保存", (d, which) ->
                        saveSettings(editAltitude, editAccuracy, cbGps, cbNetwork, cbFused,
                                switchRealism, switchSpeedVariance, switchAttitude,
                                switchRandomStart, switchAutoStop, switchSystemInject,
                                cbGnssSynthetic, switchClearLogcat, cbGnssForce))
                .setNegativeButton("取消", null)
                .create();
        injectPanel.dialog = dialog;
        dialog.show();
        // 打开即刷新；心跳/appop 在后台查，回来才更新（不会阻塞弹窗）
        refreshInjectionPanel(injectPanel);
    }

    /** 系统级注入 / 测试 Provider 两种模式互斥，界面上把当前模式说清楚。 */
    private void updateInjectModeHint(TextView view, boolean systemInject) {
        if (view == null) {
            return;
        }
        view.setText(systemInject
                ? "当前：系统级注入（system_server 改写，所有 App 生效、无 mock 标记）"
                : "当前：测试 Provider 注入（需 root + appops，靠模块擦除 mock 标记）");
    }

    private void setProvidersEnabled(CheckBox gps, CheckBox network, CheckBox fused, boolean enabled) {
        for (CheckBox box : new CheckBox[]{gps, network, fused}) {
            if (box != null) {
                box.setEnabled(enabled);
                box.setAlpha(enabled ? 1f : 0.5f);
            }
        }
    }

    /**
     * 「GNSS 完全模拟（室内/模拟器可用）」复选框：运行时插进设置弹窗。
     *
     * <p>为什么在代码里建视图：本次改动只允许动 Java 后端文件，{@code res/layout/dialog_settings.xml}
     * 属于前端资源不能碰；插进 {@code ScrollView → LinearLayout} 的效果与写进 XML 完全一致。
     * 位置紧随「拟真与反检测」分组（它属于同一类"伪装得更像真机"的选项，排在「定位参数」之前）；
     * 找不到分组标题时退回追加到末尾。
     *
     * @return 复选框本身——即使插不进布局也照常返回，保证 {@link #saveSettings} 仍能读写该项
     */
    private CheckBox addGnssSyntheticOption(View content) {
        final CheckBox checkbox = new CheckBox(this);
        checkbox.setText("GNSS 完全模拟（室内/模拟器可用）");
        final TextView hint = new TextView(this);
        hint.setText("不依赖本机真实卫星，使用内置星表按模拟位置生成");
        hint.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f);
        hint.setPadding(0, 0, 0,
                Math.round(getResources().getDisplayMetrics().density * 4f));
        try {
            hint.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        } catch (Throwable ignored) {
            // 取不到主题色就用默认色：只是观感差异，不影响功能
        }
        try {
            final LinearLayout container = settingsContainer(content);
            if (container == null) {
                return checkbox;   // 布局结构变了：设置仍可读写，绝不因为插不进视图而崩
            }
            int index = container.getChildCount();
            for (int i = 0; i < container.getChildCount(); i++) {
                final View child = container.getChildAt(i);
                if (child instanceof TextView
                        && "定位参数".contentEquals(((TextView) child).getText())) {
                    index = i;
                    break;
                }
            }
            container.addView(checkbox, index);
            container.addView(hint, index + 1);
        } catch (Throwable t) {
            Log.w("VLocSettings", "add gnss synthetic option failed", t);
        }
        return checkbox;
    }

    /**
     * 「强制接管 GNSS（构造失败时就地改写系统卫星表）」复选框：运行时插进设置弹窗。
     *
     * <p>与 {@link #addGnssSyntheticOption} 同一手法（{@code res/layout/dialog_settings.xml}
     * 属于前端资源不改），位置在上一个 GNSS 选项之后、「定位参数」之前。
     * key 是 {@code RouteStore.gnss_force}，<b>默认开</b>：构造替换（Builder → 反射构造器 →
     * Unsafe）全部失败时，模块改为反射改写系统正在下发的 {@code GnssStatus}，而不是让 App
     * 看到真实卫星（室内 0 颗、室外与模拟坐标对不上）。
     *
     * @return 复选框本身——即使插不进布局也照常返回，保证「保存」仍能读写该项
     */
    private CheckBox addGnssForceOption(View content) {
        final CheckBox checkbox = new CheckBox(this);
        checkbox.setText("强制接管 GNSS（构造失败时就地改写系统卫星表）");
        try {
            final LinearLayout container = settingsContainer(content);
            if (container == null) {
                return checkbox;   // 布局结构变了：设置仍可读写，绝不因为插不进视图而崩
            }
            int index = container.getChildCount();
            for (int i = 0; i < container.getChildCount(); i++) {
                final View child = container.getChildAt(i);
                if (child instanceof TextView
                        && "定位参数".contentEquals(((TextView) child).getText())) {
                    index = i;
                    break;
                }
            }
            container.addView(checkbox, index);
        } catch (Throwable t) {
            Log.w("VLocSettings", "add gnss force option failed", t);
        }
        return checkbox;
    }

    /** 设置弹窗的内容容器：{@code ScrollView} 里的那个 {@code LinearLayout}（取不到返回 null）。 */
    private static LinearLayout settingsContainer(View content) {
        View root = content;
        if (root instanceof ScrollView && ((ScrollView) root).getChildCount() > 0) {
            root = ((ScrollView) root).getChildAt(0);
        }
        return root instanceof LinearLayout ? (LinearLayout) root : null;
    }

    // ------------------------------------------------------------ 模块更新提示（重启）

    /** 「立即重启」执行的命令（最终形态 {@code su -c reboot}，见 {@link RootShell#su(String)}）。 */
    private static final String CMD_REBOOT = "reboot";
    /**
     * 「仅重启系统框架」执行的命令：重启 zygote 会让 system_server 重新 fork 并重新加载
     * LSPosed 模块，比整机重启轻；不改磁盘、不改任何配置。
     */
    private static final String CMD_RESTART_ZYGOTE = "setprop ctl.restart zygote";

    /**
     * 「模块已更新」提示：模块代码由 LSPosed <b>注入</b>到 {@code system_server} 与各目标进程里，
     * APK 更新只换了磁盘上的安装包，<b>已经跑着的进程里仍然是旧代码</b>，必须重启手机
     * （或重启系统框架 / zygote）才会换成新模块。
     *
     * <p>判定用的是心跳链路（{@code hidden/HookHeartbeat} → {@code service/EnvProvider} →
     * {@link HookHeartbeatStore}）里模块自报的 {@code ver}，与当前
     * {@link BuildConfig#VERSION_CODE} 比较：
     * <ul>
     *   <li>相等 → 一致，不提示；</li>
     *   <li>{@code ver > 0} 且不等 → 模块已更新但系统未重启 → 弹窗提示；</li>
     *   <li>{@code ver == 0}（老版本模块不上报版本 / 没有 system 心跳）→ <b>未知，不弹</b>，
     *       宁可少提示，也不误报"需要重启"。</li>
     * </ul>
     *
     * <p>去重：目标版本（当前 {@code VERSION_CODE}）记进 {@link RouteStore} 的
     * {@code last_restart_prompt_ver}，同一目标版本只提示一次；用户点「稍后」也记，
     * 避免每次开屏都弹。下次 APK 更新（版本号变化）才会再提示一次。
     *
     * <p>非阻塞：本方法只做一次 prefs 读 + 整数比较（主线程），弹窗 post 到下一个主线程消息，
     * root 命令全部在后台线程执行（见 {@link #restartWithRoot}）。任何 Throwable 只记日志。
     */
    private void maybePromptModuleRestart() {
        try {
            final int current = BuildConfig.VERSION_CODE;
            if (current <= 0) {
                return;
            }
            final int running = HookHeartbeatStore.freshestSystemVersionCode(this);
            if (!restartNeeded(running, current)) {
                return;   // 未知（老模块 / 没有 system 心跳）或版本一致：都不提示
            }
            if (RouteStore.loadLastRestartPromptVer(this) == current) {
                return;   // 这个目标版本已经提示过（点过「稍后」也算）
            }
            if (isFinishing() || isDestroyed()) {
                return;
            }
            final String oldLabel = versionLabel(
                    HookHeartbeatStore.freshestSystemVersionName(this), running);
            final String newLabel = versionLabel(BuildConfig.VERSION_NAME, current);
            final String body = "检测到系统里的模块仍是 v" + oldLabel + "，当前应用是 v" + newLabel
                    + "。需要重启手机（或重启系统框架）新模块才会生效。";
            final Runnable show = () -> showModuleRestartDialog(current, body);
            // 晚一拍再弹：onCreate 阶段窗口 token 可能还没就绪，直接 show() 有风险；
            // post 到 decorView 既保证"启动后"才出现，也完全不阻塞启动路径。
            final View anchor = getWindow() == null ? null : getWindow().getDecorView();
            if (anchor == null) {
                show.run();
            } else {
                anchor.post(show);
            }
        } catch (Throwable t) {
            Log.w(TAG, "module restart prompt failed", t);
        }
    }

    /**
     * 「模块已更新」对话框：立即重启 / 仅重启系统框架 / 稍后。
     *
     * <p>三个按钮都会先记下目标版本（去重），再执行各自的动作；「稍后」只记录不重启。
     * 关闭方式（按钮 / 返回键 / 点外部）统一在 {@code setOnDismissListener} 里再记一次，
     * 保证"同一版本只提示一次"不会因为绕开按钮而失效（写同一个 int，幂等）。
     *
     * @param targetVer 当前应用版本号（去重 key 的值）
     * @param body      正文文案
     */
    private void showModuleRestartDialog(final int targetVer, String body) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        final AlertDialog dialog;
        try {
            dialog = new AlertDialog.Builder(this)
                    .setTitle("模块已更新")
                    .setMessage(body)
                    .setPositiveButton("立即重启", (d, w) -> {
                        markRestartPromptShown(targetVer);
                        restartWithRoot(CMD_REBOOT);
                    })
                    .setNeutralButton("仅重启系统框架", (d, w) -> {
                        markRestartPromptShown(targetVer);
                        restartWithRoot(CMD_RESTART_ZYGOTE);
                    })
                    .setNegativeButton("稍后", (d, w) -> markRestartPromptShown(targetVer))
                    .create();
        } catch (Throwable t) {
            Log.w(TAG, "create module restart dialog failed", t);
            return;
        }
        try {
            dialog.setOnDismissListener(d -> markRestartPromptShown(targetVer));
            dialog.show();
        } catch (Throwable t) {
            Log.w(TAG, "show module restart dialog failed", t);
        }
    }

    /** 记下"这个目标版本已经提示过重启"（{@code last_restart_prompt_ver}；重复写同一个值无害）。 */
    private void markRestartPromptShown(int targetVer) {
        try {
            RouteStore.saveLastRestartPromptVer(this, targetVer);
        } catch (Throwable t) {
            Log.w(TAG, "save restart prompt version failed", t);
        }
    }

    /**
     * 后台线程执行一条 root 命令（{@code su -c <cmd>}）。
     *
     * <p>为什么必须后台：{@link RootShell#su(String)} 首次授权要等用户点 Magisk/KernelSU 的
     * 授权弹窗（默认 20s 超时），放主线程会 ANR；<code>reboot</code> / <code>setprop</code>
     * 本身也可能要一两秒。
     *
     * <p>失败处理：只 Toast 提示"请手动重启"，<b>不重试、不抛异常、不影响后续使用</b>。
     * 成功时设备/框架马上重启，这条 Toast 不会再走到（因此不做成功提示，避免一闪而过）。
     *
     * @param command 要执行的命令（本类只传 {@link #CMD_REBOOT} / {@link #CMD_RESTART_ZYGOTE}）
     */
    private void restartWithRoot(final String command) {
        if (command == null || command.isEmpty()) {
            return;
        }
        try {
            new Thread(() -> {
                RootShell.Result result = null;
                try {
                    result = RootShell.su(command);
                } catch (Throwable t) {
                    Log.w(TAG, "root restart failed: " + command, t);
                }
                if (result != null && result.ok()) {
                    return;   // 已成功下发：紧接着系统就会重启/重建
                }
                runOnUiThread(() -> {
                    try {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        toast("重启失败，请手动重启手机（或重启系统框架）");
                    } catch (Throwable t) {
                        Log.w(TAG, "restart failure toast failed", t);
                    }
                });
            }, "vloc-restart").start();
        } catch (Throwable t) {
            Log.w(TAG, "start restart thread failed", t);
        }
    }

    /**
     * 版本展示：优先用版本名（{@code VERSION_NAME}，如 {@code 0.46.5}），
     * 没有 / 不可用时退回数字版本号（模块心跳里 {@code ver} 一定存在）。
     */
    private static String versionLabel(String name, int code) {
        try {
            final String trimmed = name == null ? "" : name.trim();
            if (!trimmed.isEmpty() && trimmed.length() <= HookHeartbeatStore.MAX_VERSION_NAME_LEN) {
                return trimmed;
            }
        } catch (Throwable ignored) {
            // 落到数字版本号
        }
        return code > 0 ? String.valueOf(code) : "?";
    }

    // ------------------------------------------------------------ 注入环境面板（只读 + 立即清理）

    /**
     * 「注入环境」面板的视图句柄。
     *
     * <p>只做一件事：把 11 行状态文本（原有 7 行 + 「卫星表注入」1 行 + 兼容状态 2 行 +
     * 「模块版本」1 行）和「立即清理痕迹」按钮攒在一起，供异步回调回写；
     * {@link #dialog} 是"面板还在不在"的唯一判据——对话框关掉后所有后台回调直接丢弃，
     * 既不会更新已销毁的视图，也不会因为 Activity 已 finishing 而崩。
     */
    private final class InjectionPanel {
        TextView moduleValue;
        /** 「模块版本」：system 心跳自报的模块版本 vs 当前应用版本（见 {@link #moduleVersionText}）。 */
        TextView moduleVersionValue;
        TextView modeValue;
        TextView providerValue;
        TextView appopValue;
        TextView overlayValue;
        TextView snapshotValue;
        TextView cleanupValue;
        /** 「卫星表注入」：最近一次 system 心跳里模块上报的 GNSS 注入结果。 */
        TextView gnssValue;
        TextView lsposedValue;
        TextView systemValue;
        Button cleanButton;
        AlertDialog dialog;

        /** 面板是否仍可见（对话框已 dismiss / 尚未 show 时返回 false）。 */
        boolean alive() {
            try {
                return dialog != null && dialog.isShowing()
                        && !isFinishing() && !isDestroyed();
            } catch (Throwable t) {
                return false;
            }
        }
    }

    /**
     * 「注入环境」只读状态面板 + 「立即清理痕迹」按钮：运行时追加到设置弹窗末尾。
     *
     * <p>沿用 {@link #addGnssSyntheticOption} 的做法（{@code res/layout/dialog_settings.xml}
     * 属于前端资源、本轮不动）：整个面板按 {@code ScrollView → LinearLayout} 的结构追加，
     * <b>不插入、不重排既有的 9 个设置项</b>。11 行数据来源见 {@link #refreshInjectionPanel}。
     *
     * @return 视图句柄——即使布局结构变了、插不进去也照常返回（各值仍可刷新，只是看不见）
     */
    private InjectionPanel addInjectionStatusPanel(View content) {
        final InjectionPanel panel = new InjectionPanel();
        try {
            final LinearLayout container = settingsContainer(content);
            if (container == null) {
                return panel;
            }
            container.addView(groupTitle("注入环境（只读状态）"));
            panel.moduleValue = addStatusRow(container, "模块注入", "检查中…");
            // 模块版本：system 心跳里模块自报的 ver/vn 与当前应用版本比对（见 moduleVersionText）。
            // 不一致 = 模块已更新但没重启，运行中还是旧代码 → 用警示色 + ⚠ 前缀。
            panel.moduleVersionValue = addStatusRow(container, "模块版本", "检查中…");
            panel.modeValue = addStatusRow(container, "注入模式", "…");
            panel.providerValue = addStatusRow(container, "测试 Provider", "…");
            panel.appopValue = addStatusRow(container, "mock_location 权限", "检查中…");
            panel.overlayValue = addStatusRow(container, "悬浮窗", "…");
            panel.snapshotValue = addStatusRow(container, "快照文件", "检查中…");
            panel.cleanupValue = addStatusRow(container, "上次清理", "…");
            // 卫星表注入：模块在 GNSS hook 首次确定结果后补报的心跳字段（gnss），
            // 中文映射见 gnssInjectionLabel；无心跳 / 没上报 → 「未知」
            panel.gnssValue = addStatusRow(container, "卫星表注入", "检查中…");
            // 兼容状态两行（只读、追加在既有 7 行之后，不重排）：
            //   LSPosed API —— XposedBridge.getXposedVersion()，反射取值，取不到显示「未知」
            //   系统版本   —— Android SDK <RELEASE>（API <SDK_INT>）+ 心跳里的已装 hook 组数
            panel.lsposedValue = addStatusRow(container, "LSPosed API", lsposedApiText());
            panel.systemValue = addStatusRow(container, "系统版本", systemCompatText(null));
            panel.cleanButton = new Button(this);
            panel.cleanButton.setText("立即清理痕迹");
            panel.cleanButton.setOnClickListener(v -> startManualTraceCleanup(panel));
            container.addView(panel.cleanButton);
            container.addView(hintText("清快照 xml / 卫星快照 / 悬浮窗 / 通知 / appop 校验 / "
                    + "/data/local/tmp/vloc*，logcat 按上面开关决定；会话运行中会跳过快照两项，"
                    + "避免打断正在注入的通道。"));
        } catch (Throwable t) {
            Log.w(TAG, "add injection status panel failed", t);
        }
        return panel;
    }

    /**
     * 「停止时清理日志（logcat）」开关：运行时追加在面板之后（新增项，既有项不动）。
     *
     * <p>开关本身的 key 是 {@code RouteStore} 的 {@code clear_logcat_on_stop}
     * （{@code SettingsSchema} 里登记为 {@code clear_logcat_on_stop}，默认关）。
     *
     * @return 开关本身——即使插不进布局也照常返回，保证「保存」仍能读写该项
     */
    private SwitchMaterial addClearLogcatOption(View content) {
        final SwitchMaterial switchClearLogcat = new SwitchMaterial(this);
        switchClearLogcat.setText("停止时清理日志（logcat）");
        try {
            final LinearLayout container = settingsContainer(content);
            if (container == null) {
                return switchClearLogcat;
            }
            container.addView(groupTitle("停止清理"));
            container.addView(switchClearLogcat);
            container.addView(hintText(
                    "停止模拟后用 root 清空 logcat 缓冲区（会清掉其他应用的日志）"));
        } catch (Throwable t) {
            Log.w(TAG, "add clear-logcat option failed", t);
        }
        return switchClearLogcat;
    }

    /**
     * 刷新「注入环境」面板（三个更新阶段，全部回主线程写视图）。
     *
     * <ol>
     *   <li><b>主线程</b>（纯内存 / prefs 读）：注入模式、测试 Provider、悬浮窗、上次清理；</li>
     *   <li><b>后台</b> {@code vloc-inject-hb}：模块心跳（JSON 解析）与快照文件是否存在（磁盘 IO）；</li>
     *   <li><b>后台</b> {@code vloc-inject-appop}：{@code appops get}（要 su，可能好几秒）。</li>
     * </ol>
     * 先生成"检查中…"，查到再覆盖；对话框关掉后回调按 {@link InjectionPanel#alive()} 丢弃。
     */
    private void refreshInjectionPanel(final InjectionPanel panel) {
        if (panel == null) {
            return;
        }
        // ---- 1. 主线程就能定下来的 ----
        setValue(panel.modeValue, injectionModeText());
        setValue(panel.providerValue, testProviderText());
        setValue(panel.overlayValue, FloatingWindow.isAnyShowing() ? "显示中" : "已隐藏");
        setValue(panel.cleanupValue, lastCleanupText());
        setValue(panel.moduleValue, "检查中…");
        // 「模块版本」要读心跳 prefs（后台线程算，回来连颜色一起写），先占位
        setValue(panel.moduleVersionValue, "检查中…");
        setValue(panel.appopValue, "检查中…");
        // 「卫星表注入」要读心跳 prefs（后台线程），先占位
        setValue(panel.gnssValue, "检查中…");
        // 兼容状态：LSPosed API 是内存反射（主线程直接读），系统版本先出 SDK 部分、
        // hook 组数要读心跳 prefs 所以留给下面的后台线程补全（拿不到保持「—」）。
        setValue(panel.lsposedValue, lsposedApiText());
        setValue(panel.systemValue, systemCompatText(null));

        // ---- 2. 心跳 + 快照文件（后台；两条线程分开，appop 的 su 慢也不拖住别的行）----
        final Context app = getApplicationContext();
        new Thread(() -> {
            final String module = moduleInjectionText(app);
            final String snapshot = snapshotFileText(app);
            final String systemCompat = systemCompatText(app);
            // 卫星表注入：最近一条 system 心跳里的 gnss 字段（模块在 GNSS hook 里补报）
            final String gnss = gnssInjectionText(app);
            // 模块版本：system 心跳自报的 ver/vn vs 当前应用版本（不一致 = 需要重启）
            final String moduleVersion = moduleVersionText(app);
            final boolean moduleVersionWarn = moduleVersionNeedsRestart(app);
            runOnUiThread(() -> {
                if (!panel.alive()) {
                    return;
                }
                setValue(panel.moduleValue, module);
                setValue(panel.snapshotValue, snapshot);
                setValue(panel.systemValue, systemCompat);
                setValue(panel.gnssValue, gnss);
                setModuleVersionRow(panel.moduleVersionValue, moduleVersion, moduleVersionWarn);
            });
        }, "vloc-inject-hb").start();

        // ---- 3. mock_location appop（后台 su 查询）----
        new Thread(() -> {
            final String appop = mockAppopText(app);
            runOnUiThread(() -> {
                if (!panel.alive()) {
                    return;
                }
                setValue(panel.appopValue, appop);
            });
        }, "vloc-inject-appop").start();
    }

    /**
     * 面板「LSPosed API」行：{@code XposedBridge.getXposedVersion()}。
     *
     * <p><b>为什么用反射</b>：xposed-api 是 {@code compileOnly}，主 App 进程自己并<b>不</b>被
     * LSPosed 注入（{@code HookEntry} 明确跳过 {@code com.vloc.motion}），运行时这个类根本不存在，
     * 直接引用会抛 {@code NoClassDefFoundError}（属于 {@link Error}，不是 Exception）。
     * 因此这里 {@link Class#forName(String)} + 反射调用，整段 {@code catch (Throwable)}，
     * 取不到就显示「未知」——降级永远是“少显示一点”，不会崩。
     *
     * @return 例如 {@code "API 93"}；取不到返回「未知」
     */
    private static String lsposedApiText() {
        try {
            final Class<?> bridge = Class.forName("de.robv.android.xposed.XposedBridge");
            final Method version = bridge.getMethod("getXposedVersion");
            final Object value = version.invoke(null);
            if (value instanceof Integer) {
                return "API " + value;
            }
            return value == null ? "未知" : String.valueOf(value);
        } catch (Throwable t) {
            return "未知";
        }
    }

    /**
     * 面板「系统版本」行：{@code Android SDK <RELEASE>（API <SDK_INT>）} + 已安装 hook 组数。
     *
     * <p>SDK 部分纯内存（主线程可调）；hook 组数来自模块心跳（{@link HookHeartbeatStore}），
     * 要读 prefs，所以传 {@code null} 时只显示 SDK 部分、hook 显示「—」，
     * 由后台线程带上 {@code context} 再补全（见 {@link #refreshInjectionPanel}）。
     *
     * @param context 用于读心跳；null = 只显示系统版本
     */
    private static String systemCompatText(Context context) {
        String base;
        try {
            base = "Android SDK " + Build.VERSION.RELEASE
                    + "（API " + Build.VERSION.SDK_INT + "）";
        } catch (Throwable t) {
            base = "Android SDK ?（API ?）";
        }
        String hooks;
        try {
            hooks = hookGroupsText(context);
        } catch (Throwable t) {
            hooks = "hook 组 —";
        }
        return base + " · " + hooks;
    }

    /**
     * 已安装的 hook 组数 / 条数（来源：模块心跳的 {@code hooks} 字段）。
     *
     * <p>“组”= 心跳里的进程条目数（系统框架 1 组 + 每个被注入的应用进程各 1 组），
     * “条”= 各组自报的 hook 数之和。一条心跳都没有（模块没注入 / 从没跑过）时显示「—」，
     * 不猜 0 —— 0 会被误读成“注入了但一个 hook 都没装上”。
     */
    private static String hookGroupsText(Context context) {
        if (context == null) {
            return "hook 组 —";
        }
        try {
            final long now = System.currentTimeMillis();
            int groups = 0;
            int hooks = 0;
            for (HookHeartbeatStore.Beat beat : HookHeartbeatStore.beats(context)) {
                if (beat == null) {
                    continue;
                }
                // 只算“新鲜”的心跳（与 moduleInjectionText 同一 24h 窗口）：更早的条目
                // 说明那个进程很久没上报了，算成“已安装”会虚高。
                if (now - beat.at > HookHeartbeatStore.FRESH_MS) {
                    continue;
                }
                groups++;
                if (beat.hooks > 0) {
                    hooks += beat.hooks;
                }
            }
            if (groups <= 0) {
                return "hook 组 —";
            }
            return "hook 组 " + groups + "（共 " + hooks + " 条）";
        } catch (Throwable t) {
            return "hook 组 —";
        }
    }

    /** 面板第 1 行：模块心跳（system 优先，其次 app 进程数，最后「未检测到」）。 */
    private static String moduleInjectionText(Context context) {
        final long now = System.currentTimeMillis();
        final HookHeartbeatStore.Beat system = HookHeartbeatStore.freshestSystem(
                context, HookHeartbeatStore.FRESH_MS, Build.VERSION.SDK_INT);
        if (system != null) {
            final long age = Math.max(0L, now - system.at);
            return age < 1500L
                    ? "已生效（刚刚）"
                    : "已生效（" + formatAgeTag(age) + "前）";
        }
        int apps = 0;
        for (HookHeartbeatStore.Beat beat : HookHeartbeatStore.beats(context)) {
            if (!beat.isSystem()) {
                apps++;
            }
        }
        return apps > 0 ? "已注入 " + apps + " 个应用进程" : "未检测到";
    }

    /**
     * 面板「卫星表注入」行：最近一条 <b>system</b> 心跳里模块上报的 {@code gnss} 字段。
     *
     * <p>数据链路：{@code hidden/SystemHooks} 在 GNSS hook 里首次确定结果 → 静态字段
     * {@code sGnssState} → {@code HookHeartbeat.reportGnss} 补一条心跳 →
     * {@link HookHeartbeatStore.Beat#gnss}。这里只负责"取最近一条 + 中文映射"。
     *
     * <p>没有 system 心跳（模块没注入）、或心跳里没有 gnss（老版本模块；或补报还没到）
     * 一律显示「未知」，不猜。
     */
    private static String gnssInjectionText(Context context) {
        if (context == null) {
            return "未知";
        }
        try {
            final HookHeartbeatStore.Beat system = HookHeartbeatStore.freshestSystem(
                    context, HookHeartbeatStore.FRESH_MS, Build.VERSION.SDK_INT);
            if (system == null) {
                return "未知";
            }
            return gnssInjectionLabel(system.gnss);
        } catch (Throwable t) {
            return "未知";
        }
    }

    /**
     * 模块上报值 → 面板中文（与 {@link HookHeartbeatStore} 的 {@code GNSS_*} 常量一一对应）。
     *
     * <p>未知值（更新的模块带来新枚举 / 脏数据）→「未知」：宁可少说，也不显示原始协议串。
     */
    private static String gnssInjectionLabel(String gnss) {
        if (gnss == null) {
            return "未知";
        }
        switch (gnss) {
            case HookHeartbeatStore.GNSS_BUILT_BUILDER:
                return "已构造(builder)";
            case HookHeartbeatStore.GNSS_BUILT_CTOR:
                return "已构造(构造器)";
            case HookHeartbeatStore.GNSS_BUILT_UNSAFE:
                return "已构造(Unsafe)";
            case HookHeartbeatStore.GNSS_FORCE_MUTATED:
                return "已强制改写";
            case HookHeartbeatStore.GNSS_SKIP_NO_SATELLITES:
                return "无卫星可用";
            case HookHeartbeatStore.GNSS_SKIP_NULL:
                return "未生效";
            default:
                return "未知";
        }
    }

    /**
     * 面板「模块版本」行：最近一条 <b>system</b> 心跳里模块自报的版本 vs 当前应用版本。
     *
     * <p>三态（与 {@link #maybePromptModuleRestart} 的判定口径完全一致）：
     * <ul>
     *   <li>一致 → {@code v<当前>（已生效）}；</li>
     *   <li>不一致（心跳 {@code ver > 0}）→ {@code ⚠ 运行中 v<旧> / 当前 v<新>（需重启）}；</li>
     *   <li>没有 system 心跳 / 老模块没上报版本（{@code ver == 0}）→ {@code 未知}。</li>
     * </ul>
     *
     * <p>为什么 {@code ver == 0} 显示「未知」而不是"不一致"：老版本模块根本没有这个字段，
     * 把它当"版本不对"会让所有还没升级模块的用户看到一片 ⚠，那是误报。
     */
    private static String moduleVersionText(Context context) {
        if (context == null) {
            return "未知";
        }
        try {
            final String currentName = BuildConfig.VERSION_NAME;
            final int current = BuildConfig.VERSION_CODE;
            final int running = HookHeartbeatStore.freshestSystemVersionCode(context);
            if (running <= 0) {
                return "未知";   // 没有 system 心跳 / 老模块没上报版本：不猜
            }
            if (running == current) {
                return "v" + versionLabel(currentName, current) + "（已生效）";
            }
            return "⚠ 运行中 v"
                    + versionLabel(HookHeartbeatStore.freshestSystemVersionName(context), running)
                    + " / 当前 v" + versionLabel(currentName, current) + "（需重启）";
        } catch (Throwable t) {
            return "未知";
        }
    }

    /**
     * 「模块版本」行是否处于"需要重启"状态——决定用警示色还是常规强调色。
     *
     * <p>单独判定（而不是去 {@link #moduleVersionText} 里找 ⚠）是为了让颜色逻辑不依赖文案：
     * 文案以后改了也不会让颜色跟着错。
     */
    private static boolean moduleVersionNeedsRestart(Context context) {
        if (context == null) {
            return false;
        }
        try {
            return restartNeeded(HookHeartbeatStore.freshestSystemVersionCode(context),
                    BuildConfig.VERSION_CODE);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 版本失配判定（提示与面板共用同一口径，避免两处漂移）：
     * 只有"心跳里的模块版本已知（{@code > 0}）且与当前应用版本不同"才需要重启。
     *
     * <p>{@code running == 0} 是"未知"（老版本模块不上报 / 没有 system 心跳 / 心跳过期），
     * <b>不是</b>"版本不一致"——把它算成不一致会让所有还没升级模块的用户被误提示。
     *
     * @param running 最近一条 system 心跳自报的模块版本号（0 = 未知）
     * @param current 当前应用版本号（{@code BuildConfig.VERSION_CODE}）
     */
    private static boolean restartNeeded(int running, int current) {
        return running > 0 && current > 0 && running != current;
    }

    /** 写「模块版本」行：需要重启时用警示色（{@code R.color.danger}），否则用常规强调色。 */
    private void setModuleVersionRow(TextView view, String text, boolean warn) {
        if (view == null) {
            return;
        }
        try {
            view.setText(text == null ? "" : text);
            view.setTextColor(warn
                    ? ContextCompat.getColor(this, R.color.danger)
                    : brandColor());
        } catch (Throwable t) {
            Log.w(TAG, "set module version row failed", t);
        }
    }

    /** 面板第 2 行：当前注入模式（{@code system_inject} 优先，否则看会话是否在跑）。 */
    private String injectionModeText() {
        if (RouteStore.loadSystemInject(this)) {
            return "系统级";
        }
        return Session.INSTANCE.isActive() ? "root 测试 Provider" : "未运行";
    }

    /** 面板第 3 行：测试 Provider 是否已接管本进程（root 成功 + 会话运行）。 */
    private static String testProviderText() {
        final Session session = Session.INSTANCE;
        return session.rootOk && session.isActive() ? "已接管" : "未接管";
    }

    /**
     * 面板第 4 行：{@code android:mock_location} appop（后台线程调用）。
     *
     * <p>不猜：没有 root / appops 不可用时记「未知」，输出既不含 allow 也不含 default 也记「未知」。
     */
    private static String mockAppopText(Context context) {
        final String pkg = context == null ? null : context.getPackageName();
        if (pkg == null || pkg.isEmpty()) {
            return "未知";
        }
        final RootShell.Result result = RootShell.getMockAppop(pkg);
        if (result == null || !result.ok()) {
            return "未知";   // 没有 root / 命令失败：宁可说不知道，也不误报 default
        }
        if (RootShell.isMockAppopAllowed(result)) {
            return "allow";
        }
        return result.combined().toLowerCase(Locale.US).contains("default") ? "default" : "未知";
    }

    /** 面板第 6 行：world-readable 快照文件是否还在（后台做一次 {@code exists()}）。 */
    private static String snapshotFileText(Context context) {
        final File file = snapshotPrefsFile(context);
        if (file == null) {
            return "未知";
        }
        try {
            return file.exists() ? "存在" : "已清理";
        } catch (Throwable t) {
            return "未知";
        }
    }

    /**
     * {@code shared_prefs/vloc_snapshot.xml}（与 {@code TraceCleaner} 同一路径算法：
     * {@code ApplicationInfo.dataDir} 优先，{@code files} 的父目录兜底）。
     */
    private static File snapshotPrefsFile(Context context) {
        if (context == null) {
            return null;
        }
        final String name = SnapshotPrefs.PREFS_NAME + ".xml";
        try {
            final String dataDir = context.getApplicationInfo() == null
                    ? null : context.getApplicationInfo().dataDir;
            if (dataDir != null && !dataDir.isEmpty()) {
                return new File(new File(dataDir, "shared_prefs"), name);
            }
        } catch (Throwable ignored) {
            // 落到下面的兜底
        }
        try {
            final File files = context.getFilesDir();
            if (files != null && files.getParentFile() != null) {
                return new File(new File(files.getParentFile(), "shared_prefs"), name);
            }
        } catch (Throwable ignored) {
            // 没有可用目录
        }
        return null;
    }

    /** 面板第 7 行：上次清理的时间 + 一行摘要（没有则「从未清理」）。 */
    private static String lastCleanupText() {
        final TraceCleaner.Result result = TraceCleaner.lastResult();
        final long at = TraceCleaner.lastFinishedAtMs();
        if (result == null || at <= 0L) {
            return "从未清理";
        }
        final StringBuilder sb = new StringBuilder();
        sb.append(new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new Date(at)))
                .append(" · 清理 ").append(result.count(TraceCleaner.Status.OK)).append(" 项");
        final int skipped = result.count(TraceCleaner.Status.SKIPPED);
        final int failed = result.count(TraceCleaner.Status.FAILED);
        if (skipped > 0) {
            sb.append("，跳过 ").append(skipped);
        }
        if (failed > 0) {
            sb.append("，失败 ").append(failed);
        }
        return sb.toString();
    }

    /**
     * 「立即清理痕迹」：调 {@link TraceCleaner#cleanup}，完成后 Toast 一行摘要并刷新面板。
     *
     * <p>清理线程由 {@code TraceCleaner} 自己起（daemon），回调已 post 回主线程；
     * 期间按钮置灰 + 改文案，重复点击直接忽略。
     */
    private void startManualTraceCleanup(final InjectionPanel panel) {
        final Button button = panel == null ? null : panel.cleanButton;
        if (button != null) {
            if (!button.isEnabled()) {
                return;   // 已经在清理：忽略重复点击
            }
            button.setEnabled(false);
            button.setAlpha(0.5f);
            button.setText("清理中…");
        }
        final Context app = getApplicationContext();
        final TraceCleaner.Options options = new TraceCleaner.Options();
        // 快照 xml / 卫星快照由 TraceCleaner 无条件执行（会话运行中按 protectActiveSession 跳过）
        options.checkAppop = true;        // mock_location 只读校验
        options.removeTempFiles = true;   // /data/local/tmp/vloc*
        options.notificationId = SERVICE_NOTIFICATION_ID;
        options.alertNotificationId = ALERT_NOTIFICATION_ID;
        options.clearLogcat = RouteStore.loadClearLogcatOnStop(app);   // 取用户设置里的开关
        // 服务的实例是私有成员：用 FloatingWindow 的静态入口隐藏（TraceCleaner 会在主线程跑它）
        options.hideOverlay = FloatingWindow::hideAnyShowing;
        TraceCleaner.cleanup(app, options, result -> {
            if (button != null) {
                button.setEnabled(true);
                button.setAlpha(1f);
                button.setText("立即清理痕迹");
            }
            // 用 application context 弹：Activity 可能在清理期间被销毁
            Toast.makeText(app, cleanupSummaryText(result), Toast.LENGTH_SHORT).show();
            if (!isFinishing() && !isDestroyed()) {
                refreshInjectionPanel(panel);   // 刷新 appop / 快照 / 上次清理三行
            }
        });
    }

    /** 「已清理 N 项，跳过 M 项」一行摘要（有失败项时附上项名，便于对症排查）。 */
    private static String cleanupSummaryText(TraceCleaner.Result result) {
        if (result == null) {
            return "清理未完成";
        }
        final StringBuilder sb = new StringBuilder();
        sb.append("已清理 ").append(result.count(TraceCleaner.Status.OK)).append(" 项");
        sb.append("，跳过 ").append(result.count(TraceCleaner.Status.SKIPPED)).append(" 项");
        final int failed = result.count(TraceCleaner.Status.FAILED);
        if (failed > 0) {
            sb.append("，失败 ").append(failed).append(" 项：");
            boolean first = true;
            for (TraceCleaner.Item item : result.items) {
                if (item.status != TraceCleaner.Status.FAILED) {
                    continue;
                }
                if (!first) {
                    sb.append('/');
                }
                sb.append(item.name);
                first = false;
            }
        }
        return sb.toString();
    }

    /** 毫秒差 →「12s / 5m / 3h / 2d」短标记（面板里已带「前」字，不重复「秒/分钟」）。 */
    private static String formatAgeTag(long ageMs) {
        final long seconds = Math.max(0L, ageMs) / 1000L;
        if (seconds < 60L) {
            return seconds + "s";
        }
        final long minutes = seconds / 60L;
        if (minutes < 60L) {
            return minutes + "m";
        }
        final long hours = minutes / 60L;
        return hours < 48L ? hours + "h" : (hours / 24L) + "d";
    }

    /** 面板/分组小标题（与 dialog_settings.xml 里的小标题同样式，运行时插入用）。 */
    private TextView groupTitle(String text) {
        final TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f);
        view.setTypeface(null, android.graphics.Typeface.BOLD);
        view.setTextColor(brandColor());
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(18);
        view.setLayoutParams(lp);
        return view;
    }

    /** 说明文案（11sp、次要色；与 XML 里的说明行一致）。 */
    private TextView hintText(String text) {
        final TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f);
        view.setTextColor(secondaryColor());
        view.setPadding(0, 0, 0, dp(4));
        return view;
    }

    /** 一行「左标题 + 右状态」（只读），返回右侧状态文本视图。 */
    private TextView addStatusRow(LinearLayout container, String title, String initial) {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(3), 0, dp(3));
        final TextView label = new TextView(this);
        label.setText(title);
        label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f);
        label.setTextColor(secondaryColor());
        label.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        final TextView value = new TextView(this);
        value.setText(initial);
        value.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f);
        value.setTextColor(brandColor());
        value.setGravity(Gravity.END);
        row.addView(label);
        row.addView(value);
        container.addView(row);
        return value;
    }

    /** 写状态文本（视图可能还没建出来 / 已分离：判空即可）。 */
    private static void setValue(TextView view, String text) {
        if (view != null) {
            view.setText(text == null ? "" : text);
        }
    }

    /** 主题强调色；取不到就退回硬编码值（只是观感差异，不影响功能）。 */
    private int brandColor() {
        try {
            return ContextCompat.getColor(this, R.color.brand);
        } catch (Throwable t) {
            return 0xFF2962FF;
        }
    }

    /** 次要文本色；取不到就退回硬编码值。 */
    private int secondaryColor() {
        try {
            return ContextCompat.getColor(this, R.color.text_secondary);
        } catch (Throwable t) {
            return 0xFF5F6368;
        }
    }

    /** dp → px（运行时插入的视图用；项目里 XML 外的视图都按显示密度换算）。 */
    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------ GNSS 星表（过期提醒 / 一键更新）

    /**
     * 启动时的星表过期提醒：`ageDays > STALE_DAYS` 且距上次提醒 ≥ 7 天才弹，
     * 每个 Activity 实例只检查一次。
     *
     * <p>读盘/解析放在一次性后台线程（星表约 40 KB），算完回主线程决定是否弹窗，不阻塞启动。
     */
    private void maybeRemindTleUpdate() {
        if (tleReminderChecked) {
            return;
        }
        tleReminderChecked = true;
        final Context app = getApplicationContext();
        new Thread(() -> {
            final TleUpdater.Catalog catalog = TleUpdater.load(app);
            if (catalog.text == null) {
                return;
            }
            final long lastAt = RouteStore.loadTleRemindAt(app);
            if (!TleUpdater.shouldRemind(catalog.ageDays, lastAt, System.currentTimeMillis())) {
                return;   // 没过期，或 7 天内提醒过
            }
            final int ageDays = catalog.ageDays;
            runOnUiThread(() -> showTleRemindDialog(ageDays));
        }, "vloc-tle-check").start();
    }

    /** 过期提醒对话框：立即更新 / 稍后。弹出即记「已提醒」，点「稍后」也不会每次启动都弹。 */
    private void showTleRemindDialog(final int ageDays) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        RouteStore.saveTleRemindAt(this, System.currentTimeMillis());
        new AlertDialog.Builder(this)
                .setTitle("GNSS 星表已过期 " + Math.max(ageDays, 0) + " 天")
                .setMessage("卫星仰角/方位角会逐渐偏差，长期不更新容易被识别。是否现在更新？")
                .setPositiveButton("立即更新", (dialog, which) -> startTleUpdate(null))
                .setNegativeButton("稍后", null)
                .show();
    }

    /**
     * 更新星表：后台拉取 4 组 CelesTrak TLE → 校验 → 原子落盘，完成后回主线程提示。
     *
     * <p>进度用不可取消的对话框展示（不阻塞主线程）；成功才让服务重新加载星表，
     * 失败只提示原因、继续用当前星表。
     *
     * @param statusView 设置里那一行信息文本（更新后原地刷新）；启动提醒传 null
     */
    private void startTleUpdate(final TextView statusView) {
        if (tleUpdating) {
            toast("正在更新星表，请稍候…");
            return;
        }
        tleUpdating = true;
        final AlertDialog progress = new AlertDialog.Builder(this)
                .setTitle("更新 GNSS 星表")
                .setMessage("正在从 CelesTrak 拉取 4 个分组（每组超时 10 秒）…")
                .setCancelable(false)
                .create();
        progress.show();
        TleUpdater.update(this, result -> {
            tleUpdating = false;
            if (!isFinishing() && !isDestroyed() && progress.isShowing()) {
                progress.dismiss();
            }
            // 无论成功失败都算"提醒过了"：失败时没必要每次启动再弹一遍
            RouteStore.saveTleRemindAt(this, System.currentTimeMillis());
            if (result.ok) {
                // 与界面无关：服务在跑就立刻换用新表（Activity 已销毁也照做）
                LocationService.reloadTleCatalog();
            }
            if (isFinishing() || isDestroyed()) {
                return;
            }
            if (result.ok) {
                toast("星表已更新：" + result.satCount + " 颗（历元 "
                        + TleUpdater.formatEpochUtc(result.newestEpochMs) + "）");
                if (statusView != null) {
                    statusView.setText(tleSummary("已更新", result.ageDays));
                }
            } else {
                toast("更新失败：" + result.error + "，继续使用当前星表");
            }
        });
    }

    /** 设置弹窗里的「GNSS 星表」信息行 + 「检查更新」按钮（运行时插入，不动布局 XML）。 */
    private void addTleUpdateRow(View content) {
        final LinearLayout container = settingsContainer(content);
        if (container == null) {
            return;
        }
        final TextView info = new TextView(this);
        info.setText("GNSS 星表：读取中…");
        info.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f);
        try {
            info.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        } catch (Throwable ignored) {
            // 取不到主题色只是观感差异，不影响功能
        }
        final Button check = new Button(this);
        check.setText("检查更新");
        check.setOnClickListener(v -> startTleUpdate(info));
        container.addView(info);
        container.addView(check);
        fillTleInfo(info);
    }

    /** 后台读一次当前生效星表，把「来源 · 过期 N 天」填进设置里的信息行。 */
    private void fillTleInfo(final TextView info) {
        final Context app = getApplicationContext();
        new Thread(() -> {
            final String summary = tleSummaryText(app);
            runOnUiThread(() -> info.setText(summary));
        }, "vloc-tle-info").start();
    }

    /**
     * 当前星表摘要（设置界面用）：优先读服务最近一次加载的来源 / ageDays
     * （{@link LocationService#gnssTleSource()}），服务还没加载过就按同一优先级现场读一次。
     */
    private static String tleSummaryText(Context context) {
        String source = LocationService.gnssTleSource();
        int ageDays = LocationService.gnssTleAgeDays();
        if (TleUpdater.SOURCE_NONE.equals(source)) {
            final TleUpdater.Catalog catalog = TleUpdater.load(context);
            source = catalog.source;
            ageDays = catalog.ageDays;
        }
        if (TleUpdater.SOURCE_NONE.equals(source)) {
            return "GNSS 星表：无（不产出卫星数据）";
        }
        final String label = TleUpdater.SOURCE_UPDATED.equals(source) ? "已更新" : "内置";
        return tleSummary(label, ageDays);
    }

    private static String tleSummary(String label, int ageDays) {
        return String.format(Locale.US, "GNSS 星表：%s · 过期 %d 天", label, Math.max(ageDays, 0));
    }

    /** 保存设置：所有字段各自校验、各自写回，某一项非法不影响其它项。 */
    private void saveSettings(EditText editAltitude, EditText editAccuracy,
                              CheckBox cbGps, CheckBox cbNetwork, CheckBox cbFused,
                              SwitchMaterial switchRealism, SwitchMaterial switchSpeedVariance,
                              SwitchMaterial switchAttitude, SwitchMaterial switchRandomStart,
                              SwitchMaterial switchAutoStop, SwitchMaterial switchSystemInject,
                              CheckBox cbGnssSynthetic, SwitchMaterial switchClearLogcat,
                              CheckBox cbGnssForce) {
        // 海拔：-500 ~ 9000 m 之外的输入夹紧
        float altitude = RouteStore.loadAltitude(this);
        try {
            altitude = Float.parseFloat(editAltitude.getText().toString().trim());
        } catch (NumberFormatException ignored) {
            // 保持原值
        }
        altitude = com.vloc.motion.core.SettingsSchema.clamp(
                com.vloc.motion.core.SettingsSchema.find("base_altitude"), altitude);
        RouteStore.saveAltitude(this, altitude);

        // 精度：0.5 ~ 100 m，避免 0（除零/异常）与过大（明显假）
        float accuracy = RouteStore.loadAccuracy(this);
        try {
            accuracy = Float.parseFloat(editAccuracy.getText().toString().trim());
        } catch (NumberFormatException ignored) {
            // 保持原值
        }
        accuracy = com.vloc.motion.core.SettingsSchema.clamp(
                com.vloc.motion.core.SettingsSchema.find("accuracy"), accuracy);
        RouteStore.saveAccuracy(this, accuracy);

        List<String> selected = new ArrayList<>();
        if (cbGps.isChecked()) {
            selected.add("gps");
        }
        if (cbNetwork.isChecked()) {
            selected.add("network");
        }
        if (cbFused.isChecked()) {
            selected.add("fused");
        }
        if (selected.isEmpty()) {
            selected.add("gps");
        }
        RouteStore.saveProviders(this, selected);

        RouteStore.saveRealism(this, switchRealism.isChecked());
        RouteStore.saveSpeedVariance(this, switchSpeedVariance.isChecked());
        RouteStore.saveAttitudeReplay(this, switchAttitude.isChecked());
        RouteStore.saveRandomStart(this, switchRandomStart.isChecked());
        RouteStore.saveAutoStopOnFinish(this, switchAutoStop.isChecked());
        RouteStore.saveSystemInject(this, switchSystemInject.isChecked());
        // GNSS 完全模拟：不依赖本机真实卫星（室内/模拟器）；与注入方式一样，下次「开始」生效
        RouteStore.saveGnssSynthetic(this, cbGnssSynthetic.isChecked());
        // 强制接管 GNSS（默认开）：构造失败时就地改写系统卫星表 —— 模块侧 30s 内读到新值，
        // 不用重启系统（saveGnssForce 同时把值发布到模块读得到的通道）
        if (cbGnssForce != null) {
            RouteStore.saveGnssForce(this, cbGnssForce.isChecked());
        }
        // 停止时清 logcat（默认关；破坏性，只有用户显式打开才会在停止流程里执行）
        if (switchClearLogcat != null) {
            RouteStore.saveClearLogcatOnStop(this, switchClearLogcat.isChecked());
        }

        if (switchSystemInject.isChecked()) {
            toast("系统级注入已开启（下次开始生效）\n请确认 LSPosed 已勾选「系统框架」并重启系统");
        } else {
            toast("设置已保存（注入方式与拟真相关选项下次开始时生效）");
        }
    }

    /** 悬浮窗开关：未授权时先去系统设置授权。 */
    private void toggleOverlay() {
        boolean enable = !RouteStore.loadOverlayEnabled(this);
        if (enable && !Settings.canDrawOverlays(this)) {
            pendingOverlayEnable = true;
            showOverlayPermissionDialog();
            return;
        }
        RouteStore.saveOverlayEnabled(this, enable);
        syncOverlayToService(enable);
        toast(enable ? "悬浮窗已开启" : "悬浮窗已关闭");
        invalidateOptionsMenu();
    }

    /** 悬浮窗权限被系统列为"受限权限"时的引导（小米/红米/部分 ColorOS 常见）。 */
    private void showOverlayPermissionDialog() {
        new AlertDialog.Builder(this)
                .setTitle("需要悬浮窗权限")
                .setMessage("系统把「显示在其他应用上层」列为受限权限，直接授权会被拒绝。\n\n"
                        + "请按顺序操作：\n"
                        + "1. 点「应用信息」→ 右上角 ⋮ → 选择「允许受限制的设置」\n"
                        + "2. 返回后点「悬浮窗设置」→ 打开「显示在其他应用上层」\n"
                        + "3. 回到本应用，悬浮窗会自动开启\n\n"
                        + "（不同品牌路径略有差异：设置 → 应用 → 虚拟运动 → 权限）")
                .setPositiveButton("应用信息", (d, w) -> openAppDetails())
                .setNeutralButton("悬浮窗设置", (d, w) -> openOverlaySettings())
                .setNegativeButton("取消", null)
                .show();
    }

    private void openAppDetails() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        } catch (RuntimeException e) {
            toast("无法打开应用信息");
        }
    }

    private void openOverlaySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (RuntimeException e) {
            toast("无法打开悬浮窗权限设置");
        }
    }

    private void syncOverlayToService(boolean enable) {
        if (Session.INSTANCE.isActive()) {
            startService(new Intent(this, LocationService.class)
                    .setAction(LocationService.ACTION_SET_OVERLAY)
                    .putExtra(LocationService.EXTRA_OVERLAY_ENABLED, enable));
        }
    }

    /** 动态读取已安装 APK 的版本号，避免"关于"里写死导致升级后仍显示旧版本。 */
    private String appVersionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "未知";
        }
    }

    private void showAboutDialog() {
        new AlertDialog.Builder(this)
                .setTitle("关于 虚拟运动")
                .setMessage("版本 " + appVersionName()
                        + "（root 版，内置隐藏模块 + GNSS/计步/IMU/姿态回放）\n\n"
                        + "· 轻点地图添加路径点，长按地图临时定位\n"
                        + "· 至少 2 个点后可播放，速度/循环可调\n"
                        + "· 通过 root 给应用授予模拟位置权限，再接管系统 gps/network Provider\n"
                        + "· 停止或退出后会还原真实定位\n\n"
                        + "\n⛔ 许可：PolyForm Noncommercial 1.0.0 —— 禁止任何商业用途（出售/代打卡/捆绑预装等）。"
                        + "\n⚠️ 仅供学习与技术研究，严禁用于打卡作弊、位置欺诈、绕过风控等违规违法用途。"
                        + "使用违反多数应用的用户协议，可能导致账号封禁；一切后果自负。完整条款见仓库 DISCLAIMER.md。\n\n"
                        + "注意：测试 Provider 写入的定位在系统层会被标记为模拟位置，"
                        + "部分应用会检测并拒绝。安装 hidden 隐藏模块并在 LSPosed 中启用，可清除该标记。")
                .setPositiveButton("知道了", null)
                .show();
    }

    // ------------------------------------------------------------ 应用内更新（检查 / 下载 / 安装）

    /**
     * 启动时的静默版本检查：24 小时内只做一次，查到有更新才弹窗，其余情况完全不提示。
     *
     * <p>节流时间在发起请求<b>之前</b>就落盘（{@link UpdateChecker#markChecked}）：
     * 检查失败也算"查过了"，不会每次冷启动都去打 GitHub 接口；手动检查不受这个限制。
     * 网络在 {@link UpdateChecker} 的后台线程里跑，这里不阻塞、不显示 loading。
     */
    private void maybeAutoCheckUpdate() {
        if (autoUpdateChecked) {
            return;
        }
        autoUpdateChecked = true;
        try {
            if (!UpdateChecker.shouldAutoCheck(this)) {
                return;
            }
            UpdateChecker.markChecked(this);
            UpdateChecker.check(this, (hasUpdate, latestTag, notes, apkUrl, sha256Url, error) -> {
                // 静默：失败不提示、没有更新也不提示；只有确实有新版本才弹窗
                if (error != null || !hasUpdate) {
                    return;
                }
                showUpdateDialog(latestTag, notes, apkUrl);
            });
        } catch (Throwable t) {
            Log.w(TAG, "auto update check failed", t);
        }
    }

    /**
     * 设置弹窗里的「应用更新」行：{@code 当前版本 vX.Y.Z} + 「检查更新」按钮。
     *
     * <p>沿用 {@link #addGnssSyntheticOption} 的做法（{@code res/layout/dialog_settings.xml}
     * 属于前端资源、本轮不动）：按 {@code ScrollView → LinearLayout} 的结构追加到末尾，
     * <b>不插入、不重排既有设置项</b>。
     */
    private void addAppUpdateRow(View content) {
        try {
            final LinearLayout container = settingsContainer(content);
            if (container == null) {
                return;
            }
            container.addView(groupTitle("应用更新"));
            final LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            final TextView version = new TextView(this);
            version.setText("当前版本 v" + UpdateChecker.currentVersionName());
            version.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f);
            try {
                version.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
            } catch (Throwable ignored) {
                // 取不到主题色只是观感差异，不影响功能
            }
            version.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            final Button check = new Button(this);
            check.setText("检查更新");
            check.setOnClickListener(v -> startManualUpdateCheck(check));
            row.addView(version);
            row.addView(check);
            container.addView(row);
            container.addView(hintText(
                    "从 GitHub Releases 检查最新版本；无更新时会提示「已是最新版本」。"));
        } catch (Throwable t) {
            Log.w(TAG, "add app update row failed", t);
        }
    }

    /**
     * 手动检查更新（设置里的「检查更新」）：无更新 / 失败都只 Toast，不弹窗。
     *
     * <p>回调可能晚于对话框关闭，所以只判 Activity 状态、不碰对话框视图；
     * 检查期间禁用按钮，防止连点重复起后台任务。
     */
    private void startManualUpdateCheck(final Button button) {
        if (manualUpdateChecking) {
            toast("正在检查更新，请稍候…");
            return;
        }
        manualUpdateChecking = true;
        setCheckButtonEnabled(button, false);
        try {
            UpdateChecker.check(this, (hasUpdate, latestTag, notes, apkUrl, sha256Url, error) -> {
                manualUpdateChecking = false;
                setCheckButtonEnabled(button, true);
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (error != null) {
                    toast("检查失败：" + error);
                    return;
                }
                if (!hasUpdate) {
                    toast("已是最新版本（当前 v" + UpdateChecker.currentVersionName() + "）");
                    return;
                }
                showUpdateDialog(latestTag, notes, apkUrl);
            });
        } catch (Throwable t) {
            manualUpdateChecking = false;
            setCheckButtonEnabled(button, true);
            toast("检查失败：" + reasonText(t));
        }
    }

    /** 检查期间禁用按钮（视图已分离时只记日志，不影响检查本身）。 */
    private static void setCheckButtonEnabled(final Button button, final boolean enabled) {
        if (button == null) {
            return;
        }
        try {
            button.setEnabled(enabled);
            button.setAlpha(enabled ? 1f : 0.5f);
        } catch (Throwable ignored) {
            // 视图已分离：忽略
        }
    }

    /**
     * 「发现新版本」对话框：当前版本 → 新版本 + 更新说明（截断到 ~500 字）+ 下载并安装 / 稍后。
     *
     * <p>回调来自主线程，Activity 已销毁（用户已退出）时直接丢弃，不更新任何视图。
     */
    private void showUpdateDialog(String latestTag, String notes, final String apkUrl) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        final String remote = tagWithPrefix(latestTag);
        final String current = UpdateChecker.currentVersionName();
        final String body = clipNotes(notes, 500);
        final StringBuilder message = new StringBuilder();
        message.append("当前版本 v").append(current)
                .append(" → 新版本 ").append(remote).append('\n');
        if (!body.isEmpty()) {
            message.append("\n更新说明：\n").append(body).append('\n');
        }
        message.append("\n下载完成后会拉起系统安装器（首次需允许「安装未知应用」）。")
                .append("\n提示：若安装时提示「签名不一致 / 应用未安装」，请先卸载当前版本再安装")
                .append("（本机路线库、使用记录等数据会被清空）。");
        try {
            new AlertDialog.Builder(this)
                    .setTitle("发现新版本 " + remote)
                    .setMessage(message.toString())
                    .setPositiveButton("下载并安装",
                            (dialog, which) -> startUpdateDownload(remote, apkUrl))
                    .setNegativeButton("稍后", null)
                    .show();
        } catch (Throwable t) {
            Log.w(TAG, "show update dialog failed", t);
        }
    }

    /** tag 统一带 v 前缀用于展示（GitHub 的 tag 一般本来就是 vX.Y.Z）。 */
    private static String tagWithPrefix(String tag) {
        final String text = tag == null ? "" : tag.trim();
        if (text.isEmpty()) {
            return "v?";
        }
        return text.startsWith("v") || text.startsWith("V") ? "v" + text.substring(1) : "v" + text;
    }

    /**
     * 更新说明截断到约 {@code max} 字：Release 正文可能很长，把对话框撑爆不好看；
     * 截断时避免切坏 emoji 的代理对（不会产生半个字符导致的乱码）。
     */
    private static String clipNotes(String notes, int max) {
        if (notes == null) {
            return "";
        }
        final String text = notes.trim();
        if (text.length() <= max) {
            return text;
        }
        int end = Math.max(max, 0);
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end).trim() + "…";
    }

    /**
     * 用 DownloadManager 下载 APK 到「应用外部目录 → Download」：通知栏显示进度，
     * 完成后由 {@link #updateDownloadReceiver} 拉起安装。
     *
     * <p>任何一步失败（系统下载服务不可用 / 外部目录不可写 / enqueue 抛异常）都退回
     * 浏览器打开 APK 直链；连直链都没有就打开 Releases 页。
     */
    private void startUpdateDownload(String remoteTag, String apkUrl) {
        final String url = apkUrl == null ? "" : apkUrl.trim();
        if (url.isEmpty()) {
            toast("下载地址不可用，改用浏览器打开发布页");
            openInBrowser(null);
            return;
        }
        final String name = updateApkFileName(remoteTag, url);
        try {
            final DownloadManager manager =
                    (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (manager == null) {
                throw new IllegalStateException("系统下载服务不可用");
            }
            final File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (dir == null) {
                throw new IllegalStateException("外部下载目录不可用");
            }
            // DownloadManager 的目标文件已存在时 enqueue 会直接失败：先清掉上一次的残留
            final File stale = new File(dir, name);
            if (stale.isFile() && !stale.delete()) {
                Log.w(TAG, "delete stale apk failed: " + stale);
            }
            final DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setTitle("虚拟运动 " + tagWithPrefix(remoteTag));
            request.setDescription("正在下载新版本，完成后会提示安装");
            request.setMimeType("application/vnd.android.package-archive");
            request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setAllowedOverMetered(true);
            request.setAllowedOverRoaming(true);
            request.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, name);
            updateDownloadId = manager.enqueue(request);
            updateApkUrl = url;
            updateApkName = name;
            registerUpdateReceiver();
            toast("开始下载，可在通知栏查看进度");
        } catch (Throwable t) {
            Log.w(TAG, "start update download failed", t);
            toast("下载启动失败：" + reasonText(t) + "，改用浏览器下载");
            openInBrowser(url);
        }
    }

    /** 下载文件名：优先复用 URL 里的 {@code virtual-motion-*.apk}，否则按版本号拼一个。 */
    private static String updateApkFileName(String remoteTag, String apkUrl) {
        final String version = remoteTag == null ? "" : remoteTag.trim().replaceFirst("^[vV]", "");
        final String fallback = "virtual-motion-" + (version.isEmpty() ? "latest" : version) + ".apk";
        try {
            final String segment = Uri.parse(apkUrl).getLastPathSegment();
            if (segment != null) {
                // 只取 basename：防止 URL 里的 ../ 之类路径穿越写到 Download 之外
                final String name = new File(segment).getName();
                if (name.startsWith("virtual-motion-") && name.endsWith(".apk")) {
                    return name;
                }
            }
        } catch (Throwable ignored) {
            // URL 解析失败就用拼出来的名字
        }
        return fallback;
    }

    /** 注册下载完成广播（只注册一次；Android 14 起必须显式给 exported 标记）。 */
    private void registerUpdateReceiver() {
        if (updateReceiverRegistered) {
            return;
        }
        try {
            // ACTION_DOWNLOAD_COMPLETE 由系统下载服务（独立进程/UID）发出，
            // 用 RECEIVER_EXPORTED 才能稳定收到；接收端只认本次 updateDownloadId，
            // 其它 id 的广播（含伪造的）一律忽略，真实状态以 DownloadManager.query 为准。
            ContextCompat.registerReceiver(this, updateDownloadReceiver,
                    new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                    ContextCompat.RECEIVER_EXPORTED);
            updateReceiverRegistered = true;
        } catch (Throwable t) {
            Log.w(TAG, "register download receiver failed", t);
        }
    }

    /** 注销下载完成广播（Activity 销毁时调用；重复调用安全）。 */
    private void unregisterUpdateReceiver() {
        if (!updateReceiverRegistered) {
            return;
        }
        updateReceiverRegistered = false;
        try {
            unregisterReceiver(updateDownloadReceiver);
        } catch (Throwable t) {
            Log.w(TAG, "unregister download receiver failed", t);
        }
    }

    /**
     * 下载完成：后台核对下载状态、找文件、预检签名，再回主线程拉起安装器。
     *
     * <p>状态失败 / 文件缺失 / 文件为空 → 退回浏览器下载；签名与已安装版本不一致时
     * 先弹提示（系统必然拒绝覆盖安装），由用户决定是否仍然尝试。
     */
    private void handleUpdateDownloadComplete(final long downloadId) {
        final String url = updateApkUrl;
        final File apk = pendingUpdateApkFile();
        new Thread(() -> {
            final int status = queryDownloadStatus(downloadId);
            final boolean ready = status == DownloadManager.STATUS_SUCCESSFUL
                    && apk != null && apk.isFile() && apk.length() > 0L;
            final int signature = ready ? checkApkSignature(apk) : SIGNATURE_UNKNOWN;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (!ready) {
                    updateDownloadId = -1L;
                    toast("下载失败，改用浏览器下载");
                    openInBrowser(url);
                    return;
                }
                if (signature == SIGNATURE_MISMATCH) {
                    showSignatureMismatchDialog(apk, url);
                    return;
                }
                installUpdateApk(apk, url);
            });
        }, "vloc-update-install").start();
    }

    /** 本次下载的 APK 文件（外部 {@code files/Download} 下，与 FileProvider 暴露的路径一致）。 */
    private File pendingUpdateApkFile() {
        if (updateApkName == null || updateApkName.isEmpty()) {
            return null;
        }
        try {
            final File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            return dir == null ? null : new File(dir, updateApkName);
        } catch (Throwable t) {
            Log.w(TAG, "resolve apk file failed", t);
            return null;
        }
    }

    /** 查 DownloadManager 里这次下载的状态（查不到 / 异常都按 STATUS_FAILED 处理）。 */
    private int queryDownloadStatus(long downloadId) {
        Cursor cursor = null;
        try {
            final DownloadManager manager =
                    (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (manager == null) {
                return DownloadManager.STATUS_FAILED;
            }
            cursor = manager.query(new DownloadManager.Query().setFilterById(downloadId));
            if (cursor != null && cursor.moveToFirst()) {
                final int column = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
                if (column >= 0) {
                    return cursor.getInt(column);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "query download status failed", t);
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Throwable ignored) {
                    // 关闭失败无需处理
                }
            }
        }
        return DownloadManager.STATUS_FAILED;
    }

    /**
     * 预检 APK 的签名 / 包名是否与已安装版本一致（后台线程调用）。
     *
     * <p>签名不同（例如之前用别的 debug keystore 签名装过）时系统必然拒绝覆盖安装
     * （提示「应用未安装 / 解析包时出现问题」），提前发现能给用户可操作的说明；
     * APK 读不出来就返回 {@link #SIGNATURE_UNKNOWN}，不阻挡正常安装。
     */
    @SuppressWarnings("deprecation")
    private int checkApkSignature(File apk) {
        if (apk == null || !apk.isFile()) {
            return SIGNATURE_UNKNOWN;
        }
        try {
            final PackageManager pm = getPackageManager();
            final PackageInfo archive =
                    pm.getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_SIGNATURES);
            if (archive == null || archive.signatures == null || archive.signatures.length == 0) {
                return SIGNATURE_UNKNOWN;
            }
            if (archive.packageName != null && !getPackageName().equals(archive.packageName)) {
                return SIGNATURE_MISMATCH;   // 附件不是本应用的包，装上也覆盖不了
            }
            final PackageInfo installed =
                    pm.getPackageInfo(getPackageName(), PackageManager.GET_SIGNATURES);
            if (installed == null || installed.signatures == null
                    || installed.signatures.length == 0) {
                return SIGNATURE_UNKNOWN;
            }
            final byte[] remote = archive.signatures[0].toByteArray();
            final byte[] local = installed.signatures[0].toByteArray();
            return Arrays.equals(remote, local) ? SIGNATURE_MATCH : SIGNATURE_MISMATCH;
        } catch (Throwable t) {
            Log.w(TAG, "apk signature check failed", t);
            return SIGNATURE_UNKNOWN;
        }
    }

    /** 签名不一致提示：覆盖安装必然失败，先把原因和代价（卸载会清数据）讲清楚。 */
    private void showSignatureMismatchDialog(final File apk, final String apkUrl) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        try {
            new AlertDialog.Builder(this)
                    .setTitle("签名与已安装版本不一致")
                    .setMessage("下载的 APK 与当前安装的版本签名不同，系统会拒绝覆盖安装"
                            + "（通常提示「应用未安装」或「解析包时出现问题」）。\n\n"
                            + "解决办法：先卸载当前版本，再安装新版本。"
                            + "卸载会清空本机路线库、使用记录、作用域名单等数据。\n\n"
                            + "也可以取消后用浏览器下载，自行处理。")
                    .setPositiveButton("仍然尝试安装", (d, which) -> installUpdateApk(apk, apkUrl))
                    .setNeutralButton("浏览器下载", (d, which) -> openInBrowser(apkUrl))
                    .setNegativeButton("取消", null)
                    .show();
        } catch (Throwable t) {
            Log.w(TAG, "show signature mismatch dialog failed", t);
            installUpdateApk(apk, apkUrl);
        }
    }

    /**
     * FileProvider + ACTION_VIEW 拉起系统安装器；失败再退回浏览器。
     *
     * <p>Android 8+ 必须先允许本应用「安装未知应用」，否则安装器直接拒绝，
     * 这里提前引导去系统设置（装好后再点一次「下载并安装」即可复用已下载的文件）。
     * 注意：这里传的是 {@code content://} URI + 只读临时授权，FileProvider 也只
     * 暴露 {@code files/Download} 子目录，不把整个外部目录交给安装器。
     */
    private void installUpdateApk(final File apk, final String apkUrl) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        try {
            if (apk == null || !apk.isFile() || apk.length() <= 0L) {
                toast("安装包不完整，请重新下载");
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    && !getPackageManager().canRequestPackageInstalls()) {
                toast("请先允许本应用「安装未知应用」，再回到这里重试");
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + getPackageName())));
                } catch (Throwable t) {
                    Log.w(TAG, "open unknown-sources settings failed", t);
                }
                return;
            }
            final Uri uri = FileProvider.getUriForFile(this, FILE_PROVIDER_AUTHORITY, apk);
            final Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
            // 安装器已拉起：清掉待处理下载 id，避免同一个广播被重复处理
            updateDownloadId = -1L;
            updateApkUrl = null;
        } catch (Throwable t) {
            Log.w(TAG, "install update apk failed", t);
            toast("无法拉起安装器：" + reasonText(t) + "，改用浏览器下载");
            openInBrowser(apkUrl);
        }
    }

    /** 浏览器兜底：优先打开 APK 直链，地址缺失时打开 GitHub Releases 页。 */
    private void openInBrowser(String url) {
        final String target = url == null || url.trim().isEmpty()
                ? UpdateChecker.RELEASES_URL : url.trim();
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(target)));
        } catch (Throwable t) {
            Log.w(TAG, "open browser failed", t);
            toast("无法打开浏览器，请手动访问 " + target);
        }
    }

    /** 异常 → 可展示原因（与 UpdateChecker / TleUpdater 的写法保持一致）。 */
    private static String reasonText(Throwable t) {
        if (t == null) {
            return "未知错误";
        }
        final String message = t.getMessage();
        if (message != null && !message.trim().isEmpty()) {
            return message.trim();
        }
        return t.getClass().getSimpleName();
    }

    // ------------------------------------------------------------ 工具

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, n);
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String formatDistance(double meters) {
        if (meters < 1000) {
            return String.format(Locale.US, "%.0f m", meters);
        }
        return String.format(Locale.US, "%.2f km", meters / 1000.0);
    }
}
