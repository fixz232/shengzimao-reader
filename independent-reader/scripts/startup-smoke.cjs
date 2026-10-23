'use strict';
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict'),{spawn}=require('node:child_process');
const {Library}=require('../app/library.cjs'),{CDP,until,wait,freePort}=require('./cdp.cjs');
(async()=>{
  const root=path.resolve(__dirname,'..'),evidence=path.join(root,'.verification','startup-'+Date.now()),profile=path.join(evidence,'profile'),legacy=path.join(evidence,'legacy');fs.mkdirSync(legacy,{recursive:true});
  const file=path.join(evidence,'定位测试.txt');fs.writeFileSync(file,'第一章 定位\n'+'阅读位置与书签应保持不变。'.repeat(500));
  const library=new Library(profile),{book}=await library.importFile(file);library.open(book.id);library.move({position:37});library.bookmark();library.settings({bossKeyEnabled:false,fontOpacity:0,backgroundOpacity:0,autoRead:true,autoReadSeconds:1});library.state.readerBounds={x:100,y:100,width:420,height:26};library.save();
  const executable=process.env.SZM_TEST_EXE||require('electron'),port=await freePort(),inspectPort=await freePort(),env={...process.env};delete env.ELECTRON_RUN_AS_NODE;delete env.NODE_OPTIONS;
  const args=[`--profile=${profile}`,`--legacy-profile=${legacy}`,`--remote-debugging-port=${port}`,`--inspect=127.0.0.1:${inspectPort}`];if(!process.env.SZM_TEST_EXE)args.unshift(root);
  let child,reader,main,log='';const checks=[],check=(name,value)=>{assert.ok(value,name);checks.push(name);console.log('PASS '+name);};
  const locate=()=>reader.eval('document.querySelector(".reader-strip")?.classList.contains("locating")&&document.querySelector("#reader-copy").textContent==="阅读框在这里 · 欢迎使用圣子猫阅读器"');
  const hidden=()=>reader.eval('document.querySelector("#reader-copy")?.textContent===""&&!document.querySelector(".reader-strip").classList.contains("locating")');
  const launch=async()=>{
    child=spawn(executable,args,{cwd:root,env,windowsHide:true,stdio:['ignore','pipe','pipe']});child.stdout.on('data',s=>log+=s);child.stderr.on('data',s=>log+=s);
    const inspector=await until(async()=>(await(await fetch(`http://127.0.0.1:${inspectPort}/json/list`)).json())[0],30000);main=await CDP.connect(inspector.webSocketDebuggerUrl);
    await until(()=>main.eval(`globalThis.__e=typeof process.mainModule?.require==='function'?process.mainModule.require('electron'):process.getBuiltinModule('module').createRequire(${JSON.stringify(path.join(root,'package.json'))})('electron');Boolean(__e.app?.isReady())`));
    await main.eval(`globalThis.__pointer={x:-100000,y:-100000};__e.screen.getCursorScreenPoint=()=>__pointer;true`);
    const target=await until(async()=>(await(await fetch(`http://127.0.0.1:${port}/json/list`)).json()).find(t=>t.url.includes('mode=reader')),30000);reader=await CDP.connect(target.webSocketDebuggerUrl);await until(locate);
  };
  const quit=async()=>{await reader.request('quit').catch(()=>{});reader.close();main.close();reader=main=null;await until(()=>child.exitCode!==null);};
  try{
    await launch();let snapshot=await reader.request('snapshot');const deadline=snapshot.readerRevealUntil;
    check('已有书籍启动显示阅读框位置提示',await locate()&&(await reader.request('window-state')).visible);
    check('透明文字设置不影响定位提示辨识',await reader.eval('getComputedStyle(document.querySelector("#reader-copy")).color==="rgb(35, 54, 83)"&&getComputedStyle(document.querySelector(".reader-strip")).backgroundColor==="rgb(233, 240, 255)"'));
    check('提示显示于上次保存的位置',(await reader.request('window-state')).x===100&&(await reader.request('window-state')).y===100);
    if(process.env.SZM_SKIP_SCREENSHOTS!=='1')await reader.screenshot(path.join(evidence,'locate.png'));
    await until(hidden,8000);const elapsed=Date.now()-deadline;check('约5秒后自动恢复隐蔽',elapsed>=0&&elapsed<2200);
    snapshot=await reader.request('snapshot');check('提示期间不自动翻页且保留书签',snapshot.books[0].position===37&&snapshot.books[0].bookmarks.length===1);
    check('定位不修改用户隐蔽设置',snapshot.settings.fontOpacity===0&&snapshot.settings.backgroundOpacity===0&&snapshot.settings.mouseControl&&snapshot.settings.autoRead);
    await quit();await launch();check('第二次启动再次显示位置提示',await locate());
    await reader.request('settings',{autoRead:false});const bounds=await reader.request('window-state');await main.eval(`globalThis.__pointer={x:${bounds.x+40},y:${bounds.y+10}};true`);await until(()=>reader.eval('document.querySelector(".reader-strip").classList.contains("active")'));
    check('鼠标移入立刻结束提示并显示正文',await reader.eval('!document.querySelector(".reader-strip").classList.contains("locating")&&document.querySelector("#reader-copy").textContent!==""'));
    const outsideAt=Date.now();await main.eval('globalThis.__pointer={x:-100000,y:-100000};true');await until(hidden,2000);check('移出后立即恢复隐蔽且不再重复定位',await hidden()&&Date.now()-outsideAt<1500);
    check('重复启动不改变阅读偏移与书签',(await reader.request('read')).position===37&&(await reader.request('snapshot')).books[0].bookmarks.length===1);
    check('渲染无未捕获异常',!reader.errors.length);await quit();
    fs.writeFileSync(path.join(evidence,'report.json'),JSON.stringify({passed:checks.length,checks,executable,limits:['光标坐标仅在隔离进程内替换，未移动用户鼠标。']},null,2));console.log(JSON.stringify({passed:checks.length,evidence}));
  }finally{reader?.close();main?.close();if(child?.exitCode===null)child.kill();fs.writeFileSync(path.join(evidence,'runtime.log'),log);}
})().catch(error=>{console.error(error);process.exitCode=1;});
