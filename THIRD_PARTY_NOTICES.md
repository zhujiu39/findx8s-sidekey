# 第三方组件

## 米家适配

米家独立服务 `module/lib/mijia.jar`：GPL-3.0-or-later，适配自 Do1e/mijia-api 4.2.1（提交 `353363f79ea6f368461dc77c6de22d6f85623990`）。源码、修改范围与复现方法见 `mijia/README.md`，完整对应源码同时附在模块的 `lib/mijia-source.zip`。许可文本为 `module/LICENSES/mijia-GPL-3.0.txt`。其 RC4 签名适配保留上游 micloud/Sammy Svensson MIT 版权声明，见 `module/LICENSES/micloud-MIT.txt`。本服务独立于 MIT 监听器、菜单和 WebUI 运行，以本地 JSON IPC 交互。

感谢 [Do1e 及其贡献者](https://github.com/Do1e/mijia-api) 与 [Sammy Svensson（Squachen）](https://github.com/Squachen/micloud) 的开源工作。

米家配置页的房间分组、设备卡片与信息组织参考“米家云端控制” v1.0；其 `module.prop` 标注作者为 **酷安 @道小理**。感谢作者提供的界面参考。本项目继续通过自己的快捷菜单执行设备操作，不包含参考模块的安装包或按键接管实现。

[Xiaomi Home 项目](https://github.com/XiaoMi/ha_xiaomi_home)用于核对设备查询接口中的 `isOnline` 字段和设备操作超时错误码；本项目未分发该项目的实现代码。

设备属性及其单位来自 [Xiaomi Miot Spec](https://home.miot-spec.com/) 与 [MIOT-SPEC](https://miot-spec.org/) 的公开规格。规格按手机所选设备获取并缓存在本机，不将用户设备清单或缓存打包发布。

## 构建与运行组件

主机测试使用 Apache-2.0 的 Android JSON 实现 `com.vaadin.external.google:android-json:0.0.20131108.vaadin1`，只在忽略提交的 `tools/` 中使用，不随 Android 产物分发。固定下载来源及 SHA256 见 `build_mijia.py`。

监听程序通过 Zig 0.15.2 的 C 编译器构建，静态链接 musl libc，并可能包含 Zig compiler-rt 的运行时实现。

- musl libc：版权及各组件许可的完整说明位于 `module/LICENSES/musl-COPYRIGHT.txt`。
- Zig compiler-rt：MIT 许可，完整文本位于 `module/LICENSES/zig-LICENSE.txt`。
- 编译器下载来源为 Zig 官方下载站，构建脚本固定版本并验证压缩包 SHA256。编译器本身不随模块安装。

WebUI 使用原生 HTML、CSS、JavaScript，不加载 CDN，不引入第三方前端运行时；KernelSU 桥接按官方公开的 JavaScript 接口协议调用。

v0.3.0 手电筒 DEX 为本项目 Java 源码编译产物，调用手机自带 Android 框架，不打包 SDK 桩实现或 Java 运行时。

- 构建用 Eclipse Temurin JDK 21 从 Adoptium 官方发布仓库下载并核对 SHA256，不随模块分发。
- Android API 35 平台和 Build Tools 35 从 Google 官方 SDK 仓库下载，并核对其仓库元数据公布的 SHA1；只用于 javac 接口校验和 D8 转换，不随模块分发。
- 工具链接和校验值固定记录于 `bootstrap_android.py`，工具保存在忽略提交的 `tools/android/`。

OPlus 小窗适配代码由本项目实现，通过运行时反射调用手机自带框架；不包含厂商框架 JAR 或反编译实现。感谢 [HBYShyw／AntiThermal](https://github.com/HBYShyw/AntiThermal) 提供接口和字段资料，具体来源见 [构建与维护说明](docs/BUILD.md)的“运行边界”。
