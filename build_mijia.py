"""构建独立的米家 Android 进程，并运行不含真实账号的协议测试。"""
from hashlib import sha256
from pathlib import Path
import os
import subprocess
import sys
import tempfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parent
JSON_URL = 'https://repo.maven.apache.org/maven2/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar'
JSON_SHA256 = 'dfb7bae2f404cfe0b72b4d23944698cb716b7665171812a0a4d0f5926c0fac79'


def build(run, live=False):
    sdk = ROOT / 'tools/android'
    java = next(sdk.glob('jdk/*/bin/java.exe'))
    javac = next(sdk.glob('jdk/*/bin/javac.exe'))
    android = next(sdk.glob('platform/*/android.jar'))
    d8 = next(sdk.glob('build-tools/*/lib/d8.jar'))
    test_json = ROOT / 'tools/android-json.jar'
    if not test_json.exists():
        with urllib.request.urlopen(JSON_URL, timeout=30) as response:
            test_json.write_bytes(response.read(256 * 1024))
    if sha256(test_json.read_bytes()).hexdigest() != JSON_SHA256:
        raise RuntimeError('主机测试 JSON 依赖校验失败')
    output = ROOT / 'module/lib/mijia.jar'
    output.parent.mkdir(exist_ok=True)
    (ROOT / 'build').mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='mijia-', dir=ROOT / 'build') as directory:
        classes = Path(directory)
        run([javac, '-J-Dfile.encoding=UTF-8', '-J-Dstderr.encoding=UTF-8', '--release', '8',
             '-Xlint:-options', '-encoding', 'UTF-8', '-classpath', android, '-d', classes,
             *sorted((ROOT / 'mijia/src').rglob('*.java')), ROOT / 'tests/MijiaTest.java'])
        run([java, '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
             '-cp', os.pathsep.join([str(classes), str(test_json)]), 'cn.sidekey.mijia.MijiaTest'])
        if live:
            run([java, '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
                 '-cp', os.pathsep.join([str(classes), str(test_json)]), 'cn.sidekey.mijia.MijiaTest', 'live'])
        production = sorted((classes / 'cn/sidekey/mijia').glob('*.class'))
        production = [path for path in production if not path.name.startswith('MijiaTest')]
        run([java, '-cp', d8, 'com.android.tools.r8.D8', '--release', '--min-api', '34',
             '--lib', android, '--output', output, *production])
    with zipfile.ZipFile(output) as archive:
        if archive.testzip() or not archive.read('classes.dex').startswith(b'dex\n'):
            raise RuntimeError('米家 DEX 校验失败')
    source = ROOT / 'module/lib/mijia-source.zip'
    with zipfile.ZipFile(source, 'w', zipfile.ZIP_DEFLATED) as archive:
        paths = list((ROOT / 'mijia').rglob('*')) + [ROOT / name for name in
                ['build_mijia.py', 'bootstrap_android.py', 'tests/MijiaTest.java', 'module/scripts/mijia.sh',
                 'module/LICENSES/mijia-GPL-3.0.txt', 'module/LICENSES/micloud-MIT.txt']]
        for path in sorted(paths):
            if path.is_file(): archive.write(path, path.relative_to(ROOT).as_posix())
    print('通过：米家协议、设备约束、异步任务、失败结果和 DEX 校验；独立服务对应源码随包提供。')


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    def run(command):
        subprocess.run([str(item) for item in command], cwd=ROOT, check=True, timeout=180)
    build(run, '--live' in sys.argv)
