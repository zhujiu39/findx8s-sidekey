#!/system/bin/sh
MODDIR=${0%/scripts/*}
umask 077
case "$1" in
    watch)
        [ "$#" -eq 2 ] || exit 2
        case "$2" in ''|*[!0-9]*) exit 2 ;; esac
        [ "${#2}" -le 2 ] && [ "$2" -le 31 ] || exit 2
        ;;
    toggle) [ "$#" -eq 1 ] || exit 2 ;;
    *) exit 2 ;;
esac
[ -r "$MODDIR/lib/torch.jar" ] || exit 2
export CLASSPATH="$MODDIR/lib/torch.jar"
exec /system/bin/app_process /system/bin cn.sidekey.SurfingBridge "$@"
