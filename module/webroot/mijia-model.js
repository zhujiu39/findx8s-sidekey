export function propertyValue(property, raw) {
  if (property.values?.length) {
    const option = property.values.find(item => JSON.stringify(item.value) === raw);
    if (!option) throw new Error('请选择设备支持的值');
    return option.value;
  }
  if (property.format === 'bool') {
    if (!['true', 'false'].includes(raw)) throw new Error('请选择开启或关闭');
    return raw === 'true';
  }
  if (property.format === 'string') {
    if (raw.length > 256) throw new Error('文本不能超过 256 个字符');
    return raw;
  }
  if (!/^(?:u?int(?:8|16|32|64)?|float)$/.test(property.format)) throw new Error('该数据类型暂不支持');
  if (!raw.trim()) throw new Error('请输入数值');
  const value = Number(raw);
  if (!Number.isFinite(value) || (property.format !== 'float' && !Number.isSafeInteger(value))) throw new Error('数值格式不正确');
  if (property.format.startsWith('uint') && value < 0) throw new Error('数值不能小于零');
  if (property.range?.length >= 2) {
    const [min, max, step] = property.range;
    if (value < min || value > max) throw new Error(`数值应在 ${min}～${max} 之间`);
    if (step > 0 && Math.abs((value - min) / step - Math.round((value - min) / step)) > 0.00001) throw new Error(`数值步长应为 ${step}`);
  }
  return value;
}
export function stateLabel(property, state) {
  if (!state || state.code !== 0 || state.value === undefined || state.value === null) return state && state.code !== 0 ? `暂无数据（${state.code}）` : '暂无数据';
  const choice = property.values?.find(item => item.value === state.value);
  if (choice) return choice.name;
  if (typeof state.value === 'boolean') return state.value ? '已开启' : '已关闭';
  const value = typeof state.value === 'number' ? Number(state.value.toFixed(4)) : state.value;
  return `${value}${property.displayUnit ? ' ' + property.displayUnit : ''}`;
}
export function makeReadingAction(home, device, properties) {
  if (!properties.length || properties.length > 4) throw new Error('每张卡片请选择 1～4 项读数，可添加多张卡片');
  if (properties.some(property => !property.reading)) throw new Error('请选择设备读数，不能使用设定值替代');
  return {kind:'read', home, did:device.did, properties:readingProperties(properties)};
}
export function readingProperties(properties) {
  return properties.map(property => {
    const result = {siid:property.siid, piid:property.piid};
    if (Object.hasOwn(property, 'label')) {
      if (typeof property.label !== 'string') throw new Error('读数文案必须是文本');
      const label = property.label.trim();
      if (new TextEncoder().encode(label).length > 60) throw new Error('读数文案过长，最多 20 个汉字或 60 个英文字母');
      if (/[\p{Cc}\p{Cf}\p{Zl}\p{Zp}]/u.test(label)) throw new Error('读数文案不能包含换行或控制字符');
      result.label = label;
    }
    return result;
  });
}
export function bindingName(device, action) {
  const text = `${device} · ${action}`;
  let value = '';
  for (const char of text) { if (new TextEncoder().encode(value + char).length > 96) break; value += char; }
  return value;
}
export function makePropertyAction(home, device, property, kind, raw) {
  if (!property.write) throw new Error('该属性只能读取');
  if (!['set', 'toggle'].includes(kind)) throw new Error('不支持的设备操作');
  if (kind === 'toggle' && (property.format !== 'bool' || !property.read)) throw new Error('该属性不支持切换');
  return {kind, home, did: device.did, siid: property.siid, piid: property.piid,
    ...(kind === 'set' ? {value: propertyValue(property, raw)} : {})};
}
