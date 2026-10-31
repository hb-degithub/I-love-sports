#!/usr/bin/env bash
# 构建 Zygisk 模块并打包成 Magisk 可刷入的 zip。
#
# 需要环境变量指向 Android NDK（任一）：
#   ANDROID_NDK_ROOT / ANDROID_NDK_HOME / ANDROID_NDK
# 用法：bash zygisk/build.sh   → 产物 zygisk/out/vloc-zygisk.zip
set -euo pipefail
cd "$(dirname "$0")"

NDK="${ANDROID_NDK_ROOT:-${ANDROID_NDK_HOME:-${ANDROID_NDK:-}}}"
if [ -z "$NDK" ] || [ ! -f "$NDK/build/cmake/android.toolchain.cmake" ]; then
  echo "找不到 Android NDK（请设置 ANDROID_NDK_ROOT）" >&2
  exit 1
fi

ABI=arm64-v8a
API=29
rm -rf build out
cmake -S . -B "build/$ABI" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI="$ABI" \
  -DANDROID_PLATFORM="android-$API" \
  -DCMAKE_BUILD_TYPE=Release > /dev/null
cmake --build "build/$ABI" -j"$(nproc)"

mkdir -p out/module/zygisk
cp magisk/module.prop magisk/customize.sh magisk/service.sh \
   magisk/post-fs-data.sh magisk/sepolicy.rule out/module/
cp "build/$ABI/arm64-v8a.so" out/module/zygisk/
chmod 755 out/module/customize.sh out/module/service.sh out/module/post-fs-data.sh

( cd out/module && zip -qr ../vloc-zygisk.zip . )
echo "构建完成：zygisk/out/vloc-zygisk.zip"
unzip -l out/vloc-zygisk.zip | tail -8
