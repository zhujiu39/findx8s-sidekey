import test from 'node:test';
import assert from 'node:assert/strict';
import {defaultConfig, serialize, validate, hex, quote} from '../module/webroot/model.js';
import {exec, api} from '../module/webroot/bridge.js';

test('默认配置为暂停接管，三个手势均不执行动作', () => {
  const config = defaultConfig(); assert.equal(config.enabled, false);
  assert.deepEqual(config.actions.map(a => a.type), ['none', 'none', 'none']);
  assert.match(serialize(config), /single_arg=\ndouble_arg=\nlong_arg=\n$/);
});
test('包含换行、引号、中文的命令只能作为编码参数传输', () => {
  const config = defaultConfig();
  config.actions[2] = {type: 'shell', argument: "printf '%s\\n' '你好'\necho $(id)"};
  const text = serialize(config), encoded = text.match(/long_arg=(.*)/)[1];
  assert.equal(Buffer.from(encoded, 'hex').toString('utf8'), config.actions[2].argument);
  assert.equal(text.split('\n').length, 11);
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
