# Root 定位注入方案（Android 8.0 – 15）

> 面向「虚拟运动 root 版」的实现参考。本文只讨论**注入机制**，不含 App Java 代码。
> 证据分级：【已验证-源码】= 逐行读过对应 AOSP tag 源码；【已验证-文档】= 官方文档原文；【推断】= 基于源码结构推理，未在真机复现。
>
> 阅读约定：文中 `LMS` = `LocationManagerService.java`，`LPM` = `LocationProviderManager.java`，`MLP` = `MockLocationProvider.java`。
> 源码链接统一用 `cs.android.com` 的 tag 形式，例如
> <https://cs.android.com/android/platform/superproject/+/android-15.0.0_r1:frameworks/base/services/core/java/com/android/server/location/LocationManagerService.java>

---

## 0. 速查结论

| 结论 | 证据等级 |
|---|---|
| `addTestProvider` / `setTestProviderLocation` 唯一的权限门槛是 **appop `android:mock_location`**，不是 `ACCESS_MOCK_LOCATION` 权限 | 【已验证-源码】8/9/10/11/12/13/14/15 全部逐行比对 |
| `ACCESS_MOCK_LOCATION` 是 `protectionLevel="signature"`，**非平台签名永远拿不到，而且也不需要** | 【已验证-源码】|
| 注入端**不需要** `ACCESS_FINE_LOCATION`；接收端（`requestLocationUpdates`）才需要 | 【已验证-源码】|
| `INSTALL_LOCATION_PROVIDER` 只对 **Android ≤9 的 `reportLocation`** 与「注册真实 provider」有意义，对 `addTestProvider` 无意义 | 【已验证-源码】|
| `setTestProviderLocation` 写入的 Location **必然**带 mock 标记（12+ 为 `isMock()==true`） | 【已验证-源码】【已验证-文档】|
| 覆盖 `gps`/`network`/`fused` 会**卸载真实 provider**并替换为 mock，`removeTestProvider` 会把它装回来 | 【已验证-源码】8/10/12+ |
| App 进程被杀后 mock provider **不会自动消失**，会一直顶掉真实 GPS 直到 reboot | 【已验证-源码】框架侧不存在任何清理路径（见 §3.4）；运行期后果为【推断】 |
| Magisk 模块 `system/priv-app/...` 可装 priv-app；**KernelSU/APatch 必须先装 metamodule** 才会挂载 `system/` | 【已验证-文档】|
| priv-app + allowlist 能拿到 `INSTALL_LOCATION_PROVIDER` / `LOCATION_HARDWARE`，但**拿不到** `ACCESS_MOCK_LOCATION` | 【已验证-源码】|
| 所谓「系统应用无痕注入」在 Android 10+ 只剩 `injectLocation`，它**只写 last-known 且只在该 provider 还没有 last location 时生效**，不能持续注入 | 【已验证-源码】10/15 |

**一句话架构结论**：阶段一老老实实用 `appops set <pkg> android:mock_location allow` + `addTestProvider` + `setTestProviderLocation`；「无痕」只能在接收端（阶段二 LSPosed）解决，不要指望用 priv-app 绕过 mock 标记。

---

## 1. 权限模型

### 1.1 三条权限 + 一个 appop（最容易混淆的四样东西）

| 名称 | protectionLevel（Android 15 / 8） | 真实作用 | 非系统 App 能否获得 |
|---|---|---|---|
| `android.permission.ACCESS_MOCK_LOCATION` | `signature` / `signature` | **历史遗留**。API 23 之后 mock 能力改由 appop 控制，该权限不再被任何注入路径检查 | ❌ 永远不能（除非平台签名） |
| `android.permission.INSTALL_LOCATION_PROVIDER` | `signature\|privileged` / `signature\|privileged` | 仅 `checkCallerIsProvider()` 用：Android ≤9 的 `reportLocation()`、注册 `LocationProvider` 子类 | ✅ 可（priv-app + allowlist） |
| `android.permission.LOCATION_HARDWARE` | `signature\|privileged\|role` / `signature\|privileged` | Android ≥10 的 `injectLocation()` 必需 | ✅ 可（priv-app + allowlist） |
| `android.app.AppOpsManager#OP_MOCK_LOCATION`（`android:mock_location`） | appop，默认 `MODE_ERRORED` | **`addTestProvider` 真正检查的东西** | ✅ 可（`appops set`，需要 root 或用户在开发者选项里选） |

证据：`frameworks/base/core/res/AndroidManifest.xml`
<https://cs.android.com/android/platform/superproject/+/android-15.0.0_r1:frameworks/base/core/res/AndroidManifest.xml>
（`ACCESS_MOCK_LOCATION` 在 L2068 附近，`INSTALL_LOCATION_PROVIDER`、`LOCATION_HARDWARE` 同文件）
appop 默认模式：`AppOpsManager.java` 中 `new AppOpInfo.Builder(OP_MOCK_LOCATION, ...).setDefaultMode(AppOpsManager.MODE_ERRORED)`
<https://cs.android.com/android/platform/superproject/+/android-15.0.0_r1:frameworks/base/core/java/android/app/AppOpsManager.java>

### 1.2 `addTestProvider` 各版本的检查代码

全部版本的共同点：**只查 appop，不查任何 `<permission>`**，而且检查失败时**静默 `return`**（不是抛异常）。

| Android | 检查代码 | 位置 | 证据 |
|---|---|---|---|
| 8.0 / 8.1 | `mAppOps.noteOp(OP_MOCK_LOCATION, Binder.getCallingUid(), opPackageName) == MODE_ALLOWED` | `LMS` L2830 | 【已验证-源码】 |
| 9.0 | 与 8.0 逐字相同（`noteOp`） | `LMS` L3165 | 【已验证-源码】 |
| 10 | `mAppOps.checkOp(OP_MOCK_LOCATION, uid, opPackageName) == MODE_ALLOWED` | `LMS` L3515 | 【已验证-源码】 |
| 11 | `mAppOpsHelper.noteMockLocationAccess(identity)` | `LMS` L2561 | 【已验证-源码】 |
| 12 / 12L / 13 | `mInjector.getAppOpsHelper().noteOp(OP_MOCK_LOCATION, identity)` | `LMS` L1305 | 【已验证-源码】 |
| 14 | 同 12/13 | `LMS` L1394 | 【已验证-源码】 |
| 15 | 同 12/13/14，**代码完全未变** | `LMS` L1463 | 【已验证-源码】 |

Android 15 的 `addTestProvider` 全文（`android-15.0.0_r1`，L1463）：

