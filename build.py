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
import tempfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parent
MODULE = ROOT / 'module'
ZIG_VERSION = '0.15.2'
ZIG_SHA256 = '3a0ed1e8799a2f8ce2a6e6290a9ff22e6906f8227865911fb7ddedc3cc14cb0c'
ZIG_URL = 'https://ziglang.org/download/0.15.2/zig-x86_64-windows-0.15.2.zip'
BUILD = ROOT / 'build'
LOG = []
VERSION = '0.4.0'


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


def build_menu():
    import secrets
    import tempfile
    sdk = ROOT / 'tools/android'
    def find(pattern):
        matches = sorted(sdk.glob(pattern))
        if not matches:
            raise RuntimeError(f'缺少菜单编译工具：{pattern}，请运行 bootstrap_android.py')
        return matches[0]
    java, javac, keytool = (find(f'jdk/*/bin/{name}.exe') for name in ['java', 'javac', 'keytool'])
    android_jar, d8 = find('platform/*/android.jar'), find('build-tools/*/lib/d8.jar')
    aapt2, align, signer = find('build-tools/*/aapt2.exe'), find('build-tools/*/zipalign.exe'), find('build-tools/*/lib/apksigner.jar')
    # 签名私钥仅在被 Git 忽略的 tools/private 中保存，不进入源码包或模块包。
    private = ROOT / 'tools/private'
    private.mkdir(exist_ok=True)
    key, password = private / 'sidekey-menu.p12', private / 'sidekey-menu.pass'
    if not key.exists():
        password.write_text(secrets.token_hex(32), encoding='ascii')
        run([keytool, '-genkeypair', '-keystore', key, '-storetype', 'PKCS12', '-alias', 'sidekey',
             '-storepass:file', password, '-keypass:file', password, '-keyalg', 'RSA', '-keysize', '3072',
             '-validity', '10000', '-dname', 'CN=Find X8s Sidekey', '-noprompt'])
    if not password.exists():
        raise RuntimeError('菜单签名口令文件缺失，请恢复 tools/private 中的签名备份')
    with tempfile.TemporaryDirectory(prefix='menu-', dir=BUILD) as temporary:
        temporary = Path(temporary)
        resources, unsigned, aligned = (temporary / name for name in ['resources.zip', 'unsigned.apk', 'aligned.apk'])
        run([aapt2, 'compile', '--dir', ROOT / 'companion/res', '-o', resources])
        run([aapt2, 'link', '-I', android_jar, '--manifest', ROOT / 'companion/AndroidManifest.xml',
             '--min-sdk-version', '35', '--target-sdk-version', '35', '-o', unsigned, resources])
        classes, dex = temporary / 'classes', temporary / 'dex'
        classes.mkdir(); dex.mkdir()
        run([javac, '-J-Dfile.encoding=UTF-8', '-J-Dstdout.encoding=UTF-8', '-J-Dstderr.encoding=UTF-8', '--release', '8', '-Xlint:deprecation,-options', '-Werror', '-encoding', 'UTF-8', '-classpath', android_jar,
             '-d', classes, *sorted((ROOT / 'companion/src').rglob('*.java'))])
        run([java, '-cp', d8, 'com.android.tools.r8.D8', '--release', '--min-api', '34',
             '--lib', android_jar, '--output', dex, *sorted(classes.rglob('*.class'))])
        with zipfile.ZipFile(unsigned, 'a', zipfile.ZIP_DEFLATED) as apk:
            for path in dex.glob('*.dex'): apk.write(path, path.name)
        run([align, '-f', '4', unsigned, aligned])
        output = MODULE / 'lib/sidekey-menu.apk'
        run([java, '-jar', signer, 'sign', '--ks', key, '--ks-key-alias', 'sidekey',
             '--ks-pass', 'file:' + str(password), '--out', output, aligned])
        run([java, '-jar', signer, 'verify', '--verbose', output])
        badging = run([find('build-tools/*/aapt.exe'), 'dump', 'badging', output])
        if "package: name='cn.sidekey.menu'" not in badging or "versionCode='40'" not in badging:
            raise RuntimeError('菜单 APK 包名或版本无效')
        with zipfile.ZipFile(output) as apk:
            if apk.testzip() or not apk.read('classes.dex').startswith(b'dex\n'):
                raise RuntimeError('菜单 APK 完整性检查失败')
        LOG.append('通过：菜单 APK 的 API 35 编译、DEX、包名、版本及 APK 签名校验。')


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
         '-Wl,-z,max-page-size=16384', '-s', 'native/sidekey.c', 'native/torch.c', 'native/haptic.c', 'native/menu.c', 'native/menu_protocol.c', *sources, '-o', MODULE / 'bin/sidekey'])
    validate_elf(MODULE / 'bin/sidekey')
    build_torch()
    build_menu()
    test_binary = BUILD / ('native_tests.exe' if os.name == 'nt' else 'native_tests')
    run([zig, 'cc', '-O1', '-UNDEBUG', *common, 'tests/native_tests.c', 'native/menu_protocol.c', *sources, '-o', test_binary])
    run([test_binary])
    node = shutil.which('node')
    if not node:
        raise RuntimeError('未找到 Node.js，无法执行 WebUI 测试')
    run([node, '--test', 'tests/webui.test.js'])
    fixture = run([node, '--input-type=module', '-e',
        "import {defaultConfig,serialize} from './module/webroot/model.js';"
        "const c=defaultConfig();c.enabled=true;c.haptic=false;c.menu_side='left';c.menu=[{name:'设置',icon:'⚙️',type:'app',argument:'com.android.settings'}];c.actions[1]={type:'menu',argument:''};c.actions[0]={type:'torch',argument:''};c.actions[2]={type:'shell',argument:\"printf '%s' '你好'\\necho test\"};"
        "process.stdout.write(serialize(c));"])
    fixture_path = BUILD / 'config-fixture.conf'
    fixture_path.write_bytes(fixture.encode('utf-8'))
    with tempfile.TemporaryDirectory(prefix='config-', dir=BUILD) as config_directory:
        decoded = json.loads(run([test_binary, fixture_path, config_directory]))
    if decoded['actions'][2]['argument'] != "printf '%s' '你好'\necho test":
        raise RuntimeError('前后端配置往返验证失败')
    if decoded['actions'][0]['type'] != 'torch':
        raise RuntimeError('手电筒动作配置往返验证失败')
    if decoded['haptic'] is not False:
        raise RuntimeError('震动开关配置往返验证失败')
    if decoded['menu'][0]['name'] != '设置' or decoded['menu'][0]['icon'] != '⚙️' or decoded['actions'][1]['type'] != 'menu':
        raise RuntimeError('菜单 UTF-8 配置往返验证失败')
    LOG.append('通过：WebUI → C 配置解析 → JSON，中文、引号、换行保持一致。')
    bash = (r'C:\Program Files\Git\bin\bash.exe' if os.name == 'nt' and Path(r'C:\Program Files\Git\bin\bash.exe').exists() else shutil.which('bash'))
    if not bash or not Path(bash).exists():
        raise RuntimeError('未找到 Bash，无法执行 Shell 语法检查')
    files = sorted(path for path in MODULE.rglob('*') if path.is_file())
    required = {'module.prop', 'skip_mount', 'customize.sh', 'service.sh', 'action.sh',
                'uninstall.sh', 'scripts/control.sh', 'bin/sidekey', 'lib/torch.jar', 'lib/sidekey-menu.apk', 'scripts/menu-install.sh', 'webroot/index.html',
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
    delivery = ROOT / '交付文件' / (now.strftime('%Y%m%d_%H%M%S_%f') + f'_test_v{VERSION}_左侧快捷菜单')
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
        paths += [path for directory in ['native', 'android', 'companion', 'tests', 'module', 'diagnostics'] for path in (ROOT / directory).rglob('*') if path.is_file()]
        for path in sorted(paths):
            archive.write(path, path.relative_to(ROOT).as_posix())
    shutil.copyfile(ROOT / 'README.md', delivery / '使用说明.md')
    shutil.copyfile(ROOT / 'diagnostics/模块本地验证.md', delivery / '验证记录.md')
    (delivery / '构建日志.txt').write_text('\n'.join(LOG), encoding='utf-8')
    (delivery / '交付说明.md').write_text(
        f'# 侧键自定义 v{VERSION} 测试版\n\n'
        f'构建时间：{now.isoformat(timespec="seconds")}\n\n'
        '新增可自定义快捷菜单：默认空白，从屏幕左侧滑出，可调整弹出方向和中心高度。\n\n'
        f'安装：在 KernelSU 覆盖安装 test_oppo_sidekey_v{VERSION}.zip 后重启。升级保留已有动作。'
        '模块会自动安装侧键快捷菜单组件；在 WebUI 将某个手势改为“弹出快捷菜单”，添加捷径并保存。'
        '支持名称、emoji、动作参数及上下排序，最多 12 项。\n\n'
        '菜单组件只接收名称、图标和一次性会话；动作由模块执行。使用本机回环网络通信，'
        '不访问远端，不需要悬浮窗、无障碍或单独 Root 授权。卸载模块时移除菜单组件。\n\n'
        '本次本地验证覆盖手势、旧配置升级、菜单边界和非法请求、UTF-8 往返、WebUI、Shell、'
        'ARM64 静态 ELF、手电筒 DEX、菜单 APK 签名以及 ZIP 完整性。真实命令结果见构建日志。\n\n'
        '**这是功能测试版：本次没有连接手机，ColorOS 后台启动、实际滑出动画和点击动作仍需你刷入实测。** '
        '锁屏时不弹出菜单；不会唤醒或绕过锁屏。菜单会话 60 秒失效，配置变化或服务退出后自动收起。\n\n'
        '恢复：WebUI 关闭接管并保存，或在管理器禁用模块后重启。菜单组件安装异常时，'
        '点击“准备 / 修复菜单组件”并查看运行日志。签名冲突时需先自行卸载旧菜单组件。\n'
, encoding='utf-8')
    sums = '\n'.join(f'{sha256(p.read_bytes()).hexdigest()}  {p.name}' for p in sorted(delivery.iterdir()) if p.is_file())
    (delivery / 'SHA256SUMS.txt').write_text(sums + '\n', encoding='utf-8')
    (BUILD / 'latest-delivery.txt').write_text(str(delivery), encoding='utf-8')
    print(f'\n交付目录：{delivery}\n安装包：{package.name}（{package.stat().st_size} 字节）')


if __name__ == '__main__':
    main()
