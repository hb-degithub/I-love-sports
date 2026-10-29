package com.vloc.motion.ui;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.graphics.Paint;
import android.os.Build;
import android.os.Bundle;
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

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.vloc.motion.R;
import com.vloc.motion.core.GeoMath;
import com.vloc.motion.core.GoalPlanner;
import com.vloc.motion.core.GpxCodec;
import com.vloc.motion.core.PacePlan;
import com.vloc.motion.core.PresetRoutes;
import com.vloc.motion.core.TileSources;
import com.vloc.motion.core.CameraPreset;
import com.vloc.motion.core.Route;
import com.vloc.motion.core.RouteStore;
import com.vloc.motion.location.RealLocationProvider;
import com.vloc.motion.location.ModuleCheck;
import com.vloc.motion.location.RootShell;
import com.vloc.motion.location.TestProviderInjector;
import com.vloc.motion.service.EnvSession;
import com.vloc.motion.service.StepSession;
import com.vloc.motion.service.CameraOverlayService;
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
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 主界面：地图选点、路径编辑、播放控制。 */
public class MainActivity extends AppCompatActivity implements Session.Listener {

    private static final int REQ_PERMISSIONS = 2001;

    private MapView mapView;
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
    private ActivityResultLauncher<String[]> cameraPicker;

