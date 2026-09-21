#!/system/bin/sh
MODDIR=${0%/scripts/*}
DATA=/data/adb/oppo_sidekey
APK="$MODDIR/lib/sidekey-menu.apk"
umask 077
mkdir -p "$DATA" || exit 1
exec >"$DATA/menu-install.log" 2>&1
# 等待包管理器就绪，最长三分钟；不会拖住原有侧键服务启动。
count=0
while [ "$(getprop sys.boot_completed)" != "1" ]; do
    [ -d "$MODDIR" ] && [ ! -f "$MODDIR/disable" ] && [ ! -f "$MODDIR/remove" ] || exit 1
    count=$((count + 1))
    [ "$count" -le 90 ] || { echo "等待系统启动超时，请在 WebUI 重试准备菜单组件。"; exit 1; }
    sleep 2
done
[ ! -f "$MODDIR/disable" ] && [ ! -f "$MODDIR/remove" ] || exit 1
[ -f "$APK" ] || { echo "菜单安装包不存在。"; exit 1; }
command -v timeout >/dev/null 2>&1 || { echo "系统缺少 timeout 命令。"; exit 1; }
user_id=$(am get-current-user) || exit 1
case "$user_id" in ''|*[!0-9]*) echo "无法确定当前 Android 用户。"; exit 1 ;; esac
fingerprint=$(sha256sum "$APK") || exit 1
fingerprint=${fingerprint%% *}
previous=$(cat "$DATA/menu-installed-$user_id" 2>/dev/null)
if [ "$previous" = "$fingerprint" ] && pm path --user "$user_id" cn.sidekey.menu >/dev/null 2>&1; then
    echo "菜单组件 v0.4.1 已就绪。"
    exit 0
fi
# APK 通过标准包管理器安装，不修改系统分区，不申请悬浮窗或无障碍权限。
size=$(wc -c < "$APK") || exit 1
if timeout 45 pm install -r --user "$user_id" -S "$size" < "$APK"; then
    if [ ! -d "$MODDIR" ] || [ -f "$MODDIR/remove" ]; then
        timeout 15 pm uninstall cn.sidekey.menu
        exit 1
    fi
    printf '%s\n' "$fingerprint" > "$DATA/menu-installed-$user_id"
    echo "菜单组件 v0.4.1 已安装。"
else
    echo "菜单安装失败；若提示签名冲突，请自行卸载旧的侧键快捷菜单组件后重试。"
    exit 1
fi
