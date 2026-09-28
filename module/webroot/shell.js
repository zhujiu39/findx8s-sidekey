import {hydrateSymbols} from './symbols.js';

const $ = id => document.getElementById(id);
hydrateSymbols();
const pages = {
  gestures: ['Find X8s', '侧键', '短按、双击与长按'],
  menu: ['快捷栏', '常用的，都在手边。', '把应用与控制放在一起，按自己的习惯排列。'],
  mijia: ['米家', '我的家', '家庭与设备'],
  settings: ['侧键自定义', '设置', '外观、按键与运行状态']
};
document.addEventListener('sidekey-page', event => {
  document.body.dataset.page = event.detail;
  document.body.classList.toggle('home-interface', event.detail !== 'menu');
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
