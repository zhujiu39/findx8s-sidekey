"""构建、验证并打包 ARM64 KernelSU 侧键模块。"""
import argparse
from datetime import datetime
from hashlib import sha256
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parent
MODULE = ROOT / 'module'
ZIG_VERSION = '0.15.2'
ZIG_SHA256 = '3a0ed1e8799a2f8ce2a6e6290a9ff22e6906f8227865911fb7ddedc3cc14cb0c'
ZIG_URL = 'https://ziglang.org/download/0.15.2/zig-x86_64-windows-0.15.2.zip'
BUILD = ROOT / 'build'
LOG = []


def run(arguments):
    arguments = [str(x) for x in arguments]
    result = subprocess.run(arguments, cwd=ROOT, capture_output=True,
                            encoding='utf-8', errors='replace', timeout=600)
    rendered = subprocess.list2cmdline(arguments)
    LOG.append(f'$ {rendered}\n{result.stdout}{result.stderr}')
    print(result.stdout + result.stderr, end='')
    if result.returncode:
        raise RuntimeError(f'退出码 {result.returncode}: {rendered}')
    return result.stdout


def find_zig(bootstrap):
    bundled = ROOT / 'tools' / f'zig-x86_64-windows-{ZIG_VERSION}' / 'zig.exe'
    executable = os.environ.get('ZIG') or (str(bundled) if bundled.exists() else shutil.which('zig'))
    if executable:
        return executable
    if not bootstrap or sys.platform != 'win32':
        raise RuntimeError('设置 ZIG 环境变量，或在 Windows 上执行 python build.py --bootstrap 下载编译器。')
    bundled.parent.parent.mkdir(exist_ok=True)
    archive = bundled.parent.parent / 'zig.zip'
    urllib.request.urlretrieve(ZIG_URL, archive)
    if sha256(archive.read_bytes()).hexdigest() != ZIG_SHA256:
        raise RuntimeError('Zig SHA256 校验失败')
    with zipfile.ZipFile(archive) as package:
        package.extractall(bundled.parent.parent)
    return str(bundled)


