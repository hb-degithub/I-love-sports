package com.vloc.motion.location;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Bundle;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import com.vloc.motion.core.Fix;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * 通过系统测试 Provider 注入定位。
 *
 * 原理：调用 LocationManager.addTestProvider 接管同名 Provider（gps / network / fused），
 * 再用 setTestProviderLocation 写入伪造坐标。普通应用需要在 root 下先获得
 * android:mock_location appop（见 RootShell），或以 priv-app 形式安装。
 */
public final class TestProviderInjector {

    private static final String TAG = "VLocInjector";

    /** 默认接管 gps + network 两个 Provider。 */
    public static final List<String> DEFAULT_PROVIDERS =
            Arrays.asList("gps", "network");

    private final LocationManager locationManager;
    /** 只用于读系统定位总开关（{@link LocationSwitch}）；存 ApplicationContext 避免泄漏。 */
    private final Context context;
    private final List<String> providers;
    /** 当前仍能写入的 Provider：push() 只碰这些（运行中被系统收回的会从这里摘掉）。 */
    private final List<String> preparedProviders = new ArrayList<>();
    /**
     * 已经调用过 addTestProvider、在系统里注册成 test provider 的 Provider。
     *
     * <p>与 {@link #preparedProviders} 的区别：push() 遇到 SecurityException 只会把
     * Provider 从"可写列表"里摘掉，**系统里的 test provider 注册并不会因此消失**。
     * 所以 {@link #release()} 必须按本集合逐个 removeTestProvider，否则被摘掉的 Provider
     * 会作为常驻 mock provider 留到设备重启（下次启动时 App.forceCleanup 通常已无
     * appop，清不掉）。addTestProvider 成功、或抛 IllegalArgumentException("已存在")
     * 都算"系统里已注册"。
     */
    private final Set<String> addedProviders = new LinkedHashSet<>();
    /** 本次 prepare() 中被拒绝 / 失败的 Provider。 */
    private final List<String> failedProviders = new ArrayList<>();
    /** Provider → 失败原因（供 {@link #describe()} 展示）。 */
    private final Map<String, String> failureReasons = new LinkedHashMap<>();
    /** 状态标志（{@link #prepared} 与上面几个集合）的并发保护：prepare/push/release 跨线程调用。 */
    private boolean prepared;
    /**
     * 一次性诊断任务用的线程池（懒创建、空闲线程自动回收，**不是**常驻线程）。
     *
     * <p>只承载"停止后的只读诊断"这类可以在任意线程完成的工作：它们要读系统定位，
     * 而 {@link #release()} 在主线程执行，绝不能在这里阻塞收尾。
     */
    private static final ExecutorService DIAG_EXECUTOR =
            Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "vloc-injector-diag");
                thread.setDaemon(true);
                return thread;
            });

    public TestProviderInjector(Context context, List<String> providers) {
        this.context = context == null ? null
                : (context.getApplicationContext() != null
                        ? context.getApplicationContext() : context);
        this.locationManager =
                (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        List<String> list = new ArrayList<>();
        if (providers != null) {
            for (String p : providers) {
                if (p != null && !p.trim().isEmpty()) {
                    list.add(p.trim());
                }
            }
        }
        if (list.isEmpty()) {
            list.addAll(DEFAULT_PROVIDERS);
        }
        this.providers = list;
    }

    public List<String> getProviders() {
        return new ArrayList<>(providers);
    }

    public synchronized boolean isPrepared() {
        return prepared;
    }

    /** 已在系统里注册过的 Provider（副本）：release() 的移除依据。 */
    public synchronized List<String> getAddedProviders() {
        return new ArrayList<>(addedProviders);
    }

    /** 本次被拒绝 / 失败的 Provider（副本）。 */
    public synchronized List<String> getFailedProviders() {
        return new ArrayList<>(failedProviders);
    }

    /**
     * 人可读的接管结果，例如「gps 已接管；network 被拒：被系统拒绝（SecurityException: …）」。
     *
     * <p>用于让 UI / logcat 讲清楚"部分成功、还能不能继续"：Android 12+ 上 network
     * provider 常由系统融合定位持有，第三方接管被拒是常态，而 gps 往往仍可接管。
     */
    public synchronized String describe() {
        StringBuilder sb = new StringBuilder();
        if (!preparedProviders.isEmpty()) {
            sb.append(String.join("、", preparedProviders)).append(" 已接管");
        }
        for (int i = 0; i < failedProviders.size(); i++) {
            String name = failedProviders.get(i);
            if (sb.length() > 0) {
                sb.append("；");
            }
            sb.append(name).append(" 被拒：").append(failureReasons.get(name));
        }
        if (sb.length() == 0) {
            sb.append("未尝试接管任何 Provider");
        }
        if (!preparedProviders.isEmpty() && !preparedProviders.contains("gps")) {
            // gps 没接管成功时注入仍可能生效，但大多数 App 只读 gps/fused，必须提示
            sb.append("；（未接管 gps，多数 App 仍可能拿到真实定位）");
        }
        return sb.toString();
    }

    /**
     * 逐个尽力接管 Provider。
     *
     * <p>每个 provider 独立 try/catch：失败只记入失败列表并继续下一个，绝不让一个
     * provider 的拒绝拖垮其他已成功的接管。全部失败才算整体失败（抛
     * {@link SecurityException}），只要有 ≥1 个成功就认为准备成功。
     *
     * <p>接管前先确认系统定位总开关（{@link LocationSwitch}）：开关关闭时
     * {@link LocationManager#isProviderEnabled} 一律返回 false（{@code user0 enabled=false}），
     * 与 {@code android:mock_location} appop 毫无关系，用它做二次确认会把"没开定位"
     * 误判成"Provider 未接管成功"。所以这里先短路，直接以总开关的原因失败，绝不
     * 让用户看到"接管被拒绝"这种误导性文案。
     *
     * <p><b>线程安全</b>：prepare()/push()/release() 会在不同线程上调用（后台准备线程 +
     * 主线程），全部用同一个监视器串行化，provider 列表不会被并发改写（不再有
     * ConcurrentModificationException / 半更新状态）。
     *
     * @throws SecurityException 系统定位关闭，或所有 Provider 都接管失败
     */
    public synchronized void prepare() {
        preparedProviders.clear();
        failedProviders.clear();
        failureReasons.clear();
        // 注意：不清 addedProviders——prepare() 之前若已注册过 Provider，release() 仍要移除它们

        if (!LocationSwitch.isSystemLocationEnabled(context)) {
            Log.w(TAG, "system location switch is off, skip test provider takeover");
            throw new SecurityException(LocationSwitch.DISABLED_MSG);
        }
        for (String provider : providers) {
            if (takeOver(provider)) {
                preparedProviders.add(provider);
                Log.d(TAG, "test provider ready: " + provider);
            }
        }
        prepared = !preparedProviders.isEmpty();
        if (!prepared) {
            throw new SecurityException(describe());
        }
        Log.i(TAG, "test providers: " + describe());
    }

    /**
     * 接管单个 Provider，返回是否成功；任何一步失败都不向上抛。
     *
     * <p>二次确认（{@link LocationManager#isProviderEnabled}）只对本类刚刚接管过的
     * Provider、且只在"接管前未启用"时使用：appops 被显式 deny 时框架可能静默
     * return（不抛异常也不会真正接管），需要它兜底；但真实 gps 通常本来就 enabled，
     * 拿它当证据会把静默失败误判成成功（此前审计已指出）。
     *
     * <p>前提：{@link #prepare()} 已确认系统定位总开关开启。开关关闭时
     * {@code isProviderEnabled} 恒为 false（与 appop 无关），此时这条确认不成立，
     * 已在 prepare() 里提前短路，绝不会在这里报"Provider 未接管成功"。
     */
    // 旧版 addTestProvider 重载用的是 Criteria 常量（与 ProviderProperties 数值一致），lint 会误报
    @SuppressLint("WrongConstant")
    private boolean takeOver(String provider) {
        boolean enabledBefore;
        try {
            enabledBefore = locationManager.isProviderEnabled(provider);
        } catch (RuntimeException e) {
            enabledBefore = false;   // 查询失败按未启用处理，交给下面的确认兜底
        }
        try {
            locationManager.addTestProvider(provider,
                    false,   // requiresNetwork
                    false,   // requiresSatellite
                    false,   // requiresCell
                    false,   // hasMonetaryCost
                    true,    // supportsAltitude
                    true,    // supportsSpeed
                    true,    // supportsBearing
                    Criteria.POWER_LOW,
                    Criteria.ACCURACY_FINE);
            // 注册成功：从这一刻起系统里就有这条 test provider，release() 必须能移除它
            // （哪怕后面 setTestProviderEnabled 被拒、本方法返回 false）。
            markAdded(provider);
        } catch (IllegalArgumentException alreadyExists) {
            // 之前已经添加过同名测试 Provider（例如上次进程被强杀），直接启用即可。
            // 它同样注册在系统里（test provider 按 uid 隔离，只可能是本应用留的），
            // 停止时必须由 release() 移除，否则会一直顶掉真实定位。
            markAdded(provider);
        } catch (SecurityException e) {
            fail(provider, "被系统拒绝", e);
            return false;
        } catch (RuntimeException e) {
            fail(provider, "接管失败", e);
            return false;
        }
        try {
            locationManager.setTestProviderEnabled(provider, true);
        } catch (SecurityException e) {
            fail(provider, "启用被拒绝", e);
            return false;
        } catch (RuntimeException e) {
            fail(provider, "启用失败", e);
            return false;
        }
        if (!enabledBefore) {
            try {
                if (!locationManager.isProviderEnabled(provider)) {
                    fail(provider, "未生效（android:mock_location appop 可能未生效或被拒绝）", null);
                    return false;
                }
            } catch (RuntimeException e) {
                fail(provider, "确认失败", e);
                return false;
            }
        }
        return true;
    }

    /** 记录一个 Provider 的失败：logcat 留全量堆栈，describe() 留简短原因。 */
    private void fail(String provider, String reason, Exception e) {
        String detail = reason;
        if (e != null) {
            String message = e.getMessage();
            detail = reason + "（" + e.getClass().getSimpleName()
                    + (message == null ? "" : ": " + message) + "）";
            Log.w(TAG, "test provider failed: " + provider + " - " + detail, e);
        } else {
            Log.w(TAG, "test provider failed: " + provider + " - " + detail);
        }
        failedProviders.add(provider);
        failureReasons.put(provider, detail);
    }

    /** 记录"系统里已有这条 test provider"：只有它才能让 release() 真正清干净。 */
    private void markAdded(String provider) {
        if (addedProviders.add(provider)) {
            Log.d(TAG, "test provider registered, release() will remove it: " + provider);
        }
    }

    /** 写入一次拟真定位（含海拔 / 垂直精度 / GNSS 附属数据）。 */
    public synchronized void push(Fix fix, long timeMs) {
        if (!prepared) {
            throw new IllegalStateException("injector not prepared");
        }
        if (preparedProviders.isEmpty()) {
            // 曾是"prepared=true 但一个 Provider 都不剩"，这里会静默 return：界面显示运行中、
            // 里程在涨，实际一个坐标都没写。现在明确失败，让上层 failInjection 停止会话。
            prepared = false;
            throw new IllegalStateException("no test provider left: " + describe());
        }
        long elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos();
        // 只往本次真正接管成功的 Provider 写：失败的 provider 写也写不进去
        List<String> lost = null;
        for (String provider : preparedProviders) {
            Location location = new Location(provider);
            location.setLatitude(fix.lat);
            location.setLongitude(fix.lon);
            location.setTime(timeMs);
            location.setElapsedRealtimeNanos(elapsedRealtimeNanos);
            location.setAccuracy(fix.accuracyMeters);
            location.setAltitude(fix.altitudeMeters);
            location.setSpeed(fix.speedMps);
            location.setBearing(fix.bearingDeg);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                location.setVerticalAccuracyMeters(fix.verticalAccuracyMeters);
                location.setSpeedAccuracyMetersPerSecond(Math.max(0.2f, fix.speedMps * 0.05f));
                location.setBearingAccuracyDegrees(3f);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+：写入平均海平面高度，部分应用会读 MSL 而不是椭球高
                location.setMslAltitudeMeters(fix.altitudeMeters);
                location.setMslAltitudeAccuracyMeters(fix.verticalAccuracyMeters);
            }
            if (fix.satellites > 0) {
                // 真实 GPS 定位常带这些 extras，缺失本身就是一种"假定位"特征
                Bundle extras = new Bundle();
                extras.putInt("satellites", fix.satellites);
                extras.putFloat("meanCn0", fix.meanCn0);
                extras.putFloat("maxCn0", fix.maxCn0);
                location.setExtras(extras);
            }
            try {
                locationManager.setTestProviderLocation(provider, location);
            } catch (SecurityException e) {
                // 运行中被系统收回（appop 被撤销 / provider 被替换）：只放弃这一个，
                // 其余仍可用的 Provider 继续注入——否则会重演"一个失败废掉全部"。
                Log.w(TAG, "push rejected for " + provider + ", giving it up", e);
                if (lost == null) {
                    lost = new ArrayList<>();
                }
                lost.add(provider);
            } catch (Exception e) {
                Log.w(TAG, "push failed for " + provider + ": " + e);
            }
        }
        if (lost != null) {
            for (String provider : lost) {
                // 只从"可写列表"里摘掉：addedProviders 保留，release() 仍要 removeTestProvider
                preparedProviders.remove(provider);
                failedProviders.add(provider);
                failureReasons.put(provider, "运行中被系统收回");
            }
            if (preparedProviders.isEmpty()) {
                // 一个都不剩才整体失败，让上层 failInjection 停止会话；
                // 同时把 prepared 置 false，下一帧不会再以"已准备"状态空转。
                prepared = false;
                throw new SecurityException("所有测试 Provider 都已失效：" + describe());
            }
            Log.w(TAG, "test providers now: " + describe());
        }
    }

    /** 进程异常退出后清理残留的测试 Provider（尽力而为，不需要 root）。 */
    public static void forceCleanup(Context context, List<String> providerNames) {
        LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null || providerNames == null) {
            return;
        }
        for (String name : providerNames) {
            try {
                lm.removeTestProvider(name);
                Log.i(TAG, "cleaned leftover test provider: " + name);
            } catch (Exception ignored) {
                // 不是测试 Provider 或权限不足：忽略
            }
        }
    }

    /**
     * 还原系统真实定位（尽力而为）。
     *
     * <p><b>passive 不在、也永远不可能在本应用的接管列表里</b>：Android 8.0+ 对
     * {@code addTestProvider("passive", ...)} 直接抛
     * {@code IllegalArgumentException("Cannot mock the passive location provider")}
     * （见 docs/root-injection.md:324）。停止后 {@code dumpsys location} 里 passive 仍留着
     * 最后一条带 mock 标记的位置，是 system_server 把任意 provider（包括测试 Provider）
     * 最后一次上报的位置镜像进 passive 的系统级缓存；App 侧没有删除/清空它的 API。
     *
     * <p>本应用能做的"尽力清理"是：移除所有自己添加的测试 Provider（本方法），然后由
     * {@code LocationService#refreshRealFixForPassive()} 触发一次真实定位请求，让真实
     * provider 的下一次上报覆盖 passive 的旧值。室内/无信号时可能覆盖不到——这不是
     * 本应用能强制清除的，属系统层限制，不要试图用 {@code removeTestProvider("passive")}
     * 之类的假清理来"修好"它。
     */
    public synchronized void release() {
        // 按 addedProviders（而**不是** preparedProviders）移除：push() 中被系统收回的
        // Provider 已经不在 preparedProviders 里，但它在系统里的 test provider 注册还在；
        // 只按可写列表清理会把它留成常驻 mock provider，用户以为已恢复真实定位。
        // 没接管成功的 Provider 从未进入 addedProviders（addTestProvider 抛异常），
        // 所以这里也不会对不存在的 provider 调 remove。
        for (String provider : addedProviders) {
            try {
                locationManager.setTestProviderEnabled(provider, false);
            } catch (Exception ignored) {
                // 忽略
            }
            try {
                locationManager.removeTestProvider(provider);
            } catch (Exception e) {
                Log.w(TAG, "remove test provider failed: " + provider + " " + e);
            }
        }
        addedProviders.clear();
        preparedProviders.clear();
        failedProviders.clear();
        failureReasons.clear();
        prepared = false;
        // 诊断要读系统定位，可能在主线程阻塞（release() 由 stopInternal 在主线程调用）：
        // 放到一次性后台任务，removeTestProvider 的收尾仍然第一时间完成，绝不因诊断推迟。
        schedulePassiveResidualCheck();
    }

    /** 把只读诊断丢给后台线程池；线程池已关停（进程退出中）时直接丢弃，不影响停止。 */
    private void schedulePassiveResidualCheck() {
        try {
            DIAG_EXECUTOR.execute(this::logPassiveResidual);
        } catch (RejectedExecutionException e) {
            Log.w(TAG, "skip passive residual check: executor rejected", e);
        }
    }

    /**
     * 只读诊断：确认 passive 的系统缓存是否还带着 mock 标记。
     *
     * <p>这一步不尝试修改（也没有 API 可改），只为真机排查留下日志证据：
     * 出现这条日志说明"测试 Provider 已全部移除，但 passive 仍是系统镜像"。
     *
     * <p><b>只在后台线程执行</b>（见 {@link #schedulePassiveResidualCheck()}）：
     * {@code getLastKnownLocation} 是 binder 调用，放在停止路径的主线程会拖住
     * removeTestProvider 之后的收尾。
     */
    private void logPassiveResidual() {
        try {
            Location passive = locationManager.getLastKnownLocation("passive");
            if (passive != null && passive.isFromMockProvider()) {
                Log.i(TAG, "passive still holds the last mock location "
                        + "(system-level mirror, not removable via test-provider APIs)");
            }
        } catch (RuntimeException ignored) {
            // 无定位权限 / provider 不可用：诊断失败不影响停止
        }
    }
}
