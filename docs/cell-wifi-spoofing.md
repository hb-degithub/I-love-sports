# 基站 / Wi-Fi 指纹采集与回放（Android 8–15）

> 面向「虚拟运动 root 版」的**环境快照**能力：App 采集周围基站（CellInfo）与 Wi-Fi 扫描结果 → JSON 快照 → ContentProvider 暴露 → LSPosed 模块在目标 App 进程内回放。
> 本文只写文档与思路，不含 `app/`、`hidden/` 的源码改动。
>
> 证据分级：
> - 【已验证-源码】= 读过对应 AOSP tag 的源码（或 AOSP 官方 API 签名文件 `api/current.txt`）
> - 【已验证-文档】= 官方文档原文
> - 【推断】= 基于机制推理 / 社区实践，未在真机逐版本复现
>
> **本文最重要的一句话**：`CellInfo*` / `CellIdentity*` / `CellSignalStrength*` 这两个家族**在所有版本（8/12/15 逐版本核对）的 SDK 里都没有任何 public 构造器**，回放侧**必须走反射**；而 `ScanResult` 和 `GsmCellLocation` 是**可以直接 new 的公开 API**。这个不对称决定了整个方案的形态。

---

## 0. 结论速查

| 结论 | 证据 |
|---|---|
| `getAllCellInfo()` 权限：Android 8/9 只需 `ACCESS_COARSE_LOCATION`，**Android 10 起改为 `ACCESS_FINE_LOCATION`** | 【已验证-源码】TelephonyManager 8/9/10/12/15 |
| `getAllCellInfo()` 在 Android 15 上还带 `@RequiresFeature(FEATURE_TELEPHONY_RADIO_ACCESS)`，无该 feature 抛 `UnsupportedOperationException`（Android 12 上还没有这个注解，中间版本未逐一核对） | 【已验证-源码】12/15 |
| `getAllCellInfo()` 取不到时返回 **`null`**（不是空列表），部分字段用 `CellInfo.UNAVAILABLE = Integer.MAX_VALUE` 占位 | 【已验证-源码】javadoc + `CellInfo.UNAVAILABLE` |
| `WifiManager.getScanResults()` 要 `ACCESS_WIFI_STATE` + `ACCESS_FINE_LOCATION`，且**定位总开关必须打开** | 【已验证-源码】WifiManager 15 |
| `ScanResult` 公开无参构造器 `ScanResult()` **Android 11（API 30）才进 SDK**；API 26–29 上构造器存在但 `@hide`，**反射可调**（不是没有） | 【已验证-源码】api 8/10/11/12 + ScanResult 8 源码 L547 |
| `ScanResult` 的 `SSID/BSSID/level/frequency/timestamp/capabilities` 从 **Android 8 起就是 public 字段** → 字段赋值零反射 | 【已验证-源码】api8 |
| Android 12+ `ScanResult` 有 `SSID` 与 `wifiSsid` **两个载体**（`wifiSsid` 字段 Android 12 才有），只设 `SSID` 会让 `getWifiSsid()` 返回 null | 【已验证-源码】ScanResult 12 L51/L57、15 |
| `GsmCellLocation` **不需要反射**：`new GsmCellLocation()` + `setLacAndCid(lac,cid)` + `setPsc(psc)` 全是公开 API | 【已验证-源码】AOSP api15 + GsmCellLocation 源码 |
| `WifiInfo` 在 SDK 里**没有公开构造器**，源码里是无注解的 `@hide public WifiInfo()` → 建议直接 hook getter | 【已验证-源码】 |
| `CellInfo*` 家族在 API 8/12/15 的签名文件里 **ctor 数 = 0** | 【已验证-源码】`frameworks/base/api/current.txt`、`core/api/current.txt` |
| **Android 13（API 33）起** `CellInfoGsm/Lte/Nr/Wcdma` 都有 `(connectionStatus, registered, timeStamp, identity, signalStrength[, cellConfig])` 构造器（hidden public，反射可调），比逐字段反射稳；12 及以下只能用 no-arg + 逐字段 | 【已验证-源码】12/13/14/15 |
| `CellInfoNr` 的字段名是 `mCellIdentity` / `mCellSignalStrength`（**不带 Nr 后缀**），且 `mCellSignalStrength` 是 `final` | 【已验证-源码】 |
| 反射构造受 non-SDK 限制：targetSdk 决定哪些成员可用；被拦时表现为"方法不存在/IllegalAccess"而不是崩溃 | 【已验证-文档】 |
| **推荐的架构**：App 只传纯字段 JSON，模块侧反射构造；不要传序列化后的 CellInfo | 【推断】（本文 §3.2 论证） |

---

## 1. 数据模型总览

### 1.1 五种 CellInfo 及其"两件套"结构

每一个 `CellInfo*` 都是「身份 + 信号强度」的组合，身份和信号强度各有独立类：

| CellInfo 子类 | 身份类 | 信号强度类 | 引入版本 | 字段名（CellInfo 内） |
|---|---|---|---|---|
| `CellInfoGsm` | `CellIdentityGsm` | `CellSignalStrengthGsm` | API 17 | `mCellIdentityGsm`, `mCellSignalStrengthGsm` |
| `CellInfoWcdma` | `CellIdentityWcdma` | `CellSignalStrengthWcdma` | API 18 | `mCellIdentityWcdma`, `mCellSignalStrengthWcdma` |
| `CellInfoLte` | `CellIdentityLte` | `CellSignalStrengthLte` | API 17 | `mCellIdentityLte`, `mCellSignalStrengthLte`（+ `mCellConfig`，见 §3.4） |
| `CellInfoCdma` | `CellIdentityCdma` | `CellSignalStrengthCdma` | API 17 | `mCellIdentityCdma`, `mCellSignalStrengthCdma` |
| `CellInfoNr` | `CellIdentityNr` | `CellSignalStrengthNr` | **API 29（Android 10）** | `mCellIdentity`, `mCellSignalStrength` ⚠️ 命名不同 |

【已验证-源码】各 `CellInfo*` 源码；NR 的字段名差异见 `CellInfoNr.java`（android-12/15）

### 1.2 身份字段 vs 信号字段（决定了 JSON 怎么切）

| 类别 | 字段 | 备注 |
|---|---|---|
| **身份（定位指纹）** | `mcc`, `mnc`, `lac`, `cid`, `tac`, `ci`, `pci`, `earfcn`, `nci`, `nrarfcn`, `uarfcn`, `psc`, `bsic`, `arfcn`, `bands` | 这些是"能反查地理位置"的东西，也是检测方最关心的 |
| **信号强度（物理观测值）** | `rssi`, `ber`, `rsrp`, `rsrq`, `rssnr`, `cqi`, `ssRsrp`, `ssRsrq`, `ssSinr`, `csiRsrp`, `csiRsrq`, `csiSinr`, `timingAdvance`, `level` | 数值必须**单调合理**，不能出现 "满格但 -120dBm" |
| **元信息** | `registered`, `connectionStatus`, `timeStamp`, `CellConfigLte.isEndcAvailable` | 决定"这是服务小区还是邻区" |

### 1.3 建议的 JSON 形态（给 Lead 定 schema 用）

```json
{
  "version": 1,
  "capturedAt": 1730000000000,
  "device": {"sdk": 34, "model": "..."},
  "cells": [
    {
      "type": "gsm",
      "registered": true,
      "connectionStatus": 1,
      "timestamp": 1730000000000,
      "mcc": "460", "mnc": "00",
      "lac": 12345, "cid": 6789, "arfcn": 60, "bsic": 12,
      "rssi": -75, "ber": 99, "ta": 0
    },
    {
      "type": "lte",
      "registered": true,
      "connectionStatus": 1,
      "timestamp": 1730000000000,
      "mcc": "460", "mnc": "00",
      "ci": 123456789, "pci": 123, "tac": 4567, "earfcn": 1650,
      "bandwidth": 20000, "bands": [3],
      "rssi": -60, "rsrp": -95, "rsrq": -10, "rssnr": 12, "cqi": 10, "ta": 1
    },
    {
      "type": "nr",
      "registered": true,
      "connectionStatus": 1,
      "timestamp": 1730000000000,
      "mcc": "460", "mnc": "00",
      "nci": 1234567890, "pci": 321, "tac": 4567, "nrarfcn": 627264,
      "bands": [78],
      "ssRsrp": -92, "ssRsrq": -11, "ssSinr": 8,
      "csiRsrp": -95, "csiRsrq": -12, "csiSinr": 6, "ta": 0
    }
  ],
  "wifis": [
    {
      "ssid": "CMCC-Office",
      "bssid": "3c:46:d8:11:22:33",
      "level": -58,
      "frequency": 2437,
      "capabilities": "[WPA2-PSK-CCMP][ESS]",
      "timestamp": 1730000000000,
      "channelWidth": 0,
      "centerFreq0": 0, "centerFreq1": 0
    }
  ]
}
```

