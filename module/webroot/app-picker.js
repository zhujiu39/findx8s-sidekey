import {api, available} from './bridge.js';
import {AppCatalogStore, filterApps} from './app-catalog.js';

export const appCatalog = new AppCatalogStore(() => api('apps'));
let dialog, search, status, list, refreshButton, selection, chosen, catalog, generation = 0;
function node(tag, className, text) {
  const result = document.createElement(tag); result.className = className;
  if (text !== undefined) result.textContent = text;
  return result;
}
function render() {
  list.replaceChildren();
  if (!catalog) return;
  const apps = filterApps(catalog.apps, search.value);
  status.textContent = apps.length ? `${apps.length} 个应用 · 当前用户 ${catalog.user}` : search.value.trim() ? '没有匹配的应用，试试其他名称。' : '当前用户没有可启动的应用。';
  if (catalog.labelFallbacks) status.textContent += ' · 部分应用名称暂不可读，显示包名。';
  const fragment = document.createDocumentFragment();
  apps.forEach(app => {
    const row = node('button', 'app-result'); row.type = 'button';
    const text = node('span', 'app-result-text');
    text.append(node('strong', '', app.label), node('small', '', app.packageName));
    row.append(node('span', 'app-letter', [...app.label][0] || '◇'), text);
    row.setAttribute('aria-label', `选择 ${app.label}（${app.packageName}）`);
    if (app.packageName === chosen) { row.classList.add('selected'); row.append(node('span', 'app-check', '✓')); }
    row.addEventListener('click', () => { const callback = selection; dialog.close(); callback?.(app); });
    fragment.append(row);
  });
  list.append(fragment);
}
async function load(force) {
  const ticket = ++generation; catalog = null; list.replaceChildren();
  status.textContent = '正在读取手机应用…'; refreshButton.disabled = true;
  try {
    if (!available()) throw new Error('请从手机 KernelSU 打开 WebUI，读取当前手机的应用。');
    const result = await appCatalog.get(force);
    if (ticket !== generation || !dialog.open) return;
    catalog = result; render();
    document.dispatchEvent(new Event('sidekey-apps-updated'));
  } catch (error) {
    if (ticket === generation && dialog.open) status.textContent = `${error.message} 点击“刷新”重试，原有选择会保留。`;
  } finally { if (ticket === generation) refreshButton.disabled = false; }
}
function init() {
  if (dialog) return;
  dialog = node('dialog', 'app-picker'); dialog.setAttribute('aria-labelledby', 'app-picker-title');
  const heading = node('div', 'app-picker-heading'), title = node('h2', '', '选择应用'); title.id = 'app-picker-title';
  const close = node('button', 'icon-button', '×'); close.type = 'button'; close.setAttribute('aria-label', '关闭应用选择');
  close.addEventListener('click', () => dialog.close()); heading.append(title, close);
  const toolbar = node('div', 'app-picker-toolbar'); search = node('input', 'app-search');
  search.type = 'search'; search.placeholder = '搜索应用名称'; search.setAttribute('aria-label', '搜索应用名称或包名');
  search.autocomplete = 'off'; search.addEventListener('input', render);
  refreshButton = node('button', 'secondary', '刷新'); refreshButton.type = 'button'; refreshButton.addEventListener('click', () => load(true));
  toolbar.append(search, refreshButton);
  status = node('p', 'app-picker-status'); status.setAttribute('role', 'status');
  list = node('div', 'app-results');
  dialog.append(heading, toolbar, status, list); document.body.append(dialog);
  dialog.addEventListener('close', () => { generation++; selection = null; });
}
export function chooseApp(packageName, callback) {
  init(); if (dialog.open) return;
  selection = callback; chosen = packageName; search.value = '';
  dialog.showModal(); search.focus(); load(false);
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
