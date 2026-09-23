import {api} from './bridge.js';

export async function exportLogFile(onStatus, options = {}) {
  const call = options.call || api, now = options.now || Date.now;
  const delay = options.delay || (ms => new Promise(resolve => setTimeout(resolve, ms)));
  const id = options.id || [...crypto.getRandomValues(new Uint8Array(16))].map(value => value.toString(16).padStart(2, '0')).join('');
  const started = now();
  const launch = await call('export-logs', id);
  if (!launch?.ok || launch.id !== id) throw new Error('无法启动日志导出');
  for (;;) {
    const result = await call('export-log-status', id);
    if (result?.id !== id) throw new Error('日志导出会话不匹配，请重试');
    if (result.state === 'saved' || result.state === 'cancelled') return result;
    if (result.state === 'error') throw new Error(result.message || '日志导出失败');
    if (!['preparing', 'choosing', 'saving'].includes(result.state)) throw new Error('日志导出返回了无效状态');
    if (now() - started > (result.state === 'preparing' ? 60000 : 450000))
      throw new Error('日志导出等待超时，请检查文件是否保存，或修复快捷菜单后重试');
    onStatus(result.state);
    await delay(1200);
  }
}
