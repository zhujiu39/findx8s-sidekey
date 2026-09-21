import {quote} from './model.js';
const control = '/data/adb/modules/oppo_sidekey/scripts/control.sh';
let sequence = 0;
export function available() { return typeof window.ksu?.exec === 'function'; }

export function exec(command, milliseconds = 15000) {
  return new Promise((resolve, reject) => {
    if (!available()) { reject(new Error('请从 KernelSU 管理器打开模块 WebUI')); return; }
    const name = `sidekey_callback_${Date.now()}_${sequence++}`;
    let timer;
    function cleanup() { clearTimeout(timer); delete window[name]; }
    window[name] = (errno, stdout, stderr) => {
      cleanup();
      if (Number(errno) !== 0) reject(new Error(String(stderr || stdout || `命令失败 (${errno})`).trim()));
      else resolve(String(stdout || ''));
    };
    timer = setTimeout(() => { cleanup(); reject(new Error('响应超时，请刷新检查状态')); }, milliseconds);
    try { window.ksu.exec(command, '{}', name); }
    catch (error) { cleanup(); reject(error); }
  });
}
export async function api(operation, argument) {
  const command = `sh ${quote(control)} ${quote(operation)}` + (argument === undefined ? '' : ` ${quote(argument)}`);
  const result = await exec(command);
  if (operation === 'start' || operation === 'stop' || operation === 'prepare-menu') return null;
  try { return JSON.parse(result); } catch { throw new Error('模块返回的数据格式异常'); }
}
