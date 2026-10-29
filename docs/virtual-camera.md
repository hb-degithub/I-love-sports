# 虚拟摄像头（接管相机播放相册视频）— **未完成**

> ⚠️ **状态：未完成 / 实验性（as of v0.32.0）**
>
> 已实现：视频库与页面控制、悬浮窗（折叠/进度/切换/播放暂停）、ContentProvider 通道、
> Camera2 & Camera1 会话接管、EGL 等比渲染、拍照帧注入、12fps 分析帧投喂、接管期 IMU 竖直模式。
>
> **尚未通过真机验证的问题**：
> 1. 预览画面不一定被替换成视频（`接管：运行中` 但画面仍是真实相机 → 预览 Surface 选择/时序仍需联调）；
> 2. 姿态识别类 App 可能仍提示"请调整手机角度"（IMU 竖直姿态的轴向定义各 App 不同，需逐个校准）；
> 3. 可能仍提示"光线过暗"（目标 App 分析流若为 JPEG/私有格式，YUV 投喂不生效）；
> 4. Camera2 NDK / 厂商私有相机 / 相机跑在独立进程的 App 覆盖不到。
>
> 结论：**该功能目前不能保证可用，请勿依赖。**

> 状态：**App 侧已交付（v0.20.0）**；**模块侧已实现（v0.21，实验性）**：预览替换 + 拍照 YUV 帧注入；
> **独立悬浮窗已交付（v0.22.0）**：启用虚拟摄像头即在屏幕上出现可拖动胶囊（切换/开关/收起）。
> 模块侧需要真机装 LSPosed 才能运行时验证（本机模拟器无 LSPosed）。

## 1. 目标

在 LSPosed 作用域内的目标 App 里，把相机画面换成用户从相册选的视频（可指定多个、悬浮窗折叠状态切换），
拍照也返回视频里的画面。不改系统相机、不动 HAL。

## 2. 架构

```
相册视频（SAF 持久化授权）
   └─ 主 App：视频列表 / 当前选择 / 开关（SharedPreferences）
        ├─ 定位悬浮窗折叠行：📹 虚拟摄像头 开 · 1/2 名称（点切换 / 长按开关）
        └─ 独立悬浮窗 CameraOverlayService（v0.22.0）：启用即显示
             📹 虚拟摄像头 开 · 1/2 名称  [✕]
             点=切下一个 · 长按=开/关 · ✕=只收起 · 可拖动（位置独立记忆）
        └─ ContentProvider com.vloc.motion.env
             ├─ /camera               → JSON 配置 {enabled,index,count,clips[]}
             └─ /camera_video?index=N → 视频文件 FD（主 App 持有授权，模块只拿 FD）
                  └─ LSPosed 模块（目标 App 进程）
                       ├─ hook CameraDevice / CaptureSession：用视频帧替代真实相机输出
                       └─ hook 拍照回调：把当前视频帧编码成 JPEG 交给 ImageReader
```

关键点：模块运行在目标 App 进程，**没有主 App 的 SAF 授权**，所以视频必须由主 App 的 Provider
以 FD 形式转发（已验证 `content read` 可读到视频字节）。

## 3. 已交付（v0.20.0，App 侧）

| 能力 | 说明 |
|---|---|
| 视频库 | 菜单 →「虚拟摄像头…」→ 添加视频（多选，SAF 持久化授权）、删除、选择当前 |
| 开关 | 默认关闭；关闭时模块完全不介入相机 |
| 悬浮窗折叠控制 | 折叠状态显示 `📹 虚拟摄像头 开 · i/n 名称`；点一下切下一个视频，长按开/关 |
| Provider 下发 | `/camera` 返回配置 JSON；`/camera_video?index=N` 返回视频 FD |
| 数据模型 | `core/CameraClips.java`（纯 Java，含 JSON 序列化 + 索引环绕，单测覆盖） |

## 3.1 虚拟摄像头独立悬浮窗（v0.22.0）

此前只能靠**定位悬浮窗**折叠行操作相机；不开定位就没有任何入口。v0.22 起相机自带独立悬浮窗：

