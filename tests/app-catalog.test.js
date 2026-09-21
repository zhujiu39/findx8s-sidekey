import test from 'node:test';
import assert from 'node:assert/strict';
import {normalizeCatalog, filterApps, menuAppName, selectionName, AppCatalogStore} from '../module/webroot/app-catalog.js';
const sample = () => ({version:1,user:0,apps:[{packageName:'com.settings',label:'设置'},{packageName:'com.camera',label:'Camera 相机'}]});
test('按显示名称或包名搜索，多入口去重且保留同名不同包应用', () => {
  const data=sample();data.apps.push(data.apps[0],{packageName:'com.other',label:'设置'});
  const catalog=normalizeCatalog(data);assert.equal(catalog.apps.length,3);
  assert.equal(filterApps(catalog.apps,'设置').length,2);
  assert.equal(filterApps(catalog.apps,' CAMERA ')[0].packageName,'com.camera');
  assert.equal(filterApps(catalog.apps,'com.settings').length,1);
  assert.equal(filterApps(catalog.apps,'不存在').length,0);
});
test('拒绝异常列表，应用名保留为纯文本，空列表合法', () => {
  assert.equal(normalizeCatalog({version:1,user:10,apps:[]}).apps.length,0);
  const data=sample();data.apps[0].label='<img src=x onerror=alert(1)>';
  assert.equal(normalizeCatalog(data).apps.find(a=>a.packageName==='com.settings').label,data.apps[0].label);
  for(const value of [null,{...sample(),user:-1},{...sample(),version:2},{...sample(),apps:Array(2049).fill(data.apps[0])},
    {...sample(),apps:[{packageName:'com.app;id',label:'注入'}]}]) assert.throws(()=>normalizeCatalog(value));
});
test('应用名称自动填充保持手动命名，按 UTF-8 限制且不截断 emoji', () => {
  const selected={packageName:'com.camera',label:'相机'},lookup=()=>({label:'设置'});
  for(const name of ['','新应用','设置','com.settings']) assert.equal(selectionName(name,'com.settings',selected,lookup),'相机');
  assert.equal(selectionName('我的拍照','com.settings',selected,lookup),'我的拍照');
  assert.equal(selectionName('字'.repeat(32),'com.settings',selected,()=>({label:'字'.repeat(40)})),'相机');
  assert.equal(menuAppName('😀'.repeat(30)),'😀'.repeat(24));
  assert.equal(Buffer.byteLength(menuAppName('设置'.repeat(30))),96);
});
test('短暂缓存复用并发读取，过期/手动刷新重新获取当前用户', async () => {
  let calls=0,now=0;
  const store=new AppCatalogStore(async()=>({...sample(),user:calls++}),()=>now);
  const [a,b]=await Promise.all([store.get(),store.get(true)]);assert.equal(calls,1);assert.equal(a,b);
  await store.get();assert.equal(calls,1);assert.equal(store.lookup('com.settings').label,'设置');
  now=10000;assert.equal((await store.get()).user,1);
  assert.equal((await store.get(true)).user,2);
});
test('读取失败后允许重试，不清除已配置包名所需的名称缓存', async () => {
  let failed=false;
  const store=new AppCatalogStore(async()=>{if(failed)throw Error('读取超时');return sample();});
  await store.get();failed=true;await assert.rejects(store.get(true),/超时/);
  assert.equal(store.lookup('com.settings').label,'设置');
  failed=false;assert.equal((await store.get()).apps.length,2);
});
