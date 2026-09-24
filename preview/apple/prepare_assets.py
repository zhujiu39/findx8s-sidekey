"""从用户的 SF Symbols 库提取图标，保留 Apple 路径与来源校验信息。"""
import hashlib
import json
import xml.etree.ElementTree as ET
from pathlib import Path

HERE = Path(__file__).resolve().parent
SOURCE = Path(r'C:\Users\14692\Desktop\anjian\SF Symbols')
NAMES = '''hand.tap hand.point.up.left.fill sidebar.left house.fill gearshape.fill
iphone.gen3 flashlight.on.fill network arrow.clockwise lightbulb.fill thermometer.medium
sensor square.grid.2x2.fill air.conditioner.horizontal.fill moon.fill curtains.closed
power waveform square.on.square slider.horizontal.3 chevron.right chevron.down
checkmark xmark plus minus arrow.up arrow.down trash pencil magnifyingglass
play.fill pause.fill backward.fill forward.fill speaker.wave.2.fill speaker.slash.fill
camera.fill bell.fill lock.fill photo.on.rectangle rectangle.stack.fill
arrow.uturn.backward terminal keyboard nosign app.fill sun.max.fill
square.and.arrow.up doc.on.doc info.circle checkmark.circle.fill exclamationmark.circle.fill
bolt.fill wifi qrcode viewfinder figure.walk creditcard.fill message.fill music.note
map.fill safari clock.fill envelope.fill folder.fill rectangle.portrait.and.arrow.right
rectangle.compress.vertical circle.lefthalf.filled circle.fill battery.100'''.split()
ALIASES = {
    'sidebar.left': 'rectangle.on.rectangle.button.angledtop.vertical.left',
    'square.grid.2x2.fill': 'apps.iphone', 'square.on.square': 'rectangle.on.rectangle',
    'speaker.slash.fill': 'speaker.zzz', 'terminal': 'apple.terminal',
    'doc.on.doc': 'document.on.document', 'info.circle': 'info',
    'checkmark.circle.fill': 'checkmark', 'exclamationmark.circle.fill': 'exclamationmark',
    'qrcode': 'viewfinder', 'music.note': 'music.note.list', 'safari': 'network',
    'rectangle.compress.vertical': 'rectangle', 'battery.100': 'battery.100percent'
}

def main():
    files = sorted(SOURCE.rglob('*.svg'), key=lambda p: ('多色' in str(p), '新符号' in str(p), str(p)))
    assets = HERE / 'assets'
    assets.mkdir(exist_ok=True)
    symbols, manifest = {}, []
    for name in sorted(set(NAMES)):
        source_name = ALIASES.get(name, name)
        path = next((p for p in files if p.stem == source_name), None)
        if path is None and source_name.endswith('.fill'):
            source_name = source_name[:-5]
            path = next((p for p in files if p.stem == source_name), None)
        if path is None:
            raise FileNotFoundError(f'SF Symbols 库缺少 {name}')
        original = path.read_bytes()
        svg = ET.fromstring(original)
        for element in svg.iter():
            element.tag = element.tag.split('}')[-1]
            if element.get('fill') in ('white', '#FFFFFF', '#ffffff'):
                element.set('fill', 'currentColor')
        svg.set('xmlns', 'http://www.w3.org/2000/svg')
        svg.set('aria-hidden', 'true')
        svg.set('focusable', 'false')
        svg.set('class', 'sf-symbol')
        svg.attrib.pop('version', None)
        value = ET.tostring(svg, encoding='unicode')
        symbols[name] = value
        (assets / f'{name}.svg').write_text(value, encoding='utf-8', newline='\n')
        manifest.append({'symbol_key': name, 'apple_symbol': source_name, 'source': str(path), 'source_sha256': hashlib.sha256(original).hexdigest(),
                         'preview_sha256': hashlib.sha256(value.encode()).hexdigest()})
    (assets / 'symbols.js').write_text('export const symbols = ' + json.dumps(symbols, ensure_ascii=False, separators=(',', ':')) + ';\n', encoding='utf-8', newline='\n')
    (assets / 'sources.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    print(f'已生成 {len(symbols)} 个 SF Symbols，路径数据保持原样。')

if __name__ == '__main__':
    main()
