# 模拟位置痕迹检测与隐藏（阶段二方案）

> 配套文档：[`root-injection.md`](./root-injection.md)（阶段一注入机制）。
> 本文回答两个问题：**对方怎么发现我们在作弊**，以及**能不能、值不值得把它抹掉**。
>
> 证据分级：【已验证-源码】= 读过 AOSP / 第三方开源实现源码；【已验证-文档】= 官方文档；【推断】= 基于机制推理，未真机复现。
> 第三方参考实现均给出仓库链接，属于"别人的工程实践"，可复核但不等价于官方保证。

---

## 0. 结论先行

| 结论 | 说明 |
|---|---|
| 平台层的 mock 标记是**强制**的，`setTestProviderLocation` 无法绕过 | AOSP `MockLocationProvider.setProviderLocation()` 无条件 `setIsFromMockProvider(true)`【已验证-源码】 |
| 因此「阶段二」只能是**接收端擦除**，不可能是"注入时不留痕" | 除非走 Android ≤9 的 `reportLocation`（priv-app 路线，10+ 已废） |
| 最有效的单个 hook 是 `android.location.Location.isMock()` + `isFromMockProvider()` | 90% 的检测 App 就查这两个【推断】（社区实现一致这么做） |
| 只 hook 这两个**不够**：appops 自查、`getExtras()`、`getAllProviders()` 都会漏 | 见 §2.3 / §2.5 |
| `system_server` 侧 hook 收益高但风险高 | 能让 GMS 也吃到假数据，代价是可能整机不稳定 |
| LSPosed 自身可被检测 | 必须配合 Zygisk 隐藏（Shamiko / Zygisk 自带 denylist）|
| 真实运动类 App 的检测上限不高 | 主要卡在 mock 标记 + root 检测，物理一致性检测很少做全【推断】 |

---

## 1. 谁在检测、检测什么

检测方大概分四类，能力和动机完全不同：

| 检测方 | 典型能力 | 威胁等级 | 说明 |
|---|---|---|---|
| 普通运动/打卡 App | `isMock()`、root 检测 | 中 | 最常见，也最好绕过 |
| 专业反作弊 SDK（如风控 SDK、广告归因 SDK） | 上述 + appops 自查 + 设备指纹 + 行为模型 | 高 | 会做多信号交叉验证 |
| 企业考勤 / 游戏反外挂 | 上述 + native 层检测 + 完整性校验 | 很高 | 可能检测 hook 痕迹本身 |
| 系统自身（开发者选项、状态栏） | 只显示"正在使用模拟位置应用" | 无关 | 不影响功能，但用户可见 |

**关键判断**：本项目是"虚拟运动"，主要对抗对象是第一类。所以投入应该集中在**抹掉平台标记 + 隐藏 root**，而不是去和 native 反调试死磕。

---

## 2. 检测手段逐项拆解

### 2.1 平台 API：`Location.isMock()` / `isFromMockProvider()`

| 版本 | API 行为 |
|---|---|
| ≤ 11 | `isFromMockProvider()` 返回 `mIsFromMockProvider` |
| ≥ 12 | `isMock()` 读 `mFieldsMask & HAS_MOCK_PROVIDER_MASK`；`isFromMockProvider()` 已 deprecated，**实现就是 `return isMock()`** |

【已验证-源码】`Location.java` android-12.0.0_r1

因为 `MockLocationProvider.setProviderLocation()` 强制置位，**从 `addTestProvider` 路径来的 Location 100% 为 true**。这是最硬的证据，也是最容易查的。

```java
// android-15.0.0_r1 .../provider/MockLocationProvider.java
public void setProviderLocation(Location l) {
    Location location = new Location(l);
    location.setIsFromMockProvider(true);   // 无条件
    ...
}
```

### 2.2 `Location.getExtras()` 里的 `"mockLocation"`

某些 ROM / 某些版本会在 `Location.extras` 里塞 `mockLocation=true`。参考实现把它当作**独立载体**处理（`HideMockLocation` 与 `FakeLoc` 都专门 patch 这个 key）。【已验证-源码-第三方】

`FakeLoc` 收集到的 key 变体：

```
"mockLocation"
"mock_location"
"android.location.extra.MOCK_LOCATION"
```

### 2.3 appops 自查（**最容易漏的一个**）

