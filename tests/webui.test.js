import test from 'node:test';
import assert from 'node:assert/strict';
import {defaultConfig, serialize, validate, hex, quote} from '../module/webroot/model.js';
import {exec, api} from '../module/webroot/bridge.js';

test('默认配置为暂停接管，三个手势均不执行动作', () => {
  const config = defaultConfig(); assert.equal(config.enabled, false); assert.equal(config.haptic, true);
  assert.deepEqual(config.actions.map(a => a.type), ['none', 'none', 'none']);
  assert.match(serialize(config), /single_arg=\ndouble_arg=\nlong_arg=\n/);
});
test('包含换行、引号、中文的命令只能作为编码参数传输', () => {
  const config = defaultConfig();
  config.actions[2] = {type: 'shell', argument: "printf '%s\\n' '你好'\necho $(id)"};
  const text = serialize(config), encoded = text.match(/long_arg=(.*)/)[1];
  assert.equal(Buffer.from(encoded, 'hex').toString('utf8'), config.actions[2].argument);
  assert.equal(text.split('\n').length, 15);
  assert.match(hex(text), /^[0-9a-f]+$/);
});
test('拒绝非法阈值、未知动作、超长或空参数', () => {
  const config = defaultConfig();
  for (const value of [249, 2001, 600.5, NaN]) {
    config.long_ms = value; assert.throws(() => validate(config));
  }
  config.long_ms = 600;
  for (const action of [{type:'unknown',argument:''}, {type:'shell',argument:''},
    {type:'app',argument:'com.demo;id'}, {type:'keycode',argument:'3;id'},
    {type:'shell',argument:'字'.repeat(171)}, {type:'home',argument:'unexpected'}]) {
    config.actions[0] = action; assert.throws(() => validate(config));
  }
});
test('Shell 引号不会使参数中的单引号成为新命令', () => {
  assert.equal(quote("a'b"), "'a'\\''b'");
  assert.equal(quote('$(id);echo x'), "'$(id);echo x'");
});
test('内置动作及应用包名可以序列化', () => {
  const config = defaultConfig(); config.enabled = true;
  config.actions = [{type:'app',argument:'com.android.settings'}, {type:'keycode',argument:'3'}, {type:'notifications',argument:''}];
  assert.doesNotThrow(() => validate(config)); assert.match(serialize(config), /enabled=1/);
});

test('系统手电筒不需要 Shell 参数，并保持旧配置兼容', () => {
  const config = defaultConfig();
  config.actions[2] = {type: 'torch', argument: ''};
  assert.match(serialize(config), /long=torch\n/);
  config.actions[2].argument = '4';
  assert.throws(() => validate(config), /不需要参数/);
});

test('震动开关关闭后仍保留手电筒动作，并拒绝非布尔值', () => {
  const config = defaultConfig(); config.haptic = false;
  config.actions[2] = {type:'torch', argument:''};
  const text = serialize(config);
  assert.match(text, /haptic=0\n/); assert.match(text, /long=torch\n/);
  for (const value of ['false', 0, null, undefined]) {
    config.haptic = value; assert.throws(() => validate(config), /震动/);
  }
});

test('KernelSU 回调协议可读取数据并清理回调', async () => {
  globalThis.window = {ksu: {exec(command, options, callback) {
    assert.equal(options, '{}'); assert.match(command, /control\.sh/);
    window[callback](0, JSON.stringify({config: defaultConfig()}), '');
  }}};
  assert.equal((await api('get')).config.enabled, false);
  assert.deepEqual(Object.keys(window), ['ksu']);
});
test('桥接错误、非法响应和超时不会报告保存成功', async () => {
  globalThis.window = {ksu: {exec(command, options, callback) { window[callback](1, '', 'denied'); }}};
  await assert.rejects(api('get'), /denied/);
  window.ksu.exec = (command, options, callback) => window[callback](0, 'invalid JSON', '');
  await assert.rejects(api('get'), /数据格式异常/);
  window.ksu.exec = () => {};
  await assert.rejects(exec('test', 10), /超时/);
  assert.deepEqual(Object.keys(window), ['ksu']);
});


test('快捷菜单默认空白、左侧滑出，支持 emoji 与完整动作序列化', () => {
  const c = defaultConfig(); assert.deepEqual(c.menu, []); assert.equal(c.menu_side, 'left');
  c.actions[0] = {type: 'menu', argument: ''};
  c.menu.push({name: '设置', icon: '⚙️', type: 'app', argument: 'com.android.settings'});
  assert.match(serialize(c), /single=menu\n/);
  assert.match(serialize(c), /menu_count=1\nmenu_side=left\n/);
  assert.equal(Buffer.from(serialize(c).match(/menu_0_name=(.*)/)[1], 'hex').toString('utf8'), '设置');
});
test('拒绝菜单递归、超量、空名称、字节超长以及越界位置', () => {
  for (const item of [
    {name:'',icon:'',type:'none',argument:''}, {name:'a',icon:'',type:'menu',argument:''},
    {name:'字'.repeat(33),icon:'',type:'none',argument:''},
    {name:'a',icon:'😀'.repeat(7),type:'none',argument:''},
    {name:'a\0b',icon:'',type:'none',argument:''},
  ]) {
    const c=defaultConfig();c.menu=[item];assert.throws(() => validate(c));
  }
  const c=defaultConfig(); c.menu=Array.from({length:2061},()=>({name:'a',icon:'',type:'home',argument:''}));
  assert.throws(()=>validate(c)); c.menu=[];
  for (const position of [9,91,NaN,35.1]) { c.menu_position=position;assert.throws(()=>validate(c)); }
  c.menu_position=35;c.menu_side='top';assert.throws(()=>validate(c));
});
test('最大菜单和最长 Shell 仍处于 C 配置缓冲区内', () => {
  const c=defaultConfig();
  c.actions=Array.from({length:3},()=>({type:'shell',argument:'x'.repeat(512)}));
  c.menu=Array.from({length:2060},()=>({name:'字'.repeat(32),icon:'😀'.repeat(6),type:'shell',argument:'x'.repeat(512)}));
  assert.ok(Buffer.byteLength(serialize(c)) < 4 * 1024 * 1024);
  assert.equal(c.menu.length,2060);
});


test('网格槽位唯一且可以保留空位，小窗应用参数严格校验', () => {
  const c=defaultConfig();
  c.menu=[{slot:3,name:'应用',icon:'',type:'app_freeform',argument:'com.android.settings'},
    {slot:10,name:'灯光',icon:'',type:'torch',argument:''}];
  assert.match(serialize(c), /menu_0_slot=3\n/); assert.match(serialize(c), /menu_1_slot=10\n/);
  for (const slot of [-1,2060,3,4.5]) {c.menu[1].slot=slot;assert.throws(()=>validate(c));}
  c.menu[1].slot=10;c.menu[0].argument='com.app;id';assert.throws(()=>validate(c));
  c.menu[0].argument='';assert.throws(()=>validate(c));
});
