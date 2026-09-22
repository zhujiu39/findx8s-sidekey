import {mijiaRequest} from './mijia-api.js';
import {bindingName, makePropertyAction, propertyValue, stateLabel} from './mijia-model.js';
import {available} from './bridge.js';

const $ = id => document.getElementById(id);
const node = (tag, cls = '', text) => {
  const item = document.createElement(tag); item.className = cls;
  if (text !== undefined) item.textContent = text;
  return item;
};
let bindings = [], homes = [], home = '', lastStatus = {}, busy = false, initialized = false;
let attach = () => {}, loginTimer, resultTimer, loginPanel, lastFocus;
let loginRenderKey = '', loginPollId = 0;
export const mijiaBindingLabel = id => bindings.find(item => item.id === id)?.name || '米家动作（请到米家页检查）';
function message(text, error = false) {
  $('mijia-message').textContent = text; $('mijia-message').hidden = !text;
  $('mijia-message').classList.toggle('error', error);
}
function button(text, run, cls = 'secondary') {
  const item = node('button', cls, text); item.type = 'button';
  item.addEventListener('click', () => run()); return item;
}
function textBlock(title, text) {
  const div = node('div', 'mijia-empty'); div.append(node('strong', '', title), node('p', 'hint', text)); return div;
}
function icon(kind) {
  const paths = {home:'M3 11 12 3l9 8M5 10v10h14V10M9 20v-7h6v7',
    scene:'m13 2-8 12h7l-1 8 8-12h-7z', device:'M5 7h14v14H5zM8 3h8M8 11h8M8 15h4',
    lamp:'M9 18h6M10 21h4M8 13a6 6 0 1 1 8 0l-1 3H9z',
    plug:'M8 3v5M16 3v5M6 8h12v4a6 6 0 0 1-12 0zM12 18v4'};
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('viewBox', '0 0 24 24'); svg.setAttribute('aria-hidden', 'true');
  const path = document.createElementNS(svg.namespaceURI, 'path'); path.setAttribute('d', paths[kind] || paths.device); svg.append(path); return svg;
}
async function task(work) {
  if (busy) return;
  busy = true; $('page-mijia').setAttribute('aria-busy', 'true'); message('正在处理…');
  document.querySelectorAll('#page-mijia button, #mijia-home').forEach(item => item.disabled = true);
  try { await work(); }
  catch (error) { message(error.message, true); }
  finally {
    busy = false; $('page-mijia').removeAttribute('aria-busy');
    document.querySelectorAll('#page-mijia button, #mijia-home').forEach(item => item.disabled = false);
  }
}
async function status() {
  lastStatus = await mijiaRequest('status');
  bindings = lastStatus.bindings || [];
  document.dispatchEvent(new Event('sidekey-mijia-bindings'));
  $('mijia-account').textContent = lastStatus.loggedIn ? `小米账号 ${lastStatus.account} · 中国大陆` : '未登录 · 中国大陆';
  $('mijia-login').textContent = lastStatus.loggedIn ? '账号管理' : '登录米家';
  $('mijia-home-row').hidden = !lastStatus.loggedIn;
  $('mijia-last').textContent = lastStatus.last?.message ? `${new Date(lastStatus.last.time).toLocaleTimeString()} · ${lastStatus.last.message}` : '暂无执行记录';
  renderBindings(); return lastStatus;
}
async function loadHome() {
  $('mijia-scenes').replaceChildren(textBlock('正在读取', '同步米家手动场景…'));
  $('mijia-devices').replaceChildren(textBlock('正在读取', '同步当前家庭设备…'));
  if (!home) return;
  try {
    const data = await mijiaRequest('catalog', {home});
    const scenes = $('mijia-scenes'); scenes.replaceChildren();
    if (!data.scenes.length) scenes.append(textBlock('没有手动场景', data.sceneError || '在米家 App 中创建手动场景，再点击刷新。'));
    data.scenes.forEach(scene => {
      const item = button('', () => showScene(scene), 'mijia-scene');
      item.append(icon('scene'), node('strong', '', scene.name), node('span', '', '↗')); scenes.append(item);
    });
    const devices = $('mijia-devices'); devices.replaceChildren();
    if (!data.devices.length) devices.append(textBlock('这个家还没有设备', '请先在米家 App 添加设备，或切换家庭。'));
    data.devices.forEach(device => {
      const item = button('', () => task(() => showDevice(device)), 'mijia-device');
      const badge = node('span', 'mijia-device-icon'); badge.append(icon(/light|lamp/.test(device.model) ? 'lamp' : /plug|outlet/.test(device.model) ? 'plug' : 'device'));
      item.append(badge, node('strong', '', device.name), node('small', device.online ? '' : 'offline', device.online ? '在线 · 查看控制' : '离线 · 查看详情'));
      devices.append(item);
    });
    message(data.sceneError ? `设备已同步；场景读取失败：${data.sceneError}` : `已同步 ${data.devices.length} 台设备 · ${data.scenes.length} 个场景`, !!data.sceneError);
  } catch (error) {
    $('mijia-scenes').replaceChildren(textBlock('同步失败', '点击刷新重新读取。'));
    $('mijia-devices').replaceChildren(textBlock('设备未更新', '请检查网络和米家账号。'));
    throw error;
  }
}
async function refresh() {
  const account = await status();
  if (!account.loggedIn) {
    $('mijia-scenes').replaceChildren(textBlock('让侧键连接你的家', '登录后，你的手动场景会显示在这里。'));
    $('mijia-devices').replaceChildren(textBlock('登录后同步设备', '使用米家扫一扫授权，无需在此输入密码。'));
    message(''); initialized = true; return;
  }
  const data = await mijiaRequest('homes'); homes = data.homes;
  if (!homes.some(item => item.id === home)) home = homes[0]?.id || '';
  $('mijia-home').replaceChildren(...homes.map(item => new Option(item.name, item.id)));
  $('mijia-home').value = home;
  if (!homes.length) {
    $('mijia-scenes').replaceChildren(textBlock('没有可用家庭', '请先在米家 App 创建或加入家庭。'));
    $('mijia-devices').replaceChildren(); message('当前账号没有可用家庭'); return;
  }
  await loadHome(); initialized = true;
}
function openDialog(title) {
  closeDialog(); lastFocus = document.activeElement;
  const dialog = $('mijia-dialog'); $('mijia-dialog-title').textContent = title; $('mijia-dialog-body').replaceChildren();
  $('mijia-dialog-message').textContent = ''; dialog.showModal(); return $('mijia-dialog-body');
}
function closeDialog() {
  clearTimeout(loginTimer); loginPanel = null; loginRenderKey=''; loginPollId++;
  if ($('mijia-dialog').open) $('mijia-dialog').close();
}
async function dialogTask(work) {
  const dialog = $('mijia-dialog'); if (dialog.dataset.busy === 'true') return;
  dialog.dataset.busy = 'true'; $('mijia-dialog-message').textContent = '正在处理…';
  const controls = [...dialog.querySelectorAll('button:not(#mijia-dialog-close),input,select')];
  controls.forEach(item => item.disabled = true);
  try { await work(); }
  catch (error) { $('mijia-dialog-message').textContent = error.message; }
  finally { delete dialog.dataset.busy; controls.forEach(item => { if (item.isConnected) item.disabled = false; }); }
}
async function execute(action) {
  const result = await mijiaRequest('run', {action});
  $('mijia-dialog-message').textContent = result.message; await status();
}
function bindingTools(body, makeAction, name) {
  const tools = node('div', 'mijia-actions');
  const select = node('select'); select.setAttribute('aria-label', '绑定位置');
  [['menu','快捷栏'],['single','短按'],['double','双击'],['long','长按']].forEach(([value,label]) => select.add(new Option(label,value)));
  tools.append(select, button('添加绑定', () => dialogTask(async () => {
    const entry = await mijiaRequest('binding-save', {name:typeof name==='function' ? name() : name, action:makeAction()});
    attach(select.value, entry); await status();
    $('mijia-dialog-message').textContent = '已加入设置草稿，请点击底部“保存设置”使绑定生效';
  })));
  body.append(tools);
}
function showScene(scene) {
  const body = openDialog(scene.name); const action = {kind:'scene',home:scene.home,scene:scene.id};
  body.append(node('p','hint','执行米家 App 中配置的完整手动场景。'), button('执行场景', () => dialogTask(() => execute(action)), 'primary'));
  bindingTools(body, () => action, bindingName(scene.name, '场景'));
}
function valueInput(property, state) {
  let input;
  if (property.values?.length || property.format === 'bool') {
    input = node('select');
    const values = property.values?.length ? property.values.map(item => [JSON.stringify(item.value), item.name]) : [['true','开启'],['false','关闭']];
    values.forEach(([value,name]) => input.add(new Option(name,value)));
    if (state?.code === 0) input.value = JSON.stringify(state.value);
    if (input.selectedIndex < 0) input.selectedIndex = 0;
  } else {
    input = node('input'); input.type = property.format === 'string' ? 'text' : 'number';
    input.value = state?.code === 0 ? String(state.value) : property.range?.[0] ?? '';
    if (property.range?.length >= 2) { input.min=property.range[0]; input.max=property.range[1]; input.step=property.range[2] || 'any'; }
    else if (input.type === 'number') input.step = property.format === 'float' ? 'any' : '1';
  }
  input.setAttribute('aria-label', property.name); return input;
}
async function showDevice(device) {
  const data = await mijiaRequest('device', {home:device.home,did:device.did});
  message('设备信息已读取'); const body = openDialog(device.name);
  body.append(node('p', 'hint', device.model + ' · ' + (device.online ? '设备状态按需读取' : '上次同步离线')));
  body.append(button('刷新设备状态', () => dialogTask(async () => { await showDevice(device); })));
  if (!data.spec.properties.length && !data.spec.actions.length) body.append(textBlock('暂无可用操作', '可以在米家 App 建立手动场景，再把场景绑定到侧键。'));
  data.spec.properties.forEach(property => {
    if (!property.read && !property.write) return;
    const card = node('section', 'mijia-property'), state = data.states.find(item => item.siid === property.siid && item.piid === property.piid);
    card.append(node('small','hint',property.service), node('h3','',property.name), node('p','mijia-value',property.read ? stateLabel(property,state) : '只写属性'));
    if (property.write) {
      const input = valueInput(property,state), kind = node('select'); kind.setAttribute('aria-label', property.name + '操作');
      kind.add(new Option('设置为','set'));
      if (property.format === 'bool' && property.read) kind.add(new Option('切换当前状态','toggle'));
      kind.addEventListener('change', () => input.hidden = kind.value === 'toggle');
      const row = node('div','mijia-actions'); row.append(kind,input); card.append(row);
      if (property.range?.length >= 2) card.append(node('p','hint',`范围 ${property.range[0]}～${property.range[1]} · 步长 ${property.range[2] ?? 1}`));
      const action = () => makePropertyAction(device.home,device,property,kind.value,input.value);
      card.append(button('执行', () => dialogTask(() => execute(action())), 'primary'));
      bindingTools(card,action,()=>bindingName(device.name,property.name+' · '+(kind.value==='toggle' ? '切换' : input.tagName==='SELECT' ? input.selectedOptions[0].textContent : input.value)));
    }
    body.append(card);
  });
  data.spec.actions.forEach(action => {
    const card = node('section','mijia-property'); card.append(node('small','hint',action.service),node('h3','',action.name));
    const inputs = action.in.map(piid => {
      const property = data.spec.properties.find(item => item.siid === action.siid && item.piid === piid);
      if (!property) return null;
      const input = valueInput(property); const label = node('label','mijia-field',property.name); label.append(input); card.append(label); return {property,input};
    });
    if (inputs.some(item => !item)) card.append(node('p','hint','设备动作的参数定义不完整，暂不可执行。'));
    else {
      const descriptor = () => ({kind:'action',home:device.home,did:device.did,siid:action.siid,aiid:action.aiid,values:inputs.map(item => propertyValue(item.property,item.input.value))});
      card.append(button('执行动作', () => dialogTask(() => execute(descriptor())), 'primary'));
      bindingTools(card,descriptor,bindingName(device.name,action.name));
    }
    body.append(card);
  });
}
function renderBindings() {
  const host = $('mijia-bindings'); host.replaceChildren();
  $('mijia-bindings-section').hidden = !bindings.length;
  bindings.forEach(entry => {
    const row = node('div','mijia-binding'); row.append(node('strong','',entry.name), button('使用', () => {
      const body = openDialog(entry.name), target = node('select'); target.setAttribute('aria-label','绑定位置');
      [['menu','快捷栏'],['single','短按'],['double','双击'],['long','长按']].forEach(([value,label])=>target.add(new Option(label,value)));
      body.append(target, button('加入设置', () => { attach(target.value,entry); $('mijia-dialog-message').textContent='已加入草稿，请保存设置'; },'primary'),
        button('移除米家动作', () => dialogTask(async () => {
          await mijiaRequest('binding-delete',{id:entry.id}); await status();
          $('mijia-dialog-message').textContent='动作已移除；已有手势或快捷项也需要移除或重新选择';
        })));
    })); host.append(row);
  });
}
export async function chooseMijia(onSelect) {
  try {
    await status(); const body = openDialog('选择米家动作');
    if (!bindings.length) body.append(textBlock('还没有米家动作','到米家页选择设备或场景，点击“添加绑定”。'));
    bindings.forEach(entry => body.append(button(entry.name,()=>{onSelect(entry);closeDialog();},'mijia-choice')));
  } catch (error) { message(error.message,true); $('tab-mijia').click(); }
}
function loginView(data) {
  if (!loginPanel || !$('mijia-dialog').open) return;
  clearTimeout(loginTimer);
  const key=JSON.stringify([data.state,data.image,data.message]);
  if (key===loginRenderKey) {
    if (['waiting','preparing'].includes(data.state)) loginTimer=setTimeout(pollLogin,1800);
    return;
  }
  loginRenderKey=key;
  loginPanel.replaceChildren();
  if (data.image && data.state === 'waiting') {
    const image = node('img','mijia-qr'); image.src=data.image; image.alt='米家账号登录二维码'; loginPanel.append(image);
    loginPanel.append(node('p','hint','用米家 App「扫一扫」授权。本机操作可截图，再用扫一扫的相册功能识别。'));
  }
  loginPanel.append(node('p','',data.message || '正在等待登录'));
  if (['waiting','preparing'].includes(data.state)) {
    loginPanel.append(button('取消登录',()=>dialogTask(async()=>{loginPollId++;await mijiaRequest('login-cancel');loginView({state:'cancelled',message:'登录已取消'});$('mijia-dialog-message').textContent='';})));
    loginTimer=setTimeout(pollLogin,1800);
  } else if (data.state !== 'done') loginPanel.append(button('重新生成二维码',()=>dialogTask(startLogin),'primary'));
}
async function pollLogin() {
  if (!loginPanel || !$('mijia-dialog').open) return;
  const pollId=loginPollId;
  try {
    const data=await mijiaRequest('login-status');
    if (pollId!==loginPollId) return;
    loginView(data.login);
    if (data.login.state==='done') { closeDialog(); await task(refresh); }
  } catch (error) { if (pollId===loginPollId) loginView({state:'error',message:error.message}); }
}
async function startLogin() {
  loginPollId++;
  await mijiaRequest('login-start'); $('mijia-dialog-message').textContent=''; await pollLogin();
}
async function accountDialog() {
  const body=openDialog('米家账号'); loginPanel=body;
  if (lastStatus.loggedIn) {
    body.append(node('p','',`小米账号 ${lastStatus.account}`),node('p','hint','登录凭据仅保存在手机模块私有目录。退出会删除凭据和米家动作，已配置的米家绑定需重新选择。'));
    body.append(button('退出并清除登录',()=>dialogTask(async()=>{await mijiaRequest('logout');closeDialog();await task(refresh);})));return;
  }
  if (['waiting','preparing'].includes(lastStatus.login?.state)) loginView(lastStatus.login);
  else {
    body.append(node('p','hint','使用米家 App 扫码登录中国大陆账号。无需输入密码，账号凭据不会显示在 WebUI。'));
    body.append(button('生成登录二维码',()=>dialogTask(startLogin),'primary'));
  }
}
export function initMijia(onAttach) {
  attach=onAttach;
  $('mijia-refresh').addEventListener('click',()=>task(refresh));
  $('mijia-home').addEventListener('change',()=>{
    if (busy) { $('mijia-home').value=home; return; }
    home=$('mijia-home').value;task(loadHome);
  });
  $('mijia-login').addEventListener('click',()=>task(async()=>{await status();message('');await accountDialog();}));
  $('mijia-dialog-close').addEventListener('click',closeDialog);
  $('mijia-dialog').addEventListener('close',()=>{clearTimeout(loginTimer);loginPanel=null;loginRenderKey='';loginPollId++;lastFocus?.focus();});
  document.addEventListener('sidekey-page',event=>{
    clearTimeout(resultTimer);
    if (event.detail!=='mijia') return;
    if (!initialized) task(refresh); else task(async()=>{await status();message('');});
    const update=async()=>{
      if ($('page-mijia').hidden) return;
      if (!busy && !document.hidden && available()) try { await status(); } catch (error) { $('mijia-last').textContent=error.message; }
      resultTimer=setTimeout(update,4000);
    };resultTimer=setTimeout(update,4000);
  });
  if (available()) status().catch(()=>{});
}
