"""只构建独立网页预览，执行语法/资源/交付完整性检查，不运行功能测试。"""
import hashlib
import json
import re
import shutil
import subprocess
import zipfile
from datetime import datetime
from html.parser import HTMLParser
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def main():
    version = re.search(r'^version=(.+)$', (ROOT / 'module/module.prop').read_text(encoding='utf-8'), re.M)[1]
    head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    if version != 'v1.2.0-test.2':
        raise ValueError('模块版本已变化，请先重新审阅并更新预览基线。')
    node = shutil.which('node')
    if not node:
        raise RuntimeError('缺少 Node.js，无法完成 JavaScript 语法解析。')
    lines = [f'交付时间：{datetime.now().isoformat(timespec="seconds")}', f'模块版本：{version}', f'源码基线：{head}',
             '构建类型：独立网页预览；未编译手机模块；未执行自动功能、浏览器或联网测试。']
    for path in sorted(HERE.rglob('*.js')):
        subprocess.run([node, '--check', str(path)], check=True, capture_output=True)
        for ref in re.findall(r'(?:from\s*|import\s*)[\'\"](\.[^\'\"]+)[\'\"]', path.read_text(encoding='utf-8')):
            if not (path.parent / ref).is_file():
                raise FileNotFoundError(f'模块引用缺失：{path.name} -> {ref}')
    lines.append('JavaScript 语法解析与本地 import 引用检查完成。')
    class References(HTMLParser):
        def __init__(self):
            super().__init__(); self.ids = set(); self.refs = []
        def handle_starttag(self, tag, attrs):
            attrs = dict(attrs)
            if 'id' in attrs:
                if attrs['id'] in self.ids:
                    raise ValueError(f'HTML id 重复：{attrs["id"]}')
                self.ids.add(attrs['id'])
            for key in ['src', 'href']:
                value = attrs.get(key, '')
                if value and not re.match(r'^(?:[a-z]+:|#)', value):
                    self.refs.append(value)
    parser = References(); parser.feed((HERE / 'index.html').read_text(encoding='utf-8'))
    refs = parser.refs + re.findall(r'url\([\'\"]?([^\)\'\"]+)', (HERE / 'style.css').read_text(encoding='utf-8'))
    for ref in refs:
        if not (HERE / ref).is_file():
            raise FileNotFoundError(f'页面资源缺失：{ref}')
    # 直接沿用的规则必须与最新正式源码逐字节一致。
    unchanged = ['model.js', 'mijia-model.js', 'mijia-api.js', 'app-catalog.js', 'app-selection.js', 'clipboard.js', 'navigation.js']
    for name in unchanged:
        if digest(HERE / name) != digest(ROOT / 'module/webroot' / name):
            raise ValueError(f'复用的规则与模块源码不一致：{name}')
    sources = json.loads((HERE / 'assets/sources.json').read_text(encoding='utf-8'))
    for source in sources:
        if digest(Path(source['source'])) != source['source_sha256']:
            raise ValueError(f'用户提供的符号已发生变化：{source["source"]}')
        if digest(HERE / 'assets' / (source['symbol_key'] + '.svg')) != source['preview_sha256']:
            raise ValueError(f'预览符号校验失败：{source["symbol_key"]}')
    lines.append(f'HTML 资源、{len(unchanged)} 个原版规则文件、{len(sources)} 个 SF Symbols 资源校验完成。')
    manifest = {'version': version, 'versionCode': 115, 'source_head': head, 'source_worktree': str(ROOT),
                'built_at': datetime.now().isoformat(timespec='seconds'), 'validation': 'syntax-and-package-only',
                'source_files': {str(path.relative_to(ROOT)).replace('\\', '/'): digest(path) for path in sorted((ROOT / 'module/webroot').iterdir()) if path.is_file()}}
    (HERE / 'SOURCE_MANIFEST.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    delivery = ROOT / '交付文件' / (datetime.now().strftime('%Y%m%d_%H%M%S_%f') + '_test_v1.2.0-test.2_Apple完整交互预览')
    delivery.mkdir(parents=True)
    package = delivery / 'test_sidekey_v1.2.0-test.2_Apple预览.zip'
    files = [path for path in HERE.rglob('*') if path.is_file() and '__pycache__' not in path.parts]
    with zipfile.ZipFile(package, 'w', zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(files):
            archive.write(path, Path('apple-preview') / path.relative_to(HERE))
    with zipfile.ZipFile(package) as archive:
        if archive.testzip() is not None or 'apple-preview/index.html' not in archive.namelist():
            raise ValueError('ZIP 完整性检查失败。')
    lines.append(f'ZIP 完整性检查完成：{len(files)} 个文件，{package.stat().st_size} 字节。')
    (delivery / '构建与校验记录.txt').write_text('\n'.join(lines) + '\n', encoding='utf-8', newline='\n')
    shutil.copyfile(HERE / '功能覆盖与设计说明.md', delivery / '功能覆盖与设计说明.md')
    shutil.copyfile(HERE / 'SOURCE_MANIFEST.json', delivery / 'SOURCE_MANIFEST.json')
    (delivery / '交付说明.md').write_text(
        f'# 侧键 Apple 交互预览\n\n交付时间：{datetime.now().isoformat(timespec="seconds")}\n\n'
        f'基线：{version}，版本代码 115，提交 `{head}`。\n\n'
        '独立网页预览，覆盖手势、快捷栏、米家、设置及实际菜单的交互设计。所有账号、设备、应用和流量为示例，修改仅保存在浏览器。\n\n'
        '打开本次本地预览：http://127.0.0.1:8766/index.html\n\n'
        '分发后解压 ZIP，在 apple-preview 目录运行 `python -m http.server 8766 --bind 127.0.0.1`，再通过上述地址打开。不要通过 file:// 直接打开 ES modules。\n\n'
        '本次没有 Root 安装包，不需要刷入手机。正式模块源码和签名未改动。\n\n'
        '构建完成 JavaScript 语法、资源引用、复用规则、图标来源和压缩包完整性检查。按项目 AGENTS.md 未执行自动功能测试、浏览器测试或联网测试，真实交互与设备效果尚待评审。\n', encoding='utf-8', newline='\n')
    sums = '\n'.join(f'{digest(path)}  {path.name}' for path in sorted(delivery.iterdir()) if path.is_file())
    (delivery / 'SHA256SUMS.txt').write_text(sums + '\n', encoding='utf-8', newline='\n')
    (ROOT / 'build').mkdir(exist_ok=True)
    (ROOT / 'build/latest-apple-preview.txt').write_text(str(delivery), encoding='utf-8')
    print('\n'.join(lines))
    print(f'交付目录：{delivery}')

if __name__ == '__main__':
    main()
