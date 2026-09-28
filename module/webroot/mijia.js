import {symbol} from './symbols.js';
import {mijiaRequest} from './mijia-api.js';
import {bindingName, makePropertyAction, makeReadingAction, readingProperties, propertyValue, stateLabel} from './mijia-model.js';
import {available} from './bridge.js';
import {createHomeView, deviceSymbol} from './home-view.js';

const $ = id => document.getElementById(id);
const node = (tag, cls = '', text) => {
  const item = document.createElement(tag); item.className = cls;
  if (text !== undefined) item.textContent = text;
  return item;
};
let bindings = [], home = '', lastStatus = {}, busy = false, initialized = false;
let attach = () => {}, loginTimer, resultTimer, loginPanel, lastFocus;
let loginRenderKey = '', loginPollId = 0, dialogRevision = 0, bindingRenderKey = '';
let homeView;
export const mijiaBindingLabel = id => bindings.find(item => item.id === id)?.name || '米家动作（请到米家页检查）';
export const mijiaBindingIsReading = id => bindings.find(item => item.id === id)?.kind === 'read';
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
  // 装饰性状态圆点不应阻断账号、家庭与设备数据的加载。
  $('mijia-connection')?.classList.toggle('connected', !!lastStatus.loggedIn);
  $('mijia-login').setAttribute('aria-label', lastStatus.loggedIn ? '米家账号管理' : '登录米家');
  $('mijia-home-row').hidden = !lastStatus.loggedIn;
  if (!lastStatus.loggedIn) $('mijia-count').textContent = '登录后同步家庭与设备';
  $('mijia-last').textContent = lastStatus.last?.message ? `${new Date(lastStatus.last.time).toLocaleTimeString()} · ${lastStatus.last.message}` : '暂无执行记录';
  renderBindings(); return lastStatus;
}
async function loadHome() {
  if (!home) return;
  homeView.loading();
  $('home-title').textContent = $('mijia-home').selectedOptions[0]?.textContent || '我的家';
  try {
    const data = await mijiaRequest('catalog', {home});
    homeView.show(data);
    message(data.sceneError ? `设备已同步；场景读取失败：${data.sceneError}` : '', !!data.sceneError);
  } catch (error) {
    homeView.empty('暂时无法同步', '请检查网络和米家账号，再点击右上角刷新。');
    $('mijia-count').textContent = '同步失败，可点击刷新重试';
    throw error;
  }
}
async function refresh() {
  const account = await status();
  if (!account.loggedIn) {
    home = ''; homeView.reset(); $('home-title').textContent = '我的家';
    homeView.empty('连接米家', '用米家 App 扫码，同步你的家庭、设备与手动场景。', true);
    message(''); initialized = true; return;
  }
  const data = await mijiaRequest('homes'), homes = data.homes;
  if (!homes.some(item => item.id === home)) home = homes[0]?.id || '';
  $('mijia-home').replaceChildren(...homes.map(item => new Option(item.name, item.id)));
  $('mijia-home').value = home;
  if (!homes.length) {
    homeView.empty('还没有家庭', '在米家 App 创建或加入家庭后，点击右上角刷新。');
    $('mijia-count').textContent = '当前账号没有可用家庭'; message(''); initialized = true; return;
  }
  await loadHome(); initialized = true;
}
function openDialog(title) {
  closeDialog(); lastFocus = document.activeElement;
  dialogRevision++;
  const dialog = $('mijia-dialog'); $('mijia-dialog-title').textContent = title; $('mijia-dialog-body').replaceChildren();
  $('mijia-dialog-message').textContent = ''; dialog.showModal(); return $('mijia-dialog-body');
}
function closeDialog() {
  dialogRevision++; delete $('mijia-dialog').dataset.busy;
  clearTimeout(loginTimer); loginPanel = null; loginRenderKey=''; loginPollId++;
  if ($('mijia-dialog').open) $('mijia-dialog').close();
}
async function dialogTask(work) {
  const dialog = $('mijia-dialog'); if (dialog.dataset.busy === 'true') return;
  const revision = dialogRevision;
  dialog.dataset.busy = 'true'; $('mijia-dialog-message').textContent = '正在处理…';
  const controls = [...dialog.querySelectorAll('button:not(#mijia-dialog-close),input,select')];
  controls.forEach(item => item.disabled = true);
  try {
    await work();
    if (revision === dialogRevision && $('mijia-dialog-message').textContent === '正在处理…') $('mijia-dialog-message').textContent = '';
  }
  catch (error) { if (revision === dialogRevision) $('mijia-dialog-message').textContent = error.message; }
  finally {
    if (revision === dialogRevision) {
      delete dialog.dataset.busy; controls.forEach(item => { if (item.isConnected) item.disabled = false; });
    }
  }
}
function bindingTools(body, makeAction, name, title = '加入快捷菜单') {
  body.append(button(title, () => dialogTask(async () => {
    const entry = await mijiaRequest('binding-save', {name:typeof name==='function' ? name() : name, action:makeAction()});
    attach(entry); await status();
    $('mijia-dialog-message').textContent = '已加入快捷菜单，请保存设置';
  }), 'primary mijia-add'));
}
function showScene(scene) {
  const body = openDialog(scene.name); const action = {kind:'scene',home:scene.home,scene:scene.id};
  const hero = node('div','home-scene-detail');
  hero.append(symbol('bolt.fill'),node('h3','',scene.name),node('p','','米家手动场景'));
  body.append(hero,node('p','hint','加入后保存设置，即可从侧键快捷菜单执行此场景。'));
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
function readingLabelEditor(property, value) {
  const field = node('details','mijia-reading-label'), label = node('label','mijia-field','数值前文案');
  const heading = node('summary','','自定义读数文案'); heading.append(symbol('chevron.down')); field.append(heading);
  const input = node('input'); input.type='text'; input.maxLength=60;
  input.value=property.label ?? property.displayName ?? property.name;
  input.placeholder='留空只显示数值';
  input.setAttribute('aria-label',`${property.service} ${property.displayName || property.name} 数值前文案`);
  label.append(input);
  const preview = node('p','mijia-reading-example');
  const update = () => { preview.textContent=`显示效果：${input.value.trim() ? input.value.trim()+'：' : ''}${value}`; };
  input.addEventListener('input',update); update(); field.append(label,preview); return {field,input};
}
export async function editReadingLabels(id) {
  if ($('mijia-dialog').dataset.busy === 'true') return;
  const body = openDialog('编辑读数文案'), loading = node('p','hint','正在读取卡片…'); body.append(loading);
  await dialogTask(async () => {
    const data = await mijiaRequest('binding-detail',{id});
    if (!loading.isConnected || !$('mijia-dialog').open) return;
    body.replaceChildren(node('p','hint','每项文案独立设置，留空只显示数值。数值和单位由米家提供。'));
    const fields = data.properties.map(property => {
      const card = node('section','mijia-property');
      card.append(node('small','hint',property.service),node('h3','',property.name));
      const editor = readingLabelEditor(property,`米家数值${property.displayUnit ? ' '+property.displayUnit : ''}`);
      card.append(editor.field); body.append(card); return {property,input:editor.input};
    });
    body.append(button('保存文案',()=>dialogTask(async()=>{
      const properties = readingProperties(fields.map(({property,input})=>({...property,label:input.value})));
      await mijiaRequest('binding-labels',{id,properties});
      $('mijia-dialog-message').textContent='文案已保存，重新打开快捷栏生效';
    }),'primary mijia-save-labels'));
    $('mijia-dialog-message').textContent='';
  });
}
function propertyGroup(title, detail, open = false) {
  const group = node('details','mijia-property'); group.open = open;
  const heading = node('summary'), copy = node('span');
  copy.append(node('strong','',title)); if (detail) copy.append(node('small','',detail));
  heading.append(copy,symbol('chevron.down'));
  const content = node('div','mijia-property-content'); group.append(heading,content);
  return {group,content};
}
async function showDevice(device) {
  const body = openDialog(device.name);
  await dialogTask(() => loadDevice(body, device));
}
async function loadDevice(body, device) {
  const loading = node('p','hint','正在读取设备状态…'); body.replaceChildren(loading);
  let data;
  try { data = await mijiaRequest('device', {home:device.home,did:device.did}); }
  catch (error) {
    if (loading.isConnected && $('mijia-dialog').open) {
      loading.textContent = '暂时无法读取设备';
      body.append(button('重新读取', () => dialogTask(() => loadDevice(body,device))));
    }
    throw error;
  }
  // 关闭面板或切换设备后，旧的请求不能重新打开或覆盖新面板。
  if (!loading.isConnected || !$('mijia-dialog').open) return;
  device = data.device || device; body.replaceChildren();
  const hero = node('div','home-device-detail'), mark = node('span','home-detail-icon'), copy = node('div','home-detail-copy');
  mark.append(symbol(deviceSymbol(device)));
  copy.append(node('strong','',device.name),node('p','',device.room || '未分组'));
  hero.append(mark,copy); body.append(hero);
  const readings = data.spec.properties.filter(property => property.reading);
  if (readings.length) {
    const overview = node('div','home-reading-overview');
    readings.slice(0,4).forEach(property => {
      const state = data.states.find(item => item.siid === property.siid && item.piid === property.piid);
      const tile = node('div','home-reading-stat'), value = node('strong',state?.code === 0 && state.value != null ? '' : 'is-unavailable',stateLabel(property,state));
      tile.append(value,node('span','',property.displayName || property.name),node('small','',property.service)); overview.append(tile);
    });
    body.append(overview,node('p','home-reading-note',device.online ? '米家云端最近上报值' : '设备离线 · 以下为云端最近上报值，可能不是当前状态'));
  }
  body.append(button('刷新设备状态', () => dialogTask(() => loadDevice(body, device))));
  if (data.stateError) body.append(node('p', 'hint', data.stateError));
  const readingGroup = propertyGroup('配置读数卡片', readings.length ? `${readings.length} 项可用读数 · 选择要显示的内容` : '查看设备是否支持只读数据');
  const measurements = readingGroup.content;
  measurements.append(node('p','hint','每张卡片选择 1～4 项，可添加多张。设定温度与实际测量值分开显示。'));
  if (readings.length) {
    const selected = new Set(readings.filter(property => /^(?:temperature|relative-humidity)$/.test(property.type)).slice(0,4));
    const count = node('p','mijia-value');
    const editors = new Map();
    const update = () => { count.textContent = `已选择 ${selected.size} / 4 项`; };
    readings.forEach(property => {
      const row = node('label','mijia-reading-choice'), check = node('input'); check.type='checkbox'; check.checked=selected.has(property);
      const copy = node('span'), state = data.states.find(item => item.siid === property.siid && item.piid === property.piid);
      copy.append(node('strong','',property.displayName || property.name),node('small','',property.service));
      row.append(check,copy,node('span','mijia-reading-value',stateLabel(property,state)));
      const editor = readingLabelEditor(property,stateLabel(property,state));
      editors.set(property,editor.input); editor.field.hidden=!check.checked;
      check.addEventListener('change',()=>{
        if (check.checked && selected.size >= 4) {
          check.checked=false; $('mijia-dialog-message').textContent='每张卡片最多 4 项，可另建卡片显示其他温区或读数'; return;
        }
        if (check.checked) selected.add(property); else selected.delete(property);
        editor.field.hidden=!check.checked;
        update();
      });
      measurements.append(row,editor.field);
    });
    measurements.append(count); update();
    const chosen = () => readings.filter(property => selected.has(property)).map(property=>({...property,label:editors.get(property).value}));
    bindingTools(measurements,()=>makeReadingAction(device.home,device,chosen()),
      ()=>bindingName(device.name,chosen().map(property => property.displayName || property.name).join(' / ')), '添加读数卡片');
    measurements.append(node('p','hint','展开快捷栏时获取云端最近上报值。离线时仍会标明离线；没有上报数据的项目显示“暂无数据”。'));
  } else measurements.append(node('p','hint','该设备的 MIOT 规格没有可显示的只读数据；不会用目标温度冒充当前温度。'));
  body.append(readingGroup.group);
  if (data.spec.properties.some(property => property.write || property.setpoint) || data.spec.actions.length)
    body.append(node('h3','mijia-section-title','控制与设定'));
  data.spec.properties.forEach(property => {
    if (!property.write && !property.setpoint) return;
    const state = data.states.find(item => item.siid === property.siid && item.piid === property.piid);
    const boolean = property.format === 'bool';
    const control = propertyGroup(property.displayName || property.name, boolean ? '' : property.service), card = control.content;
    if (boolean) {
      const known = device.online && state?.code === 0 && typeof state.value === 'boolean';
      const mark = node('span',`mijia-property-power${known && state.value ? ' is-on' : ''}`);
      mark.append(symbol(known ? 'power' : 'minus'));
      const label = known ? state.value ? '已开启' : '已关闭' : '状态未确认';
      mark.setAttribute('role','img'); mark.setAttribute('aria-label',label); mark.title = label;
      const heading = control.group.querySelector('summary');
      heading.insertBefore(mark,heading.lastElementChild);
      control.group.classList.add('mijia-boolean-property');
      if (property.service) card.append(node('p','hint',property.service));
    } else card.append(node('p','mijia-value',property.read || property.notify ? stateLabel(property,state) : '只写属性'));
    if (property.setpoint) card.append(node('p','hint','这是设定值，不是设备当前测量温度。'));
    if (!property.write) { body.append(control.group); return; }
    if (property.format === 'bool' && !property.values?.length) {
      const choice = node('select'); choice.setAttribute('aria-label', property.name + '快捷菜单动作');
      choice.add(new Option('开启','true')); choice.add(new Option('关闭','false'));
      if (property.read) choice.add(new Option('切换开关','toggle'));
      choice.value = property.read ? 'toggle' : 'true';
      card.append(choice);
      const action = () => makePropertyAction(device.home,device,property,choice.value === 'toggle' ? 'toggle' : 'set',choice.value);
      bindingTools(card,action,()=>bindingName(device.name,property.name+' · '+choice.selectedOptions[0].textContent));
    } else {
      const input = valueInput(property,state); card.append(input);
      if (property.range?.length >= 2) card.append(node('p','hint',`范围 ${property.range[0]}～${property.range[1]} · 步长 ${property.range[2] ?? 1}`));
      const action = () => makePropertyAction(device.home,device,property,'set',input.value);
      bindingTools(card,action,()=>bindingName(device.name,property.name+' · '+(input.tagName==='SELECT' ? input.selectedOptions[0].textContent : input.value)));
    }
    body.append(control.group);
  });
  data.spec.actions.forEach(action => {
    const control = propertyGroup(action.name, action.service), card = control.content;
    const inputs = action.in.map(piid => {
      const property = data.spec.properties.find(item => item.siid === action.siid && item.piid === piid);
      if (!property) return null;
      const input = valueInput(property); const label = node('label','mijia-field',property.name); label.append(input); card.append(label); return {property,input};
    });
    if (inputs.some(item => !item)) card.append(node('p','hint','设备动作的参数定义不完整，暂不可执行。'));
    else {
      const descriptor = () => ({kind:'action',home:device.home,did:device.did,siid:action.siid,aiid:action.aiid,values:inputs.map(item => propertyValue(item.property,item.input.value))});
      bindingTools(card,descriptor,bindingName(device.name,action.name));
    }
    body.append(control.group);
  });
}
function renderBindings() {
  const key = JSON.stringify(bindings);
  if (key === bindingRenderKey) return;
  bindingRenderKey = key;
  const host = $('mijia-bindings'); host.replaceChildren();
  $('mijia-bindings-section').hidden = !bindings.length;
  bindings.forEach(entry => {
    const row = node('div','mijia-binding'); row.append(node('strong','',entry.name), button('管理', () => {
      const body = openDialog(entry.name);
      if (entry.kind === 'read') body.append(button('编辑读数文案',()=>editReadingLabels(entry.id),'primary'));
      body.append(button('再次加入快捷菜单', () => {
        attach(entry); $('mijia-dialog-message').textContent='已加入快捷菜单，请保存设置';
      },'primary'),
        button('移除米家动作', () => dialogTask(async () => {
          await mijiaRequest('binding-delete',{id:entry.id}); await status();
          $('mijia-dialog-message').textContent='动作已移除；已有手势或快捷项也需要移除或重新选择';
        })));
    })); host.append(row);
  });
}
export async function chooseMijia(onSelect, allowReadings = false) {
  try {
    await status(); const body = openDialog('选择米家动作');
    const choices = bindings.filter(entry => allowReadings || entry.kind !== 'read');
    if (!choices.length) body.append(textBlock('还没有可选项目','到米家页添加设备或场景。读数卡片仅用于快捷栏显示，不能绑定手势控制。'));
    choices.forEach(entry => body.append(button(entry.name + (entry.kind === 'read' ? ' · 只读' : ''),()=>{onSelect(entry);closeDialog();},'mijia-choice')));
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
  homeView = createHomeView({openDevice:showDevice, openScene:showScene, openAccount:()=>$('mijia-login').click()});
  $('mijia-refresh').addEventListener('click',()=>task(refresh));
  $('mijia-home').addEventListener('change',()=>{
    if (busy) { $('mijia-home').value=home; return; }
    home=$('mijia-home').value;homeView.reset();task(loadHome);
  });
  $('mijia-login').addEventListener('click',()=>task(async()=>{await status();message('');await accountDialog();}));
  $('mijia-dialog-close').addEventListener('click',closeDialog);
  $('mijia-dialog').addEventListener('close',()=>{
    if ($('mijia-dialog').open) return;
    dialogRevision++; delete $('mijia-dialog').dataset.busy;
    clearTimeout(loginTimer);loginPanel=null;loginRenderKey='';loginPollId++;lastFocus?.focus();
  });
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
