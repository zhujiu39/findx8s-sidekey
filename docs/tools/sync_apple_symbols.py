"""将项目内的 SF Symbols 路径转换为 Android 矢量资源，不依赖外部图标库。"""
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'module/webroot/assets'
DESTINATION = ROOT / 'companion/res/drawable'
ANDROID = 'http://schemas.android.com/apk/res/android'
ET.register_namespace('android', ANDROID)


def convert(path):
    source = ET.parse(path).getroot()
    x, y, width, height = source.attrib['viewBox'].split()
    if float(x) or float(y):
        raise ValueError(f'不支持非零视口原点：{path.name}')
    edge = max(float(width), float(height))
    vector = ET.Element('vector', {f'{{{ANDROID}}}width': '24dp', f'{{{ANDROID}}}height': '24dp',
                                  f'{{{ANDROID}}}viewportWidth': str(edge), f'{{{ANDROID}}}viewportHeight': str(edge)})
    group = ET.SubElement(vector, 'group', {f'{{{ANDROID}}}translateX': str((edge - float(width)) / 2),
                                         f'{{{ANDROID}}}translateY': str((edge - float(height)) / 2)})

    def append(node, opacity=1.0):
        tag = node.tag.rsplit('}', 1)[-1]
        opacity *= float(node.get('opacity', '1'))
        if opacity == 0:
            return
        if 'transform' in node.attrib:
            raise ValueError(f'未处理的矢量变换：{path.name}')
        if tag in ('svg', 'g'):
            for child in node:
                append(child, opacity)
        elif tag == 'path':
            color = node.get('fill', 'currentColor')
            if color != 'currentColor':
                raise ValueError(f'未处理的路径颜色：{path.name} {color}')
            attributes = {'pathData': node.attrib['d'], 'fillColor': '#FFFFFFFF',
                          'fillAlpha': str(opacity * float(node.get('fill-opacity', '1'))),
                          'fillType': 'evenOdd' if node.get('fill-rule') == 'evenodd' else 'nonZero'}
            ET.SubElement(group, 'path', {f'{{{ANDROID}}}{key}': value for key, value in attributes.items()})
        else:
            raise ValueError(f'未处理的可见元素：{path.name} {tag}')

    append(source)
    ET.indent(vector, '  ')
    name = 'sf_' + path.stem.replace('.', '_').replace('-', '_') + '.xml'
    (DESTINATION / name).write_text('<?xml version="1.0" encoding="utf-8"?>\n' + ET.tostring(vector, encoding='unicode') + '\n', encoding='utf-8', newline='\n')


if __name__ == '__main__':
    DESTINATION.mkdir(parents=True, exist_ok=True)
    files = sorted(SOURCE.glob('*.svg'))
    for file in files:
        convert(file)
    print(f'Generated {len(files)} Android SF Symbols resources.')
