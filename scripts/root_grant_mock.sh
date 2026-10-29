#!/system/bin/sh
# =============================================================================
#  root_grant_mock.sh —— 为指定包授予 android:mock_location appop（含检查 / 回滚）
#
#  适用：Android 8.0 – 15，Magisk / KernelSU / APatch 均可。
#  设计：由 App 通过 root 调用，例如
#        su -c "sh /data/user/0/com.vloc.motion/files/root_grant_mock.sh com.vloc.motion"
#
#  为什么需要这一步：
#    LocationManagerService.addTestProvider / setTestProviderLocation 唯一的权限
#    门槛就是 AppOpsManager.OP_MOCK_LOCATION（字符串 "android:mock_location"）。
#    它默认是 MODE_ERRORED，必须显式 allow。
#    证据：frameworks/base/services/core/java/com/android/server/location/
#          LocationManagerService.java（android-15.0.0_r1 L1463）
#          frameworks/base/core/java/android/app/AppOpsManager.java（OP_MOCK_LOCATION
#          默认 MODE_ERRORED）
#
#  用法：
#    sh root_grant_mock.sh [选项] [包名]
#
#    包名            省略时使用 com.vloc.motion（本项目 applicationId，仍建议显式传入）
#    --check         只读检查，不做任何修改
#    --revoke        回滚：把 appop 显式设为 deny（等价于用户取消"模拟位置应用"）
#    --reset         回滚：把 appop 恢复为 default（厂商默认值，通常等同未授权）
#    --user N        指定 Android 用户 ID（默认 0 / 机主）
#    -h, --help      显示帮助
#
#  退出码：
#    0  成功（appop 已确认为 allow）
#    1  运行时错误（写操作失败 / 校验不通过）
#    2  参数错误
#    3  没有 root
#    4  目标包未安装
#    5  系统不提供 appops 命令（极罕见）
#
#  幂等性：
#    "allow" 重复设置是安全的（appops 只是覆盖同一条记录），不会报错也不会累积。
#    脚本每次都会回读校验，因此重复执行的结果与单次执行一致。
#
#  回滚说明：
#    ./root_grant_mock.sh --revoke com.vloc.motion    # 显式 deny，最干净
#    ./root_grant_mock.sh --reset  com.vloc.motion    # 恢复厂商默认
#    也可以让用户在「开发者选项 → 选择模拟位置信息应用」里改选「无」。
#    只删 App 不会自动回收 appop —— appop 是按包名持久化的，重装同名包会继承。
#
#  注意：
#    授予后，系统的「开发者选项 → 选择模拟位置信息应用」会显示本 App，
#    这是系统行为，无法通过脚本隐藏（详见 docs/mock-detection.md §2.6）。
# =============================================================================

set -eu

DEFAULT_PKG="com.vloc.motion"
PKG=""
MODE="apply"
USER_ID=0
USE_CMD=1

# ---------------------------------------------------------------- 输出工具

say()  { printf '%s\n' "$*"; }
warn() { printf '[warn] %s\n' "$*" >&2; }
err()  { printf '[error] %s\n' "$*" >&2; }

usage() {
    # 打印文件头注释（第 3 行起到 set -eu 之前），去掉每行的 "# " 前缀
    awk 'NR>2 { if ($0 ~ /^set -eu/) exit; sub(/^# ?/, ""); print }' "$0" 2>/dev/null || true
    say ""
    say "用法: sh $0 [--check|--revoke|--reset] [--user N] [包名]"
}

die() {
    code="$1"; shift
    err "$*"
    exit "$code"
}

# ---------------------------------------------------------------- 参数解析

while [ "$#" -gt 0 ]; do
    case "$1" in
        --check)        MODE="check";  shift ;;
        --revoke|--deny) MODE="revoke"; shift ;;
        --reset)        MODE="reset";  shift ;;
        --user)
            [ "$#" -ge 2 ] || die 2 "--user 需要一个参数"
            USER_ID="$2"; shift 2 ;;
        -h|--help)      usage; exit 0 ;;
        --)             shift; break ;;
        -*)             die 2 "未知选项: $1（用 --help 查看用法）" ;;
        *)              PKG="$1"; shift ;;
    esac