```java
public void addTestProvider(String provider, ProviderProperties properties,
        List<String> extraAttributionTags, String packageName, String attributionTag) {
    // unsafe is ok because app ops will verify the package name
    CallerIdentity identity = CallerIdentity.fromBinderUnsafe(packageName, attributionTag);
    if (!mInjector.getAppOpsHelper().noteOp(AppOpsManager.OP_MOCK_LOCATION, identity)) {
        return;                       // <-- 静默返回，没有异常
    }
    final LocationProviderManager manager = getOrAddLocationProviderManager(provider);
    manager.setMockProvider(new MockLocationProvider(properties, identity,
            new ArraySet<>(extraAttributionTags)));
}
```

`LocationManager.java` 的 javadoc 却写着：

> `@throws SecurityException if mock location app op is not set to allowed for your app.`

【已验证-文档】<https://developer.android.com/reference/android/location/LocationManager#addTestProvider(java.lang.String,%20android.location.provider.ProviderProperties)>

**这两者是矛盾的，但都对** —— 见 1.3。

### 1.3 失败模式（本项目最容易踩的坑）

`AppOpsManager.noteOp` 的语义是「**MODE_ERRORED 抛异常，MODE_IGNORED 只返回**」：

```java
public int noteOp(int op, int uid, @Nullable String packageName, ...) {
    final int mode = noteOpNoThrow(op, uid, packageName, attributionTag, message);
    if (mode == MODE_ERRORED) {
        throw new SecurityException(buildSecurityExceptionMsg(op, uid, packageName));
    }
    return mode;
}
```

【已验证-源码】`AppOpsManager.java`（android-15.0.0_r1，L8977 附近）

于是 `OP_MOCK_LOCATION`（默认 `MODE_ERRORED`）会呈现两种截然不同的失败：

| appop 实际状态 | `noteOp` 行为 | Service 行为 | 调用方看到的现象 |
|---|---|---|---|
| **从未设置**（MODE_DEFAULT → 默认 ERRORED） | 抛 `SecurityException` | 不执行 | `addTestProvider` 直接抛 `SecurityException` ✅ 符合文档 |
| **显式 deny / ignore**（`appops set ... deny`） | 返回 `MODE_IGNORED` | **静默 `return`** | `addTestProvider` **不抛异常**，看起来"成功"；紧接着 `setTestProviderLocation` 抛 `IllegalArgumentException: provider doesn't exist` ❌ |
| **allow** | 返回 `MODE_ALLOWED` | 正常执行 | 注入成功 |

**实现要求**：不要用「没抛异常」判断成功。必须用 `LocationManager.getAllProviders().contains(name)` 或 `isProviderEnabled(name)` 二次确认，并且捕获 `IllegalArgumentException` 与 `SecurityException` 两类异常分别提示用户（前者=appops 被 deny，后者=appops 未设置/被 revoke）。

`setTestProviderLocation` 的失败形态（Android 15 `LMS` L1498）：

```java
if (!mInjector.getAppOpsHelper().noteOp(AppOpsManager.OP_MOCK_LOCATION, identity)) {
    return;                                  // 静默
}
Preconditions.checkArgument(location.isComplete(),
        "incomplete location object, missing timestamp or accuracy?");
LocationProviderManager manager = getLocationProviderManager(provider);
if (manager == null) {
    throw new IllegalArgumentException("provider doesn't exist: " + provider);
}
manager.setMockProviderLocation(location);
```

⚠️ **Location 必须「complete」**：`isComplete()` 要求同时有 `time`、`accuracy`、以及非空 `provider`（8.0 起；12+ 由 `LocationResult.validate()` 再校验一次，`BadLocationException` 被包成 `IllegalArgumentException`）。只填经纬度就调 `setTestProviderLocation` 会直接崩在参数校验上。

### 1.4 `ACCESS_FINE_LOCATION` 到底要不要

- **注入端：不要。** `addTestProvider` / `setTestProviderLocation` 全程没有任何 `enforceCallingPermission(ACCESS_FINE_LOCATION)`。【已验证-源码】
- **接收端：要。** App 自己要 `requestLocationUpdates` 拿到位置回显，需要 `ACCESS_FINE_LOCATION`（Android 10+ 还要 `ACCESS_BACKGROUND_LOCATION` 才能后台收）。
- **例外**：Android ≥10 的 `injectLocation` 明确 `enforceCallingPermission(ACCESS_FINE_LOCATION)`（见 2.4）。

### 1.5 `INSTALL_LOCATION_PROVIDER` 何时必需

只有两处会检查它：

1. **Android ≤9 的 `ILocationManager.reportLocation(Location, boolean passive)`** —— 服务端第一步就是 `checkCallerIsProvider()`：
   ```java
   public void reportLocation(Location location, boolean passive) {
       checkCallerIsProvider();
       if (!location.isComplete()) { ... return; }
   ```
   【已验证-源码】`LMS` android-8.0.0_r1 L2516
2. **注册 `LocationProvider` 子类**（`LocationManagerService.registerLocationProvider`，实际只有 `/system/priv-app` 里的框架 provider 会走）。

`addTestProvider` / `setTestProviderLocation` **完全不检查它**。所以「阶段一」不需要 priv-app。

---

## 2. mock 标记：写进去的 Location 一定会被标记

### 2.1 标记的物理载体

| Android | 字段/方法 | 说明 |
|---|---|---|
| ≤ 11 | `Location.mIsFromMockProvider`（独立 boolean） | `isFromMockProvider()` 直接读它 |
| ≥ 12 | `Location.mFieldsMask` 的 `HAS_MOCK_PROVIDER_MASK` 位 | `isMock()` 读位；`isFromMockProvider()` 被标 `@Deprecated` 并**直接 `return isMock()`** |

【已验证-源码】`Location.java` android-12.0.0_r1：

```java
@Deprecated
public boolean isFromMockProvider() {
    return isMock();
}

@Deprecated @SystemApi
public void setIsFromMockProvider(boolean isFromMockProvider) {
    setMock(isFromMockProvider);
}

public boolean isMock() {
    return (mFieldsMask & HAS_MOCK_PROVIDER_MASK) != 0;
}

public void setMock(boolean mock) {
    if (mock) { mFieldsMask |= HAS_MOCK_PROVIDER_MASK; }
    else      { mFieldsMask &= ~HAS_MOCK_PROVIDER_MASK; }
}
```

> `Location.isMock()` 是 API 31（Android 12）新增的公开方法；`Location.setMock(boolean)` **也是公开方法**（无 `@SystemApi`、无 `@hide`）。
> 但改自己的副本没用：`isMock()` 读的是 `mFieldsMask`，而该字段会随 `writeToParcel` 序列化；服务端在 `MockLocationProvider` 里会重新 `new Location(l)` 并强制置位，所以注入端无法通过 `setMock(false)` 逃掉标记。

### 2.2 服务端在哪里盖戳

