'use strict';
const {test}=require('node:test'),assert=require('node:assert/strict');
const fs=require('node:fs'),fsp=require('node:fs/promises'),os=require('node:os'),path=require('node:path'),crypto=require('node:crypto'),JSZip=require('jszip');
const {Library}=require('../app/library.cjs'),{parseBook}=require('../app/importer.cjs'),{convertFile}=require('../app/file-tasks.cjs');
const {planUpdate,commitUpdate,mapOnlineChapters,validateDetails}=require('../app/updates.cjs'),{exportBackup,importBackup}=require('../app/backup.cjs'),consent=require('../app/consent.cjs');
const {OnlineTasks,assertPublicUrl,safeBookUrl}=require('../app/online.cjs');
function fixture(t){const root=fs.mkdtempSync(path.join(os.tmpdir(),'szm-v030-'));t.after(()=>fs.rmSync(root,{recursive:true,force:true}));return root;}
const toc=n=>Array.from({length:n},(_,i)=>({title:'第'+(i+1)+'章 测试',url:'https://example.com/chapter/'+(i+1)}));
const details=n=>({book:{bookName:'测试',author:'作者'},toc:toc(n)});
const body=n=>toc(n).map(chapter=>chapter.title+'\n章节正文。\n').join('\n');
async function tracked(t){const root=fixture(t),library=new Library(path.join(root,'profile')),input=path.join(root,'book.txt');fs.writeFileSync(input,body(2));const {book}=await library.importFile(input,undefined,{metadata:{onlineChapters:toc(2),onlineUrl:'https://example.com/book/1',tracking:true}});return {root,library,book};}
const {simplePdf}=require('./document-fixture.cjs');

