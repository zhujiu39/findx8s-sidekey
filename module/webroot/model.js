export const actions = [
  ['none', '不执行动作'], ['home', '回到桌面'], ['back', '返回上一页'],
  ['recents', '最近任务'], ['notifications', '展开通知栏'], ['quick_settings', '展开快捷设置'],
  ['screenshot', '截屏'], ['screen_off', '息屏'], ['play_pause', '播放 / 暂停'],
  ['next', '下一首'], ['previous', '上一首'], ['volume_up', '增大音量'],
  ['volume_down', '减小音量'], ['mute', '切换媒体静音'], ['camera', '打开相机'],
  ['torch', '切换手电筒（系统最高亮度）'],
  ['app', '启动应用'], ['keycode', '发送 Android 按键'], ['shell', '自定义 Shell 命令'],
];
export const gestureIds = ['single', 'double', 'long'];
export const defaultConfig = () => ({enabled: false, haptic: true, long_ms: 600, double_ms: 280,
  actions: gestureIds.map(() => ({type: 'none', argument: ''}))});

export function validate(config) {
  if (typeof config.enabled !== 'boolean') throw new Error('接管开关无效');
  if (typeof config.haptic !== 'boolean') throw new Error('震动反馈开关无效');
  if (!Number.isInteger(config.long_ms) || config.long_ms < 250 || config.long_ms > 2000)
    throw new Error('长按时间应在 250～2000 毫秒之间');
  if (!Number.isInteger(config.double_ms) || config.double_ms < 150 || config.double_ms > 600)
    throw new Error('双击间隔应在 150～600 毫秒之间');
  if (!Array.isArray(config.actions) || config.actions.length !== 3) throw new Error('手势配置不完整');
  for (const action of config.actions) {
    if (!actions.some(([id]) => id === action.type)) throw new Error('不支持的动作');
    if (typeof action.argument !== 'string' || action.argument.includes('\0') || new TextEncoder().encode(action.argument).length > 512)
      throw new Error('动作参数最多为 512 个 UTF-8 字节，不能包含空字符');
    if (action.type === 'app' && !/^[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+$/.test(action.argument))
      throw new Error('请输入有效的应用包名，例如 com.android.settings');
    if (action.type === 'keycode' && (!/^\d+$/.test(action.argument) || Number(action.argument) < 1 || Number(action.argument) > 2047))
      throw new Error('Android 按键码应为 1～2047 的整数');
    if (action.type === 'shell' && !action.argument.trim()) throw new Error('请输入要执行的 Shell 命令');
    if (!['app', 'keycode', 'shell'].includes(action.type) && action.argument !== '') throw new Error('该动作不需要参数');
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
  return lines.join('\n') + '\n';
}
export function quote(text) { return "'" + String(text).replaceAll("'", "'\\''") + "'"; }