| Android | 盖戳点 | 代码 | 证据 |
|---|---|---|---|
| 8.0 / 8.1 | `setTestProviderLocation` 显式盖 | `Location mock = new Location(loc); mock.setIsFromMockProvider(true);` | 【已验证-源码】`LMS` L2907 |
| 9.0 | 两处：`setTestProviderLocation` 显式盖 + `handleLocationChanged` 按 `isMockProvider()` 补盖 | `mock.setIsFromMockProvider(true);` / `if (!myLocation.isFromMockProvider() && isMockProvider(provider))` | 【已验证-源码】`LMS` L3256 / L3090 |
| 10 | `MockProvider.setLocation()`，条件判断后置 true | `if (!mLocation.isFromMockProvider()) { mLocation.setIsFromMockProvider(true); }` | 【已验证-源码】`services/core/java/com/android/server/location/MockProvider.java` L65-69 |
| 11 | `MockProvider.setProviderLocation()` 无条件盖（已改造成 `AbstractLocationProvider` 体系） | `Location location = new Location(l); location.setIsFromMockProvider(true);` | 【已验证-源码】`.../server/location/MockProvider.java` |
| 12 / 12L / 13 / 14 / 15 | `MockLocationProvider.setProviderLocation` 盖 | 见下 | 【已验证-源码】 |

Android 12 与 Android 15 的 `MockLocationProvider`（文件几乎逐字相同，仅多了 `BadLocationException` 包装）：

```java
/** Sets the location to report for this mock provider. */
public void setProviderLocation(Location l) {
    Location location = new Location(l);
    location.setIsFromMockProvider(true);          // <-- 无论你传什么，都强制置 true
    mLocation = location;
    try {
        reportLocation(LocationResult.wrap(location).validate());
    } catch (BadLocationException e) {
        throw new IllegalArgumentException(e);
    }
}
```

【已验证-源码】`.../server/location/provider/MockLocationProvider.java` android-12.0.0_r1 L56 / android-15.0.0_r1 L57

**结论**：`setTestProviderLocation` 这条路径**不存在任何不置标记的可能**。想改标记只能在接收端擦（阶段二），或者换成 `injectLocation`（但那不是注入，见 2.4）。

### 2.3 客户端读到的版本差异

- Android ≤11：`Location.isFromMockProvider() == true`。
- Android 12+：`Location.isMock() == true`，`isFromMockProvider()` 同值（deprecated 代理）。
- **跨版本混淆点**：`Location.setIsFromMockProvider()` 在 12+ 是 `@SystemApi`，普通 App 编译期就调不到；而 `setMock()` 是 public。第三方检测库若用反射读 `mIsFromMockProvider` 字段，在 12+ 会拿到 `NoSuchFieldException`，需要 fallback 到 `mFieldsMask` —— 这也是阶段二 hook 必须同时处理两条路径的原因。

### 2.4 `reportLocation` / `injectLocation`：唯一"不留标记"的官方路径，但没用

| Android | 方法 | 权限 | 是否置 mock 标记 | 语义 |
|---|---|---|---|---|
| 8 / 9 | `reportLocation(Location, boolean passive)` | `INSTALL_LOCATION_PROVIDER` | **否**（作为真实 provider 上报处理） | 真正注入到 provider 管道，会分发给所有 listener |
| 10 – 15 | **`injectLocation(Location)`**（`reportLocation` 被改名/替换） | `LOCATION_HARDWARE` **+** `ACCESS_FINE_LOCATION` | 否 | **只写 last-known**，且只在"该 provider 当前没有 last location"时生效 |

Android 10 的实现（`LMS` L2773）：

```java
public boolean injectLocation(Location location) {
    mContext.enforceCallingPermission(android.Manifest.permission.LOCATION_HARDWARE, ...);
    mContext.enforceCallingPermission(android.Manifest.permission.ACCESS_FINE_LOCATION, ...);
    ...
    // NOTE: If last location is already available, location is not injected.
    if (mLastLocation.get(provider.getName()) != null) {
        return false;
    }
    updateLastLocationLocked(location, provider.getName());
    return true;
}
```

Android 15（`LMS` L1094）语义没变，只是改走 `LocationProviderManager`：

```java
public void injectLocation(Location location) {
    super.injectLocation_enforcePermission();
    Preconditions.checkArgument(location.isComplete());
    LocationProviderManager manager = getLocationProviderManager(location.getProvider());
    if (manager != null && manager.isEnabled(userId)) {
        manager.injectLastLocation(Objects.requireNonNull(location), userId);
    }
}
```

而 `LPM.injectLastLocation`（L1927）**只在没有任何 last location 时才写入**：

```java
public void injectLastLocation(Location location, int userId) {
    synchronized (mMultiplexerLock) {
        Preconditions.checkState(mState != STATE_STOPPED);
        if (getLastLocationUnsafe(userId, PERMISSION_FINE, false, Long.MAX_VALUE) == null) {
            setLastLocation(location, userId);
        }
    }
}
```

【已验证-源码】`LMS` android-10.0.0_r1 L2773；`LMS` android-15.0.0_r1 L1094；`LPM` android-15.0.0_r1 L1927

**所以**：
- `injectLocation` **不能**做连续路径注入（第二次开始直接被丢弃），也不能覆盖已被真实 GNSS 填过的 last location。
- Android 8/9 的 `reportLocation` 是真正的"无痕注入"，但需要 `INSTALL_LOCATION_PROVIDER`（priv-app + allowlist），且**这条路径在 10 已经没有了**。
- 这就是为什么整个社区在 10+ 都退回 `addTestProvider`，然后靠 hook 抹痕迹。

---

## 3. Provider 语义

### 3.1 覆盖已有 provider（`gps` / `network` / `fused`）

覆盖是**替换**语义，不是"并存"：

- **Android 8**：`addTestProvider` 里显式判断名字，若是 `gps`/`network`/`fused` 就 `removeProviderLocked(real)`，再塞 `MockProvider`。
  ```java
  if (LocationManager.GPS_PROVIDER.equals(name)
          || LocationManager.NETWORK_PROVIDER.equals(name)
          || LocationManager.FUSED_PROVIDER.equals(name)) {
      LocationProviderInterface p = mProvidersByName.get(name);
      if (p != null) { removeProviderLocked(p); }
  }
  ```
  【已验证-源码】`LMS` L2836
- **Android 10**：任何同名真实 provider 都会被 `removeProviderLocked(oldProvider)`；若旧的本身是 mock，抛 `IllegalArgumentException("already exists")`（重复调用 `addTestProvider` 会炸）。
- **Android 11+**：`LocationProviderManager` 内部有 `MockableLocationProvider`，`setMockProvider(mock)` 时 `mProvider` 直接指向 mock，真实 provider 变 `mRealProvider` 被**降级但不销毁**；`isMock()` 为真期间所有真实上报被丢弃。

Android 12 的 `MockableLocationProvider`（L117 / L176）：

