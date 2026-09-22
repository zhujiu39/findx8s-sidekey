#!/system/bin/sh
MODDIR=${0%/*}
sh "$MODDIR/scripts/mijia.sh" start
nohup sh "$MODDIR/scripts/menu-install.sh" >/dev/null 2>&1 &
exec sh "$MODDIR/scripts/control.sh" start
