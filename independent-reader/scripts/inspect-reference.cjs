'use strict';
// Observe the old executable in a synthetic profile; do not import its application source.
const fs = require('node:fs');
const path = require('node:path');
const { spawn } = require('node:child_process');
const { CDP, until, wait, freePort } = require('./cdp.cjs');
const root = path.resolve(__dirname, '..');
(async () => {
  const evidence = path.join(root, '.verification', 'legacy-reference');
  const profile = path.join(evidence, 'profiles', String(Date.now())); fs.mkdirSync(profile, { recursive: true });
  const file = path.join(profile, 'reference.txt'); fs.writeFileSync(file, '第一章\n旧版交互比对测试。'.repeat(200));
  const referenceBook={ id:'reference',title:'旧版界面对齐测试',path:file,type:'txt',page:1,size:fs.statSync(file).size,readPosition:0,bookmarks:[] };
  fs.writeFileSync(path.join(profile, 'single-reader-state.json'), JSON.stringify({ 'single-reader-state': { Book: { books: [referenceBook], book:referenceBook, readHistory: [] } } }));
  fs.writeFileSync(path.join(profile, 'single-reader-settings.json'), JSON.stringify({ reader_mouseControlEnabled: 'false', reader_bossKeyEnabled: 'false', reader_themeKey: 'classic' }));
  const port = await freePort();
  const executable = path.resolve(root, '../build/win-unpacked/圣子猫阅读器.exe');
  const env = { ...process.env }; delete env.ELECTRON_RUN_AS_NODE;
  const child = spawn(executable, [`--user-data-dir=${profile}`, `--remote-debugging-port=${port}`], { cwd: path.dirname(executable), env, windowsHide: true, stdio: ['ignore','pipe','pipe'] });
  let logs = ''; child.stdout.on('data', chunk => { logs += chunk; }); child.stderr.on('data', chunk => { logs += chunk; });
  const clients = [];
  const target = async menu => until(async () => (await (await fetch(`http://127.0.0.1:${port}/json/list`)).json()).find(item => item.type === 'page' && item.url.includes('index.html') && item.url.includes('#/menu') === menu), 40000);
  try {
    const reader = await CDP.connect((await target(false)).webSocketDebuggerUrl); clients.push(reader);
    await until(() => reader.eval('!!document.querySelector("#page")?.__vue__?._isMounted'));
    const actual = await reader.eval('require("@electron/remote").app.getPath("userData")');
    if (path.resolve(actual) !== path.resolve(profile)) throw new Error('旧版隔离目录不符，停止观察。');
    const readerState=await reader.eval(`(()=>{const window=require('@electron/remote').BrowserWindow.getFocusedWindow()||require('@electron/remote').BrowserWindow.getAllWindows().find(window=>!window.isDestroyed());return {text:document.body.innerText,markup:document.querySelector('#page').outerHTML.slice(0,10000),bounds:window?.getBounds(),visible:window?.isVisible(),title:window?.getTitle(),elements:Array.from(document.querySelectorAll('#page,#page *')).slice(0,45).map(element=>{const style=getComputedStyle(element),rect=element.getBoundingClientRect();return {tag:element.tagName,id:element.id,className:element.className,text:element.textContent.slice(0,100),rect:{x:rect.x,y:rect.y,width:rect.width,height:rect.height},fontSize:style.fontSize,fontFamily:style.fontFamily,lineHeight:style.lineHeight,color:style.color,background:style.backgroundColor,opacity:style.opacity,whiteSpace:style.whiteSpace,overflow:style.overflow}})}})()`);
    fs.writeFileSync(path.join(evidence,'reader-observed.json'),JSON.stringify(readerState,null,2));await reader.screenshot(path.join(evidence,'reader.png'));
    const context = await reader.eval('document.querySelector("#page").__vue__.menu.items.map(item=>({label:item.label,type:item.type,checked:item.checked,submenu:item.submenu?.items.map(child=>({label:child.label,type:child.type}))}))');
    fs.writeFileSync(path.join(evidence,'reader-context.json'),JSON.stringify(context,null,2));
    await reader.eval('require("electron").ipcRenderer.send("openMenuWindow")');
    const menu = await CDP.connect((await target(true)).webSocketDebuggerUrl); clients.push(menu);
    await until(() => menu.eval('!!document.querySelector("#menu")?.__vue__?._isMounted'));
    const scenes = [];
    for (const [name,index,labels] of [['settings',2,['外观','阅读','隐蔽','窗口']],['apps',4,['透明网页','窗口依附','Excel 数独','插件中心']]]) {
      await menu.eval(`document.querySelector('#menu').__vue__.checkMenu(${index})`); await wait(150);
      for (const label of labels) {
        await menu.eval(`Array.from(document.querySelectorAll('button')).find(button=>button.textContent.trim()===${JSON.stringify(label)})?.click()`); await wait(120);
        const scene = await menu.eval(`({text:document.body.innerText,fields:Array.from(document.querySelectorAll('input,select')).map(input=>({type:input.type,value:input.type==='checkbox'?input.checked:input.value,min:input.min,max:input.max,options:input.tagName==='SELECT'?Array.from(input.options).map(option=>({text:option.text,value:option.value})):undefined})),background:getComputedStyle(document.body).backgroundColor})`);
        scenes.push({name,label,...scene}); await menu.screenshot(path.join(evidence,`${name}-${labels.indexOf(label)}.png`));
      }
    }
    fs.writeFileSync(path.join(evidence,'observed-ui.json'),JSON.stringify(scenes,null,2));
    const primary=[];
    for(const [name,index,labels]of [['library',0,['继续阅读','全部书库','整理管理']],['import',1,[]],['search',3,[]]]){
      await menu.eval(`document.querySelector('#menu').__vue__.checkMenu(${index})`);await wait(200);
      for(const label of labels.length?labels:['']){
        if(label){await menu.eval(`Array.from(document.querySelectorAll('button')).find(button=>button.textContent.trim().startsWith(${JSON.stringify(label)}))?.click()`);await wait(150);}
        primary.push({name,label,...await menu.eval(`({text:document.body.innerText,fields:Array.from(document.querySelectorAll('input,select')).map(input=>({id:input.id,type:input.type,value:input.type==='checkbox'?input.checked:input.value,min:input.min,max:input.max,options:input.tagName==='SELECT'?Array.from(input.options).map(option=>({text:option.text,value:option.value})):undefined})),buttons:Array.from(document.querySelectorAll('button')).map(button=>button.textContent.trim())})`)});
        await menu.screenshot(path.join(evidence,name+'-'+(label||'page')+'.png'));
      }
    }
    fs.writeFileSync(path.join(evidence,'primary-observed.json'),JSON.stringify(primary,null,2));
    await menu.eval("document.querySelector('#menu').__vue__.checkMenu(0)");await wait(150);
    await menu.eval("Array.from(document.querySelectorAll('button')).find(button=>button.textContent.trim().startsWith('全部书库'))?.click()");await wait(100);
    await menu.eval("Array.from(document.querySelectorAll('button')).find(button=>button.textContent.trim()==='⋯')?.click()");await wait(100);
    fs.writeFileSync(path.join(evidence,'book-menu-observed.json'),JSON.stringify(await menu.eval("({text:document.body.innerText,buttons:Array.from(document.querySelectorAll('button')).map(b=>({text:b.textContent.trim(),title:b.title}))})"),null,2));
    await menu.screenshot(path.join(evidence,'book-menu.png'));
    await menu.eval("Array.from(document.querySelectorAll('button')).find(button=>button.textContent.trim()==='编辑信息')?.click()");await wait(150);
    fs.writeFileSync(path.join(evidence,'edit-observed.json'),JSON.stringify(await menu.eval("({text:document.body.innerText,fields:Array.from(document.querySelectorAll('input,select,textarea')).filter(n=>n.offsetParent).map(n=>({tag:n.tagName,type:n.type,value:n.type==='checkbox'?n.checked:n.value,placeholder:n.placeholder,options:n.tagName==='SELECT'?Array.from(n.options).map(o=>({value:o.value,text:o.text})):undefined})),buttons:Array.from(document.querySelectorAll('button')).filter(n=>n.offsetParent).map(n=>n.textContent.trim())})"),null,2));await menu.screenshot(path.join(evidence,'edit-info.png'));
    await menu.eval("Array.from(document.querySelectorAll('button')).find(button=>button.textContent.trim()==='取消')?.click()");await wait(100);
    await menu.eval("document.querySelector('#menu').__vue__.checkMenu(4)");await wait(100);
    await menu.eval("Array.from(document.querySelectorAll('button')).find(button=>button.textContent.trim()==='Excel 数独')?.click()");await wait(100);
    await menu.eval("Array.from(document.querySelectorAll('button')).find(button=>button.textContent.trim()==='打开 Excel 数独')?.click()");
    const extra=await until(async()=>{const pages=await(await fetch(`http://127.0.0.1:${port}/json/list`)).json();return pages.find(t=>t.type==='page'&&!t.url.includes('index.html'));},15000).catch(()=>null);
    if(extra){const sudoku=await CDP.connect(extra.webSocketDebuggerUrl);clients.push(sudoku);await wait(700);fs.writeFileSync(path.join(evidence,'sudoku-observed.json'),JSON.stringify(await sudoku.eval("({text:document.body.innerText,fields:Array.from(document.querySelectorAll('input,select')).map(n=>({tag:n.tagName,type:n.type,value:n.value,readOnly:n.readOnly,placeholder:n.placeholder})),buttons:Array.from(document.querySelectorAll('button')).map(n=>n.textContent),layout:Array.from(document.querySelectorAll('header,main,footer,table')).map(n=>({tag:n.tagName,rect:n.getBoundingClientRect().toJSON(),style:{background:getComputedStyle(n).backgroundColor,font:getComputedStyle(n).font}}))})"),null,2));await sudoku.screenshot(path.join(evidence,'sudoku-window.png'));}
    // Match reading geometry through visible settings controls, not implementation code.
    await menu.eval("document.querySelector('#menu').__vue__.checkMenu(2)");
    await menu.eval("Array.from(document.querySelectorAll('button')).find(button=>button.textContent.trim()==='隐蔽')?.click()");await wait(100);
    await menu.eval("(()=>{const boxes=document.querySelectorAll('input[type=checkbox]');if(boxes[3]?.checked)boxes[3].click();})()");
    const geometry=[];
    async function capture(label){await wait(1200);const value=await reader.eval(`(()=>{const page=document.querySelector('#page'),preview=page.querySelector('.preview'),style=getComputedStyle(preview),p=getComputedStyle(page);const window=require('@electron/remote').BrowserWindow.getAllWindows().find(window=>window.webContents.id===require('@electron/remote').getCurrentWebContents().id);return {text:preview.textContent,bounds:window.getBounds(),preview:{lineHeight:style.lineHeight,fontSize:style.fontSize,height:style.height,padding:style.padding,textIndent:style.textIndent,whiteSpace:style.whiteSpace,wordBreak:style.wordBreak,overflowWrap:style.overflowWrap,boxShadow:style.boxShadow},page:{padding:p.padding,display:p.display,alignItems:p.alignItems,justifyContent:p.justifyContent},state:Object.fromEntries(Object.entries(page.__vue__.$data).filter(([key,value])=>/line|page|auto|read|font|show|scroll|mouse/i.test(key)&&['number','boolean','string'].includes(typeof value)).map(([key,value])=>[key,typeof value==='string'?value.slice(0,120):value]))}})()`);geometry.push({label,...value});await reader.screenshot(path.join(evidence,'reader-'+label+'.png'));}
    await capture('single-13');
    await menu.eval("Array.from(document.querySelectorAll('button')).find(button=>button.textContent.trim()==='外观')?.click()");await wait(100);
    await menu.eval("(()=>{const input=document.querySelectorAll('input[type=range]')[1];input.value='22';input.dispatchEvent(new Event('input',{bubbles:true}));input.dispatchEvent(new Event('change',{bubbles:true}));})()");await capture('single-22');
    await menu.eval("Array.from(document.querySelectorAll('button')).find(button=>button.textContent.trim()==='阅读')?.click()");await wait(100);
    await menu.eval("(()=>{const checkbox=document.querySelector('input[type=checkbox]');if(!checkbox.checked)checkbox.click();const count=document.querySelectorAll('input[type=number]')[1];count.value='3';count.dispatchEvent(new Event('input',{bubbles:true}));count.dispatchEvent(new Event('change',{bubbles:true}));})()");await capture('multi-22');
    fs.writeFileSync(path.join(evidence,'reader-geometry.json'),JSON.stringify(geometry,null,2));
    console.log(JSON.stringify({evidence,scenes:scenes.map(scene=>({label:scene.label,fields:scene.fields.length})),reader:geometry.map(scene=>({label:scene.label,bounds:scene.bounds,fontSize:scene.preview.fontSize,multiLine:scene.state.multiLineEnabled}))},null,2));
    await reader.eval('require("electron").ipcRenderer.send("quit-app")').catch(()=>{});
    await until(()=>child.exitCode!==null,7000).catch(()=>{});
  } finally { for (const client of clients) client.close(); if(child.exitCode===null)child.kill(); fs.writeFileSync(path.join(evidence,'runtime.log'),logs); }
})().catch(error=>{console.error(error);process.exitCode=1;});
