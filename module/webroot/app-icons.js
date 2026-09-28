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

// Root 兼容路径每批两张，限制一次经过 JavaScript 回调的图片体积。
const batchSize = 2;
const packagePattern = /^[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+$/;
let user = -1, epoch = 0, running = false, frame = 0;
const cache = new Map(), pending = new Map(), targets = new Set(), records = new WeakMap();
const nativeFailures = new Set(), diagnostics = new Map();
const observer = typeof IntersectionObserver === 'undefined' ? null : new IntersectionObserver(items => {
  for (const item of items) if (item.isIntersecting) {
    const record = records.get(item.target); if (record) load(record);
  }
}, {rootMargin: '100px'});
function note(name, stage, detail = '') {
  const previous = diagnostics.get(name) || [];
  previous.push({time: new Date().toISOString(), stage, detail: String(detail).replace(/data:image\/png;base64,[A-Za-z0-9+/=]+/g, '[PNG 已省略]').slice(0, 512)});
  diagnostics.delete(name); diagnostics.set(name, previous.slice(-8));
  if (diagnostics.size > 256) diagnostics.delete(diagnostics.keys().next().value);
}
function failure(name, message) {
  note(name, 'failed', message);
  document.dispatchEvent(new CustomEvent('sidekey-icons-failed', {detail: {packageName: name, message: `${name}：${message}`}}));
}
function nativeIconsAvailable() {
  // 管理器的 URI 没有 Android 用户参数；其他用户仍用模块明确校验用户的接口。
  return user === 0 && typeof window !== 'undefined' && typeof window.ksu?.listPackages === 'function';
}
export function appIconDiagnostics() {
  const states = {}, waiting = new Map();
  for (const record of targets) if (record.element.isConnected) {
    states[record.state] = (states[record.state] || 0) + 1;
    if (record.state !== 'loaded') waiting.set(record.packageName, record.state);
  }
  return ['侧键应用图标诊断', `界面：${document.title}`, `Android 用户：${user}`,
    `KernelSU 图标接口：${nativeIconsAvailable() ? '已检测到' : '未使用（接口不存在或不是主用户 0）'}`,
    `当前图标：${JSON.stringify(states)}`, 'idle 表示尚未请求，离屏图标可处于该状态；failed 表示读取或显示失败。',
    '未完成项目（最多 48 项）：', ...[...waiting].slice(0,48).map(([name,state]) => `${name} ${state}`),
    '\n仅记录本次 WebUI 请求，不包含 PNG 数据或账号凭据。',
    ...[...diagnostics].flatMap(([name, steps]) => [`\n${name}`, ...steps.map(step => `${step.time} ${step.stage}${step.detail ? ' · ' + step.detail : ''}`)])].join('\n');
}
export function resetAppIcons(nextUser) {
  if (!Number.isInteger(nextUser) || nextUser < 0) throw new Error('无法确认图标所属的 Android 用户');
  user = nextUser; epoch++; cache.clear(); nativeFailures.clear(); diagnostics.clear();
  pending.clear();
  // 列表刷新和用户初始化后重新挂起现有图标，不能丢掉早于列表就绪的请求。
  for (const record of [...targets]) {
    if (!record.element.isConnected) { release(record); continue; }
    record.cancel?.(); record.cancelImage?.(); record.cancel = null; record.token++; record.state = 'idle';
    record.element.replaceChildren(symbol('app.fill'));
    observer?.unobserve(record.element); observer?.observe(record.element);
  }
  document.dispatchEvent(new Event('sidekey-icons-reset'));
  schedule();
}
async function readRootIcons(names, currentUser, ticket) {
  const result = new Map();
  if (ticket !== epoch) return result;
  names.forEach(name => note(name, 'root-request', `批量 ${names.length}`));
  try {
    const response = await api('app-icons', `${currentUser}:${names.join(',')}`);
    if (ticket !== epoch) return result;
    const icons = normalizeIcons(response, currentUser, names);
    names.forEach(name => {
      const entry = response.icons.find(item => item.packageName === name);
      result.set(name, {icon: icons.get(name), error: entry?.error || '系统没有返回图标'});
      note(name, 'root-response', icons.get(name) ? `${entry?.source || 'system'}，${icons.get(name).length} 字符` : entry?.error || '空图标');
    });
  } catch (error) {
    if (ticket !== epoch) return result;
    names.forEach(name => note(name, 'root-error', error.message || '图标读取失败'));
    // 批量回包失败时各读一次，避免一张图标影响同批的其他应用；不无限重试。
    if (names.length > 1) {
      for (const name of names) {
        if (ticket !== epoch) break;
        for (const [key, value] of await readRootIcons([name], currentUser, ticket)) result.set(key, value);
      }
    } else result.set(names[0], {icon: '', error: error.message || '图标读取失败'});
  }
  return result;
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
      const icons = await readRootIcons(batch.map(([name]) => name), currentUser, ticket);
      if (ticket !== epoch) continue;
      batch.forEach(([name, job]) => {
        if (pending.get(name) === job) pending.delete(name);
        const result = icons.get(name) || {icon: '', error: '图标响应缺少应用'};
        if (result.icon) {
          cache.set(name, result.icon);
          if (cache.size > 128) cache.delete(cache.keys().next().value);
        }
        job.callbacks.forEach(callback => callback(result.icon));
        if (!result.icon && job.callbacks.size) failure(name, result.error);
      });
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
  if (typeof record.packageName !== 'string' || record.packageName.length > 512 || !packagePattern.test(record.packageName)) {
    record.state = 'failed'; failure(record.packageName, '应用包名无效'); return;
  }
  observer?.unobserve(record.element);
  record.state = 'loading'; const ticket = epoch, token = ++record.token;
  const current = () => ticket === epoch && token === record.token && record.element.isConnected;
  function display(source, origin, onError) {
    record.cancelImage?.(); record.state = origin === 'ksu' ? 'manager-loading' : 'decoding';
    const image = document.createElement('img'); image.alt = ''; image.decoding = 'async';
    let settled = false;
    function cleanup() { clearTimeout(timer); image.onload = image.onerror = null; }
    function failed(reason) {
      if (settled) return;
      settled = true; cleanup();
      if (!current()) return;
      note(record.packageName, `${origin}-error`, reason);
      record.element.replaceChildren(symbol('app.fill')); onError(reason);
    }
    const timer = setTimeout(() => failed('图片读取超时'), 8000);
    record.cancelImage = () => { settled = true; cleanup(); image.removeAttribute('src'); };
    image.onload = () => {
      if (settled) return;
      if (!image.naturalWidth || !image.naturalHeight) { failed('图片尺寸无效'); return; }
      settled = true; cleanup();
      if (!current()) return;
      record.state = 'loaded';
      note(record.packageName, 'loaded', `${origin}，${image.naturalWidth}×${image.naturalHeight}`);
    };
    image.onerror = () => failed(origin === 'ksu' ? '管理器没有提供可显示的图片' : 'WebView 无法显示系统返回的 PNG 图标');
    record.element.replaceChildren(image); image.src = source;
  }
  function loadRoot() {
    if (!current()) return;
    record.state = 'loading';
    record.cancel = getIcon(record.packageName, source => {
      if (!current()) return;
      if (!source) { record.state = 'failed'; return; }
      display(source, 'root', reason => {
        cache.delete(record.packageName); record.state = 'failed'; failure(record.packageName, reason);
      });
    });
  }
  if (nativeIconsAvailable() && !nativeFailures.has(record.packageName)) {
    note(record.packageName, 'ksu-request');
    // KernelSU 在本机拦截该 URI；直接用 img，不依赖 fetch 或跨域 Canvas。
    display(`ksu://icon/${record.packageName}`, 'ksu', () => {
      nativeFailures.add(record.packageName); loadRoot();
    });
  } else loadRoot();
}
function release(record) {
  observer?.unobserve(record.element);
  record.cancel?.(); record.cancelImage?.(); record.token++; record.state = 'released'; targets.delete(record);
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
  const record = {element: wrapper, packageName, state: 'idle', token: 0, cancel: null, cancelImage: null};
  records.set(wrapper, record); targets.add(record); observer?.observe(wrapper); schedule();
  return wrapper;
}
// 首批图标在列表插入后直接读取，不依赖弹窗高度、动画、滚动坐标或观察器回调。
export function requestAppIcons(container, limit = 16) {
  [...container.querySelectorAll('[data-app-icon]')].slice(0, limit).forEach(node => {
    const record = records.get(node); if (record) load(record);
  });
}
export function releaseAppIcons(container) {
  container.querySelectorAll('[data-app-icon]').forEach(node => {
    const record = records.get(node); if (record) release(record);
  });
}
