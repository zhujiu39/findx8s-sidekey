import {api} from './bridge.js';
import {symbol} from './symbols.js';
// 浏览器交付使用明确的示例日志下载；不伪造 Android 文件选择器的保存成功。
export async function exportLogFile(onStatus) {
  onStatus('preparing');
  const text=await api('logs');
  const dialog=document.createElement('dialog');dialog.className='log-export-dialog';
  const header=document.createElement('header'),title=document.createElement('h2'),close=document.createElement('button');
  title.textContent='导出示例日志';title.id='export-demo-title';dialog.setAttribute('aria-labelledby',title.id);
  close.className='icon-button';close.setAttribute('aria-label','取消导出');close.append(symbol('xmark'));header.append(title,close);
  const body=document.createElement('div');body.className='options-body';
  const description=document.createElement('p');description.textContent='正式模块会打开 Android 文件选择器。此预览导出一份文本文件，包含当前示例诊断记录。';
  const download=document.createElement('button');download.className='primary';download.textContent='下载示例日志';
  body.append(description,download);dialog.append(header,body);document.body.append(dialog);dialog.showModal();onStatus('choosing');
  return new Promise(resolve=>{
    let started=false;
    download.onclick=()=>{
      onStatus('saving');
      const blob=new Blob(['侧键自定义 v1.2.0-test.2 · 预览日志\n\n',text],{type:'text/plain;charset=utf-8'});
      const url=URL.createObjectURL(blob),link=document.createElement('a');link.href=url;link.download='sidekey-v1.2.0-test.2-demo-logs.txt';link.click();
      setTimeout(()=>URL.revokeObjectURL(url),30000);started=true;dialog.close();
    };
    close.onclick=()=>dialog.close();
    dialog.addEventListener('close',()=>{dialog.remove();resolve({state:started?'saved':'cancelled',message:started?'已发起示例日志下载，请查看浏览器下载记录':'已取消导出'});},{once:true});
  });
}
