'use strict';
// Probe: replicate online-ui-smoke through line 34 and dump online state.
const fs=require('node:fs'),path=require('node:path'),http=require('node:http');
const {spawn}=require('node:child_process');
const {CDP,until,wait,freePort}=require('./cdp.cjs');
const root=path.resolve(__dirname,'..');
(async()=>{
  const evidence=path.join(root,'.verification','probe-'+Date.now());fs.mkdirSync(evidence,{recursive:true});
  const profile=path.join(evidence,'profile'),legacy=path.join(evidence,'legacy'),rulesDir=path.join(evidence,'rules');fs.mkdirSync(legacy);fs.mkdirSync(rulesDir);
  let child,manager,logs='',base;const hits=[];
  const server=http.createServer((request,response)=>{
    hits.push(request.url);response.setHeader('Content-Type','text/html; charset=utf-8');
    if(request.url.includes('/search'))return setTimeout(()=>response.end('<html><meta charset="utf-8"><div class="result"><a class="name" href="/book/1">合成测试小说</a><span class="author">测试作者</span></div></html>'),2500);
    if(request.url==='/book/1')return response.end(`<html><meta charset="utf-8"><h1>合成测试小说</h1><span class="author">测试作者</span><p class="intro">完整在线回归样本</p><div id="toc"><a href="/chapter/1">第一章 开始</a></div></html>`);
    if(request.url.startsWith('/chapter/'))return response.end(`<html><meta charset="utf-8"><h1>第一章 开始</h1><div id="content"><p>正文。</p></div></html>`);
    response.statusCode=404;response.end('not found');
  });
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));base=`http://127.0.0.1:${server.address().port}`;
  const rule={url:base+'/',name:'合成站点一',language:'zh-CN',needProxy:true,search:{url:base+'/search?q=%s',method:'get',result:'.result',bookName:'.name@js:r=r.trim()',author:'.author'},book:{url:'/book/(\\d+)',bookName:'h1',author:'.author',intro:'.intro'},toc:{item:'#toc a'},chapter:{title:'h1',content:'#content',paragraphTagClosed:true,filterTxt:'(?!)',filterTag:'script'},crawl:{minInterval:0,maxInterval:0,concurrency:2}};
  fs.writeFileSync(path.join(rulesDir,'main.json'),JSON.stringify([rule]));
  const fixtures=path.join(evidence,'fixtures.json');fs.writeFileSync(fixtures,JSON.stringify({importFiles:[],rulesDir}));
  const port=await freePort(),env={...process.env};delete env.ELECTRON_RUN_AS_NODE;delete env.NODE_OPTIONS;
  child=spawn(require('electron'),[path.join(__dirname,'test-host.cjs'),`--profile=${profile}`,`--legacy-profile=${legacy}`,`--test-fixtures=${fixtures}`,`--remote-debugging-port=${port}`],{cwd:root,env,windowsHide:true,stdio:['ignore','pipe','pipe']});
  child.stdout.on('data',c=>logs+=c);child.stderr.on('data',c=>logs+=c);
  const target=await until(async()=>(await(await fetch(`http://127.0.0.1:${port}/json/list`)).json()).find(item=>item.type==='page'&&item.url.includes('mode=manager')),30000);
  manager=await CDP.connect(target.webSocketDebuggerUrl);
  await until(()=>manager.eval('!!document.querySelector(".nav")'));
  await manager.request('settings',{mouseControl:false,hideOnBlur:false,bossKeyEnabled:false});
  await manager.eval('document.querySelector(\'[data-route="search"]\').click()');await wait(400);
  await manager.eval('document.querySelector("#keyword").value="合成测试小说";document.querySelector("#search-form").requestSubmit()');
  for(let i=0;i<50;i++){
    const snap=await manager.request('snapshot');
    console.log(i,snap.online.running,'results='+snap.online.results.length,'msg='+snap.online.message,'err='+snap.online.error,'hits='+hits.length);
    if(!snap.online.running)break;
    await wait(1000);
  }
  const snap=await manager.request('snapshot');
  console.log('FINAL',JSON.stringify({running:snap.online.running,results:snap.online.results.length,error:snap.online.error,message:snap.online.message,hits}));
  await manager.request('quit').catch(()=>{});
  await wait(1500);child.kill();
  server.close();process.exit(0);
})().catch(e=>{console.error(e);process.exit(1);});
