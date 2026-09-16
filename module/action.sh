#!/system/bin/sh
MODDIR=${0%/*}

printf '%s\n' "=== 当前环境 ==="
sh "$MODDIR/scripts/info.sh" || exit 1
printf '\n%s\n' "=== 最近一次开机脚本记录 ==="
if [ -f "$MODDIR/runtime/startup.log" ]; then
    cat "$MODDIR/runtime/startup.log"
else
    printf '%s\n' "暂无记录；安装后重启，再检查开机脚本是否执行。"
fi
