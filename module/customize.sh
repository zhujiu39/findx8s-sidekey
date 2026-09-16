#!/system/bin/sh
# 由安装器加载，使用安装器提供的 abort 和权限设置函数。
[ "$KSU" = "true" ] || abort "请通过 KernelSU 管理器安装本模块。"

ui_print "安装 KernelSU 开发示例 v0.1.0"
ui_print "当前版本只记录环境信息，具体功能尚未接入。"
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/action.sh" 0 0 0755
set_perm "$MODPATH/scripts/info.sh" 0 0 0755
