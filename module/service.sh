#!/system/bin/sh
MODDIR=${0%/*}
umask 077

# late_start 阶段只执行一次，不等待开机完成，也不启动常驻循环。
if ! mkdir -p "$MODDIR/runtime"; then
    printf '%s\n' "错误：无法创建模块日志目录。" >&2
    exit 1
fi
sh "$MODDIR/scripts/info.sh" > "$MODDIR/runtime/startup.log" 2>&1
