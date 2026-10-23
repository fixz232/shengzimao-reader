'use strict';
const path = require('node:path');
const fs = require('node:fs');
const { stage } = require('./stage.cjs');
const { build, Platform } = require('electron-builder');
const root = path.resolve(__dirname, '..');
(async () => {
  if(![20,22,24].includes(Number(process.versions.node.split('.')[0])))throw new Error('构建请使用 Node.js 20、22 或 24 LTS。当前打包器不支持 Node.js '+process.versions.node+'；推荐 Node.js 24 LTS。');
  stage();
  const electronPackage = require.resolve('electron/package.json');
  const electronDist = path.join(path.dirname(electronPackage), 'dist');
  if (!fs.existsSync(path.join(electronDist, 'electron.exe'))) throw new Error('Electron 开发运行时缺失，请先安装依赖。');
  const directory = process.argv.includes('--dir');
  const result = await build({ projectDir: root, targets: directory ? Platform.WINDOWS.createTarget('dir') : Platform.WINDOWS.createTarget('nsis'), config: { electronDist, electronVersion: '33.4.11', npmRebuild: false,afterPack:context=>require('./verify-package.cjs').verifyPackage(path.join(context.appOutDir,'resources','app.asar')) } });
  console.log(JSON.stringify(result));
})().catch(error => { console.error(error); process.exitCode = 1; });
