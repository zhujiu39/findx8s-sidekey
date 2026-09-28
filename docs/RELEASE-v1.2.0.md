# 侧键自定义 v1.2.0 · 界面更新与 Surfing 卡片

新版统一了手势、快捷栏、米家和设置的界面，并加入 Surfing 启停与用量卡片。**本次正式版以用户确认无误的 test.15 为基础，沿用相同 UI 与业务实现。**

## 界面与快捷菜单

- WebUI 采用家庭式分组，四页排版、应用选择和设备详情保持一致；浅色使用纯白背景，深色使用深色背景，支持跟随系统与减少动态效果。
- 功能图标使用 SF Symbols；应用名称与身份图标从手机读取。
- 原生快捷菜单采用圆润实心卡片，四周 10 dp 均匀留白，标题左对齐，去掉顶部横条及其大块占位。
- 点击整张卡片执行控制；绿色表示确认开启，蓝色表示切换中，蜜黄色表示未知或异常，普通背景随系统主题切换。
- 温湿度等读数名称与数值同行，保持五字标题和紧凑的信息密度。

## Surfing 代理与用量

- 在快捷栏添加 Surfing 卡片，整卡启停代理。
- 只有确认开启才展示实时速率、核心累计流量和订阅用量；关闭、切换中或状态未知时收起详情。
- 统计名称与数值同行，订阅已用、总额、进度和更新时间紧凑排列。
- 支持逐项勾选显示内容与自定义文案；窄栏默认缩短标签，自定义文字保留。
- 订阅用量取自服务商提供的本机元数据；缺失、缓存或读取失败均有明确提示，不用核心累计流量推算套餐用量。

需预先安装并配置 Surfing 与 SurfingTile。本模块不附带代理核心、订阅或节点。

## 修复与说明

- 修复米家面板的空引用错误，完善设备详情关闭和切换时的状态处理。
- 修复 WebUI 应用列表与快捷栏编辑页的图标读取，优先使用 KernelSU 原生图标，并提供回退和逐应用诊断。
- 配置页标题与开关同行；正常状态避免重复显示在线／离线或开关状态文字。
- README 按当前正式版重新编写，包含完整安装、使用、排障、构建与致谢。

## 安装与升级

下载 **[release_oppo_sidekey_v1.2.0.zip](https://github.com/zhujiu39/findx8s-sidekey/releases/download/v1.2.0/release_oppo_sidekey_v1.2.0.zip)**，在 **KernelSU → 模块 → 从本地安装** 中覆盖安装，然后重启手机。

- 目标环境：OPPO Find X8s、Android 15 / ColorOS 15、原版 KernelSU。
- 可从 v1.1.1 或 v1.2.0 测试版覆盖升级，保留已有配置与 APK 签名，无需先卸载。
- 版本码为 **129**，确保可覆盖升级 test.15 的菜单组件。
- 附件提供模块 ZIP 与对应的 `SHA256SUMS.txt`。GitHub 自动生成的 Source code 压缩包不能直接安装。

用户已确认 test.15 无误并指定发布为 v1.2.0。正式版仅统一版本与发布文档，重新编译、签名并核对交付完整性；没有重新改动已确认的 UI 和业务逻辑。按项目约定未运行自动测试或界面测试。

## 许可证与致谢

原创监听器、菜单和 WebUI 使用 MIT 许可；独立米家服务使用 GPL-3.0-or-later，完整对应源码随模块置于 `lib/mijia-source.zip`。图形资源按各自来源说明使用。

感谢 Do1e／mijia-api、Sammy Svensson／micloud、酷安 @道小理、Xiaomi Home、MIOT-SPEC、GitMetaio／Surfing、MetaCubeX／mihomo、HBYShyw／AntiThermal，以及 Apple、KernelSU、Android、Zig、musl 和 Eclipse Temurin 的实现、图标与参考资料。作者链接、来源和完整致谢见 [README](https://github.com/zhujiu39/findx8s-sidekey/blob/v1.2.0/README.md) 与 [第三方组件说明](https://github.com/zhujiu39/findx8s-sidekey/blob/v1.2.0/THIRD_PARTY_NOTICES.md)。

[使用说明](https://github.com/zhujiu39/findx8s-sidekey/blob/v1.2.0/README.md) · [更新记录](https://github.com/zhujiu39/findx8s-sidekey/blob/v1.2.0/CHANGELOG.md)
