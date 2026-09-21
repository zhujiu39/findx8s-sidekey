"""验证系统拒绝小窗、am 返回码假成功、当前用户和管道边界，不连接手机。"""
from pathlib import Path
import os
import subprocess
import sys
import tempfile

root = Path(__file__).resolve().parent.parent
bash = sys.argv[1]
with tempfile.TemporaryDirectory(prefix='app-launch-test-', dir=root / 'build') as temporary:
    base = Path(temporary).resolve()
    assert base.is_relative_to((root / 'build').resolve())
    binaries, data = base / 'bin', base / 'data'
    binaries.mkdir(); data.mkdir()
    source = (root / 'module/scripts/app-launch.sh').read_text(encoding='utf-8').replace('DATA=/data/adb/oppo_sidekey', 'DATA="$TEST_DATA"')
    script = base / 'launch.sh'; script.write_text(source, encoding='utf-8', newline='\n')
    mocks = {
        'cmd': '''[ -p /dev/stdout ] && [ -p /dev/stderr ] || exit 68
printf '%s\\n' "$*" >>"$TEST_DATA/calls"
[ "$TEST_CASE" != missing ] || { echo 'No activity found'; exit 0; }
if [ "$TEST_CASE" = multi ] && [ "$2" = resolve-activity ]; then echo 'android/.ResolverActivity'; else echo 'com.demo/.Main'; fi''',
        'am': '''[ -p /dev/stdout ] && [ -p /dev/stderr ] || exit 68
[ "$1" != get-current-user ] || { echo 10; exit 0; }
printf '%s\\n' "$*" >>"$TEST_DATA/calls"
case "$TEST_CASE" in
 timeout) exit 124 ;;
 unknown) echo 'Starting only'; exit 0 ;;
 system_timeout) echo 'Status: timeout'; exit 0 ;;
 fail) echo 'Error: Permission denied'; exit 0 ;;
 fallback) case "$*" in *'--windowingMode 5'*) echo 'Error: unsupported windowing mode'; exit 0 ;; esac ;;
esac
echo 'Status: ok' ''',
    }
    for name, content in mocks.items():
        path = binaries / name; path.write_text('#!/bin/sh\n' + content + '\n', encoding='utf-8', newline='\n'); path.chmod(0o755)
    def posix(path):
        return subprocess.check_output([bash, '-c', 'cygpath -u "$1"', '--', str(path)], text=True).strip() if os.name == 'nt' else str(path)
    environment = {**os.environ, 'TEST_DATA':posix(data), 'TEST_BIN':posix(binaries)}
    def run(case, mode='freeform', package='com.demo'):
        (data / 'calls').write_text('', encoding='utf-8')
        result = subprocess.run([bash, '-c', 'export PATH="$TEST_BIN:$PATH"; bash "$1" "$2" "$3"', '--', posix(script), package, mode],
                                env={**environment,'TEST_CASE':case}, capture_output=True, timeout=12)
        calls = (data / 'calls').read_text(encoding='utf-8').splitlines()
        launches = [line for line in calls if line.startswith('start ')]
        return result.returncode, launches, (data / 'app-launch.log').read_text(encoding='utf-8')
    code, launches, log = run('ok')
    assert code == 0 and len(launches) == 1 and '--user 10' in launches[0] and '-n com.demo/.Main' in launches[0], (code,launches,log)
    assert '0x10200000' in launches[0] and '--windowingMode 5' in launches[0]
    code, launches, log = run('fallback')
    assert code == 0 and len(launches) == 2 and '--windowingMode' not in launches[1] and '普通打开' in log
    for case in ['timeout','system_timeout','unknown']:
        code, launches, log = run(case)
        assert code != 0 and len(launches) == 1, (case,code,launches,log)
    code, launches, log = run('fail','normal')
    assert code != 0 and len(launches) == 1 and 'Permission denied' in log
    code, launches, log = run('missing')
    assert code != 0 and not launches
    assert run('multi')[0] == 0
    code, launches, log = run('ok',package='com.demo;id')
    assert code != 0 and not launches
print('PASS: explicit app component, current user, Binder pipe FDs, freeform fallback, false success and no duplicate launch on timeout')
