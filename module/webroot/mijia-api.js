import {api, available} from './bridge.js';
import {hex} from './model.js';

const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
function check(result) {
  if (!result || result.ok !== true) {
    const error = new Error(result?.message || '米家服务响应无效'); error.code = result?.code; throw error;
  }
  return result;
}
export async function mijiaRequest(op, fields = {}, wait = true) {
  if (!available()) throw new Error('请在手机 KernelSU 中打开米家页面');
  const bytes = new Uint8Array(16); crypto.getRandomValues(bytes);
  const requestId = [...bytes].map(value => value.toString(16).padStart(2, '0')).join('');
  const result = check(await api('mijia', hex(JSON.stringify({...fields, op, requestId}))));
  if (!result.job || !wait) return result;
  const end = Date.now() + 125000;
  while (Date.now() < end) {
    await pause(650);
    const job = check(await api('mijia', hex(JSON.stringify({op:'job', id:result.job}))));
    if (job.state === 'done') return check(job.result);
  }
  throw new Error('等待结果超时；任务可能仍在后台执行，请检查米家最近结果，不要重复发送');
}
