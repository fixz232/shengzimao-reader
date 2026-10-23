'use strict';
const fs=require('node:fs/promises'),path=require('node:path'),JSZip=require('jszip'),sax=require('sax');
const MAX_TEXT=128*1024*1024;
const EXTENSIONS=['.txt','.epub','.pdf','.docx','.md','.markdown','.html','.htm'];
function markdownText(text){
  return text.replace(/^---\r?\n[\s\S]*?\r?\n---\r?\n/,'').replace(/^\s{0,3}#{1,6}\s+/gm,'')
    .replace(/^\s*(\x60{3}|~~~).*$/gm,'').replace(/!\[([^\]]*)\]\([^)]*\)/g,(_,alt)=>alt?'[图片：'+alt+']':'')
    .replace(/\[([^\]]+)\]\([^)]*\)/g,'$1').replace(/(\*\*|__)([^\n]+?)\1/g,'$2').replace(/\x60([^\x60\n]+)\x60/g,'$1').trim();
}
async function docxText(filename){
  const zip=await JSZip.loadAsync(await fs.readFile(filename));
  if(Object.keys(zip.files).length>50000)throw new Error('DOCX 条目过多。');
  async function entry(name,limit){
    const file=zip.file(name);if(!file)return '';
    if(file._data?.uncompressedSize>limit)throw new Error('DOCX 解压内容超过安全上限。');
    const data=await file.async('nodebuffer');
    if(data.length>limit)throw new Error('DOCX 解压内容超过安全上限。');
    return data.toString('utf8');
  }
  const source=await entry('word/document.xml',MAX_TEXT);
  if(!source)throw new Error('DOCX 缺少正文，可能是损坏或加密文件。');
  const parser=sax.parser(true,{xmlns:true});let text='',visible=0;
  parser.onopentag=tag=>{if(tag.local==='t')visible++;if(tag.local==='tab')text+='\t';if(['br','cr'].includes(tag.local))text+='\n';};
  parser.ontext=value=>{if(visible)text+=value;};
  parser.onclosetag=tag=>{const name=tag.split(':').at(-1);if(name==='t')visible=Math.max(0,visible-1);if(['p','tr'].includes(name))text+='\n';if(name==='tc')text+='\t';};
  parser.write(source).close();
  const {parseStringPromise,processors}=require('xml2js'),core=await entry('docProps/core.xml',1024*1024);
  let meta={};if(core)try{meta=(await parseStringPromise(core,{explicitArray:false,tagNameProcessors:[processors.stripPrefix]})).coreProperties||{};}catch{}
  const plain=value=>typeof value==='string'?value:typeof value?._==='string'?value._:'';
  return {text:text.replace(/\n{3,}/g,'\n\n').trim(),title:plain(meta.title),author:plain(meta.creator)};
}
async function pdfText(filename){
  const pdfjs=await import('pdfjs-dist/legacy/build/pdf.mjs');
  const assets=path.dirname(require.resolve('pdfjs-dist/package.json'));
  const task=pdfjs.getDocument({data:new Uint8Array(await fs.readFile(filename)),cMapUrl:path.join(assets,'cmaps')+path.sep,cMapPacked:true,standardFontDataUrl:path.join(assets,'standard_fonts')+path.sep,isEvalSupported:false,useSystemFonts:true,disableFontFace:true,useWorkerFetch:false,stopAtErrors:true});
  try{
    const doc=await task.promise;
    if(doc.numPages>15000)throw new Error('PDF 页数超过 15000 页上限。');
    const parts=[];let length=0;
    for(let n=1;n<=doc.numPages;n++){
      const page=await doc.getPage(n),content=await page.getTextContent();let line='',lastY=null,lastRight=null,text='';
      for(const item of content.items){
        if(typeof item.str!=='string')continue;const y=item.transform?.[5],x=item.transform?.[4];
        if(lastY!=null&&Math.abs(y-lastY)>2){text+=line+'\n';line='';lastRight=null;}
        if(lastRight!=null&&x-lastRight>Math.max(2,(item.height||10)*.25))line+=' ';
        line+=item.str;
        if(item.hasEOL){text+=line+'\n';line='';lastY=null;lastRight=null;}else{lastY=y;lastRight=x+(item.width||0);}
      }
      text+=line;length+=Buffer.byteLength(text);
      if(length>MAX_TEXT)throw new Error('PDF 文字超过 128 MB 上限。');
      parts.push(text.trim());page.cleanup();
    }
    let meta={};try{meta=(await doc.getMetadata()).info||{};}catch{}
    const text=parts.join('\n\n');
    if(!text.trim())throw new Error('PDF 未检测到可提取文字；扫描件暂不支持 OCR。');
    return {text,title:typeof meta.Title==='string'?meta.Title:'',author:typeof meta.Author==='string'?meta.Author:''};
  }catch(error){if(error.name==='PasswordException')throw new Error('PDF 已加密，请先使用密码解锁后再导入。');throw error;}
  finally{await task.destroy();}
}
async function parseDocument(filename,ext,{readText,htmlText,encoding,options}){
  let book;
  if(ext==='.docx')book=await docxText(filename);
  else if(ext==='.pdf')book=await pdfText(filename);
  else{const raw=await readText(filename,encoding);book={text:['.html','.htm'].includes(ext)?htmlText(raw,options):markdownText(raw)};}
  if(!book.text.trim())throw new Error('文档没有可读取文字。');
  if(Buffer.byteLength(book.text)>MAX_TEXT)throw new Error('文档文字超过 128 MB 上限。');
  return {...book,title:book.title||path.basename(filename,ext),author:book.author||''};
}
module.exports={EXTENSIONS,parseDocument,markdownText};
