'use strict';
const {app,BrowserWindow,screen}=require('electron');
const fs=require('node:fs'),path=require('node:path');
const root=path.resolve(__dirname,'../.verification','geometry-probe');fs.mkdirSync(root,{recursive:true});app.setPath('userData',path.join(root,'profile'));
app.on('window-all-closed',()=>{});
app.whenReady().then(async()=>{
  const observations=[],area=screen.getPrimaryDisplay().workArea;let window;
  try{for(const thickFrame of [true,false]){
    window=new BrowserWindow({x:area.x,y:area.y+area.height-26,width:420,height:26,minWidth:240,minHeight:20,frame:false,transparent:true,thickFrame,show:true,backgroundColor:'#00000000'});
    await window.loadURL('data:text/html,<p>geometry</p>');
    const capture=async label=>{await new Promise(resolve=>setTimeout(resolve,250));observations.push({label,thickFrame,area,native:window.getBounds(),content:window.getContentBounds(),inner:await window.webContents.executeJavaScript('({width:innerWidth,height:innerHeight})')});};
    await capture('initial');window.setBounds({x:area.x,y:area.y+area.height-104,width:420,height:104});await capture('bottom104');window.setBounds({x:140,y:100,width:420,height:104});await capture('top104');for(const margin of [1,2,4]){window.setBounds({x:area.x,y:area.y+area.height-104-margin,width:420,height:104});await capture('bottom-margin-'+margin);}window.destroy();
  }}catch(error){observations.push({error:error.stack});}finally{fs.writeFileSync(path.join(root,'report.json'),JSON.stringify(observations,null,2));if(window&&!window.isDestroyed())window.destroy();app.quit();}
});