| 行为 | 说明 |
|---|---|
| 出现时机 | 主界面「虚拟摄像头…」里打开开关并保存 → 立即出现；回到前台时自动对齐（开了就有、关了就没有） |
| 外观 | 胶囊：`📹 虚拟摄像头 开 · 1/2 视频名` + 右侧 `✕`；可整个拖动，位置单独记忆（不与定位悬浮窗共用） |
| 交互 | 点文字 = 切下一个视频；长按 = 开 / 关虚拟摄像头（关闭后胶囊自动消失）；`✕` = 只收起悬浮窗，摄像头保持启用 |
| 权限 | 需要「显示在其他应用上层」。未授权时保存开关会弹权限引导（沿用定位悬浮窗的受限权限说明），授权回来自动出现 |
| 与定位悬浮窗互斥 | 定位会话（播放/定点）运行期间不显示相机胶囊，由定位悬浮窗统一显示相机状态，避免两个胶囊叠在一起 |
| 交还 | 定位会话结束后，若相机仍启用且用户没手动 ✕，自动恢复相机胶囊 |
| 常驻 | 前台服务（`CameraOverlayService`，Android 14 起 `specialUse` 类型）显示一条 MIN 级通知，进程不易被回收 |

实现位置：

- `ui/CameraOverlay.java`：胶囊视图（拖动/点击/长按/✕、位置持久化）
- `service/CameraOverlayService.java`：前台服务 + 1s 轮询刷新文案 + 与 `Session` 互斥判断
- `core/CameraClips.overlayLabel(...)`：文案生成（纯 Java，单测覆盖：正/负状态、越界收敛、空列表、长名截断）
- `core/RouteStore`：`camera_overlay_x/y`、`camera_overlay_hidden`

## 3.1 页面与控制（v0.22 / v0.24）

v0.24.0 起虚拟摄像头不再是独立 Activity，而是主界面的一部分：顶部 **「运动 / 摄像头」页签**切换，
摄像头页包含视频库、预览、播放/暂停、上一个/下一个、接管诊断。



- 菜单 →「虚拟摄像头…」现在打开**独立控制页**：开关、当前视频预览播放、上一个/下一个、播放/暂停、添加/删除视频、点列表切换。
- 悬浮窗折叠状态：`📹 虚拟摄像头 开 · 1/2 名称` + `⏸ 暂停` / `▶ 播放` 按钮（点标题行切视频、长按开/关）。
- 播放在 Provider JSON 里以 `playing` 下发，模块暂停时**停止推帧**，目标 App 画面冻结在最后一帧。

## 4. 模块侧实现（v0.21 / v0.22 修复）

已实现（`app/src/main/java/com/vloc/motion/hidden/CameraReplay.java`，5 个 hook）：

1. **配置读取**：1s 缓存查询 `/camera`；`enabled=false` 或没有视频 → 所有 hook 透传真实相机。
2. **Session 接管**：hook `CameraDeviceImpl#createCaptureSession`（同时支持 `List<Surface>` 与 `List<OutputConfiguration>`）；
   预览目标挑选会排除 App 用于 AI 分析的 `ImageReader`（v0.29）；视频经 `VideoSurfaceRenderer`（EGL + GLES）
   按目标比例 center-crop 后画到预览 Surface，避免拉伸/看起来"放大"：
   把 App 的输出 Surface 列表换成模块自建的临时 ImageReader（真实相机会话照常建立），
   同时用 `MediaCodec` 把视频解码渲染进 App 原来的预览 Surface（SurfaceTexture/SurfaceView 都能收）；
   会话配置成功后才启动渲染，关闭/失败立即停止。
3. **拍照接管**：hook `CameraCaptureSessionImpl#capture`：后台解码当前视频一帧 → NV21 →
   用 `ImageWriter` 写进 App 已注册的 YUV `ImageReader`（CameraX 等走 YUV 的 App 可用）。
4. **生命周期**：`CameraDeviceImpl#close` / 会话 `onClosed` → 停止解码，避免泄漏。
5. **请求目标重写（v0.22 关键修复）**：hook `CaptureRequest$Builder#addTarget`，把 App 的 Surface 目标换成临时 Surface。
   v0.21 只换了会话输出、没换请求目标，相机会以"请求引用了会话之外的 Surface"拒绝，表现为接管不生效/黑屏。
6. **降级**：任何一步异常 → 只记日志并透传；`enabled=false` 时零影响。

**已知限制（实验性）**：
- 拍照只支持 YUV_420_888 的 ImageReader；直接要 JPEG 的 App（部分 Camera2 示例/相机 App）暂不支持，会记日志并透传；
- v0.26 起同时覆盖 Camera1（`android.hardware.Camera`）：`setPreviewDisplay` / `setPreviewTexture` 把预览画面换成视频，
  `takePicture` 直接把当前视频帧压成 JPEG 回调给 App；
