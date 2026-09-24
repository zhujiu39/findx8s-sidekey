import {hydrateSymbols,symbol} from './symbols.js';
import {setScenario,resetDemo,demoState} from './demo-bridge.js';
const $=id=>document.getElementById(id);
hydrateSymbols();
const pages={gestures:['侧键自定义','一按，即达。','把顺手的操作，交给侧边的那颗按键。'],
  menu:['快捷栏','常用的，都在手边。','把应用与控制放在一起，按自己的习惯排列。'],
  mijia:['米家','家的状态，一眼可见。','读数、设备和场景，都能从侧键抵达。'],
  settings:['设置','调成你的手感。','从按压节奏到界面外观，每一处都恰到好处。']};
document.addEventListener('sidekey-page',event=>{
  const [eyebrow,title,description]=pages[event.detail]||pages.gestures;
  $('page-eyebrow').textContent=eyebrow;$('page-title').textContent=title;$('page-description').textContent=description;
});
$('demo-scenario').addEventListener('change',()=>{
  setScenario($('demo-scenario').value);$('refresh').click();
  if(!$('page-mijia').hidden)$('mijia-refresh').click();
});
$('demo-options').addEventListener('click',()=>$('preview-options').showModal());
$('demo-reset').addEventListener('click',()=>resetDemo());
$('demo-empty').addEventListener('click',()=>resetDemo(true));
try{$('reduce-motion').checked=localStorage.getItem('sidekey.apple-preview.reduce-motion')==='true';}catch{}
const applyMotion=()=>{document.documentElement.dataset.reduceMotion=String($('reduce-motion').checked);try{localStorage.setItem('sidekey.apple-preview.reduce-motion',String($('reduce-motion').checked));}catch{}};
applyMotion();$('reduce-motion').addEventListener('change',applyMotion);
const multiple=document.createElement('option');multiple.value='quota-multiple';multiple.textContent='多个订阅 / 用量缺失';$('demo-scenario').append(multiple);
// 参数变化只更新本地示例；底部保存区仍由原模块的配置校验驱动。
document.addEventListener('demo-state',()=>{$('demo-scenario').value=demoState().scenario;});
function orientation(){document.querySelector('.page-tabs').setAttribute('aria-orientation',innerWidth>680?'vertical':'horizontal');}
orientation();window.addEventListener('resize',orientation);
document.querySelector('.page-tabs').addEventListener('keydown',event=>{
  if(innerWidth<=680||!['ArrowUp','ArrowDown'].includes(event.key))return;
  event.preventDefault();const tabs=[...document.querySelectorAll('.page-tabs [role=tab]')],index=tabs.indexOf(document.activeElement);
  const next=tabs[(index+(event.key==='ArrowDown'?1:tabs.length-1))%tabs.length];next.click();next.focus();
});
