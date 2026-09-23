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
    export-logs|export-log-status)
        [ "$#" -eq 2 ] && [ "${#2}" -eq 32 ] || exit 2
        case "$2" in *[!a-f0-9]*) exit 2 ;; esac
        if [ "$1" = export-log-status ]; then
            result="$DATA/log-export-$2.json"
            if [ -f "$result" ] && [ ! -L "$result" ]; then cat "$result"
            else printf '{"id":"%s","state":"preparing","message":"正在准备日志导出"}\n' "$2"; fi
            exit 0
        fi
        [ ! -f "$MODDIR/disable" ] && [ ! -f "$MODDIR/remove" ] || { echo '模块已禁用' >&2; exit 1; }
        [ -r "$MODDIR/lib/torch.jar" ] || { echo '日志导出组件缺失，请重新安装模块' >&2; exit 1; }
        command -v setsid >/dev/null 2>&1 || { echo '系统缺少 setsid 命令' >&2; exit 1; }
        CLASSPATH="$MODDIR/lib/torch.jar" nohup setsid /system/bin/app_process /system/bin cn.sidekey.LogExportServer "$MODDIR" "$2" </dev/null >/dev/null 2>&1 &
        printf '{"ok":true,"id":"%s"}\n' "$2"
        ;;
    logs)
        [ "$#" -eq 1 ] || exit 2
        exec sh "$MODDIR/scripts/logs.sh"
        ;;
    mijia)
        [ "$#" -eq 2 ] || exit 2
        exec sh "$MODDIR/scripts/mijia.sh" request "$2"
        ;;
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
    app-icons)
        [ "$#" -eq 2 ] || exit 2
        user=${2%%:*}
        packages=${2#*:}
        case "$user" in ''|*[!0-9]*) exit 2 ;; esac
        case "$packages" in ''|*[!A-Za-z0-9_.,]*) exit 2 ;; esac
        CLASSPATH="$MODDIR/lib/torch.jar" timeout -s KILL 12 /system/bin/app_process /system/bin cn.sidekey.AppCatalog icons "$user" "$packages"
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
    save-part)
        [ "$#" -eq 2 ] || exit 2
        token=${2%%:*}; rest=${2#*:}; index=${rest%%:*}; chunk=${rest#*:}
        [ "${#token}" -eq 32 ] || exit 2
        case "$token" in *[!a-f0-9]*) exit 2 ;; esac
        case "$index" in ''|*[!0-9]*) exit 2 ;; esac
        case "$chunk" in ''|*[!a-f0-9]*) exit 2 ;; esac
        [ "${#chunk}" -le 12000 ] && [ "$index" -lt 700 ] || exit 2
        part="$DATA/config-upload-$token.part"
        if [ "$index" -eq 0 ]; then
            (set -C; : >"$part") || exit 1
        fi
        [ -f "$part" ] && [ ! -L "$part" ] && [ "$(wc -l <"$part")" -eq "$index" ] || exit 2
        printf '%s\n' "$chunk" >>"$part" || exit 1
        printf 'null\n'
        ;;
    save-commit|save-abort)
        [ "$#" -eq 2 ] && [ "${#2}" -eq 32 ] || exit 2
        case "$2" in *[!a-f0-9]*) exit 2 ;; esac
        part="$DATA/config-upload-$2.part"
        if [ "$1" = save-abort ]; then rm -f "$part"; printf 'null\n'; exit 0; fi
        [ -f "$part" ] && [ ! -L "$part" ] || exit 2
        "$BINARY" save-stdin "$DATA" <"$part" >/dev/null
        result=$?; rm -f "$part"
        [ "$result" -eq 0 ] || exit "$result"
        start_service || exit $?
        exec "$BINARY" get "$DATA"
        ;;
    test)
        case "$2" in single|double|long) ;; *) exit 2 ;; esac
        start_service || exit $?
        exec "$BINARY" test "$DATA" "$2" "$MODDIR"
        ;;
    *) echo "不支持的操作" >&2; exit 2 ;;
esac