- 仍不覆盖 NDK `ACameraManager` / 厂商私有相机 / 相机在独立进程的情况；
- 视频分辨率与 App 预览尺寸不一致时，由 App 的 `SurfaceTexture` 变换矩阵自适应，少数硬编码尺寸的 App 可能拉伸；
- 预览会话把 Surface 换成临时缓冲后，App 若强依赖真实相机输出尺寸/能力，可能配置失败 → 此时需要关闭虚拟摄像头。

## 4.0 已修根因（v0.23.1）

模块读取 `content://com.vloc.motion.env/camera` 时用 `cursor.getString(0)` 取 JSON，
但 Provider 的列是 `mode, json, updated_at` —— 第 0 列是字符串 `"camera"`，JSON 解析必然失败，
于是 `enabled` 永远读成 false、模块完全透传真实相机（表现就是"不能接管"）。
现在模块与控制页都改为**按列名 `json`** 取，越界时回退第 1 列。

## 4.1 接管诊断（v0.23 / v0.25）

摄像头悬浮窗第二行 + 控制页状态区都会显示模块上报的接管状态；v0.25 起模块还会上报
"未启用原因"（开关关 / 没有视频 / Provider 无配置 / 配置解析失败），并在注入早期
Application 未就绪时延迟重试上报，所以即使没打开相机也能看到 `hooks=N/9`。



控制页顶部会显示模块上报的运行时状态（不开 logcat 也能定位卡点）：

| 显示 | 含义 |
|---|---|
| 未上报 | 目标 App 还没打开相机，或模块未注入 / 不在作用域 |
| 已注入 · camera hooks=N/9 | 模块注入了，但还没拦到相机会话 |
| 相机会话接管：Xs前 | 拦到会话（继续看渲染） |
| 视频渲染：Xs前 | 视频已经在往目标 App 的预览 Surface 上渲染 |

模块通过 `content://com.vloc.motion.env/camera` 的 `camera_report` 调用上报；主 App 存在
`CameraSession` 里，控制页 1.5s 轮询 `/camera_status`。

同时 v0.23 扩展了覆盖面：`createCaptureSession` / `createCaptureSessionByOutputConfigurations` /
`createReprocessableCaptureSession` / `createReprocessableCaptureSessionByConfigurations` /
`createConstrainedHighSpeedCaptureSession` 全部 hook；会话配置失败时自动用原 Surface 重建真实会话（优雅降级）；
`android.hardware.Camera`（Camera1）使用会检测并上报"暂不支持"。

## 4.2 姿态识别类 App 适配（v0.30）

- **IMU 竖直模式**：接管相机期间把加速度计/陀螺仪/旋转矢量/方向传感器固定成"手机竖直、静止"，
  避免 App 通过陀螺仪判定"手机角度不对"而拒绝采集（ImuReplay.setUprightMode）。
- **持续分析帧**：以 ~12fps 把视频帧解码成 NV21，按 App 每个 YUV ImageReader 的尺寸缩放后
  通过 ImageWriter 写进去；否则 App 自己的画面分析会得到黑帧，报"光线过暗"、姿态识别无画面。
- 预置视频：`assets/camera_presets/jump_rope.mp4`、`jumping_jacks.mp4`，首启复制进视频列表，
  名称带要求与时长；时长不在 60–120 秒会提示但不阻止使用（PresetVideos / CameraPreset）。

## 5. 验证方式

- 模拟器：相机走虚拟场景（`emulator -camera-back virtualscene`），可对比"接管前后预览内容"。
- 目标 App：自建的 Camera2/CameraX 测试 App（预览 + 拍照），验证：
  - 预览内容 = 选中视频；切换视频后 1 秒内变化；
  - 拍照得到的 JPEG 与当前视频画面一致；
  - 关闭虚拟摄像头 / 主 App 未运行 → 恢复真实相机；
  - 非作用域 App 不受影响。
- 日志：`LSPosed-Bridge` 过滤 `VLocHidden`，应出现 `camera hooks=…`、`camera replay active: clip=…`。

## 6. 已知限制

- 只覆盖走 Camera2/CameraX（Java 层）的 App；直接用 NDK `ACameraManager` / 厂商私有相机的 App 可能绕过。
- 需要把目标 App 加入 LSPosed 作用域并重启目标进程。
- 视频解码在目标进程内进行，会有一次额外解码开销（预览 1080p 约 5–10% CPU）。
- 不做系统级（HAL）替换：系统相机、其它未勾选的 App 仍看到真实画面。