```java
public void setMockProvider(@Nullable MockLocationProvider provider) {
    synchronized (mOwnerLock) {
        if (mMockProvider == provider) return;
        mMockProvider = provider;
        if (mMockProvider != null) { setProviderLocked(mMockProvider); }
        else                       { setProviderLocked(mRealProvider); }
    }
}

public boolean isMock() {
    synchronized (mOwnerLock) {
        return mMockProvider != null && mProvider == mMockProvider;
    }
}
```

【已验证-源码】`.../location/provider/MockableLocationProvider.java` android-12.0.0_r1

**实践含义**：
- 只需覆盖 `gps` 就能让 `LocationManager.GPS_PROVIDER` 的客户端拿到假坐标，且真实 GNSS 被压制。
- 但**想影响 GMS 的 `FusedLocationProviderClient`，必须同时覆盖 `fused`**（GMS 走 `FUSED_PROVIDER`；只假 `gps` 时 GMS 可能仍用网络/传感器融合出真值）。见 §3.6。
- `PASSIVE_PROVIDER`（"passive"）**不可被 mock**，8.0 起显式抛 `IllegalArgumentException("Cannot mock the passive location provider")`。【已验证-源码】

### 3.2 自定义 provider（如 `"vloc"`）

- 可以 `addTestProvider("vloc", ...)`，Android 12+ 会 `getOrAddLocationProviderManager("vloc")` 现场建一个 manager，不会影响任何真实 provider。
- **但是**：只有主动 `requestLocationUpdates("vloc", ...)` 的客户端才收得到。系统/GMS/第三方地图 App 全都按 `gps`/`fused`/`network` 请求，所以**自定义 provider 对"改定位"这个目标基本无用**，只适合自测和"不污染真实 GPS"的场景。

### 3.3 `removeTestProvider` 之后真实 GPS 的恢复语义

| Android | 恢复行为 | 证据 |
|---|---|---|
| 8.0 | 显式 `reinstate real provider if available`，从 `mRealProviders` 取回并重新 `addProviderLocked` | 【已验证-源码】`LMS` L2874-2900 |
| 10 | 同上，显式遍历 `mRealProviders` 找同名 provider 装回 | 【已验证-源码】`LMS` L3555 附近 |
| 12+ | `manager.setMockProvider(null)` → `MockableLocationProvider` 把 `mProvider` 切回 `mRealProvider`；若 manager 没有真实 provider（自定义名）则整个 manager 被移除 | 【已验证-源码】`LMS` L1315 / `MockableLocationProvider` L117 |

**要点**：
- 恢复是**立即**的，不需要重启、不需要重开 GPS。
- 但 `removeTestProvider` 同样受 appop 检查 —— appop 被 revoke 后**调不动**它（静默 return），此时只能重启或重新授权。
- 顺序很重要：`removeTestProvider` 之前先 `setTestProviderEnabled(false)` 不是必需的，但先停掉播放定时器能避免"移除后立刻又报一次"。

### 3.4 进程被杀之后 provider 是否残留

分两层说清楚，别混为一谈：

**【已验证-源码】框架侧不存在任何针对 mock provider 的清理路径。** 三条独立证据（均在 `android-15.0.0_r1` 上逐行核对）：

1. `addTestProvider` 传给 `MockLocationProvider` 的只有一个 `CallerIdentity`（uid / pid / package / attributionTag），是**纯数据**，不是 `IBinder`；因此框架手里根本没有可 `linkToDeath` 的对象。
2. `LocationProviderManager.java` 全文只有两处 `linkToDeath`（L1091、L1235），都在**客户端注册对象**上——一处是监听器 binder 的 transport（`LocationTransport.onRegister`），一处是 `PendingIntent` 注册（带 `mExpirationRealtimeMs` 过期闹钟）。两者都只服务于"位置请求"，与 mock provider 的归属无关。
3. `LocationManagerService.java` 既没有实现 `AppOpsHelper.LocationAppOpListener`（全文无 `onAppOpsChanged`），也没有注册包卸载广播来移除 mock provider；`MockableLocationProvider.setMockProvider()` 在传 `null` 之前一直持有 `mMockProvider` 引用（L117-129）。也就是说：**appop 被收回、App 被卸载、进程被杀，这三件事都不会触发 mock provider 的移除。**

**【推断】（运行期后果，需真机确认）** 由此可推：App 被 `am force-stop` / 用户上滑杀掉 / 被系统低内存回收后，mock provider 继续存在、继续喂假坐标、真实 GPS 继续被压制，直到显式 `removeTestProvider` 或重启（provider 是内存态）。

> 为什么后果只敢标【推断】：这是"代码里没有清理路径"的**否定性结论**，否定性结论无法仅凭读源码穷尽（OEM 可能加了自研清理、或某条我没找到的路径），所以代码事实标已验证、运行期行为标推断。
> 你们的 `TestProviderInjector.forceCleanup()` 冷启动兜底**正是针对这个推断设计的**，无论推断是否成立它都是对的（成立=必要，不成立=无害）。

**实现要求**（非常重要）：
- App **每次冷启动**都要做一次「清理 + 重装」：`removeTestProvider("gps")`（忽略 `IllegalArgumentException`）→ 再按需 `addTestProvider`。
- 退出/停止时必须走 `removeTestProvider`，并把它放在 `finally` 或前台服务 `onDestroy` 里。
- 崩溃后残留会导致"App 明明没开，定位还是假的/或真实 GPS 不工作了"，这是这类 App 最常见的用户投诉。
- 重启可清除（provider 是内存态），所以脚本/文档要提供「重启即恢复」的兜底话术。

### 3.5 一次成功注入的最小调用序

```
1. appops set <pkg> android:mock_location allow      (root / 用户手动选 mock 应用)
2. addTestProvider("gps", <properties>)               // 会顶掉真实 gps
3. setTestProviderEnabled("gps", true)                // 建议显式调用，见下
4. setTestProviderLocation("gps", <complete Location>) // time/accuracy/provider 必须有值
   ... 循环 4 ...
5. removeTestProvider("gps")                          // 收尾，恢复真实 GPS
```

- 第 3 步：Android 8 的 `MockProvider` 默认可用，但 12+ 的 `MockLocationProvider` 有 `setProviderAllowed()` 闸门，**显式 `setTestProviderEnabled(true)` 是跨版本最稳的做法**。【推断】（未逐一验证默认 allowed 值）
- 第 4 步建议同时覆盖 `gps` + `network` + `fused`：三者各自独立，`setTestProviderLocation` 一次只能喂一个 provider。
- 播放频率建议 ≤5 Hz（200 ms）：`MockLocationProvider` 用的是 `DIRECT_EXECUTOR`，每次 `reportLocation` 同步走完 `system_server` 的分发链路，频率过高会阻塞定位线程。

### 3.6 `fused` 与 GMS 的特殊性

