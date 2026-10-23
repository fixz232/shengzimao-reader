'use strict';
// Observe rendered output and input behavior only. The reference app uses a disposable profile.
const fs=require('node:fs'),path=require('node:path'),{spawn}=require('node:child_process');
const {CDP,until,wait,freePort}=require('./cdp.cjs');
const root=path.resolve(__dirname,'..');
(async()=>{
  const evidence=path.join(root,'.verification','reader-reference-'+Date.now()),profile=path.join(evidence,'profile');
  fs.mkdirSync(profile,{recursive:true});
  const text=('第一章 阅读框测试\n中文标点，ABC 123：空 格。\n第二段，阅读框外观与翻页对照。').repeat(100),file=path.join(profile,'reference.txt');
  fs.writeFileSync(file,text);
  const book={id:'reference',title:'阅读框对照',path:file,type:'txt',page:1,size:Buffer.byteLength(text),readPosition:0,bookmarks:[]};
  fs.writeFileSync(path.join(profile,'single-reader-state.json'),JSON.stringify({'single-reader-state':{Book:{books:[book],book,readHistory:[]}}}));
  fs.writeFileSync(path.join(profile,'single-reader-settings.json'),JSON.stringify({reader_bossKeyEnabled:'false',reader_themeKey:'classic'}));
  const port=await freePort(),exe=path.resolve(root,'../build/win-unpacked/圣子猫阅读器.exe'),env={...process.env};delete env.ELECTRON_RUN_AS_NODE;delete env.NODE_OPTIONS;
  const child=spawn(exe,[`--user-data-dir=${profile}`,`--remote-debugging-port=${port}`],{cwd:path.dirname(exe),env,windowsHide:true,stdio:['ignore','pipe','pipe']});
  let log='';child.stdout.on('data',v=>log+=v);child.stderr.on('data',v=>log+=v);
  const clients=[],scenes=[];
  const target=async menu=>until(async()=>(await(await fetch(`http://127.0.0.1:${port}/json/list`)).json()).find(t=>t.type==='page'&&t.url.includes('index.html')&&t.url.includes('#/menu')===menu),40000);
  let reader,menu;
  try{
    reader=await CDP.connect((await target(false)).webSocketDebuggerUrl);clients.push(reader);
    await until(()=>reader.eval('!!document.querySelector("#page")?.__vue__?._isMounted'));
    const actual=await reader.eval('require("@electron/remote").app.getPath("userData")');
    if(path.resolve(actual)!==profile)throw new Error('Reference profile isolation failed');
    const capture=async label=>{
      await wait(220);
      const scene=await reader.eval(`(()=>{const remote=require('@electron/remote'),win=remote.getCurrentWindow(),page=document.querySelector('#page'),preview=page.querySelector('.preview');const css=node=>{const s=getComputedStyle(node);return Object.fromEntries(['font','fontFamily','fontSize','fontWeight','lineHeight','color','backgroundColor','opacity','padding','margin','height','maxHeight','width','border','borderRadius','boxShadow','textShadow','webkitTextStroke','textAlign','whiteSpace','wordBreak','overflowWrap','overflow','cursor','userSelect','display','alignItems','boxSizing'].map(k=>[k,s[k]]))};return {text:preview.textContent,viewport:{width:innerWidth,height:innerHeight,dpr:devicePixelRatio},native:{bounds:win.getBounds(),content:win.getContentBounds(),visible:win.isVisible(),focused:win.isFocused(),opacity:win.getOpacity(),minimum:win.getMinimumSize()},elements:[page,...page.children].map(node=>({className:node.className,rect:node.getBoundingClientRect().toJSON(),css:css(node),after:node.className==='resize-zone'?{css: getComputedStyle(node,'::after').cssText,width:getComputedStyle(node,'::after').width,height:getComputedStyle(node,'::after').height,right:getComputedStyle(node,'::after').right,bottom:getComputedStyle(node,'::after').bottom,borderRight:getComputedStyle(node,'::after').borderRight,borderBottom:getComputedStyle(node,'::after').borderBottom,opacity:getComputedStyle(node,'::after').opacity}:undefined})),state:Object.fromEntries(Object.entries(page.__vue__.$data).filter(([k,v])=>['string','number','boolean'].includes(typeof v)).map(([k,v])=>[k,typeof v==='string'?v.slice(0,240):v]))}})()`);
      scene.computed=await reader.eval("(()=>{const v=document.querySelector('#page').__vue__;return Object.fromEntries(Object.keys(v.$options.computed||{}).map(k=>[k,v[k]]).filter(([k,v])=>['string','number','boolean'].includes(typeof v)).map(([k,v])=>[k,typeof v==='string'?v.slice(0,300):v]));})()");
      scene.metrics=await reader.eval("(()=>{const c=document.createElement('canvas').getContext('2d');c.font=getComputedStyle(document.querySelector('.preview')).font;return Object.fromEntries(['国','汉','W','M','中','阅读框测试','ABCDEFGHIJKLMNOPQRSTUVWXYZ'].map(t=>[t,c.measureText(t).width]));})()");
      scenes.push({label,...scene});fs.writeFileSync(path.join(evidence,'report.json'),JSON.stringify({evidence,text,scenes},null,2));
      if(!process.argv.includes('--behavior-only'))await reader.screenshot(path.join(evidence,label+'.png'));
      console.log(label,JSON.stringify({text:scene.text,bounds:scene.native.bounds,computed:scene.computed}));
    };
    const event=async name=>reader.eval(`document.querySelector('#page').dispatchEvent(new MouseEvent(${JSON.stringify(name)},{bubbles:false}))`);
    await capture('initial');
    await reader.call('Input.dispatchMouseEvent',{type:'mouseMoved',x:60,y:10});await event('mouseenter');await capture('mouse-enter');
    await event('mouseleave');await capture('mouse-leave');
    await event('mouseenter');await capture('mouse-reenter');
    await reader.eval('require("electron").ipcRenderer.send("openMenuWindow")');
    menu=await CDP.connect((await target(true)).webSocketDebuggerUrl);clients.push(menu);await until(()=>menu.eval('!!document.querySelector("#menu")?.__vue__?._isMounted'));
    await capture('blur');
    await menu.eval("document.querySelector('#menu').__vue__.checkMenu(2)");
    const tab=async label=>{await menu.eval(`Array.from(document.querySelectorAll('button')).find(b=>b.textContent.trim()===${JSON.stringify(label)})?.click()`);await wait(150);};
    const input=async(selector,index,value)=>{await menu.eval(`(()=>{const input=document.querySelectorAll(${JSON.stringify(selector)})[${index}];${typeof value==='boolean'?`if(input.checked!==${value})input.click();`:`input.value=${JSON.stringify(String(value))};input.dispatchEvent(new Event('input',{bubbles:true}));input.dispatchEvent(new Event('change',{bubbles:true}));`}})()`);await wait(350);};
    const check=async(label,value)=>{await menu.eval(`(()=>{const input=Array.from(document.querySelectorAll('input[type=checkbox]')).find(n=>n.parentElement.textContent.includes(${JSON.stringify(label)}));if(!input)throw new Error('Checkbox label missing');if(input.checked!==${value})input.click();})()`);await wait(350);};
    await tab('隐蔽');
    fs.writeFileSync(path.join(evidence,'hiding-fields.json'),JSON.stringify(await menu.eval("Array.from(document.querySelectorAll('input,select')).map(n=>({type:n.type,value:n.type==='checkbox'?n.checked:n.value,label:n.parentElement.textContent,markup:n.parentElement.outerHTML.slice(0,1500)}))"),null,2));
    if(process.argv.includes('--fields-only'))return;
    if(process.argv.includes('--behavior-only')){
      fs.writeFileSync(path.join(evidence,'hiding-buttons.json'),JSON.stringify(await menu.eval("Array.from(document.querySelectorAll('button')).map(n=>({text:n.textContent,markup:n.outerHTML.slice(0,1000)}))"),null,2));
      await menu.eval("require('@electron/remote').getCurrentWindow().close()");await wait(350);
      await reader.eval("(()=>{const r=require('@electron/remote'),w=r.getCurrentWindow(),p=r.screen.getCursorScreenPoint();w.setBounds({x:Math.floor((p.x-80)/2)*2,y:Math.floor((p.y-10)/2)*2,width:420,height:26});w.show();w.focus();})()");await wait(500);await capture('pointer-inside');
      await reader.eval("(()=>{const r=require('@electron/remote'),w=r.getCurrentWindow(),p=r.screen.getCursorScreenPoint();w.setPosition(Math.floor((p.x+100)/2)*2,Math.floor((p.y+100)/2)*2);})()");await wait(500);await capture('pointer-outside');
      await reader.eval("require('@electron/remote').getCurrentWindow().blur()");await wait(400);await capture('native-blur');
      await reader.eval("require('@electron/remote').getCurrentWindow().focus()");await wait(400);await capture('native-focus');
      fs.writeFileSync(path.join(evidence,'report.json'),JSON.stringify({evidence,text,scenes},null,2));console.log(JSON.stringify({evidence}));return;
    }
    await check('窗口失去焦点',false);await check('当鼠标移出',false);await event('mouseenter');await capture('active-13');
    if(process.argv.includes('--metrics-only')){
      await reader.eval("window.__measured=[];const measure=CanvasRenderingContext2D.prototype.measureText;CanvasRenderingContext2D.prototype.measureText=function(text){const result=measure.call(this,text);window.__measured.push({text,font:this.font,width:result.width});return result}");
      await tab('阅读');await input('input[type=number]',0,200);await tab('窗口');await input('input[type=number]',0,600);
      for(const bold of [false,true])for(const font of [5,10,11,14,16,18,20,24,28,40]){
        await tab('外观');await input('input[type=checkbox]',0,bold);await input('input[type=range]',1,font);await capture(`metrics-${font}-${bold}`);
      }
      fs.writeFileSync(path.join(evidence,'canvas-calls.json'),JSON.stringify(await reader.eval('window.__measured'),null,2));console.log(JSON.stringify({evidence}));return;
    }
    if(process.argv.includes('--capacity-only')){
      await tab('阅读');await input('input[type=number]',0,200);
      for(const font of [13,22,30]){
        await tab('外观');await input('input[type=range]',1,font);
        for(const bold of [false,true]){
          await tab('外观');await input('input[type=checkbox]',0,bold);await tab('窗口');
          for(const width of [240,300,420,600,800]){await input('input[type=number]',0,width);await capture(`capacity-${font}-${bold}-${width}`);}
        }
      }
      fs.writeFileSync(path.join(evidence,'report.json'),JSON.stringify({evidence,text,scenes},null,2));console.log(JSON.stringify({evidence}));return;
    }
    await reader.eval('window.dispatchEvent(new Event("blur"))');await capture('synthetic-blur');
    await tab('外观');await input('input[type=range]',1,22);await capture('active-22');
    await input('input[type=checkbox]',0,true);await capture('bold-22');await input('input[type=checkbox]',0,false);
    await input('input[type=range]',2,40);await capture('opacity-40');await input('input[type=range]',2,100);
    await tab('阅读');await input('input[type=checkbox]',0,true);await capture('multi-22');
    await input('input[type=number]',0,10);await capture('multi-line10');
    await input('input[type=checkbox]',0,false);await capture('single-again');
    await input('input[type=number]',0,80);await capture('line80');
    await tab('窗口');await input('input[type=number]',0,600);await capture('width600');await input('input[type=number]',1,100);await capture('height100');
    await tab('阅读');await input('input[type=checkbox]',0,true);await capture('multi-height100');
    await tab('外观');await input('input[type=range]',1,13);await capture('multi-13');
    await tab('阅读');await input('input[type=checkbox]',0,false);await capture('single-13-height100');
    await tab('隐蔽');await input('input[type=checkbox]',1,true);await input('input[type=checkbox]',3,false);await event('mouseleave');await capture('disguise-fixed');
    await event('mouseenter');await capture('restore-from-disguise');
    fs.writeFileSync(path.join(evidence,'report.json'),JSON.stringify({evidence,text,scenes,errors:clients.flatMap(c=>c.errors)},null,2));
    console.log(JSON.stringify({evidence,scenes:scenes.length}));
    await reader.eval('require("electron").ipcRenderer.send("quit-app")').catch(()=>{});await until(()=>child.exitCode!==null,5000).catch(()=>{});
  }finally{for(const client of clients)client.close();if(child.exitCode===null)child.kill();fs.writeFileSync(path.join(evidence,'runtime.log'),log);}
})().catch(error=>{console.error(error);process.exitCode=1;});
