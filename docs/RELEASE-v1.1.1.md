# findx8s sidekey v1.1.1 · 米家读数与自定义文案

**快捷栏现在可以显示温湿度计、冰箱等米家设备的实际读数，并自定义每项数值前的文案。** 本版按设备 MIOT 属性规格通用适配，修复只读属性被过滤的问题。

## 米家读数卡片

- 在 **米家 → 选择设备 → 设备读数** 中勾选属性，每张卡片可合并 1～4 项，也可以添加多张卡片。
- 支持温度、湿度等只读数据，保留不同温区名称，按规格显示单位，支持零值、负数和小数。
- 冰箱显示设备开放的实际测量温度。目标温度、设定温度归入“控制与设定”，不会用来替代当前温度。
- 读数卡片没有开关按钮。在线／离线独立显示，缺失数据明确显示“暂无数据”。
- 展开快捷栏时读取，操作设备后只刷新同设备的可见项目，空闲时不持续请求云端。

卡片获取云端最近上报值，更新速度取决于设备或网关上报。离线设备可能仍有最近上报数据，菜单会保留离线标记。设备未公开相应 MIOT 测量属性时，不用其他属性替代。

## 自定义读数文案

在 **快捷栏 → 对应读数卡片 → 编辑读数文案** 中分别填写“冷藏”“冷冻”“温度”“湿度”等文案，点击 **保存文案**，下次展开快捷栏生效。

- 每项文案独立设置，非空文案后自动加冒号；留空只显示数值和单位。
- 编辑时保留原属性名称，方便核对；数值和单位继续来自原米家属性及规格。
- 已有卡片无需删除重建或重新绑定，同一属性的不同卡片可以使用不同文案。
- 新增卡片时也可直接填写文案并查看显示效果。
- **米家 → 已配置动作 → 管理** 同样提供文案编辑入口。

## 安装与升级

下载 **release_oppo_sidekey_v1.1.1.zip**，在 **KernelSU → 模块 → 从本地安装** 中覆盖安装，然后重启手机。

- 支持从 v1.1.0 正式版及 v1.1.1 测试版覆盖升级，保留手势、快捷栏、米家绑定和自定义文案。
- 目标环境：**OPPO Find X8s · Android 15 / ColorOS 15 · 原版 KernelSU**。
- GitHub 自动生成的 **Source code** 压缩包用于查看源码，不能直接安装到 KernelSU。
- 附件提供模块 ZIP 和对应的 `SHA256SUMS.txt`。

用户已确认 **v1.1.1-test.2 真机测试通过**。正式版沿用该版本功能实现，仅更新正式版本号、交付与发布文档；已重新编译、签名和检查安装包完整性。按项目约定，未运行自动测试或界面测试。

## 致谢

感谢以下作者、项目与贡献者提供的开源实现和参考资料：

- **[Do1e 及 mijia-api 贡献者](https://github.com/Do1e/mijia-api)**：米家扫码登录、云端请求、规格与控制接口，独立米家服务适配自 4.2.1。
- **[Sammy Svensson（Squachen）／micloud](https://github.com/Squachen/micloud)**：上游请求签名与 RC4 实现，保留原 MIT 版权声明。
- **酷安 @道小理／“米家云端控制” v1.0**：米家配置页的房间分组、设备卡片与信息组织参考。
- **[Xiaomi Home 贡献者](https://github.com/XiaoMi/ha_xiaomi_home)**：设备在线状态字段与错误码资料。
- **[MIOT-SPEC](https://miot-spec.org/) 与 [Xiaomi Miot Spec](https://home.miot-spec.com/)**：设备属性、单位和规格信息。
- **[HBYShyw／AntiThermal](https://github.com/HBYShyw/AntiThermal)**：ColorOS 小窗接口与状态字段资料。
- **[KernelSU](https://kernelsu.org/)、[Android](https://developer.android.com/)、[Zig](https://ziglang.org/)、[musl](https://musl.libc.org/) 与 [Eclipse Temurin](https://adoptium.net/)**：运行环境与构建工具。

原创监听器、菜单和 WebUI 使用 MIT 许可；独立米家服务使用 GPL-3.0-or-later，完整对应源码位于模块内 `lib/mijia-source.zip`。完整来源和许可见 [第三方说明](https://github.com/zhujiu39/findx8s-sidekey/blob/v1.1.1/THIRD_PARTY_NOTICES.md)。

使用说明见 [README](https://github.com/zhujiu39/findx8s-sidekey/blob/v1.1.1/README.md)，版本变化见 [更新记录](https://github.com/zhujiu39/findx8s-sidekey/blob/v1.1.1/CHANGELOG.md)。
