#!/system/bin/sh
# 准备状态文件目录（App 私有目录 → 这里，供各 App 进程读取）
mkdir -p /data/local/tmp/vloc
chmod 755 /data/local/tmp/vloc