test('文档导入支持 Markdown 和 HTML，移除脚本且不执行外部资源',async t=>{
  const root=fixture(t),md=path.join(root,'book.md'),html=path.join(root,'page.html');
  fs.writeFileSync(md,'# 第一章 开始\n\n**中文正文** [链接文字](https://example.com)\n');
  fs.writeFileSync(html,'<html><head><script>throw Error("bad")</script></head><body><h1>第一章</h1><p>正文 &amp; 测试</p><script>secret</script></body></html>');
  assert.match((await parseBook(md)).text,/第一章 开始/);assert.match((await parseBook(md)).text,/中文正文 链接文字/);
  const parsed=await parseBook(html);assert.match(parsed.text,/正文 & 测试/);assert.doesNotMatch(parsed.text,/secret|throw/);
  const output=path.join(root,'converted.epub');await convertFile(md,output);assert.match((await parseBook(output)).text,/中文正文/);
});
test('DOCX 提取段落、表格、标签转义、元数据并可转换为 TXT',async t=>{
  const root=fixture(t),zip=new JSZip(),file=path.join(root,'word.docx');
  zip.file('word/document.xml','<w:document xmlns:w="word"><w:body><w:p><w:r><w:t>第一章 DOCX</w:t></w:r></w:p><w:p><w:r><w:t>中文 &amp; 正文</w:t><w:tab/><w:t>第二列</w:t></w:r></w:p></w:body></w:document>');
  zip.file('docProps/core.xml','<cp:coreProperties xmlns:cp="core" xmlns:dc="dc"><dc:title>文档标题</dc:title><dc:creator>文档作者</dc:creator></cp:coreProperties>');
  fs.writeFileSync(file,await zip.generateAsync({type:'nodebuffer'}));
  const book=await parseBook(file);assert.equal(book.title,'文档标题');assert.equal(book.author,'文档作者');assert.match(book.text,/中文 & 正文\t第二列/);
  const output=path.join(root,'converted.txt');await convertFile(file,output);assert.match(fs.readFileSync(output,'utf8'),/第一章 DOCX/);
});
test('PDF 可提取文字并能在导入 worker 内入库；空 PDF 给出 OCR 提示',async t=>{
  const root=fixture(t),file=path.join(root,'sample.pdf'),empty=path.join(root,'empty.pdf');
  fs.writeFileSync(file,simplePdf('Hello PDF text'));fs.writeFileSync(empty,simplePdf(''));
  assert.match((await parseBook(file)).text,/Hello PDF text/);
  const library=new Library(path.join(root,'profile'));const result=await library.importFile(file);assert.equal(result.book.format,'PDF');
  await assert.rejects(parseBook(empty),/OCR/);fs.writeFileSync(empty,'broken pdf');await assert.rejects(parseBook(empty));
});
test('增量追加保留旧正文、进度、书签、统计及旧文件，重复检查不重复写入',async t=>{
  const {library,book}=await tracked(t);library.open(book.id);library.move({position:5});library.bookmark();book.readingSeconds=120;library.flushSave();
  const oldPath=library.textPath(book),oldText=fs.readFileSync(oldPath,'utf8'),mark=JSON.stringify(book.bookmarks);
  const plan=planUpdate(library,book.id,details(3));assert.equal(plan.start,2);assert.equal(plan.chapters.length,1);
  await commitUpdate(library,plan,body(3).slice(body(3).indexOf('第3章')));
  assert.equal(book.position,5);assert.equal(JSON.stringify(book.bookmarks),mark);assert.equal(book.readingSeconds,120);
  assert.ok(fs.readFileSync(library.textPath(book),'utf8').startsWith(oldText));assert.equal(fs.readFileSync(oldPath,'utf8'),oldText);
  assert.equal(book.chapters.length,3);assert.equal(book.newChapterCount,1);assert.equal(library.read({count:12}).position,5);
  const newFile=book.file;await commitUpdate(library,planUpdate(library,book.id,details(3)),'');assert.equal(book.file,newFile);assert.equal(book.newChapterCount,0);
});
test('源站删章、重排、旧 URL 改变和重复目录均拒绝追加',async t=>{
  const {library,book}=await tracked(t),oldFile=book.file;
  assert.throws(()=>planUpdate(library,book.id,details(1)),/变短/);
  const reversed=details(3);reversed.toc.reverse();assert.throws(()=>planUpdate(library,book.id,reversed),/顺序/);
  const changed=details(3);changed.toc[0].url+='/changed';assert.throws(()=>planUpdate(library,book.id,changed),/旧章节/);
  const duplicate=details(2);duplicate.toc[1].url=duplicate.toc[0].url;assert.throws(()=>validateDetails(duplicate),/重复/);
  assert.equal(book.file,oldFile);
});
test('追加失败、保存失败与取消不会改变正文、索引或留下新增文件',async t=>{
  const {library,book}=await tracked(t),before=JSON.stringify(book),files=fs.readdirSync(path.join(library.root,'books'));
  const plan=planUpdate(library,book.id,details(3));
  await assert.rejects(commitUpdate(library,plan,'缺少章节标题'),/缺少章节/);
  const save=library.save;library.save=()=>{throw new Error('disk failure');};
  await assert.rejects(commitUpdate(library,plan,'第3章 测试\n新增正文'),/disk failure/);library.save=save;
  assert.equal(JSON.stringify(book),before);assert.deepEqual(fs.readdirSync(path.join(library.root,'books')),files);
  const abort=new AbortController();abort.abort();await assert.rejects(commitUpdate(library,plan,'第3章 测试\n正文',{signal:abort.signal}));assert.equal(JSON.stringify(book),before);
});
test('有在线清单时支持非数字章名，并拦截缺章的初次下载',async t=>{
  const root=fixture(t),file=path.join(root,'book.txt'),library=new Library(path.join(root,'profile'));
  fs.writeFileSync(file,'序曲\n正文\n\n尾声\n结束\n');
  const chapters=[{title:'序曲',url:'https://example.com/a'},{title:'尾声',url:'https://example.com/b'}];
  assert.equal(mapOnlineChapters(fs.readFileSync(file,'utf8'),chapters).length,2);
  await assert.rejects(library.importFile(file,undefined,{metadata:{onlineChapters:toc(2)}}),/缺少章节/);
  assert.equal(library.state.books.length,0);assert.equal(fs.readdirSync(path.join(library.root,'books')).length,0);
});
test('导入 worker 取消时不入库且清理临时文件',async t=>{
  const root=fixture(t),library=new Library(path.join(root,'profile')),file=path.join(root,'big.txt');fs.writeFileSync(file,'第一章 开始\n'+'中文正文'.repeat(200000));
  const abort=new AbortController(),pending=library.importFile(file,undefined,{signal:abort.signal});setTimeout(()=>abort.abort(),5);
  await assert.rejects(pending,/取消/);assert.equal(library.state.books.length,0);assert.deepEqual(fs.readdirSync(path.join(library.root,'books')),[]);
});
test('章节映射兼容上游把数字点号标题标准化为第 N 章',()=>{
  const chapters=mapOnlineChapters('第1章 开始\n正文\n第2章 结束\n正文',[{title:'1. 开始'},{title:'2. 结束'}]);
  assert.equal(chapters.length,2);assert.equal(chapters[1].position,10);
});
test('备份不同 ID 同文件名不覆盖；同 ID 缺失正文可修复，保留现有进度',async t=>{
  const {root,library,book}=await tracked(t);library.open(book.id);library.move({position:8});library.bookmark();library.flushSave();
  const backup=path.join(root,'backup.szmbak');await exportBackup({dataDir:library.root,library,target:backup});
  const originalFile=book.file;fs.unlinkSync(library.textPath(book));const report=await importBackup({dataDir:library.root,library,source:backup});
  const repaired=library.book(book.id);assert.equal(report.repaired,1);assert.equal(repaired.position,8);assert.equal(repaired.bookmarks.length,1);assert.notEqual(repaired.file,originalFile);
  const other=new Library(path.join(root,'other'));const otherBook={...repaired,id:crypto.randomUUID(),file:originalFile};other.state.books=[otherBook];fs.writeFileSync(other.textPath(otherBook),'现有用户正文');
  const before=fs.readFileSync(other.textPath(otherBook),'utf8');await importBackup({dataDir:other.root,library:other,source:backup});assert.equal(other.state.books.length,2);assert.equal(fs.readFileSync(other.textPath(otherBook),'utf8'),before);
});
test('空书库恢复设置及当前书；合并保留设置；声明不随备份自动接受',async t=>{
  const {root,library,book}=await tracked(t);library.open(book.id);library.settings({fontSize:22});consent.accept(library,{version:consent.VERSION,read:true,accepted:true});
  const backup=path.join(root,'backup.szmbak');await exportBackup({dataDir:library.root,library,target:backup});
  const other=new Library(path.join(root,'other'));await importBackup({dataDir:other.root,library:other,source:backup});
  assert.equal(other.state.settings.fontSize,22);assert.equal(other.state.activeId,book.id);assert.equal(consent.accepted(other.state),false);
  other.settings({fontSize:16});await importBackup({dataDir:other.root,library:other,source:backup});assert.equal(other.state.settings.fontSize,16);
  assert.throws(()=>consent.accept(other,{version:consent.VERSION,accepted:true}),/阅读/);
});
test('备份错误版本和落盘失败不产生半合并书库',async t=>{
  const {root,library}=await tracked(t),backup=path.join(root,'bad.szmbak'),zip=new JSZip();
  zip.file('library.json',JSON.stringify({version:999,books:[]}));fs.writeFileSync(backup,await zip.generateAsync({type:'nodebuffer'}));
  const before=JSON.stringify(library.state);await assert.rejects(importBackup({dataDir:library.root,library,source:backup}),/版本/);assert.equal(JSON.stringify(library.state),before);
  await exportBackup({dataDir:library.root,library,target:backup});const other=new Library(path.join(root,'other'));other.save=()=>{throw new Error('disk failure');};
  await assert.rejects(importBackup({dataDir:other.root,library:other,source:backup}),/disk failure/);assert.equal(other.state.books.length,0);assert.deepEqual(fs.readdirSync(path.join(other.root,'books')),[]);
});
test('内网地址在引擎运行前拒绝，IPv4 映射 IPv6 也不能绕过',async t=>{
  const bypass=process.env.SZM_ALLOW_PRIVATE_URLS;delete process.env.SZM_ALLOW_PRIVATE_URLS;t.after(()=>{if(bypass)process.env.SZM_ALLOW_PRIVATE_URLS=bypass;});
  let calls=0;const online=new OnlineTasks({runner:async()=>{calls++;},notify:()=>{}});
  online.state.results=[{url:'http://127.0.0.1/book',bookName:'私网'}];online.download(0);await online.pending;assert.equal(calls,0);assert.match(online.state.error,/不被允许/);
  assert.equal(safeBookUrl('http://[::ffff:7f00:1]/book'),false);await assert.rejects(assertPublicUrl('http://localhost/book'));
});
test('取消旧任务后新任务的状态与消息不会被旧完成回调覆盖',async t=>{
  const root=fixture(t);let release;const online=new OnlineTasks({profile:root,runner:({input})=>input.keyword==='old'?new Promise(resolve=>release=resolve):Promise.resolve({data:[{bookName:'new',url:'https://example.com/new'}]}),notify:()=>{}});
  online.search('old');const old=online.pending;online.cancel();online.search('new');await online.pending;
  release({data:[{bookName:'old',url:'https://example.com/old'}]});await old;assert.equal(online.state.results[0].bookName,'new');assert.match(online.state.message,/搜索完成/);
});
