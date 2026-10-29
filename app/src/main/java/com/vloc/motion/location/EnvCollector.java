package com.vloc.motion.location;

import android.content.Context;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.telephony.CellInfo;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.vloc.motion.core.EnvSnapshot;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;

/**
 * 采集设备周围的基站（CellInfo）与 Wi-Fi 扫描结果，生成环境快照。
 *
 * <p>大量使用反射读取 {@code CellIdentity*} / {@code CellSignalStrength*}，
 * 以兼容 API 26–35 的公开 getter 差异与部分 @hide 字段。所有失败都降级为"跳过该条"。
 */
public final class EnvCollector {

    private static final String TAG = "VLocEnv";

    private EnvCollector() {
    }

    /** @return 采集结果；完全取不到时返回空快照（不是 null） */
    public static EnvSnapshot collect(Context context, double lat, double lon) {
        EnvSnapshot snapshot = new EnvSnapshot();
        snapshot.lat = lat;
        snapshot.lon = lon;
        snapshot.updatedAt = System.currentTimeMillis();

        collectCells(context, snapshot);
        collectWifi(context, snapshot);
        return snapshot;
    }

    private static void collectCells(Context context, EnvSnapshot snapshot) {
        try {
            TelephonyManager telephony =
                    (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            if (telephony == null) {
                return;
            }
            parseOperator(telephony, snapshot);
            List<CellInfo> infos;
            try {
                infos = telephony.getAllCellInfo();
            } catch (SecurityException e) {
                Log.w(TAG, "getAllCellInfo denied: " + e.getMessage());
                return;
            }
            if (infos == null) {
                return;
            }
            for (CellInfo info : infos) {
                EnvSnapshot.Cell cell = toCell(info);
                if (cell != null) {
                    snapshot.cells.add(cell);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "cell collection failed", t);
        }
    }

    private static void collectWifi(Context context, EnvSnapshot snapshot) {
        try {
            WifiManager wifiManager = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifiManager == null) {
                return;
            }
            try {
                List<ScanResult> results = wifiManager.getScanResults();
                if (results != null) {
                    for (ScanResult result : results) {
                        if (result == null) {
                            continue;
                        }
                        EnvSnapshot.Wifi wifi = new EnvSnapshot.Wifi();
                        wifi.bssid = result.BSSID == null ? "" : result.BSSID;
                        wifi.ssid = result.SSID == null ? "" : result.SSID;
                        wifi.rssi = result.level;
                        wifi.frequency = result.frequency;
                        snapshot.wifi.add(wifi);
                    }
                }
            } catch (SecurityException e) {
                Log.w(TAG, "getScanResults denied: " + e.getMessage());
            }
            if (snapshot.wifi.isEmpty()) {
                WifiInfo info = wifiManager.getConnectionInfo();
                if (info != null && info.getBSSID() != null) {
                    EnvSnapshot.Wifi wifi = new EnvSnapshot.Wifi();
                    wifi.bssid = info.getBSSID();
                    wifi.ssid = info.getSSID() == null ? "" : info.getSSID();
                    wifi.rssi = info.getRssi();
                    wifi.frequency = info.getFrequency();
                    snapshot.wifi.add(wifi);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "wifi collection failed", t);
        }
    }

    private static void parseOperator(TelephonyManager telephony, EnvSnapshot snapshot) {
        String operator = telephony.getNetworkOperator();
        if (operator == null || operator.length() < 5) {
            operator = telephony.getSimOperator();
        }
        if (operator != null && operator.length() >= 5) {
            try {
                snapshot.mcc = Integer.parseInt(operator.substring(0, 3));
                snapshot.mnc = Integer.parseInt(operator.substring(3));
            } catch (NumberFormatException ignored) {
                // 忽略
            }
        }
    }

    private static EnvSnapshot.Cell toCell(CellInfo info) {
        if (info == null) {
            return null;
        }
        String type = info.getClass().getSimpleName().replace("CellInfo", "")
                .toLowerCase(Locale.US);
        if (type.isEmpty()) {
            type = "gsm";
        }
        Object identity = invoke(info, "getCellIdentity");
        if (identity == null) {
            identity = fieldByPrefix(info, "mCellIdentity");
        }
        Object signal = invoke(info, "getCellSignalStrength");
        if (signal == null) {
            signal = fieldByPrefix(info, "mCellSignalStrength");
        }

        EnvSnapshot.Cell cell = new EnvSnapshot.Cell();
        cell.type = type;
        cell.mcc = intValue(identity, "getMcc", "mMcc");
        cell.mnc = intValue(identity, "getMnc", "mMnc");
        cell.lac = intValue(identity, "getLac", "mLac");
        cell.cid = intValue(identity, "getCid", "mCid");
        cell.tac = intValue(identity, "getTac", "mTac");
        cell.ci = intValue(identity, "getCi", "mCi");
        cell.pci = intValue(identity, "getPci", "mPci");
        cell.earfcn = intValue(identity, "getEarfcn", "mEarfcn");
        cell.bsic = intValue(identity, "getBsic", "mBsic");
        cell.nci = longValue(identity, "getNci", "mNci");
        if ("wcdma".equals(type)) {
            cell.pci = intValue(identity, "getPsc", "mPsc");
        }
        cell.rssi = intValue(signal, "getRssi", "mRssi");
        cell.rsrp = intValue(signal, "getRsrp", "mRsrp");
        cell.rsrq = intValue(signal, "getRsrq", "mRsrq");
        cell.rssnr = intValue(signal, "getRssnr", "mRssnr");
        cell.level = intValue(signal, "getLevel", "mLevel");
        return cell;
    }

    private static Object invoke(Object target, String methodName) {
        if (target == null) {
            return null;
        }
        try {
            Method method = target.getClass().getMethod(methodName);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object fieldByPrefix(Object target, String prefix) {
        if (target == null) {
            return null;
        }
        for (Class<?> type = target.getClass(); type != null && type != Object.class;
             type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (field.getName().startsWith(prefix)) {
                    try {
                        field.setAccessible(true);
                        return field.get(target);
                    } catch (Throwable ignored) {
                        // 继续找
                    }
                }
            }
        }
        return null;
    }

    private static int intValue(Object target, String methodName, String fieldName) {
        if (target == null) {
            return 0;
        }
        Object value = invoke(target, methodName);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        Object fieldValue = field(target, fieldName);
        return fieldValue instanceof Number ? ((Number) fieldValue).intValue() : 0;
    }

    private static long longValue(Object target, String methodName, String fieldName) {
        if (target == null) {
            return 0L;
        }
        Object value = invoke(target, methodName);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        Object fieldValue = field(target, fieldName);
        return fieldValue instanceof Number ? ((Number) fieldValue).longValue() : 0L;
    }

    private static Object field(Object target, String fieldName) {
        for (Class<?> type = target.getClass(); type != null && type != Object.class;
             type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            } catch (Throwable ignored) {
                // 继续找父类
            }
        }
        return null;
    }
}
