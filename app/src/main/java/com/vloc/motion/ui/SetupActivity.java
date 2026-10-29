package com.vloc.motion.ui;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.view.MotionEvent;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.vloc.motion.R;
import com.vloc.motion.core.EnvSnapshot;
import com.vloc.motion.core.GeoMath;
import com.vloc.motion.core.Route;
import com.vloc.motion.core.PresetRoutes;
import com.vloc.motion.core.RouteStore;
import com.vloc.motion.core.TileSources;
import com.vloc.motion.location.EnvCollector;
import com.vloc.motion.location.RealLocationProvider;
import com.vloc.motion.location.RootShell;
import com.vloc.motion.service.EnvSession;
import com.vloc.motion.service.LocationService;
import com.vloc.motion.service.Session;

import org.osmdroid.events.MapEventsReceiver;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.MapEventsOverlay;
import org.osmdroid.views.overlay.Marker;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 开工向导：① 选目标位置 → ② 选模拟路线 → ③ 采集/配置基站与 Wi-Fi 环境 → 开始模拟。 */
public class SetupActivity extends AppCompatActivity {

    private static final int REQ_ENV_PERMISSIONS = 4001;
    private static final int REQ_LOCATION = 4002;
    /** 快照与目标位置距离超过该值时不建议回放指纹（改用隐藏）。 */
    private static final double FINGERPRINT_MATCH_RADIUS_M = 30000.0;

    private MapView mapView;
    private Marker targetMarker;
    private TextView coordText;
    private TextView routeInfo;
    private TextView envInfo;
    private SwitchMaterial switchEnv;
    private SwitchMaterial switchHide;
    private TextView rootText;
    /** null=检测中，TRUE=已授权，FALSE=未授权 */
    private Boolean rootGranted;

    private double targetLat = Double.NaN;
    private double targetLon = Double.NaN;

