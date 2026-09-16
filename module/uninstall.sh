#!/system/bin/sh
MODDIR=${0%/*}
"$MODDIR/bin/sidekey" stop /data/adb/oppo_sidekey || {
    echo "停止监听失败，请重启后再卸载。" >&2
    exit 1
}
rm -rf /data/adb/oppo_sidekey
