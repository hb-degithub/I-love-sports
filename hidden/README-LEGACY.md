# hidden/（旧：独立模块构建，已合并）

从 **v0.6.0** 起，LSPosed 模块的源码已合并进 `app/` 模块，主 App 与隐藏模块是**同一个 APK**：

- `app/src/main/java/com/vloc/motion/hidden/`：HookEntry / EnvReplay / EnvSnapshot
- `app/src/main/assets/xposed_init`：模块入口声明
- `app/build.gradle`：`compileOnly files('libs/xposed-api-82.jar')`
- `app/src/main/AndroidManifest.xml`：`xposedmodule` / `xposedminversion` 元数据

本目录保留 v0.4.0 的独立构建源码，**不再被 settings.gradle 包含、不会参与构建**，
仅作历史参考；两个版本不要同时安装（旧包名 `com.vloc.motion.hidden` 需先卸载）。
