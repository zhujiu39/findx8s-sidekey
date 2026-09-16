# 第三方组件

监听程序通过 Zig 0.15.2 的 C 编译器构建，静态链接 musl libc，并可能包含 Zig compiler-rt 的运行时实现。

- musl libc：版权及各组件许可的完整说明位于 `module/LICENSES/musl-COPYRIGHT.txt`。
- Zig compiler-rt：MIT 许可，完整文本位于 `module/LICENSES/zig-LICENSE.txt`。
- 编译器下载来源为 Zig 官方下载站，构建脚本固定版本并验证压缩包 SHA256。编译器本身不随模块安装。

WebUI 使用原生 HTML、CSS、JavaScript，不加载 CDN，不引入第三方前端运行时；KernelSU 桥接按官方公开的 JavaScript 接口协议调用。
