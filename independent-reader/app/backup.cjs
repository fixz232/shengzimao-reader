'use strict';
const fs=require('node:fs'),fsp=require('node:fs/promises'),path=require('node:path'),crypto=require('node:crypto');
const {pipeline}=require('node:stream/promises'),JSZip=require('jszip');
const {defaults,findChapters}=require('./library.cjs');
const safeId=value=>/^[a-zA-Z0-9-]{8,64}$/.test(String(value||''));
const safeFile=value=>/^[a-zA-Z0-9-]{8,64}\.txt$/.test(String(value||''));
const MAX_BACKUP=512*1024*1024,MAX_TEXT=128*1024*1024;
const number=(value,fallback=0)=>Number.isFinite(Number(value))?Math.max(0,Number(value)):fallback;
function normalizeRecord(record,text){
  const length=text===undefined?number(record.length):text.length,position=value=>Math.min(Math.max(0,length-1),Math.floor(number(value)));
  const book={id:record.id,title:record.title.trim().slice(0,250),file:'',length,position:position(record.position),bookmarks:[],createdAt:number(record.createdAt,Date.now()),lastReadAt:number(record.lastReadAt),readingSeconds:number(record.readingSeconds),readingDailyStats:{}};
  for(const key of ['author','sourceName','category','intro','format','legacyOrigin','onlineUrl','latestChapter','lastUpdateTime','status','wordCount'])
    if(typeof record[key]==='string')book[key]=record[key].slice(0,key==='intro'?10000:2000);
  for(const key of ['favorite','pinned','completed','tracking'])book[key]=record[key]===true;
  if(typeof record.cover==='string'&&(/^https?:\/\/[^\s]+$/i.test(record.cover)||/^data:image\/(?:png|jpeg|webp);base64,[A-Za-z0-9+/=]+$/.test(record.cover)))book.cover=record.cover.slice(0,2000000);
  book.tags=(Array.isArray(record.tags)?record.tags:[]).filter(v=>typeof v==='string').slice(0,20).map(v=>v.slice(0,30));
  book.bookmarks=(Array.isArray(record.bookmarks)?record.bookmarks:[]).filter(v=>v&&typeof v==='object').slice(0,30000).map(mark=>({id:safeId(mark.id)?mark.id:crypto.randomUUID(),title:String(mark.title||'书签').slice(0,250),position:position(mark.position),createdAt:number(mark.createdAt)}));
  for(const [day,seconds]of Object.entries(record.readingDailyStats&&typeof record.readingDailyStats==='object'?record.readingDailyStats:{}))if(/^\d{4}-\d{2}-\d{2}$/.test(day)&&Number.isFinite(seconds)&&seconds>=0)book.readingDailyStats[day]=seconds;
  book.chapters=text===undefined?[]:findChapters(text);
  if(text!==undefined)book.digest=crypto.createHash('sha256').update(text).digest('hex');
  if(Array.isArray(record.onlineChapters))book.onlineChapters=record.onlineChapters.filter(v=>v&&typeof v.url==='string'&&typeof v.title==='string').slice(0,30000).map(v=>({url:v.url.slice(0,2000),title:v.title.slice(0,250)}));
  if(text!==undefined&&book.onlineChapters?.length)try{book.chapters=require('./updates.cjs').mapOnlineChapters(text,book.onlineChapters);}catch{}
  return book;
}
async function exportBackup({dataDir,library,target}){
  const state=JSON.parse(JSON.stringify(library.state)),zip=new JSZip();
  const index={version:1,exportedAt:new Date().toISOString(),app:'shengzimao-reader-independent',books:[],settings:state.settings,activeId:state.activeId};
  let included=0,missing=0;
  for(const book of state.books){
    const record={...book};
    if(book.file&&safeFile(book.file)){const source=path.join(dataDir,'books',book.file);try{await fsp.access(source);zip.file('books/'+book.file,fs.createReadStream(source));included++;}catch{missing++;record.missing=true;}}
    else{missing++;record.missing=true;}
    index.books.push(record);
  }
  zip.file('library.json',JSON.stringify(index,null,2));
  zip.file('README.txt','圣子猫阅读器书库备份\n包含正文、进度、书签与设置。空书库恢复设置；已有书库合并并保留进度。同 ID 可修复缺失正文。\n');
  const file=target||path.join(dataDir,'书库备份-'+new Date().toISOString().slice(0,10)+'.szmbak'),temp=file+'.'+crypto.randomUUID()+'.tmp';
  try{await pipeline(zip.generateNodeStream({type:'nodebuffer',streamFiles:true,compression:'DEFLATE',compressionOptions:{level:6}}),fs.createWriteStream(temp,{flags:'wx'}));await fsp.rename(temp,file);}
  finally{await fsp.unlink(temp).catch(()=>{});}
  return {file,books:index.books.length,included,missing,size:(await fsp.stat(file)).size};
}
async function importBackup({dataDir,library,source}){
  if((await fsp.stat(source)).size>MAX_BACKUP)throw new Error('备份文件超过 512 MB 上限。');
  const zip=await JSZip.loadAsync(await fsp.readFile(source));
  if(Object.keys(zip.files).length>50000)throw new Error('备份条目过多。');
  let expanded=0;
  const load=async(entry,limit)=>{const size=entry._data?.uncompressedSize;if(Number.isFinite(size)&&(size>limit||expanded+size>MAX_BACKUP))throw new Error('备份解压内容超过安全上限。');const bytes=await entry.async('nodebuffer');if(bytes.length>limit||(expanded+=bytes.length)>MAX_BACKUP)throw new Error('备份解压内容超过安全上限。');return bytes;};
  const entry=zip.file('library.json');if(!entry)throw new Error('备份文件缺少 library.json，可能不是本应用的备份。');
  let payload;try{payload=JSON.parse((await load(entry,32*1024*1024)).toString('utf8'));}catch(error){throw new Error('备份中的 library.json 已损坏或超过上限：'+error.message);}
  if(payload?.version!==1||!Array.isArray(payload.books)||payload.books.length>20000)throw new Error('备份中的书库索引格式或版本不正确。');
  const booksDir=path.join(dataDir,'books');await fsp.mkdir(booksDir,{recursive:true});
  const original=library.state,wasEmpty=original.books.length===0,next={...original,books:original.books.map(book=>({...book}))},created=[];
  let imported=0,skipped=0,invalid=0,missingText=0,repaired=0;
  try{
    for(const record of payload.books){
      if(!record||!safeId(record.id)||typeof record.title!=='string'||!record.title.trim()){invalid++;continue;}
      const existing=next.books.find(book=>book.id===record.id);let ready=false;if(existing)try{ready=fs.existsSync(library.textPath(existing));}catch{}
      if(ready){skipped++;continue;}
      const textEntry=record.file&&safeFile(record.file)&&zip.file('books/'+record.file);let file='',text;
      if(textEntry){text=(await load(textEntry,MAX_TEXT)).toString('utf8');if(!text.trim())throw new Error('备份中存在空正文：'+record.title);file=crypto.randomUUID()+'.txt';const output=path.join(booksDir,file);created.push(output);await fsp.writeFile(output,text,{encoding:'utf8',flag:'wx'});}
      else missingText++;
      if(existing){if(file){const normalized=normalizeRecord({...record,...existing},text);Object.assign(existing,{file,length:normalized.length,digest:normalized.digest,chapters:normalized.chapters,position:normalized.position,bookmarks:normalized.bookmarks,missing:false});repaired++;}else skipped++;}
      else{next.books.push({...normalizeRecord(record,text),file,missing:!file});imported++;}
    }
    if(wasEmpty&&payload.settings&&typeof payload.settings==='object'&&!Array.isArray(payload.settings)){
      const settingsState={...next,settings:defaults().settings},receiver={state:settingsState,save:()=>{}};
      library.settings.call(receiver,payload.settings);next.settings=settingsState.settings;
      next.activeId=next.books.some(book=>book.id===payload.activeId&&book.file)?payload.activeId:'';
    }
    // Online consent stays local and is never imported from another device.
    library.state=next;library.cache=null;library.save();
  }catch(error){library.state=original;library.cache=null;await Promise.all(created.map(file=>fsp.unlink(file).catch(()=>{})));throw error;}
  return {imported,skipped,invalid,missingText,repaired,total:payload.books.length,settingsRestored:wasEmpty};
}
module.exports={exportBackup,importBackup};
