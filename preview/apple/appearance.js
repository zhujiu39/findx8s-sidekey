(() => {
  const key = 'sidekey.apple-preview.appearance';
  const system = matchMedia('(prefers-color-scheme: dark)');
  let mode = 'system';
  try { mode = localStorage.getItem(key) || 'system'; } catch {}
  function apply(value) {
    mode = ['light','dark','system'].includes(value) ? value : 'system';
    document.documentElement.dataset.appearance = mode === 'system' ? (system.matches ? 'dark' : 'light') : mode;
    document.querySelectorAll('[name="appearance"]').forEach(input => {input.checked = input.value === mode;});
  }
  apply(mode);
  system.addEventListener('change', () => apply(mode));
  document.addEventListener('DOMContentLoaded', () => {
    apply(mode);
    document.querySelectorAll('[name="appearance"]').forEach(input => input.addEventListener('change', () => {
      if (!input.checked) return;
      apply(input.value); try {localStorage.setItem(key,mode);} catch {}
    }));
    document.getElementById('theme-toggle').addEventListener('click', () => {
      apply(document.documentElement.dataset.appearance === 'dark' ? 'light' : 'dark');
      try {localStorage.setItem(key,mode);} catch {}
    });
  });
})();
