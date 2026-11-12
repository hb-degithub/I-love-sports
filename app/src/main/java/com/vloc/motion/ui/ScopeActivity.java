package com.vloc.motion.ui;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.vloc.motion.R;
import com.vloc.motion.core.RouteStore;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 作用域（目标应用）选择页。
 *
 * <p>开关对应 {@link RouteStore#loadScopeEnabled}：关闭＝系统级注入对所有应用生效，
 * 打开＝只有勾选的应用会被改写定位。勾选/开关都是**即时落盘**，返回即完成，没有"保存"按钮。
 *
 * <p>应用列表（PackageManager 查询 + 图标/名称加载）全部在后台线程完成，
 * 主线程只做列表填充与过滤，避免上百个应用时卡顿。
 */
public class ScopeActivity extends AppCompatActivity {

    private static final String TAG = "VLocScope";

    private SwitchMaterial switchScopeEnabled;
    private TextView textScopeHint;
    private TextView textScopeCount;
    private EditText editSearch;
    private ListView listApps;
    private View loadingView;
    private TextView emptyView;

    /** 已加载的完整列表：后台线程写完才发布到 UI 线程，之后只读。 */
    private final List<AppEntry> allApps = new ArrayList<>();
    /** 当前过滤结果，只在 UI 线程读写。 */
    private final List<AppEntry> visibleApps = new ArrayList<>();
    /** 已选包名（内存副本，每次变化立即写 prefs）。 */
    private final Set<String> selectedPackages = new HashSet<>();

    private AppAdapter adapter;
    private ExecutorService executor;
    private boolean loading = true;
    private boolean destroyed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scope);

        MaterialToolbar toolbar = findViewById(R.id.scopeToolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        switchScopeEnabled = findViewById(R.id.switchScopeEnabled);
        textScopeHint = findViewById(R.id.textScopeHint);
        textScopeCount = findViewById(R.id.textScopeCount);
        editSearch = findViewById(R.id.editScopeSearch);
        listApps = findViewById(R.id.listScopeApps);
        loadingView = findViewById(R.id.scopeLoading);
        emptyView = findViewById(R.id.textScopeEmpty);

        selectedPackages.addAll(RouteStore.loadTargetPackages(this));

        adapter = new AppAdapter();
        listApps.setAdapter(adapter);
        listApps.setOnItemClickListener((parent, view, position, id) -> toggleSelection(position));

        // 先设初值再挂监听，避免初始化触发一次无意义的写盘
        boolean scopeEnabled = RouteStore.loadScopeEnabled(this);
        switchScopeEnabled.setChecked(scopeEnabled);
        switchScopeEnabled.setOnCheckedChangeListener((v, checked) -> {
            RouteStore.saveScopeEnabled(this, checked);
            applyEnabledState(checked);
            updateSummary();
        });
        applyEnabledState(scopeEnabled);

        editSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                // 过滤只在内存里做，不重新查 PackageManager
            }

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                applyFilter(s == null ? "" : s.toString());
            }
        });

        updateSummary();
        startLoad();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        ExecutorService service = executor;
        executor = null;
        if (service != null) {
            service.shutdownNow();
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------ 后台加载

    private void startLoad() {
        loading = true;
        loadingView.setVisibility(View.VISIBLE);
        emptyView.setVisibility(View.GONE);

        ExecutorService service = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "scope-app-load");
            thread.setDaemon(true);
            return thread;
        });
        executor = service;
        service.execute(() -> {
            List<AppEntry> loaded;
            try {
                loaded = loadLaunchableApps();
            } catch (RuntimeException e) {
                Log.w(TAG, "读取可启动应用失败", e);
                loaded = new ArrayList<>();
            }
            final List<AppEntry> result = loaded;
            runOnUiThread(() -> {
                if (destroyed || isFinishing()) {
                    return;
                }
                loading = false;
                allApps.clear();
                allApps.addAll(result);
                loadingView.setVisibility(View.GONE);
                applyFilter(editSearch.getText().toString());
            });
        });
    }

    /**
     * 后台线程：查 ACTION_MAIN + CATEGORY_LAUNCHER，去重（同一包可能有多个入口），
     * 过滤自身包名，并按应用名排序。图标也在这里加载好，滑动时不再做 IPC。
     */
    private List<AppEntry> loadLaunchableApps() {
        PackageManager pm = getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN);
        launcher.addCategory(Intent.CATEGORY_LAUNCHER);

        List<ResolveInfo> resolved;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // API 33+ 的 flag 对象重载（API 30 的 int 重载只是被标记 @Deprecated，仍可用）
            resolved = pm.queryIntentActivities(launcher, PackageManager.ResolveInfoFlags.of(0));
        } else {
            // minSdk 26 ~ API 32：使用旧的 int flags 重载
            resolved = pm.queryIntentActivities(launcher, 0);
        }
        if (resolved == null) {
            return new ArrayList<>();
        }

        String self = getPackageName();
        Map<String, AppEntry> byPackage = new LinkedHashMap<>();
        for (ResolveInfo info : resolved) {
            if (info == null || info.activityInfo == null) {
                continue;
            }
            String pkg = info.activityInfo.packageName;
            if (pkg == null || pkg.isEmpty() || self.equals(pkg) || byPackage.containsKey(pkg)) {
                continue;
            }
            String label;
            try {
                CharSequence text = info.loadLabel(pm);
                label = text == null || text.length() == 0 ? pkg : text.toString();
            } catch (RuntimeException e) {
                label = pkg;
            }
            Drawable icon = null;
            try {
                icon = info.loadIcon(pm);
            } catch (RuntimeException ignored) {
                // 个别应用的图标可能损坏，用空图标占位，不影响勾选
            }
            byPackage.put(pkg, new AppEntry(pkg, label, icon));
        }

        List<AppEntry> list = new ArrayList<>(byPackage.values());
        final Collator collator = Collator.getInstance(Locale.CHINA);
        list.sort((a, b) -> collator.compare(a.label, b.label));
        return list;
    }

    // ------------------------------------------------------------ 列表 / 过滤

    private void applyFilter(String rawQuery) {
        String query = rawQuery == null ? "" : rawQuery.trim().toLowerCase(Locale.ROOT);
        visibleApps.clear();
        for (AppEntry entry : allApps) {
            if (query.isEmpty()
                    || entry.label.toLowerCase(Locale.ROOT).contains(query)
                    || entry.packageName.toLowerCase(Locale.ROOT).contains(query)) {
                visibleApps.add(entry);
            }
        }
        adapter.notifyDataSetChanged();
        if (!loading && visibleApps.isEmpty()) {
            emptyView.setText(allApps.isEmpty() ? "没有读取到可启动的应用" : "没有匹配的应用");
            emptyView.setVisibility(View.VISIBLE);
        } else {
            emptyView.setVisibility(View.GONE);
        }
        updateSummary();
    }

    /** 勾选/取消：立即写入 prefs（apply() 异步落盘，不卡 UI）。 */
    private void toggleSelection(int position) {
        if (!switchScopeEnabled.isChecked()) {
            toast("先打开「仅对选定应用生效」");
            return;
        }
        if (position < 0 || position >= visibleApps.size()) {
            return;
        }
        String pkg = visibleApps.get(position).packageName;
        if (!selectedPackages.remove(pkg)) {
            selectedPackages.add(pkg);
        }
        RouteStore.saveTargetPackages(this, selectedPackages);
        adapter.notifyDataSetChanged();
        updateSummary();
    }

    private void applyEnabledState(boolean enabled) {
        listApps.setEnabled(enabled);
        listApps.setAlpha(enabled ? 1f : 0.4f);
        editSearch.setEnabled(enabled);
        editSearch.setAlpha(enabled ? 1f : 0.4f);
        textScopeHint.setText(enabled
                ? "只改写已勾选应用的定位（需在 LSPosed 作用域内）；未勾选任何应用时不影响任何应用。"
                : "当前对所有应用生效：全机所有应用都会看到模拟定位。");
    }

    private void updateSummary() {
        int selected = 0;
        for (AppEntry entry : allApps) {
            if (selectedPackages.contains(entry.packageName)) {
                selected++;
            }
        }
        StringBuilder text = new StringBuilder("已选 ").append(selected).append(" 个应用");
        if (loading) {
            text.append(" · 读取中…");
        } else {
            text.append(" · 共 ").append(allApps.size()).append(" 个可启动应用");
        }
        textScopeCount.setText(text.toString());
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------ 数据 / 适配器

    private static final class AppEntry {
        final String packageName;
        final String label;
        final Drawable icon;

        AppEntry(String packageName, String label, Drawable icon) {
            this.packageName = packageName;
            this.label = label;
            this.icon = icon;
        }
    }

    private static final class ViewHolder {
        final ImageView icon;
        final TextView name;
        final TextView pkg;
        final CheckBox check;

        ViewHolder(View row) {
            icon = row.findViewById(R.id.scopeIcon);
            name = row.findViewById(R.id.scopeName);
            pkg = row.findViewById(R.id.scopePackage);
            check = row.findViewById(R.id.scopeCheck);
        }
    }

    private final class AppAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return visibleApps.size();
        }

        @Override
        public Object getItem(int position) {
            return visibleApps.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getViewTypeCount() {
            return 1;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView;
            ViewHolder holder;
            if (row == null) {
                row = getLayoutInflater().inflate(R.layout.item_scope_app, parent, false);
                holder = new ViewHolder(row);
                row.setTag(holder);
            } else {
                holder = (ViewHolder) row.getTag();
            }

            AppEntry entry = visibleApps.get(position);
            holder.name.setText(entry.label);
            holder.pkg.setText(entry.packageName);
            if (entry.icon != null) {
                holder.icon.setImageDrawable(entry.icon);
            } else {
                holder.icon.setImageDrawable(null);
            }
            holder.check.setChecked(selectedPackages.contains(entry.packageName));
            return row;
        }
    }
}
