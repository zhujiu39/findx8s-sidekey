#!/system/bin/sh
MODDIR=${0%/scripts/*}
umask 077
case "$1" in watch|toggle) ;; *) exit 2 ;; esac
[ "$#" -eq 1 ] && [ -r "$MODDIR/lib/torch.jar" ] || exit 2
export CLASSPATH="$MODDIR/lib/torch.jar"
exec /system/bin/app_process /system/bin cn.sidekey.SurfingBridge "$1"
