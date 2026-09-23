import {actions} from './model.js';
import {appCatalog, chooseApps} from './app-picker.js';
import {menuAppName} from './app-catalog.js';
import {isApp, applyAppSelection} from './app-selection.js';
import {appIcon, releaseAppIcons} from './app-icons.js';
import {chooseMijia, mijiaBindingLabel, mijiaBindingIsReading, editReadingLabels} from './mijia.js';
const $ = id => document.getElementById(id);
let entries = [], onChange = () => {}, isBusy = false;
const element = (tag, className, text) => {
  const node = document.createElement(tag); node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
};
function field(title, input) { const label = element('label','menu-field'); label.append(element('span','',title),input); return label; }
function changed() { entries.forEach((item, slot) => item.slot = slot); render(); onChange(); }
function controls(item, group) {
  const tools = element('div','menu-item-tools'), index = group.indexOf(item);
  for (const [text, delta, disabled] of [['↑',-1,index === 0],['↓',1,index === group.length - 1],['移除',0,false]]) {
    const button = element('button','secondary',text); button.type='button'; button.disabled=isBusy || disabled; button.dataset.unavailable=String(disabled);
    button.setAttribute('aria-label', (text === '↑' ? '上移' : text === '↓' ? '下移' : '移除') + ' ' + item.name);
    button.addEventListener('click',()=>{
      const position=entries.indexOf(item);
      if (!delta) entries.splice(position,1);
      else { const other=entries.indexOf(group[index+delta]); [entries[position],entries[other]]=[entries[other],entries[position]]; }
      changed();
    }); tools.append(button);
  }
  return tools;
}
function render() {
  const host=$('menu-items'); releaseAppIcons(host); host.replaceChildren();
  const apps=entries.filter(isApp), switches=entries.filter(item=>!isApp(item));
  const configured=switches.filter(item=>item.type!=='none');
  renderSummary(apps,configured);
  $('menu-empty').hidden=apps.length + configured.length !== 0;
  $('menu-count').textContent=apps.length + ' 个应用 · ' + configured.length + ' 个快捷项';
  switches.forEach((item,index)=>{
    const card=element('article','menu-item');
    const reading=item.type==='mijia' && mijiaBindingIsReading(item.argument);
    const heading=element('div','menu-item-heading'); heading.append(element('strong','',(reading ? '读数卡片 ' : '快捷开关 ')+(index+1)),controls(item,switches)); card.append(heading);
    const name=element('input',''); name.value=item.name; name.addEventListener('input',()=>{item.name=name.value;onChange();}); card.append(field('显示名称',name));
    const action=element('select',''); actions.filter(([id])=>!['menu','app','app_freeform'].includes(id)).forEach(([id,label])=>action.add(new Option(label,id)));
    action.value=item.type; card.append(field('项目类型',action));
    if (item.type==='mijia') {
      const choice=element('button','secondary',mijiaBindingLabel(item.argument));choice.type='button';
      choice.dataset.mijiaBinding=item.argument;
      choice.addEventListener('click',()=>chooseMijia(entry=>{item.argument=entry.id;item.name=entry.name;changed();},true));card.append(choice);
      const hint=element('p','hint','只读卡片：展开快捷栏时更新数值，不执行开关操作。');
      hint.dataset.readingHint='';hint.hidden=!reading;card.append(hint);
      const edit=element('button','secondary','编辑读数文案');edit.type='button';edit.dataset.readingEditor='';edit.hidden=!reading;
      edit.addEventListener('click',()=>editReadingLabels(item.argument));card.append(edit);
    }
    const parameter=element('textarea',''); parameter.value=item.argument; parameter.rows=2;
    const parameterField=field('动作参数',parameter); parameterField.hidden=!['shell','keycode'].includes(item.type); card.append(parameterField);
    action.addEventListener('change',()=>{item.type=action.value;item.argument='';changed();});
    if (item.type==='none') card.append(element('p','hint','未设置动作，快捷栏中不显示。'));
    parameter.addEventListener('input',()=>{item.argument=parameter.value;onChange();}); host.append(card);
  });
  if (apps.length) host.append(element('h3','selected-app-title','已选应用'));
  apps.forEach(item=>{
    const app=appCatalog.lookup(item.argument), label=app?.label || item.name;
    const row=element('article','selected-app-row'), info=element('div','selected-app');
    info.append(appIcon(item.argument,label),element('strong','',label)); row.append(info,controls(item,apps));
    host.append(row);
  });
  menuBusy(isBusy);
}
function renderSummary(apps, switches) {
  const host=$('shortcut-apps'); releaseAppIcons(host); host.replaceChildren();
  apps.slice(0,6).forEach(item=>{
    const label=appCatalog.lookup(item.argument)?.label || item.name;
    const icon=appIcon(item.argument,label); icon.title=label; host.append(icon);
  });
  if (apps.length>6) host.append(element('span','shortcut-more','+'+(apps.length-6)));
  $('shortcut-count').textContent=apps.length+' 个应用 · '+switches.length+' 个快捷项';
  $('shortcut-empty').hidden=apps.length+switches.length!==0;
}
export function initMenuEditor(change) {
  onChange=change;
  document.addEventListener('sidekey-mijia-bindings',()=>{
    $('menu-items').querySelectorAll('[data-mijia-binding]').forEach(item=>{
      item.textContent=mijiaBindingLabel(item.dataset.mijiaBinding);
      const card=item.closest('.menu-item'), reading=mijiaBindingIsReading(item.dataset.mijiaBinding);
      const heading=card.querySelector('.menu-item-heading > strong');
      heading.textContent=heading.textContent.replace(/^(?:读数卡片|快捷开关)/,reading?'读数卡片':'快捷开关');
      card.querySelector('[data-reading-hint]').hidden=!reading;
      card.querySelector('[data-reading-editor]').hidden=!reading;
    });
  });
  $('choose-menu-apps').addEventListener('click',()=>{
    if (isBusy) return;
    const selected=entries.filter(isApp).map(item=>appCatalog.lookup(item.argument) || {packageName:item.argument,label:item.name});
    chooseApps(selected,apps=>{entries=applyAppSelection(entries,apps);changed();});
  });
  $('add-menu-switch').addEventListener('click',()=>{
    if (isBusy || entries.length>=2060) return;
    entries.push({name:'新开关',icon:'',type:'none',argument:''}); changed();
  });
  ['menu-side','menu-position','menu-width','menu-gap'].forEach(id=>$(id).addEventListener('input',onChange));
  $('preview-menu').addEventListener('click',preview);
  $('menu-preview').addEventListener('click',event=>{if(event.target===$('menu-preview'))dismiss();});
  $('close-preview').addEventListener('click',dismiss);
  document.addEventListener('keydown',event=>{if(event.key==='Escape')dismiss();});
  document.addEventListener('sidekey-apps-updated',()=>{
    entries.forEach(item=>{if(isApp(item)){const app=appCatalog.lookup(item.argument);if(app)item.name=menuAppName(app.label);item.icon='';}});
    render();onChange();
  });
}
export function fillMenu(config) {
  entries=structuredClone(config.menu).map((item,index)=>({...item,slot:item.slot??index,type:isApp(item)?'app_freeform':item.type})).sort((a,b)=>a.slot-b.slot);
  $('menu-side').value=config.menu_side;$('menu-position').value=config.menu_position;
  $('menu-width').value=config.menu_width??196;$('menu-gap').value=config.menu_gap??12;render();
}
export function addMijiaEntry(entry) {
  if (isBusy || entries.length>=2060) throw new Error('快捷栏暂时无法添加，请稍后重试');
  entries.push({name:entry.name,icon:'',type:'mijia',argument:entry.id}); changed();
}
export function readMenu() { return {menu_side:$('menu-side').value,menu_position:Number($('menu-position').value),
  menu_width:Number($('menu-width').value),menu_gap:Number($('menu-gap').value),menu:structuredClone(entries)}; }