done

# 位置参数里可能还有包名
if [ -z "$PKG" ] && [ "$#" -gt 0 ]; then
    PKG="$1"
fi

if [ -z "$PKG" ]; then
    PKG="$DEFAULT_PKG"
    warn "未提供包名，使用默认值 '$DEFAULT_PKG'（本项目 applicationId）。"
    warn "若构建时改过 applicationId，请通过第一个参数显式传入，例如："
    warn "  sh $0 com.vloc.motion"
fi

case "$PKG" in
    *[!a-zA-Z0-9._]*|"") die 2 "包名不合法: '$PKG'" ;;
esac

# ---------------------------------------------------------------- 环境检查

[ "$(id -u 2>/dev/null || echo 1)" = "0" ] || die 3 "需要 root。请先授予 su 权限，并用 su -c 调用本脚本。"

SDK="$(getprop ro.build.version.sdk 2>/dev/null || echo '?')"
REL="$(getprop ro.build.version.release 2>/dev/null || echo '?')"
MANUF="$(getprop ro.product.manufacturer 2>/dev/null || echo '?')"
MODEL="$(getprop ro.product.model 2>/dev/null || echo '?')"
say "设备: $MANUF $MODEL / Android $REL (SDK $SDK)"

command -v cmd >/dev/null 2>&1 || USE_CMD=0
if [ "$USE_CMD" = "0" ] && ! command -v appops >/dev/null 2>&1; then
    die 5 "系统既没有 'cmd' 也没有 'appops'，无法操作 appops。"
fi

# 统一入口：优先 cmd appops（8.0–15 都在），退化到 appops 二进制
ao() {
    if [ "$USE_CMD" = "1" ]; then
        if [ "$USER_ID" = "0" ]; then
            cmd appops "$@"
        else
            cmd appops --user "$USER_ID" "$@"
        fi
    else
        if [ "$USER_ID" = "0" ]; then
            appops "$@"
        else
            appops --user "$USER_ID" "$@"
        fi
    fi
}

# 包是否已安装（精确匹配，避免 com.foo 命中 com.foobar）
is_installed() {
    LIST="$(pm list packages "$1" 2>/dev/null || true)"
    printf '%s\n' "$LIST" | grep -qx "package:$1"
}

OUT=""
MODE_NOW=""

# 读取当前 mode，容错各种输出格式：
#   Android 8   : android:mock_location: allow
#   Android 10+ : android:mock_location: allow; time=+1m2s ago; duration=...
#   Android 12+ : 可能带 attribution tag 子行
read_mode() {
    RAW="$(ao get "$PKG" android:mock_location 2>/dev/null || true)"
    if [ -z "$RAW" ]; then
        RAW="$(ao get "$PKG" 58 2>/dev/null || true)"
    fi
    OUT="$RAW"
    LINE="$(printf '%s\n' "$RAW" | grep -i 'mock_location' | head -n 1)"
    [ -n "$LINE" ] || LINE="$(printf '%s\n' "$RAW" | head -n 1)"
    MODE_NOW="$(printf '%s' "$LINE" | sed 's/.*:[[:space:]]*//' | sed 's/[;[:space:]].*//' | tr 'A-Z' 'a-z')"
}

mode_is_allow() {
    [ "$MODE_NOW" = "allow" ]
}

show_state() {
    say "当前 appop 状态: ${MODE_NOW:-<空/未设置>}"
    if [ -n "$OUT" ]; then
        printf '%s\n' "$OUT" | sed 's/^/    /'
    fi
}

# ---------------------------------------------------------------- 主逻辑

is_installed "$PKG" || die 4 "包未安装: $PKG（先安装 App，再执行本脚本）"

