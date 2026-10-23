'use strict';
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict'),{spawn}=require('node:child_process');
const {CDP,until,wait,freePort}=require('./cdp.cjs');
(async()=>{
  const root=path.resolve(__dirname,'..'),evidence=path.join(root,'.verification','welcome-'+Date.now()),profile=path.join(evidence,'profile'),legacy=path.join(evidence,'legacy');fs.mkdirSync(legacy,{recursive:true});
  const port=await freePort(),env={...process.env};delete env.ELECTRON_RUN_AS_NODE;delete env.NODE_OPTIONS;
  const executable=process.env.SZM_TEST_EXE||require('electron'),args=[`--profile=${profile}`,`--legacy-profile=${legacy}`,`--remote-debugging-port=${port}`];if(!process.env.SZM_TEST_EXE)args.unshift(root);
  const child=spawn(executable,args,{cwd:root,env,windowsHide:true,stdio:['ignore','pipe','pipe']});let log='',reader,manager;const checks=[];
  child.stdout.on('data',s=>log+=s);child.stderr.on('data',s=>log+=s);
  const check=(name,value)=>{assert.ok(value,name);checks.push(name);console.log('PASS '+name);};
  const targets=async()=>(await(await fetch(`http://127.0.0.1:${port}/json/list`)).json());
  const connect=async mode=>{const t=await until(async()=>(await targets()).find(t=>t.url.includes('mode='+mode)),30000);return CDP.connect(t.webSocketDebuggerUrl);};
  try{
    reader=await connect('reader');await until(()=>reader.eval('document.querySelector("#reader-copy")?.textContent==="欢迎使用圣子猫阅读器"'));
    check('全新用户首次启动默认隐蔽单行模式',(await reader.request('snapshot')).settings.readingMode==='stealth');
    check('首先显示阅读框及准确欢迎文案',(await reader.request('window-state')).visible&&await reader.eval('document.querySelector("#reader-copy").textContent==="欢迎使用圣子猫阅读器"'));
    check('首次不强行打开管理界面',!(await targets()).some(t=>t.url.includes('mode=manager')));
    await reader.eval('document.dispatchEvent(new MouseEvent("mouseleave"))');await wait(700);
    check('未选书时欢迎文字不会被鼠标移出立即清空',await reader.eval('document.querySelector("#reader-copy").textContent==="欢迎使用圣子猫阅读器"'));
    if(process.env.SZM_SKIP_SCREENSHOTS!=='1')await reader.screenshot(path.join(evidence,'welcome.png'));
    await reader.eval('document.dispatchEvent(new MouseEvent("dblclick"))');manager=await connect('manager');await until(()=>manager.eval('!!document.querySelector("[data-route=about]")'));check('双击欢迎阅读框可以进入书库',true);
    const file=path.join(evidence,'first-book.txt');fs.writeFileSync(file,'第一章 开始\n这是首次打开书籍后的正文。');await manager.request('import-dropped',{files:[file]});const book=(await manager.request('snapshot')).books[0];await manager.request('settings',{mouseControl:false});await manager.request('open',{id:book.id});await until(()=>reader.eval('document.querySelector("#reader-copy").textContent.includes("第一章")'));check('打开书籍后欢迎文字切换为真实正文',true);
    check('欢迎流程无渲染异常',!reader.errors.length&&!manager.errors.length);await manager.request('quit').catch(()=>{});await until(()=>child.exitCode!==null);
    fs.writeFileSync(path.join(evidence,'report.json'),JSON.stringify({passed:checks.length,checks,executable},null,2));console.log(JSON.stringify({passed:checks.length,evidence}));
  }finally{reader?.close();manager?.close();if(child.exitCode===null)child.kill();fs.writeFileSync(path.join(evidence,'runtime.log'),log);}
})().catch(error=>{console.error(error);process.exitCode=1;});
