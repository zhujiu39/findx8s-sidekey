(() => {
  const key = 'findx8s-sidekey.appearance';
  const system = matchMedia('(prefers-color-scheme: dark)');
  const normalize = value => ['light', 'dark', 'system'].includes(value) ? value : 'system';
  let current = 'system';
  try { current = normalize(localStorage.getItem(key)); } catch {}
  // 在样式表加载前恢复外观，避免再次打开时闪现默认样式。
  const applyTheme = () => { document.documentElement.dataset.appearance = current === 'system' ? (system.matches ? 'dark' : 'light') : current; };
  applyTheme();
  system.addEventListener('change', applyTheme);
  document.addEventListener('DOMContentLoaded', () => {
    const choices = [...document.querySelectorAll('input[name="appearance"]')];
    function apply(value) {
      current = normalize(value);
      applyTheme();
      choices.forEach(input => { input.checked = input.value === current; });
    }
    apply(current);
    choices.forEach(input => input.addEventListener('change', () => {
      if (!input.checked) return;
      apply(input.value);
      const note = document.getElementById('appearance-note');
      try {
        localStorage.setItem(key, current);
        note.textContent = '外观已记住，立即生效。';
      } catch {
        note.textContent = '外观已切换，当前环境无法记住选择。';
      }
    }));
    window.addEventListener('storage', event => {
      if (event.key === key || event.key === null) apply(event.newValue);
    });
  }, {once: true});
})();
