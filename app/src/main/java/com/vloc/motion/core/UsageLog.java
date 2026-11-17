package com.vloc.motion.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 「使用记录」：每次模拟会话（路径 / 临时定位）结束后留下的一条历史。
 *
 * <p><b>为什么单独一份 prefs</b>：记录里会有错误文案与清理结果，属于"可以整批清掉"的数据；
 * 放进 {@code vloc_prefs} 会让「清空记录」和设置项/路线库混在一份文件里，误删风险大。
 * 因此用独立的 {@code vloc_usage}，只有一个 key（{@value #KEY_ENTRIES}）存整份 JSON 数组。
 *
 * <p><b>环形缓冲</b>：最多 {@value #MAX_ENTRIES} 条，超出后丢<b>最旧</b>的一条。会话记录只是
 * 给用户回顾"刚才跑了什么"，没必要无限增长；50 条大约相当于一周的日常使用量。
 *
 * <p><b>写入方式</b>：{@link #record} / {@link #delete} / {@link #updateCleanup} 都是
 * "读整份 JSON → 改 → 写回"，用 {@link #LOCK} 串行化；落盘一律 {@code apply()}（异步），
 * 不阻塞调用线程（会话停止流程在主线程）。读改写窗口很短（最多 50 条），可以接受。
 *
 * <p><b>容错</b>：JSON 损坏 / 机型上的 prefs 读异常一律视为"没有记录"，返回空列表，
 * 绝不抛异常——它只是附属功能，不能拖垮启动页或服务停止流程。
 *
 * <p><b>线程</b>：所有方法都可以在任意线程调用；UI 层负责把结果 post 回主线程。
 */
public final class UsageLog {

    private static final String TAG = "VLocUsage";

    /** 独立 prefs 文件名（与 {@link RouteStore} 的 {@code vloc_prefs} 分开）。 */
    private static final String PREFS = "vloc_usage";
    /** 唯一的 key：整份记录数组（旧 → 新）。 */
    private static final String KEY_ENTRIES = "entries";

    /** 环形缓冲上限：超出后丢最旧的一条。 */
    public static final int MAX_ENTRIES = 50;

    // ---------------------------------------------------------------- 模式（mode）
    /** 路径模拟（沿路线移动）。 */
    public static final String MODE_ROUTE = "ROUTE";
    /** 临时定位（定点保持）。 */
    public static final String MODE_SPOT = "SPOT";

    // ---------------------------------------------------------------- 结果（result）
    /** 正常跑完（到达终点 / 目标）。 */
    public static final String RESULT_COMPLETED = "completed";
    /** 用户手动停止（含尚未开始就被停止）。 */
    public static final String RESULT_STOPPED = "stopped";
    /** 失败（准备失败 / 注入失败 / 模块不在线等）。 */
    public static final String RESULT_FAILED = "failed";

    /** JSON 字段名：与 {@link Entry} 的字段一一对应。 */
    private static final String K_ID = "id";
    private static final String K_STARTED_AT = "startedAt";
    private static final String K_ENDED_AT = "endedAt";
    private static final String K_MODE = "mode";
    private static final String K_POINTS = "points";
    private static final String K_DISTANCE = "distanceMeters";
    private static final String K_DURATION = "durationSec";
    private static final String K_RESULT = "result";
    private static final String K_ERROR = "error";
    private static final String K_CLEANUP = "cleanup";

    /** 串行化"读整份 → 改 → 写回"，避免服务线程与清理回调互相覆盖。 */
    private static final Object LOCK = new Object();

    /** 按开始时间倒序（同一时刻用 id 兜底，保证顺序稳定）。 */
    private static final Comparator<Entry> NEWEST_FIRST = (a, b) -> {
        if (a.startedAt != b.startedAt) {
            return Long.compare(b.startedAt, a.startedAt);
        }
        return Long.compare(b.id, a.id);
    };

    private UsageLog() {
    }

    // ---------------------------------------------------------------- 记录模型

    /** 一条使用记录。 */
    public static final class Entry {
        /** 记录 id（毫秒时间戳，必要时 +1 保证单调递增）。 */
        public long id;
        /** 会话开始时刻（墙钟毫秒）。 */
        public long startedAt;
        /** 会话结束时刻（墙钟毫秒）。 */
        public long endedAt;
        /** {@link #MODE_ROUTE} / {@link #MODE_SPOT}。 */
        public String mode;
        /** 路径点数（临时定位为 0）。 */
        public int points;
        /** 本会话移动里程（米）。 */
        public double distanceMeters;
        /** 会话时长（秒）。 */
        public long durationSec;
        /** {@link #RESULT_COMPLETED} / {@link #RESULT_STOPPED} / {@link #RESULT_FAILED}。 */
        public String result;
        /** 失败原因（可空）。 */
        public String error;
        /** 会话结束后的痕迹清理结果（{@code TraceCleaner.Result#summary()}；可空）。 */
        public String cleanup;

        /** 供 JSON 反序列化 / 调用方先建对象后填字段。 */
        public Entry() {
        }

        /** 常用字段一次性填好（error / cleanup 随后按需设置）。 */
        public Entry(long startedAt, long endedAt, String mode, int points,
                     double distanceMeters, long durationSec, String result) {
            this.startedAt = startedAt;
            this.endedAt = endedAt;
            this.mode = mode;
            this.points = points;
            this.distanceMeters = distanceMeters;
            this.durationSec = durationSec;
            this.result = result;
        }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put(K_ID, id);
                o.put(K_STARTED_AT, startedAt);
                o.put(K_ENDED_AT, endedAt);
                o.put(K_MODE, mode == null ? "" : mode);
                o.put(K_POINTS, points);
                o.put(K_DISTANCE, distanceMeters);
                o.put(K_DURATION, durationSec);
                o.put(K_RESULT, result == null ? "" : result);
                if (error != null && !error.isEmpty()) {
                    o.put(K_ERROR, error);
                }
                if (cleanup != null && !cleanup.isEmpty()) {
                    o.put(K_CLEANUP, cleanup);
                }
            } catch (JSONException e) {
                // JSONObject.put 对 String/long/double 不会抛；真抛了也不影响其它字段
                Log.w(TAG, "entry to json failed: " + e);
            }
            return o;
        }

        /** 单条损坏只会让这条被跳过（返回 null），不影响其余记录。 */
        static Entry fromJson(JSONObject o) {
            if (o == null) {
                return null;
            }
            Entry e = new Entry();
            e.id = o.optLong(K_ID, 0L);
            e.startedAt = o.optLong(K_STARTED_AT, 0L);
            e.endedAt = o.optLong(K_ENDED_AT, e.startedAt);
            e.mode = o.optString(K_MODE, "");
            e.points = o.optInt(K_POINTS, 0);
            e.distanceMeters = o.optDouble(K_DISTANCE, 0d);
            e.durationSec = o.optLong(K_DURATION, 0L);
            e.result = o.optString(K_RESULT, "");
            String error = o.optString(K_ERROR, "");
            e.error = error.isEmpty() ? null : error;
            String cleanup = o.optString(K_CLEANUP, "");
            e.cleanup = cleanup.isEmpty() ? null : cleanup;
            // 没有任何时间信息的对象视为损坏条目（老版本 / 手改文件都可能留下）
            if (e.startedAt <= 0L && e.id <= 0L) {
                return null;
            }
            return e;
        }
    }

    // ---------------------------------------------------------------- 对外 API

    /**
     * 追加一条记录（超出 {@value #MAX_ENTRIES} 丢最旧）。
     *
     * <p>{@code entry.id <= 0} 时自动分配（毫秒时间戳，已存在则 +1）。
     *
     * @return 实际写入的记录 id；参数非法 / 写盘失败返回 0
     */
    public static long record(Context context, Entry entry) {
        if (context == null || entry == null) {
            return 0L;
        }
        synchronized (LOCK) {
            List<Entry> all = load(context);
            if (entry.id <= 0L) {
                entry.id = nextId(all);
            }
            if (entry.endedAt < entry.startedAt) {
                entry.endedAt = entry.startedAt;
            }
            all.add(entry);
            // 环形缓冲：列表按"旧 → 新"存放，超了就从头部丢
            while (all.size() > MAX_ENTRIES) {
                all.remove(0);
            }
            return save(context, all) ? entry.id : 0L;
        }
    }

    /** 全部记录，按开始时间<b>倒序</b>（最新在前）；空 / 损坏时返回空列表，永不为 null。 */
    public static List<Entry> list(Context context) {
        if (context == null) {
            return new ArrayList<>();
        }
        synchronized (LOCK) {
            List<Entry> all = load(context);
            Collections.sort(all, NEWEST_FIRST);
            return all;
        }
    }

    /** 清空全部记录（设置项不受影响：独立 prefs）。 */
    public static void clear(Context context) {
        if (context == null) {
            return;
        }
        synchronized (LOCK) {
            try {
                prefs(context).edit().remove(KEY_ENTRIES).apply();
            } catch (RuntimeException e) {
                Log.w(TAG, "clear failed: " + e);
            }
        }
    }

    /**
     * 删除一条记录。
     *
     * @return true = 确实删掉了一条
     */
    public static boolean delete(Context context, long id) {
        if (context == null || id <= 0L) {
            return false;
        }
        synchronized (LOCK) {
            List<Entry> all = load(context);
            boolean removed = false;
            for (int i = all.size() - 1; i >= 0; i--) {
                if (all.get(i).id == id) {
                    all.remove(i);
                    removed = true;
                }
            }
            if (!removed) {
                return false;
            }
            return save(context, all);
        }
    }

    /**
     * 回填某条记录的清理结果（{@code TraceCleaner} 是异步的，会话停止时还不知道结果）。
     *
     * <p>按 id 定位：找不到（记录被删 / 已被环形缓冲挤掉）就静默忽略——这是正常情况，不是错误。
     *
     * @return true = 确实更新了一条
     */
    public static boolean updateCleanup(Context context, long id, String summary) {
        if (context == null || id <= 0L || summary == null || summary.isEmpty()) {
            return false;
        }
        synchronized (LOCK) {
            List<Entry> all = load(context);
            for (Entry entry : all) {
                if (entry.id == id) {
                    entry.cleanup = summary;
                    return save(context, all);
                }
            }
            return false;
        }
    }

    /** 当前记录条数（用于标题 / 清空按钮状态）。 */
    public static int count(Context context) {
        if (context == null) {
            return 0;
        }
        synchronized (LOCK) {
            return load(context).size();
        }
    }

    // ---------------------------------------------------------------- 内部实现

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 读整份记录（"旧 → 新"顺序）；损坏时返回空列表，不抛异常。 */
    private static List<Entry> load(Context context) {
        List<Entry> list = new ArrayList<>();
        String raw;
        try {
            raw = prefs(context).getString(KEY_ENTRIES, null);
        } catch (RuntimeException e) {
            Log.w(TAG, "read failed: " + e);
            return list;
        }
        if (raw == null || raw.isEmpty()) {
            return list;
        }
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                Entry entry = Entry.fromJson(array.optJSONObject(i));
                if (entry != null) {
                    list.add(entry);
                }
            }
        } catch (JSONException e) {
            // JSON 损坏（截断 / 手改 / 旧版本格式）：按"没有记录"处理，并清掉这份坏数据，
            // 否则每次读写都要再 parse 一遍。
            // 注意：Android 的 org.json.JSONException 是<b>受检异常</b>，而 JVM 上的
            // org.json 实现把它做成了 RuntimeException——两个 catch 分支都要有，才能
            // 同时通过 Android 编译和 JVM 单测。
            return resetCorrupt(context, e);
        } catch (RuntimeException e) {
            return resetCorrupt(context, e);
        }
        return list;
    }

    /** JSON 损坏：清掉坏数据并按"没有记录"返回（日志只记一条）。 */
    private static List<Entry> resetCorrupt(Context context, Exception cause) {
        Log.w(TAG, "corrupted json, reset: " + cause);
        try {
            prefs(context).edit().remove(KEY_ENTRIES).apply();
        } catch (RuntimeException ignored) {
            // 清不掉也无所谓，下次仍然按空列表处理
        }
        return new ArrayList<>();
    }

    /** 写回整份记录；失败只记日志（记录功能不能影响主流程）。 */
    private static boolean save(Context context, List<Entry> entries) {
        JSONArray array = new JSONArray();
        for (Entry entry : entries) {
            array.put(entry.toJson());
        }
        try {
            prefs(context).edit().putString(KEY_ENTRIES, array.toString()).apply();
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "write failed: " + e);
            return false;
        }
    }

    /** 新 id：毫秒时间戳；同一毫秒内 / 时钟回拨时用已有最大值 +1，保证严格递增。 */
    private static long nextId(List<Entry> existing) {
        long max = 0L;
        for (Entry entry : existing) {
            max = Math.max(max, entry.id);
        }
        long now = System.currentTimeMillis();
        return now > max ? now : max + 1L;
    }
}
