'use strict';
process.env.SZM_ALLOW_PRIVATE_URLS='1';
const {test}=require('node:test');
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),os=require('node:os'),http=require('node:http');
const {runEngine}=require('../app/online.cjs');
const {parseBook}=require('../app/importer.cjs');
test('真实 SoNovel 进程通过本地合成站点完成 JS 规则检索及 TXT/EPUB 全章下载',{timeout:90000},async t=>{
  const resources=path.resolve(__dirname,'../resources');
  if(!fs.existsSync(path.join(resources,'sonovel','app.jar')))return t.skip('尚未暂存引擎');
  const root=path.resolve(__dirname,'../.verification','engine-'+Date.now());fs.mkdirSync(root,{recursive:true});
  const hits=[];let base;
  const server=http.createServer((request,response)=>{
    hits.push(request.url);response.setHeader('Content-Type','text/html; charset=utf-8');
    if(request.url.startsWith('/search'))return response.end('<html><meta charset="utf-8"><div class="result"><a class="name" href="/book/1">合成测试小说</a><span class="author">测试作者</span></div><div class="result"><a class="name" href="/book/2">完全无关目录</a><span class="author">其他作者</span></div></html>');
    if(request.url==='/book/1')return response.end(`<html><meta charset="utf-8"><h1>合成测试小说</h1><span class="author">测试作者</span><p class="intro">纯本地回归样本</p><img class="cover" src="${base}/cover.png"><div id="toc"><a href="/chapter/1">第一章 开始</a><a href="/chapter/2">第二章 结束</a></div></html>`);
    if(request.url==='/cover.png'){response.setHeader('Content-Type','image/png');return response.end(Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a10UAAAAASUVORK5CYII=','base64'));}
    if(request.url.startsWith('/chapter/')){const second=request.url.endsWith('/2');return response.end(`<html><meta charset="utf-8"><h1>${second?'第二章 结束':'第一章 开始'}</h1><div id="content"><p>${second?'结束章节的合成正文。':'开篇章节的合成正文。'}</p></div></html>`);}
    response.statusCode=404;response.end('not found');
  });
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));t.after(()=>new Promise(resolve=>server.close(resolve)));base=`http://127.0.0.1:${server.address().port}`;
  const rulesDir=path.join(root,'rules');fs.mkdirSync(rulesDir);
  // needProxy only avoids upstream's optional external cover lookup; proxy remains disabled.
  fs.writeFileSync(path.join(rulesDir,'main.json'),JSON.stringify([{url:base+'/',name:'本地合成站点',language:'zh-CN',needProxy:true,search:{url:base+'/search?q=%s',method:'get',result:'.result',bookName:'.name@js:r=r.trim()',author:'.author'},book:{url:'/book/(\\d+)',bookName:'h1',author:'.author',intro:'.intro',coverUrl:'.cover@src'},toc:{item:'#toc a'},chapter:{title:'h1',content:'#content',paragraphTagClosed:true,filterTxt:'(?!)',filterTag:'script'},crawl:{minInterval:0,maxInterval:0,concurrency:2}}]));
  const call=input=>runEngine({resources,profile:root,rulesDir,input,timeout:25000});
  const searched=await call({action:'search',keyword:'合成测试小说'});assert.equal(searched.data.length,2);assert.ok(searched.data.some(book=>book.bookName==='完全无关目录'),'适配器保留源站原始结果，不提前过滤');assert.equal(searched.data[0].url,base+'/book/1');assert.equal(searched.data[0].bookName,'合成测试小说');
  for(const format of ['txt','epub']){
    const result=await call({action:'download',url:searched.data[0].url,format});assert.equal(result.data.length,1);assert.equal(path.extname(result.data[0]),'.'+format);
    const book=await parseBook(result.data[0]);assert.match(book.text,/开篇章节的合成正文/);assert.match(book.text,/结束章节的合成正文/);assert.ok(book.text.indexOf('第一章')<book.text.indexOf('第二章'));
  }
  const count=hits.length;delete process.env.SZM_ALLOW_PRIVATE_URLS;try{await assert.rejects(call({action:'details',url:base+'/book/1'}));assert.equal(hits.length,count,'Java 在发出请求前拒绝内网地址');}finally{process.env.SZM_ALLOW_PRIVATE_URLS='1';}
  assert.ok(hits.some(url=>url.startsWith('/search')));assert.ok(hits.filter(url=>url==='/chapter/1').length>=2);assert.ok(hits.filter(url=>url==='/chapter/2').length>=2);
});
