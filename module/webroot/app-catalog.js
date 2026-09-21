// 手机应用列表只驻留在本次 WebUI 的内存中，不写入文件或远端。
const packagePattern = /^[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+$/;
export function normalizeCatalog(data) {
  if (!data || data.version !== 1 || !Number.isInteger(data.user) || data.user < 0 ||
      !Array.isArray(data.apps) || data.apps.length > 2048) throw new Error('应用列表格式异常，请刷新后重试');
  const unique = new Map();
  for (const app of data.apps) {
    if (!app || typeof app.packageName !== 'string' || !packagePattern.test(app.packageName) ||
        app.packageName.length > 512 || typeof app.label !== 'string' || app.label.includes('\0') || app.label.length > 512)
      throw new Error('应用列表包含无效数据');
    if (!unique.has(app.packageName)) unique.set(app.packageName, {packageName: app.packageName, label: app.label.trim() || app.packageName});
  }
  return {user: data.user, apps: [...unique.values()].sort((a,b) => a.label.localeCompare(b.label, 'zh-CN') || a.packageName.localeCompare(b.packageName)),
    labelFallbacks: Number.isInteger(data.labelFallbacks) ? data.labelFallbacks : 0};
}
export function filterApps(apps, query) {
  const needle = query.trim().toLocaleLowerCase();
  return needle ? apps.filter(app => app.label.toLocaleLowerCase().includes(needle) || app.packageName.toLowerCase().includes(needle)) : apps;
}
export function menuAppName(label) {
  let result = '';
  for (const character of label.trim()) {
    if (new TextEncoder().encode(result + character).length > 96) break;
    result += character;
  }
  return result;
}
export class AppCatalogStore {
  constructor(loader, clock = () => Date.now()) { this.loader = loader; this.clock = clock; this.value = null; this.until = 0; this.pending = null; }
  get(force = false) {
    if (this.pending) return this.pending;
    if (!force && this.value && this.clock() < this.until) return Promise.resolve(this.value);
    this.pending = Promise.resolve().then(this.loader).then(data => {
      const value = normalizeCatalog(data); this.value = value; this.until = this.clock() + 10000; return value;
    }).catch(error => { this.until = 0; throw error; }).finally(() => { this.pending = null; });
    return this.pending;
  }
  lookup(packageName) { return this.value?.apps.find(app => app.packageName === packageName); }
}
