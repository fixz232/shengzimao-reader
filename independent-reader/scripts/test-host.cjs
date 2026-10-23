'use strict';
// Test-only launcher; no test commands or bypasses are exposed by production IPC.
const { app, dialog, Menu } = require('electron');
const path = require('node:path');
const fs = require('node:fs');
const fixturesArg = process.argv.find(value => value.startsWith('--test-fixtures='));
if (!fixturesArg || !process.argv.some(value => value.startsWith('--profile=')) || !process.argv.some(value => value.startsWith('--legacy-profile='))) throw new Error('测试必须使用独立配置和合成旧书库。');
const fixtures=JSON.parse(fs.readFileSync(fixturesArg.slice('--test-fixtures='.length),'utf8'));
dialog.showOpenDialog=async()=>({canceled:false,filePaths:fixtures.importFiles});
dialog.showSaveDialog=async()=>({canceled:false,filePath:fixtures.outputFile});
dialog.showMessageBox=async()=>({response:fixtures.desktopUpdateFeed?(JSON.parse(fs.readFileSync(fixturesArg.slice('--test-fixtures='.length),'utf8')).confirmInstall?1:0):1});
// Exercise the real template and click callbacks without automating the OS menu.
const buildMenu=Menu.buildFromTemplate.bind(Menu);
Menu.buildFromTemplate=template=>{const menu=buildMenu(template);menu.popup=()=>{fs.writeFileSync(path.join(path.dirname(fixturesArg.slice('--test-fixtures='.length)),'menu.json'),JSON.stringify(template.map(({label,type,checked,enabled})=>({label,type,checked,enabled}))));const item=template.find(item=>item.label===fixtures.menuClick);if(item?.click)item.click();};return menu;};
app.setVersion(require('../package.json').version);
if(fixtures.desktopUpdateFeed){
  const {autoUpdater}=require('electron-updater');
  autoUpdater.forceDevUpdateConfig=true;
  autoUpdater.updateConfigPath=fixtures.desktopUpdateFeed;
  autoUpdater.disableDifferentialDownload=true;
  Object.defineProperty(autoUpdater.app,'baseCachePath',{value:path.join(path.dirname(fixtures.desktopUpdateFeed),'cache')});
  autoUpdater.quitAndInstall=(silent,restart)=>fs.writeFileSync(path.join(path.dirname(fixtures.desktopUpdateFeed),'install-request.json'),JSON.stringify({silent,restart}));
  const {DesktopUpdater}=require('../app/desktop-updater.cjs');
  require('../app/desktop-updater.cjs').DesktopUpdater=class extends DesktopUpdater{constructor(options){super({...options,isPackaged:true});}};
}
if(fixtures.rulesDir){
  const online=require('../app/online.cjs'),runEngine=online.runEngine,OnlineTasks=online.OnlineTasks;
  let failFirstSearch=fixtures.failFirstSearch===true;
  online.runEngine=options=>{if(failFirstSearch&&options.input.action==='search'){failFirstSearch=false;return Promise.reject(new Error('合成搜索失败：用于验证重试入口。'));}return runEngine({...options,rulesDir:fixtures.rulesDir});};
  online.OnlineTasks=class extends OnlineTasks{constructor(options){super({...options,runner:online.runEngine});}};
}
require(path.join(__dirname,'../app/main.cjs'));
