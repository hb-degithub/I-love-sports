# Zygisk 传感器模块（原生）

```
zygisk/
├── src/module.cpp     # Zygisk 模块：hook SystemSensorManager 的 JNI 分发点
├── zygisk.hpp         # Zygisk 官方 API 头（vendored；已修正属性语法，见文件内注释）
├── CMakeLists.txt     # 构建 arm64-v8a.so
├── magisk/            # Magisk 模块文件（module.prop / customize.sh / service.sh / sepolicy.rule）
└── build.sh           # 一键构建 + 打包 zip → out/vloc-zygisk.zip
```

## 构建

```bash
export ANDROID_NDK_ROOT=/path/to/ndk
bash zygisk/build.sh
# 产物：zygisk/out/vloc-zygisk.zip（Magisk 刷入）
```

CI 里由 `.github/workflows/zygisk.yml` 自动构建（使用 runner 预装 NDK）。

## 设计约束

- **只用 Zygisk 官方 API**（`hookJniNativeMethods`）替换 JNI 函数指针，
  不改写机器指令、不做 inline hook —— 出问题只会导致本模块失效，不会破坏系统。
- 状态文件读不到 / `active=0` / 超过 2.5 秒未更新 → **全部透传真实传感器**。
- 编译期 `static_assert(sizeof(VlocState) == 112)` 保证 C++ 结构体与
  `app/src/main/java/com/vloc/motion/service/VlocStateFile.java` 布局一致。

详见 [`../docs/zygisk-sensors.md`](../docs/zygisk-sensors.md)。
