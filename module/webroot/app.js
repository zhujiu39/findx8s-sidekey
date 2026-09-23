import {initMenuEditor, fillMenu, readMenu, menuBusy, addMijiaEntry} from './menu-editor.js';
import {actions, gestureIds, defaultConfig, validate, serialize, hex} from './model.js';
import {api, available, saveConfiguration} from './bridge.js';
import {copyText} from './clipboard.js';
import {appCatalog, createAppChoice} from './app-picker.js';
import {initNavigation} from './navigation.js';
import {initMijia, chooseMijia, mijiaBindingLabel} from './mijia.js';
const $ = id => document.getElementById(id);
let saved = defaultConfig(), loaded = false, busy = false, refreshBusy = false;
let toastTimer;
const titles = ['短按', '双击', '长按'];
const descriptions = ['轻按一次，快速执行', '连续两次，另一个捷径', '按住片刻，触发专属动作'];
const compactActions = {menu: '弹出快捷菜单', none: '不执行动作', torch: '切换手电筒',
  app_freeform: '小窗打开应用', app: '打开应用', shell: '自定义 Shell', keycode: '发送按键'};

function toast(text) {
  $('toast').textContent = text; $('toast').hidden = false;
  clearTimeout(toastTimer); toastTimer = setTimeout(() => { $('toast').hidden = true; }, 4000);
}
function notice(text) { $('notice').textContent = text; $('notice').hidden = !text; }
function buildCards() {
  gestureIds.forEach((id, index) => {
    const card = document.createElement('article'); card.className = 'gesture-card';
    card.innerHTML = `<button type="button" class="gesture-open" id="edit-${id}" aria-expanded="false" aria-controls="editor-${id}"><span class="gesture-number" aria-hidden="true">0${index+1}</span><span class="gesture-copy"><span class="gesture-name">${titles[index]}</span><strong class="gesture-value" id="summary-${id}"></strong></span><span class="gesture-arrow" aria-hidden="true">›</span></button><div class="gesture-body" id="editor-${id}" hidden><div class="gesture-edit-heading"><span>${descriptions[index]}</span><button class="test" id="test-${id}" type="button" aria-label="执行${titles[index]}动作">执行 ↗</button></div><div class="selector"><select id="action-${id}" aria-label="${titles[index]}动作"></select></div><div class="argument" id="argument-${id}" hidden><label for="value-${id}"></label><input id="value-${id}" autocomplete="off" spellcheck="false"><textarea id="shell-${id}" aria-label="${titles[index]} Shell 命令" spellcheck="false" hidden></textarea><p></p></div></div>`;
    $('gestures').append(card);
    $(`edit-${id}`).addEventListener('click', () => {
      const opening = $(`editor-${id}`).hidden;
      gestureIds.forEach(other => {
        const expanded = opening && other === id;
        $(`editor-${other}`).hidden = !expanded;
        $(`edit-${other}`).setAttribute('aria-expanded', String(expanded));
      });
    });
    const appChoice = createAppChoice(() => $(`value-${id}`).value, app => {
      $(`value-${id}`).value = app.packageName; updateDirty();
    }, `${titles[index]}应用`);
    appChoice.id = `app-${id}`; $(`argument-${id}`).insertBefore(appChoice, $(`value-${id}`));
    const mijiaChoice = document.createElement('button'); mijiaChoice.type = 'button';
    mijiaChoice.id = `mijia-${id}`; mijiaChoice.className = 'secondary mijia-gesture-choice';
    mijiaChoice.addEventListener('click', () => chooseMijia(entry => {
      $(`value-${id}`).value = entry.id; updateArgument(id); updateDirty();
    }));
    $(`argument-${id}`).append(mijiaChoice);
    const select = $(`action-${id}`);
    actions.forEach(([value, name]) => select.add(new Option(name, value)));
    select.addEventListener('change', () => { $(`value-${id}`).value = ''; $(`shell-${id}`).value = ''; updateArgument(id); updateDirty(); });
    $(`value-${id}`).addEventListener('input', updateDirty);
    $(`shell-${id}`).addEventListener('input', updateDirty);
    $(`test-${id}`).addEventListener('click', () => testAction(id));
  });
}
function updateArgument(id) {
  const type = $(`action-${id}`).value, block = $(`argument-${id}`), input = $(`value-${id}`), shell = $(`shell-${id}`);
  block.hidden = !['app', 'app_freeform', 'keycode', 'shell', 'mijia'].includes(type);
  $(`mijia-${id}`).hidden = type !== 'mijia';
  $(`mijia-${id}`).textContent = input.value && type === 'mijia' ? mijiaBindingLabel(input.value) : '选择米家动作';
  const isApp = ['app','app_freeform'].includes(type);
  input.hidden = type !== 'keycode'; shell.hidden = type !== 'shell';
  $(`app-${id}`).hidden = !isApp; $(`app-${id}`).refreshAppChoice();
  const label = block.querySelector('label');
  label.htmlFor = isApp ? `app-${id}` : type === 'shell' ? `shell-${id}` : `value-${id}`;
  label.textContent = type === 'mijia' ? '米家动作' : isApp ? '应用' : type === 'keycode' ? 'Android 按键码' : 'Shell 命令（Root）';
  input.type = type === 'keycode' ? 'number' : 'text';
  input.placeholder = '例如 3（主页）';
  shell.placeholder = '例如：input keyevent 3';
  block.querySelector('p').textContent = type === 'mijia' ? '执行结果可在米家页面查看。' : isApp ? '' : type === 'keycode' ? '填写 Android KeyEvent 编码，范围 1～2047。' : '以 Root 执行，最长 10 秒；动作结束时清理后台进程。';
}
function formConfig() {
  return {enabled: $('enabled').checked, haptic: $('haptic').checked, long_ms: Number($('long-ms').value), double_ms: Number($('double-ms').value),
    actions: gestureIds.map(id => { const type = $(`action-${id}`).value; return {type, argument: type === 'shell' ? $(`shell-${id}`).value : ['app','app_freeform','keycode','mijia'].includes(type) ? $(`value-${id}`).value.trim() : ''}; }), ...readMenu()};
}
function dirty() { try { return serialize(formConfig()) !== serialize(saved); } catch { return true; } }
function updateActionSummaries() {
  gestureIds.forEach((id, index) => {
    const type = $(`action-${id}`).value;
    let label = compactActions[type] || actions.find(([value]) => value === type)?.[1] || '不执行动作';
    if (['app', 'app_freeform'].includes(type)) {
      const app = appCatalog.lookup($(`value-${id}`).value);
      if (app) label = (type === 'app_freeform' ? '小窗 · ' : '') + app.label;
    }
    $(`summary-${id}`).textContent = label;
    if (type === 'mijia') $(`summary-${id}`).textContent = mijiaBindingLabel($(`value-${id}`).value);
    $(`edit-${id}`).setAttribute('aria-label', `编辑${titles[index]}动作：${label}`);
  });
}
function updateDirty() {
  updateActionSummaries();
  $('long-output').value = `${$('long-ms').value} ms`; $('double-output').value = `${$('double-ms').value} ms`;
  menuBusy(busy);
  $('save').disabled = busy || !loaded;
  $('save-state').textContent = busy ? '正在保存…' : !loaded ? '配置未加载' : dirty() ? '有未保存的修改' : '设置已同步';
  gestureIds.forEach((id, i) => { $(`test-${id}`).disabled = busy || !loaded || dirty() || saved.actions[i].type === 'none' || !available(); });
  document.querySelectorAll('main input:not([name="appearance"]), main select, main textarea, .app-choice, #refresh, #start-service, #reload-apps').forEach(element => {
    if (!element.closest('#page-mijia')) element.disabled = busy;
  });
  if (!available()) $('start-service').disabled = true;
}
function fill(config) {
  validate(config); saved = structuredClone(config); fillMenu(config);
  $('enabled').checked = config.enabled; $('haptic').checked = config.haptic; $('long-ms').value = config.long_ms; $('double-ms').value = config.double_ms;
  gestureIds.forEach((id, i) => {
    $(`action-${id}`).value = config.actions[i].type;
    $(`value-${id}`).value = config.actions[i].argument;
    $(`shell-${id}`).value = config.actions[i].argument;
    updateArgument(id);
  });
  loaded = true; updateDirty();
}
function runtime(data) {
  const r = data.runtime || {}, status = $('status');
  status.className = 'status';
  let label = '接管已暂停';
  if (r.error && data.config.enabled) { label = '接管异常'; status.classList.add('error'); }
  else if (!data.running) { label = '监听服务未启动'; status.classList.add('error'); }
  else if (r.grabbed && data.config.enabled) { label = '侧键已接管'; status.classList.add('active'); }
  else if (data.config.enabled) label = '正在连接按键…';
  status.querySelector('span').textContent = label;
  $('service-info').textContent = data.running ? '运行中' : '未运行';
  $('last-event').textContent = titles[gestureIds.indexOf(r.last_gesture)] || '尚未识别';
  $('event-count').textContent = String(r.count || 0);
  $('device-info').textContent = r.device ? `${r.device} · Linux 735` : '等待接管';
  $('result-info').textContent = r.busy ? '动作执行中' : !r.last_action ? '—' : r.last_result === 0 ? (r.last_action === 'mijia' ? '已提交米家任务，结果见米家页' : '执行成功') : r.last_result === 124 ? '执行超时（10 秒）' : `退出码 ${r.last_result}`;
  const t = data.torch || {};
  const torchActive = data.running && r.torch_pid > 0 && r.torch_pid === t.pid;
  $('torch-info').textContent = ![...data.config.actions, ...data.config.menu].some(a => a.type === 'torch') ? '未配置手电筒动作' :
    !torchActive ? '服务准备中，或启动失败（见下方日志）' : t.error ? t.error :
    !t.known ? '等待系统状态' : !t.available ? '不可用（相机占用或系统限制）' :
    `${t.enabled ? '已开启' : '已关闭'} · ${t.maximum > 1 ? `亮度 ${t.strength || 0} / ${t.maximum}` : '系统仅开放默认亮度'}`;
  $('logs').textContent = (data.logs || '暂无日志') + (data.torch_logs ? '\n手电筒服务：\n' + data.torch_logs : '') + (data.menu_logs ? '\n菜单组件：\n' + data.menu_logs : '') + (data.app_logs ? '\n应用启动：\n' + data.app_logs : '') + (data.mijia_menu_logs ? '\n米家快捷栏通信：\n' + data.mijia_menu_logs : '') + (data.mijia_logs ? '\n米家执行与状态读回：\n' + data.mijia_logs : '');
  if (r.error && data.config.enabled) notice(r.error);
}
async function refresh(initial = false) {
  if (!available() || busy || refreshBusy || document.hidden) return;
  refreshBusy = true;
  try {
    const data = await api('get');
    if (!loaded || (initial && !dirty())) fill(data.config);
    runtime(data);
  } catch (error) { notice(error.message); }
  finally { refreshBusy = false; }
}
async function save() {
  if (!available()) { toast('界面预览不能写入手机，请从 KernelSU 打开'); return; }
  try {
    const config = validate(formConfig());
    busy = true; updateDirty(); notice('');
    const data = await saveConfiguration(hex(serialize(config)));
    fill(data.config); runtime(data); toast('设置已保存');
  } catch (error) { notice(error.message); }
  finally { busy = false; updateDirty(); }
}
async function testAction(id) {
  if (dirty()) { toast('请先保存设置'); return; }
  busy = true; updateDirty();
  try { const data = await api('test', id); toast(data.result === 0 ? (saved.actions[gestureIds.indexOf(id)].type === 'mijia' ? '米家任务已提交，结果见米家页' : '动作已执行') : `执行失败（${data.result}），请查看运行日志`); }
  catch (error) { notice(error.message); }
  finally { busy = false; updateDirty(); refresh(); }
}
async function packages() {
  if (!available()) { toast('请在手机的 KernelSU 中读取应用'); return; }
  $('reload-apps').disabled = true;
  try {
    const data = await appCatalog.get(true);
    document.dispatchEvent(new Event('sidekey-apps-updated'));
    toast(`已读取 ${data.apps.length} 个可启动应用`);
  } catch (error) { notice(error.message); }
  finally { $('reload-apps').disabled = false; }
}
initNavigation();
initMenuEditor(updateDirty);
buildCards();
initMijia((target, entry) => {
  if (busy || !loaded) throw new Error('请等待模块配置加载完成');
  if (target === 'menu') addMijiaEntry(entry);
  else if (gestureIds.includes(target)) {
    $(`action-${target}`).value = 'mijia'; $(`value-${target}`).value = entry.id;
    updateArgument(target); updateDirty();
  }
  toast('米家动作已加入草稿，请保存设置');
});
document.addEventListener('sidekey-mijia-bindings', () => {
  gestureIds.forEach(id => { if ($(`action-${id}`).value === 'mijia') updateArgument(id); });
  updateActionSummaries();
});
['enabled', 'haptic', 'long-ms', 'double-ms'].forEach(id => $(id).addEventListener('input', updateDirty));
$('save').addEventListener('click', save);
$('copy-logs').addEventListener('click', async () => {
  const button = $('copy-logs'); button.disabled = true; button.textContent = '正在收集日志…';
  try {
    const text = available() ? await api('logs') : $('logs').textContent;
    try { await copyText(text); toast('全部日志已复制'); }
    catch {
      $('log-copy-text').value = text; $('log-copy-fallback').showModal();
      $('log-copy-text').focus(); $('log-copy-text').select();
    }
  } catch (error) { toast(`日志读取失败：${error.message}`); }
  finally { button.disabled = false; button.textContent = '复制全部日志'; }
});
$('log-copy-fallback').addEventListener('close', () => { $('log-copy-text').value = ''; });
$('prepare-menu').addEventListener('click', async () => {
  if (!available()) { toast('请从 KernelSU 中修复快捷菜单'); return; }
  try { await api('prepare-menu'); toast('正在修复，可在下方日志查看结果'); }
  catch (error) { notice(error.message); }
});
$('refresh').addEventListener('click', () => { notice(''); refresh(!dirty()); });
$('reload-apps').addEventListener('click', packages);
$('start-service').addEventListener('click', async () => {
  try { await api('start'); toast('已请求启动监听'); refresh(); } catch (error) { notice(error.message); }
});
if (available()) refresh(true).then(async () => {
  try { await appCatalog.get(); document.dispatchEvent(new Event('sidekey-apps-updated')); }
  catch (error) { notice(`应用信息暂未加载：${error.message}，可点击“勾选应用”重试。`); }
});
else {
  fill(defaultConfig()); notice('界面预览：请从 KernelSU 管理器打开模块 WebUI，以保存和应用设置。');
  $('status').querySelector('span').textContent = '界面预览'; $('start-service').disabled = true;
}
setInterval(() => refresh(), 2500);
document.addEventListener('visibilitychange', () => { if (!document.hidden) refresh(); });
