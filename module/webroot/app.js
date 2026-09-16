import {actions, gestureIds, defaultConfig, validate, serialize, hex} from './model.js';
import {api, exec, available} from './bridge.js';
const $ = id => document.getElementById(id);
let saved = defaultConfig(), loaded = false, busy = false, refreshBusy = false;
let toastTimer;
const titles = ['短按', '双击', '长按'];
const descriptions = ['轻按一次，快速执行', '连续两次，另一个捷径', '按住片刻，触发专属动作'];
const iconLabels = ['·', '··', '—'];

function toast(text) {
  $('toast').textContent = text; $('toast').hidden = false;
  clearTimeout(toastTimer); toastTimer = setTimeout(() => { $('toast').hidden = true; }, 4000);
}
function notice(text) { $('notice').textContent = text; $('notice').hidden = !text; }
function buildCards() {
  gestureIds.forEach((id, index) => {
    const card = document.createElement('article'); card.className = 'gesture-card';
    card.innerHTML = `<div class="gesture-top"><span class="gesture-icon" aria-hidden="true">${iconLabels[index]}</span><div class="gesture-title"><h3>${titles[index]}</h3><p>${descriptions[index]}</p></div><button class="test" id="test-${id}" type="button">测试 ↗</button></div><div class="selector"><select id="action-${id}" aria-label="${titles[index]}动作"></select></div><div class="argument" id="argument-${id}" hidden><label for="value-${id}"></label><input id="value-${id}" autocomplete="off" spellcheck="false"><textarea id="shell-${id}" aria-label="${titles[index]} Shell 命令" spellcheck="false" hidden></textarea><p></p></div>`;
    $('gestures').append(card);
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
  block.hidden = !['app', 'keycode', 'shell'].includes(type);
  input.hidden = type === 'shell'; shell.hidden = type !== 'shell';
  const label = block.querySelector('label');
  label.htmlFor = type === 'shell' ? `shell-${id}` : `value-${id}`;
  label.textContent = type === 'app' ? '应用包名' : type === 'keycode' ? 'Android 按键码' : 'Shell 命令（Root）';
  input.type = type === 'keycode' ? 'number' : 'text';
  input.placeholder = type === 'app' ? 'com.android.settings' : '例如 3（主页）';
  if (type === 'app') input.setAttribute('list', 'packages'); else input.removeAttribute('list');
  shell.placeholder = '例如：input keyevent 3';
  block.querySelector('p').textContent = type === 'app' ? '可在下方运行状态中读取已安装应用包名。' : type === 'keycode' ? '使用 Android KeyEvent 编码，不是底层 Linux 输入键码。' : '使用系统 Shell 执行，最长 10 秒；只运行你确认过的命令。';
}
function formConfig() {
  return {enabled: $('enabled').checked, haptic: $('haptic').checked, long_ms: Number($('long-ms').value), double_ms: Number($('double-ms').value),
    actions: gestureIds.map(id => { const type = $(`action-${id}`).value; return {type, argument: type === 'shell' ? $(`shell-${id}`).value : ['app','keycode'].includes(type) ? $(`value-${id}`).value.trim() : ''}; })};
}
function dirty() { return JSON.stringify(formConfig()) !== JSON.stringify(saved); }
function updateDirty() {
  $('long-output').value = `${$('long-ms').value} ms`; $('double-output').value = `${$('double-ms').value} ms`;
  $('save').disabled = busy || !loaded;
  $('save-state').textContent = busy ? '正在保存…' : !loaded ? '配置未加载' : dirty() ? '有未保存的修改' : '设置已同步';
  gestureIds.forEach((id, i) => { $(`test-${id}`).disabled = busy || !loaded || dirty() || saved.actions[i].type === 'none' || !available(); });
  document.querySelectorAll('input, select, textarea, #refresh, #start-service, #reload-apps').forEach(element => { element.disabled = busy; });
  if (!available()) $('start-service').disabled = true;
}
function fill(config) {
  validate(config); saved = structuredClone(config);
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
  $('result-info').textContent = r.busy ? '动作执行中' : !r.last_action ? '—' : r.last_result === 0 ? '执行成功' : r.last_result === 124 ? '执行超时（10 秒）' : `退出码 ${r.last_result}`;
  const t = data.torch || {};
  const torchActive = data.running && r.torch_pid > 0 && r.torch_pid === t.pid;
  $('torch-info').textContent = !data.config.actions.some(a => a.type === 'torch') ? '未配置手电筒动作' :
    !torchActive ? '服务准备中，或启动失败（见下方日志）' : t.error ? t.error :
    !t.known ? '等待系统状态' : !t.available ? '不可用（相机占用或系统限制）' :
    `${t.enabled ? '已开启' : '已关闭'} · ${t.maximum > 1 ? `亮度 ${t.strength || 0} / ${t.maximum}` : '系统仅开放默认亮度'}`;
  $('logs').textContent = (data.logs || '暂无日志') + (data.torch_logs ? '\n手电筒服务：\n' + data.torch_logs : '');
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
    const data = await api('save', hex(serialize(config)));
    fill(data.config); runtime(data); toast('已保存，配置会在半秒内应用');
  } catch (error) { notice(error.message); }
  finally { busy = false; updateDirty(); }
}
async function testAction(id) {
  if (dirty()) { toast('请先保存设置，再测试动作'); return; }
  busy = true; updateDirty();
  try { const data = await api('test', id); toast(data.result === 0 ? '动作已执行' : `动作退出码 ${data.result}`); }
  catch (error) { notice(error.message); }
  finally { busy = false; updateDirty(); refresh(); }
}
async function packages() {
  if (!available()) { toast('请在手机的 KernelSU 中读取应用'); return; }
  $('reload-apps').disabled = true;
  try {
    const text = await exec('/system/bin/cmd package list packages --user current');
    const list = text.split('\n').filter(line => line.startsWith('package:')).map(line => line.slice(8).trim()).sort();
    $('packages').replaceChildren(...list.map(name => new Option(name, name)));
    toast(`已读取 ${list.length} 个应用包名`);
  } catch (error) { notice(error.message); }
  finally { $('reload-apps').disabled = false; }
}
buildCards();
['enabled', 'haptic', 'long-ms', 'double-ms'].forEach(id => $(id).addEventListener('input', updateDirty));
$('save').addEventListener('click', save);
$('refresh').addEventListener('click', () => { notice(''); refresh(!dirty()); });
$('reload-apps').addEventListener('click', packages);
$('start-service').addEventListener('click', async () => {
  try { await api('start'); toast('已请求启动监听'); refresh(); } catch (error) { notice(error.message); }
});
if (available()) refresh(true);
else {
  fill(defaultConfig()); notice('界面预览：请从 KernelSU 管理器打开模块 WebUI，以保存和应用设置。');
  $('status').querySelector('span').textContent = '界面预览'; $('start-service').disabled = true;
}
setInterval(() => refresh(), 2500);
document.addEventListener('visibilitychange', () => { if (!document.hidden) refresh(); });