def validate_elf(path):
    data = path.read_bytes()
    if data[:6] != b'\x7fELF\x02\x01' or struct.unpack_from('<H', data, 18)[0] != 183:
        raise RuntimeError('监听程序不是小端 ARM64 ELF64')
    offset = struct.unpack_from('<Q', data, 32)[0]
    size, count = struct.unpack_from('<HH', data, 54)
    for index in range(count):
        entry = offset + index * size
        kind = struct.unpack_from('<I', data, entry)[0]
        if kind in (2, 3):
            raise RuntimeError('监听程序包含动态链接依赖或解释器')
        if kind == 1 and struct.unpack_from('<Q', data, entry + 48)[0] < 16384:
            raise RuntimeError('ELF 段对齐小于 16 KB')
    LOG.append(f'通过：ARM64 静态 ELF、无动态解释器、16 KB 段对齐；{len(data)} 字节。')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--bootstrap', action='store_true')
    args = parser.parse_args()
    BUILD.mkdir(exist_ok=True)
    (MODULE / 'bin').mkdir(exist_ok=True)
    zig = find_zig(args.bootstrap)
    run([zig, 'version'])
    sources = ['native/gesture.c', 'native/config.c']
    common = ['-Wall', '-Wextra', '-Werror', '-std=c11', '-I', 'native']
    run([zig, 'cc', '-target', 'aarch64-linux-musl', '-static', '-O2', *common,
         '-Wl,-z,max-page-size=16384', '-s', 'native/sidekey.c', *sources, '-o', MODULE / 'bin/sidekey'])
    validate_elf(MODULE / 'bin/sidekey')
    test_binary = BUILD / ('native_tests.exe' if os.name == 'nt' else 'native_tests')
    run([zig, 'cc', '-O1', '-UNDEBUG', *common, 'tests/native_tests.c', *sources, '-o', test_binary])
    run([test_binary])
    node = shutil.which('node')
    if not node:
        raise RuntimeError('未找到 Node.js，无法执行 WebUI 测试')
    run([node, '--test', 'tests/webui.test.js'])
    fixture = run([node, '--input-type=module', '-e',
        "import {defaultConfig,serialize} from './module/webroot/model.js';"
        "const c=defaultConfig();c.enabled=true;c.actions[2]={type:'shell',argument:\"printf '%s' '你好'\\necho test\"};"
        "process.stdout.write(serialize(c));"])
    fixture_path = BUILD / 'config-fixture.conf'
    fixture_path.write_bytes(fixture.encode('utf-8'))
    decoded = json.loads(run([test_binary, fixture_path]))
    if decoded['actions'][2]['argument'] != "printf '%s' '你好'\necho test":
        raise RuntimeError('前后端配置往返验证失败')
    LOG.append('通过：WebUI → C 配置解析 → JSON，中文、引号、换行保持一致。')
    bash = shutil.which('bash') or (r'C:\Program Files\Git\bin\bash.exe' if os.name == 'nt' else None)
    if not bash or not Path(bash).exists():
        raise RuntimeError('未找到 Bash，无法执行 Shell 语法检查')
    files = sorted(path for path in MODULE.rglob('*') if path.is_file())
    required = {'module.prop', 'skip_mount', 'customize.sh', 'service.sh', 'action.sh',
                'uninstall.sh', 'scripts/control.sh', 'bin/sidekey', 'webroot/index.html'}
    if not required.issubset({path.relative_to(MODULE).as_posix() for path in files}):
        raise RuntimeError('模块文件不完整')
    for path in files:
        if path.parts[-2] != 'bin':
            data = path.read_bytes()
            data.decode('utf-8')
            if b'\r' in data or data.startswith(b'\xef\xbb\xbf'):
                raise RuntimeError(f'{path.name} 必须为 UTF-8 无 BOM、LF 换行')
        if path.suffix == '.sh':
            run([bash, '-n', path])
        if path.suffix == '.js':
            run([node, '--check', path])

    now = datetime.now().astimezone()
    delivery = ROOT / '交付文件' / (now.strftime('%Y%m%d_%H%M%S_%f') + '_test_v0.2.0_侧键自定义WebUI')
    delivery.mkdir(parents=True, exist_ok=False)
    package = delivery / 'test_oppo_sidekey_v0.2.0.zip'
    with zipfile.ZipFile(package, 'w', zipfile.ZIP_DEFLATED) as archive:
        for path in files:
            name = path.relative_to(MODULE).as_posix()
            entry = zipfile.ZipInfo(name, now.timetuple()[:6])
            entry.create_system = 3
            executable = path.suffix == '.sh' or name.startswith('bin/')
            entry.external_attr = (0o100755 if executable else 0o100644) << 16
            entry.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(entry, path.read_bytes())
    with zipfile.ZipFile(package) as archive:
        if archive.testzip() is not None or 'module.prop' not in archive.namelist():
            raise RuntimeError('模块 ZIP 校验失败')
        for path in files:
            if archive.read(path.relative_to(MODULE).as_posix()) != path.read_bytes():
                raise RuntimeError('模块 ZIP 与源码不一致')
    LOG.append('通过：ZIP 根目录、完整性、权限标志及文件内容校验。')
    with zipfile.ZipFile(delivery / '源码与测试.zip', 'w', zipfile.ZIP_DEFLATED) as archive:
        paths = [ROOT / name for name in ['README.md', 'build.py', 'package.json', '.gitignore', '.gitattributes', 'THIRD_PARTY_NOTICES.md']]
        paths += [path for directory in ['native', 'tests', 'module', 'diagnostics'] for path in (ROOT / directory).rglob('*') if path.is_file()]
        for path in sorted(paths):
            archive.write(path, path.relative_to(ROOT).as_posix())
    shutil.copyfile(ROOT / 'README.md', delivery / '使用说明.md')
    shutil.copyfile(ROOT / 'diagnostics/模块本地验证.md', delivery / '验证记录.md')
    (delivery / '构建日志.txt').write_text('\n'.join(LOG), encoding='utf-8')
    (delivery / '交付说明.md').write_text(
        '# 侧键自定义 v0.2.0 测试版\n\n'
        f'构建时间：{now.isoformat(timespec="seconds")}\n\n'
        '安装文件：`test_oppo_sidekey_v0.2.0.zip`，在 KernelSU 管理器的模块页面选择安装。'
        '首次安装后重启，打开 WebUI 配置动作，开启接管并保存。\n\n'
        '本次实现：ARM64 原生独占监听、短按／双击／长按、WebUI、持久配置、'
        '动作超时、停止与恢复原功能、状态日志。默认暂停接管，三个动作均为不执行。\n\n'
        '目标：OPPO Find X8s（PKT110）、Android 15、KernelSU；已实测侧键为 gpio-keys / 735。'
        '无需刷入新的 boot 或 init_boot，无需元模块或 Zygisk。\n\n'
        '构建：Zig C 编译为 ARM64 静态 ELF，16 KB 段对齐；命令与真实结果见构建日志。'
        '包内包含 musl 运行时，许可证保存在模块 LICENSES 目录。\n\n'
        '验证：本机 C 手势、配置解析、前后端往返、JavaScript、Shell 语法、ELF 和 ZIP 检查通过；'
        '界面检查见验证记录。\n\n'
        '**实机边界：用户选择自行安装。本版本尚未在手机上验证独占接管、KernelSU WebUI 桥接、'
        '系统动作、重启自启、禁用及卸载。** 锁屏动作、截图和启动应用受系统策略限制。\n\n'
        '恢复：WebUI 关闭接管并保存，或在管理器禁用模块后重启。卸载会停止监听并删除自身配置。'
        '没有系统文件覆盖、外部资源或烧录地址。\n', encoding='utf-8')
    sums = '\n'.join(f'{sha256(p.read_bytes()).hexdigest()}  {p.name}' for p in sorted(delivery.iterdir()) if p.is_file())
    (delivery / 'SHA256SUMS.txt').write_text(sums + '\n', encoding='utf-8')
    (BUILD / 'latest-delivery.txt').write_text(str(delivery), encoding='utf-8')
    print(f'\n交付目录：{delivery}\n安装包：{package.name}（{package.stat().st_size} 字节）')


if __name__ == '__main__':
    main()
