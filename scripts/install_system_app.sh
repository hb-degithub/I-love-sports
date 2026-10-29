#!/system/bin/sh
# =============================================================================
#  install_system_app.sh —— 以「Magisk / KernelSU / APatch 模块」方式把 APK
#                          安装为 /system/priv-app 的特权应用
#
#  适用：Android 8.0 – 15（Android 9+ 的 privapp allowlist 是强制的，注意看警告）
#  设计：由 App 通过 root 调用，例如
#        su -c "sh /data/user/0/com.vloc.motion/files/install_system_app.sh \
#                install /data/local/tmp/app-release.apk --pkg com.vloc.motion \
#                --perm android.permission.INSTALL_LOCATION_PROVIDER \
#                --perm android.permission.LOCATION_HARDWARE"
#
#  ⚠️ 先读这一段再用：
#    1. 本脚本解决的是「让 App 拿到 signature|privileged 权限」。
#       ACCESS_MOCK_LOCATION 是 protectionLevel="signature"，
#       priv-app + allowlist **拿不到**它，也**不需要**它
#       （addTestProvider 只查 appop）。证据见 docs/root-injection.md §5.1。
#    2. 在 Android 10+ 上，priv-app 路线能拿到的 LOCATION_HARDWARE +
#       injectLocation 只能写 last-known，**不能连续注入**（§2.4）。
#       所以 Android 10+ 装 priv-app 通常没有收益，反而增加 bootloop 风险。
#    3. Android 9+：一个 priv-app 若声明了 signature|privileged 权限却没进
#       allowlist，system_server 会 fatal → 设备无法启动。
#       安装前务必确认 APK 到底声明了哪些特权权限（用 aapt / apkanalyzer 在电脑上看）。
#
#  用法：
#    sh install_system_app.sh install <apk路径> [选项]
#    sh install_system_app.sh uninstall [选项]
#    sh install_system_app.sh status    [选项]
#
#  选项：
#    --pkg NAME          包名，默认 com.vloc.motion（本项目 applicationId）
#    --perm NAME         允许的特权权限，可重复；不传则不生成 allowlist 文件
#    --app-dir NAME      /system/priv-app 下的目录名，默认取包名最后一段并首字母大写
#    --module-id ID      模块 id / 目录名，默认 vloc_sysapp
#    --force             跳过预检警告（KernelSU metamodule 缺失、allowlist 为空等）
#    --dry-run           只打印将要做什么，不写任何文件
#    -h, --help          帮助
#
#  退出码：
#    0 成功   1 运行失败   2 参数错误   3 无 root
#    4 APK 不存在或不是 APK   6 未检测到可用的模块目录   7 预检未通过（需 --force）
#
#  幂等性：
#    install 会重建模块目录（先删后建），重复执行结果一致。
#    删除前会校验该目录确实是本模块（module.prop 里 id 匹配），不会误删别人的模块。
#
#  回滚：
#    sh install_system_app.sh uninstall --pkg com.vloc.motion
#    rm -rf /data/adb/modules/vloc_sysapp        # 等价的手工做法
#    重启后特权安装消失。若还想清掉 App 数据：
#      pm uninstall com.vloc.motion        # 需要 root 或 adb
#    如果已经 bootloop：Magisk 安全模式（开机按住音量下）/ adb shell magisk
#    --remove-modules / 或进 recovery 删 /data/adb/modules/vloc_sysapp。
#
#  生效时机：
#    模块在 post-fs-data 阶段挂载，PackageManagerService 在其后扫描，
#    所以**必须重启**。脚本不会自动重启。
# =============================================================================

set -eu

DEFAULT_PKG="com.vloc.motion"
# 模块根目录。VLOC_MODULES_DIR 仅用于本机/CI 测试，设备上不要设置。
MODULES_DIR="${VLOC_MODULES_DIR:-/data/adb/modules}"

ACTION=""
APK=""
PKG=""
APP_DIR=""
MODID="vloc_sysapp"
PERMS=" "
FORCE=0
DRY=0
ALLOWLIST_BASENAME="privapp-permissions-vloc.xml"
MANAGER="unknown"

say()  { printf '%s\n' "$*"; }
warn() { printf '[warn] %s\n' "$*" >&2; }
err()  { printf '[error] %s\n' "$*" >&2; }
die()  { c="$1"; shift; err "$*"; exit "$c"; }

