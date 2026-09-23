import test from 'node:test';
import assert from 'node:assert/strict';
import {exportLogFile} from '../module/webroot/log-export.js';

const id = '1234567890abcdef1234567890abcdef';
function fixture(states) {
  let time = 0, launches = 0, queries = 0;
  return {id, now:() => time, delay:async ms => { time += ms; },
    async call(op, argument) {
      assert.equal(argument, id);
      if (op === 'export-logs') { launches++; return {ok:true, id}; }
      assert.equal(op, 'export-log-status'); queries++;
      const state = states.length > 1 ? states.shift() : states[0];
      return {id, ...state};
    }, counts:() => ({launches, queries}),
  };
}
test('打开选择器与写入中都不能报告成功，只有文件保存完成才返回成功', async () => {
  const options = fixture([{state:'preparing'}, {state:'choosing'}, {state:'saving'}, {state:'saved'}]);
  const progress = [];
  assert.equal((await exportLogFile(state => progress.push(state), options)).state, 'saved');
  assert.deepEqual(progress, ['preparing', 'choosing', 'saving']);
  assert.deepEqual(options.counts(), {launches:1, queries:4});
});
test('用户取消文件选择时返回取消，不会自动再次弹出', async () => {
  const options = fixture([{state:'choosing'}, {state:'cancelled'}]);
  assert.equal((await exportLogFile(() => {}, options)).state, 'cancelled');
  assert.equal(options.counts().launches, 1);
});
test('写入失败、会话错配与无效状态均明确失败', async () => {
  for (const [state, expected] of [[{state:'error', message:'磁盘空间不足'}, /磁盘空间不足/],
      [{id:'wrong', state:'saved'}, /会话不匹配/], [{state:'unknown'}, /无效状态/]])
    await assert.rejects(exportLogFile(() => {}, fixture([state])), expected);
});
test('服务未启动或选择器长时间未完成时有限等待，不会重发导出命令', async () => {
  for (const state of ['preparing', 'choosing']) {
    const options = fixture([{state}]);
    await assert.rejects(exportLogFile(() => {}, options), /等待超时/);
    assert.equal(options.counts().launches, 1);
  }
});