App 可以用公开 API 检查**自己**是不是被选为 mock 应用：

```java
// 注意：unsafeCheckOp / unsafeCheckOpNoThrow 是 public API
int mode = appOps.unsafeCheckOp(AppOpsManager.OPSTR_MOCK_LOCATION, myUid, myPackage);
if (mode == AppOpsManager.MODE_ALLOWED) { /* 我是 mock 应用 → 判定作弊 */ }
```

- `OPSTR_MOCK_LOCATION` = `"android:mock_location"`【已验证-源码 `AppOpsManager.java`】
- `unsafeCheckOp(String, int, String)` 在 AOSP 里是**公开方法**（无 `@SystemApi`、无 `@hide`），`checkOp(String,int,String)` 是它的 `@Deprecated` 别名。所以检测方**不需要任何权限**就能查自己：
  ```java
  // AppOpsManager.java android-15.0.0_r1 L8794 / L8802
  public int unsafeCheckOp(@NonNull String op, int uid, @NonNull String packageName)
  @Deprecated public int checkOp(@NonNull String op, int uid, @NonNull String packageName)
  ```
- 这条路径**不经过 `Location` 对象**，所以只 hook `Location.isMock()` 的模块在这里会直接暴露。
- `FakeLoc` 的做法是 hook `AppOpsManager` 的 7 个方法名（`checkOp` / `checkOpNoThrow` / `checkOpRawNoThrow` / `unsafeCheckOp` / `unsafeCheckOpNoThrow` / `unsafeCheckOpRaw` / `unsafeCheckOpRawNoThrow`），并在 `system_server` 里 hook `AppOpsService.checkOperation*` 兜底。【已验证-源码-第三方】

### 2.4 `Settings` 里的 mock 开关

历史 API：`Settings.Secure.ALLOW_MOCK_LOCATION`（API 23 起废弃，但很多检测代码仍在读字符串 `"mock_location"`）。

`HideMockLocation` 的实现是 hook 四个类的 `getStringForUser`，把 key 为 `"mock_location"` 的返回值改成 `"0"`：

```
android.provider.Settings$Secure
android.provider.Settings$System
android.provider.Settings$Global
android.provider.Settings$NameValueCache
```

【已验证-源码-第三方 <https://github.com/zhaotyue/HideMockLocationnew>】

`FakeLoc` 进一步补上 `com.android.providers.settings.SettingsProvider.call("GET_secure")` 与 `SettingsProvider.query`，因为直接走 `ContentResolver` 的检测方不会经过 `Settings` 的 Java 封装。【已验证-源码-第三方】

### 2.5 provider 列表异常（✅ v0.38 已覆盖）

`LocationManager.getAllProviders()` / `getProviders(true)` 会列出全部 provider 名，自定义测试
Provider 名会形成指纹。**v0.38 起模块会规范化返回值**：只保留 `passive/gps/network/fused`
并按真机顺序排列，非标准名一律过滤（`core/DetectionAvoidance.normalizeProviders`）。


`LocationManager.getAllProviders()` / `getProviders(true)` 会列出全部 provider 名。如果阶段一额外 `addTestProvider("vloc", ...)`，这个名字会出现在列表里，**是一个天然的指纹**。

- 对策 1（推荐）：**不要**创建自定义 provider，只覆盖 `gps`/`network`/`fused`。
- 对策 2：hook `getAllProviders`/`getProviders`/`getBestProvider`，过滤掉自定义名（`FakeLoc` 的 `LocationHooks` 就 hook 了这三个方法）。【已验证-源码-第三方】

### 2.6 系统 UI 暴露（无解，只能接受）

Android 会把"当前 mock 应用"显示在开发者选项里，部分 ROM 在状态栏/快捷设置里也有提示。这条**没有技术手段隐藏**（除非改 `system_server` 的 UI 逻辑，代价极大）。
影响：只影响"用户自己看得到"，对自动化检测无影响。

### 2.7 物理 / 行为一致性检测（高级）