case "$MODE" in
    check)
        read_mode
        show_state
        if mode_is_allow; then
            say "结果: 已授权（mock_location = allow）"
            say "RESULT=pkg=$PKG mode=$MODE_NOW verified=yes action=check"
            exit 0
        else
            say "结果: 未授权 —— addTestProvider 会失败（详见下方提示）"
            say "  提示 1: 若状态为空/default，调用 addTestProvider 会抛 SecurityException"
            say "          （AppOpsManager.noteOp 对 MODE_ERRORED 抛异常）。"
            say "  提示 2: 若状态为 deny/ignore，addTestProvider 会静默返回不报错，"
            say "          紧接着 setTestProviderLocation 抛 IllegalArgumentException:"
            say "          provider doesn't exist。"
            say "RESULT=pkg=$PKG mode=${MODE_NOW:-unset} verified=no action=check"
            exit 1
        fi
        ;;

    revoke|reset)
        read_mode
        if [ "$MODE" = "revoke" ]; then
            TARGET="deny"
        else
            TARGET="default"
        fi
        if [ "$MODE_NOW" = "$TARGET" ]; then
            say "已是 '$TARGET'，无需修改（幂等）。"
            say "RESULT=pkg=$PKG mode=$MODE_NOW verified=yes action=$MODE"
            exit 0
        fi
        if ! ao set "$PKG" android:mock_location "$TARGET" >/dev/null 2>&1; then
            ao set "$PKG" 58 "$TARGET" >/dev/null 2>&1 \
                || die 1 "设置 appop 为 '$TARGET' 失败"
        fi
        read_mode
        show_state
        if [ "$MODE_NOW" = "$TARGET" ]; then
            say "已回滚: mock_location = $TARGET"
            say "请让用户在系统「开发者选项 → 选择模拟位置信息应用」中确认已不再指向本 App。"
            say "RESULT=pkg=$PKG mode=$MODE_NOW verified=yes action=$MODE"
            exit 0
        fi
        err "回滚校验失败：期望 '$TARGET'，实际 '${MODE_NOW:-空}'"
        say "RESULT=pkg=$PKG mode=${MODE_NOW:-unset} verified=no action=$MODE"
        exit 1
        ;;

    apply)
        read_mode
        if mode_is_allow; then
            say "已是 allow，无需修改（幂等）。"
            show_state
        else
            say "授予 appop: $PKG -> android:mock_location allow"
            if ! ao set "$PKG" android:mock_location allow >/dev/null 2>&1; then
                # 极少数 ROM 需要数字 op（OP_MOCK_LOCATION = 58）
                warn "按操作名设置失败，回退到数字 op 58"
                ao set "$PKG" 58 allow >/dev/null 2>&1 \
                    || die 1 "appops set 失败。可能原因：包名错误、系统组件异常、或 SELinux 拒绝。"
            fi
            read_mode
            show_state
            mode_is_allow || die 1 "校验失败：设置后读取到 '${MODE_NOW:-空}'，期望 'allow'。"
        fi

        # 附带检查：接收端权限（不影响注入，但影响 App 自己显示位置）
        if ! dumpsys package "$PKG" 2>/dev/null \
                | grep -q 'android.permission.ACCESS_FINE_LOCATION: granted=true'; then
            warn "ACCESS_FINE_LOCATION 尚未授予。注入不需要它，但 App 自己要"
            warn "requestLocationUpdates 回显位置时需要（Android 10+ 后台还需"
            warn "ACCESS_BACKGROUND_LOCATION）。"
        fi

        say ""
        say "完成。下一步（由 App 侧执行）："
        say "  1. addTestProvider(\"gps\"/\"network\"/\"fused\", ...)"
        say "  2. setTestProviderEnabled(name, true)   # 建议显式调用"
        say "  3. setTestProviderLocation(name, location)   # location 必须 complete"
        say "  4. 退出时 removeTestProvider(name) 恢复真实 GPS"
        say ""
        say "回滚: sh $0 --revoke $PKG"
        say "RESULT=pkg=$PKG mode=$MODE_NOW verified=yes action=apply"
        exit 0
        ;;
esac
