#!/system/bin/sh
# 仅查询非唯一标识的基础环境；不采集序列号、账号等信息。
if [ ! -x /system/bin/getprop ]; then
    printf '%s\n' "错误：未找到 Android getprop 命令。" >&2
    exit 1
fi

printf '采集时间：%s\n' "$(date '+%Y-%m-%d %H:%M:%S %z')"
printf '执行 UID：%s\n' "$(id -u)"
printf '手机型号：%s\n' "$(/system/bin/getprop ro.product.model)"
printf 'Android 版本：%s\n' "$(/system/bin/getprop ro.build.version.release)"
printf 'API 等级：%s\n' "$(/system/bin/getprop ro.build.version.sdk)"
printf 'CPU ABI：%s\n' "$(/system/bin/getprop ro.product.cpu.abi)"
printf '内核：%s\n' "$(uname -r)"
printf '开机完成属性：%s\n' "$(/system/bin/getprop sys.boot_completed)"
printf 'KernelSU 版本变量：%s\n' "${KSU_VER:-当前脚本环境未提供}"
