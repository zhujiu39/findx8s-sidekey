import {symbol} from './symbols.js';
import {api} from './bridge.js';

export function normalizeIcons(data, user, requested) {
  if (data?.version !== 1 || data.user !== user || !Array.isArray(data.icons) || data.icons.length > 16)
    throw new Error('应用图标响应异常，请刷新应用列表');
  const result = new Map(requested.map(name => [name, '']));
  for (const item of data.icons) {
    if (!item || !result.has(item.packageName) || typeof item.icon !== 'string' || item.icon.length > 43714 ||
        (item.error !== undefined && (typeof item.error !== 'string' || item.error.length > 160)) ||
        (item.icon && !/^data:image\/png;base64,iVBORw0KGgo[A-Za-z0-9+/]*={0,2}$/.test(item.icon)))
      throw new Error('应用图标格式无效');
    result.set(item.packageName, item.icon);
  }
  return result;
}

// 沿用旧版的分批查询；一次最多 16 张，每张 PNG 上限 32 KiB。
const batchSize = 16;
let user = -1, epoch = 0, running = false, frame = 0;
const cache = new Map(), pending = new Map(), targets = new Set(), records = new WeakMap();
const observer = typeof IntersectionObserver === 'undefined' ? null : new IntersectionObserver(items => {
  for (const item of items) if (item.isIntersecting) {
    const record = records.get(item.target); if (record) load(record);
  }
}, {rootMargin: '100px'});
function failure(message) {
  document.dispatchEvent(new CustomEvent('sidekey-icons-failed', {detail: {message}}));
}
export function resetAppIcons(nextUser) {
  if (!Number.isInteger(nextUser) || nextUser < 0) throw new Error('无法确认图标所属的 Android 用户');
  user = nextUser; epoch++; cache.clear();
  pending.clear();
  // 列表刷新和用户初始化后重新挂起现有图标，不能丢掉早于列表就绪的请求。
  for (const record of [...targets]) {
    if (!record.element.isConnected) { release(record); continue; }
    record.cancel?.(); record.cancel = null; record.token++; record.state = 'idle';
    record.element.replaceChildren(symbol('app.fill'));
    observer?.unobserve(record.element); observer?.observe(record.element);
  }
  document.dispatchEvent(new Event('sidekey-icons-reset'));
  schedule();
}
async function pump() {
  if (running || user < 0 || !pending.size) return;
  running = true;
  try {
    while (pending.size) {
      const batch = [...pending.entries()].filter(([, job]) => !job.running).slice(0, batchSize);
      if (!batch.length) break;
      const ticket = epoch, currentUser = user;
      batch.forEach(([, job]) => job.running = true);
      try {
        const names = batch.map(([name]) => name);
        const response = await api('app-icons', `${currentUser}:${names.join(',')}`);
        const icons = normalizeIcons(response, currentUser, names);
        if (ticket !== epoch) continue;
        let missing = false;
        batch.forEach(([name, job]) => {
          if (pending.get(name) === job) pending.delete(name);
          const icon = icons.get(name);
          if (icon) {
            cache.set(name, icon);
            if (cache.size > 128) cache.delete(cache.keys().next().value);
          } else missing = true;
          job.callbacks.forEach(callback => callback(icon));
        });
        if (missing) failure(response.icons.find(item => !item.icon && item.error)?.error || '系统未返回部分应用的图标');
      } catch (error) {
        if (ticket !== epoch) continue;
        batch.forEach(([name, job]) => {
          if (pending.get(name) === job) pending.delete(name);
          job.callbacks.forEach(callback => callback(''));
        });
        failure(error.message || '图标读取失败');
      }
    }
  } finally { running = false; }
}
function getIcon(name, callback) {
  if (cache.has(name)) { callback(cache.get(name)); return () => {}; }
  if (!pending.has(name)) pending.set(name, {callbacks: new Set(), running: false});
  const job = pending.get(name); job.callbacks.add(callback); queueMicrotask(pump);
  return () => {
    job.callbacks.delete(callback);
    if (!job.running && !job.callbacks.size && pending.get(name) === job) pending.delete(name);
  };
}
function load(record) {
  if (record.state !== 'idle' || user < 0 || !record.element.isConnected) return;
  observer?.unobserve(record.element);
  record.state = 'loading'; const ticket = epoch, token = ++record.token;
  const current = () => ticket === epoch && token === record.token && record.element.isConnected;
  record.cancel = getIcon(record.packageName, source => {
    if (!current()) return;
    if (!source) { record.state = 'failed'; return; }
    const image = document.createElement('img'); image.alt = ''; image.decoding = 'async';
    image.onload = () => {
      if (!current()) return;
      record.state = 'loaded';
    };
    image.onerror = () => {
      if (!current()) return;
      cache.delete(record.packageName); record.state = 'failed';
      record.element.replaceChildren(symbol('app.fill'));
      failure('WebView 无法显示系统返回的 PNG 图标');
    };
    // 与旧版一致，直接把真实图片放入列表；加载事件只负责状态和失败处理。
    record.element.replaceChildren(image);
    image.src = source;
  });
}
function release(record) {
  observer?.unobserve(record.element);
  record.cancel?.(); record.token++; record.state = 'released'; targets.delete(record);
}
function schedule() {
  if (frame || typeof document === 'undefined' || document.hidden) return;
  frame = requestAnimationFrame(() => {
    frame = 0;
    for (const record of [...targets]) {
      const element = record.element;
      if (!element.isConnected) { release(record); continue; }
      if (record.state !== 'idle' || !element.getClientRects().length) continue;
      const dialog = element.closest('dialog');
      if (dialog && !dialog.open) continue;
      const rect = element.getBoundingClientRect();
      if (rect.width && rect.height && rect.bottom >= -100 && rect.top <= innerHeight + 100 && rect.right >= 0 && rect.left <= innerWidth)
        load(record);
    }
  });
}
// 顶层对话框、分页及滚动后主动检查可见图标，不依赖 WebView 的 IntersectionObserver 回调。
if (typeof document !== 'undefined') {
  document.addEventListener('scroll', schedule, {capture: true, passive: true});
  document.addEventListener('sidekey-page', schedule);
  document.addEventListener('sidekey-apps-updated', schedule);
  document.addEventListener('visibilitychange', schedule);
  document.addEventListener('animationend', schedule);
  window.addEventListener('resize', schedule);
}
export function appIcon(packageName, label, className = 'app-image') {
  const wrapper = document.createElement('span'); wrapper.className = className;
  wrapper.append(symbol('app.fill')); wrapper.setAttribute('aria-hidden', 'true');
  wrapper.dataset.appIcon = '';
  const record = {element: wrapper, packageName, state: 'idle', token: 0, cancel: null};
  records.set(wrapper, record); targets.add(record); observer?.observe(wrapper); schedule();
  return wrapper;
}
// 首批图标在列表插入后直接读取，不依赖弹窗高度、动画、滚动坐标或观察器回调。
export function requestAppIcons(container, limit = batchSize) {
  [...container.querySelectorAll('[data-app-icon]')].slice(0, limit).forEach(node => {
    const record = records.get(node); if (record) load(record);
  });
}
export function releaseAppIcons(container) {
  container.querySelectorAll('[data-app-icon]').forEach(node => {
    const record = records.get(node); if (record) release(record);
  });
}