usage() {
    # 打印文件头注释（第 3 行起到 set -eu 之前），去掉每行的 "# " 前缀
    awk 'NR>2 { if ($0 ~ /^set -eu/) exit; sub(/^# ?/, ""); print }' "$0" 2>/dev/null || true
}

# ---------------------------------------------------------------- 参数解析

while [ "$#" -gt 0 ]; do
    case "$1" in
        install|uninstall|status)
            if [ -z "$ACTION" ]; then ACTION="$1"; else APK="$1"; fi
            shift ;;
        --pkg)       [ "$#" -ge 2 ] || die 2 "--pkg 需要参数";       PKG="$2"; shift 2 ;;
        --perm)      [ "$#" -ge 2 ] || die 2 "--perm 需要参数";      PERMS="$PERMS$2 "; shift 2 ;;
        --app-dir)   [ "$#" -ge 2 ] || die 2 "--app-dir 需要参数";   APP_DIR="$2"; shift 2 ;;
        --module-id) [ "$#" -ge 2 ] || die 2 "--module-id 需要参数"; MODID="$2"; shift 2 ;;
        --force)     FORCE=1; shift ;;
        --dry-run)   DRY=1; shift ;;
        -h|--help)   usage; exit 0 ;;
        --)          shift; break ;;
        -*)          die 2 "未知选项: $1（--help 查看用法）" ;;
        *)           APK="$1"; shift ;;
    esac
done

[ -n "$ACTION" ] || { usage; exit 2; }

if [ -z "$PKG" ]; then
    PKG="$DEFAULT_PKG"
    warn "未提供 --pkg，使用默认值 '$DEFAULT_PKG'（本项目 applicationId）。"
    warn "若构建时改过 applicationId，请显式传入，例如 --pkg com.vloc.motion"
fi

case "$PKG" in
    *[!a-zA-Z0-9._]*|"") die 2 "包名不合法: '$PKG'" ;;
esac
case "$MODID" in
    *[!a-zA-Z0-9._-]*|"") die 2 "模块 id 不合法: '$MODID'（只允许 a-zA-Z0-9._-）" ;;
esac

# ---------------------------------------------------------------- 环境与预检

[ "$(id -u 2>/dev/null || echo 1)" = "0" ] || die 3 "需要 root。请用 su -c 调用本脚本。"

SDK="$(getprop ro.build.version.sdk 2>/dev/null || echo 0)"
REL="$(getprop ro.build.version.release 2>/dev/null || echo '?')"
say "设备: $(getprop ro.product.manufacturer 2>/dev/null || echo '?') $(getprop ro.product.model 2>/dev/null || echo '?') / Android $REL (SDK $SDK)"

detect_manager() {
    if [ -d /data/adb/magisk ] || command -v magisk >/dev/null 2>&1; then
        MANAGER="Magisk"
    elif [ -d /data/adb/ksu ] || [ "${KSU:-}" = "true" ] || command -v ksud >/dev/null 2>&1; then
        MANAGER="KernelSU"
    elif [ -d /data/adb/ap ] || [ -f /data/adb/apd ] || command -v apd >/dev/null 2>&1; then
        MANAGER="APatch"
    else
        MANAGER="unknown"
    fi
    say "root 管理器: $MANAGER"
}

