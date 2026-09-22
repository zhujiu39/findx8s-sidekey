#!/system/bin/sh
MODDIR=${0%/*}
sh "$MODDIR/scripts/mijia.sh" stop || {
    echo "停止米家服务失败，请重启后再卸载。" >&2
    exit 1
}
"$MODDIR/bin/sidekey" stop /data/adb/oppo_sidekey || {
    echo "停止监听失败，请重启后再卸载。" >&2
    exit 1
}
rm -rf /data/adb/oppo_sidekey

# 只卸载本模块拥有的菜单包，失败时可在系统应用管理手动移除。
if command -v timeout >/dev/null 2>&1; then
    timeout 15 pm uninstall cn.sidekey.menu >/dev/null 2>&1
fi