> ⚠️ `mcc`/`mnc` 在 Android 9+ 内部是 **String**（`mMccStr`/`mMncStr`），在 8 是 int。JSON 里统一用字符串最省事，模块侧按版本转 int 或 String。

---

## 2. 采集侧（App）

### 2.1 权限矩阵（Android 8 → 15）

| API | Android 8/9 | Android 10+ | Android 15 额外 | 证据 |
|---|---|---|---|---|
| `TelephonyManager.getAllCellInfo()` | `ACCESS_COARSE_LOCATION` | **`ACCESS_FINE_LOCATION`** | `@RequiresFeature(FEATURE_TELEPHONY_RADIO_ACCESS)`，否则抛 `UnsupportedOperationException` | 【已验证-源码】TM 8 L3571 / 9 L4394 / 10 L5225 / 12 L6225 / 15 L7027 |
| `TelephonyManager.getCellLocation()` | `anyOf(ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION)`，且**早已 `@Deprecated`** | 同 | — | 【已验证-源码】TM 8 L1114 |
| `TelephonyManager.getNetworkOperator()` | **无权限注解** | 无 | — | 【已验证-源码】TM 12 L2634 |
| `TelephonyManager.getSimOperator()` | 无权限，仅 `@RequiresFeature(FEATURE_TELEPHONY_SUBSCRIPTION)` | 同 | — | 【已验证-源码】TM 15 L4026 |
| `WifiManager.getScanResults()` | `ACCESS_WIFI_STATE` + `ACCESS_FINE_LOCATION` | 同 | — | 【已验证-源码】WM 15 L4532 |
| `WifiManager.getConnectionInfo()` | `ACCESS_WIFI_STATE` + `ACCESS_FINE_LOCATION`（`conditional = true`），**15 起 `@Deprecated`** | 同 | — | 【已验证-源码】WM 15 L4505 |

**结论**：
1. **`READ_PHONE_STATE` 不是必需**。采集侧只需要定位权限；`getNetworkOperator` / `getSimOperator` 都不需要手机状态权限。
2. `READ_BASIC_PHONE_STATE`（Android 13 引入的"基础手机状态"）只用在一部分 `READ_PHONE_STATE` 的替代场景（源码里形式为 `@RequiresPermission(anyOf={READ_PHONE_STATE, READ_BASIC_PHONE_STATE})`），**与本功能无关，不要申请**。【已验证-源码】TM 15 L2150/L3274/L3323
3. 后台采集还需要 `ACCESS_BACKGROUND_LOCATION`（Android 10+），否则锁屏后 `getAllCellInfo` 会被返回 `null`/空。【推断】

### 2.2 运行时形态（无 SIM / 飞行模式 / 5G）

| 场景 | `getAllCellInfo()` 返回 | 说明 |
|---|---|---|
| 无 SIM / 无服务 | `null` 或空列表 | javadoc："null if cell information is unavailable"【已验证-源码】；具体是 null 还是空列表随 ROM【推断】 |
| 飞行模式 | 通常 `null`/空 | 射频关闭后 modem 无测量结果【推断】 |
| 双卡 | 两个卡的 CellInfo 混在一个 list 里 | 无法从 `CellInfo` 直接区分卡槽（`CellIdentity` 没有 subId），只能靠 `SubscriptionManager` 交叉推断【推断】 |
| 5G NSA | 可能同时有 LTE + NR 条目 | NR 条目只在 **API 29+** 且驻留 NR 时出现；**Android 8/9 完全没有 `CellInfoNr` 类** |
| 部分字段缺失 | 用 `CellInfo.UNAVAILABLE = Integer.MAX_VALUE` 填充 | 常量值 `0x7fffffff`【已验证-源码】`CellInfo.UNAVAILABLE` |

**跨版本回放的坑**：在 Android 14 上采到的含 NR 的快照，回放到 Android 8 设备上必须**丢弃 NR 条目**（那边没有这个类，反射会 `ClassNotFoundException`）。

### 2.3 `getScanResults()` 的节流与前提

- 需要 **定位总开关打开** + `ACCESS_FINE_LOCATION` 已授予；否则返回空列表（不抛异常）。【推断，社区一致结论】
- Android 9（P）起对**主动扫描**做了节流：前台应用 **2 分钟内最多 4 次** `startScan()`，超出的调用返回 `false` 且不产生新结果。【推断】（社区共识 + Android 9 行为变更；AOSP 在 `WifiScanRequestProxy` 里实现，但本次未取到该文件对应 tag 的源码，见 §7 待验证项）
- **`getScanResults()` 本身不触发扫描**，只是取"最近一次扫描的缓存"，所以**采集时优先用 `getScanResults()`，不要反复 `startScan()`**。
- Android 13+ 新增 `NEARBY_WIFI_DEVICES` 权限：`WifiManager` 里多个"附近 Wi-Fi"接口带 `@RequiresPermission(NEARBY_WIFI_DEVICES)`（源码 L2305/L2351/L4631）；`getScanResults()` 的注解仍是 `ACCESS_WIFI_STATE + ACCESS_FINE_LOCATION`。【已验证-源码】是否在运行时额外强制取决于 ROM【推断】→ 建议 manifest 里**一并声明** `NEARBY_WIFI_DEVICES`（`android:maxSdkVersion` 不用限）并在 13+ 动态申请。
- `getConnectionInfo()` 是"兜底"：只能拿到**当前已连接**的那一个 AP，**不适合做指纹**（太稀疏），但可以在扫描被节流时至少保证 BSSID 非空。

### 2.4 `ScanResult` 的 public 字段（可直接读写）

【已验证-源码】Wifi 模块 `ScanResult.java`（android-15）+ `api/current.txt`

| 字段 | 类型 | 说明 |
|---|---|---|
| `SSID` | `String` | **从 Android 8 起就是 public**；13+ 标 `@Deprecated` 但仍可读。**值带双引号**（`"\"MyWiFi\""`），因为由 `WifiSsid.toString()` 派生【已验证-源码 SR12 L1079】 |
| `wifiSsid` | `WifiSsid` | **Android 12 才有的 public 字段**（带 `@UnsupportedAppUsage`）；`getWifiSsid()` 直接返回它，**不会回退到 `SSID`** |
| `BSSID` | `String` | `"3c:46:d8:11:22:33"` 形式 |
| `capabilities` | `String` | 如 `"[WPA2-PSK-CCMP][ESS]"` |
| `level` | `int` | dBm |
| `frequency` | `int` | MHz |
| `centerFreq0` / `centerFreq1` | `int` | 带宽中心频率，20MHz 时通常 0 |
| `channelWidth` | `int` | `CHANNEL_WIDTH_20MHZ=0` … `320MHZ=5` |
| `timestamp` | `long` | 微秒（`SystemClock.elapsedRealtime()*1000` 量级） |
| `operatorFriendlyName` / `venueName` | `CharSequence` | Passpoint 用，`@Deprecated` |
| `CREATOR` | `Parcelable.Creator<ScanResult>` | public，可 Parcel 透传 |

⚠️ **`timestamp` 的单位是微秒**（`elapsedRealtime` 基），不是毫秒。写错会被"时间戳异常"类检测抓。

### 2.5 采集实现要点（伪代码）

```java
// 1) 基站
List<CellInfo> cells = null;
if (pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
    try {
        cells = tm.getAllCellInfo();          // 可能为 null
    } catch (SecurityException | UnsupportedOperationException e) {
        cells = null;                          // 权限不足 / 无 radio
    }
}
// 2) Wi-Fi
List<ScanResult> aps = wm.getScanResults();    // 不触发扫描；可能为空
if (aps.isEmpty()) {
    WifiInfo ci = wm.getConnectionInfo();      // 兜底：只有一个
    if (ci != null && ci.getBSSID() != null) { /* 转成一条 ScanResult 记录 */ }
}
// 3) 归一化：把 UNAVAILABLE 统一成 null，避免 JSON 里出现 2147483647
// 4) 只保留需要的字段（见 §6 最小字段集）
```

