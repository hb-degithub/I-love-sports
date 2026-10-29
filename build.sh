#!/usr/bin/env bash
# 一键构建：默认构建主 App 的 debug APK
set -euo pipefail
cd "$(dirname "$0")"

if [[ ! -f .toolchain/env.sh ]]; then
  echo "缺少 .toolchain/env.sh，请先准备 JDK 17 + Android SDK 35 工具链" >&2
  exit 1
fi
# shellcheck disable=SC1091
source .toolchain/env.sh

TASKS=("$@")
if [[ ${#TASKS[@]} -eq 0 ]]; then
  TASKS=(:app:assembleDebug)
fi
exec gradle --no-daemon "${TASKS[@]}"
