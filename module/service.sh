#!/system/bin/sh
MODDIR=${0%/*}
nohup sh "$MODDIR/scripts/menu-install.sh" >/dev/null 2>&1 &
exec sh "$MODDIR/scripts/control.sh" start
