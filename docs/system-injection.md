# 系统级注入（Location / GNSS 在 system_server 内生效）

> 状态：**定位与 GNSS 已支持系统级**（v0.33.0）；**计步 / IMU / 摄像头仍为进程内注入**（见文末说明）。

## 1. 与进程内注入的区别

| | 进程内注入（旧） | 系统级注入（新） |
|---|---|---|
| Hook 位置 | 目标 App 进程（需逐个勾选作用域） | **system_server**（`android` 进程） |
| 生效范围 | 只有被勾选的目标 App | **所有 App + 系统服务** |
| 模拟位置标记 | 有（`isMock`/`isFromMockProvider` 为 true，需模块擦除） | **没有**：Location 由模块新建，天然不带标记 |
| appops 授权 | 需要 `android:mock_location` | **不需要** |
| 测试 Provider | 需要 `addTestProvider`/`setTestProviderLocation` | **不需要** |
| 数据来源 | 主 App 直接推给测试 Provider | 主 App 写 Provider，模块在 system_server 读取 |

## 2. 架构

```
主 App（每帧）
  └─ LocationSession.update(...)                     // 最新定位快照
       └─ content://com.vloc.motion.env/location     // JSON: active/lat/lon/alt/speed/bearing/acc/satellites
            └─ 模块在 system_server（LSPosed 作用域「系统框架」）
                 ├─ hook LocationListenerTransport#onLocationChanged(Location|LocationResult)
                 │     → 用快照新建 Location 替换（无 mock 标记）
                 ├─ hook getLastLocation / getCurrentLocation → 返回同样替换过的 Location
                 └─ hook GnssStatusListenerTransport#onSatelliteStatusChanged(GnssStatus)
                       → 用 content://…/gnss 的卫星数组重建 GnssStatus
```

未在模拟（`active=false`）或读取 Provider 失败时，**一律透传真实值**，不影响系统服务。

## 3. 启用步骤

1. **LSPosed → 模块 → 作用域**：勾选 **「系统框架」**（`android`，有的版本显示为「系统框架(android)」）
2. **重启手机**（system_server 只在系统启动时注入）
3. App → 菜单「设置」→ 打开 **「系统级注入（所有 App 生效，无模拟标记）」** → 保存
4. 点「开始」运行 → 观察任意 App（含系统设置里的位置、地图、运动 App）都拿到虚拟定位

> 首次开启系统级注入时，App **不再**执行 `appops set … mock_location`，也不创建测试 Provider；
> 如果发现没生效，先关掉该开关即可回退到原进程内注入方式（无需重启）。

## 4. 自检

```bash
adb logcat -s LSPosed-Bridge | grep VLocHidden
```

应看到：

- `system hooks=N (system_server 级定位/GNSS 注入)` —— 系统级 hook 装上了
- `system location replaced: 38.89...,121.52...` —— 定位已被替换
- `system gnss replaced: satellites=NN` —— 卫星已被替换

App 内：设置里开关已开；运行时「关于 → 版本」为 0.33.0+。

## 5. 风险与回退

- system_server 内 Hook 属于**高风险操作**：所有 hook 都独立 try/catch、任何异常只透传，
  但**极端情况下仍可能导致系统服务异常**。建议先备份数据、确认能用 Recovery / 关闭 LSPosed 恢复。
- 回退方式（任选）：
  1. App 设置里关掉「系统级注入」→ 立即回到进程内注入；
  2. LSPosed 里取消「系统框架」作用域 → 重启；
  3. LSPosed 里禁用模块 → 重启。
- 不同 ROM / Android 版本的系统类名不同，模块会尝试多个候选类；
  若日志里没有 `system hooks=`，说明该版本的系统类名都未命中，可反馈 ROM 与 Android 版本。

## 6. 为什么计步 / IMU / 摄像头没做成系统级

| 模拟项 | 系统级可行性 | 原因 |
|---|---|---|
| 计步（STEP_COUNTER/DETECTOR） | ❌ Java 不可行 | 传感器事件由 **native `SensorService`** 直接分发给 App，不经过 system_server 的 Java 层；要做必须 Zygisk/native Hook 或替换 sensor HAL |
| IMU / 姿态 | ❌ 同上 | 同上 |
| 摄像头 | ❌ Java 不可行 | 相机数据在 **camera HAL / cameraserver**（native），Java 层只能逐 App 接管 Camera2/Camera1 |

结论（v0.34.0 更新）：**传感器已通过 Zygisk 原生模块实现"全 App 自动接管"**，
见 [`zygisk-sensors.md`](zygisk-sensors.md)；**相机仍是进程内接管**（HAL/cameraserver 无法注入）。

如果要连传感器/相机也系统级，需要改成 **Magisk + Zygisk native 模块**（C/C++ hook
`SensorService`、替换 camera provider），工作量和风险都显著更高，且必须真机调试。