**采集时机建议**：进入"模拟"前采一次即可；若要更真实，可每隔 N 分钟重采并在快照里保留多份（检测方看到"基站列表 10 分钟没变"反而可疑）。

---

## 3. 注入侧（模块回放）★核心

### 3.1 为什么必须反射：SDK 里根本没有构造器

我在 AOSP 的 API 签名文件里逐个核对了 **API 8 / 12 / 15** 三个版本：

| 类 | API 8 public ctor | API 12 public ctor | API 15 public ctor |
|---|---|---|---|
| `CellInfoGsm` / `CellInfoLte` / `CellInfoNr` | 0 | 0 | 0 |
| `CellIdentityGsm` / `CellIdentityLte` / `CellIdentityNr` | 0 | 0 | 0 |
| `CellSignalStrengthGsm` / `Lte` / `Nr` | 0 | 0 | 0 |
| `GsmCellLocation` | `GsmCellLocation()`, `GsmCellLocation(Bundle)` | 同（`@Deprecated`） | 同（`@Deprecated`） |
| `ScanResult`（`android.net.wifi`） | **0**（源码里有 `@hide public ScanResult()`，反射可调） | 2（`ScanResult()`、`ScanResult(ScanResult)`，**API 30 起**） | 同 |

【已验证-源码】`frameworks/base/api/current.txt`（8/10）、`frameworks/base/core/api/current.txt`（12/15）、`packages/modules/Wifi/framework/api/current.txt`（12/13/14/15）

**结论**：`CellInfo*` 三大家族**在所有版本都必须反射**；`GsmCellLocation` 可以直接 new；`ScanResult` **API 30+ 直接 new，26–29 用反射**（构造器存在，只是没进 SDK）。

### 3.2 两种架构，选一个

| 方案 | 做法 | 优点 | 缺点 | 建议 |
|---|---|---|---|---|
| **A. 传字段，模块构造** | App 只把 int/String 塞进 JSON；模块在目标进程里反射构造 `CellInfo` | schema 稳定、可读、跨版本过滤容易、App 侧零反射 | 模块侧反射逻辑要覆盖 8–15 | ✅ **主推** |
| B. 传 Parcel 字节 | App 侧构造 `CellInfo` → `Parcel.marshall()` → base64 进 JSON；模块 `CellInfo.CREATOR.createFromParcel()` 还原 | 模块侧零反射 | **App 是普通应用，同样受 non-SDK 限制，构造不出来**；除非用 root `app_process` 代劳 | 不推荐（除非已有 root 辅助进程） |

> 方案 B 唯一的甜点是：`CellInfo.CREATOR` 是公开字段，`writeToParcel` 是公开方法，**传输层完全合法**。如果将来 App 侧能通过 root helper 造出对象，B 会比 A 稳。但现阶段 App 拿不到 hidden API，A 更现实。

### 3.3 通用反射骨架（模块侧）

```java
// ---- 1) 统一入口：拿到 hidden 构造器/字段 ----
static Object newInstance(Class<?> c, Class<?>[] sig, Object... args) throws Exception {
    Constructor<?> k = c.getDeclaredConstructor(sig);
    k.setAccessible(true);
    return k.newInstance(args);
}
static void setField(Object target, String name, Object value) throws Exception {
    Class<?> c = target.getClass();
    while (c != null) {                       // 必须向上找：mcc/mnc 在基类 CellIdentity
        try {
            Field f = c.getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);             // final 实例字段在 ART 上 setAccessible 后可写
            return;
        } catch (NoSuchFieldException e) { c = c.getSuperclass(); }
    }
    throw new NoSuchFieldException(name);
}

// ---- 2) 统一捕获：任何构造失败都降级，不要崩 ----
static CellInfo safe(CellInfoBuilder b) {
    try { return b.build(); }
    catch (Throwable t) { Log.w(TAG, "build cell failed: " + t); return null; }
}
```

**反射顺序（推荐）**：
1. 优先用**带全参的 hidden 构造器**（字段一次到位、不碰 final）；
2. 构造器不存在（版本差异）→ 退回 `new X()` + 逐字段 `setField`；
3. 字段也写不进去（final/被拦）→ 退回"只设置能设的字段"，让对象带着默认值返回；
4. 整条链失败 → 该条目丢弃，**不要**把半成品塞进 list。

### 3.4 GSM 配方

#### 身份：`CellIdentityGsm`

| Android | 可用构造器（hidden public） | 证据 |
|---|---|---|
| 8 | `CellIdentityGsm(int mcc, int mnc, int lac, int cid)`<br>`CellIdentityGsm(int mcc, int mnc, int lac, int cid, int arfcn, int bsic)` | 【已验证-源码】CIG_8 |
| 9 | 同上（内部转成 String 形式）+ `(int lac,int cid,int arfcn,int bsic,String mccStr,String mncStr,String alphal,String alphas)` | 【已验证-源码】CIG_9 |
| **10–15** | **4 参/6 参版本已删除**；只剩<br>`CellIdentityGsm(int lac, int cid, int arfcn, int bsic, String mccStr, String mncStr, String alphal, String alphas)` | 【已验证-源码】CIG_10/11/12/13/14/15 |

```java
// Android 10–15
Object idGsm = newInstance(Class.forName("android.telephony.CellIdentityGsm"),
        new Class[]{int.class,int.class,int.class,int.class,
                    String.class,String.class,String.class,String.class},
        lac, cid, arfcn, bsic, "460", "00", null, null);

// Android 8–9
Object idGsm = newInstance(Class.forName("android.telephony.CellIdentityGsm"),
        new Class[]{int.class,int.class,int.class,int.class},
        Integer.parseInt(mcc), Integer.parseInt(mnc), lac, cid);

// 兜底：no-arg + 逐字段（注意 mcc/mnc 在基类 CellIdentity）
Object idGsm = newInstance(Class.forName("android.telephony.CellIdentityGsm"), new Class[]{});
setField(idGsm, "mLac",  lac);     setField(idGsm, "mCid",  cid);
setField(idGsm, "mArfcn", arfcn);  setField(idGsm, "mBsic", bsic);
setField(idGsm, "mMccStr", "460"); setField(idGsm, "mMncStr", "00");  // 基类 CellIdentity
```

**字段名（反射用）**：

| 类 | 字段 | 类型 | 备注 |
|---|---|---|---|
| `CellIdentityGsm` | `mLac`, `mCid`, `mArfcn`, `mBsic` | `private final int` | 10+ 都在本类 |
| `CellIdentityGsm`（8 专有） | `mMcc`, `mMnc` | `private final int` | 8 里 mcc/mnc 是 int |
| `CellIdentity`（基类，9+） | `mMccStr`, `mMncStr` | `protected final String` | ⚠️ **9+ 必须写基类**，否则 `getMcc()/getMccString()` 返回 UNAVAILABLE |
| `CellIdentity`（基类） | `mType`, `mTag`, `mAlphaLong`, `mAlphaShort`, `mGlobalCellId` | — | `mType` 会影响 `getType()`，**别乱改** |

#### 信号强度：`CellSignalStrengthGsm`

| Android | 构造器 | 证据 |
|---|---|---|
| **8** | `CellSignalStrengthGsm(int ss /*asu 0-31*/, int ber)` ← **2 参，且第一个是 ASU 不是 dBm** | 【已验证-源码】CSSG_8 |
| **10–15** | `CellSignalStrengthGsm(int rssi /*dBm*/, int ber, int ta)` | 【已验证-源码】CSSG_10/12/15 |

```java
Object ssGsm;
if (Build.VERSION.SDK_INT >= 29) {
    ssGsm = newInstance(Class.forName("android.telephony.CellSignalStrengthGsm"),
            new Class[]{int.class,int.class,int.class}, rssiDbm, ber, ta);
} else {
    int asu = (rssiDbm <= -113) ? 0 : (rssiDbm >= -51) ? 31 : (int)((rssiDbm + 113) / 2);
    ssGsm = newInstance(Class.forName("android.telephony.CellSignalStrengthGsm"),
            new Class[]{int.class,int.class}, asu, ber);
}
```

