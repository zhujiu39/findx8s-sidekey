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
VERSION = '0.3.1'


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


def build_torch():
    sdk = ROOT / 'tools/android'
    def find(pattern):
        matches = sorted(sdk.glob(pattern))
        if not matches:
            raise RuntimeError('缺少 Android 编译工具，请先运行 python bootstrap_android.py')
        return matches[0]
    java = find('jdk/*/bin/java.exe')
    javac = find('jdk/*/bin/javac.exe')
    android_jar = find('platform/*/android.jar')
    d8 = find('build-tools/*/lib/d8.jar')
    classes = BUILD / 'torch-classes'
    classes.mkdir(exist_ok=True)
    # 每次使用新的临时目录，避免已删除的 Java 类混入当前 DEX。
    import tempfile
    with tempfile.TemporaryDirectory(prefix='classes-', dir=classes) as temporary:
        sources = sorted((ROOT / 'android').rglob('*.java'))
        run([javac, '-J-Dfile.encoding=UTF-8', '-J-Dstdout.encoding=UTF-8', '-J-Dstderr.encoding=UTF-8',
             '--release', '8', '-Xlint:-options', '-encoding', 'UTF-8', '-classpath', android_jar,
             '-d', temporary, *sources, ROOT / 'tests/TorchControllerTest.java'])
        java_options = ['-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8']
        run([java, *java_options, '-cp', temporary, 'TorchControllerTest'])
        output = MODULE / 'lib/torch.jar'
        output.parent.mkdir(exist_ok=True)
        run([java, *java_options, '-cp', d8, 'com.android.tools.r8.D8', '--release', '--min-api', '33',
             '--lib', android_jar, '--output', output,
             *sorted((Path(temporary) / 'cn').rglob('*.class'))])
    with zipfile.ZipFile(output) as jar:
        data = jar.read('classes.dex')
        if not data.startswith(b'dex\n') or jar.testzip() is not None:
            raise RuntimeError('手电筒 DEX 校验失败')
        LOG.append(f'通过：Android API 35 编译、纯 Java 状态测试、D8 DEX 校验；DEX {len(data)} 字节。')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--bootstrap', action='store_true')
    args = parser.parse_args()
    BUILD.mkdir(exist_ok=True)
    (MODULE / 'bin').mkdir(exist_ok=True)
    shutil.copyfile(ROOT / 'LICENSE', MODULE / 'LICENSES/sidekey-LICENSE.txt')
    zig = find_zig(args.bootstrap)
    run([zig, 'version'])
    sources = ['native/gesture.c', 'native/config.c']
    common = ['-Wall', '-Wextra', '-Werror', '-std=c11', '-I', 'native']
    run([zig, 'cc', '-target', 'aarch64-linux-musl', '-static', '-O2', *common,
         '-Wl,-z,max-page-size=16384', '-s', 'native/sidekey.c', 'native/torch.c', 'native/haptic.c', *sources, '-o', MODULE / 'bin/sidekey'])
    validate_elf(MODULE / 'bin/sidekey')
    build_torch()
    test_binary = BUILD / ('native_tests.exe' if os.name == 'nt' else 'native_tests')
    run([zig, 'cc', '-O1', '-UNDEBUG', *common, 'tests/native_tests.c', *sources, '-o', test_binary])
    run([test_binary])
    node = shutil.which('node')
    if not node:
        raise RuntimeError('未找到 Node.js，无法执行 WebUI 测试')
    run([node, '--test', 'tests/webui.test.js'])
    fixture = run([node, '--input-type=module', '-e',
        "import {defaultConfig,serialize} from './module/webroot/model.js';"
        "const c=defaultConfig();c.enabled=true;c.haptic=false;c.actions[0]={type:'torch',argument:''};c.actions[2]={type:'shell',argument:\"printf '%s' '你好'\\necho test\"};"
        "process.stdout.write(serialize(c));"])
    fixture_path = BUILD / 'config-fixture.conf'
    fixture_path.write_bytes(fixture.encode('utf-8'))
    decoded = json.loads(run([test_binary, fixture_path]))
    if decoded['actions'][2]['argument'] != "printf '%s' '你好'\necho test":
        raise RuntimeError('前后端配置往返验证失败')
    if decoded['actions'][0]['type'] != 'torch':
        raise RuntimeError('手电筒动作配置往返验证失败')
    if decoded['haptic'] is not False:
        raise RuntimeError('震动开关配置往返验证失败')
    LOG.append('通过：WebUI → C 配置解析 → JSON，中文、引号、换行保持一致。')
    bash = shutil.which('bash') or (r'C:\Program Files\Git\bin\bash.exe' if os.name == 'nt' else None)
    if not bash or not Path(bash).exists():
        raise RuntimeError('未找到 Bash，无法执行 Shell 语法检查')
    files = sorted(path for path in MODULE.rglob('*') if path.is_file())
    required = {'module.prop', 'skip_mount', 'customize.sh', 'service.sh', 'action.sh',
                'uninstall.sh', 'scripts/control.sh', 'bin/sidekey', 'lib/torch.jar', 'webroot/index.html',
                'LICENSES/sidekey-LICENSE.txt'}
    if not required.issubset({path.relative_to(MODULE).as_posix() for path in files}):
        raise RuntimeError('模块文件不完整')
    for path in files:
        if path.parts[-2] not in ('bin', 'lib'):
            data = path.read_bytes()
            data.decode('utf-8')
            if b'\r' in data or data.startswith(b'\xef\xbb\xbf'):
                raise RuntimeError(f'{path.name} 必须为 UTF-8 无 BOM、LF 换行')
        if path.suffix == '.sh':
            run([bash, '-n', path])
        if path.suffix == '.js':
            run([node, '--check', path])

    now = datetime.now().astimezone()
    delivery = ROOT / '交付文件' / (now.strftime('%Y%m%d_%H%M%S_%f') + f'_test_v{VERSION}_开源发布')
    delivery.mkdir(parents=True, exist_ok=False)
    package = delivery / f'test_oppo_sidekey_v{VERSION}.zip'
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
        paths = [ROOT / name for name in ['README.md', 'LICENSE', 'build.py', 'bootstrap_android.py', 'package.json', '.gitignore', '.gitattributes', 'THIRD_PARTY_NOTICES.md']]
        paths += [path for directory in ['native', 'android', 'tests', 'module', 'diagnostics'] for path in (ROOT / directory).rglob('*') if path.is_file()]
        for path in sorted(paths):
            archive.write(path, path.relative_to(ROOT).as_posix())
    shutil.copyfile(ROOT / 'README.md', delivery / '使用说明.md')
    shutil.copyfile(ROOT / 'diagnostics/模块本地验证.md', delivery / '验证记录.md')
    (delivery / '构建日志.txt').write_text('\n'.join(LOG), encoding='utf-8')
    (delivery / '交付说明.md').write_text(
        f'# 侧键自定义 v{VERSION} 测试版\n\n'
        f'构建时间：{now.isoformat(timespec="seconds")}\n\n'
        '本次交付用于 GitHub 开源发布：新增 MIT 许可证、仓库和安装包下载说明，'
        '安装包与源码包均包含项目许可证；运行功能保持 v0.3.1。\n\n'
        f'安装文件：`test_oppo_sidekey_v{VERSION}.zip`，在 KernelSU 管理器中覆盖安装并重启。'
        '升级保留配置；进入 WebUI 将长按从旧 Shell 改为“切换手电筒（系统最高亮度）”，保存设置。'
        '新增“触发时震动”默认开启；可在 WebUI 的手感调节中关闭并保存。'
        '升级前先用旧长按动作关灯，避免遗留直接写入硬件的状态。\n\n'
        '本次新增：有效手势触发时请求一次 35 ms 系统震动，默认开启、WebUI 可关闭，'
        '长按只在达到阈值时触发一次。反馈使用独立子进程，不阻塞输入或动作，失败只记录日志。'
        '震动遵循系统震动和勿扰设置，表示手势已接收，不代表后续动作必定成功。\n\n'
        '保留功能：CameraManager 系统手电筒、最高公开亮度档位、系统状态回调同步、'
        '按需启动的 Java 服务、父进程退出联动清理、手电筒状态和诊断日志。'
        '不伪造控制中心状态，不修改系统文件或 SELinux 模式。\n\n'
        '目标：OPPO Find X8s（PKT110）、Android 15、KernelSU；已实测侧键为 gpio-keys / 735。'
        '无需刷入新的 boot 或 init_boot，无需元模块或 Zygisk。\n\n'
        '构建：Zig C 编译为 ARM64 静态 ELF，16 KB 段对齐；JDK 编译与 Android D8 生成手电筒 DEX。'
        '命令与真实结果见构建日志。'
        '包内包含 musl 运行时，许可证保存在模块 LICENSES 目录。\n\n'
        '验证：本机 Java 状态机、C 手势、配置解析、前后端往返、JavaScript、Shell 语法、ELF、DEX 和 ZIP 检查通过；'
        '界面检查见验证记录。\n\n'
        '**实机边界：用户选择自行安装。系统手电筒服务和本次震动反馈尚未在该机验证；'
        'ColorOS 的 app_process 权限、亮度能力、控制中心图标和锁屏行为仍需实测。** '
        '如果系统只开放 1 档，就使用系统默认亮度，不绕过系统温控限制。\n\n'
        '恢复：WebUI 关闭接管并保存，或在管理器禁用模块后重启。卸载会停止监听并删除自身配置。'
        '没有系统文件覆盖、外部资源或烧录地址。\n', encoding='utf-8')
    sums = '\n'.join(f'{sha256(p.read_bytes()).hexdigest()}  {p.name}' for p in sorted(delivery.iterdir()) if p.is_file())
    (delivery / 'SHA256SUMS.txt').write_text(sums + '\n', encoding='utf-8')
    (BUILD / 'latest-delivery.txt').write_text(str(delivery), encoding='utf-8')
    print(f'\n交付目录：{delivery}\n安装包：{package.name}（{package.stat().st_size} 字节）')


if __name__ == '__main__':
    main()
