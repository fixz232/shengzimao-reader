'use strict';
// 视觉验收：启动测试宿主，逐个页面截图（书库/在线搜书/设置/应用），用于人工检查新 UI。
const fs = require('node:fs');
const path = require('node:path');
const { spawn } = require('node:child_process');
const { CDP, until, wait, freePort } = require('./cdp.cjs');
const root = path.resolve(__dirname, '..');
(async () => {
  const evidence = path.join(root, '.verification', 'ui-' + Date.now());
  fs.mkdirSync(evidence, { recursive: true });
  const profile = path.join(evidence, 'profile'), legacy = path.join(evidence, 'legacy');
  fs.mkdirSync(legacy, { recursive: true });
  // 造一些书，让书库/统计有内容可看。
  const { Library } = require('../app/library.cjs');
  const library = new Library(profile);
  const titles = ['三体', '活着', '红楼梦', '百年孤独', '人类简史', '小王子', '围城', '明朝那些事儿', 'SICP'];
  for (let i = 0; i < titles.length; i++) {
    const file = path.join(evidence, `seed-${i}.txt`);
    fs.writeFileSync(file, `第一章 ${titles[i]}\n` + `第 ${i} 本书的测试正文，用于界面截图。`.repeat(120) + `\n第二章 继续\n` + '更多正文内容。'.repeat(120));
    const result = await library.importFile(file);
    if (result.book) {
      const id = result.book.id;
      library.updateBooks([id], { author: ['刘慈欣', '余华', '曹雪芹', '马尔克斯', '尤瓦尔', '圣埃克苏佩里', '钱钟书', '当年明月', 'Abelson'][i], category: i % 2 ? '文学' : '经典', tags: ['测试'] });
      if (i % 3 === 0) { try { library.open(id); library.move({ position: 300 + i * 20 }); } catch { /* 进度种子失败不影响截图 */ } }
    }
  }
  library.settings({ mouseControl: false, hideOnBlur: false, bossKeyEnabled: false, view: 'grid' });
  library.state.activeId = ''; library.save();
  const fixtures = path.join(evidence, 'fixtures.json');
  fs.writeFileSync(fixtures, JSON.stringify({ importFiles: [] }));
  const port = await freePort();
  console.log('debug port:', port);
  const env = { ...process.env }; delete env.ELECTRON_RUN_AS_NODE; delete env.NODE_OPTIONS;
  const child = spawn(require('electron'), [path.join(__dirname, 'test-host.cjs'), '--profile=' + profile, '--legacy-profile=' + legacy, '--test-fixtures=' + fixtures, '--remote-debugging-port=' + port], { cwd: root, env, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
  let childLog = '';
  child.stdout.on('data', chunk => childLog += chunk);
  child.stderr.on('data', chunk => childLog += chunk);
  const target = await until(async () => (await (await fetch(`http://127.0.0.1:${port}/json/list`)).json()).find(item => item.type === 'page' && item.url.includes('mode=manager')), 90000)
    .catch(error => { console.log('boot stderr:', childLog.slice(0, 1200)); throw error; });
  const manager = await CDP.connect(target.webSocketDebuggerUrl);
  await until(() => manager.eval('!!document.querySelector(".nav")'), 20000);
  await manager.call('Emulation.setDeviceMetricsOverride', { width: 1180, height: 820, deviceScaleFactor: 1, mobile: false });
  await wait(600);
  await manager.screenshot(path.join(evidence, '01-library-grid.png'));
  // 概览
  await manager.click('[data-tab="libraryTab"][data-value="overview"]');
  await wait(500);
  await manager.screenshot(path.join(evidence, '02-overview.png'));
  // 在线搜书
  await manager.click('[data-route="search"]');
  await wait(500);
  await manager.screenshot(path.join(evidence, '03-online.png'));
  // 设置-外观
  await manager.click('[data-route="settings"]');
  await wait(500);
  await manager.screenshot(path.join(evidence, '04-settings-appearance.png'));
  // 设置-阅读（含 TTS）
  await manager.click('[data-tab="settingsTab"][data-value="reading"]');
  await wait(400);
  await manager.screenshot(path.join(evidence, '05-settings-reading.png'));
  // 应用中心
  await manager.click('[data-route="apps"]');
  await wait(400);
  await manager.click('[data-tab="appsTab"][data-value="plugins"]');
  await wait(400);
  await manager.screenshot(path.join(evidence, '06-apps-plugins.png'));
  // 导入页
  await manager.click('[data-route="import"]');
  await wait(400);
  await manager.screenshot(path.join(evidence, '07-import.png'));
  // 窄窗口检查
  await manager.call('Emulation.setDeviceMetricsOverride', { width: 760, height: 700, deviceScaleFactor: 1, mobile: false });
  await manager.click('[data-route="library"]');
  await wait(500);
  const overflow = await manager.eval('document.documentElement.scrollWidth - innerWidth');
  await manager.screenshot(path.join(evidence, '08-narrow-library.png'));
  console.log('narrow overflow px:', overflow);
  console.log('evidence:', evidence);
  await manager.request('quit').catch(() => {});
  await wait(1200);
  if (child.exitCode === null) child.kill();
})().catch(error => { console.error('ERR', error.message); process.exit(1); });
