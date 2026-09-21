"""从 WebUI 保存配置到原生菜单分页，检查实际下发的布局和动作索引。"""
from pathlib import Path
import json
import subprocess
import sys
import tempfile

root = Path(__file__).resolve().parent.parent
binary = sys.argv[1]
for width, gap in [(120, 0), (120, 32), (196, 0), (196, 12), (196, 32), (360, 32)]:
    script = """
import {defaultConfig,serialize} from './module/webroot/model.js';
const c=defaultConfig();c.menu_width=WIDTH;c.menu_gap=GAP;
c.menu=Array.from({length:19},(_,i)=>({slot:i,name:'应用 '+i,icon:'',type:'app_freeform',argument:'com.layout.app'+i}));
c.menu.splice(1,0,{slot:19,name:'隐藏',icon:'',type:'none',argument:''});
c.menu.push({slot:20,name:'命令',icon:'',type:'shell',argument:'echo private-test-command'});
process.stdout.write(serialize(c));
""".replace('WIDTH', str(width)).replace('GAP', str(gap))
    with tempfile.TemporaryDirectory(prefix='menu-layout-', dir=root / 'build') as temporary:
        folder = Path(temporary)
        config = folder / 'input.conf'
        config.write_bytes(subprocess.check_output(['node', '--input-type=module', '-e', script], cwd=root))
        saved = json.loads(subprocess.check_output([binary, str(config), str(folder)], timeout=15))
        assert saved['menu_width'] == width and saved['menu_gap'] == gap
        output = subprocess.check_output([binary, '--menu-pages', str(folder)], timeout=15).decode('utf-8')
        pages = [json.loads(line) for line in output.splitlines()]
        assert len(pages) == 2 and pages[0]['next'] == 16 and pages[1]['next'] == -1
        assert all(p['layout'] == {'width': width, 'gap': gap} for p in pages)
        items = [item for page in pages for item in page['items']]
        assert [item['index'] for item in items] == list(range(20))
        assert items[1]['packageName'] == 'com.layout.app1' and items[-1]['type'] == 'shell'
        assert 'private-test-command' not in output and all(item['type'] != 'none' for item in items)
print('PASS: WebUI save -> atomic config -> native session pages, width 120/196/360 and gap 0/12/32, stable indices and private command isolation')
