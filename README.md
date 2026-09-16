# 侧键自定义 · OPPO Find X8s

版本：**v0.3.0 功能测试版**。适用目标为 OPPO Find X8s（PKT110）、Android 15、ARM64、KernelSU。

模块通过 WebUI 自定义侧边键的短按、双击、长按。启用时以 `EVIOCGRAB` 独占输入设备，替换系统原有的侧键功能；暂停时释放设备，让系统恢复接收。

## 安装与使用

1. 将交付目录中的 **`test_oppo_sidekey_v0.3.0.zip`** 传到手机。
2. 打开 KernelSU 管理器，在模块页面从本地安装该 ZIP，然后重启手机。
3. 在模块卡片中打开 **WebUI**。如果管理器没有显示 WebUI 入口，先检查管理器版本及安装结果。
4. 为短按、双击、长按选择动作，必要时填写包名、Android 按键码或 Shell 命令。
5. 打开“接管侧边键”，点击“保存设置”。状态应变为“侧键已接管”。
6. 在“运行状态与日志”查看识别结果；各手势旁的“测试”用于执行已保存的动作。

首次安装默认**暂停接管**，三个动作均为“不执行动作”。开关和动作设置都在点击保存后应用。配置通常在半秒内生效，WebUI 状态约每 2.5 秒更新一次。

从 v0.2.0 升级：先用旧脚本关灯，在管理器覆盖安装新版并重启。配置会保留；将长按动作从“自定义 Shell 命令”改为 **“切换手电筒（系统最高亮度）”**，再点击“保存设置”。旧 Shell 不会自动替换，其他自定义命令也会保留。

## 系统手电筒

内置动作通过 Android CameraManager 控制系统手电筒，由系统回调确认实际状态。可以从侧键开灯、从控制中心关灯，再用侧键开灯；不通过修改设置项伪造图标，也不再直接写入 LED 硬件节点。

如果相机服务公开多个亮度档位，开灯使用 `FLASH_INFO_STRENGTH_MAXIMUM_LEVEL` 报告的最高档；如果只公开一个档位，则使用系统默认亮度。这里的最高档是 Android 公开能力，不代表绕过温控后的硬件极限。ColorOS 对本机开放的档位和控制中心同步效果需要安装后验证。

WebUI “运行状态与日志”新增系统手电筒状态、实际亮度／最高档位，以及服务错误日志。相机占用、系统拒绝、服务启动失败和状态确认超时都会报告失败，不自动退回旧 sysfs 脚本。

配置了手电筒动作时，监听程序按需启动一个 app_process 服务，保持 CameraService 的客户端存活；否则开灯进程退出时灯会熄灭。服务使用系统回调，没有相机预览或持续图像采集。父进程停止、模块禁用或卸载时，服务被终止，系统释放该客户端持有的灯光。仅暂停侧键接管时仍保留已配置的手电筒服务，以允许使用 WebUI 测试按钮。

安装后建议验证：长按开灯与控制中心图标、再次长按关灯、控制中心手动关灯后再次长按、相机占用时错误提示、锁屏／息屏响应。首次配置后等待几秒再测试，服务需要初始化。

只安装模块 ZIP，不安装 `源码与测试.zip`；不通过 Recovery 刷入。无需重新修补 boot/init_boot，不依赖元模块、Zygisk 或联网服务。

## 可选动作

| 类型 | 支持内容 |
| --- | --- |
| 导航与系统 | 桌面、返回、最近任务、通知栏、快捷设置、截屏、息屏、相机 |
| 媒体 | 播放／暂停、上一首、下一首、音量增减、切换媒体静音 |
| 手电筒 | 系统状态切换、最高公开亮度档位、系统回调与诊断日志 |
| 启动应用 | 输入应用包名；可从 WebUI 读取当前用户的已安装包名 |
| Android 按键 | 指定 Android KeyEvent 编码，例如 3 为主页 |
| 自定义 Shell | 输入自己的命令，以模块 Root 身份运行 |
| 不执行动作 | 启用接管后对应手势不会执行任何动作 |

启动应用示例：`com.android.settings`。Shell 示例：`input keyevent 3`。

自定义参数最多 512 个 UTF-8 字节。单次动作最长 10 秒，超时会结束整个动作进程组；动作返回后也清理该进程组中的后台子进程，因此本功能不用于启动长期常驻脚本。动作队列最多等待 4 项，超出会在日志中记录。

