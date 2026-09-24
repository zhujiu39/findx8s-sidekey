import {symbol} from './symbols.js';
const names = {
  'com.tencent.mm':['message.fill','green'], 'com.android.camera':['camera.fill','slate'],
  'com.android.settings':['gearshape.fill','slate'], 'com.xiaomi.smarthome':['house.fill','orange'],
  'com.github.surfing':['network','blue'], 'com.eg.android.AlipayGphone':['creditcard.fill','blue'],
  'com.netease.cloudmusic':['music.note','red'], 'com.android.chrome':['safari','blue'],
  'com.autonavi.minimap':['map.fill','green'], 'com.android.deskclock':['clock.fill','slate'],
  'com.android.documentsui':['folder.fill','blue'], 'com.android.gallery3d':['photo.on.rectangle','purple'],
  'com.android.email':['envelope.fill','blue'], 'com.android.calculator':['keyboard','orange']
};
export function appIcon(packageName, label, className = '') {
  const [name, tint] = names[packageName] || ['app.fill','blue'];
  const node = document.createElement('span');
  node.className = `app-image app-tint-${tint} ${className}`;
  node.setAttribute('aria-hidden','true'); node.title = label;
  node.append(symbol(name)); return node;
}
export function resetAppIcons() {}
export function releaseAppIcons() {}