| 信号 | 原理 | 我们的现状 |
|---|---|---|
| GNSS 卫星状态 | mock 时 `GnssStatus` 无卫星/信号异常，但 `Location` 声称来自 `gps` | ✅ v0.18 起回放假 `GnssStatus`（逐颗卫星、1Hz）；✅ v0.38 起回放 NMEA（`$GPGGA`/`$GPRMC`，含正确校验和）；`GnssMeasurements` 仍未覆盖 |
| `accuracy` 恒定 | mock 常写死 3~5 m，真实 GPS 会抖动 | ✅ 注入端拟真抖动 + v0.38 模块侧兜底 ±12% |
| `speed`/`bearing` 与坐标位移不符 | 检测方对连续两点做微分 | 阶段一的路径插值必须自洽（本项目已按大圆弧插值，OK）|
| wifi/cell 与坐标不符 | 扫描周围 AP/基站，反查地理位置 | 无法伪造，只能靠 hook `WifiManager`/`TelephonyManager`（`FakeLoc` 的 `SourceLockHooks` 做了）|
| `elapsedRealtimeNanos` 与 `time` 的偏差 | 两者应同步推进 | ✅ 注入时同步写入 + v0.38 读取时校正 >300ms 漂移 |
| 运动学不可信（瞬移、超速） | 相邻点的距离/时间比 | 阶段一路径播放要限速（本项目 1–120 km/h 已覆盖）|

### 2.8 root / 框架检测

| 检测点 | 说明 |
|---|---|
| `su` 可执行文件（`/system/bin/su`、`/system/xbin/su`、`PATH` 探测） | 三个 root 方案都会暴露 |
| `Magisk` 特征（`/data/adb/magisk`、`magisk` 进程、模块目录） | 可被 Shamiko / denylist 缓解 |
| `LSPosed` 特征（模块列表、`XposedBridge` 类、`de.robv.android.xposed`） | LSPosed 有"隐藏自身"选项，但并非完全隐身 |
| Zygisk / 注入痕迹（`/proc/self/maps` 里的异常 so） | 高级检测会查 |
| SELinux 状态、`ro.debuggable`、`adb_enabled` | 旁证 |

这一层不属于"mock 隐藏"，但**只要 root 被识别，mock 隐藏做得再好也没用**。阶段二必须和"隐藏 root"一起做（Magisk DenyList + Shamiko / KernelSU 的 `susfs` / APatch 的隐藏特性）。

**本项目特有的取舍**：我们的 App 需要长期持有 root 来执行 `appops set`（只在授权时需要）。若能在**首次授权后把 appop 持久化**（appops 是按包名持久化的，重启仍有效），后续运行就**不需要再调 su**，可以显著降低运行期被 root 检测抓到的概率。这是阶段二最划算的一步优化。

---

## 3. 阶段二：LSPosed hook 点清单

分四层，按"收益/风险"排序。**A 层是 MVP，B 层是进阶，C/D 层看目标 App。**

作用域说明：LSPosed 的 scope 决定 hook 注入到哪些进程。
- **A 层**：scope = 目标 App（比如被测的运动 App）。改动只影响该进程，最安全。
- **B 层**：scope = `system`（即 `system_server`）。影响全系统，风险最高。
- **C 层**：scope = 目标 App（GMS 的类被编进宿主 App 时）或 `com.google.android.gms`。
- **D 层**：scope = 目标 App（地图 SDK 的类被编进宿主 App）。

### A 层：目标 App 进程内（MVP）

