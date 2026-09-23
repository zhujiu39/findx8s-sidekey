# 构建与维护

## 环境与构建

构建脚本面向 Windows x64。需要 Git（含 Git Bash）、Python 3 和 Node.js，并确保 `git`、`python`、`node` 可从命令行运行。

```powershell
git clone https://github.com/zhujiu39/findx8s-sidekey.git
cd findx8s-sidekey

# 下载并校验 JDK 21、Android API 35 和 Build Tools 35
python bootstrap_android.py

# 首次构建，按需下载 Zig 0.15.2
python build.py --bootstrap

# 后续构建
python build.py
```

工具保存在忽略提交的 `tools/` 中。已有 Zig 时可用 `ZIG` 环境变量指定路径。Linux / macOS 构建流程尚未适配。

完整构建会编译 ARM64 监听程序、Java 服务及菜单 APK，运行 C、Java、JavaScript、配置与脚本检查，随后校验 ELF、DEX、APK 和 ZIP。每次新建独立的 `交付文件/时间_test_版本_改动内容/`，内含安装包、源码与测试、使用说明、验证记录、构建日志及 SHA256。

将交付目录中的 `test_oppo_sidekey_v<版本>.zip` 安装到 KernelSU。`源码与测试.zip` 用于审查与复现；模块内的 `lib/mijia-source.zip` 提供米家服务对应源码，无需单独安装。

## 签名

首次构建会在被 Git 忽略的 `tools/private/` 生成菜单 APK 的签名私钥及口令文件，后续构建复用。应自行备份，不能提交或随发布附件公开。

自行构建的 APK 签名与仓库发布版本不同。交叉安装提示签名冲突时，先在手机应用管理中卸载“侧键快捷菜单”，再从 WebUI → 运行状态与日志 → 修复快捷菜单重新安装组件。手势与菜单配置存放在模块数据目录，单独卸载组件不影响配置。

## 测试与预览

```powershell
# WebUI 单元测试
node --test tests/*.test.js

# 本地界面预览
python -m http.server 8765 --bind 127.0.0.1 --directory module/webroot
```

浏览器访问 `http://127.0.0.1:8765`。普通浏览器无法读取手机应用或执行动作。完整测试范围见 [验证说明](../diagnostics/模块本地验证.md)。

## 运行边界

- 侧键来自 `gpio-keys`，Linux 输入键码 735。动态查找输入设备，不固定 event 编号；若设备还承载其他按键，则拒绝独占。适配依据见 [侧边按键识别结果](../diagnostics/侧边按键识别结果.md)。
- 输入接管使用 `EVIOCGRAB`；进程退出后由内核释放独占权。
- 普通动作最长运行 10 秒，队列最多等待 4 项；Shell 动作结束后清理其进程组中的后台进程。
- 手电筒通过 CameraManager 和系统回调维护真实状态，使用系统公开的最高亮度。服务通过仅允许 UID 0 的本机 Unix 套接字接收请求。
- ColorOS 小窗使用手机自身的 `OplusZoomWindowManager.startZoomWindow`。请求后核对包名、用户、可见状态和窗口区域；不支持时报告失败。接口依据见 [OPlus 定义](https://github.com/HBYShyw/AntiThermal/blob/main/oplus-framework/sources/com/oplus/zoomwindow/OplusZoomWindowManager.java)及[状态结构](https://github.com/HBYShyw/AntiThermal/blob/main/oplus-framework/sources/com/oplus/zoomwindow/OplusZoomWindowInfo.java)。不分发厂商框架。
- 快捷菜单为透明 Activity，不使用悬浮窗。每次会话最长 60 秒；锁屏时不显示，不负责唤醒或解锁。
- 菜单通信使用本机回环地址、随机端口及一次性令牌。界面提交选择索引，Root 命令和 Shell 参数保留在守护进程中。
- 配置与日志位于 `/data/adb/oppo_sidekey/`，权限 `0700`。配置严格校验后原子替换，升级保留、卸载删除。
- 配置容量为最多 2048 个应用、2060 个菜单项目；大配置分块上传。应用图标按需加载，列表不使用固定占位。
- WebUI 离线运行，不加载 CDN，不上传应用列表或设备信息。

## 项目结构

```text
native/                 监听服务、配置、手势与动作调度
android/cn/sidekey/      手电筒、应用目录与 ColorOS 小窗服务
companion/              快捷菜单 Android 组件
mijia/                  米家独立服务及许可说明
module/webroot/         WebUI
module/scripts/         配置、服务与安装控制脚本
module/LICENSES/        随模块分发的许可证
tests/                  自动化测试
diagnostics/            适配依据与验证记录
docs/                   构建与维护文档
bootstrap_android.py    工具链准备
build.py                编译、检查与打包
```

`build/` 为可重建的中间产物，`交付文件/` 保存历史交付。清理中间产物不应删除 `tools/private/` 的签名文件。

## 发布

正式版本需统一更新模块、菜单 APK、WebUI、安装脚本、`package.json` 和构建版本。保留原 APK 签名，增加 versionCode 后覆盖升级。

运行完整构建后审核提交范围与安装包内容，创建对应 Git 标签。GitHub Release 上传模块 ZIP 和匹配的 `SHA256SUMS.txt`，关闭预发布标记，并从公开下载链接再次核对 SHA256。

米家服务可以单独使用 `python build_mijia.py` 构建；可选 `--live` 验证匿名扫码握手和二维码读取，不登录或保存账号凭据。详见 [米家服务说明](../mijia/README.md)。构建不会执行 Git push、创建 Tag 或发布 Release。
