import {actions} from './model.js';
const $ = id => document.getElementById(id);
let entries = [], onChange = () => {}, isBusy = false;
const needsArgument = type => ['app', 'keycode', 'shell'].includes(type);
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
    const heading = element('div', 'menu-item-heading'); heading.append(element('strong', '', `捷径 ${index + 1}`));
    const tools = element('div', 'menu-item-tools');
    [['↑', '上移', index > 0, -1], ['↓', '下移', index < entries.length - 1, 1], ['删除', '删除', true, 0]].forEach(([text, title, allowed, direction]) => {
      const button = element('button', 'secondary', text); button.type = 'button'; button.title = title;
      button.setAttribute('aria-label', `${title}捷径 ${index + 1}`); button.disabled = isBusy || !allowed;
      button.addEventListener('click', () => {
        if (direction) [entries[index], entries[index + direction]] = [entries[index + direction], entries[index]];
        else entries.splice(index, 1);
        render(); onChange();
      }); tools.append(button);
    });
    heading.append(tools); card.append(heading);
    const fields = element('div', 'menu-label-fields');
    for (const [key, label, placeholder] of [['icon','图标 / emoji','可留空'], ['name','名称','输入捷径名称']]) {
      const input = element('input', ''); input.value = item[key]; input.placeholder = placeholder;
      input.autocomplete = 'off'; input.addEventListener('input', () => { item[key] = input.value; onChange(); });
      fields.append(field(label, input));
    }
    card.append(fields);
    const select = element('select', ''); actions.filter(([id]) => id !== 'menu').forEach(([id, name]) => select.add(new Option(name, id)));
    select.value = item.type; card.append(field('执行动作', select));
    const argument = element('textarea', ''); argument.value = item.argument; argument.rows = 2; argument.spellcheck = false;
    const argumentField = field('动作参数', argument); card.append(argumentField);
    function updateArgument() {
      argumentField.hidden = !needsArgument(item.type);
      argument.placeholder = item.type === 'shell' ? '以 Root 执行的 Shell 命令' : item.type === 'app' ? '应用包名，如 com.android.settings' : 'Android 按键码，如 3';
    }
    select.addEventListener('change', () => { item.type = select.value; item.argument = ''; argument.value = ''; updateArgument(); onChange(); });
    argument.addEventListener('input', () => { item.argument = argument.value; onChange(); });
    updateArgument(); $('menu-items').append(card);
  });
  $('menu-count').textContent = `${entries.length} / 12 项`;
  $('add-menu-item').disabled = isBusy || entries.length >= 12;
}
export function initMenuEditor(change) {
  onChange = change;
  $('add-menu-item').addEventListener('click', () => {
    if (entries.length >= 12 || isBusy) return;
    entries.push({name: '新捷径', icon: '', type: 'none', argument: ''}); render(); onChange();
  });
  ['menu-side','menu-position'].forEach(id => $(id).addEventListener('input', onChange));
  $('preview-menu').addEventListener('click', preview);
  $('menu-preview').addEventListener('click', event => { if (event.target === $('menu-preview')) dismiss(); });
  $('close-preview').addEventListener('click', dismiss);
  document.addEventListener('keydown', event => { if (event.key === 'Escape') dismiss(); });
}
export function fillMenu(config) {
  entries = structuredClone(config.menu); $('menu-side').value = config.menu_side;
  $('menu-position').value = config.menu_position; render();
}
export function readMenu() {
  return {menu_side: $('menu-side').value, menu_position: Number($('menu-position').value), menu: structuredClone(entries)};
}
export function menuBusy(value) {
  isBusy = value; $('add-menu-item').disabled = value || entries.length >= 12;
  $('preview-menu').disabled = value;
  $('menu-position-output').value = `${$('menu-position').value}%`;
  $('menu-items').querySelectorAll('button').forEach((button, index) => {
    const itemIndex = Math.floor(index / 3), tool = index % 3;
    button.disabled = value || (tool === 0 && itemIndex === 0) || (tool === 1 && itemIndex === entries.length - 1);
  });
}
let lastFocus, dismissTimer;
function preview() {
  const overlay = $('menu-preview'), panel = $('menu-preview-panel'); lastFocus = document.activeElement;
  overlay.classList.toggle('from-right', $('menu-side').value === 'right');
  clearTimeout(dismissTimer);
  $('menu-preview-items').replaceChildren();
  if (!entries.length) $('menu-preview-items').append(element('p', 'hint', '这里还空着。添加你自己的捷径后，会显示在这里。'));
  entries.forEach(item => {
    const row = element('button', 'menu-preview-row'); row.type = 'button';
    row.append(element('span', 'menu-preview-icon', item.icon), element('span', '', item.name || '未命名'));
    row.addEventListener('click', dismiss); $('menu-preview-items').append(row);
  });
  overlay.hidden = false;
  const half = panel.offsetHeight / 2, height = window.innerHeight;
  panel.style.top = `${Math.max(half + 16, Math.min(height - half - 16, height * Number($('menu-position').value) / 100))}px`;
  document.querySelector('main').inert = true; document.querySelector('.save-bar').inert = true;
  requestAnimationFrame(() => requestAnimationFrame(() => overlay.classList.add('visible')));
  $('close-preview').focus();
}
function dismiss() {
  const overlay = $('menu-preview'); if (overlay.hidden) return;
  overlay.classList.remove('visible'); dismissTimer = setTimeout(() => { overlay.hidden = true; document.querySelector('main').inert = false; document.querySelector('.save-bar').inert = false; lastFocus?.focus(); }, 200);
}
