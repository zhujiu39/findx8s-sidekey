import {actions, surfingFields, surfingTextDefaults, surfingText, surfingMask} from './model.js';
import {appCatalog, chooseApps} from './app-picker.js';
import {menuAppName} from './app-catalog.js';
import {isApp, applyAppSelection} from './app-selection.js';
import {appIcon, releaseAppIcons, requestAppIcons} from './app-icons.js';
import {chooseMijia, mijiaBindingLabel, mijiaBindingIsReading, editReadingLabels} from './mijia.js';
import {symbol, actionSymbols} from './symbols.js';
import {decorateActionSelect} from './action-picker.js';
import {openMenuPreview, renderLiveMenu} from './side-panel.js';
const $ = id => document.getElementById(id);
let entries = [], onChange = () => {}, isBusy = false;
let expandedItem;
const element = (tag, className, text) => {
  const node = document.createElement(tag); node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
};
function field(title, input) { const label = element('label','menu-field'); label.append(element('span','',title),input); return label; }
function surfingEditor(item, card) {
  const options=element('fieldset','surfing-options');options.append(element('legend','','显示项目'));
  const textFields=element('div','surfing-text-fields'), text=surfingText(item), inputs=new Map();
  function sync() {
    const mask=surfingMask(item);
    for (const [key,,bit] of surfingFields) inputs.get(key).hidden=!(mask & bit);
    inputs.get('note').hidden=!(mask & 15);
    inputs.get('used').hidden=inputs.get('total').hidden=!(mask & 16);
  }
  for (const [key, title, bit] of surfingFields) {
    const check=element('input','');check.type='checkbox';check.setAttribute('role','switch');check.checked=Boolean(surfingMask(item) & bit);
    const toggle=element('span','switch');toggle.append(check,element('span',''));
    const label=element('label','surfing-option');label.append(element('span','',title),toggle);options.append(label);
    check.addEventListener('change',()=>{item.surfing_fields=check.checked ? surfingMask(item)|bit : surfingMask(item)&~bit;sync();onChange();});
  }
  const titles={up:'上传速率文案',down:'下载速率文案',upload:'上传流量文案',download:'下载流量文案',note:'统计说明',quota:'订阅标题',used:'已用流量文案',total:'总流量文案'};
  for (const [key,title] of Object.entries(titles)) {
    const input=element('input','');input.type='text';input.value=text[key];input.maxLength=60;
    input.placeholder=surfingTextDefaults[key];
    input.addEventListener('input',()=>{item.surfing_text={...surfingText(item),[key]:input.value};onChange();});
    const label=field(title,input);inputs.set(key,label);textFields.append(label);
  }
  const reset=element('button','secondary','恢复默认文案');reset.type='button';
  reset.addEventListener('click',()=>{item.surfing_text={...surfingTextDefaults};changed();});
  sync();card.append(options,textFields,element('p','hint','只显示勾选项。文案留空时只显示数值；统计说明留空则隐藏。每项最多 20 个汉字或 60 个英文字母，保存设置后生效。'),reset);
}
function changed() { entries.forEach((item, slot) => item.slot = slot); render(); onChange(); }
function controls(item, group) {
  const tools = element('div','menu-item-tools'), index = group.indexOf(item);
  for (const [text, delta, disabled] of [['↑',-1,index === 0],['↓',1,index === group.length - 1],['移除',0,false]]) {
    const button = element('button','secondary',text); button.type='button'; button.disabled=isBusy || disabled; button.dataset.unavailable=String(disabled);
    button.setAttribute('aria-label', (text === '↑' ? '上移' : text === '↓' ? '下移' : '移除') + ' ' + item.name);
    button.replaceChildren(symbol(delta === -1 ? 'arrow.up' : delta === 1 ? 'arrow.down' : 'trash'));
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
  $('menu-empty').hidden=entries.length !== 0;
  $('menu-count').textContent=apps.length + ' 个应用 · ' + configured.length + ' 个快捷项';
  switches.forEach((item,index)=>{
    const section=element('details','menu-item'); section.open=expandedItem===item || item.type==='none';
    section.addEventListener('toggle',()=>{if(section.open)expandedItem=item;else if(expandedItem===item)expandedItem=undefined;});
    const reading=item.type==='mijia' && mijiaBindingIsReading(item.argument);
    const summary=element('summary','menu-item-summary'), mark=element('span','colored-icon small '+(item.type==='mijia'?'orange':item.type==='surfing'?'purple':'blue'));
    mark.append(symbol(reading?'thermometer.medium':actionSymbols[item.type]));
    const copy=element('span','item-copy'), title=element('strong','',item.name);
    copy.append(title,element('small','',reading?'只读数据':item.type==='surfing'?'代理开关、速率与用量':actions.find(([type])=>type===item.type)?.[1] || '选择操作'));
    summary.append(mark,copy,symbol('chevron.right'));
    const card=element('div','menu-item-detail'); section.append(summary,card);
    const heading=element('div','menu-item-heading'); heading.append(element('strong','',(reading ? '读数卡片 ' : '快捷开关 ')+(index+1)),controls(item,switches)); card.append(heading);
    const name=element('input',''); name.value=item.name; name.addEventListener('input',()=>{item.name=name.value;title.textContent=item.name;onChange();}); card.append(field('显示名称',name));
    const action=element('select',''); actions.filter(([id])=>!['menu','app','app_freeform'].includes(id)).forEach(([id,label])=>action.add(new Option(label,id)));
    action.value=item.type; card.append(field('项目类型',decorateActionSelect(action)));
    if (item.type==='surfing') surfingEditor(item,card);
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
    action.addEventListener('change',()=>{item.type=action.value;item.argument='';if(item.type==='surfing' && item.name==='新开关')item.name='Surfing 代理';changed();});
    if (item.type==='none') card.append(element('p','hint','未设置动作，快捷栏中不显示。'));
    parameter.addEventListener('input',()=>{item.argument=parameter.value;onChange();}); host.append(section);
  });
  if (apps.length) host.append(element('h3','selected-app-title','已选应用'));
  apps.forEach(item=>{
    const app=appCatalog.lookup(item.argument), label=app?.label || item.name;
    const row=element('article','selected-app-row'), info=element('div','selected-app');
    info.append(appIcon(item.argument,label),element('strong','',label)); row.append(info,controls(item,apps));
    host.append(row);
  });
  menuBusy(isBusy);
  requestAppIcons(host);
  renderLiveMenu(readMenu());
}
function renderSummary(apps, switches) {
  const host=$('shortcut-apps'); releaseAppIcons(host); host.replaceChildren();
  apps.slice(0,6).forEach(item=>{
    const label=appCatalog.lookup(item.argument)?.label || item.name;
    const icon=appIcon(item.argument,label); icon.title=label; host.append(icon);
  });
  if (apps.length>6) host.append(element('span','shortcut-more','+'+(apps.length-6)));
  requestAppIcons(host);
  $('shortcut-count').textContent=apps.length+' 个应用 · '+switches.length+' 个快捷项';
  $('shortcut-empty').hidden=apps.length+switches.length!==0;
}
export function initMenuEditor(change) {
  onChange=()=>{change();renderLiveMenu(readMenu());};
  document.addEventListener('sidekey-icons-failed',event=>{
    const hint=$('app-icon-message');
    hint.textContent=`部分图标暂不可读：${String(event.detail?.message || '读取失败').slice(0,160)}。可在设置页刷新应用列表，原有配置会保留。`;
    hint.hidden=false;
  });
  document.addEventListener('sidekey-mijia-bindings',()=>{
    $('menu-items').querySelectorAll('[data-mijia-binding]').forEach(item=>{
      item.textContent=mijiaBindingLabel(item.dataset.mijiaBinding);
      const card=item.closest('.menu-item'), reading=mijiaBindingIsReading(item.dataset.mijiaBinding);
      const heading=card.querySelector('.menu-item-heading > strong');
      heading.textContent=heading.textContent.replace(/^(?:读数卡片|快捷开关)/,reading?'读数卡片':'快捷开关');
      card.querySelector('[data-reading-hint]').hidden=!reading;
      card.querySelector('[data-reading-editor]').hidden=!reading;
      card.querySelector('.item-copy small').textContent=reading?'只读数据':'设备或场景动作';
      card.querySelector('.menu-item-summary .colored-icon').replaceChildren(symbol(reading?'thermometer.medium':'house.fill'));
    });
    renderLiveMenu(readMenu());
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
  $('add-menu-mijia').addEventListener('click',()=>{
    if (isBusy || entries.length>=2060) return;
    chooseMijia(addMijiaEntry,true);
  });
  $('add-surfing').addEventListener('click',()=>{
    if (isBusy || entries.length>=2060 || entries.some(item=>item.type==='surfing')) return;
    const item={name:'Surfing 代理',icon:'',type:'surfing',argument:''};
    entries.push(item); expandedItem=item; changed();
  });
  ['menu-side','menu-position','menu-width','menu-gap'].forEach(id=>$(id).addEventListener('input',onChange));
  $('preview-menu').addEventListener('click',preview);
  $('expand-preview').addEventListener('click',preview);
  document.addEventListener('sidekey-apps-updated',()=>{
    $('app-icon-message').hidden=true;
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
  $('add-menu-mijia').disabled=value || entries.length>=2060;
  $('add-menu-switch').disabled=value || entries.length>=2060;
  $('add-surfing').disabled=value || entries.length>=2060 || entries.some(item=>item.type==='surfing');
  $('preview-menu').disabled=value || !entries.some(item=>item.type!=='none');
  $('preview-shortcuts').disabled=$('preview-menu').disabled;
  $('expand-preview').disabled=$('preview-menu').disabled;
  $('menu-position-output').value=$('menu-position').value+'%';
  $('menu-width-output').value=$('menu-width').value+' dp';
  $('menu-gap-output').value=$('menu-gap').value+' dp';
  $('menu-items').querySelectorAll('input,select,textarea').forEach(node=>node.disabled=value);
  $('menu-items').querySelectorAll('button').forEach(node=>node.disabled=value || node.dataset.unavailable==='true');
}
function preview() { openMenuPreview(readMenu()); }
