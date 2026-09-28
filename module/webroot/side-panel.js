import {symbol, actionSymbols} from './symbols.js';
import {appIcon, releaseAppIcons} from './app-icons.js';
import {surfingFields, surfingText, surfingMask} from './model.js';
import {mijiaBindingIsReading} from './mijia.js';

const $ = id => document.getElementById(id);
const el = (tag, cls = '', text) => {
  const node = document.createElement(tag); node.className = cls;
  if (text !== undefined) node.textContent = text;
  return node;
};
let draft, dialogConfig, lastFocus, closeTimer;
let previewSurfingOn = false;

function control(item, interactive = false) {
  const row = el(interactive ? 'button' : 'div', 'panel-control');
  if (interactive) row.type = 'button';
  const name = el('span', 'panel-name');
  const stateful = ['torch', 'mijia', 'surfing'].includes(item.type);
  name.append(el('strong', '', item.name)); name.title = item.name;
  row.append(symbol(actionSymbols[item.type] || 'app.fill'), name);
  if (stateful && !interactive) row.title = '布局预览不读取开关状态';
  return row;
}
function reading(item) {
  const card = el('article', 'panel-card reading-card');
  const heading = el('div', 'reading-heading');
  heading.append(symbol('thermometer.medium'), el('span', '', item.name));
  const value = el('div', 'reading-values'); value.append(el('strong', '', '—'));
  card.append(heading, el('p', 'reading-meta', '只读卡片 · 展开真实菜单时读取'), value);
  return card;
}
function surfing(item) {
  const card = el('article', 'panel-card surfing-card'), header = control(item, true);
  const details = el('div', 'surfing-preview-details'); card.append(header, details);
  header.setAttribute('aria-label', item.name + '，切换开启后的布局预览，不执行实际操作');
  header.title = '仅切换布局预览，不执行实际操作';
  header.addEventListener('click', () => {
    previewSurfingOn = !previewSurfingOn;
    document.querySelectorAll('.surfing-card').forEach(updatePreviewSurfing);
  });
  const labels = surfingText(item), mask = surfingMask(item), grid = el('div', 'traffic-grid');
  for (const [key, , bit] of surfingFields.slice(0, 4)) {
    if (!(mask & bit)) continue;
    const cell = el('div');
    if (labels[key]) cell.append(el('span', '', labels[key]));
    cell.append(el('strong', '', '—')); grid.append(cell);
  }
  if (mask & 15) {
    details.append(grid);
    if (labels.note) details.append(el('p', 'traffic-note', labels.note));
  }
  if (mask & 16) {
    const quota = el('section', 'subscription');
    if (labels.quota) quota.append(el('h4', '', labels.quota));
    quota.append(el('p', '', (labels.used ? labels.used + '：' : '') + '—'));
    quota.append(el('p', '', (labels.total ? labels.total + '：' : '') + '—'));
    details.append(quota);
  }
  updatePreviewSurfing(card);
  return card;
}
function updatePreviewSurfing(card) {
  const header = card.querySelector('.panel-control');
  card.classList.toggle('is-on', previewSurfingOn);
  card.classList.toggle('is-off', !previewSurfingOn);
  header.setAttribute('aria-pressed', String(previewSurfingOn));
  card.querySelector('.surfing-preview-details').hidden = !previewSurfingOn;
}
function render(host, config, modal = false) {
  if (!host || !config) return;
  const scrollTop = host.scrollTop;
  releaseAppIcons(host); host.replaceChildren();
  host.classList.toggle('from-left', config.menu_side === 'left');
  host.classList.toggle('compact', config.menu_width < 195);
  host.style.setProperty('--sidebar-width', config.menu_width + 'px');
  host.style.setProperty('--app-gap', config.menu_gap + 'px');
  const entries = config.menu.filter(item => item.type !== 'none');
  if (!entries.length) { host.append(el('p', 'empty-panel', '添加项目后，它们会出现在这里。')); return; }
  const visible = entries.slice(0, 64), stack = el('div', 'panel-stack'); host.append(stack);
  for (const item of visible.filter(item => !['app', 'app_freeform'].includes(item.type))) {
    if (item.type === 'mijia' && mijiaBindingIsReading(item.argument)) stack.append(reading(item));
    else if (item.type === 'surfing') stack.append(surfing(item));
    else {
      const card = el('article', 'panel-card');
      if (['torch', 'mijia'].includes(item.type)) card.classList.add('is-unknown');
      card.append(control(item)); stack.append(card);
    }
  }
  const apps = visible.filter(item => ['app', 'app_freeform'].includes(item.type));
  if (apps.length) {
    const grid = el('div', 'panel-apps');
    for (const item of apps) {
      const cell = el('div', 'panel-app');
      cell.append(appIcon(item.argument, item.name), el('span', '', item.name)); grid.append(cell);
    }
    host.append(grid);
  }
  if (entries.length > visible.length) host.append(el('p', 'panel-footnote', `另有 ${entries.length - visible.length} 项在真实菜单中显示`));
  host.append(el('p', 'panel-footnote', '布局示意 · 实际状态按侧键查看'));
  host.scrollTop = scrollTop;
  requestAnimationFrame(() => {
    if (!host.isConnected) return;
    const height = modal ? innerHeight : host.parentElement.clientHeight;
    const top = modal ? 92 : 48, bottom = modal ? 20 : 22, half = host.offsetHeight / 2;
    host.style.top = Math.max(top + half, Math.min(height - bottom - half, height * config.menu_position / 100)) + 'px';
  });
}
export function renderLiveMenu(config) {
  draft = structuredClone(config); render($('live-menu'), draft);
}
export function openMenuPreview(config) {
  if (!config?.menu.some(item => item.type !== 'none')) return;
  clearTimeout(closeTimer); $('menu-preview').classList.remove('is-closing');
  previewSurfingOn = false;
  dialogConfig = structuredClone(config); lastFocus = document.activeElement;
  if (!$('menu-preview').open) $('menu-preview').showModal();
  render($('menu-preview-panel'), dialogConfig, true); $('close-preview').focus();
}
function closeMenu() {
  if (!$('menu-preview').open || $('menu-preview').classList.contains('is-closing')) return;
  $('menu-preview').classList.add('is-closing');
  const reduced = matchMedia('(prefers-reduced-motion: reduce)').matches || document.documentElement.dataset.reduceMotion === 'true';
  closeTimer = setTimeout(() => {
    $('menu-preview').close(); $('menu-preview').classList.remove('is-closing');
  }, reduced ? 0 : 180);
}
window.addEventListener('resize', () => {
  if (draft) render($('live-menu'), draft);
  if ($('menu-preview').open && dialogConfig) render($('menu-preview-panel'), dialogConfig, true);
});
$('close-preview').addEventListener('click', closeMenu);
$('menu-preview').addEventListener('click', event => { if (event.target === $('menu-preview')) closeMenu(); });
$('menu-preview').addEventListener('cancel', event => { event.preventDefault(); closeMenu(); });
$('menu-preview').addEventListener('close', () => {
  dialogConfig = null; releaseAppIcons($('menu-preview-panel')); $('menu-preview-panel').replaceChildren();
  if (lastFocus?.isConnected) lastFocus.focus();
});
