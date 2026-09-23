#!/system/bin/sh
# 由安装器加载，使用安装器提供的 abort 和权限设置函数。
[ "$KSU" = "true" ] || abort "请通过 KernelSU 管理器安装本模块。"

[ "$ARCH" = "arm64" ] || abort "当前安装包仅支持 ARM64 手机。"
[ "$API" -ge 35 ] || abort "当前版本面向 Android 15 及以上系统。"
ui_print "侧键自定义 v1.1.1"
ui_print "安装后请重启手机，再打开模块 WebUI 设置。"
ui_print "升级会保留已有配置。"
# webroot 的权限和 SELinux 上下文由 KernelSU 安装器管理。
set_perm_recursive "$MODPATH/bin" 0 0 0755 0755
set_perm_recursive "$MODPATH/scripts" 0 0 0755 0755
set_perm_recursive "$MODPATH/lib" 0 0 0755 0644
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/action.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755
"$MODPATH/bin/sidekey" init /data/adb/oppo_sidekey || abort "创建配置失败。"
