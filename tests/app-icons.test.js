import test from 'node:test';
import assert from 'node:assert/strict';
import {normalizeIcons} from '../module/webroot/app-icons.js';
test('图标仅接受当前用户、所请求应用的受限 PNG 数据，不接收远端地址或 HTML', () => {
  const good='data:image/png;base64,iVBORw0KGgoAAA==';
  const data={version:1,user:10,icons:[{packageName:'com.demo',icon:good}]};
  assert.equal(normalizeIcons(data,10,['com.demo']).get('com.demo'),good);
  assert.equal(normalizeIcons({...data,icons:[]},10,['com.demo']).get('com.demo'),'');
  assert.throws(()=>normalizeIcons(data,0,['com.demo']));
  assert.throws(()=>normalizeIcons(data,10,['com.other']));
  for(const icon of ['https://example.com/icon.png','data:image/svg+xml,<svg/>',good+'A'.repeat(50000)])
    assert.throws(()=>normalizeIcons({...data,icons:[{packageName:'com.demo',icon}]},10,['com.demo']));
});
