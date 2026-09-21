import test from 'node:test';
import assert from 'node:assert/strict';
import {applyAppSelection} from '../module/webroot/app-selection.js';

const app = (name, label = name) => ({packageName:`com.${name}`, label});
const entry = (slot, name) => ({slot, name:'旧的手填名称', icon:'⭐', type:'app', argument:`com.${name}`});
test('批量勾选按选择顺序排列，自动更新名称图标，保留开关和打开方式', () => {
  const toggle = {slot:10,name:'手电筒',icon:'',type:'torch',argument:''};
  const existing = [entry(2,'camera'),entry(0,'settings'),toggle];
  const result = applyAppSelection(existing, [app('new','新应用'),app('camera','相机')]);
  assert.deepEqual(result, [{...toggle,slot:0},{slot:1,name:'新应用',icon:'',type:'app_freeform',argument:'com.new'}, {...entry(2,'camera'),name:'相机',icon:''}]);
  assert.equal(existing[0].name,'旧的手填名称');
});
test('批量取消可选择全部目录，旧版非应用捷径不被误删，拒绝重复应用', () => {
  const custom={slot:1,name:'自定义',icon:'',type:'shell',argument:'echo hi'};
  const lower=entry(8,'lower');
  assert.deepEqual(applyAppSelection([custom,entry(0,'settings'),lower],[]),[{...custom,slot:0}]);
  const many=Array.from({length:2048},(_,i)=>app(`app${i}`));
  assert.equal(applyAppSelection([],many).length,2048);
  assert.equal(applyAppSelection([custom],many).length,2049);
  assert.throws(()=>applyAppSelection([],[app('same'),app('same')]),/重复/);
  assert.throws(()=>applyAppSelection([],[app('bad;id')]),/无效/);
});
