# 阶段二：LSPosed 隐藏模块（防"模拟位置"识别）

> 技术细节与证据（AOSP 源码行号、hook 点全清单、有效性矩阵）见 [`mock-detection.md`](mock-detection.md)。
> 本文是**安装与使用手册**。

## 1. 它解决什么问题

阶段一（主 App）通过 root + `addTestProvider` 注入坐标，系统会在 `Location` 对象上**强制**盖一个
"这是模拟位置"的标记：

- Android 8–11：`Location.isFromMockProvider() == true`
- Android 12+：`Location.isMock() == true`（`isFromMockProvider()` 变成它的别名）

服务端 `MockLocationProvider.setProviderLocation()` 无条件盖戳，App 侧无法去掉（文档 `mock-detection.md` §2.1/§2.2 有源码证据）。

本模块（`hidden/`，v0.4.0）在**目标 App 进程内**用 LSPosed hook 掉六类读取路径：

| 类别 | hook | 效果 |
|---|---|---|
| Location 标记 | `Location#isFromMockProvider()` | 返回 `false` |
| Location 标记 | `Location#isMock()` | 返回 `false`（Android 12+） |
| Location 标记 | `Location#setMock(boolean)` / `setIsFromMockProvider(boolean)` | 空操作，进程内无法再置位 |
| Location 标记 | `Location#getExtras()` | 清洗 key 含 `mock` 的残留项（返回副本，不改原对象） |
| Settings 旁证 | `Settings$Secure/$System/$Global` 的 `getString/getInt/getLong(*)` | key=`mock_location` 时给"不存在"语义（String 返回 null、数值返回 0） |
| Settings 旁证 | `Settings$NameValueCache#getStringForUser`（@hide） | 兜底，防止绕过 Settings 封装直读缓存 |
| AppOps 旁证 | `AppOpsManager.checkOp*/unsafeCheckOp*`（8 个方法） | op=`android:mock_location` 时表现为"无权限"（checkOp/unsafeCheckOp 抛 SecurityException，其余返回 MODE_ERRORED，与真机语义一致） |
| AppOps 旁路 | `getPackagesForOps` / `getOpsForPackage` | 从入参数组里剔除 mock op；剔除后为空返回空 List |
| AppOps 旁路 | `noteOp/startOp/finishOp` 系列 | mock op 按"无权限"处理（throwing 变体抛 SecurityException，`*NoThrow` 返回 MODE_ERRORED，finishOp 空操作） |
| ContentProvider 旁路 | `ContentResolver#query`（3 重载）/ `#call` | 直查 `content://settings/{secure,system,global}` 且条件命中 `mock_location` 时返回空结果；`GET_*` 读取 mock 设置返回 `Bundle{value=null}` |
| Parcel 旁路 | `Location#writeToParcel` | 序列化前临时清 mock 位（`mFieldsMask & ~HAS_MOCK_PROVIDER_MASK`，旧版布尔字段同样处理），写完只把这一位恢复，避免跨进程/上报仍带标记 |
| 环境回放 | `TelephonyManager#getAllCellInfo` / `#getCellLocation` | 按主 App 快照回放 CellInfoGsm/Lte/Nr 与 GsmCellLocation；`hide` 模式返回空列表 / null |
| 环境回放 | `WifiManager#getScanResults` / `WifiInfo#getBSSID/#getSSID` | 回放 Wi-Fi 扫描列表与当前连接信息；`hide` 模式返回空列表 / null |
| 计步回放 | `SensorManager#registerListener`（STEP_COUNTER / STEP_DETECTOR） | 真实回调改写为虚拟步数；按步频合成 DETECTOR 事件；`unregisterListener` 同步翻译；无数据时透传 |
| IMU 伪造 | `SensorManager#registerListener`（ACCELEROMETER / LINEAR_ACCELERATION / GYROSCOPE / GRAVITY） | 按步态波形 50Hz 合成事件；重力随姿态旋转；无数据时透传 |
| 姿态回放 | `registerListener`（ROTATION_VECTOR / MAGNETIC_FIELD / GAME_ROTATION_VECTOR / ORIENTATION） | 航向跟随路线 + 每步俯仰/侧倾/偏航摆动；game RV 用固定随机偏航基准；`attitude=false` 时透传真实值 |

> 坐标本身仍由主 App 注入，模块不参与定位，只"擦标记"。

## 2. 前置条件

- 设备已 root（Magisk / KernelSU / APatch 均可）
- 已安装 **LSPosed**（Zygisk 或 Riru 版本均可；Android 8.1–15 支持）
- 目标 App 与主 App 装在同一台设备

## 2.1 版本

