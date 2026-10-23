'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const fsp = require('node:fs/promises');
const path = require('node:path');
const os = require('node:os');
const crypto = require('node:crypto');
const JSZip = require('jszip');
const iconv = require('iconv-lite');
const { Library, scanLegacy, migrateLegacy, legacyPosition } = require('../app/library.cjs');
const { parseBook, htmlText } = require('../app/importer.cjs');
const { OnlineTasks, decodeResult, runEngine } = require('../app/online.cjs');

function fixture(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'szm-independent-test-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  return root;
}
async function txt(root, name = '测试小说.txt', text = '第一章\n独立工程测试正文。'.repeat(100)) {
  const file = path.join(root, name); await fsp.writeFile(file, text); return file;
}
async function epub(root, name = '测试.epub') {
  const zip = new JSZip();
  zip.file('META-INF/container.xml','<container><rootfiles><rootfile full-path="OPS/book.opf"/></rootfiles></container>');
  zip.file('OPS/book.opf','<package><metadata><dc:title xmlns:dc="urn:dc">测试电子书</dc:title><dc:creator xmlns:dc="urn:dc">测试作者</dc:creator></metadata><manifest><item id="two" href="two.xhtml" media-type="application/xhtml+xml"/><item id="one" href="one.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="one"/><itemref idref="two"/></spine></package>');
  zip.file('OPS/one.xhtml','<html><head><title>不能当正文</title><script>require("fs")</script></head><body><h1>第一章</h1><p>第一段 &amp; 内容</p></body></html>');
  zip.file('OPS/two.xhtml','<html><body><p>第二章的正文</p></body></html>');
  const file = path.join(root,name); await fsp.writeFile(file,await zip.generateAsync({type:'nodebuffer'})); return file;
}
test('TXT 导入、重复导入、进度与书签在重启后保留', async t => {
  const root = fixture(t); const library = new Library(path.join(root,'profile')); const file = await txt(root);
  const result = await library.importFile(file); assert.equal(result.duplicate,false);
  assert.equal((await library.importFile(file)).duplicate,true); assert.equal(library.state.books.length,1);
  library.open(result.book.id); library.move({direction:1,count:34}); library.bookmark(); library.flushSave();
  const loaded = new Library(library.root); loaded.open(result.book.id);
  assert.equal(loaded.read().position,34); assert.equal(loaded.read().bookmarks.length,1);
});
test('异步导入在空文件/扩展名不支持时不给书库增加空条目', async t => {
  const root = fixture(t); const library = new Library(path.join(root,'profile'));
  await assert.rejects(library.importFile(await txt(root,'空.txt','')),/为空/);
  await assert.rejects(library.importFile(await txt(root,'不是小说.bin','abc')),/只支持/);
  assert.equal(library.state.books.length,0);
});
test('GB18030 与 UTF16 文本可导入', async t => {
  const root = fixture(t); const content = '中文小说内容';
  for(const encoding of ['gb18030','utf16le']) {
    const file = path.join(root,encoding+'.txt'); let bytes = iconv.encode(content,encoding);
    if(encoding === 'utf16le') bytes = Buffer.concat([Buffer.from([255,254]),bytes]);
    await fsp.writeFile(file,bytes); assert.equal((await parseBook(file)).text,content);
  }
});
test('EPUB 按 spine 顺序导入、解析元数据且不执行书中脚本', async t => {
  const root = fixture(t); const result = await parseBook(await epub(root));
  assert.equal(result.title,'测试电子书'); assert.equal(result.author,'测试作者');
  assert.ok(result.text.indexOf('第一章') < result.text.indexOf('第二章'));
  assert.ok(result.text.includes('第一段 & 内容')); assert.ok(!result.text.includes('require('));
  const library = new Library(path.join(root,'profile')); const imported = await library.importFile(await epub(root,'另一本.epub'));
  assert.equal(imported.book.format,'EPUB'); assert.match(library.open(imported.book.id).text,/第一章/);
});
test('损坏 EPUB 拒绝导入', async t => {
  const root = fixture(t); const file = await txt(root,'损坏.epub','not-a-zip');
  await assert.rejects(parseBook(file));
});
test('HTML 文本提取屏蔽 script/style 并保留段落', () => {
  assert.equal(htmlText('<style>body{}</style><p>甲</p><script>alert(1)</script><p>乙</p>'),'甲\n\n乙');
});
test('字符分页不会切断 emoji 代理对，首尾跳转有界', async t => {
  const root = fixture(t); const library = new Library(path.join(root,'profile'));
  const {book} = await library.importFile(await txt(root,'emoji.txt','甲😀乙丙')); library.open(book.id);
  assert.equal(library.read({count:2}).text,'甲😀'); assert.equal(library.move({direction:1,count:2}).position,3);
  assert.equal(library.move({position:-100}).position,0);
  assert.equal(library.move({position:999}).position,4);
});
test('损坏主书库从备份恢复且保存损坏原件', async t => {
  const root = fixture(t); const library = new Library(path.join(root,'profile'));
  await library.importFile(await txt(root)); library.settings({fontSize:20});
  fs.writeFileSync(library.file,'{bad json');
  const loaded = new Library(library.root); assert.equal(loaded.state.books.length,1);
  assert.match(loaded.recovery,/备份恢复/); assert.ok(fs.readdirSync(library.root).some(name=>name.includes('.damaged-')));
  loaded.settings({fontSize:21});
  assert.equal(JSON.parse(fs.readFileSync(library.file+'.bak','utf8')).books.length,1);
});
test('主书库与备份都损坏时停止，不能静默清空', t => {
  const root = fixture(t); fs.writeFileSync(path.join(root,'library.json'),'broken');
  assert.throws(()=>new Library(root),/停止写入/); assert.equal(fs.readFileSync(path.join(root,'library.json'),'utf8'),'broken');
});
test('可见性设置有下限，拒绝非法数字', t => {
  const library = new Library(fixture(t));
  assert.equal(library.settings({opacity:0}).opacity,.3);
  assert.equal(library.settings({fontSize:999}).fontSize,40);
  assert.throws(()=>library.settings({fontSize:'NaN'}),/数字/);
});
test('移除条目不删除正文，缺失文件可以重新关联', async t => {
  const root = fixture(t); const library = new Library(path.join(root,'profile')); const file = await txt(root);
  const {book} = await library.importFile(file); const original = library.textPath(book);
  fs.unlinkSync(original); assert.equal(library.snapshot().books[0].missing,true);
  await library.importFile(file,book.id); assert.equal(library.snapshot().books[0].missing,false);
  const restored = library.textPath(book); library.archive(book.id); assert.ok(fs.existsSync(restored));
});
test('扫描合并顶层与嵌套书库，选用更新时间较新的进度', async t => {
  const root = fixture(t); const one = await txt(root); const two = await txt(root,'嵌套书籍.txt','只在嵌套书库中');
  const first={id:'a',path:one,title:'第一本',readPosition:10,readPositionUpdatedAt:10,bookmarks:[{position:5,title:'旧位置书签'}]};
  fs.writeFileSync(path.join(root,'single-reader-state.json'),JSON.stringify({Book:{books:[first]},'single-reader-state':{Book:{books:[{...first,readPosition:30,readPositionUpdatedAt:20,bookmarks:[{position:25,title:'新位置书签'}]},{id:'b',path:two,title:'第二本'}]}}}));
  const scan = scanLegacy(root); assert.equal(scan.books.length,2); assert.equal(legacyPosition(scan.books[0]),30);
  assert.equal(scan.books[0].bookmarks.length,2);
});
test('旧数据迁移只复制，哈希不变；重复迁移不覆盖新版进度', async t => {
  const root = fixture(t); const old = path.join(root,'old'); fs.mkdirSync(old);
  const file = await txt(old,'旧书.txt','甲乙丙丁戊己庚辛壬癸'.repeat(30));
  const source = path.join(old,'single-reader-state.json'); fs.writeFileSync(source,JSON.stringify({Book:{books:[{path:file,title:'旧书',readPosition:42,bookmarks:[{position:20,title:'旧书签'}],favorite:true}]}}));
  const digest = file=>crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
  const before=[digest(source),digest(file)]; const library = new Library(path.join(root,'new'));
  const report = await migrateLegacy(library,old); assert.equal(report.imported,1); assert.ok(fs.existsSync(path.join(report.backup,'single-reader-state.json')));
  const book = library.state.books[0]; assert.equal(book.position,42); assert.equal(book.bookmarks[0].position,20); assert.equal(book.favorite,true);
  assert.notEqual(library.textPath(book),file); library.open(book.id); library.move({position:130});
  assert.equal((await migrateLegacy(library,old)).skipped,1); assert.equal(book.position,130);
  assert.deepEqual([digest(source),digest(file)],before);
});
test('迁移时缺失的书仍保留条目与进度', async t => {
  const root = fixture(t); const old = path.join(root,'old'); fs.mkdirSync(old);
  fs.writeFileSync(path.join(old,'single-reader-state.json'),JSON.stringify({Book:{books:[{path:path.join(old,'gone.txt'),title:'缺失书',readPosition:150}]}}));
  const library = new Library(path.join(root,'new')); const report = await migrateLegacy(library,old);
  assert.equal(report.missing,1); assert.equal(library.snapshot().books[0].missing,true); assert.equal(library.state.books[0].position,150);
});
test('更晚的自动书签优先；不误把第 N 页当作字符偏移', () => {
  assert.equal(legacyPosition({readPosition:20,readPositionUpdatedAt:10,autoBookmark:{position:80,updatedAt:20}}),80);
  assert.equal(legacyPosition({page:4,readPositionPageSize:34}),102);
});
test('后台搜索独立于页面，结果返回后可读取', async () => {
  let finish; const online = new OnlineTasks({resources:'',profile:'',notify:()=>{},importPaths:async()=>{},runner:()=>new Promise(resolve=>{finish=resolve;})});
  assert.equal(online.search('测试').running,true); assert.equal(online.snapshot().running,true);
  finish({data:[{url:'https://example.com/book',bookName:'测试'}]}); await online.pending;
  assert.equal(online.snapshot().results.length,1); assert.equal(online.snapshot().running,false);
});
test('取消后的旧搜索结果不会覆盖下一次搜索', async () => {
  const resolvers=[]; const online = new OnlineTasks({resources:'',profile:'',notify:()=>{},importPaths:async()=>{},runner:()=>new Promise(resolve=>resolvers.push(resolve))});
  online.search('第一本'); const previous=online.pending; online.cancel(); online.search('第二本'); const latest=online.pending;
  resolvers[0]({data:[{url:'https://example.com/a',bookName:'第一本旧结果'}]}); await previous; assert.equal(online.state.running,true);
  resolvers[1]({data:[{url:'https://example.com/b',bookName:'第二本新结果'}]}); await latest;
  assert.equal(online.state.results[0].bookName,'第二本新结果'); assert.equal(online.state.keyword,'第二本');
});
test('引擎错误显示，非法搜索和下载索引拒绝', async () => {
  const online=new OnlineTasks({notify:()=>{},runner:async()=>{throw new Error('测试错误');}});
  assert.throws(()=>online.search('  '),/请输入/); online.search('有效'); await online.pending;
  assert.equal(online.state.error,'测试错误'); assert.throws(()=>online.download(-1),/选择书籍/);
});
test('协议不接受日志冒充结果', () => {
  assert.throws(()=>decodeResult('plain log'),/没有返回/);
  assert.deepEqual(decodeResult('SZM_RESULT:'+Buffer.from(JSON.stringify({ok:true,data:[1]})).toString('base64')), [1]);
});
test('内置最小 Java 运行时可执行新适配器并加载上游规则', {timeout:30000}, async t => {
  const resources=path.resolve(__dirname,'../resources');
  if(!fs.existsSync(path.join(resources,'sonovel','app.jar'))) return t.skip('尚未暂存引擎');
  const result=await runEngine({resources,profile:fixture(t),input:{action:'probe'},timeout:25000});
  assert.ok(result.data.sources>0); assert.equal(result.data.version,'1.11.0');
});
