#!/system/bin/sh
MODDIR=${0%/*}
exec sh "$MODDIR/scripts/control.sh" start
