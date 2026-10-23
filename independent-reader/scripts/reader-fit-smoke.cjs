'use strict';
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict'),{spawn}=require('node:child_process');
const {Library}=require('../app/library.cjs'),{CDP,until,wait,freePort}=require('./cdp.cjs');
const root=path.resolve(__dirname,'..'),electron=require('electron'),executable=process.env.SZM_TEST_EXE||electron;
const env={...process.env};delete env.ELECTRON_RUN_AS_NODE;delete env.NODE_OPTIONS;
async function runScenario(evidence,upgrade){
  fs.mkdirSync(evidence,{recursive:true});const profile=path.join(evidence,'profile'),legacy=path.join(evidence,'empty-legacy');fs.mkdirSync(legacy);
  const fixture=path.join(evidence,'mixed.txt'),text=('读框测试 中文标点，ABC 123：空 格。 第二段，阅读框外观与翻页对照。').repeat(160);fs.writeFileSync(fixture,text);
  const library=new Library(profile),{book}=await library.importFile(fixture);library.open(book.id);library.settings({bossKeyEnabled:false});
  if(upgrade){library.move({position:37});library.bookmark();delete library.state.readerDefaultsVersion;Object.assign(library.state.settings,{lineSize:20,hideOnBlur:true,progressLine:true,mouseControl:false,mouseAutoShow:false});}
  library.state.readerBounds={x:100,y:100,width:420,height:26};library.save();
  const stableBooks=books=>books.map(({missing,lastReadAt,...book})=>book),originalBooks=JSON.stringify(stableBooks(library.state.books)),port=await freePort(),inspectPort=await freePort();
  const args=[`--profile=${profile}`,`--legacy-profile=${legacy}`,`--remote-debugging-port=${port}`,`--inspect=127.0.0.1:${inspectPort}`];if(executable===electron)args.unshift(root);
  const child=spawn(executable,args,{cwd:root,env,windowsHide:true,stdio:['ignore','pipe','pipe']});let log='',reader,main,runtime,failure;const checks=[],scenes=[];
  child.stdout.on('data',s=>log+=s);child.stderr.on('data',s=>log+=s);
  const check=(name,value)=>{assert.ok(value,name);checks.push(name);console.log('PASS '+(upgrade?'旧配置：':'新配置：')+name);};
  const place=async inside=>{
    await until(async()=>{
      const bounds=await reader.request('window-state'),pointer=inside?{x:bounds.x+Math.floor(bounds.width/3),y:bounds.y+Math.floor(bounds.height/2)}:{x:bounds.x+bounds.width+100,y:bounds.y+bounds.height+100};
      await main.eval(`globalThis.__readerTestPointer=${JSON.stringify(pointer)};true`);await wait(450);return (await reader.request('window-state')).pointerInside===inside;
    },5000);
  };
  const capture=async label=>{
    await reader.call('Page.bringToFront');await wait(300);const data=await reader.eval(`(()=>{const node=document.querySelector('#reader-copy'),style=getComputedStyle(node),c=document.createElement('canvas').getContext('2d');c.font=style.fontWeight+' '+style.fontSize+' '+style.fontFamily;const lines=node.textContent.split(String.fromCharCode(10));return {text:node.textContent,width:innerWidth,height:innerHeight,font:style.font,fontSize:parseFloat(style.fontSize),fontColor:style.color,opacity:style.opacity,boxShadow:style.boxShadow,textDecoration:style.textDecorationLine,background:getComputedStyle(document.querySelector('.reader-strip')).backgroundColor,lineWidths:lines.map(s=>c.measureText(s).width),available:node.clientWidth-parseFloat(style.paddingLeft)-parseFloat(style.paddingRight),copyRect:node.getBoundingClientRect().toJSON(),cursor:style.cursor,resize:getComputedStyle(document.querySelector('.reader-resize'),'::after').content,progressWidth:document.querySelector('#reader-progress').getBoundingClientRect().width}})()`);
    const position=(await reader.request('read')).position;scenes.push({label,position,...data});if(process.env.SZM_SKIP_SCREENSHOTS!=='1')await reader.screenshot(path.join(evidence,label+'.png'));return data;
  };
  const filled=(data,rows)=>data.lineWidths.length===rows&&data.lineWidths.every(width=>width>data.available-data.fontSize*1.25&&width<=data.available+.01);
  const hidden=()=>reader.eval('document.querySelector("#reader-copy").textContent===""&&getComputedStyle(document.querySelector(".reader-strip")).backgroundColor==="rgba(0, 0, 0, 0)"&&getComputedStyle(document.documentElement).backgroundColor==="rgba(0, 0, 0, 0)"');
  const key=async key=>{await reader.eval(`document.dispatchEvent(new KeyboardEvent('keydown',{key:${JSON.stringify(key)},bubbles:true}))`);await wait(220);};
  try{
    const target=await until(async()=>(await(await fetch(`http://127.0.0.1:${port}/json/list`)).json()).find(t=>t.type==='page'&&t.url.includes('mode=reader')),30000);
    reader=await CDP.connect(target.webSocketDebuggerUrl);await until(()=>reader.eval('!!document.querySelector("#reader-copy")'));
    // Substitute only the cursor input in this disposable process via its own
    // loopback debugger. Production files/IPC and the user's mouse are untouched.
    const inspector=await until(async()=>(await(await fetch(`http://127.0.0.1:${inspectPort}/json/list`)).json())[0],30000);main=await CDP.connect(inspector.webSocketDebuggerUrl);
    await until(()=>main.eval('typeof process.getBuiltinModule==="function"||typeof process.mainModule?.require==="function"'));
    runtime=await main.eval(`(()=>{const e=typeof process.mainModule?.require==='function'?process.mainModule.require('electron'):process.getBuiltinModule('module').createRequire(${JSON.stringify(path.join(root,'package.json'))})('electron');return e.app.whenReady().then(()=>{globalThis.__readerTestPointer={x:-100000,y:-100000};e.screen.getCursorScreenPoint=()=>globalThis.__readerTestPointer;return {node:process.versions.node,electron:process.versions.electron,appVersion:e.app.getVersion()};});})()`);
    await wait(400);
    const snapshot=await reader.request('snapshot'),s=snapshot.settings;
    check('隔离配置，默认每行仍为20但启用自动宽度',snapshot.profile===profile&&s.lineSize===20&&s.autoFitWidth);
    check('默认移出隐藏移入显示，关闭失焦伪装和进度线',s.mouseControl&&s.mouseAutoShow&&!s.hideOnBlur&&!s.progressLine);
    check('启动保留书库、进度和书签',JSON.stringify(stableBooks(snapshot.books))===originalBooks);
    check('每次启动先显示阅读框位置提示',!(await reader.request('window-state')).pointerInside&&await reader.eval('document.querySelector(".reader-strip").classList.contains("locating")&&document.querySelector("#reader-copy").textContent.includes("阅读框在这里")'));
    await until(hidden,8000);check('定位提示约5秒后恢复移出隐藏',await hidden());
    await place(true);await until(()=>reader.eval('document.querySelector("#reader-copy").textContent.length>0'));const first=await capture('default-single-420');
    check('默认单行铺满420窗口，不再受20字截断',filled(first,1)&&first.text.length>20);
    check('默认无下划线和底部红线',first.boxShadow==='none'&&first.textDecoration==='none'&&first.progressWidth===0);
    const start=(await reader.request('read')).position;await key('ArrowRight');check('下一页紧接实际显示内容',(await reader.request('read')).position===start+first.text.length);await key('ArrowLeft');check('上一页准确返回',(await reader.request('read')).position===start);
    await reader.request('move',{position:0});await wait(220);await key('ArrowLeft');check('书首向前翻不跳到其它位置',(await reader.request('read')).position===0);
    for(const [width,fontSize] of [[420,13],[600,18],[900,22]])for(const rows of [1,2]){
      await reader.request('settings',{windowWidth:width,fontSize,multiLine:rows>1,lineCount:rows});await wait(250);await place(true);const shot=await capture(`filled-${width}-${rows}-lines`);
      check(`${width}px窗口${rows}行逐行排满且不溢出`,shot.width===width&&filled(shot,rows));
      check(`${width}px窗口${rows}行均无下划线`,shot.boxShadow==='none'&&shot.textDecoration==='none'&&shot.progressWidth===0);
      const before=(await reader.request('read')).position;await key('ArrowRight');check(`${rows}行翻页不漏字或重复`,(await reader.request('read')).position===before+shot.text.replace(/\n/g,'').length);await key('ArrowLeft');check(`${rows}行上一页准确返回`,(await reader.request('read')).position===before);
    }
    for(const patch of [{fontSize:24,fontBold:true},{fontSize:13,fontBold:false,fontFamily:'Consolas'},{fontSize:22,fontFamily:'SimSun'}]){
      await reader.request('settings',patch);await wait(250);await place(true);const shot=await capture('font-'+patch.fontSize+'-'+patch.fontFamily+'-'+patch.fontBold);
      check('字体变化后两行仍铺满 '+shot.font,filled(shot,2));check('改变字号和字体不跳进度',(await reader.request('read')).position===0);
    }
    await reader.request('settings',{windowWidth:600,fontSize:22,fontFamily:'Microsoft YaHei',fontBold:false,multiLine:true,lineCount:3});await wait(250);await place(true);const multi=await capture('three-lines');check('三行仍按实际宽度排字',filled(multi,3));
    await reader.request('settings',{lineScroll:true});await wait(700);await key('ArrowDown');{const actual=await reader.request('read'),settings=(await reader.request('snapshot')).settings,expected=multi.text.split('\n')[0].length;assert.equal(actual.position,expected,JSON.stringify({expected,actual,settings,multi}));check('逐行滚动使用实际首行长度',true);}await reader.request('move',{position:0});
    await reader.request('settings',{multiLine:false,fontSize:13,autoFitWidth:false,lineSize:10,lineScroll:false});await wait(200);const limited=await capture('optional-manual-limit');check('仅手动关闭自动排字后按设定10字截断',Array.from(limited.text).length===10);check('切回单行保留扩大后的窗口高度',limited.height===multi.height);
    await reader.request('settings',{fontOpacity:.4});await wait(200);const alpha=await capture('font-alpha');check('文字透明度正常且没有恢复下划线',alpha.opacity==='1'&&alpha.fontColor==='rgba(32, 36, 44, 0.4)'&&alpha.boxShadow==='none');check('隐藏光标并去掉缩放角图形',alpha.cursor==='none'&&alpha.resize==='none');
    await reader.request('settings',{fontOpacity:1,autoFitWidth:true,lineSize:20,multiLine:true,lineCount:3,fontSize:18,progressLine:true});await reader.request('move',{position:text.length-200});await wait(250);
    check('可选进度线不因预取书尾提前100%',await reader.eval('document.querySelector("#reader-progress").getBoundingClientRect().width>0&&document.querySelector("#reader-progress").getBoundingClientRect().width<innerWidth'));
    await reader.request('settings',{multiLine:false,fontSize:18,windowWidth:400,windowHeight:34,progressLine:false});await reader.request('move',{position:0});await wait(250);await place(true);check('缩窄窗口后重新铺满',filled(await capture('narrow-400'),1));
    await place(false);check('原生边界轮询：移出后正文和背景都隐藏',await hidden());await reader.call('Page.reload');await until(()=>reader.eval('!!document.querySelector("#reader-copy")'));await wait(400);check('页面重载时鼠标在外仍隐藏',await hidden());
    await place(true);check('原生边界轮询：移入后恢复正文',await reader.eval('document.querySelector(".reader-strip").classList.contains("active")&&document.querySelector("#reader-copy").textContent.length>0'));
    await reader.request('show-manager');await wait(400);await place(false);check('打开其它页面不会强制显示阅读正文',(await reader.request('window-state')).managerOpen&&await hidden());
    await place(true);check('阅读框未获焦点时移入也能显示',!(await reader.request('window-state')).focused&&await reader.eval('document.querySelector(".reader-strip").classList.contains("active")'));
    await reader.request('settings',{hideOnBlur:true});await wait(300);check('启用可选失焦伪装也不会挡住移入显示',await reader.eval('document.querySelector(".reader-strip").classList.contains("active")'));await place(false);check('失焦与移出同时发生时优先隐藏，不泄露假地址',await hidden());
    await reader.request('settings',{hideOnBlur:false,mouseAutoShow:false});await wait(250);await place(true);check('用户关闭自动显示后移入保持隐藏',await hidden());await reader.request('settings',{mouseAutoShow:true});await until(()=>reader.eval('document.querySelector(".reader-strip").classList.contains("active")'));check('重新启用自动显示后可恢复',true);
    check('阅读器没有未捕获异常',reader.errors.length===0);
  }catch(error){failure=error.stack;throw error;}finally{
    if(reader){if(process.env.SZM_SKIP_SCREENSHOTS!=='1')await reader.screenshot(path.join(evidence,'final.png')).catch(()=>{});await reader.request('quit').catch(()=>{});reader.close();}main?.close();await until(()=>child.exitCode!==null,5000).catch(()=>{});if(child.exitCode===null)child.kill();
    fs.writeFileSync(path.join(evidence,'runtime.log'),log);fs.writeFileSync(path.join(evidence,'report.json'),JSON.stringify({passed:checks.length,checks,scenes,failure,upgrade,executable,environment:{hostNode:process.version,...runtime,platform:process.platform},limits:['鼠标坐标使用隔离测试进程内的输入替身；窗口、焦点、IPC与边界判断/轮询仍为生产实现，不移动用户鼠标或操作现有程序。','真实物理鼠标/多显示器长期悬停仍需人工场景验收。']},null,2));
  }
  return checks.length;
}
(async()=>{const evidence=path.join(root,'.verification','reader-fit-'+Date.now());let passed=0;for(const upgrade of [false,true])passed+=await runScenario(path.join(evidence,upgrade?'upgraded':'fresh'),upgrade);fs.writeFileSync(path.join(evidence,'report.json'),JSON.stringify({passed,scenarios:['fresh','upgraded'],executable},null,2));console.log(JSON.stringify({passed,evidence}));})().catch(error=>{console.error(error);process.exitCode=1;});