export function menuBusy(value) {
  isBusy=value;$('choose-menu-apps').disabled=value;
  $('add-menu-switch').disabled=value || entries.length>=2060;
  $('preview-menu').disabled=value || !entries.some(item=>item.type!=='none');
  $('preview-shortcuts').disabled=$('preview-menu').disabled;
  $('menu-position-output').value=$('menu-position').value+'%';
  $('menu-width-output').value=$('menu-width').value+' dp';
  $('menu-gap-output').value=$('menu-gap').value+' dp';
  $('menu-items').querySelectorAll('input,select,textarea').forEach(node=>node.disabled=value);
  $('menu-items').querySelectorAll('button').forEach(node=>node.disabled=value || node.dataset.unavailable==='true');
}
let lastFocus,dismissTimer;
function positionPreview() {
  const overlay=$('menu-preview'),panel=$('menu-preview-panel');if(overlay.hidden)return;
  const style=getComputedStyle(overlay),top=parseFloat(style.paddingTop)||0,bottom=parseFloat(style.paddingBottom)||0;
  const height=overlay.clientHeight-top-bottom,half=panel.offsetHeight/2;
  panel.style.top=(top+Math.max(half+12,Math.min(height-half-12,height*Number($('menu-position').value)/100)))+'px';
}
window.addEventListener('resize',positionPreview);window.visualViewport?.addEventListener('resize',positionPreview);
function preview() {
  if (!entries.some(item=>item.type!=='none')) return;
  const overlay=$('menu-preview');lastFocus=document.activeElement;clearTimeout(dismissTimer);
  overlay.classList.toggle('from-right',$('menu-side').value==='right');
  overlay.style.setProperty('--sidebar-width',$('menu-width').value+'px');
  overlay.style.setProperty('--app-gap',$('menu-gap').value+'px');
  releaseAppIcons($('menu-preview-items'));$('menu-preview-items').replaceChildren();$('menu-preview-switches').replaceChildren();
  const switches=entries.filter(item=>!isApp(item)&&item.type!=='none');
  for(const item of switches) {
    const row=element('button','sidebar-switch');row.type='button';
    const reading=item.type==='mijia' && mijiaBindingIsReading(item.argument);
    row.append(element('span','',item.name),element('span','menu-switch-glyph',reading?'读数':item.type==='torch'?'—':'›'));
    $('menu-preview-switches').append(row);
  }
  const apps=entries.filter(isApp);
  apps.forEach(item=>{
    const app=appCatalog.lookup(item.argument),label=app?.label||item.name;
    const row=element('button','sidebar-app');row.type='button';row.append(appIcon(item.argument,label,'menu-real-icon'),element('span','menu-tile-label',label));
    row.addEventListener('click',dismiss);$('menu-preview-items').append(row);
  });
  overlay.hidden=false;positionPreview();document.querySelector('main').inert=true;document.querySelector('.save-bar').inert=true;
  requestAnimationFrame(()=>requestAnimationFrame(()=>overlay.classList.add('visible')));$('close-preview').focus();
}
function dismiss() {
  const overlay=$('menu-preview');if(overlay.hidden)return;clearTimeout(dismissTimer);overlay.classList.remove('visible');
  dismissTimer=setTimeout(()=>{overlay.hidden=true;document.querySelector('main').inert=false;document.querySelector('.save-bar').inert=false;lastFocus?.focus();},200);
}
