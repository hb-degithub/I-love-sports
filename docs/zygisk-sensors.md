# 传感器系统级注入（Zygisk 模块）

> 状态：**已实现，待真机验证**（v0.34.0）。本项目自 v0.37.0 起只做虚拟定位与运动模拟，不含相机功能。

## 1. 为什么需要 Zygisk

| 模拟项 | LSPosed（进程内 Java Hook） | Zygisk（原生 JNI Hook） |
|---|---|---|
| 覆盖范围 | 只有勾选作用域的 App | **每个 App 进程自动注入**，无需勾选 |
| 计步 / IMU | 只能拦 Java `SensorEventListener` | 拦 `SystemSensorManager` 的 JNI 分发点，**Java 与部分 NDK 路径都覆盖** |
| 系统稳定性风险 | 低 | 中（但本模块只替换 JNI 函数指针，**不改写机器指令**） |

所以"系统级"在传感器这一层的落地方案是：**Zygisk 注入每个 App 进程 + 改写传感器事件**。

## 2. 架构

```
主 App（每帧）
  └─ VlocStateFile.write(...)                       // 128 字节定长结构体
       files/vloc_state.bin                          // App 私有目录
          └─ Magisk 模块 service.sh（root，每 100ms 复制）
               /data/local/tmp/vloc/state.bin        // chmod 644
                  └─ Zygisk 模块（每个 App 进程）
                       ├─ hook nativeGetNextSensorEvent   → 记录 handle → 传感器类型
                       └─ hook nativeDispatchSensorEvent  → 按类型改写 values[]
```

改写规则（与主 App 的步态/姿态模型一致）：

| 传感器 | 改写内容 |
|---|---|
| `TYPE_STEP_COUNTER` | 累计步数（来自 StepEngine） |
| `TYPE_ACCELEROMETER` / `TYPE_GRAVITY` | 设备系加速度/重力（含跑步摆动） |
| `TYPE_LINEAR_ACCELERATION` | 加速度 − 重力 |
| `TYPE_GYROSCOPE` | 摆动角速度 |
| `TYPE_ROTATION_VECTOR` / `GAME_ROTATION_VECTOR` | 四元数（航向跟随路线） |
| `TYPE_MAGNETIC_FIELD` | 按姿态旋转的地磁 |
| `TYPE_ORIENTATION` | 航向 + 俯仰 |

状态文件 `active=0`、超过 2.5 秒没更新、或读不到时，**一律透传真实传感器**。

## 3. 安装

1. 从 Releases 下载 `vloc-zygisk-*.zip`
2. **Magisk / KernelSU → 模块 → 从本地安装** → 选择该 zip → 重启
3. 确认 Magisk 里模块已启用，`Zygisk` 开关为**开**
4. App → 设置 → 打开 **「系统级注入」**
5. 运行模拟，观察步数类 App

> 本模块走 Magisk 的 Zygisk 注入，**不需要** LSPosed 作用域；LSPosed 仍负责定位标记
> 擦除（或改用 App 的「系统级注入」走 system_server 方案）。

## 4. 自检

```bash
adb logcat -s VLocZygisk
```

应看到：

- `state file mapped: /data/local/tmp/vloc/state.bin` —— 成功读到状态
- `sensor hooks installed: 2/2` —— JNI hook 装上了
- 若出现 `sensor hooks NOT installed (JNI signature mismatch on this ROM)`，说明该 ROM 的
  `nativeDispatchSensorEvent` 签名不同，需要按 §7 反馈。

Magisk 侧：

```bash
ls -l /data/local/tmp/vloc/state.bin   # 应存在且为 644，且时间在刷新
```

## 5. 风险与卸载

- Zygisk 模块注入**所有 App 进程**，若出现异常（App 崩溃、传感器无数据）：
  1. Magisk 里**禁用**该模块 → 重启（立即恢复真实传感器）；
  2. 或在 App 里关掉「系统级注入」开关（模块读到 active=0 就透传）。
- `sepolicy.rule` 会放宽 `appdomain` 读取 `shell_data_file` 的权限（仅用于读状态文件）。
  对安全敏感的设备**不要安装**。
- 模块不联网、不读取任何用户数据，只 mmap 一个固定路径的状态文件。

## 6. 需要你反馈的信息

首次真机运行后请把下面输出发回，用于适配不同 ROM：

```bash
adb logcat -s VLocZygisk
adb shell dumpsys sensorservice | head -30
adb shell getprop ro.build.version.release
adb shell getprop ro.product.model
```

如果 hook 没装上，我会按你的 ROM 增加对应的 JNI 签名分支。