    private Route route = new Route();
    private String routeLabel = "";
    private EnvSnapshot envSnapshot;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setup);

        MaterialToolbar toolbar = findViewById(R.id.setupToolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        initMap();
        initViews();
        restoreRouteFromIntent();
    }

    private void initMap() {
        android.widget.FrameLayout container = findViewById(R.id.setupMapContainer);
        mapView = new MapView(this, new org.osmdroid.tileprovider.MapTileProviderBasic(
                this, TileSources.load(this)));
        container.addView(mapView, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        TileProviderTuner.relaxNetworkCheck(mapView);
        mapView.setMultiTouchControls(true);
        mapView.setUseDataConnection(true);

        double[] saved = RouteStore.loadMapView(this);
        if (saved != null) {
            mapView.getController().setZoom(saved[2]);
            mapView.getController().setCenter(mapPoint(saved[0], saved[1]));
        } else {
            mapView.getController().setZoom(16.0);
            mapView.getController().setCenter(mapPoint(39.9042, 116.4074));
        }

        // MapView 在 ScrollView 里需要抢回手势，否则拖动地图会变成滚动页面
        mapView.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.getParent().requestDisallowInterceptTouchEvent(false);
                    break;
                default:
                    break;
            }
            return false;
        });

        mapView.getOverlays().add(new MapEventsOverlay(new MapEventsReceiver() {
            @Override
            public boolean singleTapConfirmedHelper(GeoPoint p) {
                double[] wgs = wgsLatLon(p.getLatitude(), p.getLongitude());
                setTarget(wgs[0], wgs[1]);
                return true;
            }

            @Override
            public boolean longPressHelper(GeoPoint p) {
                double[] wgs = wgsLatLon(p.getLatitude(), p.getLongitude());
                setTarget(wgs[0], wgs[1]);
                return true;
            }
        }));
    }

    private void initViews() {
        coordText = findViewById(R.id.setupCoord);
        routeInfo = findViewById(R.id.setupRouteInfo);
        envInfo = findViewById(R.id.setupEnvInfo);
        switchEnv = findViewById(R.id.switchEnv);
        switchHide = findViewById(R.id.switchHide);

        rootText = findViewById(R.id.setupRoot);
        findViewById(R.id.btnCheckRoot).setOnClickListener(v -> checkRoot(true));
        checkRoot(false);

        findViewById(R.id.btnUseCenter).setOnClickListener(v -> {
            org.osmdroid.api.IGeoPoint center = mapView.getMapCenter();
            if (Math.abs(center.getLatitude()) < 1e-6 && Math.abs(center.getLongitude()) < 1e-6) {
                toast("地图尚未就绪，请稍等或用「手动坐标」输入");
                return;
            }
            double[] wgs = wgsLatLon(center.getLatitude(), center.getLongitude());
            setTarget(wgs[0], wgs[1]);
        });
        findViewById(R.id.btnUseCurrent).setOnClickListener(v -> useCurrentLocation());
        findViewById(R.id.btnManualCoord).setOnClickListener(v -> showManualCoordinateDialog());
        autoPickTileSource();
        findViewById(R.id.btnPickRoute).setOnClickListener(v -> pickRoute());
        findViewById(R.id.btnPresetRoute).setOnClickListener(v -> pickPresetRoute());
        ((MaterialButton) findViewById(R.id.btnCollectEnv)).setOnClickListener(v -> collectEnv());
        findViewById(R.id.btnStartSetup).setOnClickListener(v -> startSimulation());
    }

    private void restoreRouteFromIntent() {
        String encoded = getIntent().getStringExtra("route");
        if (encoded != null && !encoded.isEmpty()) {
            Route restored = Route.decode(encoded);
            if (restored.size() >= 2) {
                setRoute(restored, "主界面路径");
            }
        }
    }

    /** 地图瓦片源可达性自动探测，尽量让地图有图。 */
    private void autoPickTileSource() {
        new Thread(() -> {
            String currentId = TileSources.loadSelectedId(this);
            if (Diagnostics.isSourceReachable(this, currentId)) {
                return;
            }
            String pick = Diagnostics.pickReachableSource(this);
            if (pick == null) {
                runOnUiThread(() -> envInfo.setText(
                        "提示：地图瓦片当前不可达，可点「手动坐标」选点（不影响注入）"));
                return;
            }
            String current = TileSources.loadSelectedId(this);
            if (!pick.equals(current)) {
                TileSources.saveSelectedId(this, pick);
                runOnUiThread(() -> {
                    mapView.setTileSource(TileSources.byId(pick).tileSource);
                    mapView.invalidate();
                    toast("已自动切换地图源：" + TileSources.byId(pick).label);
                });
            }
        }, "vloc-setup-tile").start();
    }

    /** WGS-84 → 地图坐标（国内底图 GCJ-02）。 */
    private GeoPoint mapPoint(double lat, double lon) {
        double[] converted = TileSources.toMapLatLon(this, lat, lon);
        return new GeoPoint(converted[0], converted[1]);
    }

    /** 地图坐标 → WGS-84。 */
    private double[] wgsLatLon(double lat, double lon) {
        return TileSources.toWgsLatLon(this, lat, lon);
    }

    // ------------------------------------------------------------ Root

    private void checkRoot(boolean userInitiated) {
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
            runOnUiThread(() -> {
                rootGranted = rooted;
                rootText.setTextColor(rooted ? 0xFF00C853 : 0xFFE53935);
                rootText.setText(rooted
                        ? "Root：已授权 ✓"
                        : "Root：未获得授权 ✗（点上方按钮重新检测/申请）");
                if (userInitiated) {
                    toast(rooted ? "Root 已授权" : "未获得 root：" + result.combined());
                }
            });
        }, "vloc-setup-root").start();
    }

    private void showRootRequiredDialog() {
        new AlertDialog.Builder(this)
                .setTitle("未获得 Root 权限")
                .setMessage("注入定位需要 root 授权（Magisk / KernelSU / APatch）。\n"
                        + "请确认设备已 root，并在授权弹窗中允许本应用。")
                .setPositiveButton("重新申请", (dialog, which) -> checkRoot(true))
                .setNegativeButton("取消", null)
                .show();
    }

    private void showManualCoordinateDialog() {
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
                .setTitle("手动输入目标坐标")
                .setView(container)
                .setPositiveButton("确定", (dialog, which) -> {
                    try {
                        double lat = Double.parseDouble(latInput.getText().toString().trim());
                        double lon = Double.parseDouble(lonInput.getText().toString().trim());
                        if (!GeoMath.isValid(lat, lon)) {
                            toast("坐标不合法");
                            return;
                        }
                        setTarget(lat, lon);
                        mapView.getController().animateTo(mapPoint(lat, lon));
                    } catch (NumberFormatException e) {
                        toast("请输入合法数字");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ------------------------------------------------------------ ① 位置

    private void setTarget(double lat, double lon) {
        targetLat = lat;
        targetLon = lon;
        if (targetMarker == null) {
            targetMarker = new Marker(mapView);
            targetMarker.setIcon(ContextCompat.getDrawable(this, R.drawable.ic_crosshair));
            targetMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
            targetMarker.setEnabled(false);
            mapView.getOverlays().add(targetMarker);
        }
        targetMarker.setPosition(mapPoint(lat, lon));
        coordText.setText(String.format(Locale.US, "目标位置：%.6f, %.6f", lat, lon));
        mapView.invalidate();
    }

    private void useCurrentLocation() {
        if (!RealLocationProvider.hasPermission(this)) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
            return;
        }
        toast("正在获取当前位置…");
        RealLocationProvider.request(this, new RealLocationProvider.Callback() {
            @Override
            public void onLocation(android.location.Location location) {
                setTarget(location.getLatitude(), location.getLongitude());
                mapView.getController().animateTo(
                        mapPoint(location.getLatitude(), location.getLongitude()));
                toast("已使用当前位置");
            }

            @Override
            public void onError(String message) {
                toast(message);
            }
        });
    }

    // ------------------------------------------------------------ ② 路线

    private void pickRoute() {
        List<String> labels = new ArrayList<>();
        List<Route> routes = new ArrayList<>();
        if (route.size() >= 2) {
            labels.add("当前已选：" + describeRoute(route));
            routes.add(route);
        }
        for (RouteStore.NamedRoute named : RouteStore.loadRouteLibrary(this)) {
            labels.add(named.name + "（" + describeRoute(named.route) + "）");
            routes.add(named.route);
        }
        if (routes.isEmpty()) {
            toast("路线库为空：请先在主界面绘制并「保存当前路线」");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("选择模拟路线")
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    String label = labels.get(which);
                    int bracket = label.indexOf('（');
                    setRoute(routes.get(which), bracket > 0 ? label.substring(0, bracket) : label);
                })
                .show();
    }

    private void pickPresetRoute() {
        double[] centerWgs = wgsLatLon(mapView.getMapCenter().getLatitude(),
                mapView.getMapCenter().getLongitude());
        double lat = !Double.isNaN(targetLat) ? targetLat : centerWgs[0];
        double lon = !Double.isNaN(targetLon) ? targetLon : centerWgs[1];
        if (Math.abs(lat) < 1e-6 && Math.abs(lon) < 1e-6) {
            toast("请先在地图上选择目标位置");
            return;
        }
        List<PresetRoutes.Preset> presets = PresetRoutes.all();
        String[] labels = new String[presets.size()];
        for (int i = 0; i < presets.size(); i++) {
            labels[i] = presets.get(i).name + "（约 "
                    + Math.round(presets.get(i).nominalMeters) + " m）";
        }
        new AlertDialog.Builder(this)
                .setTitle("预设路线（以" + (Double.isNaN(targetLat) ? "地图中心" : "目标位置") + "为基准）")
                .setItems(labels, (dialog, which) -> {
                    PresetRoutes.Preset preset = presets.get(which);
                    Route generated = PresetRoutes.create(preset.id, lat, lon);
                    setRoute(generated, "预设：" + preset.name);
                    if (RouteStore.loadGoalEnabled(this)) {
                        RouteStore.saveGoalLapMeters(this, (float) Math.round(generated.totalMeters()));
                    }
                    mapView.getController().animateTo(
                            mapPoint(generated.get(0).lat, generated.get(0).lon));
                    toast("已生成：" + preset.name + " · "
                            + Math.round(generated.totalMeters()) + " m");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void setRoute(Route selected, String label) {
        this.route = selected.copy();
        this.routeLabel = label;
        routeInfo.setText(String.format(Locale.US, "已选：%s · %d 个点 · 总里程 %s",
                label, route.size(), formatDistance(route.totalMeters())));
    }

    private static String describeRoute(Route r) {
        return r.size() + " 点 / " + formatDistance(r.totalMeters());
    }

    // ------------------------------------------------------------ ③ 环境

    private void collectEnv() {
        List<String> missing = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.READ_PHONE_STATE);
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), REQ_ENV_PERMISSIONS);
            return;
        }
        envInfo.setText("正在采集周围基站与 Wi-Fi…");
        final double lat = !Double.isNaN(targetLat) ? targetLat : mapView.getMapCenter().getLatitude();
        final double lon = !Double.isNaN(targetLon) ? targetLon : mapView.getMapCenter().getLongitude();
        new Thread(() -> {
            EnvSnapshot snapshot = EnvCollector.collect(this, lat, lon);
            runOnUiThread(() -> showEnvSnapshot(snapshot));
        }, "vloc-env-collect").start();
    }

    private void showEnvSnapshot(EnvSnapshot snapshot) {
        envSnapshot = snapshot;
        StringBuilder sb = new StringBuilder();
        sb.append("基站 ").append(snapshot.cellCount()).append(" 个 · Wi-Fi ")
                .append(snapshot.wifiCount()).append(" 个");
        if (!snapshot.cells.isEmpty()) {
            EnvSnapshot.Cell serving = snapshot.cells.get(0);
            sb.append(String.format(Locale.US, "\n主小区：%s %03d-%02d lac=%d cid=%d",
                    serving.type.toUpperCase(Locale.US), serving.mcc, serving.mnc,
                    serving.lac, serving.cid));
        }
        if (snapshot.cellCount() == 0 && snapshot.wifiCount() == 0) {
            sb.append("\n未采集到数据：请确认已插卡、开启定位，或改用「隐藏基站信息」");
        }
        envInfo.setText(sb.toString());
        switchEnv.setChecked(true);
    }

    // ------------------------------------------------------------ 开始

    private void startSimulation() {
        if (Boolean.FALSE.equals(rootGranted)) {
            showRootRequiredDialog();
            return;
        }
        boolean hasRoute = route.size() >= 2;
        boolean hasTarget = !Double.isNaN(targetLat) && !Double.isNaN(targetLon);
        if (!hasRoute && !hasTarget) {
            toast("请先在地图上选目标位置，或选择一条路线");
            return;
        }

        configureEnvSession(hasRoute, hasTarget);

        RouteStore.saveRoute(this, route);
        Session.INSTANCE.route = route.copy();
        Session.INSTANCE.loop = RouteStore.loadLoop(this);

        if (hasRoute) {
            Intent intent = new Intent(this, LocationService.class)
                    .setAction(LocationService.ACTION_START_ROUTE)
                    .putExtra(LocationService.EXTRA_SPEED_KMH,
                            (double) RouteStore.loadSpeedKmh(this))
                    .putExtra(LocationService.EXTRA_LOOP, RouteStore.loadLoop(this))
                    .putExtra(LocationService.EXTRA_SPEED_VARIANCE, RouteStore.loadSpeedVariance(this))
                    .putExtra(LocationService.EXTRA_GOAL_ENABLED, RouteStore.loadGoalEnabled(this))
                    .putExtra(LocationService.EXTRA_GOAL_LAP_M, RouteStore.loadGoalLapMeters(this))
                    .putExtra(LocationService.EXTRA_GOAL_DISTANCE_M, RouteStore.loadGoalDistanceMeters(this))
                    .putExtra(LocationService.EXTRA_GOAL_TIME_S, RouteStore.loadGoalTimeSeconds(this))
                    .putExtra(LocationService.EXTRA_GOAL_PACE_S, RouteStore.loadGoalPaceSeconds(this))
                    .putExtra(LocationService.EXTRA_GOAL_MAX_KMH, RouteStore.loadGoalMaxKmh(this))
                    .putExtra(LocationService.EXTRA_ACCURACY, RouteStore.loadAccuracy(this))
                    .putStringArrayListExtra(LocationService.EXTRA_PROVIDERS,
                            new ArrayList<>(RouteStore.loadProviders(this)));
            ContextCompat.startForegroundService(this, intent);
        } else {
            Intent intent = new Intent(this, LocationService.class)
                    .setAction(LocationService.ACTION_START_SPOT)
                    .putExtra(LocationService.EXTRA_LAT, targetLat)
                    .putExtra(LocationService.EXTRA_LON, targetLon)
                    .putExtra(LocationService.EXTRA_ACCURACY, RouteStore.loadAccuracy(this))
                    .putStringArrayListExtra(LocationService.EXTRA_PROVIDERS,
                            new ArrayList<>(RouteStore.loadProviders(this)));
            ContextCompat.startForegroundService(this, intent);
        }
        finish();
    }

    private void configureEnvSession(boolean hasRoute, boolean hasTarget) {
        if (!switchEnv.isChecked()) {
            EnvSession.INSTANCE.clear();
            return;
        }
        double refLat = hasRoute ? route.get(0).lat : targetLat;
        double refLon = hasRoute ? route.get(0).lon : targetLon;
        double distance = envSnapshot == null ? Double.MAX_VALUE
                : GeoMath.distanceMeters(envSnapshot.lat, envSnapshot.lon, refLat, refLon);

        if (envSnapshot != null && distance <= FINGERPRINT_MATCH_RADIUS_M) {
            EnvSession.INSTANCE.set(EnvSession.Mode.REPLAY, envSnapshot);
            toast("环境指纹回放已启用");
        } else if (switchHide.isChecked() || envSnapshot == null) {
            EnvSession.INSTANCE.set(EnvSession.Mode.HIDE, null);
            toast("已启用基站信息隐藏" + (envSnapshot == null ? "（未采集指纹）" : "（指纹与目标位置距离过远）"));
        } else {
            EnvSession.INSTANCE.set(EnvSession.Mode.REPLAY, envSnapshot);
            toast("环境指纹回放已启用（目标位置较远，与真实环境可能不一致）");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOCATION) {
            if (RealLocationProvider.hasPermission(this)) {
                useCurrentLocation();
            } else {
                toast("未授予定位权限");
            }
            return;
        }
        if (requestCode == REQ_ENV_PERMISSIONS) {
            boolean fine = ContextCompat.checkSelfPermission(this,
                    Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            if (fine) {
                collectEnv();
            } else {
                toast("需要定位权限才能采集基站/Wi-Fi");
            }
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private static String formatDistance(double meters) {
        if (meters < 1000) {
            return String.format(Locale.US, "%.0f m", meters);
        }
        return String.format(Locale.US, "%.2f km", meters / 1000.0);
    }
}
