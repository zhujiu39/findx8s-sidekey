import {hydrateSymbols} from './symbols.js';

const $ = id => document.getElementById(id);
hydrateSymbols();
const pages = {
  gestures: ['侧键自定义', '一按，即达。', '把顺手的操作，交给侧边的那颗按键。'],
  menu: ['快捷栏', '常用的，都在手边。', '把应用与控制放在一起，按自己的习惯排列。'],
  mijia: ['米家', '家的状态，一眼可见。', '读数、设备和场景，都能从侧键抵达。'],
  settings: ['设置', '调成你的手感。', '调整按压节奏，选择喜欢的界面外观。']
};
document.addEventListener('sidekey-page', event => {
  const [eyebrow, title, description] = pages[event.detail] || pages.gestures;
  $('page-eyebrow').textContent = eyebrow;
  $('page-title').textContent = title;
  $('page-description').textContent = description;
});
const motionKey = 'findx8s-sidekey.reduce-motion';
try { $('reduce-motion').checked = localStorage.getItem(motionKey) === 'true'; } catch {}
function applyMotion() {
  document.documentElement.dataset.reduceMotion = String($('reduce-motion').checked);
  try { localStorage.setItem(motionKey, String($('reduce-motion').checked)); } catch {}
}
applyMotion();
$('reduce-motion').addEventListener('change', applyMotion);
function orientation() {
  document.querySelector('.page-tabs').setAttribute('aria-orientation', innerWidth > 680 ? 'vertical' : 'horizontal');
}
orientation();
window.addEventListener('resize', orientation);
document.querySelector('.page-tabs').addEventListener('keydown', event => {
  if (innerWidth <= 680 || !['ArrowUp', 'ArrowDown'].includes(event.key)) return;
  event.preventDefault();
  const tabs = [...document.querySelectorAll('.page-tabs [role=tab]')];
  const index = tabs.indexOf(document.activeElement);
  const next = tabs[(index + (event.key === 'ArrowDown' ? 1 : tabs.length - 1)) % tabs.length];
  next.click(); next.focus();
});
