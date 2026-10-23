'use strict';
const {app,BrowserWindow}=require('electron');
const fs=require('node:fs'),path=require('node:path'),http=require('node:http'),assert=require('node:assert/strict');
const {spawn}=require('node:child_process');
const {WindowTools,listWindows}=require('../app/window-tools.cjs');
const {until,wait}=require('./cdp.cjs');
const root=path.resolve(__dirname,'../.verification','tools-'+Date.now());fs.mkdirSync(root,{recursive:true});app.setPath('userData',path.join(root,'profile'));
app.on('window-all-closed',()=>{});
app.whenReady().then(async()=>{
  let child,reader,tools,server;const checks=[];let commandId=0;
  const check=(label,value)=>{assert.ok(value,label);checks.push(label);console.log('PASS '+label);};
  const command=(action,extra={})=>fs.writeFileSync(path.join(root,'fixture-command.json'),JSON.stringify({id:++commandId,action,...extra}));
  try{
    const env={...process.env};delete env.ELECTRON_RUN_AS_NODE;delete env.NODE_OPTIONS;
    child=spawn(process.execPath,[path.join(__dirname,'window-fixture.cjs'),'--fixture-dir='+root],{env,stdio:'ignore'});
    const stateFile=path.join(root,'fixture-state.json');await until(()=>fs.existsSync(stateFile));const target=JSON.parse(fs.readFileSync(stateFile,'utf8'));
    reader=new BrowserWindow({x:50,y:50,width:420,height:26,frame:false,show:true,webPreferences:{sandbox:true}});await reader.loadURL('data:text/html,reader-test');
    tools=new WindowTools({getReader:()=>reader,notify:message=>console.log('NOTICE '+message)});
    const list=await listWindows();fs.writeFileSync(path.join(root,'window-list.json'),JSON.stringify({target,matched:list.filter(window=>window.handle===target.handle),total:list.length},null,2));check('真实 Win32 列表包含测试窗口',list.some(window=>window.handle===target.handle));
    await tools.start({handle:target.handle,title:'阅读器依附测试窗口',offsetX:25,offsetY:35,attachReader:true,attachApps:true});await wait(600);
    const before=reader.getBounds();check('阅读窗按偏移依附目标',Math.abs(before.x-target.x-25)<=2&&Math.abs(before.y-target.y-35)<=2);
    command('move',{x:target.x+137,y:target.y+91});await until(()=>Math.abs(reader.getBounds().x-before.x-137)<=2&&Math.abs(reader.getBounds().y-before.y-91)<=2);check('移动目标后真实阅读窗跟随',true);
    command('minimize');await until(()=>!reader.isVisible());command('restore');await until(()=>reader.isVisible());check('目标最小化/恢复时隐藏并恢复阅读窗',true);
    reader.hide();command('minimize');await wait(600);command('restore');await wait(900);check('用户主动隐藏的阅读窗不会被依附误恢复',!reader.isVisible());reader.show();
    tools.stop();const stopped=reader.getBounds();command('move',{x:target.x,y:target.y});await wait(800);check('停止依附后不再移动阅读窗',reader.getBounds().x===stopped.x&&reader.getBounds().y===stopped.y);
    server=http.createServer((_request,response)=>{response.setHeader('Content-Type','text/html; charset=utf-8');response.end('<!doctype html><html><body style="background:rgb(255,0,0)"><p>透明网页本地测试</p><img alt="占位" src="data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a10UAAAAASUVORK5CYII="></body></html>');});await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
    tools.open('web',{url:`http://127.0.0.1:${server.address().port}/`,title:'测试网页',width:600,height:400,gray:true,hideImages:true,transparent:true,opacity:70,zoom:120,top:false});
    const web=[...tools.windows].find(window=>window.readerTool==='web');await until(()=>web.webContents.executeJavaScript('document.querySelector("p")?.textContent==="透明网页本地测试"'));await until(()=>web.webContents.executeJavaScript('getComputedStyle(document.querySelector("img")).visibility==="hidden"'));
    check('网页灰度/隐藏图片/透明背景实际生效',await web.webContents.executeJavaScript('getComputedStyle(document.documentElement).filter==="grayscale(1)"&&getComputedStyle(document.body).backgroundColor==="rgba(0, 0, 0, 0)"'));
    check('网页缩放和整窗透明度实际生效',Math.abs(web.getOpacity()-.7)<.02&&web.webContents.getZoomFactor()===1.2);
    check('外部网页无 Node 和主程序 IPC',await web.webContents.executeJavaScript('typeof require==="undefined"&&typeof window.reader==="undefined"'));
    await tools.start({handle:target.handle,offsetX:20,offsetY:30,attachReader:true,attachApps:true});await wait(600);const old=web.getBounds();command('move',{x:target.x+80,y:target.y+60});await until(()=>web.getBounds().x!==old.x);check('透明应用也随目标移动',true);
    tools.disable('web');await until(()=>web.isDestroyed());check('停用插件关闭对应工具窗口',true);tools.disable('attachment');check('停用依附插件停止跟随',!tools.attached);
    fs.writeFileSync(path.join(root,'report.json'),JSON.stringify({passed:checks.length,checks,environment:{platform:process.platform,electron:process.versions.electron}},null,2));console.log(JSON.stringify({passed:checks.length,evidence:root}));
  }catch(error){console.error(error);fs.writeFileSync(path.join(root,'failure.log'),error.stack);process.exitCode=1;}finally{tools?.close();if(reader&&!reader.isDestroyed())reader.destroy();if(child?.exitCode===null){command('quit');await wait(500);if(child.exitCode===null)child.kill();}if(server)await new Promise(resolve=>server.close(resolve));app.exit(process.exitCode||0);}
}).catch(error=>{console.error(error);app.exit(1);});