## 手势规则

- **短按**：关闭双击动作时松开即执行；设置双击动作后，等待双击间隔结束再执行。
- **双击**：第一次松开到第二次按下之间不超过设定窗口；第二次松开时执行。若第二次按住达到长按阈值，则只触发长按。
- **长按**：按住达到阈值时执行一次，松开不再触发短按，即使驱动没有重复事件也能识别。
- 长按阈值为 250～2000 ms，默认 600 ms；双击窗口为 150～600 ms，默认 280 ms。
- 修改并保存配置会取消尚未完成的手势和排队动作；已按住的键需要先松开再操作。

## 暂停、恢复与卸载

- 临时恢复原功能：在 WebUI 关闭接管，点击保存。
- 停用模块：在 KernelSU 管理器禁用模块。正常运行中的监听程序会检查禁用标记并退出；如状态不确定，重启手机即可。
- 卸载：在管理器卸载并按提示重启。卸载脚本停止监听，删除模块自身的数据目录。
- 监听进程退出后，内核自动释放它持有的输入设备独占权。

如果页面显示“接管失败”或“未找到输入设备”，先保持关闭接管，将 WebUI 下方日志反馈用于适配。不需要放宽整个系统的 SELinux 策略。

## 设备依据与验证范围

已通过手机 ADB 实测 `gpio-keys`、键码 **735（0x02DF，BTN_TRIGGER_HAPPY32）**，目前设备节点为 `/dev/input/event0`。程序按设备名称和支持的键码重新查找，不固定节点编号；如果同一节点还承载其他按键，则拒绝独占。

735 是 Linux 输入键码，不能直接当作 Android KeyEvent 编码。原始证据见 [侧边按键识别结果](diagnostics/侧边按键识别结果.md)。

**本版本通过本地编译和自动测试，新增系统手电筒尚未在手机验证。** 用户已反馈旧 sysfs 脚本可以开关灯，但亮度较低且图标不同步；这不能作为新增 CameraManager 服务已通过实测的依据。ColorOS 的 Root app_process 权限、相机元数据、控制中心、锁屏行为和服务退出清理都需要安装后确认。本地记录见 [模块本地验证](diagnostics/模块本地验证.md)。

## 工程与构建

```text
native/                原生监听、手势状态机、配置解析
android/               系统手电筒服务与可独立测试的状态逻辑
module/bin/sidekey     构建生成的 ARM64 静态程序
module/lib/torch.jar   构建生成的 Android DEX，不是安装 APK
module/webroot/        离线 WebUI
module/scripts/       配置与服务入口
module/LICENSES/      静态运行时许可
tests/                C、JavaScript 测试
diagnostics/          设备实测证据与本地验证记录
build.py              编译、测试、打包与 SHA256 输出
bootstrap_android.py  下载并校验构建用 JDK、Android 平台和 D8
```

依赖：Python 3、Node.js、Git Bash、Zig 0.15.2、JDK 21、Android API 35 平台和 D8。Windows 首次准备编译工具：

```powershell
python bootstrap_android.py
python build.py --bootstrap
```

已有编译器时运行 `python build.py`；也可通过环境变量 `ZIG` 指定可执行文件。编译目标为 `aarch64-linux-musl` 静态 ELF，按 16 KB 页面对齐，无需手机安装额外运行时。

每次构建在 `交付文件/时间_test_v0.3.0_系统手电筒与状态同步/` 新建目录，包含安装包、源码与测试、使用说明、验证记录、构建日志及 SHA256。DEX 编译以 API 35 为接口库、API 33 为最低字节码目标；模块安装目标仍为 Android 15 及以上。

模块 ID：`oppo_sidekey`。持久配置与日志：`/data/adb/oppo_sidekey/`，目录权限 0700，升级保留，卸载删除。配置使用严格解析的文本格式，参数按 UTF-8 十六进制存储，原子替换写入；没有直接加载配置为 Shell 脚本。

此项目不修改系统 keylayout，不修改内核或系统分区，不上传设备信息。第三方组件说明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

官方接口依据：[KernelSU 模块](https://kernelsu.org/guide/module.html)、[WebUI](https://kernelsu.org/guide/module-webui.html)。

手电筒接口依据：[Android CameraManager](https://developer.android.com/reference/android/hardware/camera2/CameraManager)、[相机亮度能力](https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics#FLASH_INFO_STRENGTH_MAXIMUM_LEVEL)。
