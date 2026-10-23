'use strict';
const fs=require('node:fs'),path=require('node:path'),http=require('node:http'),crypto=require('node:crypto'),assert=require('node:assert/strict');
const {spawn}=require('node:child_process');
const {CDP,until,wait,freePort}=require('./cdp.cjs');

(async()=>{
  const root=path.resolve(__dirname,'..'),evidence=path.join(root,'.verification','desktop-update-'+Date.now());
  fs.mkdirSync(evidence,{recursive:true});
  const profile=path.join(evidence,'profile'),legacy=path.join(evidence,'legacy'),fixtureFile=path.join(evidence,'fixtures.json'),configFile=path.join(evidence,'update.yml');
  fs.mkdirSync(legacy);
  const currentVersion=require('../package.json').version,versionParts=currentVersion.split('.').map(Number),nextVersion=[versionParts[0],versionParts[1]+1,0].join('.');
  let version=currentVersion,missing=false,corrupt=false,slow=false,child,manager,reader,normal,logs='';
  const bytes=crypto.randomBytes(3*1024*1024),sha512=crypto.createHash('sha512').update(bytes).digest('base64'),file='圣子猫阅读器-独立版-'+nextVersion+'-安装包.exe';
  const server=http.createServer((request,response)=>{
    const url=new URL(request.url,'http://localhost');
    if(url.pathname==='/desktop/latest.yml'){
      if(missing){response.writeHead(404);return response.end('not published');}
      response.setHeader('Content-Type','text/yaml; charset=utf-8');
      return response.end('version: '+version+'\nfiles:\n  - url: '+file+'\n    sha512: '+sha512+'\n    size: '+bytes.length+'\nreleaseNotes: "模拟更新说明：新增关于页与远程更新"\n');
    }
    if(decodeURIComponent(url.pathname)!=='/desktop/'+file){response.writeHead(404);return response.end();}
    response.setHeader('Content-Length',bytes.length);response.setHeader('Content-Type','application/octet-stream');
    const body=corrupt?Buffer.from(bytes):bytes;if(corrupt)body[0]^=255;
    if(!slow)return response.end(body);
    let position=0;const timer=setInterval(()=>{if(position>=body.length){clearInterval(timer);response.end();}else{response.write(body.subarray(position,position+65536));position+=65536;}},40);
    response.on('close',()=>clearInterval(timer));
  });
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  fs.writeFileSync(configFile,'provider: generic\nurl: http://127.0.0.1:'+server.address().port+'/desktop/\nchannel: latest\nupdaterCacheDirName: smoke-updater\n');
  const fixtures={importFiles:[],desktopUpdateFeed:configFile,confirmInstall:false};
  fs.writeFileSync(fixtureFile,JSON.stringify(fixtures));
  const port=await freePort(),env={...process.env};delete env.ELECTRON_RUN_AS_NODE;delete env.NODE_OPTIONS;
  const checks=[],clients=[];
  const check=(name,value)=>{assert.ok(value,name);checks.push(name);console.log('PASS '+name);};
  const connect=async mode=>{const target=await until(async()=>(await(await fetch('http://127.0.0.1:'+port+'/json/list')).json()).find(item=>item.type==='page'&&item.url.includes('mode='+mode)),40000);const client=await CDP.connect(target.webSocketDebuggerUrl);clients.push(client);return client;};
  const phase=async()=> (await manager.request('snapshot')).update.phase;
  const awaitPhase=value=>until(async()=>await phase()===value,25000);
  try{
    child=spawn(require('electron'),[path.join(__dirname,'test-host.cjs'),'--profile='+profile,'--legacy-profile='+legacy,'--test-fixtures='+fixtureFile,'--remote-debugging-port='+port],{cwd:root,env,windowsHide:true,stdio:['ignore','pipe','pipe']});
    child.stdout.on('data',chunk=>logs+=chunk);child.stderr.on('data',chunk=>logs+=chunk);
    reader=await connect('reader');await until(()=>reader.eval('!!document.querySelector("#reader-copy")'));await reader.request('show-manager');manager=await connect('manager');await until(()=>manager.eval('!!document.querySelector(".nav")'));
    await manager.click('[data-route=about]');await until(()=>manager.eval('!!document.querySelector(".update-panel")'));
    check('关于页显示实际源码地址',await manager.eval('document.querySelector(".repository-link").textContent==="https://github.com/fixz232/shengzimao-reader"'));
    await assert.rejects(reader.request('update-check'),/关于页面/);check('阅读窗不能直接触发更新操作',true);
    await manager.click('[data-action=update-check]');await awaitPhase('not-available');check('真实更新器识别版本相同',true);
    missing=true;await manager.request('update-check');await awaitPhase('error');check('未发布更新通道时显示可理解的提示',/尚未发布/.test((await manager.request('snapshot')).update.error));
    missing=false;version=nextVersion;await manager.request('update-check');await awaitPhase('available');
    await until(()=>manager.eval('!!document.querySelector("[data-action=update-download]")'));
    check('真实更新器发现新版本并展示说明',(await manager.request('snapshot')).update.releaseNotes.includes('模拟更新说明'));
    await manager.call('Emulation.setDeviceMetricsOverride',{width:940,height:820,deviceScaleFactor:1,mobile:false});
    if(process.env.SZM_SKIP_SCREENSHOTS!=='1')await manager.screenshot(path.join(evidence,'about-update.png'));
    await manager.call('Emulation.setDeviceMetricsOverride',{width:560,height:820,deviceScaleFactor:1,mobile:false});
    check('关于页在最小窗口宽度没有横向溢出',await manager.eval('Array.from(document.querySelectorAll(".workspace,.panel")).every(el=>el.scrollWidth<=el.clientWidth+1)'));
    if(process.env.SZM_SKIP_SCREENSHOTS!=='1')await manager.screenshot(path.join(evidence,'about-narrow.png'));
    slow=true;await manager.click('[data-action=update-download]');await until(async()=>{const u=(await manager.request('snapshot')).update;return u.phase==='downloading'&&u.progress>0&&u.progress<100;},25000);
    check('真实网络下载有进度',true);await manager.request('update-cancel');await awaitPhase('available');await wait(150);check('取消真实网络下载后可重新下载',await phase()==='available');
    slow=false;corrupt=true;await manager.request('update-download');await awaitPhase('error');check('实际 SHA-512 校验拒绝损坏文件',/校验失败/.test((await manager.request('snapshot')).update.error));
    await assert.rejects(manager.request('update-install'),/尚未下载完成/);
    corrupt=false;await manager.request('update-check');await awaitPhase('available');await manager.request('update-download');await awaitPhase('downloaded');
    check('真实更新器下载并校验完整文件',fs.readFileSync(path.join(evidence,'cache','smoke-updater','pending',file)).equals(bytes));
    check('取消安装确认保留已下载更新',await manager.request('update-install')===false&&await phase()==='downloaded'&&!fs.existsSync(path.join(evidence,'install-request.json')));
    const bookFile=path.join(evidence,'阅读进度测试.txt');fs.writeFileSync(bookFile,'第一章 测试\n'+Array.from({length:150},(_,n)=>'第'+n+'段：安装前保存滚动位置，继续阅读时恢复原文进度。').join('\n'));
    await manager.request('import-dropped',{files:[bookFile]});const id=(await manager.request('snapshot')).books[0].id;await manager.request('open',{id,mode:'normal'});normal=await connect('normal');await until(()=>normal.eval('!!document.querySelector(".normal-article p")'));await normal.eval('document.querySelector(".normal-content").scrollTop=900');
    fixtures.confirmInstall=true;fs.writeFileSync(fixtureFile,JSON.stringify(fixtures));await manager.request('update-install');
    const stored=JSON.parse(fs.readFileSync(path.join(profile,'library.json'),'utf8')),install=JSON.parse(fs.readFileSync(path.join(evidence,'install-request.json'),'utf8'));
    check('确认后先落盘普通阅读进度再请求安装',stored.books[0].position>0&&install.restart===true&&install.silent===false);
    check('更新页面没有未捕获异常',clients.every(client=>client.errors.length===0));
    await manager.request('quit').catch(()=>{});await until(()=>child.exitCode!==null);
    fs.writeFileSync(path.join(evidence,'report.json'),JSON.stringify({passed:checks.length,checks,environment:{node:process.version,electron:require('electron/package.json').version,platform:process.platform},note:'使用真实 Electron 网络、版本比较和文件校验；安装启动被测试宿主拦截，不运行合成安装文件。'},null,2));
    console.log(JSON.stringify({passed:checks.length,evidence}));
  }finally{
    clients.forEach(client=>client.close());if(child?.exitCode===null)child.kill();server.closeAllConnections();await new Promise(resolve=>server.close(resolve));fs.writeFileSync(path.join(evidence,'runtime.log'),logs);
  }
})().catch(error=>{console.error(error);process.exitCode=1;});
