export function initNavigation() {
  const tabs = [...document.querySelectorAll('.page-tabs [role="tab"]')];
  function show(target, focus = false) {
    tabs.forEach(tab => {
      const selected = tab === target;
      tab.setAttribute('aria-selected', String(selected));
      tab.tabIndex = selected ? 0 : -1;
      document.getElementById(tab.getAttribute('aria-controls')).hidden = !selected;
    });
    if (focus) target.focus();
    window.scrollTo({top: 0, behavior: 'instant'});
  }
  tabs.forEach((tab, index) => {
    tab.addEventListener('click', () => show(tab));
    tab.addEventListener('keydown', event => {
      let next;
      if (event.key === 'ArrowRight') next = (index + 1) % tabs.length;
      else if (event.key === 'ArrowLeft') next = (index + tabs.length - 1) % tabs.length;
      else if (event.key === 'Home') next = 0;
      else if (event.key === 'End') next = tabs.length - 1;
      else return;
      event.preventDefault(); show(tabs[next], true);
    });
  });
  document.getElementById('edit-shortcuts').addEventListener('click', () =>
    show(document.getElementById('tab-menu'), true));
  document.getElementById('preview-shortcuts').addEventListener('click', () =>
    document.getElementById('preview-menu').click());
}
