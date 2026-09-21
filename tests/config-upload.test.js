import test from 'node:test';
import assert from 'node:assert/strict';
import {saveConfiguration} from '../module/webroot/bridge.js';

test('大配置分块有序提交，失败不提交且清理临时文件', async () => {
  const encoded='61'.repeat(80000), calls=[];
  let fail=false;
  globalThis.window={ksu:{exec(command,options,callback){
    calls.push(command);
    queueMicrotask(()=>window[callback](fail && command.includes("'save-part'") ? 1 : 0, command.includes("'save-commit'") ? '{"saved":true}' : 'null','测试失败'));
  }}};
  assert.deepEqual(await saveConfiguration(encoded),{saved:true});
  const parts=calls.filter(command=>command.includes("'save-part'"));
  assert.equal(parts.length,14);
  assert.ok(calls.every(command=>command.length<12500));
  assert.equal(parts.map(command=>command.match(/'([a-f0-9]{32}):(\d+):([a-f0-9]+)'$/)[3]).join(''),encoded);
  assert.ok(calls.at(-2).includes("'save-commit'"));assert.ok(calls.at(-1).includes("'save-abort'"));
  calls.length=0;fail=true;
  await assert.rejects(saveConfiguration(encoded),/测试失败/);
  assert.equal(calls.length,2);assert.ok(calls.at(-1).includes("'save-abort'"));
  delete globalThis.window;
});