**字段名**：`mRssi`, `mBitErrorRate`, `mTimingAdvance`, `mLevel`（10+）；`mSignalStrength`, `mBitErrorRate`, `mTimingAdvance`（8）。
> `getRssi()` 的**公开 API**是 **Android 12（API 31）**才有的（Android 10/11 源码里有该方法但 `@hide`；Android 8 连方法都没有）。所以 8 上必须自己把 dBm 换成 ASU，**不能直接塞 dBm**。【已验证-源码】api8/api10/api12 `CellSignalStrengthGsm`（10 的公开 API 里只有 `getBitErrorRate/getDbm/getLevel/getTimingAdvance`）

#### 组装：`CellInfoGsm`

| Android | 构造器 | 证据 |
|---|---|---|
| 8–12 | `CellInfoGsm()`（hidden public）+ `CellInfoGsm(CellInfoGsm)` | 【已验证-源码】CIGsm_8/10/12 |
| **13 / 14 / 15** | `CellInfoGsm(int connectionStatus, boolean registered, long timeStamp, CellIdentityGsm, CellSignalStrengthGsm)` ← **首选** | 【已验证-源码】CIGsm_13/14/15 |

```java
// Android 13+：一步到位
Object ciGsm = newInstance(Class.forName("android.telephony.CellInfoGsm"),
        new Class[]{int.class, boolean.class, long.class,
                    Class.forName("android.telephony.CellIdentityGsm"),
                    Class.forName("android.telephony.CellSignalStrengthGsm")},
        CellInfo.CONNECTION_PRIMARY_SERVING, true, System.currentTimeMillis(), idGsm, ssGsm);

// Android 8–12：no-arg + 两个字段 + 基类三个字段
Object ciGsm = newInstance(Class.forName("android.telephony.CellInfoGsm"), new Class[]{});
setField(ciGsm, "mCellIdentityGsm",       idGsm);
setField(ciGsm, "mCellSignalStrengthGsm", ssGsm);
setField(ciGsm, "mRegistered",            true);                       // 基类 CellInfo
setField(ciGsm, "mCellConnectionStatus",  CellInfo.CONNECTION_PRIMARY_SERVING); // 10+ 才有
setField(ciGsm, "mTimeStamp",             System.currentTimeMillis()); // 基类 CellInfo
```

### 3.5 LTE 配方

#### `CellIdentityLte`

| Android | 构造器（hidden public） | 证据 |
|---|---|---|
| 8 | `(int mcc,int mnc,int ci,int pci,int tac)` / `+earfcn` 6 参 | 【已验证-源码】CIL_8 |
| 10 | 5 参仍在（`@UnsupportedAppUsage(maxTargetSdk=P)`）+ `(ci,pci,tac,earfcn,bandwidth,mccStr,mncStr,...)` | 【已验证-源码】CIL_10 |
| 12 | 5 参仍在 + `(ci,pci,tac,earfcn,int[] bands,bandwidth,mccStr,mncStr,alphal,alphas,Collection,csgInfo)` | 【已验证-源码】CIL_12 |
| 15 | 同 12 | 【已验证-源码】CIL_15 |

```java
// 最省事：5 参（8–15 都有，但 maxTargetSdk=P → 高 targetSdk 时可能被 non-SDK 限制拦）
Object idLte = newInstance(Class.forName("android.telephony.CellIdentityLte"),
        new Class[]{int.class,int.class,int.class,int.class,int.class},
        Integer.parseInt(mcc), Integer.parseInt(mnc), ci, pci, tac);

// 稳一点：12+ 的全参构造器（后面 6 个参数给默认值即可）
Object idLte = newInstance(Class.forName("android.telephony.CellIdentityLte"),
        new Class[]{int.class,int.class,int.class,int.class,int[].class,int.class,
                    String.class,String.class,String.class,String.class,
                    java.util.Collection.class,
                    Class.forName("android.telephony.ClosedSubscriberGroupInfo")},
        ci, pci, tac, earfcn, new int[]{band}, bandwidth,
        mcc, mnc, null, null, java.util.Collections.emptyList(), null);
```

**字段名**：`mCi`, `mPci`, `mTac`, `mEarfcn`, `mBandwidth`（8–11）、`mBands`（12+）、`mAdditionalPlmns`、`mCsgInfo`；`mcc/mnc` 同 GSM，在基类 `mMccStr/mMncStr`（9+）或本类 `mMcc/mMnc`（8）。

#### `CellSignalStrengthLte`

| Android | 构造器 | 备注 |
|---|---|---|
| 8 | `(int signalStrength, int rsrp, int rsrq, int rssnr, int cqi, int ta)` | 第 1 个是 ASU 风格 `mSignalStrength` |
| 10 | `(int rssi, int rsrp, int rsrq, int rssnr, int cqi, int ta)` | 改成 dBm `mRssi` |
| **12–15** | `(int rssi, int rsrp, int rsrq, int rssnr, int **cqiTableIndex**, int cqi, int ta)` ← **7 参** | 多了一个 cqiTableIndex，**别传错位数** |

【已验证-源码】CSSL_8/10/12/15

```java
Object ssLte;
Class<?> c = Class.forName("android.telephony.CellSignalStrengthLte");
if (Build.VERSION.SDK_INT >= 31) {          // 12+
    ssLte = newInstance(c, new Class[]{int.class,int.class,int.class,int.class,
            int.class,int.class,int.class}, rssi, rsrp, rsrq, rssnr, 1 /*cqiTableIndex*/, cqi, ta);
} else if (Build.VERSION.SDK_INT >= 29) {   // 10–11
    ssLte = newInstance(c, new Class[]{int.class,int.class,int.class,int.class,
            int.class,int.class}, rssi, rsrp, rsrq, rssnr, cqi, ta);
} else {                                    // 8–9
    ssLte = newInstance(c, new Class[]{int.class,int.class,int.class,int.class,
            int.class,int.class}, asuFromRssi(rssi), rsrp, rsrq, rssnr, cqi, ta);
}
```
> 注意 12+ 的 7 参版本与 10 的 6 参版本**参数个数不同但类型前缀相同**，反射时按 `Build.VERSION.SDK_INT` 分支，不要用"参数最多的那个"瞎试（`getDeclaredConstructor` 参数类型不匹配会直接 `NoSuchMethodException`）。

#### 组装：`CellInfoLte`

| Android | 构造器 |
|---|---|
| 8–12 | `CellInfoLte()` + 字段 `mCellIdentityLte` / `mCellSignalStrengthLte` |
| **13 / 14 / 15** | `CellInfoLte(int connectionStatus, boolean registered, long timeStamp, CellIdentityLte, CellSignalStrengthLte, CellConfigLte)` |

`CellConfigLte` 是 **public API**：`new CellConfigLte()` / `new CellConfigLte(boolean isEndcAvailable)` 都可以直接调，不需要反射。【已验证-源码】CellConfigLte 15

```java
// 13+
Object cfg = newInstance(Class.forName("android.telephony.CellConfigLte"), new Class[]{boolean.class}, false);
Object ciLte = newInstance(Class.forName("android.telephony.CellInfoLte"),
        new Class[]{int.class, boolean.class, long.class,
                    Class.forName("android.telephony.CellIdentityLte"),
                    Class.forName("android.telephony.CellSignalStrengthLte"),
                    Class.forName("android.telephony.CellConfigLte")},
        CellInfo.CONNECTION_PRIMARY_SERVING, true, ts, idLte, ssLte, cfg);

// 8–12
Object ciLte = newInstance(Class.forName("android.telephony.CellInfoLte"), new Class[]{});
setField(ciLte, "mCellIdentityLte",       idLte);
setField(ciLte, "mCellSignalStrengthLte", ssLte);
setField(ciLte, "mRegistered",            true);
setField(ciLte, "mCellConnectionStatus",  CellInfo.CONNECTION_PRIMARY_SERVING); // 10+
setField(ciLte, "mTimeStamp",             ts);
```
> `mCellConfig` 字段名未逐版本核实（15 的构造器参数叫 `cellConfig`），标【推断】；若 `getCellConfig()` 返回 null 导致检测异常，再用 `dumpsys` / 逐字段 dump 确认名字。

### 3.6 NR 配方（Android 10+）

