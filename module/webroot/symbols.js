import {symbols} from './assets/symbols.js';
export function symbol(name, className = '') {
  const template = document.createElement('template');
  template.innerHTML = symbols[name] || symbols['app.fill'];
  const svg = template.content.firstElementChild;
  if (className) svg.classList.add(...className.split(' '));
  return svg;
}
export const actionSymbols = {
  menu:'sidebar.left', none:'nosign', home:'house.fill', back:'arrow.uturn.backward',
  recents:'rectangle.stack.fill', notifications:'bell.fill', quick_settings:'slider.horizontal.3',
  screenshot:'viewfinder', screen_off:'lock.fill', play_pause:'play.fill', next:'forward.fill',
  previous:'backward.fill', volume_up:'speaker.wave.2.fill', volume_down:'speaker.wave.2.fill',
  mute:'speaker.slash.fill', camera:'camera.fill', torch:'flashlight.on.fill', mijia:'house.fill',
  surfing:'network', app_freeform:'square.on.square', app:'app.fill', keycode:'keyboard', shell:'terminal'
};
export function hydrateSymbols(root = document) {
  root.querySelectorAll('[data-symbol]').forEach(el => {
    if (!el.querySelector('.sf-symbol')) el.prepend(symbol(el.dataset.symbol));
  });
}