- `FUSED_PROVIDER`（`"fused"`）的**真实实现**是 `/system/priv-app/.../com.android.location.fused`（一个独立 priv-app，AOSP 里位于 `frameworks/base/packages/FusedLocation`）。
- 你在自己进程里 `addTestProvider("fused", ...)` 时，Android 12+ 是把这个 **provider 名**在 `system_server` 层面替换成 mock，`com.android.location.fused` 这个 App 仍然活着但它的上报被丢弃 —— 效果上"接管了 fused"。
- **但 GMS 的 `FusedLocationProviderClient` 不一定读平台的 `fused` provider**：Google Play services 有自己的融合逻辑，它通常直接吃 `gps`/`network` + 传感器。所以：
  - 想影响 GMS，最可靠的是**同时覆盖 `gps` 与 `network`**（让 GMS 的输入源变假），再叠加 `fused`。
  - GMS 返回的 `Location` 是否带 mock 标记取决于 GMS 版本 —— 它可能自己不上报 mock 标记，这对阶段二是**好事**（但不可依赖）。
- 已知 AOSP 侧 reference：`data/etc/privapp-permissions-platform.xml` 里 `com.android.location.fused` 被授予 `INSTALL_LOCATION_PROVIDER`，说明它是以"真实 provider 注册者"身份工作的。【已验证-源码】

---

## 4. Magisk / KernelSU / APatch 的 `su` 差异

三者都提供**兼容的 `su -c "<command>"`**，从 App 角度调用方式一致：

```java
Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
```

| | Magisk | KernelSU | APatch |
|---|---|---|---|
| 提权原理 | patch boot ramdisk，改 init | patch 内核（需要内核源码 / GKI） | patch 内核（只需原厂 boot.img，不需要源码） |
| `su -c` | ✅ | ✅（`userspace/ksud/src/su.rs` 显式解析 `-c`、`-s/--shell`、`-g`、`-Z/--context`） | ✅（APatch 源自 KernelSU 用户态） |
| 模块目录 | `/data/adb/modules` | `/data/adb/modules`（同） | `/data/adb/modules`（同，APM） |
| module 格式 | zip + `module.prop` | **几乎相同** | **相同**（源码来自 KernelSU） |
| `system/` overlay | ✅ 内核内置 magic mount | ⚠️ **必须装 metamodule**（如 `meta-overlayfs`），否则 `system/` 不挂载 | ⚠️ 同 KernelSU（APM + metamodule 机制） |
| `.replace` 文件 | ✅ 支持 | ❌ 不支持，用 `REMOVE`/`REPLACE` 变量或 `mknod f c 0 0` | ❌ 同 KernelSU |
| BusyBox | `/data/adb/magisk/busybox` | `/data/adb/ksu/bin/busybox` | KernelSU 同路径 |
| 环境变量识别 | — | `KSU=true` | — |
| SELinux | 提供 `magiskpolicy`，su 域基本 unconfined | 提供 `sepolicy.rule` | **可选择不修改 SELinux**，靠 hook 绕过 |

证据：
- KernelSU 模块机制与 Magisk 的异同：<https://github.com/tiann/KernelSU/blob/main/website/docs/guide/difference-with-magisk.md>【已验证-文档】
- KernelSU 必须 metamodule 才会挂 `system/`：同文档 `module.md` 顶部 WARNING + `metamodule.md`【已验证-文档】
- KernelSU `su` 参数解析：<https://github.com/tiann/KernelSU/blob/main/userspace/ksud/src/su.rs>【已验证-源码】
- Magisk 模块结构（`module.prop` / `system/` / `skip_mount` / `post-fs-data.sh` / `service.sh`）：
  <https://topjohnwu.github.io/Magisk/guides.html>【已验证-文档】
- APatch 与 Magisk/KernelSU 的关系、SELinux 策略、模块 WebUI 兼容性：
  <https://apatch.dev/zh_CN/faq.html>、<https://apatch.dev/zh_CN/install.html>【已验证-文档】

**对项目的直接结论**：

1. `su -c` 这一层**不需要做三套适配**，`su -c 'sh /path/script.sh arg'` 在三个管理器上都能跑。
2. 需要适配的是**「能不能改 /system」**：Magisk 直接放模块 `system/` 就行；KernelSU/APatch 要额外确认用户装了 metamodule（脚本里应探测并给出明确报错，而不是静默失败）。
3. `su` 的可执行路径统一用 `su`（走 `PATH`）；不要硬编码 `/system/bin/su`（Magisk 下可能被 magic mount 到别处）。

---

## 5. 系统应用（priv-app）路线

### 5.1 先明确：这条路**解决不了 mock 标记**

这是最容易走弯路的地方。逐个权限看：

| 目标 | 需要的权限 | priv-app + allowlist 能否拿到 |
|---|---|---|
| `addTestProvider`（会置 mock 标记） | 仅 appop | 不需要系统应用 |
| `reportLocation`（≤9，不置标记） | `INSTALL_LOCATION_PROVIDER` | ✅ `signature\|privileged` |
| `injectLocation`（≥10，不置标记但只写 last-known） | `LOCATION_HARDWARE` + `ACCESS_FINE_LOCATION` | ✅ `signature\|privileged` / `dangerous` |
| 绕过 `isMock()` | — | ❌ `ACCESS_MOCK_LOCATION` 是 `signature`，**allowlist 无法授予** |

**所以系统应用路线的真实收益是**：在 Android 8/9 上可以用 `reportLocation` 做真正无痕的注入；在 Android 10+ 上**收益接近于零**（`injectLocation` 只写 last-known）。

### 5.2 Magisk 模块安装 priv-app 的具体步骤

模块目录（`/data/adb/modules/<id>/`）：

```
vloc_sysapp/
├── module.prop
├── system/
│   ├── priv-app/
│   │   └── VlocMotion/
│   │       └── VlocMotion.apk          # 文件名随意，必须 .apk
│   └── etc/
│       └── permissions/
│           └── privapp-permissions-vloc.xml
└── (可选) post-fs-data.sh / service.sh
```

`module.prop` 必需字段：

```
id=vloc_sysapp
name=VLoc System App
version=1.0
versionCode=1
author=vloc
description=Install com.vloc.motion as privileged app
```

- `id` 必须与目录名一致，且只含 `[a-zA-Z0-9._-]`。
- 模块目录建好后需要**重启**（magic mount 在 `post-fs-data` 阶段挂载，`PackageManagerService` 扫描在其后）。
- 直接往 `/data/adb/modules/` 写文件相当于"已安装模块"，重启即生效；不需要打 zip。若要走 Magisk App 安装，则需 `META-INF/com/google/android/update-binary` + `updater-script`（本仓库脚本采用前者，更简单）。
- 权限/上下文：`chmod 0755` 目录、`0644` apk、`chown 0:0`；SELinux 上下文用 `chcon -R u:object_r:system_file:s0`（或用 `restorecon`）。Magisk 挂载时通常会沿用源上下文，但显式设置更保险。