`CellInfoNr` 从 **API 29** 才有，Android 8/9 上直接跳过。

#### `CellIdentityNr`

单一构造器（12/15 相同）：

```java
public CellIdentityNr(int pci, int tac, int nrArfcn, int[] bands,
                      String mccStr, String mncStr, long nci,
                      String alphal, String alphas, Collection<String> additionalPlmns)
```

【已验证-源码】CellIdentityNr 12/15

```java
Object idNr = newInstance(Class.forName("android.telephony.CellIdentityNr"),
        new Class[]{int.class,int.class,int.class,int[].class,String.class,String.class,
                    long.class,String.class,String.class,java.util.Collection.class},
        pci, tac, nrarfcn, new int[]{78}, "460", "00", nci, null, null,
        java.util.Collections.emptyList());
```

**字段名**：`mNrArfcn`, `mPci`, `mTac`, `mNci`(**long**), `mBands`, `mAdditionalPlmns`；`mcc/mnc` 在基类 `mMccStr/mMncStr`。

> ⚠️ `nci` 是 **long**，用 `int` 反射会 `IllegalArgumentException`；`getNci()` 返回 long。

#### `CellSignalStrengthNr`

| Android | 构造器（hidden public） |
|---|---|
| **12 / 13** | `(csiRsrp,csiRsrq,csiSinr,csiCqiTableIndex,List<Byte> csiCqiReport,ssRsrp,ssRsrq,ssSinr)` ← **8 参（无 ta）** |
| **14 / 15** | `(…, ssSinr, int timingAdvance)` ← **9 参** |

【已验证-源码】CellSignalStrengthNr 12/13/14/15（12/13 = 8 参，14/15 = 9 参）

```java
Class<?> c = Class.forName("android.telephony.CellSignalStrengthNr");
Object ssNr;
if (Build.VERSION.SDK_INT >= 34) {          // 14/15：9 参（最后一个是 timingAdvance）
    ssNr = newInstance(c, new Class[]{int.class,int.class,int.class,int.class,
            java.util.List.class,int.class,int.class,int.class,int.class},
            csiRsrp, csiRsrq, csiSinr, 1 /*cqiTableIndex*/,
            java.util.Collections.<Byte>emptyList(), ssRsrp, ssRsrq, ssSinr, ta);
} else {                                     // 10–13：8 参（没有 timingAdvance）
    ssNr = newInstance(c, new Class[]{int.class,int.class,int.class,int.class,
            java.util.List.class,int.class,int.class,int.class},
            csiRsrp, csiRsrq, csiSinr, 1 /*cqiTableIndex*/,
            java.util.Collections.<Byte>emptyList(), ssRsrp, ssRsrq, ssSinr);
}
```

**字段名**：`mCsiRsrp`, `mCsiRsrq`, `mCsiSinr`, `mCsiCqiTableIndex`, `mCsiCqiReport`, `mSsRsrp`, `mSsRsrq`, `mSsSinr`, `mLevel`, `mTimingAdvance`（14+）。

#### 组装：`CellInfoNr` ⚠️ 字段名不一样

```java
// 13+
Object ciNr = newInstance(Class.forName("android.telephony.CellInfoNr"),
        new Class[]{int.class, boolean.class, long.class,
                    Class.forName("android.telephony.CellIdentityNr"),
                    Class.forName("android.telephony.CellSignalStrengthNr")},
        CellInfo.CONNECTION_PRIMARY_SERVING, true, ts, idNr, ssNr);

// 10–12：no-arg + 字段（注意名字！而且 mCellSignalStrength 是 final）
Object ciNr = newInstance(Class.forName("android.telephony.CellInfoNr"), new Class[]{});
setField(ciNr, "mCellIdentity",       idNr);   // 不是 mCellIdentityNr
setField(ciNr, "mCellSignalStrength", ssNr);   // 不是 mCellSignalStrengthNr，且 final
setField(ciNr, "mRegistered",         true);
setField(ciNr, "mCellConnectionStatus", CellInfo.CONNECTION_PRIMARY_SERVING);
setField(ciNr, "mTimeStamp",          ts);
```
> `final` 的 `mCellSignalStrength` 在 ART 上 `setAccessible(true)` 后仍可 `Field.set`（Xposed 生态常用手法）；若某个 OEM 报 `IllegalAccessException`，改用 `sun.misc.Unsafe` / `putObject` 兜底，或退回"构造 `CellInfoNr` 失败 → 只回放 LTE 条目"。

### 3.7 `getAllCellInfo()` / `getCellLocation()` 回放

```java
// hook TelephonyManager.getAllCellInfo() → 返回快照构造出来的 list
List<CellInfo> fake = buildAll(snapshot);
if (fake.isEmpty()) return Collections.emptyList();   // 见 §3.10 降级策略
return fake;
```

`getCellLocation()` 最简单——**完全公开 API，零反射**：

```java
GsmCellLocation loc = new GsmCellLocation();   // public
loc.setLacAndCid(lac, cid);                    // public（就是它，不叫 setLac/setCid）
loc.setPsc(psc);                               // public
return loc;                                    // CellLocation
```

【已验证-源码】AOSP `api/current.txt`（15）里 `GsmCellLocation` 的公开成员：
`GsmCellLocation()`、`GsmCellLocation(Bundle)`、`getLac()`、`getCid()`、`getPsc()`、`setLacAndCid(int,int)`、`setPsc(int)`、`setStateInvalid()`、`fillInNotifierBundle(Bundle)` —— **没有任何一个需要反射**。
（`GsmCellLocation` 在 API 15 标了 `@Deprecated`，但仍可用。）

### 3.8 Wi-Fi 回放

#### `ScanResult`：字段全 public，构造器看版本

**构造器可用性（逐版本核对 API 签名文件）**：

| Android | `ScanResult()` | 怎么造 |
|---|---|---|
| 8 / 9（API 26–28） | SDK 里**没有**（源码 `@hide public ScanResult()` 存在，L547） | 反射 `getDeclaredConstructor()` |
| 10（API 29） | 仍未进 SDK | 反射 |
| **11+（API 30+）** | **公开** | 直接 `new` |

```java
// 统一写法：一个 helper 抹平版本差异
static ScanResult newScanResult() throws Exception {
    if (Build.VERSION.SDK_INT >= 30) return new ScanResult();          // 公开 API
    Constructor<ScanResult> c = ScanResult.class.getDeclaredConstructor();
    c.setAccessible(true);                                             // 26–29：hidden public
    return c.newInstance();
}

ScanResult sr = newScanResult();
sr.BSSID    = "3c:46:d8:11:22:33";
sr.SSID     = "\"CMCC-Office\"";       // ⚠️ 带双引号（AOSP 从 WifiSsid.toString() 派生）
sr.level    = -58;
sr.frequency= 2437;
sr.capabilities = "[WPA2-PSK-CCMP][ESS]";
sr.timestamp = SystemClock.elapsedRealtime() * 1000L;   // 微秒
sr.channelWidth = 0; sr.centerFreq0 = 0; sr.centerFreq1 = 0;
// Android 12+：同时设置 wifiSsid，否则 getWifiSsid() 返回 null
if (Build.VERSION.SDK_INT >= 33) {
    try { sr.wifiSsid = WifiSsid.fromBytes("CMCC-Office".getBytes(StandardCharsets.UTF_8)); }
    catch (Throwable ignored) { }        // fromBytes 是 public API（13+）
} else if (Build.VERSION.SDK_INT >= 31) {
    // 12：wifiSsid 字段存在但 WifiSsid 没有公开工厂，只能反射（或干脆不设，走 hook getWifiSsid）
}
```

**为什么必须同时设 `SSID` 和 `wifiSsid`**（【已验证-源码】ScanResult 15）：

```java
public WifiSsid getWifiSsid() { return wifiSsid; }   // 只有这一个来源，不回退 SSID
```

所以只写 `SSID` → 老代码（读 `SSID` 字段）看到名字，新代码（`getWifiSsid()`）看到 `null`，**本身就是矛盾信号**。

> `WifiSsid` 类本身也是 **API 33 才进公开 API**（`fromBytes(byte[])` / `getBytes()` / `CREATOR`）【已验证-源码 wifi13/wifi15 api】。在 12 及以下只能用反射或直接 hook `getWifiSsid()` 返回 null（多数 App 仍读 `SSID` 字段）。

