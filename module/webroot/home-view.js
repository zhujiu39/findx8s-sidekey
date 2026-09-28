import {symbol} from './symbols.js';

const categories = [
  {id:'climate', name:'环境', icon:'thermometer.medium', match:/aircondition|air\.condition|airfresh|airpurifier|humidifier|heater|thermostat|temperature|airmonitor|空调|温湿|温度|湿度|净化|暖气|新风/i},
  {id:'lights', name:'照明', icon:'lightbulb.fill', match:/light|lamp|bulb|灯/i},
  {id:'security', name:'安防', icon:'lock.fill', match:/lock|camera|motion|magnet|door|smoke|门锁|摄像|门窗|烟雾|人体/i},
  {id:'power', name:'电源', icon:'power', match:/plug|outlet|switch|插座|开关/i},
  {id:'other', name:'其他', icon:'square.grid.2x2.fill'}
];
export function deviceCategory(device) {
  const text = `${device.model || ''} ${device.name || ''}`;
  return categories.find(item => !item.match || item.match.test(text));
}
export function deviceSymbol(device) {
  const text = `${device.model || ''} ${device.name || ''}`;
  if (/curtain|窗帘/i.test(text)) return 'curtains.closed';
  if (/aircondition|air\.condition|空调/i.test(text)) return 'air.conditioner.horizontal.fill';
  if (/camera|摄像/i.test(text)) return 'camera.fill';
  if (/speaker|sound|音箱/i.test(text)) return 'speaker.wave.2.fill';
  const category = deviceCategory(device);
  return category.id === 'other' ? 'sensor' : category.icon;
}
const node = (tag, cls, text) => {
  const element = document.createElement(tag); element.className = cls;
  if (text !== undefined) element.textContent = text;
  return element;
};
const $ = id => document.getElementById(id);

