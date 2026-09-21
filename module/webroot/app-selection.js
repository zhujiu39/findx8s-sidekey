import {menuAppName} from './app-catalog.js';
export const isApp = item => ['app', 'app_freeform'].includes(item.type);
export function applyAppSelection(entries, selected) {
  const result = entries.filter(item => !isApp(item)).map(item => ({...item})), names = new Set();
  for (const app of selected) {
    if (!/^[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+$/.test(app.packageName) || names.has(app.packageName)) throw new Error('应用选择重复或无效');
    names.add(app.packageName);
    const previous = entries.find(item => isApp(item) && item.argument === app.packageName);
    result.push({name:menuAppName(app.label),icon:'',type:previous?.type || 'app_freeform',argument:app.packageName});
  }
  return result.map((item, slot) => ({...item, slot}));
}