#### `WifiInfo`：不构造，直接 hook getter

`WifiInfo` 在 SDK 里**没有公开构造器**；源码里是 `/** @hide */ @UnsupportedAppUsage public WifiInfo()`（15 源码 L501）。

**推荐**：不构造 `WifiInfo`，而是 hook 它的 getter，让"当前连接信息"与快照一致：

| hook 目标 | 返回 |
|---|---|
| `WifiInfo.getBSSID()` | 快照里"当前连接"那条的 BSSID |
| `WifiInfo.getSSID()` | `"\"CMCC-Office\""` ⚠️ **带双引号**（AOSP 行为）【推断】 |
| `WifiInfo.getMacAddress()` | 保持原值（这是本机 MAC，不是 AP 的，不要乱改） |
| `WifiInfo.getFrequency()` | 快照 AP 的频率 |
| `WifiInfo.getRssi()` | 快照 AP 的 level |
| `WifiInfo.getLinkSpeed()` / `getNetworkId()` / `getSupplicantState()` | 保持原值或给合理值 |
| `WifiManager.getConnectionInfo()` | 一般不用 hook（hook getter 已覆盖），除非目标 App 把整个对象缓存后到处传递 |

> ⚠️ `getSSID()` 返回值是否带引号随版本/ROM 有差异，**建议 hook 时保留原返回值的格式**（例如拿原始结果判断是否有引号，再套用同样格式）。
> ⚠️ 不要伪造 `getMacAddress()`：那是**本机** MAC，AOSP 在 Android 6+ 上对普通 App 一律返回 `02:00:00:00:00:00`，改了反而异常。

### 3.9 hook 点清单（给 hidden-module-dev 直接抄）

| # | 类 | 方法 | 返回 | 优先级 |
|---|---|---|---|---|
| 1 | `android.telephony.TelephonyManager` | `getAllCellInfo()` | `List<CellInfo>`（快照构造） | ★★★ |
| 2 | `android.telephony.TelephonyManager` | `getCellLocation()` | `GsmCellLocation`（公开 API 构造） | ★★★ |
| 3 | `android.telephony.TelephonyManager` | `getNetworkOperator()` | `mcc + mnc`（如 `"46000"`） | ★★ |
| 4 | `android.telephony.TelephonyManager` | `getNetworkOperatorName()` | 运营商名（可选，与 §5 文案解耦） | ★ |
| 5 | `android.telephony.TelephonyManager` | `getSimOperator()` / `getSimOperatorName()` | 与快照 MCC/MNC 一致（防止"SIM 是 A 国、基站是 B 国"） | ★★ |
| 6 | `android.telephony.TelephonyManager` | `getDataNetworkType()` / `getNetworkType()` | 与快照里"最高代际"一致（有 NR → `NR`） | ★★ |
| 7 | `android.net.wifi.WifiManager` | `getScanResults()` | `List<ScanResult>`（API 30+ 直接 new，26–29 反射） | ★★★ |
| 8 | `android.net.wifi.WifiInfo` | `getBSSID()` / `getSSID()` / `getFrequency()` / `getRssi()` | 快照"当前 AP" | ★★ |
| 9 | `android.location.LocationManager` | `getLastKnownLocation("network")` | 与 GPS 假坐标一致（可选，防交叉校验） | ★ |

**实现注意**：
- hook 里**不要**再调用同一个被 hook 的方法取原始值（无限递归）。需要原始值时用 `XposedBridge.invokeOriginalMethod` 或 `MethodHookParam.getResult()`。
- `getDataNetworkType()` 在部分 ROM 上需要 `READ_PHONE_STATE`，hook 返回常量可绕过权限。
- 所有 hook **必须 try/catch**，异常时 `param.setResult(originalResult)`，绝不能让目标 App 崩（崩溃比"没生效"更糟）。

### 3.10 失败降级策略（重要）

| 失败点 | 推荐行为 | 理由 |
|---|---|---|
| 快照 JSON 解析失败 | **不 hook**，走原始实现 | 宁可暴露真实，也别崩 |
| 某条 CellInfo 构造失败 | 丢弃该条目，保留其余 | 一条坏数据毁掉整个列表不划算 |
| 全部构造失败 | 返回 `Collections.emptyList()`（**不是 null**） | 空列表是合法返回值；`null` 会让很多 App NPE。注意原始 API 的 javadoc 说可能返回 null，但**我们主动返回 null 风险更高** |
| 反射被 non-SDK 拦截（`NoSuchMethodException` / `IllegalAccessException`） | 记录日志 + 返回空列表；**不要**抛给调用方 | 被拦说明该机型版本不支持，功能降级而不是崩溃 |
| Wi-Fi 快照为空 | hook 返回空列表 | 与"关掉 Wi-Fi 扫描"的设备行为一致 |

> **对比：返回空列表 vs 返回真实列表**
> - 返回**真实**：检测方立刻发现"GPS 说在 A 城市，基站全在 B 城市" → 直接判定作弊。
> - 返回**空**：检测方看到"无基站信息"，可能判定"设备无信号/无权限"，是**较弱**的异常信号。
> 所以**默认返回空列表**，把"要不要伪造"交给策略开关。

---

## 4. 检测侧：谁在用基站/Wi-Fi 交叉校验

> 这一节绝大部分是【推断】：具体商业风控 SDK 的实现不公开。给出的是"公开 API 层面的可行手段 + 常见判定逻辑"，用于评估风险等级，不是逆向结论。

### 4.1 常见手段与所需 API

| # | 手段 | 用到的 API | 判定逻辑 | 威胁 | 证据 |
|---|---|---|---|---|---|
| D1 | **mock 标记** | `Location.isMock()` | 见 `mock-detection.md` | 高（已单独处理） | 【已验证-源码】 |
| D2 | **基站反查位置** | `getAllCellInfo()` → MCC/MNC/LAC/CID/TAC | 查 OpenCellID / Mozilla Location Service / 商业基站库，得到经纬度，与 GPS 距离比对 | 高 | 【推断】 |
| D3 | **BSSID 反查位置** | `WifiManager.getScanResults()` → BSSID | 查 Wi-Fi 地理位置库（Google Geolocation API / Mozilla 等），与 GPS 比对 | 高 | 【推断】 |
| D4 | **运营商一致性** | `getNetworkOperator()` vs `getSimOperator()` vs 快照 MCC/MNC | 三者不一致 → 异常 | 中 | 【推断】 |
| D5 | **代际一致性** | `getDataNetworkType()` vs `getAllCellInfo()` 里出现的类型 | 说 5G 但只有 LTE 条目 / 说 4G 但出现 NR | 中 | 【推断】 |
| D6 | **服务小区唯一性** | `CellInfo.isRegistered()` 计数 | 正常设备**恰好一个**（或每卡一个）`registered=true`；伪造时容易 0 个或全 true | 中 | 【推断】 |
| D7 | **信号强度合理性** | `CellSignalStrength*.getLevel()/getDbm()` | level 与 dBm 不匹配（如 level=4 但 rsrp=-120）；或所有小区 rssi 完全相同 | 中 | 【推断】 |
| D8 | **时间戳异常** | `CellInfo.getTimeStamp()` / `ScanResult.timestamp` | 时间戳是未来/远古、所有条目时间戳完全一致 | 低-中 | 【推断】 |
| D9 | **Wi-Fi 与基站地理矛盾** | BSSID 库位置 vs 基站库位置 | 两者相距几百公里 | 高 | 【推断】 |
| D10 | **Play Integrity / 设备完整性** | Play Integrity API | 检测 root / hook / 非官方 ROM | 高（与本文无关但会一起触发） | 【推断】 |

### 4.2 "隐藏" vs "回放"两种策略

| 维度 | 隐藏（返回空/原始） | 回放（返回伪造快照） |
|---|---|---|
| 实现复杂度 | 低（返回空列表） | 高（跨 8 个版本构造对象） |
| 对抗 D2/D3 | 有效（拿不到数据，无法反查） | **有效但要求快照真实**（快照必须来自目标城市，否则 D9 立刻穿帮） |
| 对抗 D6/D7 | 弱（"无基站"本身可疑） | 中（需要把"1 个服务小区 + N 个邻区"造对） |
| 崩溃风险 | 极低 | 中（反射构造失败/字段写不进） |
| 维护成本 | 低 | 高（每次 Android 大版本都要核对构造器） |
| **推荐** | ✅ 作为默认/兜底 | ✅ 作为"高质量模式"，仅对**指定目标城市**的快照启用 |

