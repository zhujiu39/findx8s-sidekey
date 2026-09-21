import {api} from './bridge.js';

export function normalizeIcons(data, user, requested) {
  if (data?.version !== 1 || data.user !== user || !Array.isArray(data.icons) || data.icons.length > 16)
    throw new Error('应用图标响应异常，请刷新应用列表');
  const result = new Map(requested.map(name => [name, '']));
  for (const item of data.icons) {
    if (!result.has(item.packageName) || typeof item.icon !== 'string' || item.icon.length > 43714 ||
        (item.icon && !/^data:image\/png;base64,iVBORw0KGgo[A-Za-z0-9+/]*={0,2}$/.test(item.icon)))
      throw new Error('应用图标格式无效');
    result.set(item.packageName, item.icon);
  }
  return result;
}

let user = -1, epoch = 0, running = false;
const cache = new Map(), pending = new Map();
export function resetAppIcons(nextUser) {
  user = nextUser; epoch++; cache.clear();
  for (const callbacks of pending.values()) callbacks.forEach(callback => callback(''));
  pending.clear();
}
async function pump() {
  if (running || user < 0 || !pending.size) return;
  running = true;
  try {
    while (pending.size) {
      const batch = [...pending.entries()].slice(0, 16), ticket = epoch, currentUser = user;
      batch.forEach(([name]) => pending.delete(name));
      try {
        const names = batch.map(([name]) => name);
        const icons = normalizeIcons(await api('app-icons', `${currentUser}:${names.join(',')}`), currentUser, names);
        batch.forEach(([name, callbacks]) => {
          const icon = ticket === epoch ? icons.get(name) : '';
          if (ticket === epoch) cache.set(name, icon);
          callbacks.forEach(callback => callback(icon));
        });
      } catch {
        batch.forEach(([, callbacks]) => callbacks.forEach(callback => callback('')));
        document.dispatchEvent(new Event('sidekey-icons-failed'));
      }
    }
  } finally { running = false; }
}
function getIcon(name, callback) {
  if (cache.has(name)) { callback(cache.get(name)); return; }
  if (!pending.has(name)) pending.set(name, []);
  pending.get(name).push(callback); queueMicrotask(pump);
}
const observer = typeof IntersectionObserver === 'undefined' ? null : new IntersectionObserver(items => {
  items.forEach(item => { if (item.isIntersecting) { observer.unobserve(item.target); item.target.loadAppIcon?.(); } });
}, {rootMargin: '100px'});
export function appIcon(packageName, label, className = 'app-image') {
  const wrapper = document.createElement('span'); wrapper.className = className;
  wrapper.textContent = [...label][0] || '◇'; wrapper.setAttribute('aria-hidden', 'true');
  wrapper.loadAppIcon = () => getIcon(packageName, source => {
    if (!source) return;
    const image = document.createElement('img'); image.src = source; image.alt = ''; image.decoding = 'async';
    wrapper.replaceChildren(image);
  });
  if (observer) observer.observe(wrapper); else wrapper.loadAppIcon();
  return wrapper;
}
export function releaseAppIcons(container) {
  container.querySelectorAll('.app-image, .menu-real-icon').forEach(node => observer?.unobserve(node));
}
