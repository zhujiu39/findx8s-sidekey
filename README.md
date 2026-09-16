# KernelSU 模块开发骨架

当前版本：v0.1.0，功能测试骨架。目标模块的具体功能尚待确认。

用户提供的目标环境：OPPO Find X8s、Android 15、原版 KernelSU，管理器版本显示为 `32601-2`，通过修补 `init_boot` 安装。上述信息尚未通过设备命令验证。

这是 Android 上的 KernelSU 用户空间模块工程，使用 Shell 脚本；不涉及 MCU、BSP 或内核编译。

## 示例行为

- 安装：检查 KernelSU 安装环境并设置脚本权限。
- 开机：`service.sh` 在 late_start 阶段查询一次基础环境，保存到模块自己的 `runtime/startup.log`。
- 操作按钮：`action.sh` 显示当前环境和最近一次开机记录。
- 日志每次开机覆盖，不包含序列号或账号，不上传信息。
- 无常驻进程，无系统属性修改，无 system 文件挂载。

`service.sh` 执行时 Android 可能尚未完全启动，所以日志中的开机完成属性可以为 `0` 或空。

## 源码结构

```text
module/
├── module.prop       模块名称、ID、版本等元数据
├── customize.sh      安装入口，由安装器加载
├── service.sh        开机晚期执行一次
├── action.sh         管理器的操作按钮入口
├── skip_mount        声明不挂载 system
└── scripts/
    └── info.sh       查询 Android 基础环境
build.py              校验并生成可安装 ZIP、源码快照和校验值
```

所有模块文本文件使用 UTF-8 无 BOM 编码、LF 换行。模块脚本使用 `MODDIR=${0%/*}` 定位自身目录。

## 构建和安装

在 Windows PowerShell 中执行：

```powershell
python build.py
```

产物位于 `交付文件/时间_test_KernelSU开发骨架/`，每次构建新建目录。

1. 将 `test_ksu_dev_starter.zip` 传到手机。
2. 在 KernelSU 管理器的模块页面选择从本地安装 ZIP。
3. 查看安装结果，成功后重启手机。
4. 点击本模块的“操作”按钮，检查当前环境和启动日志。
5. 要移除示例，在管理器中卸载并按提示重启。

ZIP 根目录必须直接包含 `module.prop`，不能在外面再套一层 `module/` 文件夹。不要通过 Recovery 安装此 ZIP。

## 后续接入功能

- 开机执行的短任务：从 `service.sh` 调用独立脚本。
- 必须等 Android 完成开机的任务：确认 KernelSU 版本支持后使用 `boot-completed.sh`。
- 手动执行：从 `action.sh` 调用独立脚本。
- 配置界面：确认功能需求后增加 `webroot/` WebUI。
- 系统文件覆盖：确认安装版本及挂载实现后再增加 `system/`；当前官方版本要求提供挂载能力的元模块。

涉及设备节点、按键事件、厂商设置或后台控制时，必须先确认目标设备和预期行为，再实现业务逻辑。

## 验证边界

`build.py` 校验模块文件、编码、换行、元数据和 ZIP 完整性，不模拟 Android，也不代表实机功能验证通过。
目前尚未在手机上验证安装、开机执行、操作按钮和卸载。是否支持某个 KernelSU 分支或旧版本，需根据实际版本确认。

## 官方资料

- [模块开发指南](https://kernelsu.org/guide/module.html)
- [模块 WebUI](https://kernelsu.org/guide/module-webui.html)
- [元模块说明](https://kernelsu.org/guide/metamodule.html)
