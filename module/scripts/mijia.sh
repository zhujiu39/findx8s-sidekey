#!/system/bin/sh
SCRIPT_DIR=${0%/*}
MODDIR=${SCRIPT_DIR%/*}
DATA=/data/adb/oppo_sidekey/mijia
umask 077
export CLASSPATH="$MODDIR/lib/mijia.jar"

service_alive() {
    [ -r "$DATA/service.pid" ] || return 1
    read -r mijia_pid < "$DATA/service.pid"
    case "$mijia_pid" in ''|*[!0-9]*) return 1 ;; esac
    kill -0 "$mijia_pid" 2>/dev/null || return 1
    [ -r "/proc/$mijia_pid/cmdline" ] || return 1
    tr '\000' ' ' < "/proc/$mijia_pid/cmdline" | grep -q 'cn.sidekey.mijia.MijiaMain.*server'
}

if [ "$1" = stop ]; then
    service_alive || exit 0
    exec timeout -s KILL 8 /system/bin/app_process /system/bin cn.sidekey.mijia.MijiaMain stop
fi
[ ! -f "$MODDIR/disable" ] && [ ! -f "$MODDIR/remove" ] || exit 1
[ -r "$CLASSPATH" ] || { echo '米家组件缺失，请重新安装模块' >&2; exit 1; }
[ ! -L "$DATA" ] || exit 1
mkdir -p "$DATA" && chmod 700 "$DATA" || exit 1

start_mijia() {
    # 独立进程会话，扫码和网络任务不会被侧键动作的 10 秒清理终止。
    command -v setsid >/dev/null 2>&1 || return 1
    nohup setsid /system/bin/app_process /system/bin cn.sidekey.mijia.MijiaMain server "$MODDIR" </dev/null >/dev/null 2>&1 &
}

case "$1" in
    start) start_mijia ;;
    request)
        [ "$#" -eq 2 ] && [ "${#2}" -le 65536 ] || exit 2
        case "$2" in ''|*[!a-f0-9]*) exit 2 ;; esac
        service_alive || start_mijia || exit 1
        exec timeout -s KILL 9 /system/bin/app_process /system/bin cn.sidekey.mijia.MijiaMain request "$2"
        ;;
    trigger)
        [ "$#" -eq 2 ] && [ "${#2}" -eq 32 ] || exit 2
        case "$2" in *[!a-f0-9]*) exit 2 ;; esac
        service_alive || start_mijia || exit 1
        exec timeout -s KILL 9 /system/bin/app_process /system/bin cn.sidekey.mijia.MijiaMain trigger "$2"
        ;;
    *) exit 2 ;;
esac
