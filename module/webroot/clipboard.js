export async function copyText(text, environment = globalThis) {
  if (!text.trim()) throw new Error('暂无可复制的日志');
  try {
    if (typeof environment.navigator?.clipboard?.writeText === 'function') {
      await environment.navigator.clipboard.writeText(text);
      return;
    }
  } catch { /* 部分 WebView 不开放 Clipboard API，继续使用选区复制。 */ }
  const doc = environment.document, previous = doc.activeElement;
  const field = doc.createElement('textarea');
  field.value = text; field.readOnly = true;
  field.className = 'clipboard-buffer';
  doc.body.append(field);
  try {
    field.focus({preventScroll:true}); field.select(); field.setSelectionRange(0, text.length);
    if (!doc.execCommand('copy')) throw new Error('此 WebView 未允许自动复制');
  } finally {
    field.remove(); previous?.focus({preventScroll:true});
  }
}
