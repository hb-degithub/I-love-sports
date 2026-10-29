package com.vloc.motion.hidden;

import android.os.Build;
import android.os.SystemClock;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 基站 / Wi-Fi 环境回放（v0.4.0）。
 *
 * <p>在目标 App 进程内把 {@link EnvSnapshot} 里的快照回放成真实的
 * {@code CellInfo} / {@code GsmCellLocation} / {@code ScanResult} / {@code WifiInfo} 值，或按
 * {@code mode=hide} 把对应数据清空。
 *
 * <p>版本兼容策略（AOSP 8.1 ~ 15 实测构造器）：
 * <ul>
 *   <li><b>CellInfoGsm/Lte/Nr 外壳</b>：所有版本都有 public 无参构造器 +
 *       {@code setCellIdentity}/{@code setCellSignalStrength}（CellInfoNr 在部分版本只有一个 setter），
 *       统一用“无参构造 + setter + 私有字段兜底”，不依赖各版本的 value 构造器；</li>
 *   <li><b>CellIdentityGsm/Lte/Nr、CellSignalStrengthGsm/Lte/Nr</b>：按 {@code Build.VERSION} 无关的
 *       构造器签名逐一尝试（同签名在不同版本存在与否由 {@code getDeclaredConstructor} 判定），
 *       全部失败再退到“无参 + 私有字段注入”，仍失败则该基站跳过；</li>
 *   <li><b>ScanResult</b>：所有版本都有 public 无参构造器 + public 字段（SSID/BSSID/level/frequency），
 *       直接字段赋值；</li>
 *   <li>任何反射失败都只降级（跳过该条 / 返回空列表 / null），绝不抛给目标 App。</li>
 * </ul>
 */
final class EnvReplay {

    private static final String TAG = "[VLocHidden] ";

    /** CellInfo.CONNECTION_PRIMARY_SERVING / CONNECTION_NONE 的值（AOSP 8.1~15 一致）。 */
    private static final int CONNECTION_PRIMARY_SERVING = 1;
    private static final int CONNECTION_NONE = 0;

    private static ClassLoader sClassLoader;

    /** 同一份快照的 CellInfo 列表只构造一次（每 3 秒最多一次），每次返回副本避免调用方改坏缓存。 */
    private static volatile EnvSnapshot sCachedCellSnapshot;
    private static volatile List<Object> sCachedCells;

    private static boolean sLoggedCellsReplay;
    private static boolean sLoggedCellsFail;
    private static boolean sLoggedWifiReplay;
    private static boolean sLoggedHide;
    private static boolean sLoggedUnsupported;

    static int install(ClassLoader classLoader) {
        sClassLoader = classLoader;
        int hooked = 0;
        hooked += hookTelephony(classLoader);
        hooked += hookWifi(classLoader);
        log("env hooks=" + hooked + "/5 (cell/wifi snapshot replay)");
        return hooked;
    }

    // ==================================================================
    // 安装 hook
    // ==================================================================

    private static int hookTelephony(ClassLoader classLoader) {
        final Class<?> telephonyManager =
                findClassIfExists("android.telephony.TelephonyManager", classLoader);
        if (telephonyManager == null) {
            log("skip TelephonyManager (class not found)");
            return 0;
        }
        int hooked = 0;
        try {
            final int count =
                    XposedBridge.hookAllMethods(telephonyManager, "getAllCellInfo", CELL_LIST_HOOK).size();
            if (count > 0) {
                hooked++;
                log("hooked TelephonyManager#getAllCellInfo (" + count + " overload(s))");
            }
        } catch (Throwable t) {
            log("skip TelephonyManager#getAllCellInfo: " + t);
        }
        try {
            final int count =
                    XposedBridge.hookAllMethods(telephonyManager, "getCellLocation", CELL_LOCATION_HOOK).size();
            if (count > 0) {
                hooked++;
                log("hooked TelephonyManager#getCellLocation (" + count + " overload(s))");
            }
        } catch (Throwable t) {
            log("skip TelephonyManager#getCellLocation: " + t);
        }
        return hooked;
    }

    private static int hookWifi(ClassLoader classLoader) {
        int hooked = 0;
        final Class<?> wifiManager = findClassIfExists("android.net.wifi.WifiManager", classLoader);
        if (wifiManager != null) {
            try {
                final int count =
                        XposedBridge.hookAllMethods(wifiManager, "getScanResults", SCAN_RESULTS_HOOK).size();
                if (count > 0) {
                    hooked++;
                    log("hooked WifiManager#getScanResults (" + count + " overload(s))");
                }
            } catch (Throwable t) {
                log("skip WifiManager#getScanResults: " + t);
            }
        } else {
            log("skip WifiManager (class not found)");
        }

        final Class<?> wifiInfo = findClassIfExists("android.net.wifi.WifiInfo", classLoader);
        if (wifiInfo != null) {
            try {
                XposedHelpers.findAndHookMethod(wifiInfo, "getBSSID", WIFI_BSSID_HOOK);
                hooked++;
                log("hooked WifiInfo#getBSSID");
            } catch (Throwable t) {
                log("skip WifiInfo#getBSSID: " + t);
            }
            try {
                XposedHelpers.findAndHookMethod(wifiInfo, "getSSID", WIFI_SSID_HOOK);
                hooked++;
                log("hooked WifiInfo#getSSID");
            } catch (Throwable t) {
                log("skip WifiInfo#getSSID: " + t);
            }
        } else {
            log("skip WifiInfo (class not found)");
        }
        return hooked;
    }

