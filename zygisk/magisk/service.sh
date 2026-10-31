#!/system/bin/sh
# 把主 App 写出的传感器状态复制到 /data/local/tmp/vloc/state.bin，
# 供 Zygisk 模块在任意 App 进程内 mmap 读取。
# 用 root 复制，App 自身无需任何额外权限。
(
  while true; do
    SRC=/data/user/0/com.vloc.motion/files/vloc_state.bin
    DST=/data/local/tmp/vloc/state.bin
    if [ -f "$SRC" ]; then
      cp -f "$SRC" "$DST.tmp" 2>/dev/null && mv -f "$DST.tmp" "$DST" 2>/dev/null
      chmod 644 "$DST" 2>/dev/null
      chown system:system "$DST" 2>/dev/null
    fi
    sleep 0.1
  done
) &
