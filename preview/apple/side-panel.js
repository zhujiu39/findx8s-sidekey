import {symbol, actionSymbols} from './symbols.js';
import {appIcon} from './app-icons.js';
import {surfingFields, surfingText, surfingMask} from './model.js';
import {demoState, runMenuItem, readingFor} from './demo-bridge.js';

const $=id=>document.getElementById(id);
const el=(tag,cls='',text)=>{const node=document.createElement(tag);node.className=cls;if(text!==undefined)node.textContent=text;return node;};
let draft, dialogConfig, timer, lastFocus, closeTimer;
let modalQuotaRead=false,liveQuotaRead=false;
const pending=new Set();
function message(text) {
  const toast=$('toast');toast.textContent=text;toast.hidden=false;
  clearTimeout(message.timer);message.timer=setTimeout(()=>toast.hidden=true,3300);
}
function stateFor(item,state) {
  if(item.type==='surfing') {
    if(state.scenario==='surfing-missing')return {text:'未安装 Surfing',disabled:true};
    if(state.scenario==='unknown')return {text:'状态未知',disabled:true};
    return {text:state.surfing?'已开启':'已关闭',on:state.surfing};
  }
  if(item.type==='torch') {
    if(state.scenario==='torch-unavailable')return {text:'相机占用或系统限制',disabled:true};
    if(state.scenario==='unknown')return {text:'状态未知',disabled:true};
    return {text:state.torch?'已开启 · 最高亮度':'已关闭',on:state.torch};
  }
  if(item.type==='mijia') {
    const binding=state.bindings.find(b=>b.id===item.argument);
    if(!binding)return {text:'绑定已失效，请重新选择',disabled:true};
    if(binding.kind==='scene')return {text:'手动场景',action:true};
    if(state.scenario==='offline'||binding.action?.did==='plug1')return {text:'设备离线',disabled:true};
    if(state.scenario==='unknown')return {text:'状态未知',disabled:true};
    if(binding.kind==='action'||binding.kind==='set'&&typeof binding.action?.value!=='boolean')return {text:'在线 · 执行动作',action:true};
    return {text:state.lamp?'已开启 · 在线':'已关闭 · 在线',on:state.lamp};
  }
  return {text:'轻触执行',action:true};
}
function control(item,state,interactive) {
  const current=stateFor(item,state),button=el('button','panel-control');button.type='button';
  const name=el('span','panel-name');name.append(el('strong','',item.name),el('small','',pending.has(item.type)?'操作中…':current.text));
  const power=el('span','power-button'+(pending.has(item.type)?' waiting':''));
  power.append(symbol(current.action?'chevron.right':current.disabled?'minus':'power'));
  button.append(symbol(actionSymbols[item.type]||'app.fill'),name,power);
  button.classList.toggle('is-on',!!current.on);button.disabled=!!current.disabled||pending.has(item.type);
  if(!interactive)button.tabIndex=-1;
  button.setAttribute('aria-label',`${item.name}，${current.text}`);
  if(!current.action&&!current.disabled)button.setAttribute('aria-pressed',String(!!current.on));
  button.addEventListener('click',async()=>{
    if(pending.has(item.type))return;
    pending.add(item.type);refreshPanels();
    try { await runMenuItem(item);if(current.action)message('已模拟执行：'+item.name); }
    catch(error){message(error.message);}
    finally{pending.delete(item.type);refreshPanels();}
  });
  return button;
}
function reading(item,state) {
  const card=el('article','panel-card reading-card');
  const offline=state.scenario==='offline',unknown=state.scenario==='unknown';
  card.classList.toggle('offline',offline||unknown);
  const title=el('div','reading-heading');title.append(symbol('thermometer.medium'),el('span','',item.name));
  card.append(title,el('p','reading-meta',`${offline?'离线':unknown?'状态未知':'在线'} · 云端上报值`));
  const values=el('div','reading-values');
  for(const entry of readingFor(item.argument)){
    const cell=el('div'),number=el('strong','',entry.value);number.append(el('small','',entry.unit));cell.append(number);
    if(entry.label)cell.append(el('p','',entry.label));values.append(cell);
  }
  card.append(values);return card;
}
function metricValue(index,state) {
  if(!state.surfing||['unknown','surfing-missing'].includes(state.scenario))return '—';
  const values=['18.6 KB/s','1.2 MB/s','1.8 GB','12.4 GB'];
  if(index===0)values[0]=(18.6+Math.sin(Date.now()/2500)*2).toFixed(1)+' KB/s';
  return values[index];
}
function surfing(item,state,interactive,hasQuota) {
  const card=el('article','panel-card surfing-card');card.append(control(item,state,interactive));
  const labels=surfingText(item),mask=surfingMask(item),grid=el('div','traffic-grid');
  for(const [index,[key,,bit]] of surfingFields.slice(0,4).entries()){
    if(!(mask&bit))continue;const cell=el('div');
    if(labels[key])cell.append(el('span','',labels[key]));
    const number=el('strong','',metricValue(index,state));number.dataset.metric=String(index);cell.append(number);grid.append(cell);
  }
  if(mask&15){card.append(grid);if(labels.note)card.append(el('p','traffic-note',labels.note));}
  if(mask&16){
    const quota=el('section','subscription');if(labels.quota)quota.append(el('h4','',labels.quota));
    let issue='';
    if(state.scenario==='quota-empty')issue='订阅未提供用量';
    else if(state.scenario==='quota-error')issue='读取失败，请查看日志';
    else if(['unknown','surfing-missing'].includes(state.scenario)||!state.surfing&&!hasQuota)issue='开启后读取订阅用量';
    if(issue)quota.append(el('p','',issue));
    else{
      if(!state.surfing)quota.append(el('small','','上次读取'));
      const subscriptions=state.scenario==='quota-multiple'?[['日常订阅','28.4 GB','100 GB'],['备用订阅','未提供','50 GB']]:[['','28.4 GB','100 GB']];
      for(const [name,used,total] of subscriptions){
        if(name)quota.append(el('h4','',name));
        quota.append(el('p','',(labels.used?labels.used+'：':'')+used),el('p','',(labels.total?labels.total+'：':'')+total));
        if(used!=='未提供'){const progress=el('div','quota-progress');progress.append(el('span'));quota.append(progress);}
        quota.append(el('small','','示例订阅元数据 · 更新 09:40'));
      }
    }
    card.append(quota);
  }
  return card;
}
function render(host,config,interactive=false) {
  if(!host||!config)return;const scrollTop=host.scrollTop;
  const state=demoState();const entries=config.menu.filter(item=>item.type!=='none');
  host.replaceChildren();host.classList.toggle('from-left',config.menu_side==='left');host.classList.toggle('compact',config.menu_width<195);
  host.style.setProperty('--sidebar-width',(config.menu_width??196)+'px');host.style.setProperty('--app-gap',(config.menu_gap??12)+'px');
  const grip=el('button','panel-grip');grip.setAttribute('aria-label',interactive?'收起快捷栏':'打开快捷栏预览');grip.onclick=()=>interactive?closeMenu():openMenuPreview(draft);host.append(grip);
  if(!entries.length){host.append(el('p','empty-panel','添加项目后，它们会出现在这里。'));return;}
  if(state.surfing&&state.scenario!=='surfing-missing'&&entries.some(item=>item.type==='surfing'&&(surfingMask(item)&16))){if(interactive)modalQuotaRead=true;else liveQuotaRead=true;}
  const stack=el('div','panel-stack');host.append(stack);
  for(const item of entries.filter(item=>!['app','app_freeform'].includes(item.type))){
    const binding=state.bindings.find(b=>b.id===item.argument);
    if(item.type==='mijia'&&binding?.kind==='read')stack.append(reading(item,state));
    else if(item.type==='surfing')stack.append(surfing(item,state,interactive,interactive?modalQuotaRead:liveQuotaRead));
    else {const card=el('article','panel-card');card.append(control(item,state,interactive));stack.append(card);}
  }
  const apps=entries.filter(item=>['app','app_freeform'].includes(item.type));
  if(apps.length){const grid=el('div','panel-apps');
    for(const item of apps){const button=el('button','panel-app');button.type='button';button.append(appIcon(item.argument,item.name),el('span','',item.name));
      button.setAttribute('aria-label',`小窗打开 ${item.name}`);
      button.onclick=()=>{if(!interactive){openMenuPreview(draft);return;}showApp(item);};grid.append(button);}
    host.append(grid);
  }
  host.append(el('p','panel-footnote','示例数据 · 展开时更新'));
  host.scrollTop=scrollTop;requestAnimationFrame(()=>position(host,config,interactive));
}
function position(host,config,interactive) {
  const height=interactive?window.innerHeight:host.parentElement.clientHeight;
  const top=interactive?92:48,bottom=interactive?20:22;
  const half=host.offsetHeight/2;
  const y=Math.max(top+half,Math.min(height-bottom-half,height*(config.menu_position??35)/100));
  host.style.top=y+'px';
}
export function renderLiveMenu(config) {draft=structuredClone(config);render($('live-menu'),draft);syncTimer();}
export function openMenuPreview(config) {
  if(!config?.menu.some(item=>item.type!=='none')){message('先添加一个快捷项目');return;}
  clearTimeout(closeTimer);$('menu-preview').classList.remove('is-closing');
  dialogConfig=structuredClone(config);modalQuotaRead=false;lastFocus=document.activeElement;
  $('app-window').hidden=true;$('menu-preview-panel').hidden=false;
  if(!$('menu-preview').open)$('menu-preview').showModal();
  render($('menu-preview-panel'),dialogConfig,true);$('close-preview').focus();syncTimer();
}
function closeMenu() {
  if($('menu-preview').classList.contains('is-closing'))return;
  $('menu-preview').classList.add('is-closing');
  const reduce=matchMedia('(prefers-reduced-motion: reduce)').matches||document.documentElement.dataset.reduceMotion==='true';
  closeTimer=setTimeout(()=>{$('menu-preview').close();$('menu-preview').classList.remove('is-closing');dialogConfig=null;modalQuotaRead=false;syncTimer();lastFocus?.focus();},reduce?0:180);
}
function showApp(item){
  $('menu-preview-panel').hidden=true;$('app-window').hidden=false;
  $('app-window-name').textContent=item.name;$('app-window-icon').replaceChildren(appIcon(item.argument,item.name));
  $('close-app-window').focus();runMenuItem(item).catch(error=>message(error.message));syncTimer();
}
function refreshPanels(){if(draft)render($('live-menu'),draft);if($('menu-preview').open&&dialogConfig&&!$('menu-preview-panel').hidden)render($('menu-preview-panel'),dialogConfig,true);}
function syncTimer(){
  clearInterval(timer);timer=null;
  if(document.hidden)return;
  const liveVisible=$('live-menu')?.offsetParent!==null;
  const modalVisible=$('menu-preview').open&&!$('menu-preview-panel').hidden;
  if(!liveVisible&&!modalVisible)return;
  timer=setInterval(()=>{const state=demoState();document.querySelectorAll('.side-panel [data-metric]').forEach(node=>{if(node.closest('.side-panel').offsetParent!==null)node.textContent=metricValue(+node.dataset.metric,state);});},1000);
}
document.addEventListener('demo-state',refreshPanels);
document.addEventListener('visibilitychange',syncTimer);
window.addEventListener('resize',()=>{refreshPanels();syncTimer();});
$('close-preview').addEventListener('click',closeMenu);
$('close-app-window').addEventListener('click',closeMenu);
$('menu-preview').addEventListener('click',event=>{if(event.target===$('menu-preview'))closeMenu();});
$('menu-preview').addEventListener('cancel',event=>{event.preventDefault();closeMenu();});
$('menu-preview').addEventListener('close',()=>{dialogConfig=null;modalQuotaRead=false;syncTimer();lastFocus?.focus();});
