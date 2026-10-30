# 虚拟运动（root 版）

<p>
  <a href="https://github.com/hb-degithub/I-love-sports/actions/workflows/android.yml">
    <img src="https://github.com/hb-degithub/I-love-sports/actions/workflows/android.yml/badge.svg" alt="Android CI"></a>
  <a href="https://github.com/hb-degithub/I-love-sports/releases/latest">
    <img src="https://img.shields.io/github/v/release/hb-degithub/I-love-sports" alt="Release"></a>
  <img src="https://img.shields.io/badge/license-PolyForm%20Noncommercial%201.0.0-red" alt="禁止商用">
  <img src="https://img.shields.io/badge/camera-未完成-orange" alt="虚拟摄像头未完成">
</p>

## 📦 下载

最新构建（CI 自动产出）：**[Releases](https://github.com/hb-degithub/I-love-sports/releases/latest)** ——
`virtual-motion-<版本>.apk` + `.sha256`；每次打 `v*` tag 会自动构建并发布。


> ## ⚠️ 项目状态：虚拟摄像头功能**尚未完成**
>
> - **定位/运动模拟：可用**（虚拟定位注入、路径播放、拟真、GNSS 卫星回放、计步/IMU、LSPosed 隐藏模块）
> - **虚拟摄像头：未完成 / 实验性**。已实现相机接管（Camera1 + Camera2 预览替换、拍照帧注入、分析帧投喂、
>   接管期 IMU 竖直模式），但在真实运动/姿态识别类 App 上**尚未验证通过**：预览画面替换、姿态判定、
>   分析帧是否被目标 App 接受都还需要真机联调。
> - 摄像头相关的进度、已知缺口与验证步骤见 [`docs/virtual-camera.md`](docs/virtual-camera.md)。
> - 如果你只需要"虚拟定位 + 跑步数据"，摄像头部分可以完全不管（默认关闭）。


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

> 阶段一（已交付）：root + 系统测试 Provider 注入，地图规划路径、按速度播放。
> 阶段二（已交付）：LSPosed 模块隐藏"模拟位置"标记，对抗 isMock 类检测。

## 功能

- 🛰️ GNSS 卫星回放（v0.19.0）：内置 CelesTrak 真实 TLE 星表（GPS / GLONASS / Galileo / 北斗共 143 颗），按**目标位置 + 当前 UTC 时间**用轨道传播算出此刻真实可见的卫星（仰角/方位角/载噪比，通常 30–70 颗），模块在目标 App 进程里 hook `GnssStatus` 读取方法与 `registerGnssStatusCallback` 按 1 Hz 回放 —— 不同城市看到的星座不同、卫星会随时间升落，不再是凭空一套固定星
- 🧭 **交互重构（v0.35.0）**：菜单从 15 个平铺项收敛为「4 个一级动作 + 3 个分组子菜单」；
  主界面底部控件按状态显示 —— 运行中自动隐藏选点/速度/循环控件避免误触，只留主操作与「彻底停止」；
  显示规则抽到 `core/MotionUiState`，由单元测试固定（运行中永不暴露可编辑控件等 9 条规则）
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
- 🎥 虚拟摄像头（App 侧，v0.20.0）：菜单「虚拟摄像头…」可从相册添加多个视频；视频通过 `content://com.vloc.motion.env/camera` + `/camera_video` 下发给 LSPosed 模块（v0.24.0：**主界面「运动 / 摄像头」页签切换**，摄像头页含视频库/预览/播放暂停/诊断；悬浮窗播放/暂停 + 修复接管不生效（重写 CaptureRequest 目标 / 支持 OutputConfiguration）；模块侧接管为实验性，见 `docs/virtual-camera.md`）
- 🪟 **虚拟摄像头独立悬浮窗（v0.22.0）**：启用虚拟摄像头后，屏幕上会出现独立的胶囊悬浮窗 `📹 虚拟摄像头 开 · 1/2 视频名`——点一下切下一个视频、长按开/关、✕ 只收起悬浮窗（摄像头保持启用）、可拖动且位置独立记忆；与定位悬浮窗互斥（定位会话运行时由定位悬浮窗统一显示，不会出现两个胶囊）
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
- 🛰️ **系统级注入（v0.33.0，可选）**：定位与 GNSS 由隐藏模块在 **system_server** 内改写，
  对所有 App 生效、**不带模拟位置标记**、无需 appops / 测试 Provider；需在 LSPosed 勾选「系统框架」并重启。
  详见 [`docs/system-injection.md`](docs/system-injection.md)（计步/IMU/相机仍为进程内注入，原因见该文档 §6）
- 🧩 **传感器系统级注入（v0.34.0，可选）**：配套的 **Zygisk 模块**会注入每个 App 进程，
  改写计步/加速度/陀螺仪/姿态等传感器事件 —— 无需 LSPosed 作用域，对所有 App 生效。
  见 [`docs/zygisk-sensors.md`](docs/zygisk-sensors.md)（相机仍需 HAL 层方案，见该文档 §6）
- 🧩 单 APK 双身份：同一安装包既是主 App，也是 LSPosed 模块（`assets/xposed_init` + `xposedmodule` 元数据）
- 📍 首页定位按钮：一键读取设备**真实位置**，地图居中显示；可直接「添加为路径点 / 临时定位到这里」
- 🗺️ 地图源可切换：默认高德（国内可用），另备高德卫星 / OSM / Carto；地图空白时可「手动输入坐标」选点
- 🔐 Root 检测显式化：主界面与开工向导都显示 Root 状态，可一键重新检测/申请授权（兼容 Magisk / KernelSU / APatch 的 su 路径）
- 🔐 root 注入：自动 `su` 授权 → `appops set <pkg> android:mock_location allow` → 接管 `gps`/`network`/`fused` Provider
- 🛑 还原：停止后移除测试 Provider，恢复系统真实定位
- 📥📤 GPX：导入/导出路径轨迹
- 🔔 前台服务 + 通知栏暂停/停止

## 交付物

从 **v0.6.0** 起只有一个 APK：**主 App 与 LSPosed 隐藏模块已合并**（模块内置在安装包里，
未在 LSPosed 中启用时不会注入任何进程）。

| 文件 | 说明 |
|---|---|
| `dist/virtual-motion-0.32.0.apk` | 主 App + 内置隐藏模块（root 注入 / 路径播放 / 开工向导 / 环境指纹 / LSPosed 反检测） |

```bash
adb install -r dist/virtual-motion-0.32.0.apk
```

> 升级提示：如果之前装过独立的隐藏模块，先 `adb uninstall com.vloc.motion.hidden`，
> 然后在 LSPosed 里启用「虚拟运动」并勾选目标 App。

## 目录结构

```
app/src/main/java/com/vloc/motion/
├── App.java                      # osmdroid 初始化
├── core/                         # 纯 Java，可单元测试
│   ├── GeoMath.java              # 距离/方位角/球面插值
│   ├── Route.java                # 路径点、按里程采样
│   ├── RoutePlayer.java          # 播放状态机
│   ├── GpxCodec.java             # GPX 读写
│   └── RouteStore.java           # SharedPreferences 持久化
├── location/
│   ├── RootShell.java            # su 命令执行、appops 授权
│   └── TestProviderInjector.java # addTestProvider / setTestProviderLocation
├── service/
│   ├── LocationService.java      # 前台服务，200ms 推进 + 注入
│   └── Session.java              # 进程内共享状态
└── ui/MainActivity.java          # 地图 + 控制面板

app/src/main/assets/xposed_init   # 内置 LSPosed 模块入口声明
app/src/main/assets/gnss_tle.txt  # GNSS 真实星表（CelesTrak TLE，GPS/GLONASS/Galileo/北斗）
app/src/main/java/com/vloc/motion/hidden/
├── HookEntry.java                # Location / Settings / AppOps / ContentProvider / Parcel hook
├── EnvReplay.java                # 基站 / Wi-Fi 环境回放
└── EnvSnapshot.java              # 通过主 App Provider 读取快照（缓存 + 超时）
hidden/                           # v0.4.0 独立模块源码（历史参考，不再构建）

docs/
├── usage.md                      # 使用手册
├── virtual-camera.md             # 虚拟摄像头架构与实施计划
├── system-injection.md           # 系统级注入（system_server 改写定位/GNSS）
├── zygisk-sensors.md             # 传感器系统级注入（Zygisk 原生模块）
├── phase2-hidden.md              # 隐藏模块安装手册
├── root-injection.md             # root 注入方案调研（AOSP 源码级）
└── mock-detection.md             # 模拟位置检测手段与绕过矩阵

scripts/
├── update_gnss_tle.py            # 刷新内置 GNSS 星表（TLE 会过期，发版前跑一次）
├── root_grant_mock.sh            # appops 授权/回读/回滚
└── install_system_app.sh         # Magisk 模块方式安装为 priv-app（进阶）
```

## 构建

工具链放在 `.toolchain/`（JDK 17 + Android SDK 35 + Gradle 8.9，已gitignore）：

```bash
source .toolchain/env.sh
gradle :app:assembleDebug          # 产物：app/build/outputs/apk/debug/app-debug.apk
gradle :app:testDebugUnitTest      # 核心算法单元测试
```

## 使用

1. 安装 APK，首次启动授予**定位**权限（Android 13+ 还有通知权限）
2. 启动时 Magisk / KernelSU / APatch 会弹 root 授权，点允许
3. 状态栏显示 `Root：已授权 ✓`
4. 轻点地图加路径点（≥2 个），拖动"速度"滑块，可选循环
5. 点「开始」→ 前台服务接管定位并沿路径移动
6. 「停止」恢复真实定位；长按地图或点「临时定位」可跳到某个坐标

## 原理（阶段一）

```
用户 root 授权
   └─ su -c "appops set com.vloc.motion android:mock_location allow"
        └─ LocationManager.addTestProvider("gps"/"network")
             └─ setTestProviderLocation(每 200ms 一个点)
```

- 普通 App 默认拿不到 `ACCESS_MOCK_LOCATION`，root 下用 `appops` 直接开 `android:mock_location` 操作权限，**无需打开开发者选项**
- 接管的是同名系统 Provider，所以直接查询 GPS 的 App 会拿到伪造坐标
- 测试 Provider 写入的点在系统层会被标记为模拟位置（`isFromMockProvider`/`isMock`），严格检测的 App 仍能识别 → 阶段二用 LSPosed Hook 处理

## 已知限制

- 需要 root；未 root 设备无法使用（不走开发者选项模拟路径）
- 地图瓦片来自 OpenStreetMap，需要联网
- 部分应用（尤其游戏、打卡类）会检测模拟位置并拒绝
- 仅供学习与测试使用

## 阶段二：隐藏"模拟位置"痕迹

安装 `hidden` 模块后，在 **LSPosed** 中启用并把目标 App 勾进作用域，目标 App 进程里
`Location.isMock()` / `isFromMockProvider()` 一律返回 `false`，`getExtras()` 里的 mock 残留也会被清掉。
详见 [`docs/phase2-hidden.md`](docs/phase2-hidden.md)。

> 说明：模块只"擦标记"，不改变检测能力上限——GMS 融合定位、native 读取、行为检测仍可能识破，
> 完整能力矩阵见 `docs/mock-detection.md` §4。

## Roadmap

- [x] 地图选点（图钉确认）/ 路径播放 / 速度 / 循环 / 临时定位
- [x] 点位编辑：点选删除 / 临时定位，长按拖动微调
- [x] root appops 自动授权 + 多 Provider 注入 + 注入前二次校验
- [x] GPX 导入导出
- [x] 阶段二：LSPosed 隐藏模块（清除 isMock / isFromMockProvider / extras）
- [x] 拟真反检测（漂移/噪声/卫星数据/起步缓动）+ 海拔链路 + 前台悬浮窗
- [x] 隐藏模块增强：Settings / AppOps 自查 hook（A11/A12）
- [x] 隐藏模块增强：ContentProvider 直查 / AppOps 枚举 / Parcel 序列化旁路（v0.3.0）
- [x] 开工向导（位置 → 路线 → 环境）+ 路线库 + 基站/Wi-Fi 环境快照回放（v0.4.0）
- [x] 主 App 与隐藏模块合并为单 APK（v0.6.0）
- [x] 地图源切换 + 手动坐标选点 + Root 检测/申请按钮（v0.7.0）
- [x] 地图瓦片可达性自动探测/自动换源 + 内置地图诊断、Root 诊断（v0.7.3）
- [x] 修复地图网格：osmdroid XYTileSource 不替换 {x}/{y}/{z} 占位符导致高德/OSM 瓦片全部 404，改用自实现 PlaceholderTileSource（v0.8.0）
- [x] 模拟器（Android 9 x86_64，TCG）实测：地图渲染 / Root 检测 / 临时定位注入 / 开工向导地图 全部通过
- [x] 首页「定位」按钮读取真实位置，支持用当前位置选点（v0.8.1）
- [x] 速度混淆 + 目标配速（圈数取整、目标时间 ±10s、最大速度与分段速度限制）（v0.9.1）
- [x] 预设路线组 + 目标时间改分:秒输入 + 预设自动同步单圈距离（v0.9.4）
- [x] 目标配速改选择器 + 自动推导时间 + 隐藏手动速度 + 不可达/异常数据提示（v0.10.0）
- [x] GCJ-02/WGS-84 双向转换 + 内置路线闭环 + 悬浮窗受限权限引导（v0.11.1）
- [x] 坐标口径修正：内置路线改用 glat/glon（GCJ-02）为权威输入，卫星图核对对准操场（v0.11.2）
- [x] 悬浮窗交互重构：开始/暂停/停止移动 + 只有主界面能彻底停止（v0.12.1）
- [x] 步数/步频/步幅推算 + 计步传感器回放（v0.13.0）
- [x] 时变步态（步频/步幅在一次模拟内波动）+ IMU（加速度计/陀螺仪）伪造（v0.14.0）
- [x] 姿态回放（ROTATION_VECTOR / MAGNETIC_FIELD 按跑步摆动）+ 重力按姿态自洽 + 姿态开关（v0.15.1）
- [x] GAME_ROTATION_VECTOR + ORIENTATION 伪造（v0.15.2）
- [x] 步频/速度模型收敛 + 模块自检 + 默认速度改 6 km/h（v0.15.3）
- [x] 两段式启动（主界面只定位到起点）+ 悬浮窗自检信息 + 可折叠（v0.16.2）
- [x] 步数/步频/模块注入自检（未注入 / 已注入未读传感器 / 读取中）+ 悬浮窗状态三态（v0.16.3）
- [x] 步骤快照 JSON 增加 `satellites` / `cn0Mean` / `cn0Max`，为隐藏模块回放 GNSS 附属数据预留字段（v0.17.0）
- [x] GNSS 卫星回放：逐颗卫星模型 + 模块侧 GnssStatus hook + 1Hz 回放（v0.18.0）
- [x] GNSS 改为真实星历驱动：内置 TLE 星表 + 轨道传播，按位置/UTC 时间计算真实可见卫星（v0.19.0）
- [x] 拟真漂移改为时间连续 OU（√dt 缩放）：高刷新注入下轨迹不再"跳"，反推瞬时速度波动减半（v0.19.1）
- [x] 配速自然度：多时间尺度速度模型（慢变/地形/疲劳/冲刺事件），每公里分段标准差从 ±3s 提到 ±20s 量级（v0.19.2）
- [x] 到达目标自动停止并恢复真实定位（可在设置关闭）+ 修复历史版本 SharedPreferences 类型不一致导致的启动崩溃（v0.19.3）
- [x] 虚拟摄像头 App 侧：相册多视频、悬浮窗折叠切换、Provider 下发配置与视频 FD（v0.20.0）
- [x] 虚拟摄像头模块侧（实验性）：Camera2 预览接管 + 拍照 YUV 帧注入（v0.21）
- [x] 虚拟摄像头控制页 + 悬浮窗播放/暂停 + 修复接管不生效（CaptureRequest 目标重写 / OutputConfiguration）（v0.22.0）
- [x] 接管诊断：模块把「注入状态 / hook 数 / 是否拦到相机会话 / 是否开始渲染 / 最后错误」回报到控制页；扩展会话 hook（reprocessable/high-speed）+ 配置失败自动恢复真实相机 + Camera1 检测（v0.23.0）
- [x] **修复接管不生效的根因**：模块读 Provider 时按第 0 列取 JSON（实际第 0 列是 "camera"），解析失败后一直判定「未启用」；改为按列名取（v0.23.1）
- [x] 虚拟运动 / 虚拟摄像头合并为一个页面，顶部页签切换（v0.24.0）
- [x] 摄像头悬浮窗补完：播放/暂停 + 接管状态行；摄像头页点「播放」自动拉起悬浮窗；模块上报「未启用原因 / hooks / 会话 / 渲染」并支持注入早期重试（v0.25.0）
- [x] 模块补 Camera1（android.hardware.Camera）接管：预览渲染到 Surface/SurfaceTexture + 拍照回调注入视频帧 JPEG（v0.26.0）
- [x] 虚拟摄像头默认暂停（添加视频不自动播放，点「播放」才开始）；诊断细分「相机已打开但没拦到会话 / 具体原因」；动态扫描所有 createCaptureSession* 方法（v0.27.0）
- [x] 修复「关于」里版本号写死导致升级后仍显示旧版本，改为读取安装包实际版本（v0.27.1）
- [x] 摄像头悬浮窗默认折叠（单行胶囊，箭头展开接管状态）；不再在打开 App / 回到前台时自动弹出，只有点「播放」或打开开关才显示（v0.28.0）
- [x] 相机接管渲染修正：预览 Surface 智能挑选（排除给 AI 分析用的 ImageReader）+ EGL 等比 center-crop，不再拉伸/放大（v0.29.0）
- [x] 姿态识别 App 适配：接管期间 IMU 固定为「竖直静止」；持续 12fps 把视频帧写入 App 的 YUV 分析 ImageReader（v0.30.0）
- [x] 预置视频机制（assets/camera_presets：跳绳 / 开合跳）+ 时长 60–120 秒校验 + 页面要求提示（v0.30.0）
- [x] 摄像头悬浮窗细化：折叠单行 + 细进度条；展开显示「0:12/1:30 · 接管状态」；新增 ◀ ▶ 切换视频（v0.30.0）
- [x] 系统级注入：定位 + GNSS 改由模块在 system_server 改写（所有 App 生效、无 mock 标记）（v0.33.0）
- [x] 交互重构：菜单分组、底部卡片状态驱动（运行中隐藏选点/设置控件）、摄像头页状态置顶（v0.35.0）
- [x] 传感器系统级注入：Zygisk 原生模块 + JNI hook，所有 App 进程自动接管计步/IMU/姿态（v0.34.0）
- [x] 把「跑步运动模拟」与「相机接管」的 IMU 逻辑显式分开：新增开关「接管时 IMU 固定竖直」，默认开；关掉则按跑步姿态回放（v0.31.0）
- [x] 跑步悬浮窗去掉摄像头/视频行（避免与视频悬浮窗混淆）；注入频率 5Hz → 10Hz（点间距减半）；拟真漂移再收紧（σ 0.28→0.20、τ 6→8s）（v0.32.0）
- [x] 虚拟摄像头独立悬浮窗：启用即显示、点击切视频、长按开关、✕ 收起、独立位置记忆、与定位悬浮窗互斥、定位会话结束后自动交还（v0.22.0）
- [ ] 路线收藏与多路径管理
- [ ] 摇杆手动控制 / 路线图钉拖拽编辑

## 许可协议

本项目以 **PolyForm Noncommercial License 1.0.0** 授权 —— **禁止任何商业用途**。

- 英文原文：[LICENSE](LICENSE)
- 中文要点：[LICENSE-ZH.md](LICENSE-ZH.md)
- 免责声明（使用限制与风险）：[DISCLAIMER.md](DISCLAIMER.md)

商业使用需取得作者书面许可；违反许可的商用行为作者将追究相应责任。
