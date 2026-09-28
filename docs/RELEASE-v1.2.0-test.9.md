# v1.2.0-test.9 · KernelSU 原生图标与逐项诊断

2026-09-28，版本码 122。本地实测包。

## 本次修改

用户反馈：test.8 只有固定几个应用能显示图标，刷新后没有变化。当前尚无手机回包证明某一种故障，因此这版更换优先读取路径，同时增加逐项诊断。

1. 支持的 KernelSU 管理器优先通过 `ksu://icon/{packageName}` 提供图片。该 URI 被管理器在本机拦截，不是互联网图标服务。能力判断遵循官方文档中的 `listPackages` 接口，网页 CSP 同步允许 `ksu://icon`。
2. 直接将 URI 设为 img 的来源，不使用 fetch、Canvas 或跨域像素读取；不依赖较新管理器增加的 CORS 响应头。
3. URI 没有 Android 用户参数，所以当前仅在用户 0 使用；其他用户继续通过模块接口读取并校验用户，避免混用用户数据。
4. 管理器接口不存在、某张图标加载失败或 8 秒未完成时，使用原来的 Root PNG 路径。Root 每批两张；批量响应失败时对两项各单独重试一次，不无限重试、不因一项失败永久丢掉整批。
5. 保留首屏主动请求、搜索、滚动、已选应用及摘要加载，成功图片直接显示。刷新／关闭／用户变化会取消旧图片事件与等待，避免旧结果覆盖新列表。
6. 应用列表数量旁增加 SF Symbols 复制按钮，名称为“复制图标诊断”。记录界面版本、接口检测结果、当前用户、图标加载状态及每个包名的来源／失败阶段。只驻留本次 WebUI 内存，最多 256 个应用、每项 8 步，不记录 PNG 数据或账号凭据；用户点击后才复制。自动复制受限时提供可手动复制的文本。

紧凑开关布局、Surfing 仅开启时显示详情、深浅色、米家功能、原生菜单代码与资源均保持 test.8 的行为。

## 安装与实测

KernelSU 覆盖安装 `test_oppo_sidekey_v1.2.0-test.9.zip` 后重启，关闭旧 WebUI 并重新打开，确认设置页版本 test.9。保留原配置、登录和 APK 签名。

- 打开快捷栏的应用选择器，检查之前固定缺失的图标；再搜索对应应用并向下滚动。
- 检查勾选后的已选应用、首页摘要，以及刷新和关闭重开后的图标。
- 如果还有占位符，先让该应用出现在列表可见区域，等待读取结束，再点击应用数量旁的复制图标诊断按钮，将文本提供给开发者。不要先刷新，刷新会重置本次诊断记录。
- 核对同行开关、Surfing 详情收起和深浅色行为未受影响。

## 编译与验证边界

运行 `python build.py`，执行 JavaScript 语法检查、71 项 SF Symbols 资源处理、ARM64／Java／API 35 编译、DEX、APK 签名及 ZIP 内容检查；AppCatalog 已过时 API 提示沿用，编译详情见本次日志。交付时比对原生菜单与 test.8 的 DEX、资源和签名。

按项目约定不运行自动测试、浏览器测试、联网测试或 ADB。接口依据已核对，不等于手机图标已实测成功；真实兼容情况仍需用户验收。未推送或发布。

## 接口依据

- [KernelSU JS 文档：listPackages 与应用图标 URI](https://github.com/tiann/KernelSU/blob/main/js/README.md#listpackages)
- [KernelSU WebViewHelper：本机图片请求处理](https://github.com/tiann/KernelSU/blob/main/manager/app/src/main/java/me/weishu/kernelsu/ui/webui/WebViewHelper.kt)