// 目录只返回在线状态；卡片不能把“在线”解释成开关已开启，也不主动控制设备。
export function createHomeView({openDevice, openScene, openAccount}) {
  let catalog = null, category = 'all', room = '', query = '';
  const filters = $('home-categories'), roomSelect = $('home-room'), search = $('home-search');
  function reset() {
    category = 'all'; room = ''; query = ''; search.value = '';
  }
  function empty(title, detail, connect = false) {
    catalog = null; filters.replaceChildren(); $('home-content').hidden = true;
    const host = $('home-empty'); host.hidden = false; host.removeAttribute('aria-busy'); host.replaceChildren();
    const mark = node('span','home-empty-icon'); mark.append(symbol(connect ? 'house.fill' : 'sensor'));
    host.append(mark, node('h2','',title), node('p','',detail));
    if (connect) {
      const login = node('button','home-connect','连接米家'); login.type = 'button';
      login.addEventListener('click', openAccount); host.append(login);
    }
  }
  function render() {
    if (!catalog) return;
    const {devices, scenes} = catalog;
    const countByCategory = id => devices.filter(device => deviceCategory(device).id === id).length;
    const choices = [{id:'all', name:'全部', icon:'house.fill'}, ...categories.filter(item => countByCategory(item.id))];
    if (!choices.some(item => item.id === category)) category = 'all';
    filters.replaceChildren(...choices.map(item => {
      const chip = node('button','home-category'); chip.type = 'button'; chip.dataset.category = item.id;
      chip.setAttribute('aria-pressed', String(category === item.id));
      const copy = node('span',''); copy.append(node('strong','',item.name),node('small','',`${item.id === 'all' ? devices.length : countByCategory(item.id)} 台设备`));
      chip.append(symbol(item.icon),copy);
      chip.addEventListener('click', () => { category = item.id; render(); filters.querySelector(`[data-category="${item.id}"]`)?.focus({preventScroll:true}); });
      return chip;
    }));
    const rooms = [...new Set(devices.map(device => device.room || '未分组'))];
    if (!rooms.includes(room)) room = '';
    roomSelect.replaceChildren(new Option('所有房间', ''), ...rooms.map(name => new Option(name,name)));
    roomSelect.value = room;
    const matches = value => String(value || '').toLocaleLowerCase().includes(query);
    const shownDevices = devices.filter(device => (category === 'all' || deviceCategory(device).id === category) &&
      (!room || (device.room || '未分组') === room) && (!query || matches(`${device.name} ${device.room || ''} ${device.model}`)));
    const shownScenes = category === 'all' && !room ? scenes.filter(scene => !query || matches(scene.name)) : [];
    const sceneHost = $('mijia-scenes'); sceneHost.replaceChildren();
    $('home-scenes-section').hidden = !shownScenes.length;
    shownScenes.forEach(scene => {
      const tile = node('button','home-scene'); tile.type = 'button';
      const mark = node('span','home-scene-symbol'); mark.append(symbol('bolt.fill'));
      const copy = node('span',''); copy.append(node('strong','',scene.name),node('small','','手动场景'));
      tile.append(mark,copy); tile.setAttribute('aria-label', `配置场景：${scene.name}`);
      tile.addEventListener('click', () => openScene(scene)); sceneHost.append(tile);
    });
    $('home-scene-count').textContent = `${shownScenes.length} 个`;
    $('home-device-count').textContent = `${shownDevices.length} 台`;
    $('home-clear-filter').hidden = category === 'all' && !room && !query;
    const host = $('mijia-devices'); host.replaceChildren();
    const groups = new Map();
    shownDevices.forEach(device => {
      const name = device.room || '未分组';
      if (!groups.has(name)) groups.set(name, []);
      groups.get(name).push(device);
    });
    groups.forEach((list, name) => {
      const section = node('section','home-room'), heading = node('h3','home-room-heading',name);
      const grid = node('div','home-device-grid'); section.append(heading,grid);
      list.forEach(device => {
        const tile = node('button',`home-device${device.online ? '' : ' is-offline'}`); tile.type = 'button';
        tile.dataset.category = deviceCategory(device).id;
        const mark = node('span','home-device-symbol'); mark.append(symbol(deviceSymbol(device)));
        const copy = node('span','home-device-copy');
        copy.append(node('strong','',device.name),node('small','',device.online ? '在线 · 查看设备' : '离线 · 查看详情'));
        tile.append(mark,copy);
        tile.setAttribute('aria-label', `${device.name}，${name}，${device.online ? '在线' : '离线'}，查看读数和控制动作`);
        tile.addEventListener('click', () => openDevice(device)); grid.append(tile);
      });
      host.append(section);
    });
    if (!shownDevices.length) {
      const info = node('div','home-no-results'); info.append(symbol('magnifyingglass'),node('strong','',devices.length ? '没有符合条件的设备' : '这个家还没有设备'),
        node('p','',devices.length ? '换一个分类、房间，或清除搜索。' : '在米家 App 添加设备后，点击右上角刷新。'));
      host.append(info);
    }
    const online = devices.filter(device => device.online).length;
    $('mijia-count').textContent = `${devices.length} 台设备 · ${online} 台在线${devices.length - online ? ` · ${devices.length - online} 台离线` : ''}`;
  }
  roomSelect.addEventListener('change', () => { room = roomSelect.value; render(); });
  search.addEventListener('input', () => { query = search.value.trim().toLocaleLowerCase(); render(); });
  $('home-search-toggle').addEventListener('click', () => {
    const open = $('home-search-row').hidden; $('home-search-row').hidden = !open;
    $('home-search-toggle').setAttribute('aria-expanded', String(open));
    if (open) search.focus(); else { query = ''; search.value = ''; render(); }
  });
  $('home-clear-filter').addEventListener('click', () => { reset(); render(); roomSelect.focus(); });
  return {
    reset, empty,
    loading() { empty('正在同步', '读取这个家的设备与手动场景…'); $('mijia-count').textContent = '正在同步家庭与设备'; $('home-empty').setAttribute('aria-busy','true'); },
    show(data) {
      catalog = data; $('home-empty').removeAttribute('aria-busy'); $('home-empty').hidden = true;
      $('home-content').hidden = false; render();
    }
  };
}
