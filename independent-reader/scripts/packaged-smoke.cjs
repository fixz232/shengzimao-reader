'use strict';
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),assert=require('node:assert/strict');
const {spawn}=require('node:child_process');
const {CDP,until,freePort}=require('./cdp.cjs');
(async()=>{
  const root=path.resolve(__dirname,'..'),executable=path.join(root,'release','win-unpacked','圣子猫阅读器·独立版.exe');
  if(!fs.existsSync(executable))throw new Error('先运行 npm run build:dir');
  const evidence=path.join(root,'.verification','packaged-'+Date.now());fs.mkdirSync(evidence,{recursive:true});const profile=path.join(evidence,'profile'),legacy=path.join(evidence,'legacy');fs.mkdirSync(legacy);
  const file=path.join(evidence,'打包回归小说.txt');fs.writeFileSync(file,'第一章 打包后导入\n'+'验证打包版本可以稳定阅读。'.repeat(300)+'\n第二章 保持书库\n打包回归正文。');
  const {simplePdf}=require('../test/document-fixture.cjs'),JSZip=require('jszip');
  const documents=['sample.md','sample.html','sample.docx','sample.pdf'].map(name=>path.join(evidence,name));
  fs.writeFileSync(documents[0],'# Markdown document\nPackaged Markdown text.');fs.writeFileSync(documents[1],'<h1>HTML document</h1><p>Packaged HTML text.</p>');
  const docx=new JSZip();docx.file('word/document.xml','<w:document xmlns:w="word"><w:body><w:p><w:r><w:t>Packaged DOCX text.</w:t></w:r></w:p></w:body></w:document>');fs.writeFileSync(documents[2],await docx.generateAsync({type:'nodebuffer'}));fs.writeFileSync(documents[3],simplePdf('Packaged PDF text.'));
  const port=await freePort(),env={...process.env};delete env.ELECTRON_RUN_AS_NODE;delete env.NODE_OPTIONS;
  let child,manager,reader,sudoku,logs='';const checks=[];let position,id;
  const check=(label,value)=>{assert.ok(value,label);checks.push(label);console.log('PASS '+label);};
  const launch=()=>{child=spawn(executable,[`--profile=${profile}`,`--legacy-profile=${legacy}`,`--remote-debugging-port=${port}`],{cwd:path.dirname(executable),env,windowsHide:true,stdio:['ignore','pipe','pipe']});child.stdout.on('data',chunk=>logs+=chunk);child.stderr.on('data',chunk=>logs+=chunk);};
  const connect=async match=>{const target=await until(async()=>(await(await fetch(`http://127.0.0.1:${port}/json/list`)).json()).find(item=>item.type==='page'&&item.url.includes(match)),40000);return CDP.connect(target.webSocketDebuggerUrl);};
  try{
    launch();reader=await connect('mode=reader');await until(()=>reader.eval('document.querySelector("#reader-copy")?.textContent==="欢迎使用圣子猫阅读器"'));check('安装版首次打开显示欢迎阅读框',true);await reader.request('show-manager');manager=await connect('mode=manager');await until(()=>manager.eval('!!document.querySelector(".nav")'));check('打包主界面可加载',true);check('安装目录版本与当前源代码版本一致',(await manager.request('snapshot')).version===require('../package.json').version);await manager.click('[data-route="about"]');await until(()=>manager.eval('!!document.querySelector(".update-panel [data-action=update-check]")'));check('打包关于页显示源码入口与桌面版更新操作',await manager.eval('!!document.querySelector(".update-panel")&&!!document.querySelector("[data-kind=repository]")&&!!document.querySelector("[data-kind=releases]")'));await manager.click('[data-route="library"]');
    check('打包版本使用隔离配置目录',(await manager.request('snapshot')).profile===profile);await manager.request('settings',{bossKeyEnabled:false,mouseControl:false,hideOnBlur:false});
    const result=await manager.request('import-dropped',{files:[file]});check('打包后的 worker 可以导入 TXT',result.imported===1&&result.errors.length===0);id=(await manager.request('snapshot')).books[0].id;
    await manager.request('open',{id});reader=await connect('mode=reader');await until(()=>reader.eval('document.querySelector("#reader-copy")?.textContent.includes("打包后导入")'));check('打包后的阅读条有实际正文',true);check('打包后的单行阅读框为420×26',await reader.eval('innerWidth===420&&innerHeight===26'));
    await manager.request('settings',{fontSize:22,multiLine:true,lineCount:3});await until(()=>reader.eval('innerHeight===104&&getComputedStyle(document.querySelector("#reader-copy")).fontSize==="22px"'));check('打包后的多行窗口及字号同步正常',true);
    const found=await manager.request('local-search',{ids:[id],keyword:'稳定阅读'});check('打包后的全文检索 worker 可用',found.length===100);
    await manager.request('move',{position:128});await manager.request('bookmark');position=(await manager.request('snapshot')).books[0].position;
    const probe=await manager.request('engine-probe');check('打包的最小 Java 与适配器可运行',Number(probe.sources)>0&&Boolean(probe.version));
    check('打包的 PowerShell 窗口列表脚本可运行',Array.isArray(await manager.request('windows-list')));
    await manager.request('app-open',{kind:'sudoku',options:{difficulty:'easy'}});sudoku=await connect('sudoku.html');await until(()=>sudoku.eval('document.querySelectorAll("#sheet input").length===81'));check('打包后的数独资源可用',true);
    const importedDocs=await manager.request('import-dropped',{files:documents});check('打包的 PDF、DOCX、Markdown、HTML worker 均可入库',importedDocs.imported===4&&importedDocs.errors.length===0);
    await manager.click('[data-route="search"]');await until(()=>manager.eval('!!document.querySelector("#consent-scroll")'));
    await assert.rejects(manager.request('search',{keyword:'未确认'}),/声明/);check('打包版本也强制首次在线声明',true);
    await manager.eval('const el=document.querySelector("#consent-scroll");el.scrollTop=el.scrollHeight;el.dispatchEvent(new Event("scroll"))');await manager.click('#consent-checkbox');await manager.click('#consent-accept');await until(()=>manager.eval('!!document.querySelector("#keyword")'));
    check('打包版本的确认记录可保存',(await manager.request('snapshot')).consent.accepted);await manager.click('[data-route="library"]');
    if(process.env.SZM_SKIP_SCREENSHOTS!=='1')await manager.screenshot(path.join(evidence,'manager.png'));if(process.env.SZM_SKIP_SCREENSHOTS!=='1')await reader.screenshot(path.join(evidence,'reader.png'));check('打包渲染进程无未捕获异常',[manager,reader,sudoku].every(client=>client.errors.length===0));
    await manager.request('quit').catch(()=>{});manager.close();reader.close();sudoku.close();await until(()=>child.exitCode!==null);launch();reader=await connect('mode=reader');await until(()=>reader.eval('!!document.querySelector("#reader-copy")'));check('打包程序启动自动恢复阅读框',true);await reader.request('show-manager');manager=await connect('mode=manager');await until(()=>manager.eval('document.querySelectorAll(".book").length===5'));check('重启后声明确认不会重复弹出',(await manager.request('snapshot')).consent.accepted);const book=(await manager.request('snapshot')).books[0];check('打包程序重启保留书库、进度和书签',book.position===position&&book.bookmarks.length===1);await manager.request('quit').catch(()=>{});await until(()=>child.exitCode!==null);
    fs.writeFileSync(path.join(evidence,'report.json'),JSON.stringify({passed:checks.length,checks,executable,sha256:crypto.createHash('sha256').update(fs.readFileSync(executable)).digest('hex'),environment:{platform:process.platform,node:process.version}},null,2));console.log(JSON.stringify({passed:checks.length,evidence}));
  }finally{manager?.close();reader?.close();sudoku?.close();if(child?.exitCode===null)child.kill();fs.writeFileSync(path.join(evidence,'runtime.log'),logs);}
})().catch(error=>{console.error(error);process.exitCode=1;});