| # | 类 | 方法 | 动作 | 证据 |
|---|---|---|---|---|
| A1 | `android.location.Location` | `isMock()` | 返回 `false` | 【已验证-源码-第三方】两个实现都 hook |
| A2 | `android.location.Location` | `isFromMockProvider()` | 返回 `false`（≤11 必须，≥12 冗余但要留） | 同上 |
| A3 | `android.location.Location` | `setMock(boolean)` | 参数强制 `false` | 同上 |
| A4 | `android.location.Location` | `setIsFromMockProvider(boolean)` | 参数强制 `false` | 同上 |
| A5 | `android.location.Location` | `getExtras()` | patch 掉 `mockLocation` / `mock_location` / `android.location.extra.MOCK_LOCATION` | 【已验证-源码-第三方】 |
| A6 | `android.location.Location` | `setExtras(Bundle)` | 同上，入参 patch | 同上 |
| A7 | `android.location.Location` | `set(Location)` | after hook：清 `mFieldsMask` 的 `HAS_MOCK_PROVIDER_MASK` 位 + patch `mExtras` | 【已验证-源码-第三方】`HideMockLocation` 用反射改 `mFieldsMask` |
| A8 | `android.location.LocationManager` | `getLastKnownLocation(String)` | 返回值重写为新坐标 + 抹标记 | 【已验证-源码-第三方】 |
| A9 | `android.location.LocationManager$LocationListenerTransport` | `onLocationChanged(...)` / `onLocationChangedAsync(...)` | 就地改写参数里的 `Location`（而不是替换 listener） | 【已验证-源码-第三方】`FakeLoc` 明确说明为何不用 `Proxy` 包 listener |
| A10 | `android.location.LocationManager` | `requestLocationUpdates` 系列 | **不需要** hook（A9 在更底层，覆盖所有重载） | 【推断】（`FakeLoc` 的设计说明支持这个结论） |
| A11 | `android.provider.Settings$Secure` / `$System` / `$Global` / `$NameValueCache` | `getStringForUser` | key `"mock_location"` → `"0"` | 【已验证-源码-第三方】 |
| A12 | `android.app.AppOpsManager` | `checkOp` / `checkOpNoThrow` / `checkOpRawNoThrow` / `unsafeCheckOp` / `unsafeCheckOpNoThrow` / `unsafeCheckOpRaw` / `unsafeCheckOpRawNoThrow` | 当 op 是 `OP_MOCK_LOCATION`(58) 时返回 `MODE_IGNORED`/`MODE_ERRORED`（视检测方语义） | 【已验证-源码-第三方】 |
| A13 | `android.location.LocationManager` | `getAllProviders()` / `getProviders(...)` / `getBestProvider(...)` | 过滤自定义 provider 名 | 【已验证-源码-第三方】 |
| A14 | `android.location.Location` | `getProvider()` | 若返回自定义名，改写为 `"gps"` | 【已验证-源码-第三方】 |
| A15 | `android.location.Location` | `getLatitude`/`getLongitude`/`getAccuracy`/`getAltitude`/`getSpeed`/`getMslAltitudeMeters` | 作为 A8/A9 失效时的兜底，直接改读取值 | 【已验证-源码-第三方】`FakeLoc` 有，但注明是 fallback |

> **A9 是这套方案的技术核心**。AOSP 把**所有** `requestLocationUpdates` 的回调收敛到一个私有传输类 `LocationManager$LocationListenerTransport`，hook 它的 `onLocationChanged` 就能覆盖全部公开重载、现在和未来，且**不需要包装调用方的 listener**。
> `FakeLoc` 明确列出了不用 `Proxy` 包 listener 的三个理由（`WeakHashMap` 弱引用导致订阅被 GC、Proxy 会意外实现默认的批量回调方法、`removeUpdates` 按相等性匹配导致订阅泄漏）——这是很有价值的工程结论，值得照抄。【已验证-源码-第三方】

### B 层：`system_server`（进阶，高风险）

| # | 类 | 方法 | 动作 | 说明 |
|---|---|---|---|---|
| B1 | `com.android.server.location.LocationManagerService`（≤11）<br>`com.android.server.location.LocationManagerService`（≥12） | `getLastLocation` | 返回值重写 | 拉取路径 |
| B2 | `com.android.server.location.provider.LocationProviderManager`（≥12） | `onReportLocation` | 参数里的 `LocationResult`/`List<Location>`/`Location` 就地改写 | **推**路径，能让 GMS 也吃到假数据 |
| B3 | `com.android.server.location.gnss.GnssManagerService` | `addGnssMeasurementsListener`<br>`addGnssNavigationMessageListener`<br>`addGnssAntennaInfoListener`<br>`addGnssBatchingCallback`<br>`registerGnssNmeaCallback`<br>`registerGnssStatusCallback` | 返回默认值（即"该能力不支持"） | 消除"有坐标却没卫星"的矛盾证据 |
| B4 | `com.android.server.appop.AppOpsService` | `checkOperation*` | 对 `OP_MOCK_LOCATION` 返回忽略 | A12 的权威兜底 |

【已验证-源码-第三方 `FakeLoc/FrameworkLocationHooks.kt`】

**B 层的关键收益**：GMS 的 fused 结果是在 GMS 进程里用"它被允许读到的原始 provider 数据"算出来的。如果**原始数据已经在 `system_server` 层变成假的**，那么 GMS 算出来的 fused 结果自然也是假的，**且不需要 hook 任何 GMS 内部类**（GMS 内部类名每个版本都在混淆变化，hook 它们必然腐烂）。

