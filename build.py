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
VERSION = '1.2.0-test.13'
VERSION_CODE = 126
PACKAGE_PREFIX = 'test' if '-' in VERSION else 'release'
EDITION = '本地测试包' if PACKAGE_PREFIX == 'test' else '正式版'


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


def build_ui():
    assets = MODULE / 'webroot/assets'
    manifest = json.loads((assets / 'sources.json').read_text(encoding='utf-8'))
    for entry in manifest:
        path = assets / (entry['symbol_key'] + '.svg')
        if sha256(path.read_bytes()).hexdigest() != entry['asset_sha256']:
            raise RuntimeError(f'SF Symbols 资源与清单不一致：{path.name}')
    run([sys.executable, 'docs/tools/sync_apple_symbols.py'])
    node = shutil.which('node')
    if not node:
        raise RuntimeError('未找到 Node.js，无法编译检查 WebUI JavaScript 语法')
    for path in sorted((MODULE / 'webroot').rglob('*.js')):
        run([node, '--check', path])
    LOG.append(f'通过：{len(manifest)} 项 SF Symbols 来源清单、Android 矢量转换及 WebUI JavaScript 语法检查；未执行页面或测试。')


def build_torch(tests=False):
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
        if tests:
            sources += [ROOT / 'tests' / name for name in ['TorchControllerTest.java', 'AppCatalogModelTest.java', 'ZoomControllerTest.java', 'LogTransferTest.java']]
        run([javac, '-J-Dfile.encoding=UTF-8', '-J-Dstdout.encoding=UTF-8', '-J-Dstderr.encoding=UTF-8',
             '--release', '8', '-Xlint:-options', '-encoding', 'UTF-8', '-classpath', android_jar,
             '-d', temporary, *sources])
        java_options = ['-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8']
        if tests:
            for name in ['TorchControllerTest', 'AppCatalogModelTest', 'ZoomControllerTest', 'LogTransferTest']:
                run([java, *java_options, '-cp', temporary, name])
        output = MODULE / 'lib/torch.jar'
        output.parent.mkdir(exist_ok=True)
        run([java, *java_options, '-cp', d8, 'com.android.tools.r8.D8', '--release', '--min-api', '33',
             '--lib', android_jar, '--output', output,
             *sorted((Path(temporary) / 'cn').rglob('*.class'))])
    with zipfile.ZipFile(output) as jar:
        data = jar.read('classes.dex')
        if not data.startswith(b'dex\n') or jar.testzip() is not None:
            raise RuntimeError('手电筒 DEX 校验失败')
        LOG.append(f'通过：Android API 35 编译、D8 DEX 校验；DEX {len(data)} 字节。')


