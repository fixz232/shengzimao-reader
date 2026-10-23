'use strict';
// A test-owned external window. Never controls any existing user application.
const {app,BrowserWindow}=require('electron');
const fs=require('node:fs'),path=require('node:path');
const root=process.argv.find(arg=>arg.startsWith('--fixture-dir='))?.slice(14);
if(!root||!path.isAbsolute(root)||!root.includes('.verification'))throw new Error('Fixture directory required');
app.setPath('userData',path.join(root,'fixture-profile'));
app.whenReady().then(()=>{
  const window=new BrowserWindow({x:180,y:160,width:400,height:260,frame:false,show:false,title:'阅读器依附测试窗口',webPreferences:{sandbox:true,contextIsolation:true,nodeIntegration:false}});
  window.loadURL('data:text/html;charset=utf-8,<title>阅读器依附测试窗口</title><p>独立测试窗口；不操作用户软件。</p>');
  let ready=false;const save=()=>{if(!ready)return;const handle=window.getNativeWindowHandle();fs.writeFileSync(path.join(root,'fixture-state.json'),JSON.stringify({ready,pid:process.pid,visible:window.isVisible(),title:window.getTitle(),handle:String(handle.length===8?handle.readBigUInt64LE():handle.readUInt32LE()),...window.getBounds(),minimized:window.isMinimized()}));};
  window.once('ready-to-show',()=>{ready=true;window.show();window.setTitle('阅读器依附测试窗口');save();});for(const event of ['move','minimize','restore'])window.on(event,save);
  let last=0;const timer=setInterval(()=>{const file=path.join(root,'fixture-command.json');if(!fs.existsSync(file))return;let command;try{command=JSON.parse(fs.readFileSync(file,'utf8'));}catch{return;}if(command.id===last)return;last=command.id;if(command.action==='move')window.setPosition(command.x,command.y);if(command.action==='minimize')window.minimize();if(command.action==='restore'){window.restore();window.show();}if(command.action==='quit'){clearInterval(timer);app.quit();}else save();},80);
});
app.on('window-all-closed',()=>app.quit());
