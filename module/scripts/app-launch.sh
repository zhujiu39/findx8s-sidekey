#!/system/bin/sh
# 系统命令只接收管道，不把模块私有日志文件描述符传给 Binder。
DATA=/data/adb/oppo_sidekey
SCRIPT_DIR=${0%/*}
MODDIR=${SCRIPT_DIR%/*}
umask 077
mkdir -p "$DATA" || exit 1
exec 3>"$DATA/app-launch.log"
report() { printf '%s\n' "$*" >&3; }
fail() { report "$*"; exit 1; }
[ "$#" -eq 2 ] || fail '应用启动参数不完整'
package=$1
mode=$2
deadline=$(($(date +%s) + 9))
case "$package" in ''|*[!A-Za-z0-9_.]*|.*|*.|*..*) fail '应用包名无效' ;; esac
case "$package" in *.*) ;; *) fail '应用包名无效' ;; esac
case "$mode" in normal|freeform) ;; *) fail '应用启动模式无效' ;; esac
command -v timeout >/dev/null 2>&1 || fail '系统缺少 timeout 命令'
user=$(timeout -s KILL 1 am get-current-user 3>&- 2>&1)
case "$user" in ''|*[!0-9]*) fail "无法读取当前用户：$user" ;; esac
report "应用：$package；用户：$user；模式：$mode"
resolved=$(timeout -s KILL 1 cmd package resolve-activity --brief --user "$user" -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p "$package" 3>&- 2>&1)
component=$(printf '%s\n' "$resolved" | tr -d '\r' | tail -n 1)
case "$component" in
    "$package"/*) ;;
    *)
        # 多桌面入口可能返回系统选择器；只选属于目标应用的显式组件。
        resolved=$(timeout -s KILL 1 cmd package query-activities --brief --components --user "$user" -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p "$package" 3>&- 2>&1)
        component=$(printf '%s\n' "$resolved" | tr -d '\r' | while IFS= read -r line; do case "$line" in "$package"/*) printf '%s\n' "$line"; break ;; esac; done)
        ;;
esac
case "$component" in "$package"/*) ;; *) fail "找不到可启动入口，应用可能已卸载或停用：$resolved" ;; esac
case "$component" in *[!A-Za-z0-9_./\$]*) fail '系统返回了无效的应用入口' ;; esac
report "入口：$component"
if [ "$mode" = freeform ]; then
    [ -r "$MODDIR/lib/torch.jar" ] || fail '小窗组件不存在，请重新安装模块并重启'
    remaining=$((deadline - $(date +%s)))
    [ "$remaining" -gt 0 ] || fail '小窗启动总等待时间已耗尽'
    output=$(CLASSPATH="$MODDIR/lib/torch.jar" timeout -s KILL "$remaining" /system/bin/app_process /system/bin cn.sidekey.ZoomWindowLauncher "$component" "$user" 3>&- 2>&1)
    result=$?
    report "$output"
    case "$result" in 124|137) fail '等待 ColorOS 小窗确认超时，未重复启动或转为全屏' ;; esac
    [ "$result" -eq 0 ] || fail "ColorOS 小窗启动失败，退出码：$result；未转为全屏"
    printf '%s\n' "$output" | grep -qxF "SIDEKEY_ZOOM_CONFIRMED $package $user" || fail '未收到目标应用的小窗状态确认；未转为全屏'
    report '系统已确认目标应用进入 ColorOS 小窗'
    exit 0
fi
start_app() {
    remaining=$((deadline - $(date +%s)))
    [ "$remaining" -gt 0 ] || { report '应用启动总等待时间已耗尽'; return 124; }
    output=$(timeout -s KILL "$remaining" am start -W --user "$user" -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10200000 -n "$component" "$@" 3>&- 2>&1)
    result=$?
    report "$output"
    # am 即使打印启动错误，也可能返回 0；必须同时检查系统结果。
    [ "$result" -eq 0 ] || { report "启动命令退出码：$result"; return "$result"; }
    case "$output" in
        *'Status: timeout'*) return 124 ;;
        *Error:*|*Exception*|*'Background activity'*|*'background activity'*|*'Permission Denial'*|*'Permission denied'*) return 1 ;;
        *'Status: ok'*) return 0 ;;
        *) report '未收到系统启动成功回执'; return 65 ;;
    esac
}
start_app || fail '应用打开失败，请查看以上系统返回信息。'
report '系统已确认普通打开'