### 5.3 KernelSU / APatch 的额外前提

- 两者的模块格式与 Magisk 几乎一致，`/data/adb/modules/<id>/` 结构通用。
- **但 `system/` 目录不会自动挂载**：KernelSU 把挂载逻辑外置成 metamodule，用户必须先装 `meta-overlayfs`（或等价的提供 magic-mount 的 metamodule）。
  > "Only if your module needs to modify `/system` files (via the `system` directory) do you need to install a metamodule" —— KernelSU `module.md`
- 脚本必须探测：`ls /data/adb/modules/ | grep -i meta` 或读 `/data/adb/ksu/modules.img` 之类，**探测不到就明确报错并让用户去装 meta-overlayfs**，否则用户会看到"脚本成功、重启后什么也没发生"。

### 5.4 privapp allowlist 的必需字段与 bootloop 风险

```xml
<?xml version="1.0" encoding="utf-8"?>
<permissions>
    <privapp-permissions package="com.vloc.motion">
        <permission name="android.permission.INSTALL_LOCATION_PROVIDER"/>
        <permission name="android.permission.LOCATION_HARDWARE"/>
    </privapp-permissions>
</permissions>
```

规则（全部来自 AOSP 官方文档 <https://source.android.com/docs/core/permissions/perms-allowlist>，【已验证-文档】）：

| 规则 | 说明 |
|---|---|
| 必需属性 | `<privapp-permissions package="...">` + 若干 `<permission name="..."/>`；只允许 `package` 一个属性 |
| 文件位置 | `<partition>/etc/permissions/*.xml`，名字随意，惯例 `privapp-permissions-<DEVICE>.xml` |
| 同分区约束 | allowlist 只能授予**同一分区**上的 priv-app 的权限。`/system/priv-app` 的 App 必须由 `/system/etc/permissions/` 里的文件授权 —— 所以模块里两个路径都要写在 `system/` 下 |
| Android 8.0 | 允许"部分授予"，缺的权限只是不授予，设备能启动 |
| **Android 9+** | **必须显式 grant 或 deny 所有 `signature\|privileged` 权限，缺失会导致设备无法启动（bootloop）** |
| 拒绝写法 | `<deny-permission name="..."/>` |
| 缺权限排查 | `ro.control_privapp_permissions=log`（只记日志不拦截）/ `enforce`（拦截） |
| 只列真实存在的 App | allowlist 里写了分区上不存在的包名，该条目被忽略（不报错也不生效） |
| 平台权限必须列出 | `package="android"` 定义的权限（`INSTALL_LOCATION_PROVIDER`、`LOCATION_HARDWARE` 都是）必须 allowlist |

⚠️ **bootloop 风险是本路线最大的工程风险**：Android 9+ 上，一个 priv-app 若在 manifest 里声明了 `signature|privileged` 权限却没进 allowlist，`system_server` 会在 `PackageManagerService` 阶段直接 fatal。规避手段：
1. 脚本在安装前检查 `/system/etc/permissions/` 是否已有该包的条目，没有就**同时**写入 allowlist 文件；
2. 脚本安装前先备份并打印「救砖指引」（Magisk 安全模式 / `adb shell magisk --remove-modules` / 按住音量键）；
3. 只在 `ro.control_privapp_permissions` 为 `log` 或用户明确知情时才继续。

### 5.5 签名问题

- priv-app 的 APK **不需要平台签名**就能拿到 `signature|privileged` 权限（这正是 `privileged` 的含义）。
- 但如果设备上已经装了同包名的普通用户应用，且签名不同，系统应用会"覆盖"它（system 优先），数据可能保留但升级会失败。安装前应当 `pm uninstall --user 0 <pkg>`（保留数据用 `pm uninstall -k --user 0`），或确保两者签名一致。
- 平台签名的 App 才能拿 `signature` 权限（如 `ACCESS_MOCK_LOCATION`）—— 我们已经论证不需要它。

---

## 6. 方案结论表

| 方案 | 最低 Android | 需要 root？ | 是否留 mock 标记 | 适用场景 | 风险 |
|---|---|---|---|---|---|
| **A. appop + `addTestProvider("gps"/"network"/"fused")`**（阶段一主线） | 8.0 | 是（仅为了 `appops set`，root 不是注入本身的要求） | **是**（`isMock()==true`） | 阶段一：能改定位、能跑路径 | 检测类 App 一眼看穿；进程死残留；appop 被 revoke 后静默失效；`com.android.shell`/开发者选项会显示"正在使用模拟位置应用" |
| B. appop + 只加自定义 provider（如 `"vloc"`） | 8.0 | 是 | 是 | 自测、不影响真实 GPS | 系统/GMS/地图 App 都不会请求它 → **对改定位无效** |
| C. priv-app + `INSTALL_LOCATION_PROVIDER` + `reportLocation` | 8.0 | 是（装模块 + 重启） | **否**（源头注入） | 仅 Android 8/9 上追求真正无痕 | 10+ 该 API 已不存在；需 priv-app + allowlist（bootloop 风险）；违反 CTS；系统升级/OTA 后模块仍需重装 |
| D. priv-app + `LOCATION_HARDWARE` + `injectLocation` | 10 | 是 | 否 | 几乎无用：只写 last-known，且只在该 provider 还没有 last location 时生效 | 不能连续注入；需要 priv-app（收益远小于代价） |
| E. LSPosed 模块（抹标记 + 接管回调） | 8.0 | 是（LSPosed + Zygisk） | **接收端不可见** | 阶段二：对抗检测 | 只对 hook 作用域内进程有效；可被 native 检测 / 自身被检测；Android 15 上 LSPosed 兼容性依赖版本；GMS 是黑盒 |
| F. 内核层 hook（KPM / kprobe / LSM）拦截 GNSS HAL 或 `ioctl` | 8.0 | 是（KPM 需 APatch/KernelSU） | 否 | 理论最强隐蔽 | 与机型内核强耦合；跨厂商不可移植；开发/调试成本极高；kallsyms 依赖 |
| G. 替换 vendor GNSS HAL 实现（假 `android.hardware.gnss`） | 8.0 | 是 | 否 | 极客级方案 | 依赖 vendor 分区与厂商 ABI；每个机型单独适配；极易 bootloop |

**推荐路线**：
- 阶段一用 **A**（唯一现实可行、跨 8–15 的路径）。
- 阶段二用 **E**，并且优先 hook **接收端 App 进程**（改动小、可控），而不是 `system_server`（跨进程 hook 面大、稳定性差）。
- **C 只在明确要支持 Android 8/9 且愿意维护 priv-app 时考虑**，不要为 10+ 做它。
- F/G 不作产品路线，只作为研究分支。

