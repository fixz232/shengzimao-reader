'use strict';
const fs = require('node:fs');
const path = require('node:path');
const net = require('node:net');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const { spawn } = require('node:child_process');
const { Library } = require('../app/library.cjs');
const root=path.resolve(__dirname,'..');
const wait=ms=>new Promise(resolve=>setTimeout(resolve,ms));
async function until(fn,timeout=20000) { const end=Date.now()+timeout;let last;while(Date.now()<end){try { const value=await fn();if(value)return value; }catch(error){last=error;}await wait(120);}throw last||new Error('等待超时'); }
async function port() { return new Promise(resolve=>{const server=net.createServer();server.listen(0,'127.0.0.1',()=>{const value=server.address().port;server.close(()=>resolve(value));});}); }
class CDP {
  constructor(ws){this.ws=ws;this.id=0;this.pending=new Map();this.errors=[];ws.addEventListener('close',()=>{for(const item of this.pending.values()){clearTimeout(item.timer);item.reject(new Error('页面已关闭'));}this.pending.clear();});ws.addEventListener('message',event=>{const msg=JSON.parse(event.data);if(msg.id){const item=this.pending.get(msg.id);this.pending.delete(msg.id);if(item){clearTimeout(item.timer);msg.error?item.reject(new Error(msg.error.message)):item.resolve(msg.result);}}if(msg.method==='Runtime.exceptionThrown')this.errors.push(msg.params.exceptionDetails);});}
  static async connect(url){const ws=new WebSocket(url);await new Promise((resolve,reject)=>{ws.addEventListener('open',resolve,{once:true});ws.addEventListener('error',reject,{once:true});});const c=new CDP(ws);await c.call('Runtime.enable');await c.call('Page.enable');return c;}
  call(method,params={}){const id=++this.id;return new Promise((resolve,reject)=>{const timer=setTimeout(()=>{this.pending.delete(id);reject(new Error('CDP 超时：'+method));},30000);this.pending.set(id,{resolve,reject,timer});this.ws.send(JSON.stringify({id,method,params}));});}
  async eval(expression){const result=await this.call('Runtime.evaluate',{expression,awaitPromise:true,returnByValue:true,userGesture:true});if(result.exceptionDetails)throw new Error(result.exceptionDetails.exception?.description||result.exceptionDetails.text);return result.result.value;}
  request(command,payload={}){return this.eval(`window.reader.request(${JSON.stringify(command)},${JSON.stringify(payload)})`);}
  async click(selector){return this.eval(`document.querySelector(${JSON.stringify(selector)}).click()`);}
  async screenshot(file){const result=await this.call('Page.captureScreenshot',{format:'png'});fs.writeFileSync(file,Buffer.from(result.data,'base64'));}
  close(){this.ws.close();}
}
async function run(){
  const evidence=path.join(root,'.verification',`ui-${Date.now()}`);fs.mkdirSync(evidence,{recursive:true});
  const profile=path.join(evidence,'profile'), legacy=path.join(evidence,'legacy');fs.mkdirSync(legacy);
  const fixture=path.join(evidence,'import.txt');fs.writeFileSync(fixture,'第一章 独立实现\n这是用于界面回归的合成小说，不含用户书库。'.repeat(160));
  const legacyText=path.join(legacy,'legacy.txt');fs.writeFileSync(legacyText,'旧版迁移测试的正文。'.repeat(100));
  const legacyFile=path.join(legacy,'single-reader-state.json');fs.writeFileSync(legacyFile,JSON.stringify({Book:{books:[{path:legacyText,title:'旧版迁移测试',readPosition:65,readPositionUpdatedAt:1,bookmarks:[{position:32,title:'旧书签'}]}]},'single-reader-state':{Book:{books:[{path:legacyText,title:'旧版迁移测试',readPosition:90,readPositionUpdatedAt:2}]}}}));
  const digest=file=>crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');const oldHash=digest(legacyFile),oldTextHash=digest(legacyText);
  const library=new Library(profile);for(let i=1;i<=9;i++){const file=path.join(evidence,`grid-${i}.txt`);fs.writeFileSync(file,`第 ${i} 本书的测试内容。`.repeat(80));await library.importFile(file);}
  const fixtures=path.join(evidence,'fixtures.json');fs.writeFileSync(fixtures,JSON.stringify({importFiles:[fixture]}));
  const debugPort=await port();const env={...process.env};delete env.ELECTRON_RUN_AS_NODE;delete env.NODE_OPTIONS;
  let child,manager,reader,stderr='';const checks=[];
  const launch=()=>{child=spawn(require('electron'),[path.join(__dirname,'test-host.cjs'),`--profile=${profile}`,`--legacy-profile=${legacy}`,`--test-fixtures=${fixtures}`,`--remote-debugging-port=${debugPort}`],{cwd:root,env,windowsHide:true,stdio:['ignore','pipe','pipe']});child.stdout.on('data',chunk=>{stderr+=chunk;});child.stderr.on('data',chunk=>{stderr+=chunk;});child.on('error',error=>{stderr+=error.message;});};
  const targets=async()=>{const response=await fetch(`http://127.0.0.1:${debugPort}/json/list`);return response.json();};
  const connect=async mode=>{const target=await until(async()=>(await targets()).find(target=>target.type==='page'&&target.url.includes('mode='+mode)));return CDP.connect(target.webSocketDebuggerUrl);};
  const check=(name,value)=>{assert.ok(value,name);checks.push(name);console.log('PASS '+name);};
  try{
    launch();manager=await connect('manager');await until(()=>manager.eval('!!document.querySelector(".book")'));
    check('9 本合成小说在书库显示',(await manager.request('snapshot')).books.length===9);
    check('九宫格默认三列',await manager.eval('getComputedStyle(document.querySelector(".library")).gridTemplateColumns.split(" ").length===3'));
    check('无 Node 或 remote 暴露',await manager.eval('typeof require==="undefined" && typeof process==="undefined" && typeof window.reader.request==="function"'));
    await manager.click('[data-action="view-list"]');await until(()=>manager.eval('document.querySelector(".library").classList.contains("list")'));check('切换列表有效',true);
    await manager.click('[data-action="view-grid"]');await until(()=>manager.eval('document.querySelector(".library").classList.contains("grid")'));
    await manager.click('[data-action="import"]');await until(async()=>(await manager.request('snapshot')).books.length===10);check('通过应用 IPC 导入 TXT',true);
    const imported=(await manager.request('snapshot')).books.find(book=>book.title==='import');assert.ok(imported);
    await manager.request('open',{id:imported.id});reader=await connect('reader');await until(()=>reader.eval('document.querySelector("#reader-copy")?.textContent.includes("独立实现")'));
    check('阅读框实际渲染可见',await reader.eval('document.querySelector("#reader-copy").getBoundingClientRect().height>20 && document.visibilityState==="visible"'));
    await reader.screenshot(path.join(evidence,'reader.png'));
    await reader.click('[data-action="next"]');await until(async()=>(await manager.request('snapshot')).books.find(book=>book.id===imported.id).position>0);
    const moved=(await manager.request('snapshot')).books.find(book=>book.id===imported.id).position;check('阅读窗翻页同步进度',moved>0);
    await reader.click('[data-action="bookmark"]');await until(async()=>(await manager.request('snapshot')).books.find(book=>book.id===imported.id).bookmarks.length===1);check('阅读窗保存书签',true);
    await manager.request('settings',{fontSize:22,lineCount:3});await until(()=>reader.eval('getComputedStyle(document.querySelector("#reader-copy")).fontSize==="22px"'));check('阅读设置跨窗口生效',true);
    await manager.request('hide-reader');await until(()=>reader.eval('document.visibilityState==="hidden"'));await manager.request('show-reader');await until(()=>reader.eval('document.visibilityState==="visible"'));check('隐藏后可找回阅读窗',true);
    await manager.click('[data-route="migration"]');const scan=await manager.request('legacy-scan');check('旧版双层数据去重扫描',scan.found===1);
    const report=await manager.request('legacy-migrate');check('迁移复制成功',report.imported===1);
    const old=(await manager.request('snapshot')).books.find(book=>book.title==='旧版迁移测试');check('迁移保留较新位置',old.position===90);
    const repeated=await manager.request('legacy-migrate');check('再次迁移不重复导入',repeated.skipped===1);
    check('旧版 JSON 和正文哈希不变',digest(legacyFile)===oldHash&&digest(legacyText)===oldTextHash);
    await manager.click('[data-route="search"]');await manager.request('search',{keyword:'西游记'});await manager.click('[data-route="settings"]');check('离开搜书页面后任务仍运行',(await manager.request('snapshot')).online.running);
    await manager.click('[data-route="library"]');check('搜书时书库仍可操作',(await manager.request('snapshot')).online.running);await manager.request('online-cancel');check('主动取消任务有效',!(await manager.request('snapshot')).online.running);
    await until(()=>manager.eval('document.querySelectorAll(".book").length===11'));
    check('主窗口无水平溢出',await manager.eval('document.documentElement.scrollWidth<=innerWidth'));
    await manager.screenshot(path.join(evidence,'library.png'));
    await manager.call('Emulation.setDeviceMetricsOverride',{width:700,height:650,deviceScaleFactor:1,mobile:false});check('窄窗口无水平溢出',await manager.eval('document.documentElement.scrollWidth<=innerWidth'));
    await manager.screenshot(path.join(evidence,'library-narrow.png'));await manager.call('Emulation.clearDeviceMetricsOverride');
    check('渲染进程无未捕获异常',manager.errors.length===0&&reader.errors.length===0);
    await manager.request('quit').catch(()=>{});manager.close();reader.close();await until(()=>child.exitCode!==null);
    launch();manager=await connect('manager');await until(()=>manager.eval('!!document.querySelector(".book")'));
    const reloaded=await manager.request('snapshot');const saved=reloaded.books.find(book=>book.id===imported.id);
    check('重启后书库/进度/书签保留',reloaded.books.length===11&&saved.position===moved&&saved.bookmarks.length===1);
    await manager.request('open',{id:imported.id});reader=await connect('reader');await until(()=>reader.eval('document.querySelector("#reader-copy")?.textContent.length>0'));check('重启后阅读窗可见',await reader.eval('document.visibilityState==="visible"'));
    await manager.request('quit').catch(()=>{});await until(()=>child.exitCode!==null);
    fs.writeFileSync(path.join(evidence,'report.json'),JSON.stringify({passed:checks.length,checks,environment:{node:process.version,electron:require('electron/package.json').version,platform:process.platform},oldHash,oldTextHash,limitations:['原生文件选择/确认对话框使用测试替身；生产入口未加入绕过接口','网络书源搜索结果及完整章节下载未在此测试中作成功断言']},null,2));
    console.log(JSON.stringify({passed:checks.length,evidence}));
  }finally{manager?.close();reader?.close();if(child?.exitCode===null)child.kill();fs.writeFileSync(path.join(evidence,'runtime.log'),stderr);}
}
run().catch(error=>{console.error(error);process.exitCode=1;});
