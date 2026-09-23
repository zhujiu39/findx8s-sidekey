import test from 'node:test';
import assert from 'node:assert/strict';
import {copyText} from '../module/webroot/clipboard.js';
import {api} from '../module/webroot/bridge.js';

const logs = 'findx8s sidekey\n监听服务：\n测试\n米家执行与状态读回：\n目标=0，读回=1\n';
function legacyEnvironment(success = true) {
  const result = {copied:'', removed:false, restored:false};
  const field = {value:'', focus() {}, select() {}, setSelectionRange() {}, remove() { result.removed = true; }};
  const environment = {document: {
    activeElement: {focus() { result.restored = true; }},
    createElement: () => field, body:{append() {}},
    execCommand(command) { assert.equal(command, 'copy'); result.copied = field.value; return success; },
  }};
  return {environment, result};
}
test('全部日志文本通过桥接保留中文、换行和多个分类', async () => {
  globalThis.window = {ksu:{exec(command, options, callback) {
    assert.match(command, /control\.sh' 'logs'$/);
    window[callback](0, logs, '');
  }}};
  assert.equal(await api('logs'), logs);
});
test('Clipboard API 成功时复制完整文本', async () => {
  let actual;
  await copyText(logs, {navigator:{clipboard:{async writeText(text) { actual = text; }}}});
  assert.equal(actual, logs);
});
test('没有 Clipboard API 的 WebView 使用选区复制并恢复焦点', async () => {
  const {environment, result} = legacyEnvironment();
  await copyText(logs, environment);
  assert.deepEqual(result, {copied:logs, removed:true, restored:true});
});
test('Clipboard API 拒绝时仍可使用兼容复制', async () => {
  const {environment, result} = legacyEnvironment();
  environment.navigator = {clipboard:{async writeText() { throw new Error('denied'); }}};
  await copyText(logs, environment);
  assert.equal(result.copied, logs);
});
test('两种自动复制均失败时报告失败并清理临时选区', async () => {
  const {environment, result} = legacyEnvironment(false);
  environment.navigator = {clipboard:{async writeText() { throw new Error('denied'); }}};
  await assert.rejects(copyText(logs, environment), /未允许自动复制/);
  assert.ok(result.removed && result.restored);
});