| 版本 | 变化 |
|---|---|
| 0.1.0 | Location 标记遮蔽（isMock / isFromMockProvider / setMock / getExtras） |
| 0.2.0 | 新增 Settings `mock_location` 与 AppOps 自查旁证 hook |
| 0.3.0 | 新增 ContentResolver 直查旁路、AppOps 枚举/noteOp 旁路、Parcel 序列化清位 |
| 0.4.0 | 新增基站 / Wi-Fi 环境快照回放与隐藏（数据来自主 App 的 `content://com.vloc.motion.env/snapshot`） |
| 0.5.0（并入主 App 0.13.0） | 新增计步传感器回放（步数/步频与虚拟路线自洽） |
| 0.4.0 修订 | 优先用 Android 13+ 的 `CellInfo*` 全参构造器（避开 `CellInfoNr.mCellSignalStrength` 为 final 的写入风险）；`ScanResult.SSID` 写带双引号值并与 `wifiSsid` 保持一致；`WifiInfo.getSSID()` 沿用原返回值的引号风格 |
| 0.17.0（主 App 侧） | 步数快照 JSON 追加 `satellites` / `cn0Mean` / `cn0Max`（值来自拟真引擎），为模块侧回放 GNSS 附属数据预留字段 |
| 0.18.0 | GNSS 卫星回放：hook `GnssStatus` 的 13 个读取方法 + `registerGnssStatusCallback` 全重载，按 1Hz 向目标 App 投递与虚拟路线自洽的假卫星（数据来自主 App steps JSON 的 `gnss` 数组，`version:2`） |
| 0.19.0 | GNSS 数据改为真实星历驱动：主 App 内置 CelesTrak TLE 星表，按目标位置 + UTC 时间用轨道传播计算真实可见卫星（不同城市不同、随时间升落），模块侧协议不变 |

## 3. 安装与启用

```bash
adb install -r dist/virtual-motion-0.18.0.apk
```

1. 打开 **LSPosed 管理器** → 底部「模块」→ 找到 **虚拟运动**（包名 `com.vloc.motion`）→ 启用
2. 进入该模块的「作用域」，勾选**要欺骗的目标 App**（例如某个游戏 / 打卡 App）
3. **不要**勾选「系统框架 / android」：当前版本没有 hook `system_server`，勾了既不生效也增加风险
4. 强制停止目标 App 再打开（LSPosed 在进程启动时注入）
   - 目标 App 有多个进程时，作用域要把它**所有进程的包名**都勾上（同包名的多进程会被同一 scope 覆盖）
   - 若目标 App 走 `FusedLocationProviderClient` 且表现像"拿不到位置"而不是"拿到带标记的位置"，
     可再把 `com.google.android.gms` 加入作用域（风险自担：会影响该设备上所有使用 GMS 定位的 App）
5. 回主 App「虚拟运动」运行开工向导并开始注入

> 如果之前装过独立模块（`com.vloc.motion.hidden`），请先
> `adb uninstall com.vloc.motion.hidden`，否则两个模块可能同时 hook 目标进程。

验证：目标 App 运行后看日志

```bash
adb logcat | grep VLocHidden
# [VLocHidden] hooks installed in <目标包名>
# [VLocHidden] hooked Location#isMock() -> false
# ...
```

## 4. 能力边界（诚实说明）

| 检测手段 | 本模块能否通过 |
|---|---|
| `Location.isMock()` / `isFromMockProvider()` | ✅ |
| `Location.getExtras()` 里的 `mockLocation` | ✅ |
| 反射直读 `Location` 内部字段 / 自定义 `Location` 子类重写 getter | ⚠️ 部分（`mFieldsMask` 有意不改写，见下） |
| `Settings.Secure.getInt/getString("mock_location")` | ✅（v0.2.0 起） |
| `AppOpsManager` 自查 `mock_location` | ✅（v0.2.0 起，8 个 check 方法 + v0.3.0 noteOp/startOp） |
| 直连 ContentProvider 查 `content://settings/{secure,system,global}` | ✅（v0.3.0 起，带 selection/selectionArgs/单项 URI/Bundle 查询参数匹配） |
| `getPackagesForOps` / `getOpsForPackage` 枚举 | ✅（v0.3.0 起；`ops==null` 的全量查询仍是残留旁路） |
| `Location` 序列化（Parcel/AIDL）后跨进程上报 | ✅（v0.3.0 起，`writeToParcel` 前清位） |
| 基站指纹与 GPS 位置交叉校验（`getAllCellInfo`/`getCellLocation` 反查） | ✅ 回放模式（v0.4.0 起，取决于快照质量与距离）；或用隐藏模式规避 |
| Wi-Fi BSSID 地理位置交叉校验 | ✅ 回放/隐藏（v0.4.0 起） |
| 运营商 MCC/MNC、网络代际（`getNetworkOperator`/`getDataNetworkType`）一致性 | ❌ 未 hook（v0.5 计划） |
| 传感器物理一致性（加速度/陀螺/计步） | ❌ 需要伪造 sensor HAL，应用层做不到 |
| GNSS 卫星状态 `GnssStatus`（卫星数/Svid/星座/仰角/方位角/载噪比/是否参与定位） | ✅ v0.18.0 起在目标 App 进程内回放；v0.19.0 起数据来自真实 TLE 星表，按位置/时间计算（与真实观测几何一致） |
| GNSS `GnssMeasurements` / `GnssNavigationMessage` / `GnssAntennaInfo` / NMEA 句子 | ❌ 未实现，仍可被与"固定假卫星"交叉校验 |
| Google Play Services `FusedLocationProviderClient` | ❌ GMS 有独立融合逻辑，建议在设置里同时勾选 `gps`+`network` 让它的输入源变假 |
| native / HAL 层直接读 GNSS | ❌ |
| 行为检测（速度/轨迹不自然、基站/Wi-Fi 指纹对不上） | ❌ |
| 系统「开发者选项 → 模拟位置应用」列表显示主 App | ❌ 系统 UI 行为，无法脚本隐藏 |
| 目标 App 把 `Location` 序列化（Parcel/AIDL）后交给**其他进程或服务端**判定 | ✅ v0.3.0 起 `writeToParcel` 前清位（并发反射读字段的瞬时窗口除外） |
| 栈帧特征（`Thread.getStackTrace()` 中可见 Xposed 帧） | ❌ 方案固有 |
| 已安装 LSPosed 模块列表 / root 痕迹 | ❌ 需 DenyList / Shamiko / susfs 等配合 |

