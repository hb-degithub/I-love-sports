package com.vloc.motion.ui;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.vloc.motion.R;
import com.vloc.motion.core.UsageLog;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 使用记录（会话历史）列表页。
 *
 * <p>数据来自 {@link UsageLog}（独立 prefs {@code vloc_usage}，环形缓冲最多 50 条）。
 * 读取/删除/清空全部在<b>后台线程</b>执行（虽然只有 50 条，但 prefs 反序列化仍按 IO 处理，
 * 不占主线程），结果 post 回主线程刷新列表。
 *
 * <p>交互：
 * <ul>
 *   <li>点击单条 → 对话框展示完整信息（点数 / 错误 / 清理结果），可「删除这条」；</li>
 *   <li>底部「清空全部」→ 二次确认后清空；</li>
 *   <li>空列表显示「暂无记录」。</li>
 * </ul>
 */
public class UsageLogActivity extends AppCompatActivity {

    private static final String TAG = "VLocUsageUi";

    private ListView listUsage;
    private TextView textUsageEmpty;
    private TextView textUsageCount;
    private Button buttonClearUsage;

    /** 当前展示的数据（只在主线程读写）。 */
    private final List<UsageLog.Entry> entries = new ArrayList<>();

    private UsageAdapter adapter;
    private ExecutorService executor;
    private boolean loading;
    private boolean destroyed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_usage_log);

        MaterialToolbar toolbar = findViewById(R.id.usageToolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        listUsage = findViewById(R.id.listUsage);
        textUsageEmpty = findViewById(R.id.textUsageEmpty);
        textUsageCount = findViewById(R.id.textUsageCount);
        buttonClearUsage = findViewById(R.id.buttonClearUsage);

        adapter = new UsageAdapter();
        listUsage.setAdapter(adapter);
        listUsage.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < entries.size()) {
                showDetailDialog(entries.get(position));
            }
        });
        buttonClearUsage.setOnClickListener(v -> confirmClearAll());

        startLoad();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        ExecutorService service = executor;
        executor = null;
        if (service != null) {
            service.shutdown();
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------ 后台读写

    /** 首次加载：后台读取 + 主线程刷新。 */
    private void startLoad() {
        loading = true;
        textUsageCount.setText("正在读取记录…");
        executor().execute(() -> {
            List<UsageLog.Entry> loaded;
            try {
                loaded = UsageLog.list(getApplicationContext());
            } catch (RuntimeException e) {
                Log.w(TAG, "读取使用记录失败", e);
                loaded = new ArrayList<>();
            }
            final List<UsageLog.Entry> result = loaded;
            post(() -> {
                loading = false;
                entries.clear();
                entries.addAll(result);
                refresh();
            });
        });
    }

    /** 删除 / 清空：在后台线程改盘，再读回最新列表。 */
    private void mutate(java.util.function.BooleanSupplier action, String success) {
        executor().execute(() -> {
            boolean changed = false;
            try {
                changed = action.getAsBoolean();
            } catch (RuntimeException e) {
                Log.w(TAG, "修改使用记录失败", e);
            }
            final String outcome = changed ? success : "未能确认删除，请重新查看使用记录";
            post(() -> {
                toast(outcome);
                startLoad();
            });
        });
    }

    private ExecutorService executor() {
        ExecutorService service = executor;
        if (service == null || service.isShutdown()) {
            service = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "vloc-usage-log");
                thread.setDaemon(true);
                return thread;
            });
            executor = service;
        }
        return service;
    }

    /** post 回主线程（页面已销毁则丢弃结果）。 */
    private void post(Runnable action) {
        runOnUiThread(() -> {
            if (destroyed || isFinishing()) {
                return;
            }
            action.run();
        });
    }

    // ------------------------------------------------------------ 列表刷新

    private void refresh() {
        adapter.notifyDataSetChanged();
        boolean empty = entries.isEmpty();
        textUsageEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        listUsage.setVisibility(empty ? View.GONE : View.VISIBLE);
        buttonClearUsage.setEnabled(!empty);
        if (loading) {
            textUsageCount.setText("正在读取记录…");
        } else if (empty) {
            textUsageCount.setText("共 0 条记录（最多保留 " + UsageLog.MAX_ENTRIES + " 条）");
        } else {
            textUsageCount.setText("共 " + entries.size() + " 条记录（最多保留 "
                    + UsageLog.MAX_ENTRIES + " 条，超出后自动删除最旧一条）");
        }
    }

    // ------------------------------------------------------------ 对话框

    /** 单条详情：完整信息 + 「删除这条」。 */
    private void showDetailDialog(UsageLog.Entry entry) {
        new AlertDialog.Builder(this)
                .setTitle("使用记录详情")
                .setMessage(describe(entry))
                .setPositiveButton("删除这条", (dialog, which) -> {
                    long id = entry.id;
                    // 删除 + 重新读取都在后台线程，主线程只等回调刷新
                    mutate(() -> UsageLog.delete(getApplicationContext(), id), "已删除");
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    /** 清空全部（二次确认）。 */
    private void confirmClearAll() {
        if (entries.isEmpty()) {
            toast("暂无记录");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("清空全部使用记录")
                .setMessage("将删除全部 " + entries.size() + " 条记录，清空后不可恢复。")
                .setPositiveButton("清空", (dialog, which) -> {
                    mutate(() -> UsageLog.clear(getApplicationContext()), "已清空");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 完整信息（对话框正文）。 */
    private String describe(UsageLog.Entry entry) {
        StringBuilder sb = new StringBuilder();
        sb.append("模式：").append(modeLabel(entry.mode)).append('\n');
        sb.append("开始：").append(fullTime(entry.startedAt)).append('\n');
        sb.append("结束：").append(entry.endedAt > 0L ? fullTime(entry.endedAt) : "—").append('\n');
        sb.append("里程：").append(formatDistance(entry.distanceMeters)).append('\n');
        sb.append("时长：").append(formatDuration(entry.durationSec)).append('\n');
        sb.append("路径点数：").append(entry.points).append(" 个\n");
        sb.append("结果：").append(resultLabel(entry.result));
        if (entry.error != null && !entry.error.isEmpty()) {
            sb.append("\n错误：").append(entry.error);
        }
        if (entry.cleanup != null && !entry.cleanup.isEmpty()) {
            sb.append("\n清理结果：").append(entry.cleanup);
        } else {
            sb.append("\n清理结果：无（未执行清理或清理尚未完成）");
        }
        return sb.toString();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------ 文案格式化

    private static String modeLabel(String mode) {
        if (UsageLog.MODE_ROUTE.equals(mode)) {
            return "路径模拟";
        }
        if (UsageLog.MODE_SPOT.equals(mode)) {
            return "临时定位";
        }
        return "未知模式";
    }

    private static String resultLabel(String result) {
        if (UsageLog.RESULT_COMPLETED.equals(result)) {
            return "完成";
        }
        if (UsageLog.RESULT_STOPPED.equals(result)) {
            return "手动停止";
        }
        if (UsageLog.RESULT_FAILED.equals(result)) {
            return "失败";
        }
        return "未知结果";
    }

    /** 结果颜色：完成=绿、手动停止=灰、失败=红。 */
    private int resultColor(Context context, String result) {
        if (UsageLog.RESULT_COMPLETED.equals(result)) {
            return ContextCompat.getColor(context, R.color.accent);
        }
        if (UsageLog.RESULT_FAILED.equals(result)) {
            return ContextCompat.getColor(context, R.color.danger);
        }
        return ContextCompat.getColor(context, R.color.text_secondary);
    }

    private static String formatDistance(double meters) {
        if (meters >= 1000d) {
            return String.format(Locale.US, "%.2f km", meters / 1000d);
        }
        return String.format(Locale.US, "%.0f m", meters);
    }

    private static String formatDuration(long seconds) {
        long s = Math.max(0L, seconds);
        long hours = s / 3600L;
        long minutes = (s % 3600L) / 60L;
        long secs = s % 60L;
        if (hours > 0L) {
            return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, secs);
        }
        return String.format(Locale.US, "%02d:%02d", minutes, secs);
    }

    private static String shortTime(long timestampMs) {
        return new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new Date(timestampMs));
    }

    private static String fullTime(long timestampMs) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(new Date(timestampMs));
    }

    // ------------------------------------------------------------ 适配器

    private static final class ViewHolder {
        final TextView time;
        final TextView detail;
        final TextView result;

        ViewHolder(View row) {
            time = row.findViewById(R.id.usageTime);
            detail = row.findViewById(R.id.usageDetail);
            result = row.findViewById(R.id.usageResult);
        }
    }

    private final class UsageAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return entries.size();
        }

        @Override
        public Object getItem(int position) {
            return entries.get(position);
        }

        @Override
        public long getItemId(int position) {
            return entries.get(position).id;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView;
            ViewHolder holder;
            if (row == null) {
                row = getLayoutInflater().inflate(R.layout.item_usage_log, parent, false);
                holder = new ViewHolder(row);
                row.setTag(holder);
            } else {
                holder = (ViewHolder) row.getTag();
            }
            UsageLog.Entry entry = entries.get(position);
            holder.time.setText(shortTime(entry.startedAt));
            holder.detail.setText(modeLabel(entry.mode)
                    + " · " + formatDistance(entry.distanceMeters)
                    + " · " + formatDuration(entry.durationSec));
            holder.result.setText(resultLabel(entry.result));
            holder.result.setTextColor(resultColor(UsageLogActivity.this, entry.result));
            return row;
        }
    }
}
