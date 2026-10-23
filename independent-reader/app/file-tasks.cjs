'use strict';
const fs = require('node:fs/promises');
const path = require('node:path');
const crypto = require('node:crypto');
const JSZip = require('jszip');
const { Worker, isMainThread, parentPort, workerData } = require('node:worker_threads');
const { parseBook } = require('./importer.cjs');
const { decode } = require('./encoding.cjs');
const escape = value => String(value).replace(/[&<>"']/g, char => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&apos;'}[char]));
// 过滤 XML 非法控制字符（保留 \t\n\r）。
const stripXmlControl = text => text.replace(/[\x00-\x08\x0B\x0C\x0E-\x1F]/g,'');
async function makeEpub(book) {
  const zip = new JSZip(), title = escape(book.title), id = crypto.randomUUID();
  zip.file('mimetype', 'application/epub+zip', { compression: 'STORE' });
  zip.file('META-INF/container.xml', '<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OPS/book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>');
  zip.file('OPS/book.opf', `<?xml version="1.0" encoding="UTF-8"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="book-id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="book-id">urn:uuid:${id}</dc:identifier><dc:title>${title}</dc:title><dc:language>zh-CN</dc:language><dc:creator>${escape(book.author||'')}</dc:creator><meta property="dcterms:modified">${new Date().toISOString().replace(/\.\d{3}Z$/, 'Z')}</meta></metadata><manifest><item id="text" href="text.xhtml" media-type="application/xhtml+xml"/><item id="nav" href="nav.xhtml" properties="nav" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="text"/></spine></package>`);
  zip.file('OPS/nav.xhtml', `<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>${title}</title></head><body><nav epub:type="toc"><h1>目录</h1><ol><li><a href="text.xhtml">${title}</a></li></ol></nav></body></html>`);
  zip.file('OPS/text.xhtml', `<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>${title}</title></head><body>${stripXmlControl(book.text).split('\n').map(line=>'<p>'+escape(line)+'</p>').join('\n')}</body></html>`);
  return zip.generateAsync({ type:'nodebuffer', compression:'DEFLATE' });
}
async function convertFile(input, output, options = {}) {
  if (path.resolve(input).toLowerCase() === path.resolve(output).toLowerCase()) throw new Error('输出文件不能覆盖原小说。');
  const ext = path.extname(output).toLowerCase();
  if (!['.epub', '.txt'].includes(ext)) throw new Error('输出格式仅支持 TXT 和 EPUB。');
  const book = await parseBook(input, undefined, options);
  const bytes = ext === '.epub' ? await makeEpub(book) : Buffer.from(book.text, 'utf8');
  const temp = output + '.' + crypto.randomUUID() + '.tmp';
  try { await fs.writeFile(temp, bytes, { flag:'wx' }); await fs.rename(temp, output); }
  finally { await fs.unlink(temp).catch(()=>{}); }
  return { file:output };
}
async function searchBooks(books, keyword) {
  keyword = String(keyword||'').trim();
  if (!keyword || keyword.length>120) throw new Error('请输入 1–120 个字符的正文关键词。');
  const results = [], pattern = new RegExp(keyword.replace(/[.*+?^${}()|[\]\\]/g,'\\$&'),'giu');
  for (const book of books) {
    let text;
    try { const stat = await fs.stat(book.path); if (stat.size>128*1024*1024) continue; text=decode(await fs.readFile(book.path)); }
    catch(error) { if(error.code==='ENOENT') continue; throw error; }
    pattern.lastIndex=0; let match;
    while((match=pattern.exec(text)) && results.length<100) results.push({id:book.id,title:book.title,position:match.index,snippet:text.slice(Math.max(0,match.index-35),match.index+keyword.length+60).replace(/\s+/g,' ')});
    if(results.length>=100) break;
  }
  return results;
}
function taskTimeout(data) {
  // 按输入总大小动态计算：每 10MB +30s，下限 120s，上限 20min。
  let total = (data.books?.reduce((sum,book)=>sum+(book.size||0),0)) || 0;
  if(!total&&data.input)try{total=require('node:fs').statSync(data.input).size;}catch{}
  return Math.min(20*60000, Math.max(120000, Math.ceil(total/(10*1024*1024))*30000));
}
function runFileTask(data) {
  return new Promise((resolve,reject)=>{
    const worker=new Worker(__filename,{workerData:data});
    let settled=false;
    const finish=fn=>value=>{if(settled)return;settled=true;clearTimeout(timer);fn(value);};
    const timer=setTimeout(()=>{settled=true;clearTimeout(timer);worker.terminate();reject(new Error('文件处理超时，请缩小范围重试。'));},taskTimeout(data));
    worker.once('message',finish(value=>value.error?reject(new Error(value.error)):resolve(value.result)));
    worker.once('error',finish(reject));
    worker.once('exit',code=>finish(()=>{reject(new Error(`文件处理进程退出：${code}`));})());
  });
}
if(!isMainThread) (async()=>workerData.action==='search'?searchBooks(workerData.books,workerData.keyword):convertFile(workerData.input,workerData.output,workerData.options))().then(result=>parentPort.postMessage({result})).catch(error=>parentPort.postMessage({error:error.message}));
module.exports={makeEpub,convertFile,searchBooks,runFileTask};
