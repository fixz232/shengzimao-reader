'use strict';
const { BrowserWindow, screen } = require('electron');
const { spawn } = require('node:child_process');
const path = require('node:path');
const clamp=(n,min,max,fallback)=>Number.isFinite(Number(n))?Math.max(min,Math.min(max,Number(n))):fallback;
function externalWindows(mode, options={}) {
  if(process.platform!=='win32')throw new Error('窗口依附目前仅支持 Windows。');
  const script=path.join(__dirname,'native-windows.ps1').replace('app.asar'+path.sep,'app.asar.unpacked'+path.sep);
  return spawn('powershell.exe',['-NoLogo','-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File',script,'-Mode',mode,'-ExcludeProcess',String(process.pid),'-ParentPid',String(process.pid),...(mode==='Watch'?['-TargetHandle',String(options.handle||0),'-TitleBase64',Buffer.from(options.title||'','utf8').toString('base64')]:[])],{windowsHide:true,stdio:['ignore','pipe','pipe']});
}
async function listWindows() {
  return new Promise((resolve,reject)=>{
    const child=externalWindows('List');let output='',error='';child.stdout.setEncoding('utf8');child.stderr.setEncoding('utf8');
    const timer=setTimeout(()=>{child.kill();reject(new Error('读取窗口列表超时。'));},15000);
    child.stdout.on('data',part=>output+=part);child.stderr.on('data',part=>error+=part);
    child.once('error',cause=>{clearTimeout(timer);reject(cause);});
    child.once('close',code=>{clearTimeout(timer);try{if(code)throw new Error(error||'读取窗口列表失败。');resolve(JSON.parse(output.replace(/^\uFEFF/,'')||'[]'));}catch(cause){reject(cause);}});
  });
}
class WindowTools {
  constructor({getReader,icon,notify=()=>{}}){this.getReader=getReader;this.icon=icon;this.notify=notify;this.windows=new Set();this.hidden=new Set();}
  stop(){this.helper?.kill();this.helper=null;for(const window of this.hidden)if(!window.isDestroyed())window.showInactive();this.hidden.clear();this.attached=false;}
  start(options) {
    if(options.handle&&!/^\d{1,16}$/.test(String(options.handle)))throw new Error('窗口句柄无效。');
    if(!options.handle&&!String(options.title||'').trim())throw new Error('请选择目标窗口或输入标题。');
    this.stop();
    this.attachment={...options,offsetX:Math.round(clamp(options.offsetX,-3000,3000,0)),offsetY:Math.round(clamp(options.offsetY,-3000,3000,0))};
    return new Promise((resolve,reject)=>{
      const child=externalWindows('Watch',options);this.helper=child;let buffer='',error='',ready=false;
      const timer=setTimeout(()=>{if(!ready){this.stop();reject(new Error('未找到目标窗口，请刷新列表后重试。'));}},15000);
      child.stderr.on('data',part=>error=(error+part).slice(-3000));
      child.stdout.on('data',part=>{buffer+=part.toString('utf8');let end;while((end=buffer.indexOf('\n'))>=0){const line=buffer.slice(0,end).trim();buffer=buffer.slice(end+1);try{const target=JSON.parse(line);if(target.found&&!ready){ready=true;clearTimeout(timer);this.attached=true;resolve(true);}if(ready)this.follow(target);}catch{}}});
      child.once('error',cause=>{clearTimeout(timer);if(!ready)reject(cause);else this.notify(cause.message);});
      child.once('close',code=>{clearTimeout(timer);if(this.helper===child){this.helper=null;this.stop();}if(!ready)reject(new Error(error||'窗口依附未启动。'));else if(code)this.notify('窗口依附已停止。'+error);});
    });
  }
  follow(target){
    const options=this.attachment;
    const windows=[...(options.attachReader?[this.getReader()]:[]),...(options.attachApps?[...this.windows].filter(window=>window.followAttachment):[])].filter(window=>window&&!window.isDestroyed());
    for(const window of windows){
      if(!target.found||target.minimized){if(window.isVisible()){this.hidden.add(window);window.hide();}continue;}
      if(this.hidden.has(window)){this.hidden.delete(window);window.showInactive();}
      // GetWindowRect reports physical pixels; Electron setBounds expects DIP.
      const physical={x:target.x,y:target.y,width:target.width,height:target.height};
      const display=screen.getDisplayMatching(physical);
      const scale=display.scaleFactor||1;
      const x=Math.round(physical.x/scale)+Math.round(options.offsetX/scale),y=Math.round(physical.y/scale)+Math.round(options.offsetY/scale);
      const bounds=window.getBounds();
      if(bounds.x!==x||bounds.y!==y)window.setBounds({x,y,width:bounds.width,height:bounds.height},false);
    }
  }
  add(window,options){window.followAttachment=options.attachApps!==false;this.windows.add(window);window.on('closed',()=>{this.windows.delete(window);this.hidden.delete(window);});return window;}
  disable(kind){if(kind==='attachment')this.stop();else for(const window of this.windows)if(window.readerTool===kind&&!window.isDestroyed())window.close();}
  open(kind,options={}) {
    if(!['web','sudoku'].includes(kind))throw new Error('未知内置应用。');
    const web=kind==='web';let url;
    if(web){try{url=new URL(String(options.url||'https://www.baidu.com'));}catch{throw new Error('请输入完整的 HTTP / HTTPS 地址。');}if(!['http:','https:'].includes(url.protocol))throw new Error('只允许 HTTP / HTTPS 地址。');}
    const area=screen.getPrimaryDisplay().workArea;
    const width=Math.round(clamp(web?options.width:options.sudokuWidth,web?320:520,web?2400:1600,web?900:760)),height=Math.round(clamp(web?options.height:options.sudokuHeight,web?220:420,web?1800:1200,620));
    const window=this.add(new BrowserWindow({width:Math.min(width,area.width),height:Math.min(height,area.height),minWidth:web?320:520,minHeight:web?220:420,title:web?String(options.title||'透明网页').slice(0,120):'工作簿1 - Excel',frame:web,icon:this.icon,show:false,transparent:web&&options.transparent!==false,backgroundColor:web&&options.transparent!==false?'#00000000':'#ffffff',alwaysOnTop:options.top!==false,webPreferences:{contextIsolation:true,nodeIntegration:false,sandbox:true,webSecurity:true,partition:web?'reader-transparent-web':undefined}}),options);
    window.readerTool=kind;
    window.setMenuBarVisibility(false);window.webContents.setWindowOpenHandler(()=>({action:'deny'}));
    window.webContents.on('will-attach-webview',event=>event.preventDefault());
    window.webContents.session.setPermissionRequestHandler((_contents,_permission,callback)=>callback(false));
    window.webContents.on('will-navigate',(event,target)=>{if(!web||!/^https?:\/\//i.test(target))event.preventDefault();});
    window.once('ready-to-show',()=>window.show());
    if(web){
      window.setOpacity(clamp(options.opacity,20,100,92)/100);
      window.webContents.on('did-finish-load',()=>{window.webContents.setZoomFactor(clamp(options.zoom,60,220,100)/100);const styles=[options.gray?'html{filter:grayscale(1)!important}':'',options.hideImages?'img,picture,video,canvas{visibility:hidden!important}':'',options.transparent!==false?'html,body{background:transparent!important}':''].join('\n');if(styles)window.webContents.insertCSS(styles).catch(error=>this.notify(error.message));});
      window.loadURL(url.href).catch(error=>this.notify('网页打开失败：'+error.message));
    }else window.loadFile(path.join(__dirname,'ui','sudoku.html'),{query:{difficulty:['easy','medium','hard','expert'].includes(options.difficulty)?options.difficulty:'easy'}}).catch(error=>this.notify(error.message));
    return true;
  }
  close(){this.stop();for(const window of this.windows)if(!window.isDestroyed())window.destroy();this.windows.clear();}
}
module.exports={WindowTools,listWindows};
