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
VERSION = '1.1.0-test.7'
VERSION_CODE = 107


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
             '-d', temporary, *sources, ROOT / 'tests/TorchControllerTest.java', ROOT / 'tests/AppCatalogModelTest.java', ROOT / 'tests/ZoomControllerTest.java', ROOT / 'tests/LogTransferTest.java'])
        java_options = ['-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8']
        run([java, *java_options, '-cp', temporary, 'TorchControllerTest'])
        run([java, *java_options, '-cp', temporary, 'AppCatalogModelTest'])
        run([java, *java_options, '-cp', temporary, 'ZoomControllerTest'])
        run([java, *java_options, '-cp', temporary, 'LogTransferTest'])
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
             '-d', classes, *sorted((ROOT / 'companion/src').rglob('*.java')), ROOT / 'android/cn/sidekey/LogTransfer.java', ROOT / 'tests/MenuGeometryTest.java', ROOT / 'tests/AppGridGeometryTest.java', ROOT / 'tests/MenuSwitchStateTest.java'])
        run([java, '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-cp', classes, 'MenuGeometryTest'])
        run([java, '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-cp', classes, 'AppGridGeometryTest'])
        run([java, '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-cp', classes, 'MenuSwitchStateTest'])
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
    args = parser.parse_args()
    BUILD.mkdir(exist_ok=True)
    (MODULE / 'bin').mkdir(exist_ok=True)
    shutil.copyfile(ROOT / 'LICENSE', MODULE / 'LICENSES/sidekey-LICENSE.txt')
    zig = find_zig(args.bootstrap)
    run([zig, 'version'])
    sources = ['native/gesture.c', 'native/config.c']
    common = ['-Wall', '-Wextra', '-Werror', '-std=c11', '-I', 'native']
    run([zig, 'cc', '-target', 'aarch64-linux-musl', '-static', '-O2', *common,
         '-Wl,-z,max-page-size=16384', '-s', 'native/sidekey.c', 'native/torch.c', 'native/haptic.c', 'native/menu.c', 'native/menu_protocol.c', 'native/menu_launch.c', 'native/mijia_menu.c', 'native/mijia_state_protocol.c', *sources, '-o', MODULE / 'bin/sidekey'])
    validate_elf(MODULE / 'bin/sidekey')
    build_torch()
    build_menu()
    from build_mijia import build as build_mijia
    build_mijia(run)
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
    files = sorted(path for path in MODULE.rglob('*') if path.is_file())
    required = {'module.prop', 'skip_mount', 'customize.sh', 'service.sh', 'action.sh',
                'uninstall.sh', 'scripts/control.sh', 'scripts/app-launch.sh', 'bin/sidekey', 'lib/torch.jar', 'lib/sidekey-menu.apk', 'scripts/menu-install.sh', 'webroot/index.html',
                'LICENSES/sidekey-LICENSE.txt', 'lib/mijia.jar', 'lib/mijia-source.zip', 'scripts/mijia.sh',
                'webroot/mijia.js', 'webroot/clipboard.js', 'webroot/log-export.js', 'scripts/logs.sh', 'LICENSES/mijia-GPL-3.0.txt', 'LICENSES/micloud-MIT.txt'}
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
    delivery = ROOT / '交付文件' / (now.strftime('%Y%m%d_%H%M%S_%f') + f'_test_v{VERSION}_米家控制超时状态核对')
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
        paths = [ROOT / name for name in ['README.md', 'CHANGELOG.md', 'LICENSE', 'build.py', 'build_mijia.py', 'bootstrap_android.py', 'package.json', '.gitignore', '.gitattributes', 'THIRD_PARTY_NOTICES.md']]
        paths += [path for directory in ['native', 'android', 'companion', 'mijia', 'tests', 'module', 'diagnostics', 'docs'] for path in (ROOT / directory).rglob('*') if path.is_file()]
        for path in sorted(paths):
            archive.write(path, path.relative_to(ROOT).as_posix())
    shutil.copyfile(ROOT / 'README.md', delivery / '使用说明.md')
    shutil.copyfile(ROOT / 'diagnostics/模块本地验证.md', delivery / '验证记录.md')
    (delivery / '构建日志.txt').write_text('\n'.join(LOG), encoding='utf-8')
    (delivery / '交付说明.md').write_text(
        f'# 侧键自定义 v{VERSION} 本地测试包\n\n'
        f'构建时间：{now.isoformat(timespec="seconds")}\n\n'
        '本版修正米家设备操作超时后的状态核对：-704083036 和 -704053036 作为执行结果未确认处理，保留原始错误码。'
        '快捷栏布尔操作遇到设备超时或网络中断时，仍保留目标值并进行有限次读回；匹配后更新图标，否则显示未知，不把第一次旧值当成最终状态。'
        '控制只发送一次，正常成功时不增加读取或等待。诊断增加切换前状态、属性编号、目标值和发送阶段的错误原因。'
        '此修复不代表已经消除云端、网关或设备侧约 6 秒的响应超时，仍需观察实体设备测试。\n\n'
        '快捷开关点击后立即提交动作并保留快捷栏，允许连续操作；点击应用后仍自动收起，并按原有路径打开 ColorOS 小窗。'
        '米家开关在展开及每次操作完成后读取实际状态；执行期间显示进度，完成后更新开关图标，空闲时不请求云端。'
        '布尔操作后的首次读回若仍是旧值，会有限次复查目标属性，匹配即结束；不会显示旧的相反状态或重发控制。'
        '快捷栏直接连接常驻米家服务，省去每次点击启动命令进程；属性读回合并为批量请求。'
        '内置墨白极简与石墨工具箱两套 WebUI；手机快捷菜单外观保持不变。\n\n'
        f'安装文件：test_oppo_sidekey_v{VERSION}.zip。在 KernelSU 中覆盖安装并重启，已有手势和快捷栏配置保留。'
        '目标为 OPPO Find X8s、Android 15 / ColorOS 15、原版 KernelSU；无需额外安装 Python 或 Termux。\n\n'
        '测试顺序：先用侧键打开快捷栏，点击米家开关，观察执行完成后图标能否变成实际开/关状态；连续切换时确认面板仍在。'
        '执行手动场景后核对相关开关状态；最后点击应用确认面板收起且打开小窗。'
        '米家测试可进入米家页生成登录二维码并用米家 App 扫一扫授权；选择家庭，测试设备开关或手动场景，加入快捷菜单并保存设置后再用侧键验证。'
        '该接入当前面向中国大陆账号；没有 MIOT 规格的设备可通过米家手动场景使用。\n\n'
        '最近执行结果位于米家页；超时后读回匹配会明确说明已读回目标状态，并保留控制超时的原因。'
        '设置 → 运行状态与日志 → 导出完整日志可选择保存位置，保存版本、时间、各类诊断及保留的上一段日志。'
        '米家日志记录目标值、每次读回值和耗时。完整导出总上限 64 MiB，超限报错而非截取；原复制入口仍保留每文件 64 KiB 的预览边界。'
        '每次展开及每次操作完成后读取状态，读取失败或有限次复查仍未确认时显示未知；本地结果查询不会重复发送控制或持续查询云端。'
        '场景和无可读开关状态的动作仍是执行按钮。已受理不等于设备状态已确认；请求中断时不会自动重发。'
        '登录凭据保存在手机私有目录，WebUI 不显示令牌，退出账号删除本机凭据和米家动作。\n\n'
        '本地 C/Java/JavaScript/配置往返/脚本测试、ARM64 ELF、DEX、APK 签名、ZIP 和权限检查的命令输出见构建日志。'
        '真实服务的匿名扫码握手、二维码读取和公开设备规格解析已通过；本包未完成目标手机账号与真实设备端到端实测。'
        '系统文件选择器与所选文档提供方的实际写入需要手机验证；取消选择不应显示成功。\n\n'
        '包内 lib/mijia-source.zip 是 GPL 米家服务的完整对应源码，不是另一个安装包；无需解压。'
        '其余源码与测试、说明、日志及 SHA256 在本交付目录提供。没有包含个人账号、设备数据、ADB 标识或签名私钥。\n\n'
        '本次仅本地打包，没有推送 GitHub、创建 Tag 或发布 Release。恢复原侧键可关闭接管，或禁用模块后重启。\n',
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
