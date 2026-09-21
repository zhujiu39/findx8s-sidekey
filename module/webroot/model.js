export const actions = [
  ['menu', '弹出快捷菜单'], ['none', '不执行动作'], ['home', '回到桌面'], ['back', '返回上一页'],
  ['recents', '最近任务'], ['notifications', '展开通知栏'], ['quick_settings', '展开快捷设置'],
  ['screenshot', '截屏'], ['screen_off', '息屏'], ['play_pause', '播放 / 暂停'],
  ['next', '下一首'], ['previous', '上一首'], ['volume_up', '增大音量'],
  ['volume_down', '减小音量'], ['mute', '切换媒体静音'], ['camera', '打开相机'],
  ['torch', '切换手电筒（系统最高亮度）'],
  ['app_freeform', '启动应用（请求小窗）'], ['app', '启动应用'], ['keycode', '发送 Android 按键'], ['shell', '自定义 Shell 命令'],
];
export const gestureIds = ['single', 'double', 'long'];
export const defaultConfig = () => ({enabled: false, haptic: true, long_ms: 600, double_ms: 280,
  actions: gestureIds.map(() => ({type: 'none', argument: ''})), menu_side: 'left', menu_position: 35, menu: []});

export function validate(config) {
  if (typeof config.enabled !== 'boolean') throw new Error('接管开关无效');
  if (typeof config.haptic !== 'boolean') throw new Error('震动反馈开关无效');
  if (!Number.isInteger(config.long_ms) || config.long_ms < 250 || config.long_ms > 2000)
    throw new Error('长按时间应在 250～2000 毫秒之间');
  if (!Number.isInteger(config.double_ms) || config.double_ms < 150 || config.double_ms > 600)
    throw new Error('双击间隔应在 150～600 毫秒之间');
  if (!Array.isArray(config.actions) || config.actions.length !== 3) throw new Error('手势配置不完整');
  if (!['left', 'right'].includes(config.menu_side)) throw new Error('请选择菜单弹出方向');
  if (!Number.isInteger(config.menu_position) || config.menu_position < 10 || config.menu_position > 90)
    throw new Error('菜单高度应在 10%～90% 之间');
  if (!Array.isArray(config.menu) || config.menu.length > 2060) throw new Error('菜单数据超出应用目录范围，请刷新应用列表');
  const bytes = text => new TextEncoder().encode(text).length;
  const slots = new Set();
  for (const [index, item] of config.menu.entries()) {
    const slot = item.slot ?? index;
    if (!Number.isInteger(slot) || slot < 0 || slot >= 2060 || slots.has(slot)) throw new Error('菜单顺序无效或重复');
    slots.add(slot);
    if (typeof item.name !== 'string' || !item.name.trim() || item.name.includes('\0') || bytes(item.name) > 96)
      throw new Error('菜单名称不能为空，最多 96 个 UTF-8 字节');
    if (typeof item.icon !== 'string' || item.icon.includes('\0') || bytes(item.icon) > 24)
      throw new Error('菜单图标最多 24 个 UTF-8 字节，可填写 emoji');
    if (item.type === 'menu') throw new Error('菜单内不能再次打开菜单');
  }
  for (const action of [...config.actions, ...config.menu]) {
    if (!actions.some(([id]) => id === action.type)) throw new Error('不支持的动作');
    if (typeof action.argument !== 'string' || action.argument.includes('\0') || new TextEncoder().encode(action.argument).length > 512)
      throw new Error('动作参数最多为 512 个 UTF-8 字节，不能包含空字符');
    if (['app','app_freeform'].includes(action.type) && !/^[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+$/.test(action.argument))
      throw new Error('请从手机应用列表选择应用');
    if (action.type === 'keycode' && (!/^\d+$/.test(action.argument) || Number(action.argument) < 1 || Number(action.argument) > 2047))
      throw new Error('Android 按键码应为 1～2047 的整数');
    if (action.type === 'shell' && !action.argument.trim()) throw new Error('请输入要执行的 Shell 命令');
    if (!['app', 'app_freeform', 'keycode', 'shell'].includes(action.type) && action.argument !== '') throw new Error('该动作不需要参数');
  }
  return config;
}
export function hex(text) {
  return Array.from(new TextEncoder().encode(text), value => value.toString(16).padStart(2, '0')).join('');
}
export function serialize(config) {
  validate(config);
  const lines = ['version=1', `enabled=${Number(config.enabled)}`, `haptic=${Number(config.haptic)}`, `long_ms=${config.long_ms}`, `double_ms=${config.double_ms}`];
  gestureIds.forEach((id, i) => lines.push(`${id}=${config.actions[i].type}`));
  gestureIds.forEach((id, i) => lines.push(`${id}_arg=${hex(config.actions[i].argument)}`));
  lines.push(`menu_count=${config.menu.length}`, `menu_side=${config.menu_side}`, `menu_position=${config.menu_position}`);
  config.menu.forEach((item, i) => lines.push(`menu_${i}_name=${hex(item.name)}`, `menu_${i}_icon=${hex(item.icon)}`,
    `menu_${i}_slot=${item.slot ?? i}`, `menu_${i}_action=${item.type}`, `menu_${i}_arg=${hex(item.argument)}`));
  return lines.join('\n') + '\n';
}
export function quote(text) { return "'" + String(text).replaceAll("'", "'\\''") + "'"; }
