'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const {EventEmitter}=require('node:events');
const {DesktopUpdater,releaseNotesText}=require('../app/desktop-updater.cjs');

class FakeUpdater extends EventEmitter {
  constructor(){super();this.checks=0;this.downloads=0;this.installs=0;this.available=true;}
  checkForUpdates(){this.checks++;if(this.checkError)throw this.checkError;this.emit(this.available?'update-available':'update-not-available',{version:'0.6.0',releaseNotes:[{note:'修复问题'}]});return Promise.resolve();}
  downloadUpdate(token){this.downloads++;this.token=token;return token.createPromise((resolve,reject)=>{this.complete=()=>{this.emit('update-downloaded',{version:'0.6.0'});resolve(['update.exe']);};this.fail=error=>{this.emit('error',error);reject(error);};});}
  quitAndInstall(){this.installs++;}
}
const tick=()=>new Promise(resolve=>setImmediate(resolve));
async function available(desktop){desktop.check();await desktop.pending;}
async function downloaded(desktop,updater){await available(desktop);desktop.download();await tick();updater.complete();await desktop.pending;}

test('disables automatic downloading and gates checks to packaged Windows builds',async()=>{
  const updater=new FakeUpdater(),desktop=new DesktopUpdater({updater,isPackaged:true,platform:'win32'});
  assert.equal(updater.autoDownload,false);assert.equal(updater.autoInstallOnAppQuit,false);assert.equal(updater.allowPrerelease,false);
  await available(desktop);assert.equal(updater.checks,1);assert.equal(desktop.snapshot().supported,true);
  const unsupported=new DesktopUpdater({updater:new FakeUpdater(),isPackaged:false,platform:'win32'});
  assert.throws(()=>unsupported.check(),/Windows 安装版/);
});

test('reports availability, release notes, real cancellation tokens, and download completion',async()=>{
  const updater=new FakeUpdater(),desktop=new DesktopUpdater({updater,isPackaged:true,platform:'win32'});
  await available(desktop);
  assert.equal(desktop.snapshot().phase,'available');assert.equal(desktop.snapshot().releaseNotes,'修复问题');
  desktop.download();await tick();assert.equal(updater.downloads,1);
  updater.emit('download-progress',{percent:48.5,bytesPerSecond:1024,transferred:50,total:100});
  assert.equal(desktop.snapshot().phase,'downloading');assert.equal(desktop.snapshot().progress,48.5);
  const firstToken=updater.token;desktop.cancel();assert.equal(firstToken.cancelled,true);assert.equal(desktop.snapshot().phase,'cancelling');
  assert.throws(()=>desktop.download(),/当前任务/);updater.emit('download-progress',{percent:99});updater.emit('update-downloaded',{version:'0.6.0'});await desktop.pending;
  assert.equal(desktop.snapshot().phase,'available');assert.equal(desktop.snapshot().error,'');
  desktop.download();await tick();assert.notEqual(updater.token,firstToken);assert.equal(updater.token.cancelled,false);updater.complete();await desktop.pending;
  assert.equal(desktop.snapshot().phase,'downloaded');assert.equal(desktop.snapshot().progress,100);
});

test('flushes reading state before confirmed install and reports updater errors',async()=>{
  const updater=new FakeUpdater(),desktop=new DesktopUpdater({updater,isPackaged:true,platform:'win32'});
  await assert.rejects(desktop.install({confirm:async()=>true,flushProgress:async()=>{}}),/尚未下载完成/);
  await downloaded(desktop,updater);
  const order=[];assert.equal(await desktop.install({confirm:async()=>false,flushProgress:async()=>order.push('should not flush')}),false);assert.deepEqual(order,[]);assert.equal(updater.installs,0);
  assert.equal(desktop.snapshot().phase,'downloaded');
  await assert.rejects(desktop.install({confirm:async()=>true,flushProgress:async()=>{throw new Error('保存失败');}}),/保存失败/);assert.equal(updater.installs,0);assert.equal(desktop.snapshot().phase,'downloaded');
  await desktop.install({confirm:async()=>true,flushProgress:async()=>order.push('flush')});order.push('install');
  assert.deepEqual(order,['flush','install']);assert.equal(updater.installs,1);
  updater.emit('error',new Error('网络不可用'));assert.equal(desktop.snapshot().phase,'error');assert.match(desktop.snapshot().error,/网络不可用/);
});

test('blocks concurrent checks and duplicate install confirmation',async()=>{
  const updater=new FakeUpdater(),desktop=new DesktopUpdater({updater,isPackaged:true,platform:'win32'});
  desktop.check();assert.throws(()=>desktop.check(),/正在进行/);await desktop.pending;
  desktop.download();await tick();updater.complete();await desktop.pending;
  let approve;const first=desktop.install({confirm:()=>new Promise(resolve=>{approve=resolve;}),flushProgress:async()=>{}});
  await assert.rejects(desktop.install({confirm:async()=>true,flushProgress:async()=>{}}),/安装已在进行/);
  approve(false);await first;assert.equal(updater.installs,0);
});

test('handles no update, channel not published, network retry, and failed checksum without installing',async()=>{
  const updater=new FakeUpdater(),desktop=new DesktopUpdater({updater,isPackaged:true,platform:'win32'});
  updater.available=false;await available(desktop);assert.equal(desktop.snapshot().phase,'not-available');
  updater.checkError=Object.assign(new Error('404'),{code:'ERR_UPDATER_CHANNEL_FILE_NOT_FOUND'});await available(desktop);assert.match(desktop.snapshot().error,/尚未发布/);
  updater.checkError=new Error('网络不可用');await available(desktop);assert.match(desktop.snapshot().error,/网络不可用/);
  updater.checkError=null;updater.available=true;await available(desktop);desktop.download();await tick();updater.fail(new Error('sha512 checksum mismatch'));await desktop.pending;
  assert.equal(desktop.snapshot().phase,'error');assert.match(desktop.snapshot().error,/校验失败/);await assert.rejects(desktop.install({confirm:async()=>true,flushProgress:async()=>{}}),/尚未下载完成/);assert.equal(updater.installs,0);
});

test('normalizes and bounds remote release notes',()=>{
  assert.equal(releaseNotesText([{note:'第一条'},{note:'第二条'}]),'第一条\n第二条');
  assert.equal(releaseNotesText('x'.repeat(13000)).length,12000);
});
