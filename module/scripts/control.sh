#!/system/bin/sh
SCRIPT_DIR=${0%/*}
MODDIR=${SCRIPT_DIR%/*}
DATA=/data/adb/oppo_sidekey
BINARY="$MODDIR/bin/sidekey"
umask 077

[ -x "$BINARY" ] || { echo "监听程序不存在或不可执行" >&2; exit 1; }
"$BINARY" init "$DATA" || { echo "初始化配置失败" >&2; exit 1; }

start_service() {
    [ ! -f "$MODDIR/disable" ] && [ ! -f "$MODDIR/remove" ] || {
        echo "模块已禁用或等待卸载，请先在管理器中启用" >&2
        return 1
    }
    # 二进制程序持有文件锁，多次启动不会出现重复监听。
    nohup "$BINARY" daemon "$DATA" "$MODDIR" >/dev/null 2>&1 &
}

case "$1" in
    prepare-menu)
        [ ! -f "$MODDIR/disable" ] && [ ! -f "$MODDIR/remove" ] || exit 1
        nohup sh "$MODDIR/scripts/menu-install.sh" >/dev/null 2>&1 &
        ;;
    apps)
        [ "$#" -eq 1 ] || exit 2
        [ -r "$MODDIR/lib/torch.jar" ] || { echo "应用列表组件不存在，请重新安装模块" >&2; exit 1; }
        command -v timeout >/dev/null 2>&1 || { echo "系统缺少 timeout 命令" >&2; exit 1; }
        CLASSPATH="$MODDIR/lib/torch.jar" timeout -s KILL 12 /system/bin/app_process /system/bin cn.sidekey.AppCatalog
        result=$?
        [ "$result" -eq 0 ] && exit 0
        if [ "$result" -eq 124 ] || [ "$result" -eq 137 ]; then
            echo "读取应用列表超时，请重试" >&2
        fi
        exit "$result"
        ;;
    get) exec "$BINARY" get "$DATA" ;;
    start) start_service ;;
    stop) exec "$BINARY" stop "$DATA" ;;
    save)
        [ "$#" -eq 2 ] || exit 2
        "$BINARY" save "$DATA" "$2" >/dev/null || exit $?
        start_service || exit $?
        exec "$BINARY" get "$DATA"
        ;;
    test)
        case "$2" in single|double|long) ;; *) exit 2 ;; esac
        start_service || exit $?
        exec "$BINARY" test "$DATA" "$2"
        ;;
    *) echo "不支持的操作" >&2; exit 2 ;;
esac
