#!/system/bin/sh
MODDIR=${0%/*}

printf '%s\n' "侧键自定义：请使用模块的 WebUI 按钮配置动作。"
printf '%s\n' "以下是当前服务及配置状态："
exec sh "$MODDIR/scripts/control.sh" get