    // ---- 虚拟摄像头页签 ----
    private TextView tabMotion;
    private TextView tabCamera;
    private View motionPage;
    private View cameraPage;
    private SwitchMaterial cameraSwitch;
    private VideoView cameraPreview;
    private TextView cameraCurrent;
    private TextView cameraStatusText;
    private TextView cameraEmpty;
    private LinearLayout cameraList;
    private Button cameraPlayPause;
    private SwitchMaterial cameraImuSwitch;
    private int currentTab;
    private final android.os.Handler cameraStatusHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());

    private String lastErrorShown;
    /** null=检测中，TRUE=已授权，FALSE=未授权 */
    private Boolean rootGranted;

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
    private boolean pendingCameraOverlay;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        initMap();
        initViews();
        initCameraPage();
        // 内置预置视频（assets/camera_presets）：首次启动复制进视频列表
        com.vloc.motion.service.PresetVideos.sync(this);
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
    }

    @Override
    protected void onResume() {
        super.onResume();
        Session.INSTANCE.addListener(this);
        refreshUi();
        if (currentTab == 1) {
            updateCameraPage();
            cameraStatusHandler.removeCallbacks(cameraStatusTicker);
            cameraStatusHandler.post(cameraStatusTicker);
        }
        updateCurrentMarker();
        if (pendingOverlayEnable && Settings.canDrawOverlays(this)) {
            pendingOverlayEnable = false;
            RouteStore.saveOverlayEnabled(this, true);
            syncOverlayToService(true);
            invalidateOptionsMenu();
            toast("悬浮窗已开启");
        }
        if (pendingCameraOverlay) {
            if (Settings.canDrawOverlays(this)) {
                pendingCameraOverlay = false;
                RouteStore.saveCameraOverlayHidden(this, false);
                syncCameraOverlay(true);
                toast("虚拟摄像头悬浮窗已开启");
            }
        }
        // 注意：不再在回到前台时自动拉起摄像头悬浮窗 —— 只有用户主动点「播放」/
        // 打开开关时才显示，避免"一打开应用就弹一个悬浮窗"
    }

    @Override
    protected void onPause() {
        super.onPause();
        cameraStatusHandler.removeCallbacks(cameraStatusTicker);
        stopCameraPreview();
        Session.INSTANCE.removeListener(this);
        saveMapView();
    }

    // ------------------------------------------------------------ 初始化

    private void initMap() {
        android.widget.FrameLayout container = findViewById(R.id.mapContainer);
        mapView = new MapView(this, new org.osmdroid.tileprovider.MapTileProviderBasic(
                this, TileSources.load(this)));
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
        cameraPicker = registerForActivityResult(
                new ActivityResultContracts.OpenMultipleDocuments(), uris -> {
                    if (uris == null || uris.isEmpty()) {
                        return;
                    }
                    addCameraClips(uris);
                });
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
        if (session.mode != Session.Mode.IDLE) {
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
        String status = String.format(Locale.US, "%s · %d 个路径点%s%s%s",
                state, route.size(), coords, envTag, stepTag);
        statusText.setText(status);
        updateInfoTexts();

        // 目标配速模式下速度完全由配速决定：隐藏手动速度/循环控件，避免"多个速度以哪个为准"
        boolean goalConfigured = RouteStore.loadGoalEnabled(this);
        boolean hideManualSpeed = goalConfigured || goalMode;
        View speedRow = findViewById(R.id.speedRow);
        if (speedRow != null) {
            speedRow.setVisibility(hideManualSpeed ? View.GONE : View.VISIBLE);
        }
        loopSwitch.setVisibility(hideManualSpeed ? View.GONE : View.VISIBLE);
        speedBar.setEnabled(!hideManualSpeed);
        loopSwitch.setEnabled(!hideManualSpeed && session.mode == Session.Mode.IDLE);
        if (!hideManualSpeed && session.mode == Session.Mode.IDLE) {
            speedValue.setText(getString(R.string.speed_value,
                    speedBar.getProgress() + 1));
        }
        if (session.mode == Session.Mode.ROUTE) {
            if (session.held && session.traveledMeters <= 0.5) {
                btnStartStop.setText("开始移动");
            } else if (session.arrived) {
                btnStartStop.setText(R.string.action_restart);
            } else {
                btnStartStop.setText(session.paused ? R.string.action_resume : R.string.action_pause);
            }
            btnStartStop.setEnabled(true);
        } else if (session.mode == Session.Mode.SPOT) {
            btnStartStop.setText(R.string.action_spot);
            btnStartStop.setEnabled(false);
        } else {
            btnStartStop.setText(R.string.action_start);
            btnStartStop.setEnabled(true);
        }
        btnStop.setEnabled(session.mode != Session.Mode.IDLE);
        btnStop.setText(session.mode == Session.Mode.IDLE ? "停止" : "彻底停止");
        if (hintText != null) {
            if (session.arrived) {
                hintText.setText(RouteStore.loadAutoStopOnFinish(this)
                        ? "已到达目标，正在自动停止并恢复真实定位"
                        : "已到达终点，定位保持中 · 点「彻底停止」恢复真实定位");
            } else if (session.held && session.traveledMeters <= 0.5) {
                hintText.setText("已定位到起点（定位保持中）· 在悬浮窗点「开始」开始路线模拟");
            } else if (session.held) {
                hintText.setText("已停止移动，定位保持在原地 · 只有这里「彻底停止」才会恢复真实定位");
            } else if (session.mode != Session.Mode.IDLE
                    && session.sessionSteps > 0
                    && StepSession.INSTANCE.moduleLastReadAt <= 0
                    && StepSession.INSTANCE.moduleHeartbeatAt > 0) {
                hintText.setText("模块已注入目标 App，但它没有读取计步传感器（可能走系统健康数据，无法伪造）");
            } else if (session.mode != Session.Mode.IDLE
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

    private void showModuleStatusDialog() {
        String manager = ModuleCheck.detectManager(this);
        long read = StepSession.INSTANCE.moduleLastReadAt;
        long beat = StepSession.INSTANCE.moduleHeartbeatAt;
        StringBuilder sb = new StringBuilder();
        sb.append("LSPosed 管理器：").append(manager == null ? "未检测到（需先安装 LSPosed）" : manager).append('\n');
        sb.append("模块注入：").append(beat > 0 ? "已注入目标 App" : "尚未注入").append('\n');
        sb.append("数据读取：").append(read > 0 ? "目标 App 正在读取步数" : "目标 App 未读取").append('\n');
        sb.append("\n启用步骤：\n");
        sb.append("1. 安装并激活 LSPosed（Magisk/KernelSU 的 Zygisk 模块）\n");
        sb.append("2. 打开 LSPosed 管理器 → 模块 → 勾选「虚拟运动」\n");
        sb.append("3. 进入「虚拟运动」的作用域，勾选目标 App（多进程 App 要逐个勾选）\n");
        sb.append("4. 强制停止目标 App，再重新打开\n");
        sb.append("5. 回到本应用开始模拟，悬浮窗第二行应显示「模块读取中」");
        new AlertDialog.Builder(this)
                .setTitle("模块状态")
                .setMessage(sb.toString())
                .setPositiveButton("知道了", null)
                .show();
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
        saveRoute();
        refreshUi();
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
        SwitchMaterial variance = content.findViewById(R.id.switchSpeedVariance);
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
        variance.setChecked(RouteStore.loadSpeedVariance(this));

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
                                    enabled.isChecked(), variance.isChecked());
                            dialog.dismiss();
                        })
                        .setNegativeButton("返回修改", null)
                        .show();
                return;
            }
            saveGoalSettings(lap, distance, pace, maxKmh,
                    enabled.isChecked(), variance.isChecked());
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
        } else if (id == R.id.action_camera) {
            selectTab(1);
            return true;
        } else if (id == R.id.action_settings) {
            showSettingsDialog();
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
                    saveRoute();
                    refreshUi();
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

    // ---------------------------------------------------------------- 虚拟摄像头

    /** 把选中的相册视频加入虚拟摄像头列表（SAF 持久化授权，模块通过 provider 读取 FD）。 */
    private void addCameraClips(java.util.List<Uri> uris) {
        java.util.List<com.vloc.motion.core.CameraClips.Clip> clips =
                RouteStore.loadCameraClips(this);
        int added = 0;
        for (Uri uri : uris) {
            try {
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
                // 个别来源不支持持久化授权，仍尝试使用（重启后可能失效）
            }
            String name = queryClipName(uri);
            long durationMs = com.vloc.motion.service.PresetVideos.durationMs(this, uri);
            String displayName = name;
            if (durationMs > 0) {
                displayName = name + "（" + CameraPreset.formatDuration(durationMs) + "）";
                if (!CameraPreset.durationOk(durationMs)) {
                    toast("「" + name + "」时长 " + CameraPreset.formatDuration(durationMs)
                            + "，不在要求的 60–120 秒内");
                }
            }
            clips.add(new com.vloc.motion.core.CameraClips.Clip(
                    displayName, uri.toString(), System.currentTimeMillis()));
            added++;
        }
        RouteStore.saveCameraClips(this, clips);
        if (clips.size() == added) {
            RouteStore.saveCameraIndex(this, 0);
        }
        toast("已添加 " + added + " 个视频（共 " + clips.size() + " 个）");
        updateCameraPage();
    }

    private String queryClipName(Uri uri) {
        try (android.database.Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.isEmpty()) {
                    return name;
                }
            }
        } catch (Exception ignored) {
            // 查询失败用默认名
        }
        return "视频";
    }

    /** 虚拟摄像头管理：开关、视频列表（选当前 / 删除）、添加视频。 */
    // ---------------------------------------------------------------- 虚拟摄像头页签

    private void initCameraPage() {
        tabMotion = findViewById(R.id.tabMotion);
        tabCamera = findViewById(R.id.tabCamera);
        motionPage = findViewById(R.id.motionPage);
        cameraPage = findViewById(R.id.cameraPage);
        cameraSwitch = findViewById(R.id.switchCamera);
        cameraPreview = findViewById(R.id.videoPreview);
        cameraCurrent = findViewById(R.id.textCurrent);
        cameraStatusText = findViewById(R.id.textModuleStatus);
        cameraEmpty = findViewById(R.id.textEmpty);
        cameraList = findViewById(R.id.listClips);
        cameraPlayPause = findViewById(R.id.btnPlayPause);
        cameraImuSwitch = findViewById(R.id.switchCameraImu);

        tabMotion.setOnClickListener(v -> selectTab(0));
        tabCamera.setOnClickListener(v -> selectTab(1));
        cameraSwitch.setOnCheckedChangeListener((buttonView, checked) -> {
            RouteStore.saveCameraEnabled(this, checked);
            if (checked) {
                // 用户重新打开开关：清掉上次 ✕ 收起的标记，让悬浮窗能回来
                RouteStore.saveCameraOverlayHidden(this, false);
            }
            syncCameraOverlay(checked);
            updateCameraPage();
        });
        cameraPlayPause.setOnClickListener(v -> {
            boolean playing = !RouteStore.loadCameraPlaying(this);
            RouteStore.saveCameraPlaying(this, playing);
            // 点「播放」时把摄像头悬浮窗拉起来（定位会话在跑时由定位悬浮窗统一显示）
            if (playing && RouteStore.loadCameraEnabled(this)) {
                // 主动点播放：即使上次 ✕ 收起过，也把摄像头悬浮窗拉起来
                RouteStore.saveCameraOverlayHidden(this, false);
                syncCameraOverlay(true);
            }
            updateCameraPage();
        });
        findViewById(R.id.btnPrev).setOnClickListener(v -> stepCameraClip(-1));
        findViewById(R.id.btnNext).setOnClickListener(v -> stepCameraClip(1));
        findViewById(R.id.btnAdd).setOnClickListener(
                v -> cameraPicker.launch(new String[]{"video/*"}));
        findViewById(R.id.btnDelete).setOnClickListener(v -> deleteCameraClip());
        // VideoView 播不了时不要弹系统对话框
        cameraImuSwitch.setOnCheckedChangeListener((buttonView, checked) -> {
            RouteStore.saveCameraImuUpright(this, checked);
            toast(checked ? "接管期间 IMU：竖直静止" : "接管期间 IMU：跟随运动模拟");
        });
        cameraPreview.setOnErrorListener((mp, what, extra) -> true);
        selectTab(0);
    }

    /** 切页签：0 = 虚拟运动，1 = 虚拟摄像头。 */
    private void selectTab(int tab) {
        currentTab = tab;
        boolean motion = tab == 0;
        motionPage.setVisibility(motion ? View.VISIBLE : View.GONE);
        cameraPage.setVisibility(motion ? View.GONE : View.VISIBLE);
        int brand = androidx.core.content.ContextCompat.getColor(this, R.color.brand);
        int secondary = androidx.core.content.ContextCompat.getColor(this, R.color.text_secondary);
        tabMotion.setBackgroundColor(motion ? brand : 0x00000000);
        tabCamera.setBackgroundColor(motion ? 0x00000000 : brand);
        tabMotion.setTextColor(motion ? 0xFFFFFFFF : secondary);
        tabCamera.setTextColor(motion ? secondary : 0xFFFFFFFF);
        if (!motion) {
            updateCameraPage();
            cameraStatusHandler.removeCallbacks(cameraStatusTicker);
            cameraStatusHandler.post(cameraStatusTicker);
        } else {
            cameraStatusHandler.removeCallbacks(cameraStatusTicker);
            stopCameraPreview();
        }
    }

    private void stepCameraClip(int delta) {
        java.util.List<com.vloc.motion.core.CameraClips.Clip> clips =
                RouteStore.loadCameraClips(this);
        if (clips.isEmpty()) {
            return;
        }
        int index = com.vloc.motion.core.CameraClips.clampIndex(clips,
                RouteStore.loadCameraIndex(this) + delta);
        RouteStore.saveCameraIndex(this, index);
        updateCameraPage();
    }

    private void deleteCameraClip() {
        java.util.List<com.vloc.motion.core.CameraClips.Clip> clips =
                RouteStore.loadCameraClips(this);
        int index = RouteStore.loadCameraIndex(this);
        if (!com.vloc.motion.core.CameraClips.isValidIndex(clips, index)) {
            return;
        }
        clips.remove(index);
        RouteStore.saveCameraClips(this, clips);
        RouteStore.saveCameraIndex(this, 0);
        updateCameraPage();
    }

    /** 刷新摄像头页：开关、当前视频、列表、预览。 */
    private void updateCameraPage() {
        java.util.List<com.vloc.motion.core.CameraClips.Clip> clips =
                RouteStore.loadCameraClips(this);
        int index = RouteStore.loadCameraIndex(this);
        boolean enabled = RouteStore.loadCameraEnabled(this);
        boolean playing = RouteStore.loadCameraPlaying(this);

        cameraSwitch.setChecked(enabled);
        cameraImuSwitch.setChecked(RouteStore.loadCameraImuUpright(this));
        cameraPlayPause.setText(playing ? "暂停" : "播放");
        com.vloc.motion.core.CameraClips.Clip current =
                com.vloc.motion.core.CameraClips.current(clips, index);
        cameraCurrent.setText(current == null ? "未选择视频"
                : String.format(Locale.US, "当前：%d/%d · %s", index + 1, clips.size(), current.name));
        cameraEmpty.setVisibility(clips.isEmpty() ? View.VISIBLE : View.GONE);

        cameraList.removeAllViews();
        for (int i = 0; i < clips.size(); i++) {
            final int position = i;
            com.vloc.motion.core.CameraClips.Clip clip = clips.get(i);
            TextView row = new TextView(this);
            row.setText(String.format(Locale.US, "%s %d. %s",
                    i == index ? "▶" : "　", i + 1, clip.name));
            row.setTextSize(14f);
            row.setPadding(0, 14, 0, 14);
            row.setOnClickListener(v -> {
                RouteStore.saveCameraIndex(this, position);
                updateCameraPage();
            });
            cameraList.addView(row);
        }

        if (current != null && enabled && playing) {
            startCameraPreview(current);
        } else {
            stopCameraPreview();
        }
    }

    private void startCameraPreview(com.vloc.motion.core.CameraClips.Clip clip) {
        try {
            Uri uri = Uri.parse(clip.uri);
            if (!uri.equals(cameraPreview.getTag(R.id.videoPreview))) {
                cameraPreview.setTag(R.id.videoPreview, uri);
                cameraPreview.setVideoURI(uri);
            }
            if (!cameraPreview.isPlaying()) {
                cameraPreview.start();
            }
        } catch (Exception ignored) {
            // 预览失败不影响配置下发
        }
    }

    private void stopCameraPreview() {
        try {
            if (cameraPreview != null && cameraPreview.isPlaying()) {
                cameraPreview.pause();
            }
        } catch (Exception ignored) {
            // ignore
        }
    }

    /** 模块上报的接管状态（1.5s 轮询）。 */
    private final Runnable cameraStatusTicker = new Runnable() {
        @Override
        public void run() {
            refreshCameraStatus();
            cameraStatusHandler.postDelayed(this, 1500L);
        }
    };

    private void refreshCameraStatus() {
        String text;
        try (android.database.Cursor cursor = getContentResolver().query(
                Uri.parse("content://com.vloc.motion.env/camera_status"),
                new String[]{"json"}, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) {
                text = "模块状态：未上报\n（目标 App 还没打开相机，或模块未注入 / 不在 LSPosed 作用域）";
            } else {
                int column = cursor.getColumnIndex("json");
                if (column < 0) {
                    column = 1;
                }
                org.json.JSONObject json = new org.json.JSONObject(cursor.getString(column));
                long reportAt = json.optLong("reportAt", 0L);
                if (reportAt == 0L) {
                    text = "模块状态：未上报\n（把目标 App 勾进 LSPosed 作用域 → 强制停止 → 重新打开相机）";
                } else {
                    long now = System.currentTimeMillis();
                    String pkg = json.optString("pkg", "");
                    int hooks = json.optInt("hooks", 0);
                    long sessionAt = json.optLong("sessionAt", 0L);
                    long renderAt = json.optLong("renderAt", 0L);
                    String error = json.optString("error", "");
                    StringBuilder sb = new StringBuilder();
                    sb.append("模块已注入：").append(pkg.isEmpty() ? "目标 App" : pkg)
                            .append("（camera hooks=").append(hooks).append("/9）\n");
                    sb.append("相机会话接管：")
                            .append(sessionAt == 0 ? "还没发生" : ago(sessionAt, now) + "前").append("\n");
                    sb.append("视频渲染：")
                            .append(renderAt == 0 ? "还没开始" : ago(renderAt, now) + "前");
                    if (!error.isEmpty()) {
                        sb.append("\n最近错误：").append(error);
                    }
                    if (sessionAt == 0 && now - reportAt > 10_000L) {
                        sb.append("\n提示：模块在跑但没拦到相机会话——目标 App 可能走 Camera1/NDK，或相机在别的进程");
                    }
                    text = sb.toString();
                }
            }
        } catch (Exception e) {
            text = "模块状态：查询失败（" + e.getClass().getSimpleName() + "）";
        }
        cameraStatusText.setText(text);
    }

    private static String ago(long at, long now) {
        long seconds = Math.max(0L, (now - at) / 1000L);
        if (seconds < 60) {
            return seconds + "s";
        }
        return (seconds / 60) + "m" + (seconds % 60) + "s";
    }


    private void showSettingsDialog() {
        View content = getLayoutInflater().inflate(R.layout.dialog_settings, null, false);
        EditText editAltitude = content.findViewById(R.id.editAltitude);
        CheckBox cbGps = content.findViewById(R.id.cbGps);
        CheckBox cbNetwork = content.findViewById(R.id.cbNetwork);
        CheckBox cbFused = content.findViewById(R.id.cbFused);
        SwitchMaterial switchRealism = content.findViewById(R.id.switchRealism);
        SwitchMaterial switchAttitude = content.findViewById(R.id.switchAttitude);
        SwitchMaterial switchRandomStart = content.findViewById(R.id.switchRandomStart);
        SwitchMaterial switchAutoStop = content.findViewById(R.id.switchAutoStop);

        editAltitude.setText(String.format(Locale.US, "%.0f", RouteStore.loadAltitude(this)));
        List<String> providers = RouteStore.loadProviders(this);
        cbGps.setChecked(providers.contains("gps"));
        cbNetwork.setChecked(providers.contains("network"));
        cbFused.setChecked(providers.contains("fused"));
        switchRealism.setChecked(RouteStore.loadRealism(this));
        switchAttitude.setChecked(RouteStore.loadAttitudeReplay(this));
        switchRandomStart.setChecked(RouteStore.loadRandomStart(this));
        switchAutoStop.setChecked(RouteStore.loadAutoStopOnFinish(this));

        new AlertDialog.Builder(this)
                .setTitle("设置")
                .setView(content)
                .setPositiveButton("保存", (dialog, which) -> {
                    float altitude = RouteStore.loadAltitude(this);
                    try {
                        altitude = Float.parseFloat(editAltitude.getText().toString().trim());
                    } catch (NumberFormatException ignored) {
                        // 保持原值
                    }
                    altitude = Math.max(-500f, Math.min(9000f, altitude));
                    RouteStore.saveAltitude(this, altitude);

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
                    RouteStore.saveAttitudeReplay(this, switchAttitude.isChecked());
                    RouteStore.saveRandomStart(this, switchRandomStart.isChecked());
                    RouteStore.saveAutoStopOnFinish(this, switchAutoStop.isChecked());
                    toast("设置已保存（Provider / 拟真模式下次开始时生效）");
                })
                .setNegativeButton("取消", null)
                .show();
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

    /** 虚拟摄像头悬浮窗与开关状态联动（定位会话运行期间由定位悬浮窗统一显示，不重复）。 */
    private void syncCameraOverlay(boolean enable) {
        if (!enable) {
            CameraOverlayService.stop(this);
            return;
        }
        if (Session.INSTANCE.mode != Session.Mode.IDLE) {
            // 定位会话在跑：定位悬浮窗已经显示相机状态，避免两个胶囊
            CameraOverlayService.stop(this);
            return;
        }
        if (RouteStore.loadCameraOverlayHidden(this)) {
            // 用户手动 ✕ 收起了，不主动弹回（下次启用会重置）
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            if (!pendingCameraOverlay) {
                pendingCameraOverlay = true;
                showOverlayPermissionDialog();
            }
            return;
        }
        CameraOverlayService.start(this);
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
                        + "\n⚠️ 仅供学习与技术研究，严禁用于打卡作弊、位置欺诈、绕过风控等违规违法用途。"
                        + "使用违反多数应用的用户协议，可能导致账号封禁；一切后果自负。完整条款见仓库 DISCLAIMER.md。\n\n"
                        + "注意：测试 Provider 写入的定位在系统层会被标记为模拟位置，"
                        + "部分应用会检测并拒绝。安装 hidden 隐藏模块并在 LSPosed 中启用，可清除该标记。")
                .setPositiveButton("知道了", null)
                .show();
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
