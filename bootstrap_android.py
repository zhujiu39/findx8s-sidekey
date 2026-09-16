"""下载并校验本地 Android 编译工具；不会安装或修改系统环境变量。"""
from concurrent.futures import ThreadPoolExecutor
from hashlib import sha1, sha256
from pathlib import Path
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parent / 'tools' / 'android'
PACKAGES = [
    ('jdk', 'https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jdk_x64_windows_hotspot_21.0.12.1_1.zip', 'f9d6e191ab098c0d416e7d588a24420a8621cd2f4720dab2459b8b7b2d2d8b4e', sha256),
    ('platform', 'https://dl.google.com/android/repository/platform-35_r02.zip', '0bb560a90a7a2cbd0dd8348224d518b638fe7949', sha1),
    ('build-tools', 'https://dl.google.com/android/repository/build-tools_r35_windows.zip', 'af059bb67cf7786f45ee0db85e2d24985df1b4b6', sha1),
]


def install(package):
    name, url, expected, algorithm = package
    target = ROOT / name
    if (target / '.verified').exists():
        return name + ': already verified'
    archive = ROOT / (name + '.zip')
    ROOT.mkdir(parents=True, exist_ok=True)
    if not archive.exists():
        urllib.request.urlretrieve(url, archive)
    if algorithm(archive.read_bytes()).hexdigest() != expected:
        raise RuntimeError(name + ': checksum mismatch')
    with zipfile.ZipFile(archive) as bundle:
        for member in bundle.namelist():
            if not (target / member).resolve().is_relative_to(target.resolve()):
                raise RuntimeError('Invalid archive path')
        bundle.extractall(target)
    (target / '.verified').write_text(expected, encoding='ascii')
    return name + ': downloaded and verified'


if __name__ == '__main__':
    with ThreadPoolExecutor(max_workers=3) as pool:
        for result in pool.map(install, PACKAGES):
            print(result, flush=True)
