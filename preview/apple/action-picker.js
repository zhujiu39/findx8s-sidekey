import {symbol, actionSymbols} from './symbols.js';
const groups = [
  ['常用', ['menu','torch','camera','app_freeform','app','mijia','surfing']],
  ['系统操作', ['home','back','recents','notifications','quick_settings','screenshot','screen_off']],
  ['媒体', ['play_pause','next','previous','volume_up','volume_down','mute']],
  ['更多', ['keycode','shell','none']]
];
let dialog;
export function decorateActionSelect(select) {
  const wrapper = document.createElement('div'); wrapper.className = 'action-selection';
  select.classList.add('native-action-select'); select.tabIndex = -1;
  const button = document.createElement('button'); button.type = 'button'; button.className = 'action-choice';
  function update() {
    button.replaceChildren(symbol(actionSymbols[select.value] || 'app.fill'));
    const label = document.createElement('span'); label.textContent = select.selectedOptions[0]?.textContent || '选择动作';
    button.append(label, symbol('chevron.right')); button.disabled = select.disabled;
  }
  button.addEventListener('click', () => open(select, button));
  select.addEventListener('change', update);
  new MutationObserver(update).observe(select, {attributes:true,attributeFilter:['disabled']});
  select.updateChoice = update;
  if (select.parentNode) select.replaceWith(wrapper);
  wrapper.append(select, button); update(); return wrapper;
}
function open(select, invoker) {
  if (!dialog) {
    dialog = document.createElement('dialog'); dialog.className = 'action-picker';
    document.body.append(dialog);
  }
  dialog.replaceChildren();
  const header = document.createElement('header'); const title = document.createElement('h2');
  title.id = 'action-picker-title'; title.textContent = '选择动作'; dialog.setAttribute('aria-labelledby',title.id);
  const close = document.createElement('button'); close.className = 'icon-button'; close.setAttribute('aria-label','关闭动作选择');
  close.append(symbol('xmark')); close.onclick = () => dialog.close(); header.append(title,close); dialog.append(header);
  for (const [label,types] of groups) {
    const options = [...select.options].filter(option => types.includes(option.value)); if (!options.length) continue;
    const section = document.createElement('section'); const heading = document.createElement('h3'); heading.textContent = label; section.append(heading);
    for (const option of options) {
      const row = document.createElement('button'); row.type = 'button'; row.className = 'action-option';
      const name = document.createElement('span'); name.textContent = option.textContent;
      row.append(symbol(actionSymbols[option.value]), name);
      row.setAttribute('aria-pressed',String(option.value === select.value));
      if (option.value === select.value) row.append(symbol('checkmark'));
      row.onclick = () => { select.value = option.value; select.dispatchEvent(new Event('change',{bubbles:true})); dialog.close(); (invoker.isConnected?invoker:document.querySelector('#menu-items details[open] summary'))?.focus(); };
      section.append(row);
    }
    dialog.append(section);
  }
  dialog.showModal();
}
