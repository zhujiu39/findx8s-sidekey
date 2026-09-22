import test from 'node:test';
import assert from 'node:assert/strict';
import {propertyValue, stateLabel, bindingName, makePropertyAction} from '../module/webroot/mijia-model.js';
import {defaultConfig, validate, serialize} from '../module/webroot/model.js';

test('米家值选择保留布尔和枚举类型，拒绝越界及不合步长的值',()=>{
  assert.equal(propertyValue({format:'bool'},'false'),false);
  assert.throws(()=>propertyValue({format:'bool'},'0'));
  const property={format:'uint8',range:[1,99,2],values:[]};
  assert.equal(propertyValue(property,'51'),51);
  for(const value of ['0','100','50','','NaN','Infinity']) assert.throws(()=>propertyValue(property,value));
  assert.equal(propertyValue({format:'uint8',values:[{value:0,name:'关闭'}]},'0'),0);
  assert.throws(()=>propertyValue({format:'uint8',values:[{value:0,name:'关闭'}]},'1'));
});
test('只读和未知状态不会被当作关灯，只有布尔可读写支持切换',()=>{
  assert.equal(stateLabel({format:'bool'},{code:0,value:false}),'已关闭');
  assert.match(stateLabel({format:'bool'},{code:-704042011}),/读取失败/);
  const p={siid:2,piid:1,format:'bool',read:true,write:true};
  assert.deepEqual(makePropertyAction('1',{did:'2'},p,'toggle',''),{kind:'toggle',home:'1',did:'2',siid:2,piid:1});
  assert.throws(()=>makePropertyAction('1',{did:'2'},{...p,write:false},'toggle',''));
  assert.throws(()=>makePropertyAction('1',{did:'2'},{...p,format:'uint8'},'toggle',''));
});
test('米家配置只保存动作编号，不保存凭据和任意命令',()=>{
  const c=defaultConfig(),id='0123456789abcdef'.repeat(2);c.actions[0]={type:'mijia',argument:id};
  c.menu=[{name:'台灯 · 开关',icon:'',type:'mijia',argument:id}];
  assert.equal(validate(c),c);assert.match(serialize(c),/single=mijia/);
  for(const value of ['', 'a'.repeat(31), '../auth.json', '$(id)', 'A'.repeat(32)]) {
    c.actions[0].argument=value;assert.throws(()=>validate(c));
  }
  assert.ok(new TextEncoder().encode(bindingName('灯'.repeat(50),'开关')).length<=96);
});