---

## 7. 代码级注意事项（给实现者）

### 7.1 权限与授权

1. **不要**在 `AndroidManifest.xml` 声明 `ACCESS_MOCK_LOCATION`：它是 `signature`，声明了也拿不到，部分 ROM 的静态扫描会因此把 App 标成"mock 应用"。历史项目里声明它是因为 API <23 需要，本项目 `minSdk 26` 不需要。
2. 授权命令统一用 `cmd appops set <pkg> android:mock_location allow`（`appops` 二进制只是 `cmd appops` 的包装，`cmd` 在所有版本都存在）。
3. `appops set` 需要 root 或 shell 身份；**如果 App 已经有 root**，一条 `su -c` 就够，不需要引导用户去开发者选项。
4. 授权后**必须读回校验**：`cmd appops get <pkg> android:mock_location` 输出里应含 `allow`（不同版本格式不同，见 `scripts/root_grant_mock.sh` 的解析逻辑）。

### 7.2 注入

5. **provider 名字要覆盖三个**：`gps`、`network`、`fused`。只覆盖 `gps` 时，用 `FusedLocationProviderClient` 的 App 仍可能拿到真值。
6. `Location` 必须 complete：`time`（`System.currentTimeMillis()`）、`accuracy`（>0）、`provider`（与传入的 provider 名一致）、`elapsedRealtimeNanos`（12+ 的 `LocationResult.validate()` 会要求）。**建议直接 `new Location(LocationManager.GPS_PROVIDER)` 再逐字段赋值**，不要用空构造再漏字段。
7. `provider` 字段必须等于 `setTestProviderLocation` 的第一个参数。不一致虽然只写一条 EventLog（`b/33091107`），但会让 mock 标记的推断逻辑走偏。
8. 每次 `setTestProviderLocation` 前检查 provider 是否还在（用户可能中途 revoke appop / 手动清掉）；`IllegalArgumentException` 必须捕获，不能让前台服务崩。
9. 播放循环用 `Handler.postDelayed` 或 `ScheduledExecutorService`，**不要**用 `Thread.sleep` 阻塞，且必须能被 `onDestroy` 立刻打断。
10. 停止顺序：停播放 → `setTestProviderEnabled(false)`（可选）→ `removeTestProvider` × 3 → 把 `finally` 里的异常吞掉并记日志。

### 7.3 生命周期与残留

11. **冷启动自愈**：`App.onCreate` / 服务启动时先 `removeTestProvider("gps"/"network"/"fused")` 各一次（吞掉 `IllegalArgumentException`），再按需重装。这样上次崩溃的残留会被清掉。
12. **不要**在 `Application.onTerminate` 里做清理（Android 上基本不会被调用）。用前台服务的 `onDestroy` + `onTaskRemoved`。
13. 每次注入前记录 `SystemClock.elapsedRealtime()`，用于诊断"残留了多久"。

### 7.4 版本相关的坑

14. **Android 12+**：`Location.isMock()` 才是新 API；`isFromMockProvider()` 已 deprecated 但仍可用（服务端两条路径等价）。
15. **Android 12+**：`LocationManager.addTestProvider` 新增了 `Set<String> extraAttributionTags` 重载；用两参数重载即可（内部会补空集合）。
16. **Android 11+**：`addTestProvider` 的 AIDL 签名多了 `attributionTag`；但从 `LocationManager` 公开 API 调用时框架会自己填，不用管。
17. **Android 14/15**：`targetSdk 34+` 要求前台服务声明 `foregroundServiceType="location"` 且有 `FOREGROUND_SERVICE_LOCATION` 权限，否则启动即崩 —— 这是 App 层的问题，不是注入层，但会表现为"注入突然不工作了"。
18. **Android 15**：`OP_MOCK_LOCATION` 的默认模式仍是 `MODE_ERRORED`（源码 2810 行），没有放宽。
19. **Android 8 vs 9+ 的 `/system` 只读**：不要试图 `mount -o rw,remount /system`，现代设备上必然失败（dm-verity / AVB / 动态分区）。走模块。

### 7.5 子系统交互

20. **不要**同时用 `GoogleApiClient`/`FusedLocationProviderClient` 去读位置来做"自身回显"，会和注入打架；UI 上直接用注入源即可（同进程，读自己保存的状态）。
21. 如果 App 自己要监听注入结果（比如画轨迹），从 `LocationManager` 请求 `gps` 反而会拿到 mock（因为被你替换了），可以用来做**自检**。

### 7.6 与本项目实现的对应关系（代码核对）

| 本文结论 | 本项目对应实现 | 说明 |
|---|---|---|
| §1.3 appop 是唯一门槛，必须 `allow` | `location/RootShell.java` → `RootShell.grantMockAppop(String pkg)` | 由 `service/LocationService.java` 在开始时调用，返回 `RootShell.Result` 供 UI 显示；对应本文 §1.1/§1.3 |
| §1.3「appop 被拒时服务端静默 return、不抛异常」 | `location/TestProviderInjector.java` → `prepare()` 里的 `isProviderEnabled(provider)` 二次确认 | 正是本文 §1.3 那个陷阱的解法：不靠异常判断成功，改用 `isProviderEnabled` 兜底并抛 `SecurityException` |
| §3 `removeTestProvider` 会立即恢复真实 provider | `TestProviderInjector.release()` | 先 `setTestProviderEnabled(false)` 再 `removeTestProvider`，逐个 provider 吞异常；对应 §3.3 |
| §3.4 进程被杀后残留 → 冷启动自愈 | `TestProviderInjector.forceCleanup(Context, List<String>)`，由 `App.java` 在 `Application` 创建后用 `vloc-cleanup` 线程、`mode == IDLE` 时调用 | 与本文 §3.4/§7.3 的建议一致：`forceCleanup` 在启动时对上次用过的 provider 名各调一次 `removeTestProvider`（吞掉异常），清掉强杀残留 |
| §3.1 同名 test provider 已存在时的行为 | `prepare()` 捕获 `IllegalArgumentException` 后改走 `setTestProviderEnabled(provider, true)` | Android 8/10 上重复 `addTestProvider` 同名 mock 会抛 `already exists`；12+ 是直接替换可能不抛。`prepare()` 的 catch 对两种行为都能兜住 |

> 一句话：**实现与本文结论是对齐的**——唯一需要盯住的是 §1.3 的两种失败形态（`SecurityException` vs 静默 return），`prepare()` 的 `isProviderEnabled` 校验和 `App.java` 的 `forceCleanup` 正好各挡一头。

---

## 8. 证据来源清单

### AOSP 源码（tag 精确，可逐行复核）

