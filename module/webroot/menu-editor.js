import {actions} from './model.js';
import {appCatalog, createAppChoice} from './app-picker.js';
import {selectionName} from './app-catalog.js';
const $ = id => document.getElementById(id);
let entries = [], onChange = () => {}, isBusy = false;
const needsArgument = type => ['app', 'app_freeform', 'keycode', 'shell'].includes(type);
function element(tag, className, text) {
  const node = document.createElement(tag); node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}
function field(label, input) {
  const node = element('label', 'menu-field'); node.append(element('span', '', label), input); return node;
}
function render() {
  $('menu-items').replaceChildren(); $('menu-empty').hidden = entries.length !== 0;
  entries.forEach((item, index) => {
    const card = element('article', 'menu-item');
    const slotLabel = item.slot < 8 ? `应用卡片 ${item.slot + 1}` : `快捷开关 ${item.slot - 7}`;
    const heading = element('div', 'menu-item-heading'); heading.append(element('strong', '', slotLabel));
    const tools = element('div', 'menu-item-tools');
    [['↑', '上移', index > 0 && (entries[index - 1].slot < 8) === (item.slot < 8), -1], ['↓', '下移', index < entries.length - 1 && (entries[index + 1].slot < 8) === (item.slot < 8), 1], ['删除', '删除', true, 0]].forEach(([text, title, allowed, direction]) => {
      const button = element('button', 'secondary', text); button.type = 'button'; button.title = title;
      button.setAttribute('aria-label', `${title}捷径 ${index + 1}`); button.disabled = isBusy || !allowed;
      button.addEventListener('click', () => {
        if (direction) {
          [entries[index].slot, entries[index + direction].slot] = [entries[index + direction].slot, entries[index].slot];
          entries.sort((a,b) => a.slot - b.slot);
        }
        else entries.splice(index, 1);
        render(); onChange();
      }); tools.append(button);
    });
    heading.append(tools); card.append(heading);
    const position = element('select', '');
    for (let slot = item.slot < 8 ? 0 : 8; slot < (item.slot < 8 ? 8 : 12); slot++) {
      const option = new Option(slot < 8 ? `APP ${String(slot + 1).padStart(2,'0')}` : `开关 ${String(slot - 7).padStart(2,'0')}`, String(slot));
      option.disabled = entries.some(other => other !== item && other.slot === slot); position.add(option);
    }
    position.value = String(item.slot);
    position.addEventListener('change', () => { item.slot = Number(position.value); entries.sort((a,b) => a.slot - b.slot); render(); onChange(); });
    card.append(field('面板位置', position));
    const fields = element('div', 'menu-label-fields');
    let nameInput;
    for (const [key, label, placeholder] of [['icon','图标 / emoji','可留空'], ['name','名称','输入捷径名称']]) {
      const input = element('input', ''); input.value = item[key]; input.placeholder = placeholder;
      input.autocomplete = 'off'; input.addEventListener('input', () => { item[key] = input.value; onChange(); });
      if (key === 'name') nameInput = input;
      fields.append(field(label, input));
    }
    card.append(fields);
    const select = element('select', ''); actions.filter(([id]) => id !== 'menu').forEach(([id, name]) => select.add(new Option(name, id)));
    select.value = item.type; card.append(field('执行动作', select));
    const argument = element('textarea', ''); argument.value = item.argument; argument.rows = 2; argument.spellcheck = false;
    const argumentField = field('动作参数', argument); card.append(argumentField);
    const appChoice = createAppChoice(() => item.argument, app => {
      item.name = selectionName(item.name, item.argument, app, packageName => appCatalog.lookup(packageName));
      item.argument = app.packageName; nameInput.value = item.name; onChange();
    }, `${slotLabel}应用`);
    const appField = field('应用', appChoice); card.append(appField);
    function updateArgument() {
      const isApp = ['app','app_freeform'].includes(item.type);
      appField.hidden = !isApp; appChoice.refreshAppChoice();
      argumentField.hidden = !needsArgument(item.type) || isApp;
      argument.placeholder = item.type === 'shell' ? '以 Root 执行的 Shell 命令' : 'Android 按键码，如 3';
    }
    select.addEventListener('change', () => { item.type = select.value; item.argument = ''; argument.value = ''; updateArgument(); onChange(); });
    argument.addEventListener('input', () => { item.argument = argument.value; onChange(); });
    updateArgument(); $('menu-items').append(card);
  });
  $('menu-count').textContent = `${entries.length} / 12 项`;
  $('add-menu-item').disabled = isBusy || entries.filter(item => item.slot < 8).length >= 8;
  $('add-menu-switch').disabled = isBusy || entries.filter(item => item.slot >= 8).length >= 4;
}
export function initMenuEditor(change) {
  onChange = change;
  function add(start, end) {
    if (isBusy) return;
    let slot = start; while (slot < end && entries.some(item => item.slot === slot)) slot++;
    if (slot === end) return;
    entries.push({slot, name: slot < 8 ? '新应用' : '新开关', icon: '', type: slot < 8 ? 'app_freeform' : 'none', argument: ''});
    entries.sort((a,b) => a.slot - b.slot); render(); onChange();
  }
  $('add-menu-item').addEventListener('click', () => add(0,8));
  $('add-menu-switch').addEventListener('click', () => add(8,12));
  ['menu-side','menu-position'].forEach(id => $(id).addEventListener('input', onChange));
  $('preview-menu').addEventListener('click', preview);
  $('menu-preview').addEventListener('click', event => { if (event.target === $('menu-preview')) dismiss(); });
  $('close-preview').addEventListener('click', dismiss);
  document.addEventListener('keydown', event => { if (event.key === 'Escape') dismiss(); });
}
export function fillMenu(config) {
  entries = structuredClone(config.menu).map((item,index)=>({...item,slot:item.slot ?? index})).sort((a,b)=>a.slot-b.slot); $('menu-side').value = config.menu_side;
  $('menu-position').value = config.menu_position; render();
}
export function readMenu() {
  return {menu_side: $('menu-side').value, menu_position: Number($('menu-position').value), menu: structuredClone(entries)};
}
export function menuBusy(value) {
  isBusy = value; $('add-menu-item').disabled = value || entries.filter(item=>item.slot < 8).length >= 8;
  $('add-menu-switch').disabled = value || entries.filter(item=>item.slot >= 8).length >= 4;
  $('preview-menu').disabled = value;
  $('menu-position-output').value = `${$('menu-position').value}%`;
  $('menu-items').querySelectorAll('.app-choice').forEach(button => { button.disabled = value; });
  $('menu-items').querySelectorAll('.menu-item-tools button').forEach((button, index) => {
    const itemIndex = Math.floor(index / 3), tool = index % 3;
    button.disabled = value || (tool === 0 && (itemIndex === 0 || (entries[itemIndex - 1].slot < 8) !== (entries[itemIndex].slot < 8))) || (tool === 1 && (itemIndex === entries.length - 1 || (entries[itemIndex + 1].slot < 8) !== (entries[itemIndex].slot < 8)));
  });
}
let lastFocus, dismissTimer;
function positionPreview() {
  const overlay = $('menu-preview'), panel = $('menu-preview-panel');
  if (overlay.hidden) return;
  const style = getComputedStyle(overlay);
  const safeTop = parseFloat(style.paddingTop) || 0, safeBottom = parseFloat(style.paddingBottom) || 0;
  const height = overlay.clientHeight - safeTop - safeBottom, half = panel.offsetHeight / 2;
  panel.style.top = `${safeTop + Math.max(half + 12, Math.min(height - half - 12, height * Number($('menu-position').value) / 100))}px`;
}
window.addEventListener('resize', positionPreview);
window.visualViewport?.addEventListener('resize', positionPreview);
function preview() {
  const overlay = $('menu-preview'), panel = $('menu-preview-panel'); lastFocus = document.activeElement;
  overlay.classList.toggle('from-right', $('menu-side').value === 'right');
  clearTimeout(dismissTimer);
  $('menu-preview-items').replaceChildren(); $('menu-preview-switches').replaceChildren();
  for (let slot = 0; slot < 12; slot++) {
    const item = entries.find(entry=>entry.slot === slot), configured = item && item.type !== 'none';
    const row = element('button', 'menu-tile' + (configured ? '' : ' unconfigured')); row.type = 'button';
    const label = item?.name || (slot < 8 ? `APP ${String(slot + 1).padStart(2,'0')}` : `开关 ${String(slot - 7).padStart(2,'0')}`);
    if (slot < 8) {
      const icon = element('span', item?.icon ? 'menu-app-icon' : 'menu-app-glyph', item?.icon || undefined);
      if (!item?.icon) for(let square=0;square<4;square++) icon.append(element('i',''));
      row.append(icon);
    }
    row.append(element('span','menu-tile-label',label));
    if (slot >= 8) {
      const control = element('span', 'menu-switch-glyph' + (configured ? ' unknown' : ''), configured ? (item.type === 'torch' ? '—' : '›') : '');
      if (!configured) control.append(element('i','')); row.append(control);
    }
    row.disabled = !configured; row.addEventListener('click', dismiss);
    $(slot < 8 ? 'menu-preview-items' : 'menu-preview-switches').append(row);
  }
  overlay.hidden = false;
  positionPreview();
  document.querySelector('main').inert = true; document.querySelector('.save-bar').inert = true;
  requestAnimationFrame(() => requestAnimationFrame(() => overlay.classList.add('visible')));
  $('close-preview').focus();
}
function dismiss() {
  const overlay = $('menu-preview'); if (overlay.hidden) return;
  clearTimeout(dismissTimer);
  overlay.classList.remove('visible'); dismissTimer = setTimeout(() => { overlay.hidden = true; document.querySelector('main').inert = false; document.querySelector('.save-bar').inert = false; lastFocus?.focus(); }, 200);
}