**B 层的风险**：`system_server` 崩溃 = 整机重启；hook 点写错会导致定位框架半死不活。真机上必须先在可恢复环境（Magisk 安全模式）验证。

### C 层：Google Play Services

| # | 类 | 方法 | 动作 |
|---|---|---|---|
| C1 | `com.google.android.gms.location.LocationResult` | `getLocations()` | 数组内每个 `Location` 就地改写 |
| C2 | `com.google.android.gms.location.LocationResult` | `getLastLocation()` | 同上 |

【已验证-源码-第三方 `FakeLoc/PlayServicesHooks.kt`】

注意 `FakeLoc` 的注释明确**放弃** hook `com.google.android.gms.internal.location.*` 和 `Task` 管道，理由是混淆名每版都变。这个判断值得采纳：**C 层只 hook 那两个稳定入口，且优先用 B 层从源头解决**。

### D 层：地图 / 定位 SDK（按目标 App 决定是否需要）

只有当目标 App 完全绕过 `android.location`（例如直接用高德/百度/腾讯 SDK 自带的定位能力）时才需要。`FakeLoc` 覆盖了：

| SDK | 关键类 | 入口 |
|---|---|---|
| 高德 | `com.amap.api.location.AMapLocation`、`AMapLocationClient`、`AMapLocationListener` | 读取字段 + listener 回调 |
| 百度 | `com.baidu.location.BDLocation`、`LocationClient`、`BDLocationListener`/`BDAbstractLocationListener` | `registerLocationListener` |
| 腾讯 | `com.tencent.map.geolocation.TencentLocation`、`TencentLocationManager`、`TencentLocationListener` | manager 入口 |

【已验证-源码-第三方 `FakeLoc/MapSdkHooks.kt`】

**判断**：本项目是"运动轨迹"，被测 App 大概率还是走系统定位（否则拿不到步频/配速的连续性），所以 D 层可以**先不做**，留扩展点。

---

## 4. hook 组合有效性矩阵

| 组合 | 能骗过 `isMock()` | 能骗过 appops 自查 | 能骗过 `getExtras()` | 能骗过 GMS fused | 能骗过 native 读取 | 稳定性 |
|---|---|---|---|---|---|---|
| 只 hook A1+A2 | ✅ | ❌ | ❌ | ❌ | ❌ | 高 |
| A1–A7（Location 全套） | ✅ | ❌ | ✅ | ❌ | ❌（native 直接读字段，可被绕过） | 高 |
| A1–A15（+ Settings + AppOps + Manager） | ✅ | ✅ | ✅ | ❌ | ❌ | 中 |
| A 层 + B2 | ✅ | ✅ | ✅ | ✅（大部分场景） | ❌ | 中低 |
| A 层 + B2 + B3 | ✅ | ✅ | ✅ | ✅ | 部分 | 低 |
| 上者 + B1/B4 | ✅ | ✅ | ✅ | ✅ | 部分 | 低 |
| 上者 + C 层 | ✅ | ✅ | ✅ | ✅（GMS 入口兜底） | 部分 | 低 |
| **B2 + B3 单独**（不 hook App 进程） | ✅（数据源已无标记） | ✅（appops 仍需 B4） | ✅ | ✅ | 部分 | 低 |

**推荐组合**：
- **MVP（先做）**：A 层全套（A1–A15 里去掉 A10/A15 也可）+ 隐藏 root。覆盖 80% 场景。
- **增强**：+ B2 + B3（需要 LSPosed scope 加 `system`）。
- **不建议**：只做 A1+A2（太多检测面没覆盖，会被 appops 自查打穿）；直接上 B 层（风险收益比差）。

---

## 5. 可行性 / 风险评估

### 5.1 可行性：**可行，但不是"无痕"**

| 维度 | 评估 |
|---|---|
| 技术可行性 | 高。A 层 hook 点都是稳定的 AOSP 公开/半公开成员，跨 8–15 名字一致（`LocationListenerTransport` 在 12+ 仍是这个名字） |
| 跨版本稳定性 | 中高。AOSP 类名稳定，但 OEM（MIUI/ColorOS/HarmonyOS）可能改私有类名 → 需要 `findClass` 多候选 + 失败降级 |
| GMS 覆盖 | 中。B2 能覆盖大部分，但 GMS 版本迭代可能引入新路径 |
| 对抗升级 | 低-中。一旦检测方改用 native 层读 `Location` 字段，Java hook 全废 |