| 内容 | 链接 |
|---|---|
| `addTestProvider` / `setTestProviderLocation`（15） | <https://cs.android.com/android/platform/superproject/+/android-15.0.0_r1:frameworks/base/services/core/java/com/android/server/location/LocationManagerService.java> |
| `addTestProvider` / `setTestProviderLocation`（12） | <https://cs.android.com/android/platform/superproject/+/android-12.0.0_r1:frameworks/base/services/core/java/com/android/server/location/LocationManagerService.java> |
| `addTestProvider` / `reportLocation` / `injectLocation`（8.0） | <https://cs.android.com/android/platform/superproject/+/android-8.0.0_r1:frameworks/base/services/core/java/com/android/server/LocationManagerService.java> |
| `addTestProvider` / `injectLocation` / `removeTestProvider`（10） | <https://cs.android.com/android/platform/superproject/+/android-10.0.0_r1:frameworks/base/services/core/java/com/android/server/LocationManagerService.java> |
| `addTestProvider` / `noteMockLocationAccess`（11） | <https://cs.android.com/android/platform/superproject/+/android-11.0.0_r1:frameworks/base/services/core/java/com/android/server/location/LocationManagerService.java> |
| `addTestProvider` / `setTestProviderLocation`（9） | <https://cs.android.com/android/platform/superproject/+/android-9.0.0_r1:frameworks/base/services/core/java/com/android/server/LocationManagerService.java> |
| `addTestProvider`（13） | <https://cs.android.com/android/platform/superproject/+/android-13.0.0_r1:frameworks/base/services/core/java/com/android/server/location/LocationManagerService.java> |
| `addTestProvider`（14） | <https://cs.android.com/android/platform/superproject/+/android-14.0.0_r1:frameworks/base/services/core/java/com/android/server/location/LocationManagerService.java> |
| `MockProvider.setLocation`（10，mock 标记） | <https://cs.android.com/android/platform/superproject/+/android-10.0.0_r1:frameworks/base/services/core/java/com/android/server/location/MockProvider.java> |
| `MockProvider.setProviderLocation`（11，mock 标记） | <https://cs.android.com/android/platform/superproject/+/android-11.0.0_r1:frameworks/base/services/core/java/com/android/server/location/MockProvider.java> |
| `MockLocationProvider`（mock 标记写入点） | <https://cs.android.com/android/platform/superproject/+/android-15.0.0_r1:frameworks/base/services/core/java/com/android/server/location/provider/MockLocationProvider.java> |
| `MockableLocationProvider`（真实 provider 替换语义） | <https://cs.android.com/android/platform/superproject/+/android-12.0.0_r1:frameworks/base/services/core/java/com/android/server/location/provider/MockableLocationProvider.java> |
| `LocationManagerService.injectLocation` 权限（15） | 同上 `LocationManagerService.java` |
| `Location.isMock` / `isFromMockProvider` / `setMock` | <https://cs.android.com/android/platform/superproject/+/android-12.0.0_r1:frameworks/base/location/java/android/location/Location.java> |
| `ILocationManager.aidl`（`injectLocation` 的 `@EnforcePermission`） | <https://cs.android.com/android/platform/superproject/+/android-15.0.0_r1:frameworks/base/location/java/android/location/ILocationManager.aidl> |
| `OP_MOCK_LOCATION` 默认 `MODE_ERRORED` | <https://cs.android.com/android/platform/superproject/+/android-15.0.0_r1:frameworks/base/core/java/android/app/AppOpsManager.java> |
| `noteOp` 在 `MODE_ERRORED` 抛 `SecurityException` | 同上 |
| `ACCESS_MOCK_LOCATION` / `INSTALL_LOCATION_PROVIDER` / `LOCATION_HARDWARE` 的 protectionLevel | <https://cs.android.com/android/platform/superproject/+/android-15.0.0_r1:frameworks/base/core/res/AndroidManifest.xml> |
| AOSP 自带 privapp allowlist 实例（`com.android.location.fused` → `INSTALL_LOCATION_PROVIDER`） | <https://cs.android.com/android/platform/superproject/+/android-15.0.0_r1:frameworks/base/data/etc/privapp-permissions-platform.xml> |

### 官方文档

| 内容 | 链接 | 等级 |
|---|---|---|
| `addTestProvider` / `setTestProviderLocation` 的 `@throws SecurityException` 与"可被 `isMock()` 识别" | <https://developer.android.com/reference/android/location/LocationManager> | 【已验证-文档】 |
| priv-app 定义、`/etc/permissions`、`privapp-permissions.xml` 格式、Android 9+ 缺权限不启动、`ro.control_privapp_permissions` | <https://source.android.com/docs/core/permissions/perms-allowlist> | 【已验证-文档】 |
| Magisk 模块结构（`module.prop`、`system/`、`skip_mount`、脚本） | <https://topjohnwu.github.io/Magisk/guides.html> | 【已验证-文档】 |
| KernelSU 与 Magisk 的模块差异、`KSU=true`、`.replace` 不支持 | <https://github.com/tiann/KernelSU/blob/main/website/docs/guide/difference-with-magisk.md> | 【已验证-文档】 |
| KernelSU `system/` 需要 metamodule | <https://github.com/tiann/KernelSU/blob/main/website/docs/guide/module.md>、<https://github.com/tiann/KernelSU/blob/main/website/docs/guide/metamodule.md> | 【已验证-文档】 |
| KernelSU `su` 支持 `-c/-s/-g/-Z` | <https://github.com/tiann/KernelSU/blob/main/userspace/ksud/src/su.rs> | 【已验证-源码】 |
| APatch 与 Magisk/KernelSU 的差异、SELinux 策略、模块 WebUI 兼容 | <https://apatch.dev/zh_CN/faq.html> | 【已验证-文档】 |
| APatch 安装方式（patch boot.img，仅 ARM64、内核 3.18–6.12） | <https://apatch.dev/zh_CN/install.html> | 【已验证-文档】 |

### 待真机验证项（本文标【推断】的部分）

1. 各版本 `MockLocationProvider` / `AbstractLocationProvider` 的**默认 `allowed` 状态**；`setTestProviderEnabled(true)` 在哪些 ROM 上确实是必需的。
2. 进程被杀后 provider 残留的确切时长（是否会被 `LocationManagerService` 的空闲回收清掉）。
3. GMS `FusedLocationProviderClient` 返回的 `Location` 在各 GMS 版本上是否带 mock 标记。
4. 各 OEM（MIUI / ColorOS / HarmonyOS）是否改写了 `Location` 的私有字段名（影响阶段二）。

---

## 附：本次调研的抓取方式

AOSP 源码通过 `https://android.googlesource.com/platform/frameworks/base/+/refs/tags/<tag>/<path>?format=TEXT`（base64）拉取后解码比对，因此文中行号对应**该 tag 的确切内容**，可直接用 `cs.android.com` 同 tag 复核。
