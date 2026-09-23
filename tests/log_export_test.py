"""验证诊断导出包含保留日志，且不会读取凭据或其他数据文件。"""
from pathlib import Path
import os
import subprocess
import sys
import tempfile

root = Path(__file__).resolve().parent.parent
bash = sys.argv[1]
with tempfile.TemporaryDirectory(prefix='log-export-test-', dir=root / 'build') as temporary:
    base = Path(temporary).resolve()
    assert base.is_relative_to((root / 'build').resolve())
    module, data = base / 'module', base / 'data'
    module.mkdir()
    (data / 'mijia').mkdir(parents=True)
    (module / 'module.prop').write_text('version=fixture-test\n', encoding='utf-8')
    (data / 'events.log.1').write_text('上一段事件\n', encoding='utf-8')
    (data / 'events.log').write_text('日志开头不能被六千字节预览截掉\n' + '事件\n' * 2000, encoding='utf-8')
    for name in ['torch-service.log', 'menu-install.log', 'menu-launch.log', 'app-launch.log', 'mijia-menu.log.1', 'mijia-menu.log', 'mijia/debug.log.1']:
        (data / name).write_text(f'完整内容：{name}\n', encoding='utf-8')
    (data / 'mijia/debug.log').write_text('旧内容\n' + '开灯与关灯状态\n' * 5000 + '目标=0，读回=1\n目标=0，读回=0\n', encoding='utf-8')
    for name in ['auth.json', 'bindings.json', 'catalog-1.json', 'unexpected.log']:
        (data / 'mijia' / name).write_text('PRIVATE_DO_NOT_EXPORT', encoding='utf-8')
    source = (root / 'module/scripts/logs.sh').read_text(encoding='utf-8')
    source = source.replace('MODDIR=${0%/scripts/*}', 'MODDIR="$TEST_MODULE"').replace('DATA=/data/adb/oppo_sidekey', 'DATA="$TEST_DATA"')
    script = base / 'logs.sh'
    script.write_text(source, encoding='utf-8', newline='\n')

    def posix(path):
        return subprocess.check_output([bash, '-c', 'cygpath -u "$1"', '--', str(path)], text=True).strip() if os.name == 'nt' else str(path)

    result = subprocess.run([bash, posix(script)], env={**os.environ, 'TEST_MODULE': posix(module), 'TEST_DATA': posix(data)},
                            capture_output=True, check=True, timeout=15)
    text = result.stdout.decode('utf-8')
    assert 'fixture-test' in text and '导出时间' in text
    assert text.count('──── ') == 10
    assert '上一段事件' in text and '日志开头不能被六千字节预览截掉' in text
    assert text.index('目标=0，读回=0') < text.index('日志开头不能被六千字节预览截掉') < text.index('上一段事件')
    assert '完整内容：mijia/debug.log.1' in text and '目标=0，读回=0' in text
    assert '已截取末尾 64 KiB' in text and '旧内容' not in text
    assert 'PRIVATE_DO_NOT_EXPORT' not in text and len(result.stdout) < 10 * 65536 + 4096
    assert text.rstrip().endswith('===== SIDEKEY_LOG_END =====')
    full_bytes = subprocess.run([bash, posix(script), 'full'], env={**os.environ, 'TEST_MODULE': posix(module), 'TEST_DATA': posix(data)},
                                capture_output=True, check=True, timeout=15).stdout
    full = full_bytes.decode('utf-8')
    assert '已截取' not in full and full.count('开灯与关灯状态') == 5000 and '旧内容' in full
    assert (data / 'mijia/debug.log').read_bytes() in full_bytes
    assert 'PRIVATE_DO_NOT_EXPORT' not in full and full.rstrip().endswith('===== SIDEKEY_LOG_END =====')
    (data / 'mijia/debug.log').unlink()
    result = subprocess.run([bash, posix(script)], env={**os.environ, 'TEST_MODULE': posix(module), 'TEST_DATA': posix(data)},
                            capture_output=True, check=True, timeout=15)
    assert '暂无日志' in result.stdout.decode('utf-8')
print('PASS: diagnostic sections, rotated logs, copy bounds, untruncated full export, missing files, private-data exclusion')