def build_menu(tests=False):
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
        sources = [*sorted((ROOT / 'companion/src').rglob('*.java')), ROOT / 'android/cn/sidekey/LogTransfer.java']
        if tests:
            sources += [ROOT / 'tests' / name for name in ['MenuGeometryTest.java', 'AppGridGeometryTest.java', 'MenuSwitchStateTest.java']]
        run([javac, '-J-Dfile.encoding=UTF-8', '-J-Dstdout.encoding=UTF-8', '-J-Dstderr.encoding=UTF-8', '--release', '8', '-Xlint:deprecation,-options', '-Werror', '-encoding', 'UTF-8', '-classpath', android_jar,
             '-d', classes, *sources])
        if tests:
            for name in ['MenuGeometryTest', 'AppGridGeometryTest', 'MenuSwitchStateTest']:
                run([java, '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-cp', classes, name])
        run([java, '-cp', d8, 'com.android.tools.r8.D8', '--release', '--min-api', '34',
             '--lib', android_jar, '--output', dex, *sorted((classes / 'cn').rglob('*.class'))])
        with zipfile.ZipFile(unsigned, 'a', zipfile.ZIP_DEFLATED) as apk:
            for path in dex.glob('*.dex'): apk.write(path, path.name)
        run([align, '-f', '4', unsigned, aligned])
        output = MODULE / 'lib/sidekey-menu.apk'
        run([java, '-jar', signer, 'sign', '--ks', key, '--ks-key-alias', 'sidekey',
             '--ks-pass', 'file:' + str(password), '--out', output, aligned])
        run([java, '-jar', signer, 'verify', '--verbose', output])
        # 侧载只使用已校验的 APK；增量安装用的 .idsig 不属于 KernelSU 模块。
        output.with_name(output.name + '.idsig').unlink(missing_ok=True)
        badging = run([find('build-tools/*/aapt.exe'), 'dump', 'badging', output])
        if ("package: name='cn.sidekey.menu'" not in badging or
                f"versionCode='{VERSION_CODE}'" not in badging or f"versionName='{VERSION}'" not in badging):
            raise RuntimeError('菜单 APK 包名或版本无效')
        with zipfile.ZipFile(output) as apk:
            if apk.testzip() or not apk.read('classes.dex').startswith(b'dex\n'):
                raise RuntimeError('菜单 APK 完整性检查失败')
        LOG.append('通过：菜单 APK 的 API 35 编译、DEX、包名、版本及 APK 签名校验。')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--bootstrap', action='store_true')
    parser.add_argument('--test', action='store_true', help='显式运行自动测试；默认只编译打包')
    args = parser.parse_args()
    BUILD.mkdir(exist_ok=True)
    (MODULE / 'bin').mkdir(exist_ok=True)
    shutil.copyfile(ROOT / 'LICENSE', MODULE / 'LICENSES/sidekey-LICENSE.txt')
    build_ui()
    zig = find_zig(args.bootstrap)
    run([zig, 'version'])
    sources = ['native/gesture.c', 'native/config.c']
    common = ['-Wall', '-Wextra', '-Werror', '-std=c11', '-I', 'native']
    run([zig, 'cc', '-target', 'aarch64-linux-musl', '-static', '-O2', *common,
         '-Wl,-z,max-page-size=16384', '-s', 'native/sidekey.c', 'native/torch.c', 'native/haptic.c', 'native/menu.c', 'native/menu_protocol.c', 'native/menu_launch.c', 'native/mijia_menu.c', 'native/mijia_state_protocol.c', 'native/surfing.c', *sources, '-o', MODULE / 'bin/sidekey'])
    validate_elf(MODULE / 'bin/sidekey')
    build_torch(args.test)
    build_menu(args.test)
    from build_mijia import build as build_mijia
    build_mijia(run, tests=args.test)
    if args.test:
        test_binary = BUILD / ('native_tests.exe' if os.name == 'nt' else 'native_tests')
        run([zig, 'cc', '-O1', '-UNDEBUG', *common, 'tests/native_tests.c', 'native/menu_protocol.c', 'native/menu_launch.c', 'native/mijia_state_protocol.c', *sources, '-o', test_binary])
        run([test_binary])
        run([sys.executable, 'tests/config_scale_test.py', test_binary])
        run([sys.executable, 'tests/menu_layout_test.py', test_binary])
        node = shutil.which('node')
        if not node:
            raise RuntimeError('未找到 Node.js，无法执行 WebUI 测试')
        run([node, '--test', *sorted((ROOT / 'tests').glob('*.test.js'))])
        fixture = run([node, '--input-type=module', '-e',
            "import {defaultConfig,serialize} from './module/webroot/model.js';"
            "const c=defaultConfig();c.enabled=true;c.haptic=false;c.menu_side='left';c.menu_width=280;c.menu_gap=24;c.menu=[{slot:3,name:'设置',icon:'⚙️',type:'app_freeform',argument:'com.android.settings'},{slot:10,name:'灯光',icon:'',type:'torch',argument:''},{slot:11,name:'米家台灯',icon:'',type:'mijia',argument:'0123456789abcdef0123456789abcdef'}];c.actions[1]={type:'menu',argument:''};c.actions[0]={type:'torch',argument:''};c.actions[2]={type:'shell',argument:\"printf '%s' '你好'\\necho test\"};"
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
        if decoded['menu'][0]['slot'] != 3 or decoded['menu'][1]['slot'] != 10 or decoded['menu'][0]['type'] != 'app_freeform' or decoded['menu'][0]['name'] != '设置' or decoded['menu'][0]['icon'] != '⚙️' or decoded['actions'][1]['type'] != 'menu':
            raise RuntimeError('菜单 UTF-8 配置往返验证失败')
        if decoded['menu'][2]['type'] != 'mijia' or decoded['menu'][2]['argument'] != '0123456789abcdef0123456789abcdef':
            raise RuntimeError('米家动作编号跨语言配置往返失败')
        if decoded['menu_width'] != 280 or decoded['menu_gap'] != 24:
            raise RuntimeError('快捷栏宽度与间距配置往返验证失败')
        LOG.append('通过：WebUI → C 配置解析 → JSON，尺寸、中文、引号、换行保持一致。')
        bash = (r'C:\Program Files\Git\bin\bash.exe' if os.name == 'nt' and Path(r'C:\Program Files\Git\bin\bash.exe').exists() else shutil.which('bash'))
        if not bash or not Path(bash).exists():
            raise RuntimeError('未找到 Bash，无法执行 Shell 语法检查')
        run([sys.executable, 'tests/menu_install_test.py', bash])
        run([sys.executable, 'tests/app_launch_test.py', bash])
        run([sys.executable, 'tests/log_export_test.py', bash])
    else:
        LOG.append("未运行自动测试或界面测试；本次仅编译、签名和打包，交由用户实机验证。")
    files = sorted(path for path in MODULE.rglob('*') if path.is_file())
    required = {'module.prop', 'skip_mount', 'customize.sh', 'service.sh', 'action.sh',
                'uninstall.sh', 'scripts/control.sh', 'scripts/app-launch.sh', 'bin/sidekey', 'lib/torch.jar', 'lib/sidekey-menu.apk', 'scripts/menu-install.sh', 'webroot/index.html',
                'LICENSES/sidekey-LICENSE.txt', 'lib/mijia.jar', 'lib/mijia-source.zip', 'scripts/mijia.sh',
                'webroot/mijia.js', 'webroot/clipboard.js', 'webroot/log-export.js', 'scripts/logs.sh', 'scripts/surfing.sh', 'LICENSES/mijia-GPL-3.0.txt', 'LICENSES/micloud-MIT.txt',
                'webroot/shell.js', 'webroot/symbols.js', 'webroot/side-panel.js', 'webroot/action-picker.js', 'webroot/assets/symbols.js', 'webroot/assets/sources.json', 'LICENSES/SF-Symbols-notice.txt',
                'webroot/home.css', 'webroot/home-view.js'}
    if not required.issubset({path.relative_to(MODULE).as_posix() for path in files}):
        raise RuntimeError('模块文件不完整')
    for path in files:
        if path.parts[-2] not in ('bin', 'lib'):
            data = path.read_bytes()
            data.decode('utf-8')
            if b'\r' in data or data.startswith(b'\xef\xbb\xbf'):
                raise RuntimeError(f'{path.name} 必须为 UTF-8 无 BOM、LF 换行')
        if args.test and path.suffix == '.sh':
            run([bash, '-n', path])
        if args.test and path.suffix == '.js':
            run([node, '--check', path])

    now = datetime.now().astimezone()
    delivery = ROOT / '交付文件' / (now.strftime('%Y%m%d_%H%M%S_%f') + f'_{PACKAGE_PREFIX}_v{VERSION}_去除顶部占位与标题居中')
    delivery.mkdir(parents=True, exist_ok=False)
    package = delivery / f'{PACKAGE_PREFIX}_oppo_sidekey_v{VERSION}.zip'
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
        paths = [ROOT / name for name in ['AGENTS.md', 'README.md', 'CHANGELOG.md', 'LICENSE', 'build.py', 'build_mijia.py', 'bootstrap_android.py', 'package.json', '.gitignore', '.gitattributes', 'THIRD_PARTY_NOTICES.md']]
        paths += [path for directory in ['native', 'android', 'companion', 'mijia', 'tests', 'module', 'diagnostics', 'docs'] for path in (ROOT / directory).rglob('*') if path.is_file()]
        for path in sorted(paths):
            archive.write(path, path.relative_to(ROOT).as_posix())
    shutil.copyfile(ROOT / 'README.md', delivery / '使用说明.md')
    shutil.copyfile(ROOT / 'diagnostics/模块本地验证.md', delivery / '验证记录.md')
    shutil.copyfile(ROOT / f'docs/RELEASE-v{VERSION}.md', delivery / '本版变化与真机实测.md')
    (delivery / '构建日志.txt').write_text('\n'.join(LOG), encoding='utf-8')
    (delivery / '交付说明.md').write_text(
        f'# 侧键自定义 v{VERSION} {EDITION}\n\n'
        f'构建时间：{now.isoformat(timespec="seconds")}\n\n'
        '删除原生菜单顶部横条及其 44 dp 整块占位，只保留与左右一致的 10 dp 正常边距；点击菜单外或系统返回仍可收起。'
        '快捷操作、米家和 Surfing 卡片标题统一居中，状态提示同行列也居中；有状态图标时采用左右对称占位，标题不被推偏。'
        '最窄栏缩小状态标记占位以保留五字标题空间；温湿度数值维持名称与数值同行，不改数据展示逻辑。'
        'WebUI 布局预览同步去掉顶部横条及空位、居中标题；页面已有预览按钮与关闭按钮保留。'
        '继续整卡点击，沿用图样绿 #0DCD94、指定蓝 #66CCFF、蜜黄 #F4C66A 及深浅色背景。'
        '图标读取、状态判断、禁用规则、Surfing 显示条件、配置格式和签名证书继续沿用。\n\n'
        f'安装文件：{package.name}。在 KernelSU 中覆盖安装并重启，已有手势和快捷栏配置保留。'
        '目标为 OPPO Find X8s、Android 15 / ColorOS 15、原版 KernelSU。\n\n'
        '构建入口：python build.py。编译、APK 签名与 ZIP 校验结果见“构建日志.txt”。'
        'Java 编译仍有 AppCatalog 使用已过时 API 的提示；编译、DEX 和签名正常完成。'
        + ('本次运行自动测试。' if args.test else '按项目要求，本次未运行自动测试或界面测试；真机反馈见验证记录。') +
        '本次 UI 与触控尚未实机验证；真机步骤见“本版变化与真机实测.md”。'
        'Surfing 统计需已安装并配置 Surfing 与 SurfingTile 6.0.0 的本机 HTTP API。'
        '实时速率和核心累计流量来自 mihomo；核心重启或重置统计后累计值清零。'
        '订阅用量来自本机 /providers/proxies 的订阅元数据，依赖服务商上报并随订阅更新；只读，不触发订阅下载。'
        '未提供的用量或总额显示缺失提示，不推算或填 0；API 读取失败时提示查看日志。'
        '密钥只在 Root 侧读取，不传给菜单或写入日志。\n\n'
        '“源码与测试.zip”提供完整项目；模块内 lib/mijia-source.zip 提供 GPL 米家服务对应源码，无需单独安装。'
        '使用方法见“使用说明.md”，文件校验值见 SHA256SUMS.txt。\n\n'
        f'交付类型：{EDITION}。构建脚本不自动发布，正式下载以 GitHub Release 为准。\n',
        encoding='utf-8')
    sums = '\n'.join(f'{sha256(p.read_bytes()).hexdigest()}  {p.name}' for p in sorted(delivery.iterdir()) if p.is_file())
    (delivery / 'SHA256SUMS.txt').write_text(sums + '\n', encoding='utf-8')
    (BUILD / 'latest-delivery.txt').write_text(str(delivery), encoding='utf-8')
    print(f'\n交付目录：{delivery}\n安装包：{package.name}（{package.stat().st_size} 字节）')


if __name__ == '__main__':
    # Windows 重定向输出时也保留中文和测试结果符号，避免默认代码页中断构建。
    sys.stdout.reconfigure(encoding='utf-8')
    sys.stderr.reconfigure(encoding='utf-8')
    main()
