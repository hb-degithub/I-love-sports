SKIPUNZIP=0

ui_print "- 安装 虚拟运动 · Zygisk 传感器模块"
ui_print "- 请配合「虚拟运动」App 使用（App 内开启「系统级注入」）"

# Zygisk 模块只需把 arm64-v8a.so 放到 zygisk/ 目录
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/post-fs-data.sh" 0 0 0755
set_perm "$MODPATH/zygisk/arm64-v8a.so" 0 0 0644