### 5.2 风险清单

| 风险 | 影响 | 缓解 |
|---|---|---|
| LSPosed 自身被检测 | 直接判定 root/作弊 | Magisk DenyList + Shamiko；KernelSU 用 susfs；APatch 隐藏特性 |
| `system_server` hook 导致崩溃 | 整机重启/定位服务挂死 | 只在 A 层不够用时才开；hook 体必须包 `try/catch` 且失败就 `chain.proceed()` |
| 反射改 `mFieldsMask` 失败 | 抹标记不彻底 | 多路径兜底（改字段 → 调 setter → 改 getter 返回值） |
| OEM 改字段名 | 同上 | `MockTrace` 式多候选字段名（`mIsFromMockProvider` / `mMock`）|
| Android 版本升级导致 hook 点消失 | 静默失效（"看起来在跑但没生效"） | 启动时自检：构造一个 mock Location 验证 `isMock()` 是否被抹掉，失败则提示 |
| **合规/政策风险** | 应用商店下架、账号封禁 | 这是产品决策，不是技术问题。LSPosed 模块通常单独分发，不进 Play 商店 |
| 用户设备变砖 | 支持成本 | 模块必须可一键卸载（Magisk `remove` 标记 / 安全模式） |

### 5.3 一个容易被忽略的现实约束

LSPosed 依赖 Zygisk：
- **Magisk**：自带 Zygisk（需在设置里打开）。
- **KernelSU / APatch**：**没有**内置 Zygisk，需要装 **ZygiskNext**。

所以阶段二的模块**不能假设用户已经能跑 LSPosed**。产品上应当：
1. 检测 `Zygisk`/`ZygiskNext` 是否就绪；
2. 检测 LSPosed 是否安装且**已把目标 App 加进 scope**（用户经常忘）；
3. 给出明确的分步引导，而不是"装了模块却没生效"的黑盒。

---

## 6. 不推荐的做法（以及为什么）

| 做法 | 为什么不做 |
|---|---|
| 只 hook `isMock()` | appops 自查、`getExtras()`、provider 列表全都会漏 |
| 用 `Proxy` 包 `LocationListener` | 弱引用被 GC、意外实现批量回调、`removeUpdates` 匹配失败（`FakeLoc` 已论证） |
| hook `com.google.android.gms.internal.location.*` | 混淆名每版变，维护成本无穷 |
| 在 App 自己的进程里 hook 自己 | 毫无意义——检测发生在**对方**进程 |
| 用 `XposedBridge` 直接改 `system_server` 的注册表结构 | 有一半概率把 `mRegistrations` 改坏 → `system_server` 崩 |
| 依赖 "priv-app + `injectLocation`" 来避免 mock 标记 | `injectLocation` 只写 last-known（见 `root-injection.md` §2.4），不能连续注入 |
| 把 `mock_location` appop 在运行期反复 `su` 授予 | 运行期持有 root 会被 root 检测抓到；应首次授权后持久化（appops 是持久的） |

---

## 7. 落地建议

### 阶段一先做的"低成本抗检测"（不需要 LSPosed）

这些在注入端就能做，几乎零成本，能挡掉最简单的启发式检测：

1. **`accuracy` 加抖动**：不要恒定 3/5/10 m，按 ±30% 随机。
2. **`speed` 与位移严格自洽**：`speed = 两点距离 / 时间差`（本项目按大圆弧插值，天然自洽）。
3. **`bearing` 与位移方向一致**。
4. **`time` 与 `elapsedRealtimeNanos` 同步推进**，不要一个用 `System.currentTimeMillis()` 另一个用固定值。
5. **不创建自定义 provider**（避免 `getAllProviders` 指纹）。
6. **注入频率控制在 1–5 Hz**，太低会被判"定位卡住"，太高不像真实 GPS。
7. **停止后立刻 `removeTestProvider`**，避免"App 没开但还在 mock"被用户/系统发现。

### 阶段二 MVP（建议顺序）

