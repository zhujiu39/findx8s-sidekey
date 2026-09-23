# findx8s sidekey v1.1.0 · 米家适配完成

**v1.1.0 正式版已完成米家适配。** 在 WebUI 登录米家、选择设备或手动场景后，即可从侧键快捷菜单控制家中的设备。

## 米家适配

- 扫码登录米家，选择家庭，按房间查看和配置设备。
- 支持 MIOT 设备开关、属性设置、设备动作及手动场景，可加入快捷菜单或绑定侧键手势。
- 设备名称下显示在线／离线状态；在线时可操作，离线时禁用控制，不继续显示旧的开启状态。
- 打开快捷栏时读取一次可见设备；操作后只刷新当前设备，同设备绑定同步更新，其他设备保持原显示，空闲时不持续请求云端。
- 开关以绿色表示开启、灰色表示关闭。操作后核对目标状态，必要时有限次复查；控制指令不会自动重发。
- 点击快捷开关后菜单保持展开；点击应用后收起菜单并以 ColorOS 小窗打开。

手动场景可能影响多个设备，执行后刷新可见设备一轮。支持中国大陆账号和 MIOT 设备；在线标志与状态上报速度取决于设备和米家云端。无可读开关属性的动作以执行按钮显示。

## 其他更新

- WebUI 内置“墨白极简”和“石墨工具箱”两套样式，支持系统深浅色并记住选择。
- 新增复制全部日志与完整日志导出，通过系统另存为选择保存位置和文件名。
- 完善米家控制超时、状态滞后和在线状态的诊断信息，清理失效样式与开发文案。

## 安装与升级

下载 **release_oppo_sidekey_v1.1.0.zip**，在 **KernelSU → 模块 → 从本地安装** 中选择安装包，完成后重启手机。

- v1.0.0 和 v1.1.0 测试版均可覆盖升级，已有手势、快捷菜单与米家配置保留。
- 首次安装后打开 WebUI 配置动作，开启“接管侧边键”并保存；米家功能需先扫码登录。
- 适用环境：**OPPO Find X8s · Android 15 / ColorOS 15 · 原版 KernelSU**。
- GitHub 自动生成的 **Source code** 压缩包用于查看源码，不能直接安装到 KernelSU。

本次执行正式构建、APK 签名和安装包完整性检查；按项目约定，未运行自动测试或界面测试，由用户实机测试。附件提供模块 ZIP 和对应的 `SHA256SUMS.txt`。

## 致谢

感谢以下作者、项目与贡献者提供的开源实现和参考资料：

- **[Do1e 及 mijia-api 贡献者](https://github.com/Do1e/mijia-api)**：米家扫码登录、云端请求、规格与控制接口，独立米家服务适配自 4.2.1。
- **[Sammy Svensson（Squachen）／micloud](https://github.com/Squachen/micloud)**：上游请求签名与 RC4 实现，保留原 MIT 版权声明。
- **酷安 @道小理／“米家云端控制” v1.0**：米家配置页的房间分组、设备卡片和信息组织参考；设备操作保持在本项目快捷菜单中。
- **[Xiaomi Home 贡献者](https://github.com/XiaoMi/ha_xiaomi_home)**：设备在线状态字段与错误码的核对资料。
- **[HBYShyw／AntiThermal](https://github.com/HBYShyw/AntiThermal)**：ColorOS 小窗接口与状态字段资料。
- **[KernelSU](https://kernelsu.org/)、[Android](https://developer.android.com/)、[Zig](https://ziglang.org/)、[musl](https://musl.libc.org/) 与 [Eclipse Temurin](https://adoptium.net/)**：运行环境与构建工具。

原创监听器、菜单和 WebUI 使用 MIT 许可；独立米家服务使用 GPL-3.0-or-later，完整对应源码位于模块内 `lib/mijia-source.zip`。完整来源和许可见 [第三方说明](https://github.com/zhujiu39/findx8s-sidekey/blob/v1.1.0/THIRD_PARTY_NOTICES.md)。

使用说明见 [README](https://github.com/zhujiu39/findx8s-sidekey/blob/v1.1.0/README.md)，版本变化见 [更新记录](https://github.com/zhujiu39/findx8s-sidekey/blob/v1.1.0/CHANGELOG.md)。