结论：v0.2.0 在 0.1.0 的 `isMock` 遮蔽之上补了 Settings / AppOps 两类旁证，能挡住最常见的
"读标记 + 读设置 + 查 appop"三连检测；但**仍不是"无痕"**——直连系统服务、序列化上报、物理一致性检测都不在能力范围内。
更高强度需要 hook `system_server`（B 层）与 `com.google.android.gms`（C 层），风险高、未实现，见 `mock-detection.md` §3。

## 5. 卸载 / 回滚

1. LSPosed → 模块 → 关闭「虚拟运动」的启用
2. 取消作用域勾选
3. 卸载主 App 即同时移除模块（`adb uninstall com.vloc.motion`）
4. 主 App 停止注入（`removeTestProvider`）后定位即恢复真实值

模块所有 hook 都是"独立 try/catch + 找不到就跳过"，单个 hook 失败不会让目标 App 崩溃；
即便如此，也建议先在**不重要的测试 App** 上验证。

## 5.1 v0.3.0 已知风险

- `ContentResolver#query` 在热路径：目标 App 每次内容查询都会经过一次 authority 字符串比较（开销很小）
- 若检测代码把 `mock_location` 和其它设置放在**同一条批量查询**里，整条查询会被置空，可能导致同批其它设置读不到（极罕见）
- `SettingsProvider.call` 返回的 `Bundle{value=null}` 与真机一致；若检测代码期望 `String` 直接强转，只有查 mock_location 这一条会异常
- `noteOp/startOp` 对 mock op 抛 `SecurityException`：若目标 App 自己用 mock op 做合法操作会被打断（正常 App 不会）
- `writeToParcel` 期间临时改写共享对象字段：其它线程若**反射**同一瞬间读 `mFieldsMask` 会看到 0（读 getter 不受影响）
- 个别 ROM 的 hidden API 限制可能导致 `mFieldsMask` 反射失败，该 hook 自动跳过（日志可见）

## 5.2 v0.4.0 环境回放的风险

- 快照来自**当前设备真实环境**：目标位置距采集地越远，回放越容易被基站/Wi-Fi 地理位置库反查识破（向导里 >30 km 建议用「隐藏」）
- `hide` 模式语义矛盾：目标 App 看到"无基站 / 无 Wi-Fi，但 GPS 有值"，与真实设备不符
- 回放数据会**绕过目标 App 的权限门**：即使目标 App 没有定位/电话权限也能拿到数据，可能被"权限状态 vs 数据"交叉检测发现
- 首次查询最坏阻塞 800 ms（之后 3 秒缓存）；只在主 App 进程存活时才有数据
- 细粒度字段为缺省值（BSIC/PSC/bands/cqi/TA、ScanResult.capabilities 固定 `[ESS]` 等）
- Android 10 上 NR 小区无法构造，自动跳过；wcdma/cdma 未实现
- 回放保真度依赖主 App 快照质量：建议快照含 3–8 条 cell（serving + 邻区，信号有梯度）、2–5 条 Wi-Fi 且 BSSID 为真实 OUI

## 6. 免责声明

仅供**自有设备上的测试、开发调试、隐私保护**等合法用途。
伪造位置欺骗他人、刷单打卡、违反平台规则或法律法规的行为与作者无关。