**结论**：做成**双模式**——
1. 默认「隐藏」：hook 返回空列表 / 保持原始（可配）；
2. 高级「回放」：仅在用户导入了"目标城市"的快照时启用，且启用前做**自检**（构造一条测试 CellInfo，看 `getAllCellInfo` hook 是否返回了预期对象）。

### 4.3 回放可信度的最低要求（给产品的验收标准）

1. **至少 3 条、至多 8 条** CellInfo：1 条 `registered=true` 的服务小区 + 2~7 条邻区。
2. **代际一致**：`getDataNetworkType()` 返回的类型必须与列表里"最高代际"一致。
3. **MCC/MNC 一致**：`getNetworkOperator()`、`getSimOperator()`、所有 CellIdentity 的 MCC/MNC 三者一致。
4. **信号强度成梯度**：服务小区最强（如 -85 dBm），邻区逐级变弱（-95 / -105），且 `getLevel()` 与 `getDbm()` 在 AOSP 阈值下自洽。
5. **Wi-Fi 至少 2~5 条**（1 条"当前连接"+ 若干邻居），BSSID 前缀（OUI）建议取自真实路由器厂商且**互不相同**。
6. **时间戳**：`ScanResult.timestamp` 用 `elapsedRealtime*1000`，`CellInfo.getTimeStamp()` 用 `currentTimeMillis()`，两者都"刚刚"。

---

## 5. 隐私合规与开关文案

### 5.1 风险点

| 数据 | 敏感性 | 说明 |
|---|---|---|
| MCC/MNC/LAC/CID/TAC/PCI | **高** | 基站标识可直接反查物理位置（精度几十米~几百米），在中国属于**位置信息**（《个人信息保护法》敏感个人信息 / 《App 违法违规收集使用个人信息行为认定方法》） |
| BSSID / SSID | **高** | Wi-Fi 指纹是业界标准的地理定位手段；BSSID 属于设备/网络标识符 |
| 快照文件本身 | **高** | 一份快照 = 一份"用户曾经在哪里"的证据 |

### 5.2 建议

1. **默认关闭**：环境快照采集必须由用户显式打开，且**采集前弹一次说明**。
2. **本地存储、绝不上传**：快照存 `filesDir/env-snapshots/`，不上传服务器、不进日志。
3. **可一键清除**：设置里提供"清除所有环境快照"，并显示占用大小。
4. **采集时给可见提示**：前台服务通知里注明"正在采集周边基站/Wi-Fi 用于本地模拟"。
5. **导出/导入 GPX 之类的外部文件时**，不要把快照一起导出去。
6. **合规文案**（建议）：
   > 环境快照会记录您当前位置周边的基站与 Wi-Fi 标识。这些信息可用于反推出您所处的地理位置，属于敏感个人信息。快照**仅保存在本机**、不会上传，您可以随时在设置中删除。是否开启由您决定。

### 5.3 权限清单（Manifest 建议）

```xml
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"/>
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION"/>
<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION"/> <!-- 10+ -->
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE"/>
<uses-permission android:name="android.permission.CHANGE_WIFI_STATE"/>          <!-- 若需要 startScan -->
<uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES"
                 android:usesPermissionFlags="neverForLocation"/>              <!-- 13+ -->
<!-- 不需要：READ_PHONE_STATE / READ_BASIC_PHONE_STATE -->
```

> `NEARBY_WIFI_DEVICES` 加 `neverForLocation` 是 Google 推荐的写法（声明"我不用于定位"），但**我们的用途恰恰是定位**，所以要**去掉** `neverForLocation`，否则部分 ROM 会退化 Wi-Fi 结果。【推断】

---

## 6. 最小字段集（给 JSON schema 用）

> 只列"模块构造对象时**必须**有的字段"。没列的都是可选/有默认值。

### 6.1 CellInfo 公共字段

| 字段名 | 类型 | 含义 | 来源 API | 必需 |
|---|---|---|---|---|
| `type` | `string` | `gsm` / `lte` / `nr` / `wcdma` / `cdma` | `CellInfo.getClass()` 或 `instanceof` | ✅ |
| `registered` | `boolean` | 是否服务小区 | `CellInfo.isRegistered()` | ✅ |
| `connectionStatus` | `int` | 0=none 1=primary 2=secondary | `CellInfo.getCellConnectionStatus()`（10+） | 10+ ✅ |
| `timestamp` | `long` | 毫秒 | `CellInfo.getTimeStamp()` | ✅ |

### 6.2 身份字段

| 字段名 | 类型 | 含义 | 来源 API | 用于 |
|---|---|---|---|---|
| `mcc` | `string` | 移动国家码 `"460"` | `getMccString()`（Android 10+ 公开；8 上是 `getMcc()` 返回 int） | Gsm/Lte/Nr/Wcdma |
| `mnc` | `string` | 移动网络码 `"00"` | 同上 | 同上 |
| `lac` | `int` | GSM/WCDMA 位置区码 | `CellIdentityGsm/Wcdma.getLac()` | Gsm/Wcdma |
| `cid` | `int` | GSM/WCDMA 小区号 | `CellIdentityGsm/Wcdma.getCid()` | Gsm/Wcdma |
| `arfcn` | `int` | GSM 频点 | `CellIdentityGsm.getArfcn()` | Gsm（可选） |
| `bsic` | `int` | GSM 基站识别码 | `CellIdentityGsm.getBsic()` | Gsm（可选） |
| `psc` | `int` | WCDMA 主扰码 | `CellIdentityWcdma.getPsc()` | Wcdma（可选） |
| `uarfcn` | `int` | WCDMA 频点 | `CellIdentityWcdma.getUarfcn()` | Wcdma（可选） |
| `ci` | `int` | LTE 小区号（28 bit） | `CellIdentityLte.getCi()` | Lte ✅ |
| `pci` | `int` | 物理小区 ID（0-503 LTE / 0-1007 NR） | `CellIdentityLte/Nr.getPci()` | Lte/Nr ✅ |
| `tac` | `int` | 跟踪区码 | `CellIdentityLte/Nr.getTac()` | Lte/Nr ✅ |
| `earfcn` | `int` | LTE 频点 | `CellIdentityLte.getEarfcn()` | Lte ✅ |
| `nci` | `long` | NR 小区号（36 bit） | `CellIdentityNr.getNci()` | Nr ✅ |
| `nrarfcn` | `int` | NR 频点（0-3279165） | `CellIdentityNr.getNrarfcn()` | Nr ✅ |
| `bands` | `int[]` | 频段号数组，如 `[3]` / `[78]` | `CellIdentityLte.getBands()`（12+） | 12+ 可选 |
| `bandwidth` | `int` | LTE 带宽 kHz（如 20000） | `CellIdentityLte.getBandwidth()` | ≤11 可选 |

### 6.3 信号强度字段

| 字段名 | 类型 | 含义 | 来源 API | 用于 |
|---|---|---|---|---|
| `rssi` | `int` | dBm | GSM：`getRssi()`（Android 12+ 公开）/ `getDbm()`（8）；LTE：`getRssi()`（Android 10+） | Gsm/Lte |
| `ber` | `int` | 误码率 0-7 或 99 | `CellSignalStrengthGsm.getBitErrorRate()`（Android 10+ 公开） | Gsm |
| `ta` | `int` | timing advance | `getTimingAdvance()` | Gsm/Lte（可选） |
| `rsrp` | `int` | dBm | `CellSignalStrengthLte.getRsrp()` | Lte ✅ |
| `rsrq` | `int` | dB | `CellSignalStrengthLte.getRsrq()` | Lte ✅ |
| `rssnr` | `int` | dB | `CellSignalStrengthLte.getRssnr()` | Lte ✅ |
| `cqi` | `int` | 0-15 | `CellSignalStrengthLte.getCqi()` | Lte（可选） |
| `ssRsrp` | `int` | NR SS-RSRP dBm | `CellSignalStrengthNr.getSsRsrp()` | Nr ✅ |
| `ssRsrq` | `int` | NR SS-RSRQ dB | `CellSignalStrengthNr.getSsRsrq()` | Nr ✅ |
| `ssSinr` | `int` | NR SS-SINR dB | `CellSignalStrengthNr.getSsSinr()` | Nr ✅ |
| `csiRsrp` / `csiRsrq` / `csiSinr` | `int` | NR CSI 系列 | `getCsiRsrp()` 等 | Nr（可选） |

