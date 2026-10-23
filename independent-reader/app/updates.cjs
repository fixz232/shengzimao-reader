'use strict';
const fs=require('node:fs'),fsp=require('node:fs/promises'),path=require('node:path'),crypto=require('node:crypto');
const titleKey=value=>String(value||'').normalize('NFKC').trim().replace(/^(\d+)\s*\.\s*(.+)$/,'第$1章$2').replace(/\s+/g,'').toLowerCase();
function mapOnlineChapters(text,toc){
  const lines=[];let position=0;
  for(const line of text.split('\n')){if(line.trim())lines.push({key:titleKey(line),position});position+=line.length+1;}
  let cursor=0;
  return toc.map(chapter=>{
    while(cursor<lines.length&&lines[cursor].key!==titleKey(chapter.title))cursor++;
    if(cursor===lines.length)throw new Error('下载正文缺少章节「'+chapter.title+'」，已停止入库或追加。');
    return {title:chapter.title,position:lines[cursor++].position};
  });
}
function validateDetails(value){
  if(!value||!value.book||!Array.isArray(value.toc)||!value.toc.length||value.toc.length>30000)throw new Error('源站未返回有效目录。');
  const seen=new Set();
  const toc=value.toc.map(chapter=>{
    if(!chapter||typeof chapter.title!=='string'||!chapter.title.trim()||typeof chapter.url!=='string'||!/^https?:\/\/[^\s]+$/i.test(chapter.url)||seen.has(chapter.url))throw new Error('源站目录存在空标题、重复章节或非法链接，已停止更新。');
    seen.add(chapter.url);return {title:chapter.title.slice(0,250),url:chapter.url.slice(0,2000)};
  });
  return {book:value.book,toc,chapterCount:toc.length};
}
function planUpdate(library,id,value){
  const details=validateDetails(value),book=library.book(id);
  if(!book.file||!fs.existsSync(library.textPath(book)))throw new Error('正文缺失，请先重新关联或恢复备份。');
  const manifest=Array.isArray(book.onlineChapters)&&book.onlineChapters.length?book.onlineChapters:null;
  const local=manifest||library.chapters(id);
  if(!local.length)throw new Error('旧书未记录可核对目录，请先保留备份并重新从原站入库。');
  if(local.length>details.toc.length)throw new Error('源站目录变短，已保留现有正文并停止追加。');
  for(let i=0;i<local.length;i++){
    if(titleKey(local[i].title)!==titleKey(details.toc[i].title)||(manifest&&local[i].url!==details.toc[i].url))
      throw new Error('源站目录顺序或旧章节已变化，无法安全追加；现有正文未改动。');
  }
  return {id,file:book.file,digest:book.digest,details,start:local.length,chapters:details.toc.slice(local.length)};
}
async function commitUpdate(library,plan,addition,{signal}={}){
  signal?.throwIfAborted();
  const book=library.book(plan.id);
  if(book.file!==plan.file||book.digest!==plan.digest)throw new Error('书籍正文已被其他操作修改，请重新检查更新。');
  if(!plan.chapters.length){
    const retained={...book};try{Object.assign(book,{onlineChapters:plan.details.toc,lastUpdateCheckAt:Date.now(),newChapterCount:0});library.save();}catch(error){for(const key of Object.keys(book))if(!Object.hasOwn(retained,key))delete book[key];Object.assign(book,retained);throw error;}return {added:0};
  }
  if(typeof addition!=='string'||!addition.trim())throw new Error('未取得完整新增正文，现有正文未改动。');
  const original=await fsp.readFile(library.textPath(book),'utf8');
  if(plan.digest&&crypto.createHash('sha256').update(original).digest('hex')!==plan.digest)throw new Error('本地正文已被修改，请重新关联后再更新。');
  const text=original+(original.endsWith('\n\n')?'':'\n\n')+addition;
  if(Buffer.byteLength(text)>128*1024*1024)throw new Error('更新后正文超过 128 MB 上限。');
  const filename=crypto.randomUUID()+'.txt',output=path.join(library.root,'books',filename);
  await fsp.writeFile(output,text,{encoding:'utf8',flag:'wx'});
  const retained={...book};
  try{
    signal?.throwIfAborted();
    if(library.book(plan.id)!==book||book.file!==plan.file||book.digest!==plan.digest)throw new Error('书籍已变化，请重新检查更新。');
    Object.assign(book,{file:filename,length:text.length,digest:crypto.createHash('sha256').update(text).digest('hex'),chapters:mapOnlineChapters(text,plan.details.toc),onlineChapters:plan.details.toc,
      lastUpdateCheckAt:Date.now(),lastUpdatedAt:Date.now(),newChapterCount:plan.chapters.length,
      updateBackupFiles:[plan.file,...(book.updateBackupFiles||[])].slice(0,2)});
    for(const key of ['latestChapter','lastUpdateTime','status'])if(typeof plan.details.book[key]==='string')book[key]=plan.details.book[key];
    library.cache=null;library.save();
  }catch(error){for(const key of Object.keys(book))if(!Object.hasOwn(retained,key))delete book[key];Object.assign(book,retained);library.cache=null;await fsp.unlink(output).catch(()=>{});throw error;}
  for(const stale of retained.updateBackupFiles||[])if(!book.updateBackupFiles.includes(stale)&&!library.state.books.some(item=>item.file===stale||(item.updateBackupFiles||[]).includes(stale)))try{await fsp.unlink(library.textPath({file:stale}));}catch{}
  return {added:plan.chapters.length};
}
module.exports={validateDetails,planUpdate,commitUpdate,titleKey,mapOnlineChapters};
