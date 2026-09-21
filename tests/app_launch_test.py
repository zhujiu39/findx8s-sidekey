"""检查小窗走专用入口、真实状态回执、失败不回退以及普通手势动作兼容。"""
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
    binaries, data, scripts, library = (base / name for name in ['bin','data','scripts','lib'])
    for folder in [binaries, data, scripts, library]: folder.mkdir()
    (library / 'torch.jar').write_bytes(b'test placeholder')
    source = (root / 'module/scripts/app-launch.sh').read_text(encoding='utf-8')
    source = source.replace('DATA=/data/adb/oppo_sidekey', 'DATA="$TEST_DATA"').replace('/system/bin/app_process', 'app_process')
    script = scripts / 'app-launch.sh'; script.write_text(source, encoding='utf-8', newline='\n')
    mocks = {
        'cmd': '''[ -p /dev/stdout ] && [ -p /dev/stderr ] || exit 68
printf '%s\\n' "$*" >>"$TEST_DATA/calls"
[ "$TEST_CASE" != missing ] || { echo 'No activity found'; exit 0; }
if [ "$TEST_CASE" = multi ] && [ "$2" = resolve-activity ]; then echo 'android/.ResolverActivity'; else echo 'com.demo/.Main'; fi''',
        'am': '''[ -p /dev/stdout ] && [ -p /dev/stderr ] || exit 68
[ "$1" != get-current-user ] || { echo 10; exit 0; }
printf '%s\\n' "$*" >>"$TEST_DATA/calls"
[ "$TEST_CASE" != fail ] || { echo 'Error: Permission denied'; exit 0; }
echo 'Status: ok' ''',
        'app_process': '''[ -p /dev/stdout ] && [ -p /dev/stderr ] || exit 68
[ -r "$CLASSPATH" ] || exit 69
printf 'zoom %s\\n' "$*" >>"$TEST_DATA/calls"
[ "$1" = /system/bin ] && [ "$2" = cn.sidekey.ZoomWindowLauncher ] && [ "$3" = com.demo/.Main ] && [ "$4" = 10 ] || exit 70
case "$TEST_CASE" in
 timeout) exit 124 ;;
 killed) exit 137 ;;
 false_success) echo 'Status: ok'; exit 0 ;;
 wrong_package) echo 'SIDEKEY_ZOOM_CONFIRMED com.other 10'; exit 0 ;;
 wrong_user) echo 'SIDEKEY_ZOOM_CONFIRMED com.demo 11'; exit 0 ;;
 fail) echo '小窗启动失败：SecurityException'; exit 1 ;;
esac
echo 'SIDEKEY_ZOOM_CONFIRMED com.demo 10' ''',
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
        launches = [line for line in calls if line.startswith(('start ', 'zoom '))]
        return result.returncode, launches, (data / 'app-launch.log').read_text(encoding='utf-8')
    code, launches, log = run('ok')
    assert code == 0 and len(launches) == 1 and launches[0].startswith('zoom ') and '确认目标应用' in log, (code,launches,log)
    for case in ['timeout','killed','false_success','wrong_package','wrong_user','fail']:
        code, launches, log = run(case)
        assert code != 0 and len(launches) == 1 and launches[0].startswith('zoom '), (case,code,launches,log)
    code, launches, log = run('ok', 'normal')
    assert code == 0 and len(launches) == 1 and '--user 10' in launches[0] and '-n com.demo/.Main' in launches[0]
    assert '0x10200000' in launches[0] and '--windowingMode' not in launches[0]
    code, launches, log = run('fail','normal')
    assert code != 0 and len(launches) == 1 and 'Permission denied' in log
    code, launches, log = run('missing')
    assert code != 0 and not launches
    assert run('multi')[0] == 0
    code, launches, log = run('ok',package='com.demo;id')
    assert code != 0 and not launches
    (library / 'torch.jar').unlink()
    code, launches, log = run('ok')
    assert code != 0 and not launches and '组件不存在' in log
print('PASS: OEM small-window entry, exact confirmation, pipe FDs, one launch, no fullscreen fallback, missing helper and normal gesture compatibility')