### 6.4 Wi-Fi 字段

| 字段名 | 类型 | 含义 | 来源 API | 必需 |
|---|---|---|---|---|
| `bssid` | `string` | AP MAC `"3c:46:d8:11:22:33"` | `ScanResult.BSSID` | ✅ |
| `ssid` | `string` | 网络名 | `ScanResult.SSID` / `getWifiSsid()` | ✅ |
| `level` | `int` | dBm | `ScanResult.level` | ✅ |
| `frequency` | `int` | MHz | `ScanResult.frequency` | ✅ |
| `capabilities` | `string` | 加密能力串 | `ScanResult.capabilities` | ✅ |
| `timestamp` | `long` | **微秒** | `ScanResult.timestamp` | ✅ |
| `channelWidth` | `int` | 0/1/2/3/4/5 | `ScanResult.channelWidth` | 可选 |
| `centerFreq0` / `centerFreq1` | `int` | MHz | 同名字段 | 可选 |
| `isCurrent` | `boolean` | 是否为"当前连接"的 AP | 采集侧自己标记（`WifiInfo.getBSSID()` 比对） | 建议 |

---

## 7. 证据来源

### AOSP 源码 / API 签名文件（tag 精确）

| 内容 | 链接 |
|---|---|
| `CellIdentityGsm` 8 / 9 / 10 / 11–15 构造器 | <https://cs.android.com/android/platform/superproject/+/android-15.0.0_r1:frameworks/base/telephony/java/android/telephony/CellIdentityGsm.java> |
| `CellIdentityLte` 构造器（5 参 + 全参 + bands） | <https://cs.android.com/android/platform/superproject/+/android-15.0.0_r1:frameworks/base/telephony/java/android/telephony/CellIdentityLte.java> |
| `CellIdentityNr` 构造器 + long nci | <https://cs.android.com/android/platform/superproject/+/android-15.0.0_r1:frameworks/base/telephony/java/android/telephony/CellIdentityNr.java> |
| `CellInfoNr`（`mCellIdentity` / `mCellSignalStrength` 命名 + final） | <https://cs.android.com/android/platform/superproject/+/android-15.0.0_r1:frameworks/base/telephony/java/android/telephony/CellInfoNr.java> |
| `CellSignalStrengthGsm`（8 的 2 参 vs 10+ 的 3 参） | <https://cs.android.com/android/platform/superproject/+/android-15.0.0_r1:frameworks/base/telephony/java/android/telephony/CellSignalStrengthGsm.java> |
| `CellSignalStrengthLte`（12+ 的 7 参） | <https://cs.android.com/android/platform/superproject/+/android-15.0.0_r1:frameworks/base/telephony/java/android/telephony/CellSignalStrengthLte.java> |
| `CellSignalStrengthNr` | <https://cs.android.com/android/platform/superproject/+/android-15.0.0_r1:frameworks/base/telephony/java/android/telephony/CellSignalStrengthNr.java> |
| `CellIdentity` 基类字段（`mMccStr` / `mMncStr`） | <https://cs.android.com/android/platform/superproject/+/android-15.0.0_r1:frameworks/base/telephony/java/android/telephony/CellIdentity.java> |
| `CellInfo` 基类字段（`mRegistered` / `mTimeStamp` / `mCellConnectionStatus`、`UNAVAILABLE`） | <https://cs.android.com/android/platform/superproject/+/android-15.0.0_r1:frameworks/base/telephony/java/android/telephony/CellInfo.java> |
| `GsmCellLocation`（`setLacAndCid` 是公开 API） | <https://cs.android.com/android/platform/superproject/+/android-15.0.0_r1:frameworks/base/telephony/java/android/telephony/gsm/GsmCellLocation.java> |
| **公开 API 签名文件**（证明"零 public 构造器"） | <https://cs.android.com/android/platform/superproject/+/android-15.0.0_r1:frameworks/base/core/api/current.txt> |
| `ScanResult`（`ScanResult()` 公开无参、public 字段、`wifiSsid`） | <https://cs.android.com/android/platform/superproject/+/android-15.0.0_r1:packages/modules/Wifi/framework/java/android/net/wifi/ScanResult.java> |
| `WifiInfo`（`@hide public WifiInfo()`、getter） | <https://cs.android.com/android/platform/superproject/+/android-15.0.0_r1:packages/modules/Wifi/framework/java/android/net/wifi/WifiInfo.java> |
| `WifiManager.getScanResults` 权限注解 | <https://cs.android.com/android/platform/superproject/+/android-15.0.0_r1:packages/modules/Wifi/framework/java/android/net/wifi/WifiManager.java> |
| `TelephonyManager.getAllCellInfo` 权限演进 | <https://cs.android.com/android/platform/superproject/+/android-10.0.0_r1:frameworks/base/telephony/java/android/telephony/TelephonyManager.java> |
| `WifiSsid.fromBytes`（公开 API） | <https://cs.android.com/android/platform/superproject/+/android-15.0.0_r1:packages/modules/Wifi/framework/api/current.txt> |

### 官方文档

| 内容 | 链接 | 等级 |
|---|---|---|
| non-SDK 接口限制：blocklist / unsupported / max-target-x 语义 | <https://developer.android.com/guide/app-compatibility/restrictions-non-sdk-interfaces> | 【已验证-文档】 |
| `ScanResult` 参考（字段/方法） | <https://developer.android.com/reference/android/net/wifi/ScanResult> | 【已验证-文档】 |
| `WifiManager.getScanResults` / `startScan`（节流说明） | <https://developer.android.com/reference/android/net/wifi/WifiManager> | 【已验证-文档】 |
| `TelephonyManager.getAllCellInfo` | <https://developer.android.com/reference/android/telephony/TelephonyManager#getAllCellInfo()> | 【已验证-文档】 |
| `CellInfo.UNAVAILABLE` | <https://developer.android.com/reference/android/telephony/CellInfo#UNAVAILABLE> | 【已验证-文档】 |
| `GsmCellLocation.setLacAndCid` | <https://developer.android.com/reference/android/telephony/gsm/GsmCellLocation#setLacAndCid(int,%20int)> | 【已验证-文档】 |
| HiddenApiBypass（LSPosed 官方库，反射被拦时的兜底） | <https://github.com/LSPosed/AndroidHiddenApiBypass> | 【已验证-文档】 |

### 待真机验证项

1. `getAllCellInfo()` 在飞行模式/无 SIM 下到底返回 `null` 还是空列表（各 ROM 可能不同）。
2. `ScanResult.SSID` 是否带双引号：AOSP 侧由 `WifiSsid.toString()` 派生（带引号），但 OEM 可能改写；`WifiInfo.getSSID()` 同理。<https://developer.android.com/reference/android/net/wifi/WifiInfo#getSSID()>
3. `CellInfoLte.mCellConfig` 的准确字段名（15 的构造器参数叫 `cellConfig`，未逐字段核实）。
4. `getScanResults()` 在 Android 13+ 是否运行时强制 `NEARBY_WIFI_DEVICES`（注解里没有，但服务端可能有检查）。
5. Wi-Fi 扫描节流的确切实现（`WifiScanRequestProxy` 未取到对应 tag 源码，2 分钟 4 次的数字来自社区共识）。
6. LSPosed 作用域内进程是否默认放开 hidden API（若无，需集成 [HiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass)）。
7. `ScanResult()` 在 API 26–29 上反射构造时，是否会因 targetSdk 触发 non-SDK 拦截（取决于目标 App 的 targetSdk）。

---

## 8. 给 hidden-module-dev 的三句话（摘要）

1. `CellInfo*` 全家族**没有公开构造器**，反射是唯一路；**Android 15 优先用 5/6 参全参构造器，8–14 用 no-arg + set 字段**。
2. `ScanResult` 和 `GsmCellLocation` **不需要反射**，直接 `new` + 赋 public 字段/调 public setter，别浪费时间。
3. 任何构造失败都**降级返回空列表**，绝不能让目标 App 崩——崩一次就等于告诉对方"你被 hook 了"。
