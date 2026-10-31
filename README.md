# 虚拟运动（root 版）

> **专注虚拟定位与跑步模拟**：root 注入定位、路径播放、拟真轨迹、GNSS 卫星回放、
> 计步 / IMU / 姿态回放、LSPosed 隐藏模块、system_server 系统级注入、Zygisk 传感器模块。
>
> 当前版本 **v0.37.1** · 相机 / 虚拟摄像头相关功能已于 v0.37.0 **完全移除**。

<p>
  <a href="https://github.com/hb-degithub/I-love-sports/actions/workflows/android.yml">
    <img src="https://github.com/hb-degithub/I-love-sports/actions/workflows/android.yml/badge.svg" alt="Android CI"></a>
  <a href="https://github.com/hb-degithub/I-love-sports/releases/latest">
    <img src="https://img.shields.io/github/v/release/hb-degithub/I-love-sports" alt="Release"></a>
  <img src="https://img.shields.io/badge/license-PolyForm%20Noncommercial%201.0.0-red" alt="禁止商用">
</p>

## 📦 下载

最新构建（CI 自动产出）：**[Releases](https://github.com/hb-degithub/I-love-sports/releases/latest)** ——
`virtual-motion-<版本>.apk` + `.sha256`；每次打 `v*` tag 会自动构建并发布。

> ## 🚫 免责声明（必读）
>
> **本项目仅供学习、研究与自有设备上的技术验证使用。严禁用于任何违规、违法用途**，包括但不限于：
> 伪造运动数据以通过打卡/考核/比赛、伪造定位骗取补贴或考勤、绕过第三方应用的风控与反作弊、
> 在他人设备上使用、以及任何商业化的"代打卡"服务。
>
> 使用本项目**违反绝大多数应用的用户协议**，可能导致账号封禁、数据清零、设备不稳定、
> 保修失效，乃至承担民事/刑事责任。**作者不对可用性、合法性与后果作任何担保，
> 一切后果由使用者自行承担。**
>
> 完整条款见 [DISCLAIMER.md](DISCLAIMER.md)。不同意者请勿使用、复制或分发。

> ## ⛔ 许可：禁止商用
>
> 本项目使用 **PolyForm Noncommercial License 1.0.0**（[LICENSE](LICENSE) / [中文要点](LICENSE-ZH.md)）：
>
> - ✅ 个人学习、研究、自有设备技术验证、非商业二次开发与分发（需保留声明）
> - ❌ **任何商业用途一律禁止**：出售、付费下载、捆绑预装、代打卡/代跑服务、企业内部商业化业务等
> - 商业授权须取得作者书面许可。

Android 虚拟定位 App：通过 **root** 向系统注入伪造定位，可在地图上规划路径，按设定速度沿路径移动。

本项目分两条注入路线（可在设置里切换）：

| 路线 | 位置 | 生效范围 | 模拟标记 | 依赖 |
|---|---|---|---|---|
| **测试 Provider 注入**（默认） | 主 App 进程 | 全设备（同名 gps/network/fused Provider） | 有，靠 LSPosed 模块擦除 | root + appops |
| **系统级注入**（可选） | system_server | 全设备，**所有 App 自动生效** | **无**（Location 由模块新建） | root + LSPosed 勾选「系统框架」 |

## 功能

### 注入与反检测

- 🛰️ **系统级注入（v0.33.0，可选）**：定位与 GNSS 由隐藏模块在 **system_server** 内改写，
  对所有 App 生效、**不带 `isMock`/`isFromMockProvider` 标记**，无需 appops 与测试 Provider。
  需在 LSPosed 勾选「系统框架」并重启系统。见 [`docs/system-injection.md`](docs/system-injection.md)
- 🧩 **传感器系统级注入（v0.34.0，可选）**：配套 **Zygisk 模块**（Magisk 刷入）注入每个 App 进程，
  改写计步 / 加速度 / 陀螺仪 / 姿态事件，无需 LSPosed 作用域。见 [`docs/zygisk-sensors.md`](docs/zygisk-sensors.md)
- ⚙️ **设置重构（v0.39.0）**：设置按「注入方式 / 拟真与反检测 / 定位参数 / 行为」四组分类，
  补齐此前只能读不能改的**水平定位精度**，去掉「目标配速」里重复的速度混淆开关；
  设置项清单由 `core/SettingsSchema` 描述并由单测守护
- 🛡️ **反检测补强（v0.38.0）**：provider 列表规范化（隐藏自定义测试 Provider 指纹）、
  `elapsedRealtimeNanos`/`time` 一致性校正、`accuracy` 抖动兜底、NMEA 回放（`$GPGGA`/`$GPRMC`）
- 🔐 root 注入：自动 `su` 授权 → `appops set <pkg> android:mock_location allow` → 接管 `gps`/`network`/`fused` Provider
- 🛑 还原：停止后移除测试 Provider，恢复系统真实定位
- 🔐 Root 检测显式化：主界面与开工向导都显示 Root 状态，可一键重新检测/申请授权（兼容 Magisk / KernelSU / APatch）

### 定位与轨迹

- 🛰️ GNSS 卫星回放（v0.19.0）：内置 CelesTrak 真实 TLE 星表（GPS / GLONASS / Galileo / 北斗共 143 颗），按**目标位置 + 当前 UTC 时间**用轨道传播算出此刻真实可见的卫星（仰角/方位角/载噪比，通常 30–70 颗），模块在目标 App 进程里 hook `GnssStatus` 读取方法与 `registerGnssStatusCallback` 按 1 Hz 回放 —— 不同城市看到的星座不同、卫星会随时间升落，不再是凭空一套固定星
- 🗺️ 地图选点：轻点地图落图钉 → 弹窗确认「添加为路径点 / 临时定位 / 取消」，不会误触
- ✋ 点位编辑：点击图钉可删除或临时定位，长按拖动可微调位置，折线实时跟随
- 🛣️ 路径播放：按 1–120 km/h 沿路径插值移动，支持循环；地图上实时显示当前位置
- 🧭 朝向/速度：注入的 Location 同步写入 bearing、speed、accuracy，插值走大圆弧
- 🎛️ 拟真模式：位置漂移按**时间连续**的均值回归游走生成（按 √dt 缩放，5 Hz 注入也不会让轨迹发跳，v0.19.1）、速度/精度/朝向噪声、GNSS 卫星数/载噪比、起步 2 秒缓动，降低合成轨迹特征
- ⛰️ 海拔：支持基础海拔与逐点海拔（GPX `<ele>` 读写），注入 altitude / verticalAccuracy / Android 14 MSL 高度
- 🪟 前台悬浮窗：在其它应用上层显示速度/里程/海拔，三按钮「开始 / 暂停 / 停止移动」，可拖动
- 👟 步数自洽：时变步态模型推导步数/步频/步幅（同一次模拟内步频、步幅会自然波动），模块回放到计步传感器（STEP_COUNTER / STEP_DETECTOR）
- 📈 IMU 伪造：按同一套步态波形合成加速度计 / 线加速度 / 陀螺仪 / 重力（50Hz），重力随姿态旋转，与其他传感器自洽
- 🧭 姿态回放：旋转矢量 / 磁力计 / 游戏旋转矢量 / 方向传感器全部按"跑步摆动"生成（航向跟随路线，俯仰/侧倾/偏航随步频摆动），设置里可关闭
- 🧭 两段式启动：主界面「开始」只把定位定到起点并保持；路线移动要在悬浮窗点「开始」（暂停/停止移动只冻结路线、定位保持在原地；只有主界面「彻底停止」恢复真实定位）
- 🏁 到达自动停止（v0.19.3）：跑到目标距离（或配速计划跑完）时先显示「已到达」，2.5 秒后自动彻底停止并恢复真实定位；可在设置里关闭（关闭后旧行为：定位冻结在终点、需手动「彻底停止」）
- 🪟 悬浮窗自检：显示模块状态（未注入 / 已注入未读传感器 / 读取中）、里程海拔步频；点标题行可折叠
- ⏸️ 暂停/到达不停报：暂停与到达终点时按 1Hz 持续上报同一坐标，避免"定位冻结"被检测
- 🎲 配速自然度（v0.19.2）：速度由四个时间尺度叠加 —— 秒级抖动 ±2%、分钟级慢变 ±3%、公里级地形 ±4%、逐分钟累积疲劳（上限 8%）、平均约 2 分钟一次的冲刺/减速事件；每公里分段与配速曲线不再是直线
- 📌 内置路线：首次启动写入「田径场（示例）」（用户提供的 5 点坐标，已首尾相连成闭环）
- 🌏 坐标系统校正：路线按 WGS-84 存储与注入，显示在国内底图（高德/GCJ-02）时自动双向转换；内置田径场路线已按高德卫星图逐点核对对准跑道
- 🧩 预设路线：标准 400m 跑道、800m 圆环、2km/3km 矩形环线、1km 直线、2km 往返，以地图中心/当前位置一键生成
- 🎯 目标配速（选择器 + 自动计算）：选单圈距离 / 目标距离 / 配速 / 最大速度，自动算圈数、实际距离与目标时间，实时预览；配速模式隐藏手动速度，速度只有一个来源
- 🧭 开工向导：① 选目标位置 → ② 选模拟路线 → ③ 采集/配置基站与 Wi-Fi 环境 → 开始
- 📚 路线库：命名保存、载入、删除多条路线
- 📡 环境一致性：采集周围基站/Wi-Fi 快照，模块在目标 App 内回放或隐藏（防基站/Wi-Fi 交叉校验）
- 🧩 单 APK 双身份：同一安装包既是主 App，也是 LSPosed 模块（`assets/xposed_init` + `xposedmodule` 元数据）
- 📍 首页定位按钮：一键读取设备**真实位置**，地图居中显示；可直接「添加为路径点 / 临时定位到这里」
- 🗺️ 地图源可切换：默认高德（国内可用），另备高德卫星 / OSM / Carto；地图空白时可「手动输入坐标」选点
- 📥📤 GPX：导入/导出路径轨迹
- 🔔 前台服务 + 通知栏暂停/停止

### 界面与交互

- 🧭 **交互重构（v0.35.0）**：菜单从 15 个平铺项收敛为「4 个一级动作 + 3 个分组子菜单」；
  主界面底部控件按状态显示 —— 运行中自动隐藏选点/速度/循环控件避免误触，只留主操作与「彻底停止」；
  显示规则抽到 `core/MotionUiState`，由单元测试固定（运行中永不暴露可编辑控件等 9 条规则）

## 交付物

从 **v0.6.0** 起只有一个 APK：**主 App 与 LSPosed 隐藏模块已合并**（模块内置在安装包里，
未在 LSPosed 中启用时不会注入任何进程）。

| 文件 | 说明 |
|---|---|
| `virtual-motion-0.37.1.apk` | 主 App + 内置隐藏模块（`Releases` 里下载） |
| `vloc-zygisk-v0.37.1.zip` | Zygisk 传感器模块（可选，Magisk 刷入） |

```bash
adb install -r virtual-motion-0.37.1.apk
```

> 升级提示：如果之前装过独立的隐藏模块，先 `adb uninstall com.vloc.motion.hidden`，
> 然后在 LSPosed 里启用「虚拟运动」并勾选目标 App。

## 目录结构

```
app/src/main/java/com/vloc/motion/
├── App.java                       # osmdroid 初始化
├── core/                          # 纯 Java，可单元测试（22 个）
│   ├── GeoMath / Route / RoutePlayer      # 距离、路径、播放状态机
│   ├── RealismEngine / PacePlan           # 拟真漂移、目标配速
│   ├── StepEngine / ImuWaveform / GaitAttitude  # 步态与 IMU/姿态波形
│   ├── GnssTle / GnssOrbit / GnssSky      # 真实 TLE → 轨道传播 → 可见卫星
│   ├── GpxCodec / RouteStore / BuiltinRoutes / PresetRoutes
│   ├── ChinaCoord / TileSources / EnvSnapshot / Fix / GoalPlanner
│   └── MotionUiState                      # 界面显示规则（可测）
├── location/
│   ├── RootShell                          # su 执行、appops 授权
│   ├── TestProviderInjector               # addTestProvider / setTestProviderLocation
│   ├── RealLocationProvider / GnssCollector / EnvCollector / ModuleCheck
├── service/
│   ├── LocationService                    # 前台服务：10Hz 推进 + 注入（可切系统级）
│   ├── LocationSession / StepSession / GnssSession / EnvSession  # 下发通道（ContentProvider）
│   ├── EnvProvider                        # content://com.vloc.motion.env/{location,steps,gnss,snapshot}
│   ├── VlocStateFile                      # 传感器状态（供 Zygisk 模块 mmap）
│   └── Session                            # 进程内共享状态
├── hidden/                        # 内置 LSPosed 模块（同 APK）
│   ├── HookEntry                          # 入口：isMock/Settings/AppOps/Provider/Parcel
│   ├── SystemHooks                        # system_server 系统级定位/GNSS 注入
│   ├── GnssReplay / StepReplay / ImuReplay / EnvReplay / EnvSnapshot
└── ui/
    ├── MainActivity / SetupActivity       # 地图主界面 / 开工向导
    ├── FloatingWindow                     # 前台悬浮窗
    └── Diagnostics / TileProviderTuner

app/src/main/assets/
├── xposed_init                    # LSPosed 模块入口声明
└── gnss_tle.txt                   # GNSS 真实星表（GPS/GLONASS/Galileo/北斗，143 颗）

docs/
├── usage.md                       # 使用手册
├── system-injection.md            # system_server 系统级注入（定位/GNSS）
├── zygisk-sensors.md              # Zygisk 传感器模块
├── phase2-hidden.md               # 隐藏模块安装手册
├── root-injection.md              # root 注入方案调研（AOSP 源码级）
├── mock-detection.md              # 模拟位置检测手段与绕过矩阵
└── cell-wifi-spoofing.md          # 基站 / Wi-Fi 环境伪装

zygisk/                            # Zygisk 传感器模块（C++ + Magisk 打包）
scripts/                           # update_gnss_tle.py / root_grant_mock.sh / install_system_app.sh
hidden/                            # v0.4.0 独立模块源码（历史参考，不再构建）
```

## 构建

工具链放在 `.toolchain/`（JDK 17 + Android SDK 35 + Gradle 8.9，已gitignore）：

```bash
# 有 wrapper，任意机器可直接构建（CI 用同一套命令）
./gradlew :app:testDebugUnitTest   # 134 个单元测试
./gradlew :app:assembleDebug       # 产物：app/build/outputs/apk/debug/app-debug.apk

# 本仓库开发机还可用 .toolchain/ 里的固定工具链（JDK 17 + SDK 35 + Gradle 8.9）
source .toolchain/env.sh && gradle :app:assembleDebug

# Zygisk 传感器模块（需要 NDK）
export ANDROID_NDK_ROOT=/path/to/ndk && bash zygisk/build.sh   # → zygisk/out/vloc-zygisk.zip
```

CI：`.github/workflows/android.yml`（push/PR 跑测试+构建 APK）、`release.yml`（打 `v*` tag 自动发 Release，
含 APK + SHA256 + Zygisk zip）、`zygisk.yml`（原生模块编译）。

## 使用

1. 安装 APK，首次启动授予**定位**权限（Android 13+ 还有通知权限）
2. 启动时 Magisk / KernelSU / APatch 会弹 root 授权 → 允许
3. 状态栏显示 `Root：已授权 ✓`
4. 轻点地图加路径点（≥2 个）→ 拖「速度」滑块（或用菜单「目标配速…」）→ 可选循环
5. 点「开始」→ 前台服务接管定位，把位置定到起点并保持
6. 到其它 App 上，在**悬浮窗**点「开始」→ 开始沿路径移动（暂停/停止移动只冻结路线，定位保持原地）
7. 回主界面点「彻底停止」→ 移除模拟定位、恢复真实定位

**想开系统级注入**：设置里打开「系统级注入」→ LSPosed 把「系统框架」加入作用域 → 重启手机。
之后所有 App 直接拿到虚拟定位，且没有 mock 标记。

## 原理

### 路线 A：测试 Provider 注入（默认）

```
root 授权
  └─ su -c "appops set com.vloc.motion android:mock_location allow"
       └─ LocationManager.addTestProvider("gps"/"network"/"fused")
            └─ setTestProviderLocation(每 100ms 一个点，随速度插值)
```

- 普通 App 拿不到 `ACCESS_MOCK_LOCATION`，root 下用 `appops` 直接开，**无需开发者选项**
- 接管的是同名系统 Provider，查询 GPS 的 App 直接拿到伪造坐标
- 系统会把这些点标记为模拟位置（`isFromMockProvider`/`isMock`）→ 由 LSPosed 模块擦除

### 路线 B：系统级注入（可选）

```
主 App 每帧写 content://com.vloc.motion.env/location（JSON 快照）
  └─ 隐藏模块在 system_server 内 hook
       ├─ LocationListenerTransport#onLocationChanged(Location|LocationResult)
       ├─ getLastLocation / getCurrentLocation
       └─ GnssStatusListenerTransport#onSatelliteStatusChanged(GnssStatus)
```

- Location 由模块 **新建**，天然没有 mock 标记，也不需要 appops / 测试 Provider
- **所有 App 自动生效**，不用逐个勾选作用域
- GNSS 卫星：内置 CelesTrak 真实 TLE（143 颗），按目标经纬度 + UTC 时间做轨道传播，
  只上报仰角 > 3° 的卫星，因此不同城市星座不同、卫星会随时间升落

### 传感器（计步 / IMU / 姿态）

- 默认：LSPosed 模块在目标 App 进程内合成传感器事件（按步态波形）
- 可选：**Zygisk 模块**注入每个 App 进程，在 `SystemSensorManager` 的 JNI 分发点改写，
  无需作用域（见 `docs/zygisk-sensors.md`）

## 已知限制

- **需要 root**；未 root 设备无法使用
- 地图瓦片依赖网络（高德 / OSM / Carto）
- 部分应用会检测模拟位置并拒绝（GMS 融合定位、native 读取、行为检测）；
  系统级注入能消除标记，但行为一致性仍需拟真参数配合
- 传感器事件由 native 分发，Java 层接管对部分 NDK 直读无效 → 需要可选的 Zygisk 模块
- 仅供学习与测试使用

## 隐藏「模拟位置」痕迹

LSPosed 模块（已内置在同一 APK）在目标 App 进程内做这些遮蔽：

- `Location.isMock()` / `isFromMockProvider()` → 恒 `false`，`setMock`/`setIsFromMockProvider` 空操作
- `Settings.Secure/System/Global` 里 `mock_location` 相关读取 → 等价于"该设置不存在"
- `AppOpsManager` 自查 / 枚举 / `noteOp` / `startOp` → 对 mock op 表现为"无权限"
- `ContentResolver.query/call` 直查 settings provider → 返回空结果
- `Location.writeToParcel` → 序列化前临时清 mock 位
- `getExtras()` → 清掉 mock 类残留 key

安装后在 **LSPosed** 启用「虚拟运动」，并把目标 App 勾进作用域（用系统级注入则勾「系统框架」）。

> 说明：模块只"擦标记"，不改变检测能力上限——GMS 融合定位、native 读取、行为检测仍可能识破，
> 完整能力矩阵见 [`docs/mock-detection.md`](docs/mock-detection.md)。

## Roadmap

已完成（按版本）：

- v0.1 – v0.16：地图选点 / 路径播放 / root appops 注入 / GPX / LSPosed 隐藏模块 /
  拟真反检测 / 海拔 / 前台悬浮窗 / 步数·IMU·姿态回放 / 两段式启动
- v0.17 – v0.19：GNSS 卫星回放（真实 TLE + 轨道传播）/ 拟真漂移改时间连续 OU /
  配速自然度（多时间尺度）/ 到达自动停止
- v0.33：**系统级注入**（system_server 改写定位与 GNSS，无 mock 标记）
- v0.34：**Zygisk 传感器模块**（计步 / IMU / 姿态全 App 自动接管）
- v0.35 – v0.36：交互重构（菜单分组 + 底部卡片状态驱动）、显示规则抽成可测单元
- v0.37：移除相机 / 虚拟摄像头功能，回归单一虚拟定位与跑步模拟
- v0.39：设置分组重构 + 补齐精度设置 + 去掉重复入口
- v0.38：**反检测补强** —— provider 列表指纹、时间戳一致性、精度抖动、NMEA 回放

待办：

- [ ] 路线收藏与多路径管理
- [ ] 摇杆手动控制 / 路线图钉拖拽编辑

## 许可协议

本项目以 **PolyForm Noncommercial License 1.0.0** 授权 —— **禁止任何商业用途**。

- 英文原文：[LICENSE](LICENSE)
- 中文要点：[LICENSE-ZH.md](LICENSE-ZH.md)
- 免责声明（使用限制与风险）：[DISCLAIMER.md](DISCLAIMER.md)

商业使用需取得作者书面许可；违反许可的商用行为作者将追究相应责任。
