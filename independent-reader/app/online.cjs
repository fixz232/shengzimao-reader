'use strict';
const path = require('node:path');
const fs = require('node:fs');
const fsp = require('node:fs/promises');
const crypto = require('node:crypto');
const { spawn } = require('node:child_process');
const { isIP } = require('node:net');
const dns = require('node:dns').promises;
const isPrivateHost = hostname => {
  const host = String(hostname || '').toLowerCase().replace(/^\[|\]$/g, '');
  if (!host || host === 'localhost' || host.endsWith('.localhost') || host.endsWith('.local')) return true;
  if (isIP(host)) {
    if(host.includes(':')&&host.includes('.'))return isPrivateHost(host.split(':').at(-1));
    if(/^::ffff:/i.test(host)){const words=host.slice(7).split(':');if(words.length===2){const number=parseInt(words[0],16)*65536+parseInt(words[1],16);return isPrivateHost([24,16,8,0].map(n=>(number>>>n)&255).join('.'));}}
    if (host === '::1' || /^(f[cd]|fe[89ab])/.test(host) || /^0(?::|$)/.test(host.replace(/:/g, ':'))) return true;
    const parts = host.split('.').map(Number);
    if (parts.length === 4) return parts[0] === 0 || parts[0] === 10 || parts[0] === 127 || (parts[0] === 172 && parts[1] >= 16 && parts[1] <= 31) || (parts[0] === 192 && parts[1] === 168) || (parts[0] === 169 && parts[1] === 254);
    return false;
  }
  if (/^0\./.test(host) || /^(localhost|10|127)\./.test(host)) return true;
  const match = host.match(/^(\d+)\.(\d+)\./);
  if (match) { const a = Number(match[1]), b = Number(match[2]); if (a === 10 || a === 127 || a === 0 || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168) || (a === 169 && b === 254)) return true; }
  return false;
};
const safeBookUrl = url => {
  try {
    const parsed = new URL(url);
    if (!/^https?:$/.test(parsed.protocol) || isPrivateHost(parsed.hostname)) return false;
    return true;
  } catch { return false; }
};
async function assertPublicUrl(url) {
  // 仅测试脚本（本地合成站点）使用；正常打包运行不会有此环境变量。
  if (process.env.SZM_ALLOW_PRIVATE_URLS === '1') return;
  if (!safeBookUrl(url)) throw new Error('链接地址不被允许。');
  const hostname = new URL(url).hostname;
  if (isIP(hostname.replace(/^\[|\]$/g, ''))) return;
  let addresses = [];
  try { addresses = await dns.lookup(hostname, { all: true }); } catch { throw new Error('下载地址无法解析。'); }
  if (addresses.some(address => isPrivateHost(address.address))) throw new Error('下载地址指向内网，已被拒绝。');
}
const MAX_BATCH = 30;
const FORMATS = ['txt', 'epub'];
const {filterResults} = require('./search-matching.cjs');
function aggregateSources(books) {
  const groups = new Map();
  for (const book of Array.isArray(books) ? books : []) {
    const sourceId = String(book.sourceId || book.sourceName || '').trim();
    if (!sourceId) continue;
    const group = groups.get(sourceId) || { sourceId, sourceName: String(book.sourceName || sourceId), count: 0 };
    group.count += 1; groups.set(sourceId, group);
  }
  return [...groups.values()].sort((left, right) => right.count - left.count || left.sourceName.localeCompare(right.sourceName, 'zh-Hans-CN'));
}
const usableResult = book => Boolean(book && typeof book.url === 'string' && /^(?:https?:\/\/)/i.test(book.url) && (!book.cover || typeof book.cover !== 'string' || /^(?:https?|data:image\/(?:png|jpe?g|webp|gif))/i.test(book.cover)));
const bookNameOf = book => String((book && book.bookName) || '小说');
const refreshBatch = batch => { batch.total = batch.items.length; batch.done = batch.items.filter(item => item.state === 'done').length; batch.failed = batch.items.filter(item => item.state === 'failed').length; return batch; };
function decodeResult(stdout) {
  const match = stdout.match(/^SZM_RESULT:([A-Za-z0-9+/=]+)\s*$/m);
  if (!match) throw new Error('引擎没有返回可识别结果。');
  const value = JSON.parse(Buffer.from(match[1], 'base64').toString('utf8'));
  if (!value.ok) throw new Error(value.error || '引擎执行失败。');
  return value.data;
}
function runEngine({ resources, profile, input, onChild = () => {}, timeout = 120000, signal, rulesDir }) {
  return new Promise((resolve, reject) => {
    let child, timer, settled = false, work = '';
    const settle = (fn, value) => { if (settled) return; settled = true; clearTimeout(timer);if(fn===reject&&work)fs.rm(work,{recursive:true,force:true,maxRetries:5,retryDelay:200},()=>{}); fn(value); };
    (async () => {
      work = path.join(profile, 'engine', 'jobs', crypto.randomUUID());
      const home = path.join(resources, 'sonovel');
      const java = path.join(home, 'runtime', 'bin', process.platform === 'win32' ? 'java.exe' : 'java');
      for (const name of [java, path.join(home, 'app.jar'), path.join(home, 'adapter', 'org', 'shengzimao', 'reader', 'ReaderEngine.class')]) if (!fs.existsSync(name)) throw new Error('内置在线引擎尚未暂存，请先执行 npm run stage。');
      await fsp.mkdir(work, { recursive: true });
      await fsp.mkdir(path.join(work,'tmp'), { recursive: true });
      await fsp.cp(rulesDir || path.join(home, 'rules'), path.join(work, 'rules'), { recursive: true });
      await fsp.writeFile(path.join(work, 'config.ini'), '[global]\nauto-update=0\n[download]\ndownload-path=downloads\nextname=txt\ntxt-encoding=UTF-8\nenable-progressbar=0\n[source]\nactive-rules=main.json\nsearch-limit=30\nsearch-filter=1\nlanguage=zh-CN\n[crawl]\nconcurrency=4\nmax-retries=1\n[web]\nenabled=0\n');
      signal?.throwIfAborted();
      child = spawn(java, ['-Xms16m','-Xmx256m','-XX:+UseSerialGC','-Dfile.encoding=UTF-8','-Djava.io.tmpdir='+path.join(work,'tmp'), '-Dconfig.file=' + path.join(work,'config.ini'), '-cp', path.join(home, 'app.jar') + path.delimiter + path.join(home, 'adapter'), 'org.shengzimao.reader.ReaderEngine'], { cwd: work, windowsHide: true, stdio: ['pipe','pipe','pipe'] });
      const abort = () => child.kill();
      signal?.addEventListener('abort', abort, { once:true });
      onChild(child);
      let stdout = '', stderr = '';
      let outputSize = 0;
      const log = fs.createWriteStream(path.join(work, 'engine.log'));
      child.stdout.setEncoding('utf8'); child.stderr.setEncoding('utf8');
      child.stdout.on('data', chunk => { outputSize += Buffer.byteLength(chunk); if (outputSize > 8 * 1024 * 1024) child.kill(); else stdout += chunk; });
      child.stderr.on('data', chunk => { stderr = (stderr + chunk).slice(-6000); log.write(chunk); });
      log.on('error', () => {});
      child.on('error', error => { log.end(); log.destroy(); onChild(null); settle(reject, new Error(`在线引擎启动失败：${error.message}`)); });
      child.once('close', code => {
        log.end(); log.destroy(); onChild(null); signal?.removeEventListener('abort', abort);
        if (settled) return;
        try {
          if (outputSize > 8 * 1024 * 1024) throw new Error('在线结果过大，任务已停止。');
          const data = decodeResult(stdout);
          if (code !== 0) console.warn('[online]', `在线引擎异常退出（${code}），已保留解码结果。${stderr ? '\n' + stderr.slice(-500) : ''}`);
          settle(resolve, { data, work });
        } catch (error) { settle(reject, new Error(`${error.message}${stderr ? '\n' + stderr.slice(-500) : ''}`)); }
      });
      child.stdin.on('error', () => {});
      child.stdin.end(JSON.stringify(input));
      timer = setTimeout(() => { child.kill(); settle(reject, new Error('在线任务超时；可更换关键词或稍后重试。')); }, timeout);
      timer.unref();
    })().catch(error => { if (child) child.kill(); settle(reject, error); });
  });
}
class OnlineTasks {
  constructor({resources,profile,notify=()=>{},importPaths,library,runner=runEngine}){
    Object.assign(this,{resources,profile,notify,importPaths,library,runner});
    this.state={running:false,keyword:'',results:[],message:'',error:'',batch:null,sources:[],elapsedMs:0,revision:0};
    this.generation=0;this.child=null;this.startedAt=0;
  }
  snapshot(){return {...this.state,results:this.state.results.map(book=>({...book})),sources:this.state.sources.map(source=>({...source})),batch:this.state.batch?{...this.state.batch,items:this.state.batch.items.map(item=>({...item}))}:null};}
  cleanupWork(work){if(work)fs.rm(work,{recursive:true,force:true},()=>{});}
  sweepStale(){
    if(!this.profile)return;
    for(const root of [path.join(this.profile,'engine','jobs'),path.join(this.profile,'engine','profile'),path.join(this.profile,'jobs')]){
      let entries=[];try{entries=fs.readdirSync(root,{withFileTypes:true});}catch{continue;}
      for(const entry of entries)try{const file=path.join(root,entry.name);if(fs.statSync(file).mtimeMs<Date.now()-86400000)fs.rm(file,{recursive:true,force:true},()=>{});}catch{}
    }
  }
  begin(executor){
    if(this.state.running)throw new Error('已有在线任务正在运行，请等待或先取消。');
    const generation=++this.generation;this.abort=new AbortController();
    this.state.running=true;this.state.error='';this.startedAt=Date.now();this.state.elapsedMs=0;this.notify();
    let task;try{task=executor(generation,this.abort.signal);}catch(error){task=Promise.reject(error);}
    this.pending=Promise.resolve(task).catch(error=>{if(generation===this.generation){this.state.error=error.message;this.state.message='任务未完成。';}})
      .finally(()=>{if(generation!==this.generation)return;this.state.running=false;this.child=null;this.state.elapsedMs=Date.now()-this.startedAt;this.notify();this.sweepStale();});
    return this.snapshot();
  }
  skipBatch(reason,batch=this.state.batch){
    if(!batch)return false;let changed=false;
    for(const item of batch.items)if(['pending','downloading'].includes(item.state)){item.state='skipped';item.message=reason;changed=true;}
    refreshBatch(batch);return changed;
  }
  cancel(){
    const running=this.state.running;this.generation++;this.abort?.abort();this.child?.kill();this.child=null;this.state.running=false;
    if(running)this.state.elapsedMs=Date.now()-this.startedAt;
    this.skipBatch('已取消');this.state.message='任务已取消。';this.notify();return this.snapshot();
  }
  run(input,generation,signal){
    const launch=()=>{
      signal.throwIfAborted();if(generation!==this.generation)throw new Error('任务已取消。');
      return this.runner({resources:this.resources,profile:this.profile,input,signal,timeout:['download','update'].includes(input.action)?1800000:120000,
        onChild:child=>{if(generation===this.generation)this.child=child;else child?.kill();}});
    };
    return input.url?assertPublicUrl(input.url).then(launch):launch();
  }
  start(input,accept){
    if(this.state.running)throw new Error('已有在线任务正在运行，请等待或先取消。');
    this.state.batch=null;
    return this.begin(async(generation,signal)=>{
      let result;
      try{
        result=await this.run(input,generation,signal);
        if(generation!==this.generation)return;
        await accept?.(result,generation,signal);
      }finally{this.cleanupWork(result?.work);}
    });
  }
  async checkedFile(work,file,extension){
    const root=await fsp.realpath(path.join(work,'downloads')),real=await fsp.realpath(file);
    const relative=path.relative(root,real),stat=await fsp.stat(real);
    if(!relative||relative.startsWith('..')||path.isAbsolute(relative)||path.extname(real).toLowerCase()!=='.'+extension||!stat.isFile())throw new Error('引擎返回了不允许的文件路径。');
    if(stat.size>128*1024*1024)throw new Error('文件超过 128 MB 上限，已跳过导入。');
    return real;
  }
  async importDownloaded(book,format,{data,work},signal){
    if(!Array.isArray(data)||!data.length)throw new Error('下载未生成 '+format.toUpperCase()+' 文件。');
    for(const file of data)await this.checkedFile(work,file,format);
    let details;
    const metadataFile=path.join(work,'download-metadata.json');
    if(fs.existsSync(metadataFile)){
      if((await fsp.stat(metadataFile)).size>16*1024*1024)throw new Error('目录元数据过大。');
      details=require('./updates.cjs').validateDetails(JSON.parse(await fsp.readFile(metadataFile,'utf8')));
    }
    signal?.throwIfAborted();
    const metadata={sourceName:String(book.sourceName||'在线'),onlineUrl:book.url,author:String(book.author||details?.book.author||''),tracking:true};
    if(details){metadata.onlineChapters=details.toc;for(const key of ['intro','category','latestChapter','lastUpdateTime','status'])if(typeof details.book[key]==='string')metadata[key]=details.book[key];}
    const report=await this.importPaths(data,metadata,{signal});
    if(report.errors.length)throw new Error(report.errors.map(error=>error.message).join('；'));
    return report;
  }
  search(keyword){
    keyword=String(keyword||'').trim();
    if(!keyword||keyword.length>120)throw new Error('请输入 1–120 个字符的书名或作者。');
    if(this.state.running)throw new Error('请等待当前任务结束或先取消。');
    this.state.keyword=keyword;this.state.results=[];this.state.sources=[];this.state.revision++;
    this.state.message='正在后台搜索；可以切换到其他页面。';this.lastOperation={kind:'search',keyword};
    return this.start({action:'search',keyword},({data})=>{
      if(!Array.isArray(data))throw new Error('搜索结果格式错误。');
      this.state.results=filterResults(keyword,data.filter(usableResult)).slice(0,500);
      this.state.sources=aggregateSources(this.state.results);
      this.state.message=this.state.results.length?'搜索完成，找到 '+this.state.results.length+' 条相关结果（已过滤 '+(data.length-this.state.results.length)+' 条无关或无效结果）。':'未找到与书名或作者匹配的结果，请缩短书名、检查作者，或更换关键词。';
    });
  }
  filterResults(query){return filterResults(query,this.state.results);}
  download(index,format='txt'){
    if(!Number.isInteger(index)||!this.state.results[index])throw new Error('请从当前搜索结果选择书籍。');
    if(!FORMATS.includes(format))throw new Error('入库格式只支持 TXT 或 EPUB。');
    if(this.state.running)throw new Error('请等待当前任务结束或先取消。');
    const book=this.state.results[index];this.lastOperation={kind:'download',index,format};
    this.state.message='正在下载《'+bookNameOf(book)+'》，完成后自动导入。';
    return this.start({action:'download',url:book.url,format},async(result,generation,signal)=>{
      const report=await this.importDownloaded(book,format,result,signal);
      if(generation===this.generation)this.state.message='下载完成，导入 '+report.imported+' 本，已有 '+report.duplicates+' 本。';
    });
  }
  downloadMany(indexes,format='txt'){
    if(this.state.running)throw new Error('已有在线任务正在运行，请等待或先取消。');
    if(!Array.isArray(indexes)||!indexes.length)throw new Error('请先选择要批量下载的结果。');
    if(indexes.length>MAX_BATCH)throw new Error('批量下载一次最多 '+MAX_BATCH+' 条结果。');
    if(!FORMATS.includes(format))throw new Error('入库格式只支持 TXT 或 EPUB。');
    for(const index of indexes)if(!Number.isInteger(index)||!this.state.results[index])throw new Error('请从当前搜索结果选择书籍。');
    const batch=refreshBatch({format,items:[...new Set(indexes)].map(index=>({index,bookName:bookNameOf(this.state.results[index]),sourceName:String(this.state.results[index].sourceName||''),state:'pending',message:''}))});
    this.state.batch=batch;this.state.message='正在下载 '+batch.total+' 本（批量），完成后自动导入。';
    return this.begin((generation,signal)=>this.runBatch(batch,generation,signal));
  }
  async runBatch(batch,generation,signal){
    for(const item of batch.items){
      if(generation!==this.generation)return;
      const book=this.state.results[item.index];item.state='downloading';this.notify();let result;
      try{
        if(!book)throw new Error('搜索结果已变化。');
        result=await this.run({action:'download',url:book.url,format:batch.format},generation,signal);
        signal.throwIfAborted();
        const report=await this.importDownloaded(book,batch.format,result,signal);
        if(generation!==this.generation)return;
        item.state=report.imported?'done':'skipped';item.message=report.imported?'导入 '+report.imported+' 本':'已在书库';
      }catch(error){if(generation!==this.generation)return;item.state='failed';item.message=error.message;}
      finally{this.cleanupWork(result?.work);}
      refreshBatch(batch);this.notify();
    }
    if(generation===this.generation)this.state.message='批量下载结束：成功 '+batch.done+' 本，跳过 '+batch.items.filter(item=>item.state==='skipped').length+' 本，失败 '+batch.failed+' 本。';
  }
  async details(index){
    if(!Number.isInteger(index)||!this.state.results[index])throw new Error('请从当前结果选择书籍。');
    const book=this.state.results[index];let details;
    this.state.message='正在读取书籍详情和目录…';
    this.start({action:'details',url:book.url},({data})=>{
      details=require('./updates.cjs').validateDetails(data);
      for(const key of Object.keys(book))if(details.book[key]==null||details.book[key]==='')details.book[key]=book[key];
      Object.assign(book,details.book,{chapterCount:details.chapterCount});this.state.message='详情与目录已读取。';
    });
    await this.pending;if(!details)throw new Error(this.state.error||'详情读取已取消。');return details;
  }
  update(ids){
    if(this.state.running)throw new Error('请等待当前任务结束或先取消。');
    if(!this.library)throw new Error('书库未就绪。');
    if(!Array.isArray(ids)||!ids.length||ids.length>200)throw new Error('请选择 1–200 本在线书籍。');
    const books=[...new Set(ids)].map(id=>this.library.book(id));
    if(books.some(book=>!book.onlineUrl))throw new Error('本地书籍没有原站链接，无法追更。');
    const batch=refreshBatch({kind:'update',items:books.map(book=>({id:book.id,bookName:book.title,state:'pending',message:''}))});
    this.state.batch=batch;this.state.message='正在检查追更书籍…';
    return this.begin(async(generation,signal)=>{
      const {planUpdate,commitUpdate}=require('./updates.cjs');
      for(const item of batch.items){
        if(generation!==this.generation)return;
        item.state='downloading';item.message='检查目录';this.notify();let details,result;
        try{
          const book=this.library.book(item.id);
          details=await this.run({action:'details',url:book.onlineUrl},generation,signal);signal.throwIfAborted();
          const plan=planUpdate(this.library,item.id,details.data);let addition='';
          if(plan.chapters.length){
            item.message='下载新增 '+plan.chapters.length+' 章';this.notify();
            result=await this.run({action:'update',url:book.onlineUrl,start:plan.start,chapters:plan.chapters},generation,signal);
            if(result.data?.count!==plan.chapters.length)throw new Error('新增章节未完整返回，已保留原正文。');
            addition=await fsp.readFile(await this.checkedFile(result.work,result.data.file,'txt'),'utf8');
          }
          signal.throwIfAborted();const report=await commitUpdate(this.library,plan,addition,{signal});
          item.state='done';item.message=report.added?'新增 '+report.added+' 章':'已是最新';
        }catch(error){if(generation!==this.generation)return;item.state='failed';item.message=error.message;}
        finally{this.cleanupWork(details?.work);this.cleanupWork(result?.work);}
        if(generation!==this.generation)return;refreshBatch(batch);this.notify();
      }
      if(generation===this.generation)this.state.message='追更结束：完成 '+batch.done+' 本，失败 '+batch.failed+' 本。';
    });
  }
  retryFailed(){
    if(this.state.running)throw new Error('已有在线任务正在运行，请等待或先取消。');
    const failed=this.state.batch?.items.filter(item=>item.state==='failed')||[];
    if(failed.length)return this.state.batch.kind==='update'?this.update(failed.map(item=>item.id)):this.downloadMany(failed.map(item=>item.index),this.state.batch.format);
    if(this.state.error&&this.lastOperation?.kind==='download')return this.download(this.lastOperation.index,this.lastOperation.format);
    if(this.state.error&&this.state.keyword)return this.search(this.state.keyword);
    throw new Error('没有需要重试的任务。');
  }
}
module.exports={OnlineTasks,runEngine,decodeResult,filterResults,aggregateSources,assertPublicUrl,safeBookUrl,isPrivateHost};