# KernelSU / APatch 的 system/ 目录不会自动挂载，必须有 metamodule。
# 这是一个启发式探测（各版本存放位置不同），探测不到会要求 --force。
metamodule_present() {
    if [ -f /data/adb/ksu/metamodule ]; then return 0; fi
    if [ -f /data/adb/ap/metamodule ]; then return 0; fi
    for d in "$MODULES_DIR"/*; do
        [ -d "$d" ] || continue
        case "$(basename "$d")" in *meta*|*Meta*|*META*) return 0 ;; esac
        if [ -f "$d/metamodule" ]; then return 0; fi
        if [ -f "$d/module.prop" ] && grep -qi 'metamodule\|meta-overlayfs\|overlayfs' "$d/module.prop" 2>/dev/null; then
            return 0
        fi
    done
    return 1
}

[ -d "$(dirname "$MODULES_DIR")" ] || die 6 "找不到 $(dirname "$MODULES_DIR")，这台设备可能没有可用 root 管理器的模块系统。"
[ -d "$MODULES_DIR" ] || die 6 "找不到 $MODULES_DIR。请确认已安装 Magisk / KernelSU / APatch 且至少打开过一次管理器。"

detect_manager

if [ "$MANAGER" = "KernelSU" ] || [ "$MANAGER" = "APatch" ]; then
    if metamodule_present; then
        say "已检测到 metamodule（system/ 可挂载）"
    else
        warn "==============================================================="
        warn " 未检测到 metamodule！"
        warn " $MANAGER 的模块 system/ 目录**不会自动挂载**，必须先安装"
        warn " meta-overlayfs（或等价提供 magic-mount 的 metamodule），"
        warn " 否则重启后什么也不会发生。"
        warn " 官方说明: https://github.com/tiann/KernelSU/blob/main/website/docs/guide/module.md"
        warn " 确认已自行处理，可加 --force 跳过此检查。"
        warn "==============================================================="
        [ "$FORCE" = "1" ] || die 7 "预检未通过（缺少 metamodule）。确认无误请加 --force。"
    fi
fi

CTRL="$(getprop ro.control_privapp_permissions 2>/dev/null || echo '')"
say "ro.control_privapp_permissions = ${CTRL:-<未设置>}"

# ---------------------------------------------------------------- 工具函数

# 安全删除模块目录：只删"确实是本模块"的目录
safe_remove_moddir() {
    DIR="$MODULES_DIR/$MODID"
    [ -e "$DIR" ] || return 0
    case "$DIR" in
        "$MODULES_DIR"/*) : ;;
        *) err "内部错误：拒绝删除 $DIR"; return 1 ;;
    esac
    if [ -f "$DIR/module.prop" ]; then
        OLDID="$(sed -n 's/^id=//p' "$DIR/module.prop" | head -n 1)"
        if [ -n "$OLDID" ] && [ "$OLDID" != "$MODID" ]; then
            err "目录 $DIR 属于模块 '$OLDID'，不是 '$MODID'，拒绝删除。"
            return 1
        fi
    fi
    rm -rf "$DIR"
}

fix_context() {
    chcon -R u:object_r:system_file:s0 "$1" 2>/dev/null \
        || restorecon -R "$1" 2>/dev/null \
        || true
}

# ---------------------------------------------------------------- status

do_status() {
    DIR="$MODULES_DIR/$MODID"
    say ""
    say "--- 模块状态 ---"
    if [ -d "$DIR" ]; then
        say "模块目录: $DIR (存在)"
        [ -f "$DIR/module.prop" ] && sed 's/^/    /' "$DIR/module.prop"
    else
        say "模块目录: $DIR (不存在)"
    fi

    say ""
    say "--- 特权 APK 是否已挂载到 /system ---"
    FOUND_APK=0
    if [ -n "$APP_DIR" ]; then
        if [ -f "/system/priv-app/$APP_DIR/$APP_DIR.apk" ]; then
            say "/system/priv-app/$APP_DIR/$APP_DIR.apk  ✅ 已挂载"
            FOUND_APK=1
        fi
    else
        # 未指定则扫一遍，找出我们写过的目录
        for f in /system/priv-app/*/*.apk; do
            [ -f "$f" ] || continue
            case "$f" in *vloc*|*Vloc*|*VLOC*) say "$f  （疑似）"; FOUND_APK=1 ;; esac
        done
    fi
    [ "$FOUND_APK" = "1" ] || say "未在 /system/priv-app 下看到对应 APK（未重启 / 未挂载 / 已被卸载）"

    say ""
    say "--- allowlist ---"
    ALFILE="/system/etc/permissions/$ALLOWLIST_BASENAME"
    if [ -f "$ALFILE" ]; then
        say "$ALFILE:"
        sed 's/^/    /' "$ALFILE"
    else
        say "$ALFILE 不存在（若 APK 未声明 signature|privileged 权限，这是正常的）"
    fi

    say ""
    say "--- PackageManager 视角 ---"
    PMPATH="$(pm path "$PKG" 2>/dev/null || true)"
    if [ -n "$PMPATH" ]; then
        printf '%s\n' "$PMPATH" | sed 's/^/    /'
        case "$PMPATH" in
            */system/priv-app/*|*/system/app/*) say "    ⇒ 系统分区安装" ;;
            *) say "    ⇒ 用户安装（模块未生效）" ;;
        esac
    else
        say "    包未安装: $PKG"
    fi

    # 已授予的权限（能看出 allowlist 是否真的生效）
    say ""
    say "--- 相关权限授予情况 ---"
    DUMP="$(dumpsys package "$PKG" 2>/dev/null || true)"
    for P in android.permission.INSTALL_LOCATION_PROVIDER \
             android.permission.LOCATION_HARDWARE \
             android.permission.ACCESS_FINE_LOCATION \
             android.permission.ACCESS_MOCK_LOCATION; do
        if printf '%s\n' "$DUMP" | grep -q "$P: granted=true"; then
            say "    $P: granted"
        else
            say "    $P: (未授予)"
        fi
    done
    say ""
    say "提示: ACCESS_MOCK_LOCATION 是 signature 权限，priv-app 永远拿不到，也不需要。"
    exit 0
}

# ---------------------------------------------------------------- uninstall

do_uninstall() {
    DIR="$MODULES_DIR/$MODID"
    if [ ! -e "$DIR" ]; then
        say "模块 $MODID 不存在，无需卸载（幂等）。"
        exit 0
    fi
    if [ "$DRY" = "1" ]; then
        say "[dry-run] 将删除 $DIR"
        exit 0
    fi
    safe_remove_moddir || die 1 "删除模块目录失败"
    say "已删除模块目录: $DIR"
    say ""
    say "后续步骤："
    say "  1. 重启设备，特权安装即消失。"
    say "  2. 若想顺带清掉 App 及其数据：pm uninstall $PKG"
    say "     （只清数据可先 pm uninstall -k $PKG，具体行为随版本不同）"
    say "  3. 如果已经 bootloop：进 Magisk 安全模式或 recovery，删除"
    say "     /data/adb/modules/$MODID 即可恢复。"
    exit 0
}

# ---------------------------------------------------------------- install

do_install() {
    [ -n "$APK" ] || die 2 "install 需要 APK 路径。用法: sh $0 install <apk> --pkg <包名>"
    [ -f "$APK" ] || die 4 "APK 不存在: $APK"
    [ -r "$APK" ] || die 4 "APK 不可读: $APK"

    # 极简 APK 校验：zip 魔数 "PK"
    MAGIC="$(dd if="$APK" bs=1 count=2 2>/dev/null || true)"
    [ "$MAGIC" = "PK" ] || die 4 "不是有效的 APK/ZIP 文件（缺少 PK 魔数）: $APK"

    SIZE="$(wc -c < "$APK" 2>/dev/null | tr -d ' ' || echo '?')"
    say "APK: $APK (${SIZE} bytes)"

    # 目录名：默认取包名最后一段并首字母大写
    if [ -z "$APP_DIR" ]; then
        LAST="$(printf '%s' "$PKG" | awk -F. '{print $NF}')"
        APP_DIR="$(printf '%s' "$LAST" | cut -c1 | tr 'a-z' 'A-Z')$(printf '%s' "$LAST" | cut -c2-)"
    fi
    case "$APP_DIR" in
        *[!a-zA-Z0-9_-]*|"") die 2 "app-dir 不合法: '$APP_DIR'" ;;
    esac

    # ---- 预检：allowlist 为空但有 enforce 的风险
    TRIMPERMS="$(printf '%s' "$PERMS" | tr -d ' ')"
    if [ -z "$TRIMPERMS" ]; then
        warn "未指定任何 --perm，不会生成 privapp allowlist 文件。"
        warn "如果 APK 的 AndroidManifest 里声明了 signature|privileged 权限，"
        warn "Android 9+ 会因为它不在 allowlist 里而**无法开机**。"
        warn "请先用 aapt/apkanalyzer 在电脑上确认 APK 声明了哪些特权权限；"
        warn "确认没有这类权限，或已自行处理，可加 --force。"
    fi

    # ---- 预检：同名用户应用（签名冲突）
    EXISTS_USER=0
    PMPATH="$(pm path "$PKG" 2>/dev/null || true)"
    if [ -n "$PMPATH" ]; then
        case "$PMPATH" in
            */system/priv-app/*|*/system/app/*) say "已存在系统分区安装，将覆盖" ;;
            *)
                EXISTS_USER=1
                warn "设备上已有以**用户应用**形式安装的 $PKG。"
                warn "换成系统应用后，若两者签名不同，行为可能异常（数据保留但更新失败）。"
                warn "建议先执行: pm uninstall -k $PKG   （保留数据）/ pm uninstall $PKG（全清）"
                ;;
        esac
    fi

    if [ "$DRY" = "0" ] && [ "$FORCE" = "0" ]; then
        if [ -z "$TRIMPERMS" ] && [ "$EXISTS_USER" = "1" ]; then
            die 7 "预检未通过（无 allowlist + 存在用户安装）。确认无误请加 --force。"
        fi
        if [ -z "$TRIMPERMS" ] && [ "$CTRL" = "enforce" ]; then
            die 7 "ro.control_privapp_permissions=enforce 且未指定 --perm。确认 APK 无特权权限后再加 --force。"
        fi
    fi

    DIR="$MODULES_DIR/$MODID"

    if [ "$DRY" = "1" ]; then
        say ""
        say "[dry-run] 将创建模块:"
        say "  $DIR/module.prop"
        say "  $DIR/system/priv-app/$APP_DIR/$APP_DIR.apk"
        if [ -n "$TRIMPERMS" ]; then
            say "  $DIR/system/etc/permissions/$ALLOWLIST_BASENAME"
            for P in $PERMS; do say "      <permission name=\"$P\"/>"; done
        fi
        say "[dry-run] 不写任何文件。"
        exit 0
    fi

    # ---- 重建模块目录
    safe_remove_moddir || die 1 "清理旧模块目录失败"
    mkdir -p "$DIR/system/priv-app/$APP_DIR" || die 1 "创建目录失败: $DIR"

    printf '%s\n' \
        "id=$MODID" \
        "name=VLoc Motion privileged app" \
        "version=1.0" \
        "versionCode=1" \
        "author=vloc" \
        "description=Install $PKG as privileged app (generated by install_system_app.sh)" \
        > "$DIR/module.prop"

    cp "$APK" "$DIR/system/priv-app/$APP_DIR/$APP_DIR.apk" || die 1 "拷贝 APK 失败"

    # ---- 可选 allowlist
    if [ -n "$TRIMPERMS" ]; then
        mkdir -p "$DIR/system/etc/permissions"
        ALFILE="$DIR/system/etc/permissions/$ALLOWLIST_BASENAME"
        {
            printf '%s\n' '<?xml version="1.0" encoding="utf-8"?>'
            printf '%s\n' '<!-- generated by install_system_app.sh -->'
            printf '%s\n' '<permissions>'
            printf '    <privapp-permissions package="%s">\n' "$PKG"
            for P in $PERMS; do
                printf '        <permission name="%s"/>\n' "$P"
            done
            printf '%s\n' '    </privapp-permissions>'
            printf '%s\n' '</permissions>'
        } > "$ALFILE"
        say "已生成 allowlist: $ALFILE"
    fi

    # ---- 权限与 SELinux 上下文
    find "$DIR" -type d -exec chmod 0755 {} \; 2>/dev/null || true
    find "$DIR" -type f -exec chmod 0644 {} \; 2>/dev/null || true
    chown -R 0:0 "$DIR" 2>/dev/null || true
    fix_context "$DIR"

    say ""
    say "模块已写入: $DIR"
    say "  module.prop"
    say "  system/priv-app/$APP_DIR/$APP_DIR.apk"
    if [ -n "$TRIMPERMS" ]; then
        say "  system/etc/permissions/$ALLOWLIST_BASENAME"
    fi
    say ""
    say "==== 必须重启才生效 ===="
    say "重启后可用以下命令验证："
    say "  sh $0 status --pkg $PKG --app-dir $APP_DIR"
    say "  pm path $PKG                 # 应显示 /system/priv-app/..."
    say ""
    say "回滚："
    say "  sh $0 uninstall --pkg $PKG"
    say ""
    say "⚠️ 若重启后卡在开机动画：进 Magisk 安全模式 / recovery，删除"
    say "   /data/adb/modules/$MODID 即可恢复。"
    exit 0
}

# ---------------------------------------------------------------- 分发

case "$ACTION" in
    install)   do_install ;;
    uninstall) do_uninstall ;;
    status)    do_status ;;
    *)         die 2 "未知操作: $ACTION（install | uninstall | status）" ;;
esac
