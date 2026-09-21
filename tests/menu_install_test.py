"""用包管理器替身验证安装脚本的管道边界和失败结果，不连接 Android。"""
from pathlib import Path
import os
import subprocess
import sys
import tempfile

root = Path(__file__).resolve().parent.parent
bash = sys.argv[1]
with tempfile.TemporaryDirectory(prefix='menu-install-test-', dir=root / 'build') as temporary:
    base = Path(temporary).resolve()
    assert base.is_relative_to((root / 'build').resolve())
    module, data, binaries = (base / name for name in ['module', 'data', 'bin'])
    for directory in [module / 'lib', data, binaries]: directory.mkdir(parents=True)
    (module / 'lib/sidekey-menu.apk').write_bytes(b'test APK only')
    source = (root / 'module/scripts/menu-install.sh').read_text(encoding='utf-8')
    source = source.replace('MODDIR=${0%/scripts/*}', 'MODDIR="$TEST_MODULE"').replace('DATA=/data/adb/oppo_sidekey', 'DATA="$TEST_DATA"')
    script = base / 'install.sh'; script.write_text(source, encoding='utf-8', newline='\n')
    mocks = {
        'getprop': 'echo 1',
        'am': '[ -p /dev/stdout ] && [ -p /dev/stderr ] || { echo "private FD rejected" >&2; exit 68; }; echo 0',
        'pm': '''if [ "$1" = path ]; then exit 1; fi
[ "$1" = install ] || exit 69
[ -p /dev/stdin ] && [ -p /dev/stdout ] && [ -p /dev/stderr ] || { echo "private FD rejected" >&2; exit 68; }
cat >/dev/null
if [ "$TEST_FAIL" = 1 ]; then echo "Failure [MOCK_INSTALL_FAILED]" >&2; exit 17; fi
echo Success''',
    }
    for name, content in mocks.items():
        path = binaries / name; path.write_text('#!/bin/sh\n' + content + '\n', encoding='utf-8', newline='\n'); path.chmod(0o755)
    def posix(path):
        return subprocess.check_output([bash, '-c', 'cygpath -u "$1"', '--', str(path)], text=True).strip() if os.name == 'nt' else str(path)
    environment = {**os.environ, 'TEST_MODULE':posix(module), 'TEST_DATA':posix(data), 'TEST_BIN':posix(binaries)}
    def run(fail=False):
        return subprocess.run([bash, '-c', 'export PATH="$TEST_BIN:$PATH"; bash "$1"', '--', posix(script)],
                              env={**environment, 'TEST_FAIL':'1' if fail else '0'}, capture_output=True, timeout=15)
    assert run().returncode == 0
    marker = data / 'menu-installed-0'
    assert marker.exists() and 'Success' in (data / 'menu-install.log').read_text(encoding='utf-8')
    marker.unlink()
    assert run(True).returncode != 0 and not marker.exists()
    assert 'MOCK_INSTALL_FAILED' in (data / 'menu-install.log').read_text(encoding='utf-8')
    # 重现旧的直接重定向：替身拒绝私有文件描述符，确保本测试能捕捉回归。
    unsafe = source.replace('install_menu </dev/null 2>&1 | cat >"$DATA/menu-install.log"', 'install_menu </dev/null >"$DATA/menu-install.log" 2>&1')
    script.write_text(unsafe, encoding='utf-8', newline='\n')
    assert run().returncode != 0 and not marker.exists()
    assert 'private FD rejected' in (data / 'menu-install.log').read_text(encoding='utf-8')
print('PASS: installer pipe descriptors, install failure propagation, and direct-file regression detection')
