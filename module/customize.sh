#!/system/bin/sh
# 由安装器加载，使用安装器提供的 abort 和权限设置函数。
[ "$KSU" = "true" ] || abort "请通过 KernelSU 管理器安装本模块。"

[ "$ARCH" = "arm64" ] || abort "当前安装包仅支持 ARM64 手机。"
[ "$API" -ge 35 ] || abort "当前版本面向 Android 15 及以上系统。"
ui_print "安装侧键自定义 v0.3.0"
ui_print "新增系统手电筒：请在 WebUI 将长按改为内置手电筒动作。"
ui_print "安装后打开模块 WebUI，配置动作并开启接管。"
# webroot 的权限和 SELinux 上下文由 KernelSU 安装器管理。
set_perm_recursive "$MODPATH/bin" 0 0 0755 0755
set_perm_recursive "$MODPATH/scripts" 0 0 0755 0755
set_perm_recursive "$MODPATH/lib" 0 0 0755 0644
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/action.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755
"$MODPATH/bin/sidekey" init /data/adb/oppo_sidekey || abort "创建配置失败。"
