"""大菜单跨 WebUI、C 解析和原子文件保存的往返，避免仅扩展界面数量。"""
from pathlib import Path
import json, subprocess, sys, tempfile

root=Path(__file__).resolve().parent.parent
binary=sys.argv[1]
script="""import {defaultConfig,serialize} from './module/webroot/model.js';
const config=defaultConfig();
config.menu=Array.from({length:2048},(_,i)=>({slot:i,name:'应用 '+i,icon:'',type:'app_freeform',argument:'com.scale.app'+i}));
process.stdout.write(serialize(config));"""
with tempfile.TemporaryDirectory(prefix='config-scale-',dir=root/'build') as temporary:
    folder=Path(temporary)
    text=subprocess.check_output(['node','--input-type=module','-e',script],cwd=root)
    assert len(text.hex())>131072
    file=folder/'input.conf';file.write_bytes(text)
    data=json.loads(subprocess.check_output([binary,str(file),str(folder)],timeout=15))
    assert len(data['menu'])==2048 and data['menu'][2047]['argument']=='com.scale.app2047'
    assert data['menu'][1000]['name']=='应用 1000'
    assert (folder/'config.conf').exists()
    chunks=folder/'chunks.hex'; encoded=text.hex()
    chunks.write_text(''.join(encoded[i:i+12000]+'\n' for i in range(0,len(encoded),12000)),encoding='ascii',newline='\n')
    # Windows 的 rename 不覆盖已有文件；分块路径独立验证首次原子保存。
    chunk_folder=folder/'chunked'; chunk_folder.mkdir()
    chunked=json.loads(subprocess.check_output([binary,'--chunks',str(chunks),str(chunk_folder)],timeout=15))
    assert chunked==data
    original=(chunk_folder/'config.conf').read_bytes()
    chunks.write_bytes(chunks.read_bytes()[:-1])
    assert subprocess.run([binary,'--chunks',str(chunks),str(chunk_folder)],stdout=subprocess.DEVNULL,timeout=15).returncode!=0
    assert (chunk_folder/'config.conf').read_bytes()==original
    # 尾部重复字段仍必须拒绝，不能只解析大文件的前半段。
    file.write_bytes(text+b'menu_2047_name=61\n')
    assert subprocess.run([binary,str(file)],stdout=subprocess.DEVNULL,timeout=15).returncode!=0
print('PASS: 2048 apps, oversized command-line payload, WebUI/C/atomic-file round trip and duplicate-tail rejection')
