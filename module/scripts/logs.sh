#!/system/bin/sh
MODDIR=${0%/scripts/*}
DATA=/data/adb/oppo_sidekey
umask 077
set -o pipefail
case "$1" in ''|full) ;; *) exit 2 ;; esac

printf 'findx8s sidekey 调试日志\n版本：'
sed -n 's/^version=//p' "$MODDIR/module.prop" || exit 1
printf '导出时间：'
date '+%Y-%m-%d %H:%M:%S %z'
if [ "$1" = full ]; then
    printf '完整导出当前及保留的上一段日志，不按单文件 64 KiB 截取。\n'
else
    printf '复制当前及保留的上一段日志；单文件超过 64 KiB 时仅保留末尾完整行。完整内容请使用导出完整日志。\n'
fi
log_mode=$1
printf '米家布尔状态：1=开，0=关，?=未确认。\n'

dump_log() {
    printf '\n──── %s ────\n' "$1"
    log_path="$DATA/$2"
    if [ -L "$DATA" ] || [ -L "$log_path" ] || [ ! -f "$log_path" ] || [ ! -r "$log_path" ]; then
        printf '暂无日志\n'
        return
    fi
    case "$2" in mijia/*) [ ! -L "$DATA/mijia" ] || { printf '日志目录不可用\n'; return; } ;; esac
    log_size=$(wc -c <"$log_path") || { printf '读取日志失败\n'; return 1; }
    if [ "$log_mode" = full ]; then
        # 固定本次打开前的长度，避免日志持续追加使导出无法结束。
        head -c "$log_size" "$log_path" || return 1
    elif [ "$log_size" -gt 65536 ]; then
        printf '[已截取末尾 64 KiB]\n'
        # 丢弃可能从 UTF-8 字符中间开始的首行，保持剪贴板文本完整。
        tail -c 65536 "$log_path" | sed '1d' || return 1
    else
        cat "$log_path" || return 1
    fi
    printf '\n'
}

# 只导出固定的诊断文件，不遍历数据目录、登录凭据或设备缓存。
dump_log '米家执行与状态读回' mijia/debug.log || exit 1
dump_log '米家快捷栏通信' mijia-menu.log || exit 1
dump_log '监听服务' events.log || exit 1
dump_log '手电筒服务' torch-service.log || exit 1
dump_log '菜单组件安装' menu-install.log || exit 1
dump_log '菜单组件启动' menu-launch.log || exit 1
dump_log '应用启动' app-launch.log || exit 1
dump_log '米家执行与状态读回（上一段）' mijia/debug.log.1 || exit 1
dump_log '米家快捷栏通信（上一段）' mijia-menu.log.1 || exit 1
dump_log '监听服务（上一段）' events.log.1 || exit 1
printf '\n===== SIDEKEY_LOG_END =====\n'