```
Step 1  A1–A7（Location 全套）+ A11（Settings）+ A12（AppOps）
        → 覆盖 mock 标记、extras、appops 自查
Step 2  A8/A9（getLastKnownLocation + LocationListenerTransport）
        → 覆盖真正的实时回调
Step 3  A13/A14（provider 名过滤）
Step 4  隐藏 root（DenyList / Shamiko / susfs）
Step 5  （可选）B2 + B3，scope 加 system
Step 6  （可选）C 层，应对 GMS 特殊性
```

**自检机制**（强烈建议）：模块启动后在目标进程里构造一个已知的 mock Location（或读一个真实回调），断言 `isMock()==false` 且 `getExtras()` 不含 mock key，把结果打到 log。否则用户永远不知道模块有没有生效。

---

## 8. 证据来源清单

### AOSP 源码

| 内容 | 链接 |
|---|---|
| `MockLocationProvider.setProviderLocation` 无条件置 mock | <https://cs.android.com/android/platform/superproject/+/android-15.0.0_r1:frameworks/base/services/core/java/com/android/server/location/provider/MockLocationProvider.java> |
| `Location.isMock` / `isFromMockProvider` / `setMock` | <https://cs.android.com/android/platform/superproject/+/android-12.0.0_r1:frameworks/base/location/java/android/location/Location.java> |
| `OPSTR_MOCK_LOCATION = "android:mock_location"` | <https://cs.android.com/android/platform/superproject/+/android-15.0.0_r1:frameworks/base/core/java/android/app/AppOpsManager.java> |
| `LocationManager$LocationListenerTransport`（AOSP 内部回调收敛点） | <https://cs.android.com/android/platform/superproject/+/android-15.0.0_r1:frameworks/base/location/java/android/location/LocationManager.java> |
| `LocationProviderManager.onReportLocation`（B2 hook 点） | <https://cs.android.com/android/platform/superproject/+/android-15.0.0_r1:frameworks/base/services/core/java/com/android/server/location/provider/LocationProviderManager.java> |

### 第三方开源实现（工程实践参考，非官方保证）

| 项目 | 提供的关键 hook 清单 | 链接 |
|---|---|---|
| **HideMockLocation** (auag0 / zhaotyue fork) | `Location`：`isFromMockProvider` / `isMock` / `setIsFromMockProvider` / `setMock` / `getExtras` / `setExtras` / `set`；`Settings.{Secure,System,Global,NameValueCache}.getStringForUser` → `"mock_location"`→`"0"`；反射清 `mFieldsMask` 的 `HAS_MOCK_PROVIDER_MASK` | <https://github.com/zhaotyue/HideMockLocationnew>、<https://modules.lsposed.org/module/io.github.auag0.hidemocklocation/> |
| **FakeLoc** (Cobylinweiqi) | 上述全部 + `AppOpsManager` 7 个 `checkOp*` + `AppOpsService.checkOperation*` + `SettingsProvider.call("GET_secure")`/`query` + `LocationManager.getLastKnownLocation`/`getCurrentLocation`/`getProviders`/`getAllProviders`/`getBestProvider` + `LocationManager$LocationListenerTransport.onLocationChanged(Async)` + `LocationManagerService.getLastLocation` + `LocationProviderManager.onReportLocation` + `GnssManagerService` 6 个注册方法 + `GmsLocationResult.getLocations/getLastLocation` + 高德/百度/腾讯 SDK | <https://github.com/Cobylinweiqi/FakeLoc> |

### 待真机验证项

1. LSPosed 在 Android 15 上的可用性（本机无法验证；需按目标机型实测，官方仓库与社区 fork 的支持版本不同）。
2. `LocationManager$LocationListenerTransport` 在各 OEM ROM 上是否改名（`FakeLoc` 已留多候选）。
3. MIUI / ColorOS / HarmonyOS 对 `Location` 私有字段的改动情况。
4. GMS 各版本 `LocationResult` 是否仍为那两个入口。
5. 目标 App 是否真的做 appops 自查（可用测试机 + 我们自己的检测探针验证）。

---

## 9. 与阶段一文档的交叉索引

- 「为什么 `setTestProviderLocation` 一定留标记」→ `root-injection.md` §2.2
- 「为什么 priv-app 也拿不到 `ACCESS_MOCK_LOCATION`」→ `root-injection.md` §5.1
- 「为什么 `injectLocation` 不能当无痕注入」→ `root-injection.md` §2.4
- 「进程被杀后的残留怎么清」→ `root-injection.md` §3.4 / §7.3
