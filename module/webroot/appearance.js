(() => {
  const key = 'findx8s-sidekey.webui-theme';
  const themes = {mono: '按键，自定义。', graphite: '侧键控制台'};
  const normalize = value => Object.hasOwn(themes, value) ? value : 'mono';
  let current = 'mono';
  try { current = normalize(localStorage.getItem(key)); } catch {}
  // 在样式表加载前恢复外观，避免再次打开时闪现默认样式。
  document.documentElement.dataset.theme = current;
  document.addEventListener('DOMContentLoaded', () => {
    const choices = [...document.querySelectorAll('input[name="appearance"]')];
    function apply(value) {
      current = normalize(value);
      document.documentElement.dataset.theme = current;
      document.getElementById('page-title').textContent = themes[current];
      choices.forEach(input => { input.checked = input.value === current; });
    }
    apply(current);
    choices.forEach(input => input.addEventListener('change', () => {
      if (!input.checked) return;
      apply(input.value);
      const note = document.getElementById('appearance-note');
      try {
        localStorage.setItem(key, current);
        note.textContent = '样式已记住，深浅色跟随系统。';
      } catch {
        note.textContent = '样式已切换，当前环境无法记住选择。';
      }
    }));
    window.addEventListener('storage', event => {
      if (event.key === key || event.key === null) apply(event.newValue);
    });
  }, {once: true});
})();
