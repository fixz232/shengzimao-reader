'use strict';
// Real UI + real bundled Java engine, against a local synthetic site only.
const fs=require('node:fs'),path=require('node:path'),http=require('node:http'),assert=require('node:assert/strict');
const {spawn}=require('node:child_process');
const {CDP,until,wait,freePort}=require('./cdp.cjs');
const root=path.resolve(__dirname,'..');
(async()=>{
  const evidence=path.join(root,'.verification','online-ui-'+Date.now());fs.mkdirSync(evidence,{recursive:true});
  const profile=path.join(evidence,'profile'),legacy=path.join(evidence,'legacy'),rulesDir=path.join(evidence,'rules');fs.mkdirSync(legacy);fs.mkdirSync(rulesDir);
  new (require('../app/library.cjs').Library)(profile).save();
  let child,manager,reader,logs='',base,chapterCount=2;const checks=[],hits=[];
  const check=(label,value)=>{assert.ok(value,label);checks.push(label);console.log('PASS '+label);};
  const server=http.createServer((request,response)=>{
    hits.push(request.url);response.setHeader('Content-Type','text/html; charset=utf-8');
    if(request.url.includes('/search'))return setTimeout(()=>response.end('<html><meta charset="utf-8"><div class="result"><a class="name" href="/book/1">合成测试小说</a><span class="author">测试作者</span></div><div class="result"><a class="name" href="/book/999">完全无关目录</a><span class="author">其他作者</span></div><div class="result"><a class="name" href="/book/998">合成测试小说外传</a><span class="author">其他作者</span></div></html>'),2500);
    if(request.url==='/book/1')return response.end(`<html><meta charset="utf-8"><h1>合成测试小说</h1><span class="author">测试作者</span><p class="intro">完整在线回归样本</p><img class="cover" src="${base}/cover.png"><div id="toc"><a href="/chapter/1">第一章 开始</a><a href="/chapter/2">第二章 结束</a>${chapterCount===3?'<a href="/chapter/3">第三章 新增</a>':''}</div></html>`);
    if(request.url==='/cover.png'){response.setHeader('Content-Type','image/png');return response.end(Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a10UAAAAASUVORK5CYII=','base64'));}
    if(request.url.startsWith('/chapter/')){const third=request.url.endsWith('/3'),second=request.url.endsWith('/2');return setTimeout(()=>response.end(`<html><meta charset="utf-8"><h1>${third?'第三章 新增':second?'第二章 结束':'第一章 开始'}</h1><div id="content"><p>${third?'新增章节正文。':second?'结束章节的合成正文。':'开篇章节的合成正文。'}</p></div></html>`),500);}
    response.statusCode=404;response.end('not found');
  });
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));base=`http://127.0.0.1:${server.address().port}`;
  const rule={url:base+'/',name:'合成站点一',language:'zh-CN',needProxy:true,search:{url:base+'/search?q=%s',method:'get',result:'.result',bookName:'.name@js:r=r.trim()',author:'.author'},book:{url:'/book/(\\d+)',bookName:'h1',author:'.author',intro:'.intro',coverUrl:'.cover@src'},toc:{item:'#toc a'},chapter:{title:'h1',content:'#content',paragraphTagClosed:true,filterTxt:'(?!)',filterTag:'script'},crawl:{minInterval:0,maxInterval:0,concurrency:2}};
  fs.writeFileSync(path.join(rulesDir,'main.json'),JSON.stringify([rule,{...rule,name:'合成站点二',search:{...rule.search,url:base+'/second/search?q=%s'}}]));
  const fixtures=path.join(evidence,'fixtures.json');fs.writeFileSync(fixtures,JSON.stringify({importFiles:[],rulesDir,failFirstSearch:true}));
  const port=await freePort(),env={...process.env,SZM_ALLOW_PRIVATE_URLS:'1'};delete env.ELECTRON_RUN_AS_NODE;delete env.NODE_OPTIONS;
  const connect=async mode=>{const target=await until(async()=>(await(await fetch(`http://127.0.0.1:${port}/json/list`)).json()).find(item=>item.type==='page'&&item.url.includes('mode='+mode)),30000);return CDP.connect(target.webSocketDebuggerUrl);};
  const nav=async route=>{await manager.click(`[data-route="${route}"]`);await until(()=>manager.eval(`document.querySelector('[data-route="${route}"]').getAttribute('aria-current')==='page'`));};
  try{
    child=spawn(require('electron'),[path.join(__dirname,'test-host.cjs'),`--profile=${profile}`,`--legacy-profile=${legacy}`,`--test-fixtures=${fixtures}`,`--remote-debugging-port=${port}`],{cwd:root,env,windowsHide:true,stdio:['ignore','pipe','pipe']});child.stdout.on('data',chunk=>logs+=chunk);child.stderr.on('data',chunk=>logs+=chunk);
    manager=await connect('manager');await until(()=>manager.eval('!!document.querySelector(".nav")'));await manager.request('settings',{mouseControl:false,hideOnBlur:false,bossKeyEnabled:false});
    await manager.request('show-reader');reader=await connect('reader');await until(()=>reader.eval('!!document.querySelector("#reader-copy")'));
    await nav('search');check('首次打开在线页显示声明并锁定确认',await manager.eval('!!document.querySelector("#consent-scroll")&&document.querySelector("#consent-accept").disabled'));
await assert.rejects(manager.request('search',{keyword:'未确认'}),/声明/);check('主进程阻止未同意声明的联网请求',true);
await manager.eval('const node=document.querySelector("#consent-scroll");node.scrollTop=node.scrollHeight;node.dispatchEvent(new Event("scroll"));');
await manager.click('#consent-checkbox');await manager.click('#consent-accept');await until(()=>manager.eval('!!document.querySelector("#keyword")'));check('阅读到底并勾选后可进入搜书',true);await nav('search');await manager.eval('document.querySelector("#keyword").value="合成测试小说 测试作者";document.querySelector("#search-form").requestSubmit()');
    await until(()=>manager.eval('!!document.querySelector("[data-action=online-retry]")'));
    check('聚合搜索失败且无结果时显示重试入口',await manager.eval('document.querySelectorAll(".result").length===0&&document.querySelector(".error").textContent.includes("合成搜索失败")&&document.querySelector("#keyword").value==="合成测试小说 测试作者"'));
    await manager.click('[data-action="online-retry"]');await until(async()=>(await manager.request('snapshot')).online.running);check('点击重试实际重新启动原关键词搜索',true);
    await nav('settings');check('真实搜书运行时可以切换设置',(await manager.request('snapshot')).online.running);
    await manager.eval('window.close()');manager.close();manager=null;await until(async()=>!(await(await fetch(`http://127.0.0.1:${port}/json/list`)).json()).some(item=>item.url.includes('mode=manager')));await reader.request('show-manager');manager=await connect('manager');await until(()=>manager.eval('!!document.querySelector(".nav")'));check('关闭并重新打开菜单不取消搜书',(await manager.request('snapshot')).online.running);
    await until(async()=>{const online=(await manager.request('snapshot')).online;if(online.error)throw new Error(online.error);return !online.running&&online.results.length===2;},40000);
    check('真实适配器多关键词过滤无关书和作者不符的外传',(await manager.request('snapshot')).online.results.every(book=>book.bookName==='合成测试小说'&&book.author==='测试作者'));
    await nav('search');await until(()=>manager.eval('document.querySelectorAll(".result").length===1'));check('默认按相关度排序',await manager.eval('document.querySelector("#online-sort").value==="match"'));check('同名同作者的真实站点结果合并显示',await manager.eval('document.querySelector(".result").textContent.includes("2 个站点可选")'));
    await manager.click('.result-top [data-action="online-details"]');await until(()=>manager.eval('document.querySelector("#reader-dialog")?.dataset.type==="online-details"'),40000);
    check('真实详情页展示简介与远程两章目录',await manager.eval('document.querySelector(".dialog-content").textContent.includes("完整在线回归样本")&&document.querySelectorAll(".chapter-entry").length===2'));
    await manager.eval('const q=document.querySelector("#chapter-query");q.value="结束";q.dispatchEvent(new Event("input",{bubbles:true}))');
    check('在线目录搜索筛选实际章节',await manager.eval('document.querySelectorAll(".chapter-entry").length===1&&document.querySelector(".chapter-entry").textContent.includes("结束")'));
    await manager.click('[data-action="close-dialog"]');
    check('两个站点可分别选择入库',await manager.eval('document.querySelectorAll(".site-row").length===2&&document.querySelectorAll("#online-site option").length===3'));
    await manager.eval('const input=document.querySelector("#online-filter");input.value="没有匹配的书";input.dispatchEvent(new Event("input",{bubbles:true}))');check('在线结果关键词筛选有效',await manager.eval('document.querySelectorAll(".result").length===0'));await manager.click('[data-action="reset-online-filter"]');await until(()=>manager.eval('document.querySelectorAll(".result").length===1'));
    await reader.request('show-manager');await until(()=>manager.eval('document.visibilityState==="visible"'));
    if(process.env.SZM_SKIP_SCREENSHOTS!=='1')await manager.screenshot(path.join(evidence,'results.png'));
    for(const format of ['epub','txt']){
      const index=await manager.eval('Number(document.querySelector(".result-top [data-action=online-download]").dataset.index)'),chosen=(await manager.request('snapshot')).online.results[index];
      await manager.eval(`(()=>{const field=document.querySelector('#online-format');field.value='${format}';field.dispatchEvent(new Event('change',{bubbles:true}));})()`);
      await until(()=>manager.eval('!document.querySelector(".result-top [data-action=online-download]").disabled'));
      await manager.click('.result-top [data-action="online-download"]');await until(async()=>(await manager.request('snapshot')).online.running);await nav('library');check(format.toUpperCase()+' 下载期间可浏览书库',(await manager.request('snapshot')).online.running);
      await until(async()=>{const online=(await manager.request('snapshot')).online;if(online.error)throw new Error(online.error);return !online.running;},50000);
      const snapshot=await manager.request('snapshot'),book=snapshot.books.find(book=>book.format.toLowerCase()===format);check(format.toUpperCase()+' 实际下载并经导入器入库',Boolean(book));
      const body=fs.readFileSync(path.join(profile,'books',book.file),'utf8');check(format.toUpperCase()+' 入库正文包含完整两章',body.includes('开篇章节的合成正文')&&body.includes('结束章节的合成正文'));check(format.toUpperCase()+' 来源和作者与所选站点一致',book.sourceName===chosen.sourceName&&book.author===chosen.author&&book.onlineUrl===chosen.url);await nav('search');
    }
    chapterCount=3;const tracked=(await manager.request('snapshot')).books[0];
    await manager.request('open',{id:tracked.id});await manager.request('move',{position:7});await manager.request('bookmark');
    const hitsBefore=hits.filter(url=>url.startsWith('/chapter/')).length,oldBody=fs.readFileSync(path.join(profile,'books',tracked.file),'utf8');
    await manager.request('online-update',{ids:[tracked.id]});await until(async()=>!(await manager.request('snapshot')).online.running,45000);
    let updated=(await manager.request('snapshot')).books.find(book=>book.id===tracked.id);
    check('真实追更新增一章且只请求新增正文',updated.onlineChapters.length===3&&hits.filter(url=>url.startsWith('/chapter/')).length===hitsBefore+1&&hits.at(-1)==='/chapter/3');
    check('追更保留旧正文、进度和书签',fs.readFileSync(path.join(profile,'books',updated.file),'utf8').startsWith(oldBody)&&updated.position===7&&updated.bookmarks.length===1);
    const latestFile=updated.file;await manager.request('online-update',{ids:[tracked.id]});await until(async()=>!(await manager.request('snapshot')).online.running,45000);
    updated=(await manager.request('snapshot')).books.find(book=>book.id===tracked.id);check('再次追更不重复追加或重抓旧章',updated.file===latestFile&&hits.filter(url=>url.startsWith('/chapter/')).length===hitsBefore+1);
    check('后台任务与结果渲染无未捕获异常',manager.errors.length===0&&reader.errors.length===0);
    fs.writeFileSync(path.join(evidence,'report.json'),JSON.stringify({passed:checks.length,checks,hits,environment:{node:process.version,electron:require('electron/package.json').version},scope:'本地合成站点；未把第三方站点可用性当作保证'},null,2));console.log(JSON.stringify({passed:checks.length,evidence}));
    await manager.request('quit').catch(()=>{});await until(()=>child.exitCode!==null);
  }catch(error){if(manager){fs.writeFileSync(path.join(evidence,'failure.json'),JSON.stringify(await manager.eval('({title:document.querySelector("h1")?.textContent,route:document.querySelector("[aria-current=page]")?.dataset.route,html:document.querySelector(".workspace-inner")?.innerHTML,errors:document.querySelector("#notice")?.textContent})').catch(()=>null),null,2));await manager.screenshot(path.join(evidence,'failure.png')).catch(()=>{});}throw error;
  }finally{manager?.close();reader?.close();if(child?.exitCode===null)child.kill();await new Promise(resolve=>server.close(resolve));fs.writeFileSync(path.join(evidence,'runtime.log'),logs);}
})().catch(error=>{console.error(error);process.exitCode=1;});
