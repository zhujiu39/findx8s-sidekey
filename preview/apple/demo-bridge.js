import {defaultConfig, validate, gestureIds, surfingTextDefaults} from './model.js';
import {readingProperties} from './mijia-model.js';

// 仅为浏览器预览提供示例数据；此文件没有手机、Root、账号或网络调用。
const KEY = 'sidekey.apple-preview.v120.2';
const ids = {lamp:'11111111111111111111111111111111', reading:'22222222222222222222222222222222', scene:'33333333333333333333333333333333'};
export const demoApps = [
  ['com.tencent.mm','微信'], ['com.android.camera','相机'], ['com.android.settings','设置'],
  ['com.xiaomi.smarthome','米家'], ['com.github.surfing','Surfing'], ['com.eg.android.AlipayGphone','支付宝'],
  ['com.netease.cloudmusic','网易云音乐'], ['com.android.chrome','浏览器'], ['com.autonavi.minimap','高德地图'],
  ['com.android.deskclock','时钟'], ['com.android.documentsui','文件'], ['com.android.gallery3d','相册'],
  ['com.android.email','邮件'], ['com.android.calculator','计算器']
].map(([packageName,label]) => ({packageName,label}));
const item = (name,type,argument='',extra={}) => ({name,type,argument,icon:'',...extra});
export function exampleConfig() {
  const config = defaultConfig();
  config.enabled = true;
  config.actions = [{type:'menu',argument:''},{type:'torch',argument:''},{type:'mijia',argument:ids.scene}];
  config.menu_width=244; config.menu_side='right'; config.menu_position=50;
  config.menu = [item('手电筒','torch'),item('客厅灯','mijia',ids.lamp),item('客厅环境','mijia',ids.reading),
    item('Surfing','surfing','',{surfing_fields:31,surfing_text:{...surfingTextDefaults}}),
    ...demoApps.filter(app=>['com.tencent.mm','com.android.camera','com.xiaomi.smarthome','com.netease.cloudmusic'].includes(app.packageName))
      .map(app=>item(app.label,'app_freeform',app.packageName))].map((entry,slot)=>({...entry,slot}));
  return config;
}
function initialBindings() {
  return [
    {id:ids.lamp,name:'客厅灯 · 切换开关',kind:'toggle',action:{kind:'toggle',home:'home1',did:'lamp1',siid:2,piid:1}},
    {id:ids.reading,name:'客厅环境',kind:'read',action:{kind:'read',home:'home1',did:'sensor1',properties:[{siid:3,piid:1,label:'温度'},{siid:3,piid:2,label:'湿度'}]}},
    {id:ids.scene,name:'晚安 · 场景',kind:'scene',action:{kind:'scene',home:'home1',scene:'night'}}
  ];
}
const fresh = () => ({config:exampleConfig(),bindings:initialBindings(),loggedIn:true,scenario:'normal',torch:false,surfing:true,lamp:true,count:128,lastGesture:'single',lastAction:'menu',last:{},login:{state:'idle'},log:[]});
let state=fresh();
try { const saved=JSON.parse(localStorage.getItem(KEY)); if(saved?.config && Array.isArray(saved.bindings)) {validate(saved.config);state={...state,...saved};} } catch {}
state.scenario='normal';
function persist() { try { localStorage.setItem(KEY,JSON.stringify(state)); } catch {} }
function changed() { persist();document.dispatchEvent(new Event('demo-state')); }
const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const clone=value=>structuredClone(value);
const decode=value=>new TextDecoder().decode(Uint8Array.from(value.match(/.{2}/g)||[],part=>parseInt(part,16)));
function log(message) { state.log.push(`[${new Date().toLocaleTimeString()}] 示例：${message}`);state.log=state.log.slice(-60);changed(); }
export const available=()=>true;
export function demoState() {return clone(state);}
export function setScenario(value) { state.scenario=value;changed(); }
export function resetDemo(empty=false) {
  state=fresh(); if(empty){state.config=defaultConfig();state.bindings=[];state.loggedIn=false;state.count=0;state.lastGesture='';state.lastAction='';}
  persist();location.reload();
}
export function completeDemoLogin() { state.loggedIn=true;state.login={state:'done',message:'示例账号已连接'};changed(); }
function runtime() {
  const stopped=state.scenario==='service-off',error=state.scenario==='takeover-error';
  return {config:clone(state.config),running:!stopped,
    runtime:{grabbed:state.config.enabled&&!stopped&&!error,count:state.count,last_gesture:state.lastGesture,last_action:state.lastAction,
      last_result:state.scenario==='timeout'?124:0,device:'示例输入设备',torch_pid:4102,error:error?'示例：输入设备暂不可用，接管未生效。':''},
    torch:{pid:4102,known:state.scenario!=='unknown',available:state.scenario!=='torch-unavailable',enabled:state.torch,strength:state.torch?5:0,maximum:5},
    logs:['[预览数据] 监听服务运行中',...state.log].join('\n'),torch_logs:'[预览数据] 系统手电筒状态已读取',
    menu_logs:'[预览数据] 快捷菜单组件已就绪',app_logs:'[预览数据] ColorOS 小窗启动记录',
    mijia_menu_logs:'[预览数据] 已读取本次面板设备状态',mijia_logs:'[预览数据] 账号与读数均为虚构示例',surfing_logs:'[预览数据] 速率、核心累计和订阅额度分别显示'};
}
const devices = [
  {home:'home1',did:'lamp1',name:'客厅灯',model:'demo.light.v1',online:true,room:'客厅'},
  {home:'home1',did:'sensor1',name:'温湿度传感器',model:'demo.sensor.v1',online:true,room:'客厅'},
  {home:'home1',did:'ac1',name:'空调',model:'demo.airconditioner.v1',online:true,room:'卧室'},
  {home:'home1',did:'plug1',name:'书桌插座',model:'demo.plug.v1',online:false,room:'书房'},
  {home:'home2',did:'sensor2',name:'温湿度传感器',model:'demo.sensor.v1',online:true,room:'工作室'}
];
function property(siid,piid,type,name,format,value,extra={}) {
  return {siid,piid,type,name,displayName:name,service:'环境',format,read:true,notify:true,write:false,reading:true,setpoint:false,displayUnit:'',values:[],value,...extra};
}
function spec(did) {
  const props = did.startsWith('sensor') ? [
    property(3,1,'temperature','当前温度','float',24.6,{displayUnit:'°C',range:[-40,85,.1]}),
    property(3,2,'relative-humidity','相对湿度','float',48,{displayUnit:'%',range:[0,100,1]}),
    property(3,3,'battery-level','电池电量','uint8',87,{displayUnit:'%',range:[0,100,1]}),
    property(3,4,'illuminance','照度','float',360,{displayUnit:'lx',range:[0,100000,1]}),
    property(3,5,'contact-state','传感器状态','bool',false)
  ] : [property(2,1,'on','电源','bool',state.lamp,{service:'设备控制',reading:false,write:true})];
  if(did==='lamp1') props.push(property(2,2,'brightness','亮度','uint8',70,{service:'灯光',reading:false,write:true,range:[1,100,1],displayUnit:'%'}));
  if(did==='ac1') props.push(
    property(3,1,'temperature','当前温度','float',25.1,{displayUnit:'°C'}),
    property(2,2,'target-temperature','设定温度','float',26,{service:'空调',reading:false,setpoint:true,write:true,range:[16,30,.5],displayUnit:'°C'}),
    property(2,3,'mode','工作模式','uint8',1,{service:'空调',reading:false,write:true,values:[{value:1,name:'制冷'},{value:2,name:'制热'},{value:3,name:'送风'}]})
  );
  return {properties:props,actions:did==='ac1'?[{siid:2,aiid:1,name:'按指定温度启动',service:'空调',in:[2]}]:[]};
}
function binding(id) { const entry=state.bindings.find(b=>b.id===id); if(!entry)throw new Error('绑定已移除，请重新选择');return entry; }
async function mijia(request) {
  const {op}=request;
  if(op==='status') return {ok:true,loggedIn:state.loggedIn,account:'•••• 2026（示例）',bindings:clone(state.bindings),last:clone(state.last),login:clone(state.login)};
  if(op==='login-start') {state.login={state:'waiting',message:'预览登录流程：不生成真实二维码。点击“模拟扫码完成”体验后续页面。'};changed();return {ok:true};}
  if(op==='login-status')return {ok:true,login:clone(state.login)};
  if(op==='login-cancel'){state.login={state:'cancelled',message:'示例登录已取消'};changed();return {ok:true};}
  if(op==='logout'){state.loggedIn=false;state.bindings=[];state.login={state:'idle'};changed();return {ok:true};}
  if(!state.loggedIn)throw new Error('请先连接示例米家账号');
  if(op==='homes')return {ok:true,homes:[{id:'home1',name:'我的家'},{id:'home2',name:'工作室'}]};
  if(op==='catalog')return {ok:true,devices:devices.filter(d=>d.home===request.home).map(d=>({...d,online:state.scenario==='offline'?false:d.online})),
    scenes:request.home==='home1'?[{home:'home1',id:'night',name:'晚安'},{home:'home1',id:'back',name:'回家'},{home:'home1',id:'away',name:'离家'}]:[],sceneError:''};
  if(op==='device') {
    const source=spec(request.did); return {ok:true,spec:source,states:source.properties.map(p=>({siid:p.siid,piid:p.piid,code:state.scenario==='unknown'?-1:0,value:p.value})),stateError:state.scenario==='unknown'?'暂无上报数据，可先配置读数卡片。':''};
  }
  if(op==='binding-save') {
    if(state.bindings.length>=256)throw new Error('米家动作最多保存 256 项');
    const id=crypto.randomUUID().replaceAll('-','');const entry={id,name:request.name,kind:request.action.kind,action:clone(request.action)};
    state.bindings.push(entry);changed();return {ok:true,...clone(entry)};
  }
  if(op==='binding-delete'){state.bindings=state.bindings.filter(b=>b.id!==request.id);changed();return {ok:true};}
  if(op==='binding-detail') {
    const entry=binding(request.id),properties=spec(entry.action.did).properties;
    return {ok:true,id:entry.id,name:entry.name,properties:entry.action.properties.map(p=>({...properties.find(v=>v.siid===p.siid&&v.piid===p.piid),...p}))};
  }
  if(op==='binding-labels') {
    const entry=binding(request.id),properties=readingProperties(request.properties);
    if(entry.kind!=='read'||JSON.stringify(properties.map(p=>[p.siid,p.piid]))!==JSON.stringify(entry.action.properties.map(p=>[p.siid,p.piid])))throw new Error('读数来源已变化，请重新打开编辑');
    entry.action.properties=properties;changed();return {ok:true};
  }
  throw new Error(`预览未提供这个米家操作：${op}`);
}
export function readingFor(id) {
  const entry=state.bindings.find(b=>b.id===id);if(!entry||entry.kind!=='read')return [];
  const props=spec(entry.action.did).properties;
  return entry.action.properties.map(p=>{const prop=props.find(v=>v.siid===p.siid&&v.piid===p.piid);return {label:p.label??prop?.displayName??'',value:state.scenario==='unknown'?'—':typeof prop?.value==='boolean'?(prop.value?'是':'否'):String(prop?.value??'—'),unit:prop?.displayUnit??''};});
}
export async function runMenuItem(entry) {
  if(entry.type==='mijia') {
    const b=binding(entry.argument);if(b.kind==='read')return {kind:'read'};
    if(b.kind!=='scene'&&['offline','unknown'].includes(state.scenario))throw new Error('设备状态不可确认，暂不能操作');
    state.last={time:Date.now(),message:'示例：指令已提交，等待设备状态'};changed();await delay(620);
    state.lamp=b.kind==='set'&&typeof b.action.value==='boolean'?b.action.value:!state.lamp;
    state.last={time:Date.now(),message:b.kind==='scene'?'示例：场景请求已接收':'示例：已读回并确认设备状态'};
  } else if(entry.type==='torch') {
    if(['torch-unavailable','unknown'].includes(state.scenario))throw new Error('系统手电筒暂不可用');
    await delay(220);state.torch=!state.torch;
  } else if(entry.type==='surfing') {await delay(680);state.surfing=!state.surfing;}
  log(`${entry.name} · ${entry.type==='app_freeform'?'ColorOS 小窗示意':'模拟执行'}`);return {kind:entry.type};
}
export async function api(operation, argument) {
  await delay(operation==='get'?60:180);
  if(operation==='get')return runtime();
  if(operation==='apps')return {version:1,user:0,apps:clone(demoApps),labelFallbacks:0};
  if(operation==='mijia')return mijia(JSON.parse(decode(argument)));
  if(operation==='start'){state.scenario='normal';log('已模拟启动监听服务');return null;}
  if(operation==='prepare-menu'){log('快捷菜单组件修复完成（示例）');return null;}
  if(operation==='logs')return Object.entries(runtime()).filter(([key])=>key.endsWith('logs')).map(([key,value])=>`${key}\n${value}`).join('\n\n');
  if(operation==='test') {
    const index=gestureIds.indexOf(argument),action=state.config.actions[index];
    state.lastGesture=argument;state.lastAction=action.type;state.count++;changed();
    if(state.scenario==='timeout')return {result:124};
    if(action.type==='menu')document.dispatchEvent(new Event('demo-open-menu'));
    else await runMenuItem({...action,name:['短按','双击','长按'][index]});
    return {result:0};
  }
  throw new Error(`预览不执行系统命令：${operation}`);
}
export async function saveConfiguration(encoded) {
  const fields=Object.fromEntries(decode(encoded).trimEnd().split('\n').map(line=>{const i=line.indexOf('=');return [line.slice(0,i),line.slice(i+1)];}));
  const config={enabled:fields.enabled==='1',haptic:fields.haptic==='1',long_ms:+fields.long_ms,double_ms:+fields.double_ms,
    actions:gestureIds.map(id=>({type:fields[id],argument:decode(fields[`${id}_arg`])})),menu_side:fields.menu_side,menu_position:+fields.menu_position,menu_width:+fields.menu_width,menu_gap:+fields.menu_gap,menu:[]};
  for(let i=0;i<+fields.menu_count;i++){
    const prefix=`menu_${i}_`;const entry={name:decode(fields[prefix+'name']),icon:decode(fields[prefix+'icon']),slot:+fields[prefix+'slot'],type:fields[prefix+'action'],argument:decode(fields[prefix+'arg'])};
    if(entry.type==='surfing'){entry.surfing_fields=+fields[prefix+'surfing_fields'];entry.surfing_text=Object.fromEntries(Object.keys(surfingTextDefaults).map(key=>[key,decode(fields[prefix+'surfing_'+key])]));}
    config.menu.push(entry);
  }
  validate(config);await delay(380);state.config=clone(config);log('已将配置保存到本浏览器的预览空间');return runtime();
}
