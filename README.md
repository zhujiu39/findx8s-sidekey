<div align="center">

# 侧键自定义 · Find X8s

**一颗侧键，三种手势，常用操作随手可达。**

为 OPPO Find X8s 开发的 KernelSU 模块。<br>
用短按、双击和长按打开应用、呼出快捷栏、控制米家设备，或执行自己的命令。

[![Version](https://img.shields.io/badge/version-v1.2.0-0DCD94)](https://github.com/zhujiu39/findx8s-sidekey/releases/tag/v1.2.0)
[![Device](https://img.shields.io/badge/device-Find%20X8s-555555)](#compatibility)
[![Android](https://img.shields.io/badge/Android-15-3DDC84?logo=android&logoColor=white)](#compatibility)
[![KernelSU](https://img.shields.io/badge/Root-KernelSU-1565C0)](https://kernelsu.org/)
[![License](https://img.shields.io/badge/license-MIT%20%2B%20GPL--3.0-blue)](THIRD_PARTY_NOTICES.md)

[下载模块](https://github.com/zhujiu39/findx8s-sidekey/releases/latest) · [安装使用](#quick-start) · [快捷栏](#quick-menu) · [米家](#mijia) · [Surfing](#surfing) · [常见问题](#faq)

</div>

> **v1.2.0 正式版**：[下载 release_oppo_sidekey_v1.2.0.zip](https://github.com/zhujiu39/findx8s-sidekey/releases/download/v1.2.0/release_oppo_sidekey_v1.2.0.zip)，在 KernelSU 中覆盖安装并重启。新版界面、应用图标修复和 Surfing 卡片已包含在本次正式版中。[查看更新说明](docs/RELEASE-v1.2.0.md)。

## 能做什么

| 功能 | 使用方式 |
| --- | --- |
| 三种独立手势 | 为短按、双击、长按分别设置动作，调整双击间隔与长按时间 |
| 侧边快捷栏 | 常用应用、系统操作、米家设备与读数、代理状态集中在一个可滚动面板里 |
| ColorOS 小窗 | 快捷栏中的应用以系统小窗打开；直接绑定手势时也可选择普通启动 |
| 米家设备与场景 | 扫码登录，按家庭和房间查找设备，将设备控制或手动场景加入快捷栏 |
| 米家读数 | 展示温湿度、冰箱温区等云端上报值，支持选择读数与自定义文案 |
| Surfing 代理 | 整卡启停，开启后查看速率、核心累计流量及服务商上报的订阅用量 |
| 系统手电筒 | 与系统共享开关状态，使用系统公开的最高亮度档位 |
| 外观与触感 | SF Symbols 图标、浅色／深色／跟随系统、减少动态效果，以及可关闭的触发震动 |
| 本机设置与排障 | WebUI 配置保存在手机，可查看状态、复制图标诊断和导出完整日志 |

WebUI 分为 **手势、快捷栏、米家、设置** 四页。普通配置与侧键动作可离线使用；米家登录、设备查询和控制需要联网，Surfing 依赖手机上已安装并配置好的服务。

<a id="compatibility"></a>

## 适用环境

| 项目 | 当前适配范围 |
| --- | --- |
| 手机 | OPPO Find X8s |
| 系统 | Android 15 / ColorOS 15，ARM64 |
| Root | 原版 KernelSU，能够正常打开模块 WebUI |

其他机型、ROM 或 Root 方案尚未纳入适配范围。模块不依赖 Zygisk；快捷菜单无需悬浮窗或无障碍权限。手机需要处于解锁状态才能显示菜单，模块不负责唤醒或解锁。

<a id="quick-start"></a>

## 安装与开始使用

1. 从 [Releases](https://github.com/zhujiu39/findx8s-sidekey/releases) 获取模块安装附件。正式包为 `release_oppo_sidekey_v<版本>.zip`，测试包为 `test_oppo_sidekey_v<版本>.zip`。GitHub 自动生成的 **Source code** 压缩包不能直接安装。
2. 在 **KernelSU → 模块 → 从本地安装** 中选择 ZIP，安装完成后重启手机。
3. 打开模块 WebUI，在 **手势** 页点按短按、双击或长按卡片，选择动作并完成编辑。
4. 开启 **启用侧键自定义**，点击底部 **保存设置**。

首次安装默认不接管侧键，三种手势均为“不执行动作”。手势和快捷栏的修改需要保存才会生效。关闭“启用侧键自定义”并保存，可恢复系统对侧键的处理。

**升级**：在 KernelSU 中覆盖安装并重启，保留已有配置，无需先卸载。卸载模块会删除模块配置并移除“侧键快捷菜单”组件。

<a id="quick-menu"></a>

## 配置快捷栏

1. 将任意手势设为 **弹出快捷菜单**。
2. 在 **快捷栏 → 添加项目** 中选择 **应用、快捷操作、米家或 Surfing**。
3. 选择应用后点击 **使用所选应用**；其他项目可展开编辑名称、动作或显示内容，用上下箭头调整顺序。
4. 在 **菜单布局** 中调整方向、宽度、应用间距和位置，保存后按侧键打开。

操作与读数卡片位于上方，应用按两列排列，超出面板的内容可上下滚动。点击应用后菜单收起；点击控制卡片后菜单保持展开。点击面板外或使用系统返回可收起菜单。全部项目留空时不弹出面板。

### 卡片与状态

控制卡片采用**整卡点击**，标题左对齐，由卡片底色表达状态：

| 外观 | 含义 |
| --- | --- |
| 绿色 `#0DCD94` | 已读回的开启状态 |
| 蓝色 `#66CCFF` | 正在切换或等待确认 |
| 蜜黄色 `#F4C66A` | 状态未知、离线或读取失败，结合提示图标判断 |
| 浅色白底／深色灰底 | 关闭状态、普通操作或只读卡片 |

面板保留四周均匀的留白。只读卡片用于显示数据，不提供开关。状态以实际读回结果为准，设备控制仍遵循离线和操作中的禁用规则。

| 布局设置 | 默认值 | 范围 |
| --- | --- | --- |
| 面板宽度 | 196 dp | 120～360 dp |
| 应用间距 | 12 dp | 0～32 dp |
| 面板中心位置 | 35% | 10%～90% |
| 弹出方向 | 左侧 | 左侧／右侧 |

快捷栏应用统一使用 **ColorOS 小窗**。系统或应用不支持时报告失败，不自动切换为全屏。WebUI 的“预览”用于查看布局，不代表手机上的实际开关状态，也不会执行设备或代理操作。

<a id="mijia"></a>

## 米家设备、场景与读数

### 连接与控制

在 **米家** 页点击右上角账号按钮，生成登录二维码，用米家 App 扫一扫授权。同一部手机上可先截图，再从扫一扫的相册中识别。

登录后选择家庭，按房间、设备类别或名称查找设备。点开设备，选择其规格支持的开关、亮度、模式等控制，或选择手动场景；点击 **加入快捷菜单** 并保存设置，即可从侧键菜单操作。已存绑定可在 **我的项目** 中管理，执行结果在 **最近执行** 中查看。

“已受理”表示云端或网关接收了请求；读回结果吻合后才显示“已确认”。操作后刷新相关设备状态，控制指令不会因超时自动重发。设备能力与状态依赖米家云端和设备自身支持。

### 添加只读卡片

打开设备详情，展开 **配置读数卡片**，勾选 1～4 项读数，点击 **添加读数卡片** 并保存。支持添加多张卡片；属性名称和单位来自设备 MIOT 规格。

温湿度等读数采用 **名称与数值同行** 的紧凑排版，例如 `温度 24.5°C`、`湿度 73%`。实际测量值与目标／设定值分开，设备未开放测量属性时不会用设定值代替。

新增卡片时可填写自定义读数文案；已有卡片可从 **快捷栏 → 对应卡片 → 编辑读数文案**，或 **米家 → 我的项目 → 管理** 修改。文案留空时只显示数值与单位，保存文案后下次展开菜单生效。

读数是云端最近上报值。过期或状态未确认时会显示“最近上报 · 待确认”；未返回数据时显示“暂无数据”。展开菜单时读取，空闲时不持续轮询云端。

目前支持中国大陆账号与 MIOT 设备；部分旧设备可通过米家手动场景接入。**退出并清除登录**会清除本机凭据和米家动作，相关绑定需重新配置。更多实现与许可信息见 [米家服务说明](mijia/README.md)。

<a id="surfing"></a>

## Surfing 代理卡片

先在手机上安装并配置 Surfing 与 SurfingTile，再从 **快捷栏 → 添加项目 → Surfing** 添加卡片并保存。本模块提供控制与状态入口，不附带代理核心、订阅或节点。

点击整张卡片启停代理。**只有确认开启后才显示详细信息**；关闭、切换中或状态未知时收起统计区。窄栏默认使用 `↑速率`、`↓速率`、`↑流量`、`↓流量` 等短标签，名称与数值同行；订阅的已用、总额、进度与更新时间紧凑排列。

在卡片的 **显示项目** 中，可分别选择上传速率、下载速率、上传流量、下载流量和订阅用量。取消的项目不占位，全部取消时仅保留启停卡片。统计标签、说明、订阅标题及用量文案均可自定义，留空则隐藏相应文案。

| 数据 | 来源与含义 |
| --- | --- |
| 上传／下载速率 | mihomo 本机 API 的实时统计，菜单展开时约每秒更新 |
| 上传／下载流量 | 核心本次运行或最近重置后的累计值，核心重启或重置后可能清零 |
| 订阅已用／总额 | 服务商通过订阅元数据提供的用量，随订阅更新；多个订阅分别展示 |

核心累计流量与套餐用量是不同数据，不能用来相互替代。订阅未提供用量或总额时会标明缺失，读取失败会提示排障；不推算额度，不把缺失值填成 0，也不将其视为无限流量。

适配依据为 Surfing 7.8.4 / SurfingTile 6.0.0 的控制服务。订阅信息仅从本机元数据读取，不强制下载订阅；缓存会标注“上次读取”。菜单关闭后停止本轮统计刷新。API 密钥由 Root 侧读取，菜单只接收展示所需的状态与统计数据。

<a id="actions"></a>

## 动作、手感与外观

| 类别 | 可选动作 |
| --- | --- |
| 系统 | 桌面、返回、最近任务、通知栏、快捷设置、截屏、息屏 |
| 媒体 | 播放／暂停、上一首、下一首、音量增减、媒体静音 |
| 相机与灯光 | 打开相机、切换手电筒 |
| 应用 | 普通启动或 ColorOS 小窗启动 |
| 米家 | 已配置的设备动作与手动场景 |
| 自定义 | Android 按键码、Shell 命令 |
| 菜单与留空 | 弹出快捷菜单、不执行动作 |

Surfing 卡片在快捷栏中配置。菜单内不再嵌套菜单；应用放入快捷栏后统一使用小窗。

在 **设置 → 按键节奏** 中调节长按时间与双击间隔：

| 设置 | 默认值 | 范围 |
| --- | --- | --- |
| 长按触发时间 | 600 ms | 250～2000 ms |
| 双击最大间隔 | 280 ms | 150～600 ms |
| 触感反馈 | 开启 | 开启／关闭，位于手势页 |

启用双击动作后，短按会等待双击间隔结束；长按达到阈值执行一次。手电筒使用系统公开的最高亮度，受相机占用、温控与 ROM 限制。

**设置 → 外观**提供浅色、深色与跟随系统，立即生效并在本机记住。WebUI 浅色使用纯白页面背景，深色使用深色背景；侧键弹出的原生菜单跟随 Android 系统外观。可开启“减少动态效果”。

Shell 以 Root 身份执行，单次最长 10 秒，结束时清理后台子进程，不适合常驻脚本。命令上限为 512 个 UTF-8 字节；Android 按键码范围为 `1～2047`。

<a id="faq"></a>

## 常见问题

<details>
<summary><strong>安装后侧键没有变化？</strong></summary>

先配置动作，开启“启用侧键自定义”并保存。确认安装后已重启；若监听服务未启动，在“设置 → 运行状态与日志”中点击“启动监听服务”。

</details>

<details>
<summary><strong>快捷栏打不开，或升级后仍显示旧界面？</strong></summary>

确认手机已解锁、手势设为“弹出快捷菜单”、菜单中有项目，并已保存。升级后重启手机；仍异常时进入“设置 → 运行状态与日志 → 修复快捷菜单”，再导出日志排查。无需另开悬浮窗或无障碍权限。

</details>

<details>
<summary><strong>应用列表里只有占位图标？</strong></summary>

先在应用选择页点击“刷新”。若固定几个应用仍无图标，点击选择页的“复制图标诊断”按钮，反馈应用名称、模块版本与诊断内容。WebUI 图标读取和原生快捷菜单的图标读取路径不同，请说明问题出现在哪个页面。

</details>

<details>
<summary><strong>应用不能以小窗打开？</strong></summary>

小窗依赖 ColorOS 与应用自身支持。模块会报告失败，不自动改为全屏。反馈时提供应用名称、系统版本，以及运行日志中的应用启动错误。

</details>

<details>
<summary><strong>代理已开启，却没有速率或订阅用量？</strong></summary>

先确认对应显示项已勾选并保存，且 SurfingTile 的本机 API 配置可用。速率与订阅是独立的数据来源；只有服务商提供了订阅用量元数据，卡片才能显示套餐数据。保留具体错误提示并导出完整日志。

</details>

<details>
<summary><strong>怎样恢复原来的侧键功能？</strong></summary>

关闭“启用侧键自定义”并保存，或在 KernelSU 中禁用模块后重启。卸载会删除模块配置；临时停用无需卸载。

</details>

## 反馈与日志

在 **设置 → 运行状态与日志 → 导出完整日志** 中选择保存位置，将生成的 `.log` 文件与问题说明提交到 [Issues](https://github.com/zhujiu39/findx8s-sidekey/issues)。请附手机型号、系统与模块版本、复现步骤，界面问题可附截图。

文件导出包含现存日志及上一段轮换记录，不包含登录凭据、二维码、设备缓存、订阅或节点配置。“复制全部日志”适合快速查看，大日志可能被截取；完整排查请使用文件导出。公开反馈前仍请检查并移除个人信息。

<a id="build"></a>

## 从源码构建

当前构建脚本面向 Windows x64，需要 Python 3、Node.js 和 Git。首次构建：

```powershell
git clone https://github.com/zhujiu39/findx8s-sidekey.git
cd findx8s-sidekey
python bootstrap_android.py
python build.py --bootstrap
```

后续运行 `python build.py`。产物位于新建的 `交付文件/` 子目录，包含模块 ZIP、对应源码、说明、构建日志和 SHA256。默认执行语法、编译、资源、签名与包完整性检查，不运行自动测试；实际使用在目标手机验证。

自行构建会生成自己的菜单 APK 签名，应保留 `tools/private/` 供后续升级使用，不要公开提交。交叉安装的签名冲突处理、工具链和项目结构见 [构建与维护](docs/BUILD.md)。

[更新记录](CHANGELOG.md) · [当前版本说明](docs/RELEASE-v1.2.0.md) · [设计说明](docs/HOME-UI-DESIGN.md) · [验证记录](diagnostics/模块本地验证.md)

## 许可证与致谢

监听器、菜单和 WebUI 等原创代码采用 [MIT License](LICENSE)；独立米家服务采用 **GPL-3.0-or-later**，对应源码随模块提供。SF Symbols 等图形资源的权利归各自权利人，不适用业务代码的 MIT 许可。完整来源与许可见 [第三方组件说明](THIRD_PARTY_NOTICES.md)。

感谢以下作者、项目和贡献者：

| 作者／项目 | 使用或参考的内容 |
| --- | --- |
| [Do1e 及 mijia-api 贡献者](https://github.com/Do1e/mijia-api) | 米家扫码登录、云端请求、设备规格与控制；独立服务适配自 4.2.1 |
| [Sammy Svensson（Squachen）／micloud](https://github.com/Squachen/micloud) | 请求签名与 RC4 加密实现，保留 MIT 版权声明 |
| 酷安 **@道小理**／“米家云端控制” v1.0 | 米家房间分组、设备卡片与信息组织参考 |
| [Xiaomi Home](https://github.com/XiaoMi/ha_xiaomi_home) | 设备在线状态与错误码核对资料 |
| [MIOT-SPEC](https://miot-spec.org/) 与 [Xiaomi Miot Spec](https://home.miot-spec.com/) | 设备属性、单位及规格信息 |
| [GitMetaio／Surfing](https://github.com/GitMetaio/Surfing) 与 [MetaCubeX／mihomo](https://github.com/MetaCubeX/mihomo) | 代理控制服务与流量、订阅统计接口 |
| [HBYShyw／AntiThermal](https://github.com/HBYShyw/AntiThermal) | ColorOS 小窗接口与状态字段核对资料 |
| [Apple Human Interface Guidelines](https://developer.apple.com/cn/design/human-interface-guidelines/getting-started) 与 [SF Symbols](https://developer.apple.com/sf-symbols/) | 界面信息层级、排版参考与图标资源 |

也感谢 [KernelSU](https://kernelsu.org/)、[Android](https://developer.android.com/)、[Zig](https://ziglang.org/)、[musl](https://musl.libc.org/) 和 [Eclipse Temurin](https://adoptium.net/) 提供运行与构建基础。

由 [@zhujiu39](https://github.com/zhujiu39) 创建并维护。
