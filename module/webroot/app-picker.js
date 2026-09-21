import {api, available} from './bridge.js';
import {AppCatalogStore, filterApps} from './app-catalog.js';
import {appIcon, resetAppIcons, releaseAppIcons} from './app-icons.js';

export const appCatalog = new AppCatalogStore(async () => {
  const data = await api('apps'); resetAppIcons(data.user); return data;
});
let dialog, search, status, list, refreshButton, selection, chosen, catalog, footer, applyButton, counter, title;
let multiple = false, maximum = 8, generation = 0;
let picked = new Map();
function node(tag, className, text) {
  const result = document.createElement(tag); result.className = className;
  if (text !== undefined) result.textContent = text;
  return result;
}
function render() {
  releaseAppIcons(list); list.replaceChildren();
  if (!catalog) return;
  const apps = filterApps(catalog.apps, search.value);
  if (multiple) {
    const missing = [...picked.values()].filter(app => !catalog.apps.some(current => current.packageName === app.packageName));
    apps.push(...filterApps(missing, search.value));
  }
  status.textContent = apps.length ? `${apps.length} 个应用 · 当前用户 ${catalog.user}` : search.value.trim() ? '没有匹配的应用，试试其他名称。' : '当前用户没有可启动的应用。';
  if (catalog.labelFallbacks) status.textContent += ' · 部分应用名称暂不可读，显示包名。';
  const fragment = document.createDocumentFragment();
  apps.forEach(app => {
    const row = node(multiple ? 'label' : 'button', 'app-result');
    if (!multiple) row.type = 'button';
    const text = node('span', 'app-result-text');
    text.append(node('strong', '', app.label), node('small', '', app.packageName));
    if (!catalog.apps.some(current => current.packageName === app.packageName)) text.append(node('small', '', '当前列表中不可用，可取消勾选'));
    row.append(appIcon(app.packageName, app.label), text);
    row.setAttribute('aria-label', `选择 ${app.label}（${app.packageName}）`);
    if (multiple) {
      const checkbox = node('input', 'app-checkbox'); checkbox.type = 'checkbox';
      checkbox.checked = picked.has(app.packageName); checkbox.setAttribute('aria-label', `选择 ${app.label}`);
      row.classList.toggle('selected', checkbox.checked);
      checkbox.addEventListener('change', () => {
        if (checkbox.checked) picked.set(app.packageName, app); else picked.delete(app.packageName);
        updateSelection();
      });
      row.dataset.packageName = app.packageName; row.append(checkbox);
    } else {
      if (app.packageName === chosen) { row.classList.add('selected'); row.append(node('span', 'app-check', '✓')); }
      row.addEventListener('click', () => { const callback = selection; dialog.close(); callback?.(app); });
    }
    fragment.append(row);
  });
  list.append(fragment);
  updateSelection();
}
function updateSelection() {
  if (!multiple) return;
  counter.textContent = `已选 ${picked.size} 个应用`;
  applyButton.disabled = !catalog || picked.size > maximum;
  list.querySelectorAll('.app-result').forEach(row => {
    const checkbox = row.querySelector('input'); checkbox.checked = picked.has(row.dataset.packageName);
    checkbox.disabled = !checkbox.checked && picked.size >= maximum;
    row.classList.toggle('selected', checkbox.checked);
  });
}
async function load(force) {
  const ticket = ++generation; catalog = null; releaseAppIcons(list); list.replaceChildren(); updateSelection();
  status.textContent = '正在读取手机应用…'; refreshButton.disabled = true;
  try {
    if (!available()) throw new Error('请从手机 KernelSU 打开 WebUI，读取当前手机的应用。');
    const result = await appCatalog.get(force);
    if (ticket !== generation || !dialog.open) return;
    catalog = result;
    // 保留取消/搜索/刷新的勾选草稿，名称始终跟随系统应用信息。
    picked.forEach((app, name) => { const latest = catalog.apps.find(item => item.packageName === name); if (latest) picked.set(name, latest); });
    render();
    document.dispatchEvent(new Event('sidekey-apps-updated'));
  } catch (error) {
    if (ticket === generation && dialog.open) status.textContent = `${error.message} 点击“刷新”重试，原有选择会保留。`;
  } finally { if (ticket === generation) refreshButton.disabled = false; }
}
function init() {
  if (dialog) return;
  dialog = node('dialog', 'app-picker'); dialog.setAttribute('aria-labelledby', 'app-picker-title');
  const heading = node('div', 'app-picker-heading'); title = node('h2', '', '选择应用'); title.id = 'app-picker-title';
  const close = node('button', 'icon-button', '×'); close.type = 'button'; close.setAttribute('aria-label', '关闭应用选择');
  close.addEventListener('click', () => dialog.close()); heading.append(title, close);
  const toolbar = node('div', 'app-picker-toolbar'); search = node('input', 'app-search');
  search.type = 'search'; search.placeholder = '搜索应用名称'; search.setAttribute('aria-label', '搜索应用名称或包名');
  search.autocomplete = 'off'; search.addEventListener('input', render);
  refreshButton = node('button', 'secondary', '刷新'); refreshButton.type = 'button'; refreshButton.addEventListener('click', () => load(true));
  toolbar.append(search, refreshButton);
  status = node('p', 'app-picker-status'); status.setAttribute('role', 'status');
  list = node('div', 'app-results');
  footer = node('div', 'app-picker-footer'); counter = node('span', '');
  applyButton = node('button', 'primary', '使用所选应用'); applyButton.type = 'button';
  applyButton.addEventListener('click', () => { const callback = selection, apps = [...picked.values()]; dialog.close(); callback?.(apps); });
  footer.append(counter, applyButton);
  dialog.append(heading, toolbar, status, list, footer); document.body.append(dialog);
  dialog.addEventListener('close', () => {
    if (dialog.open) return;
    generation++; selection = null; releaseAppIcons(list); list.replaceChildren();
  });
  document.addEventListener('sidekey-icons-failed', () => { if (dialog.open) status.textContent = '部分图标暂不可读，点击“刷新”重试；勾选内容会保留。'; });
}
export function chooseApp(packageName, callback) {
  init(); if (dialog.open) return;
  multiple = false; footer.hidden = true; title.textContent = '选择应用';
  selection = callback; chosen = packageName; search.value = '';
  dialog.showModal(); search.focus(); load(false);
}
export function chooseApps(apps, limit, callback) {
  init(); if (dialog.open) return;
  multiple = true; maximum = limit; footer.hidden = false; title.textContent = '勾选菜单应用';
  picked = new Map(apps.map(app => [app.packageName, {...app}])); selection = callback; search.value = '';
  dialog.showModal(); load(false);
}
export function createAppChoice(readPackage, onSelect, label = '选择应用') {
  const button = node('button', 'app-choice'); button.type = 'button';
  const name = node('strong', ''), detail = node('small', ''); button.append(name, detail, node('span', 'app-choice-arrow', '›'));
  function update() {
    const packageName = readPackage(), app = appCatalog.lookup(packageName);
    name.textContent = app?.label || (packageName ? '已配置应用' : '点击选择应用');
    detail.textContent = packageName || '读取手机应用列表，按名称选择';
    button.setAttribute('aria-label', `${label}：${app?.label || packageName || '未选择'}`);
  }
  button.addEventListener('click', () => chooseApp(readPackage(), app => { onSelect(app); update(); }));
  // 事件委托只更新仍在页面中的控件，避免重建菜单卡片后保留旧元素。
  button.refreshAppChoice = update; update(); return button;
}
document.addEventListener('sidekey-apps-updated', () => {
  document.querySelectorAll('.app-choice').forEach(button => button.refreshAppChoice());
});