    // ==================================================================
    // hook 回调
    // ==================================================================

    private static final XC_MethodHook CELL_LIST_HOOK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                final EnvSnapshot snapshot = EnvSnapshot.get();
                if (snapshot == null || !snapshot.isActive()) {
                    return; // 无行 / off / 查询失败 → 透传真实值
                }
                if (snapshot.isHide()) {
                    param.setResult(new ArrayList<Object>());
                    logHideOnce();
                    return;
                }
                param.setResult(new ArrayList<Object>(cellsFor(snapshot)));
            } catch (Throwable ignored) {
                // 失败就透传
            }
        }
    };

    private static final XC_MethodHook CELL_LOCATION_HOOK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                final EnvSnapshot snapshot = EnvSnapshot.get();
                if (snapshot == null || !snapshot.isActive()) {
                    return;
                }
                if (snapshot.isHide()) {
                    param.setResult(null);
                    logHideOnce();
                    return;
                }
                param.setResult(buildCellLocation(snapshot));
            } catch (Throwable ignored) {
                // 失败就透传
            }
        }
    };

    private static final XC_MethodHook SCAN_RESULTS_HOOK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                final EnvSnapshot snapshot = EnvSnapshot.get();
                if (snapshot == null || !snapshot.isActive()) {
                    return;
                }
                if (snapshot.isHide()) {
                    param.setResult(new ArrayList<Object>());
                    logHideOnce();
                    return;
                }
                param.setResult(buildScanResults(snapshot));
            } catch (Throwable ignored) {
                // 失败就透传
            }
        }
    };

    private static final XC_MethodHook WIFI_BSSID_HOOK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                final EnvSnapshot snapshot = EnvSnapshot.get();
                if (snapshot == null || !snapshot.isActive()) {
                    return;
                }
                if (param.hasThrowable()) {
                    param.setThrowable(null);
                }
                if (snapshot.isHide()) {
                    param.setResult(null);
                    logHideOnce();
                    return;
                }
                param.setResult(firstWifiBssid(snapshot));
                logWifiReplayOnce();
            } catch (Throwable ignored) {
                // 失败就透传
            }
        }
    };

    private static final XC_MethodHook WIFI_SSID_HOOK = new XC_MethodHook() {
        /**
         * 用 after 钩子先拿“真实返回值”，按它是否带双引号决定回放值的格式：
         * 不同 ROM 的 getSSID() 引号风格不一致，照抄原格式才不会被“格式异常”识别。
         */
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                final EnvSnapshot snapshot = EnvSnapshot.get();
                if (snapshot == null || !snapshot.isActive()) {
                    return;
                }
                if (param.hasThrowable()) {
                    param.setThrowable(null);
                }
                if (snapshot.isHide()) {
                    param.setResult(null);
                    logHideOnce();
                    return;
                }
                final String raw = firstWifiSsidRaw(snapshot);
                if (raw == null) {
                    param.setResult(null);
                    logWifiReplayOnce();
                    return;
                }
                final Object original = param.getResult();
                final boolean quoted =
                        original instanceof String && ((String) original).startsWith("\"");
                param.setResult(quoted ? quoteSsid(raw) : raw);
                logWifiReplayOnce();
            } catch (Throwable ignored) {
                // 失败就保留原始结果
            }
        }
    };

    // ==================================================================
    // 基站回放
    // ==================================================================

    private static List<Object> cellsFor(EnvSnapshot snapshot) {
        final List<Object> cached = sCachedCells;
        if (cached != null && sCachedCellSnapshot == snapshot) {
            return cached;
        }
        synchronized (EnvReplay.class) {
            if (sCachedCells == null || sCachedCellSnapshot != snapshot) {
                sCachedCells = buildCellInfos(snapshot);
                sCachedCellSnapshot = snapshot;
            }
            return sCachedCells;
        }
    }

    private static List<Object> buildCellInfos(EnvSnapshot snapshot) {
        final List<Object> result = new ArrayList<>();
        int index = 0;
        for (EnvSnapshot.CellSpec spec : snapshot.cells) {
            final boolean serving = index == 0;
            index++;
            if (isSupportedType(spec.type)) {
                final Object cellInfo = buildCellInfo(spec, serving);
                if (cellInfo != null) {
                    result.add(cellInfo);
                }
            }
        }
        if (result.isEmpty()) {
            if (!sLoggedCellsFail) {
                sLoggedCellsFail = true;
                log("replay: no CellInfo could be constructed (snapshot cells="
                        + snapshot.cells.size() + "), returning empty list");
            }
        } else if (!sLoggedCellsReplay) {
            sLoggedCellsReplay = true;
            log("replay: built " + result.size() + " CellInfo");
        }
        return result;
    }

    private static boolean isSupportedType(String type) {
        if ("gsm".equals(type) || "lte".equals(type) || "nr".equals(type)) {
            return true;
        }
        if (!sLoggedUnsupported) {
            sLoggedUnsupported = true;
            log("replay: cell type '" + type + "' not supported yet, skipped");
        }
        return false;
    }

    private static Object buildCellInfo(EnvSnapshot.CellSpec spec, boolean serving) {
        final Class<?> infoClass = findClassIfExists(cellInfoClassName(spec.type), sClassLoader);
        if (infoClass == null) {
            return null;
        }
        final Object identity = buildCellIdentity(spec);
        final Object signalStrength = buildSignalStrength(spec);
        if (identity == null || signalStrength == null) {
            return null;
        }

        // Android 13+ 的 CellInfo* 有 hidden public 全参构造器（(status, registered, timeStamp,
        // identity, signal[, cellConfig])）：字段一次到位，还能避开 CellInfoNr 的 final 信号字段写入。
        final Object builtWithPublicCtor =
                tryBuildCellInfoWithPublicCtor(spec, identity, signalStrength, serving);
        if (builtWithPublicCtor != null) {
            return builtWithPublicCtor;
        }

        final Object cellInfo = newInstanceNoArg(infoClass);
        if (cellInfo == null) {
            return null;
        }

        // 1) 身份 + 信号：优先 setter（8.1~15 基本都有），失败退私有字段
        boolean identitySet = callMethodIfExists(cellInfo, "setCellIdentity", identity);
        if (!identitySet) {
            identitySet = setObjectFieldIfExists(cellInfo, identityFieldName(spec.type), identity);
        }
        if (!identitySet) {
            return null;
        }
        boolean signalSet = callMethodIfExists(cellInfo, "setCellSignalStrength", signalStrength);
        if (!signalSet) {
            signalSet = setObjectFieldIfExists(cellInfo, signalFieldName(spec.type), signalStrength);
        }
        if (!signalSet) {
            return null;
        }

        // 2) 基类字段：时间戳（AOSP 语义为开机后纳秒）、注册态、连接态
        final long timestampNanos = SystemClock.elapsedRealtimeNanos();
        if (!callMethodIfExists(cellInfo, "setTimeStamp", Long.valueOf(timestampNanos))) {
            setLongFieldIfExists(cellInfo, "mTimeStamp", timestampNanos);
        }
        if (!callMethodIfExists(cellInfo, "setRegistered", Boolean.valueOf(serving))) {
            setBooleanFieldIfExists(cellInfo, "mRegistered", serving);
        }
        final int connectionStatus = serving ? CONNECTION_PRIMARY_SERVING : CONNECTION_NONE;
        if (!callMethodIfExists(cellInfo, "setCellConnectionStatus", Integer.valueOf(connectionStatus))) {
            setIntFieldIfExists(cellInfo, "mCellConnectionStatus", connectionStatus);
        }
        return cellInfo;
    }

    /**
     * Android 13（API 33）起 CellInfoGsm/Lte/Nr 提供 hidden public 全参构造器：
     * {@code (int connectionStatus, boolean registered, long timeStamp, Identity, SignalStrength)}
     * （LTE 多一个 {@code CellConfigLte}）。优先用它；本版本没有该构造器 / 构造失败返回 null，
     * 由调用方降级为“无参 + setter/字段”。
     */
    private static Object tryBuildCellInfoWithPublicCtor(EnvSnapshot.CellSpec spec, Object identity,
            Object signalStrength, boolean serving) {
        final Class<?> infoClass = findClassIfExists(cellInfoClassName(spec.type), sClassLoader);
        final Class<?> identityClass = findClassIfExists(identityClassName(spec.type), sClassLoader);
        final Class<?> signalClass = findClassIfExists(signalClassName(spec.type), sClassLoader);
        if (infoClass == null || identityClass == null || signalClass == null) {
            return null;
        }
        final int connectionStatus = serving ? CONNECTION_PRIMARY_SERVING : CONNECTION_NONE;
        final long timestampNanos = SystemClock.elapsedRealtimeNanos();

        if ("lte".equals(spec.type)) {
            final Class<?> configClass =
                    findClassIfExists("android.telephony.CellConfigLte", sClassLoader);
            if (configClass != null) {
                Object config = newInstanceNoArg(configClass);
                if (config == null) {
                    config = newInstance(configClass, new Class<?>[]{boolean.class}, Boolean.FALSE);
                }
                if (config != null) {
                    final Object cellInfo = newInstance(infoClass,
                            new Class<?>[]{int.class, boolean.class, long.class,
                                    identityClass, signalClass, configClass},
                            connectionStatus, serving, timestampNanos, identity, signalStrength,
                            config);
                    if (cellInfo != null) {
                        return cellInfo;
                    }
                }
            }
        }
        return newInstance(infoClass,
                new Class<?>[]{int.class, boolean.class, long.class, identityClass, signalClass},
                connectionStatus, serving, timestampNanos, identity, signalStrength);
    }

    private static String cellInfoClassName(String type) {
        if ("gsm".equals(type)) {
            return "android.telephony.CellInfoGsm";
        }
        if ("lte".equals(type)) {
            return "android.telephony.CellInfoLte";
        }
        return "android.telephony.CellInfoNr";
    }

    private static String identityFieldName(String type) {
        if ("gsm".equals(type)) {
            return "mCellIdentityGsm";
        }
        if ("lte".equals(type)) {
            return "mCellIdentityLte";
        }
        return "mCellIdentity"; // CellInfoNr 的字段就叫 mCellIdentity
    }

    private static String signalFieldName(String type) {
        if ("gsm".equals(type)) {
            return "mCellSignalStrengthGsm";
        }
        if ("lte".equals(type)) {
            return "mCellSignalStrengthLte";
        }
        return "mCellSignalStrength";
    }

    // ---------------- CellIdentity ----------------

    private static Object buildCellIdentity(EnvSnapshot.CellSpec spec) {
        final Class<?> identityClass = findClassIfExists(identityClassName(spec.type), sClassLoader);
        if (identityClass == null) {
            return null;
        }
        final String mccStr = String.valueOf(spec.mcc);
        final String mncStr = String.valueOf(spec.mnc);
        final int lac = nonNegative(spec.lac);
        final int cid = nonNegative(spec.cid);
        final int tac = nonNegative(spec.tac);
        final int ci = nonNegative(spec.ci);
        final int pci = nonNegative(spec.pci);
        final int earfcn = nonNegative(spec.earfcn);
        final int arfcn = nonNegative(spec.arfcn);
        final int bsic = nonNegative(spec.bsic);

        if ("gsm".equals(spec.type)) {
            // Android 11+：9 参
            Object identity = newInstance(identityClass,
                    new Class<?>[]{int.class, int.class, int.class, int.class,
                            String.class, String.class, String.class, String.class, Collection.class},
                    lac, cid, arfcn, bsic, mccStr, mncStr, "", "", Collections.emptySet());
            if (identity != null) {
                applyLegacyMccMnc(identity, spec);
                return identity;
            }
            // Android 9/10：8 参
            identity = newInstance(identityClass,
                    new Class<?>[]{int.class, int.class, int.class, int.class,
                            String.class, String.class, String.class, String.class},
                    lac, cid, arfcn, bsic, mccStr, mncStr, "", "");
            if (identity != null) {
                applyLegacyMccMnc(identity, spec);
                return identity;
            }
            // Android 8.1/9：6 参 int，(mcc, mnc, lac, cid, arfcn, bsic)
            identity = newInstance(identityClass,
                    new Class<?>[]{int.class, int.class, int.class, int.class, int.class, int.class},
                    spec.mcc, spec.mnc, lac, cid, arfcn, bsic);
            if (identity != null) {
                return identity;
            }
            // Android 8.1/9：4 参 int，(mcc, mnc, lac, cid)
            identity = newInstance(identityClass,
                    new Class<?>[]{int.class, int.class, int.class, int.class},
                    spec.mcc, spec.mnc, lac, cid);
            if (identity != null) {
                return identity;
            }
            // 兜底：无参 + 旧版私有字段
            identity = newInstanceNoArg(identityClass);
            if (identity != null) {
                boolean any = setIntFieldIfExists(identity, "mMcc", spec.mcc);
                any |= setIntFieldIfExists(identity, "mMnc", spec.mnc);
                any |= setIntFieldIfExists(identity, "mLac", lac);
                any |= setIntFieldIfExists(identity, "mCid", cid);
                any |= setIntFieldIfExists(identity, "mArfcn", arfcn);
                any |= setIntFieldIfExists(identity, "mBsic", bsic);
                // Android 9+ 的 mcc/mnc 字符串字段在基类 CellIdentity 里
                any |= setObjectFieldIfExists(identity, "mMccStr", mccStr);
                any |= setObjectFieldIfExists(identity, "mMncStr", mncStr);
                return any ? identity : null;
            }
            return null;
        }

        if ("lte".equals(spec.type)) {
            // Android 11+：12 参（带 bands / additionalPlmns / csgInfo，csgInfo 可空）
            final Class<?> csgClass =
                    findClassIfExists("android.telephony.ClosedSubscriberGroupInfo", sClassLoader);
            if (csgClass != null) {
                Object identity = newInstance(identityClass,
                        new Class<?>[]{int.class, int.class, int.class, int.class, int[].class, int.class,
                                String.class, String.class, String.class, String.class,
                                Collection.class, csgClass},
                        ci, pci, tac, earfcn, new int[]{3}, 0,
                        mccStr, mncStr, "", "", Collections.emptySet(), null);
                if (identity != null) {
                    return identity;
                }
            }
            // Android 10：9 参
            Object identity = newInstance(identityClass,
                    new Class<?>[]{int.class, int.class, int.class, int.class, int.class,
                            String.class, String.class, String.class, String.class},
                    ci, pci, tac, earfcn, 0, mccStr, mncStr, "", "");
            if (identity != null) {
                return identity;
            }
            // Android 8.1：6 参 int，(mcc, mnc, ci, pci, tac, earfcn)
            identity = newInstance(identityClass,
                    new Class<?>[]{int.class, int.class, int.class, int.class, int.class, int.class},
                    spec.mcc, spec.mnc, ci, pci, tac, earfcn);
            if (identity != null) {
                return identity;
            }
            // Android 9/10：5 参 int，(mcc, mnc, ci, pci, tac)
            identity = newInstance(identityClass,
                    new Class<?>[]{int.class, int.class, int.class, int.class, int.class},
                    spec.mcc, spec.mnc, ci, pci, tac);
            if (identity != null) {
                return identity;
            }
            // 兜底：无参 + 旧版私有字段
            identity = newInstanceNoArg(identityClass);
            if (identity != null) {
                boolean any = setIntFieldIfExists(identity, "mMcc", spec.mcc);
                any |= setIntFieldIfExists(identity, "mMnc", spec.mnc);
                any |= setIntFieldIfExists(identity, "mCi", ci);
                any |= setIntFieldIfExists(identity, "mPci", pci);
                any |= setIntFieldIfExists(identity, "mTac", tac);
                any |= setIntFieldIfExists(identity, "mEarfcn", earfcn);
                any |= setObjectFieldIfExists(identity, "mMccStr", mccStr);
                any |= setObjectFieldIfExists(identity, "mMncStr", mncStr);
                return any ? identity : null;
            }
            return null;
        }

        // NR（Android 10+）
        final long nci = Math.max(0L, spec.nci);
        // Android 11+：10 参（bands / additionalPlmns）
        Object identity = newInstance(identityClass,
                new Class<?>[]{int.class, int.class, int.class, int[].class,
                        String.class, String.class, long.class, String.class, String.class, Collection.class},
                pci, tac, arfcn, new int[]{78}, mccStr, mncStr, nci, "", "", Collections.emptySet());
        if (identity != null) {
            return identity;
        }
        // Android 10：8 参
        identity = newInstance(identityClass,
                new Class<?>[]{int.class, int.class, int.class,
                        String.class, String.class, long.class, String.class, String.class},
                pci, tac, arfcn, mccStr, mncStr, nci, "", "");
        if (identity != null) {
            return identity;
        }
        // 兜底：无参 + 私有字段
        identity = newInstanceNoArg(identityClass);
        if (identity != null) {
            boolean any = setIntFieldIfExists(identity, "mPci", pci);
            any |= setIntFieldIfExists(identity, "mTac", tac);
            any |= setIntFieldIfExists(identity, "mNrArfcn", arfcn);
            any |= setLongFieldIfExists(identity, "mNci", nci);
            return any ? identity : null;
        }
        return null;
    }

    /**
     * Android 9 的 CellIdentityGsm 同时有 int mMcc/mMnc 和字符串字段（10+ 改成 mPlmn），
     * 用字符串构造器建完之后顺手补一下 int 字段，避免老 App 读 getMcc() 拿到 UNKNOWN。
     */
    private static void applyLegacyMccMnc(Object identity, EnvSnapshot.CellSpec spec) {
        setIntFieldIfExists(identity, "mMcc", spec.mcc);
        setIntFieldIfExists(identity, "mMnc", spec.mnc);
    }

    private static String identityClassName(String type) {
        if ("gsm".equals(type)) {
            return "android.telephony.CellIdentityGsm";
        }
        if ("lte".equals(type)) {
            return "android.telephony.CellIdentityLte";
        }
        return "android.telephony.CellIdentityNr";
    }

    // ---------------- CellSignalStrength ----------------

    private static Object buildSignalStrength(EnvSnapshot.CellSpec spec) {
        final Class<?> cls = findClassIfExists(signalClassName(spec.type), sClassLoader);
        if (cls == null) {
            return null;
        }
        final int rsrp = spec.rsrp;
        final int rsrq = spec.rsrq;
        final int rssnr = spec.rssnr;
        final int sinr = rssnr;

        if ("gsm".equals(spec.type)) {
            // Android 8/9 的构造器参数是 ASU(0-31, 99)，Android 10+ 才改成 dBm；
            // 传错单位会让 getDbm()/getLevel() 明显不合理（AOSP/CellSignalStrengthGsm 有注释）。
            final boolean asu = Build.VERSION.SDK_INT < 29;
            final int ssArg = asu ? asuFromDbm(spec.rssi) : spec.rssi;
            // Android 9+：3 参
            Object strength = newInstance(cls,
                    new Class<?>[]{int.class, int.class, int.class}, ssArg, 0, 0);
            if (strength != null) {
                return strength;
            }
            // Android 8.1 / 9：2 参
            strength = newInstance(cls, new Class<?>[]{int.class, int.class}, ssArg, 0);
            if (strength != null) {
                return strength;
            }
            strength = newInstanceNoArg(cls);
            if (strength != null) {
                boolean any = asu
                        ? setIntFieldIfExists(strength, "mSignalStrength", ssArg)
                        : setIntFieldIfExists(strength, "mRssi", spec.rssi);
                any |= setIntFieldIfExists(strength, "mBitErrorRate", 0);
                any |= setIntFieldIfExists(strength, "mTimingAdvance", 0);
                any |= setIntFieldIfExists(strength, "mLevel", levelFromRsrp(spec.rssi));
                return any ? strength : null;
            }
            return null;
        }

        if ("lte".equals(spec.type)) {
            // 同 GSM：API < 29 的第一个参数是 ASU，10+ 是 dBm
            final boolean asu = Build.VERSION.SDK_INT < 29;
            final int rssiArg = asu ? asuFromDbm(spec.rssi) : spec.rssi;
            // Android 12+：7 参（多一个 cqiTableIndex）
            Object strength = newInstance(cls,
                    new Class<?>[]{int.class, int.class, int.class, int.class, int.class, int.class, int.class},
                    rssiArg, rsrp, rsrq, rssnr, 0, 0, 0);
            if (strength != null) {
                return strength;
            }
            // Android 8.1~11：6 参
            strength = newInstance(cls,
                    new Class<?>[]{int.class, int.class, int.class, int.class, int.class, int.class},
                    rssiArg, rsrp, rsrq, rssnr, 0, 0);
            if (strength != null) {
                return strength;
            }
            strength = newInstanceNoArg(cls);
            if (strength != null) {
                boolean any = asu
                        ? setIntFieldIfExists(strength, "mSignalStrength", rssiArg)
                        : setIntFieldIfExists(strength, "mRssi", spec.rssi);
                any |= setIntFieldIfExists(strength, "mRsrp", rsrp);
                any |= setIntFieldIfExists(strength, "mRsrq", rsrq);
                any |= setIntFieldIfExists(strength, "mRssnr", rssnr);
                any |= setIntFieldIfExists(strength, "mCqi", 0);
                any |= setIntFieldIfExists(strength, "mTimingAdvance", 0);
                any |= setIntFieldIfExists(strength, "mLevel", levelFromRsrp(rsrp));
                return any ? strength : null;
            }
            return null;
        }

        // NR
        // Android 14/15：9 参（多 timingAdvance）
        Object strength = newInstance(cls,
                new Class<?>[]{int.class, int.class, int.class, int.class, List.class,
                        int.class, int.class, int.class, int.class},
                rsrp, rsrq, sinr, 0, Collections.emptyList(), rsrp, rsrq, sinr, 0);
        if (strength != null) {
            return strength;
        }
        // Android 12/13：8 参
        strength = newInstance(cls,
                new Class<?>[]{int.class, int.class, int.class, int.class, List.class,
                        int.class, int.class, int.class},
                rsrp, rsrq, sinr, 0, Collections.emptyList(), rsrp, rsrq, sinr);
        if (strength != null) {
            return strength;
        }
        // Android 10/11：6 参
        strength = newInstance(cls,
                new Class<?>[]{int.class, int.class, int.class, int.class, int.class, int.class},
                rsrp, rsrq, sinr, rsrp, rsrq, sinr);
        if (strength != null) {
            return strength;
        }
        strength = newInstanceNoArg(cls);
        if (strength != null) {
            boolean any = setIntFieldIfExists(strength, "mSsRsrp", rsrp);
            any |= setIntFieldIfExists(strength, "mSsRsrq", rsrq);
            any |= setIntFieldIfExists(strength, "mSsSinr", sinr);
            any |= setIntFieldIfExists(strength, "mCsiRsrp", rsrp);
            any |= setIntFieldIfExists(strength, "mCsiRsrq", rsrq);
            any |= setIntFieldIfExists(strength, "mCsiSinr", sinr);
            any |= setIntFieldIfExists(strength, "mLevel", levelFromRsrp(rsrp));
            any |= setIntFieldIfExists(strength, "mTimingAdvance", 0);
            return any ? strength : null;
        }
        return null;
    }

    private static String signalClassName(String type) {
        if ("gsm".equals(type)) {
            return "android.telephony.CellSignalStrengthGsm";
        }
        if ("lte".equals(type)) {
            return "android.telephony.CellSignalStrengthLte";
        }
        return "android.telephony.CellSignalStrengthNr";
    }

    // ==================================================================
    // CellLocation 回放（gsm 用 lac/cid，lte 用 tac/ci 映射）
    // ==================================================================

    private static Object buildCellLocation(EnvSnapshot snapshot) {
        EnvSnapshot.CellSpec chosen = null;
        for (EnvSnapshot.CellSpec spec : snapshot.cells) {
            if ("gsm".equals(spec.type)) {
                chosen = spec;
                break;
            }
        }
        if (chosen == null) {
            for (EnvSnapshot.CellSpec spec : snapshot.cells) {
                if ("lte".equals(spec.type)) {
                    chosen = spec;
                    break;
                }
            }
        }
        if (chosen == null) {
            return null; // 快照里没有 gsm/lte：等价于“取不到基站位置”
        }

        try {
            // GsmCellLocation 是公开 API（API 1+），setLacAndCid 直接可用
            final android.telephony.gsm.GsmCellLocation location =
                    new android.telephony.gsm.GsmCellLocation();
            final int lac;
            final int cid;
            if ("gsm".equals(chosen.type)) {
                lac = nonNegative(chosen.lac);
                cid = nonNegative(chosen.cid);
            } else {
                lac = nonNegative(chosen.tac);
                cid = nonNegative(chosen.ci);
            }
            location.setLacAndCid(lac, cid);
            return location;
        } catch (Throwable t) {
            log("replay: build GsmCellLocation failed: " + t);
            return null;
        }
    }

    // ==================================================================
    // Wi-Fi 回放
    // ==================================================================

    private static List<Object> buildScanResults(EnvSnapshot snapshot) {
        final List<Object> result = new ArrayList<>();
        final Class<?> scanResultClass =
                findClassIfExists("android.net.wifi.ScanResult", sClassLoader);
        if (scanResultClass == null) {
            return result;
        }
        final long timestampMicros = SystemClock.elapsedRealtimeNanos() / 1000L;
        for (EnvSnapshot.WifiSpec spec : snapshot.wifi) {
            final Object scanResult = newInstanceNoArg(scanResultClass);
            if (scanResult == null) {
                continue;
            }
            try {
                // 真机的 ScanResult.SSID 来自 WifiSsid.toString()（带双引号）；只写裸 SSID 会让
                // “读 SSID 字段的旧代码”和“读 getWifiSsid() 的新代码”看到不一致的结果。
                setObjectFieldIfExists(scanResult, "SSID",
                        spec.ssid == null ? "" : quoteSsid(spec.ssid));
                if (spec.bssid != null) {
                    setObjectFieldIfExists(scanResult, "BSSID", spec.bssid);
                }
                setIntFieldIfExists(scanResult, "level", spec.rssi);
                setIntFieldIfExists(scanResult, "frequency", spec.frequency);
                setIntFieldIfExists(scanResult, "centerFreq0", spec.frequency);
                setIntFieldIfExists(scanResult, "centerFreq1", 0);
                setIntFieldIfExists(scanResult, "channelWidth", 0);
                // 真机的 capabilities 至少是 "[ESS]"（开放网络），留空会让部分 App 的解析逻辑异常
                setObjectFieldIfExists(scanResult, "capabilities", "[ESS]");
                setLongFieldIfExists(scanResult, "timestamp", timestampMicros);
                setWifiSsidField(scanResult, spec.ssid);
                result.add(scanResult);
            } catch (Throwable ignored) {
                // 单条失败就跳过
            }
        }
        if (!result.isEmpty()) {
            logWifiReplayOnce();
        }
        return result;
    }

    /**
     * Android 12+ 的 ScanResult 多了一个私有 mWifiSsid；能构造就一并设置，
     * 让 getWifiSsid() 不为 null（构造失败忽略，不影响公开字段）。
     */
    private static void setWifiSsidField(Object scanResult, String ssid) {
        if (ssid == null || ssid.length() == 0) {
            return;
        }
        try {
            // Android 13+：ScanResult 有 public WifiSsid wifiSsid 字段（只写 SSID 会让 getWifiSsid() 返回 null，
            // 这本身就是“新旧 API 结果矛盾”的检测信号）；更老的版本字段名是 mWifiSsid，一起兼容。
            Field field = findFieldInHierarchy(scanResult.getClass(), "wifiSsid");
            if (field == null) {
                field = findFieldInHierarchy(scanResult.getClass(), "mWifiSsid");
            }
            if (field == null) {
                return;
            }
            final Class<?> wifiSsidClass =
                    findClassIfExists("android.net.wifi.WifiSsid", sClassLoader);
            if (wifiSsidClass == null) {
                return;
            }
            // API 33 是 public WifiSsid.fromBytes(byte[])；更早版本是 @hide createFromByteArray(byte[])
            java.lang.reflect.Method factory =
                    XposedHelpers.findMethodExactIfExists(wifiSsidClass, "fromBytes", byte[].class);
            if (factory == null) {
                factory = XposedHelpers.findMethodExactIfExists(
                        wifiSsidClass, "createFromByteArray", byte[].class);
            }
            if (factory == null) {
                return;
            }
            final Object wifiSsid = factory.invoke(null, (Object) ssid.getBytes("UTF-8"));
            if (wifiSsid != null) {
                field.setAccessible(true);
                field.set(scanResult, wifiSsid);
            }
        } catch (Throwable ignored) {
            // 忽略：公开字段 SSID 已经可用
        }
    }

    /** ASU → dBm 反向换算：ASU = (dBm + 113) / 2，范围 0~31（TS 27.007）。 */
    private static int asuFromDbm(int dbm) {
        if (dbm <= -113) {
            return 0;
        }
        if (dbm >= -51) {
            return 31;
        }
        return (dbm + 113) / 2;
    }

    private static String firstWifiBssid(EnvSnapshot snapshot) {
        for (EnvSnapshot.WifiSpec spec : snapshot.wifi) {
            if (spec.bssid != null) {
                return spec.bssid;
            }
        }
        return null;
    }

    private static String firstWifiSsidRaw(EnvSnapshot snapshot) {
        for (EnvSnapshot.WifiSpec spec : snapshot.wifi) {
            if (spec.ssid != null) {
                return spec.ssid;
            }
        }
        return null;
    }

    /** 真机 WifiInfo#getSSID() 返回带双引号的 SSID（如 "CMCC-Free"），这里保持一致。 */
    private static String quoteSsid(String ssid) {
        if (ssid == null) {
            return null;
        }
        if (ssid.length() >= 2 && ssid.startsWith("\"") && ssid.endsWith("\"")) {
            return ssid;
        }
        return "\"" + ssid + "\"";
    }

    // ==================================================================
    // 反射工具（全部吞异常）
    // ==================================================================

    private static Class<?> findClassIfExists(String className, ClassLoader classLoader) {
        try {
            return XposedHelpers.findClassIfExists(className,
                    classLoader != null ? classLoader : sClassLoader);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object newInstanceNoArg(Class<?> cls) {
        return newInstance(cls, new Class<?>[0]);
    }

    private static Object newInstance(Class<?> cls, Class<?>[] parameterTypes, Object... args) {
        if (cls == null) {
            return null;
        }
        try {
            final Constructor<?> constructor = cls.getDeclaredConstructor(parameterTypes);
            constructor.setAccessible(true);
            return constructor.newInstance(args);
        } catch (Throwable t) {
            return null; // 该版本没有这个构造器 / hidden API 受限
        }
    }

    private static boolean callMethodIfExists(Object target, String methodName, Object... args) {
        if (target == null) {
            return false;
        }
        try {
            XposedHelpers.callMethod(target, methodName, args);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Field findFieldInHierarchy(Class<?> cls, String fieldName) {
        Class<?> current = cls;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (Throwable ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private static boolean setObjectFieldIfExists(Object target, String fieldName, Object value) {
        try {
            final Field field = findFieldInHierarchy(target.getClass(), fieldName);
            if (field == null) {
                return false;
            }
            field.setAccessible(true);
            field.set(target, value);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean setIntFieldIfExists(Object target, String fieldName, int value) {
        try {
            final Field field = findFieldInHierarchy(target.getClass(), fieldName);
            if (field == null) {
                return false;
            }
            field.setAccessible(true);
            field.setInt(target, value);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean setLongFieldIfExists(Object target, String fieldName, long value) {
        try {
            final Field field = findFieldInHierarchy(target.getClass(), fieldName);
            if (field == null) {
                return false;
            }
            field.setAccessible(true);
            field.setLong(target, value);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean setBooleanFieldIfExists(Object target, String fieldName, boolean value) {
        try {
            final Field field = findFieldInHierarchy(target.getClass(), fieldName);
            if (field == null) {
                return false;
            }
            field.setAccessible(true);
            field.setBoolean(target, value);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static int nonNegative(int value) {
        return value < 0 ? 0 : value;
    }

    /** 没有构造器可用时给一个粗略的信号等级（0~4），仅用于字段兜底。 */
    private static int levelFromRsrp(int rsrp) {
        if (rsrp >= -85) {
            return 4;
        }
        if (rsrp >= -95) {
            return 3;
        }
        if (rsrp >= -105) {
            return 2;
        }
        if (rsrp >= -115) {
            return 1;
        }
        return 0;
    }

    // ==================================================================
    // 日志（每类首次命中打一条）
    // ==================================================================

    private static void logHideOnce() {
        if (sLoggedHide) {
            return;
        }
        sLoggedHide = true;
        log("env mode=hide, cell/wifi data hidden");
    }

    private static void logWifiReplayOnce() {
        if (sLoggedWifiReplay) {
            return;
        }
        sLoggedWifiReplay = true;
        log("env mode=replay, wifi snapshot replayed");
    }

    private static void log(String message) {
        try {
            XposedBridge.log(TAG + message);
        } catch (Throwable ignored) {
            // ignore
        }
    }
}
