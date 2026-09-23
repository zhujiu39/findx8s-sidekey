#!/system/bin/sh
MODDIR=${0%/scripts/*}
DATA=/data/adb/oppo_sidekey
umask 077

printf 'findx8s sidekey 调试日志\n版本：'
sed -n 's/^version=//p' "$MODDIR/module.prop"
printf '导出时间：'
date '+%Y-%m-%d %H:%M:%S %z'
printf '包含当前及保留的上一段日志；单文件超过 64 KiB 时仅保留末尾完整行。\n'
printf '米家布尔状态：1=开，0=关，?=未确认。\n'

dump_log() {
    printf '\n──── %s ────\n' "$1"
    log_path="$DATA/$2"
    if [ -L "$DATA" ] || [ -L "$log_path" ] || [ ! -f "$log_path" ] || [ ! -r "$log_path" ]; then
        printf '暂无日志\n'
        return
    fi
    case "$2" in mijia/*) [ ! -L "$DATA/mijia" ] || { printf '日志目录不可用\n'; return; } ;; esac
    log_size=$(wc -c <"$log_path") || { printf '读取日志失败\n'; return; }
    if [ "$log_size" -gt 65536 ]; then
        printf '[已截取末尾 64 KiB]\n'
        # 丢弃可能从 UTF-8 字符中间开始的首行，保持剪贴板文本完整。
        tail -c 65536 "$log_path" | sed '1d'
    else
        cat "$log_path"
    fi
    printf '\n'
}

# 只导出固定的诊断文件，不遍历数据目录、登录凭据或设备缓存。
dump_log '监听服务（上一段）' events.log.1
dump_log '监听服务' events.log
dump_log '手电筒服务' torch-service.log
dump_log '菜单组件安装' menu-install.log
dump_log '菜单组件启动' menu-launch.log
dump_log '应用启动' app-launch.log
dump_log '米家快捷栏通信（上一段）' mijia-menu.log.1
dump_log '米家快捷栏通信' mijia-menu.log
dump_log '米家执行与状态读回（上一段）' mijia/debug.log.1
dump_log '米家执行与状态读回' mijia/debug.log
