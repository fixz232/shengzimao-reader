'use strict';
const fs = require('node:fs/promises');
const fsSync = require('node:fs');
const path = require('node:path');
const iconv = require('iconv-lite');
const JSZip = require('jszip');
const { parseStringPromise, processors } = require('xml2js');
const { detectBom, invalidRatio, decode, decodeXml } = require('./encoding.cjs');

const MAX_TEXT = 128 * 1024 * 1024;
// 流式解码：iconv-lite decodeStream 分块处理，避免整文件驻留内存；多字节跨块由流内状态机保证。
async function readText(file, encoding) {
  const stat = await fs.stat(file);
  const head = Buffer.alloc(Math.min(stat.size, 4 << 20));
  const fd = await fs.open(file, 'r');
  try { await fd.read(head, 0, head.length, 0); } finally { await fd.close(); }
  const bom = detectBom(head);
  // 显式 encoding 优先，其次 BOM，再退化到按首块采样比例决策（UTF-8/GB18030）。
  let codec = encoding && iconv.encodingExists(encoding) ? encoding
    : bom ? bom.encoding
    : invalidRatio(head) > 0.01 ? 'gb18030' : 'utf8';
  const stream = fsSync.createReadStream(file, { highWaterMark: 1 << 20 });
  const decoder = iconv.getDecoder ? iconv.getDecoder(codec) : null;
  let text = '';
  for await (const chunk of stream) text += decoder ? decoder.write(chunk) : chunk.toString('utf8');
  if (decoder) text += decoder.end();
  return text;
}
const xml = text => parseStringPromise(text, { explicitArray: false, tagNameProcessors: [processors.stripPrefix] });
const array = item => item == null ? [] : Array.isArray(item) ? item : [item];
const value = item => typeof item === 'string' ? item : item?._ || '';
function htmlText(html, options = {}) {
  // Parse inert XML/HTML in a worker; never load book markup in a BrowserWindow.
  const sax = require('sax');
  const parser = sax.parser(false, { lowercase: true });
  let result = '';
  const stack = [];
  parser.onopentag = tag => {
    const attrs = tag.attributes;
    const footnote = /(?:^|\s)(?:footnote|endnote|doc-footnote|doc-endnote|noteref)(?:\s|$)/i.test([attrs['epub:type'], attrs.role, attrs.class].filter(Boolean).join(' '));
    const hidden = Boolean(stack.at(-1)?.hidden || ['script', 'style', 'head'].includes(tag.name) || ['skip','remove'].includes(options.footnoteMode) && footnote);
    stack.push({ name: tag.name, hidden });
    if (!hidden && ['p', 'div', 'br', 'h1', 'h2', 'h3', 'li', 'section'].includes(tag.name)) result += '\n';
    if (!hidden && tag.name === 'img' && !['skip','none'].includes(options.imageMode)) result += options.imageMode === 'placeholder' ? '[图片]' : attrs.alt ? `[图片：${attrs.alt}]` : '';
  };
  parser.onclosetag = name => {
    const node = stack.pop();
    if (!node?.hidden && ['p', 'div', 'h1', 'h2', 'h3', 'li', 'section'].includes(name)) result += '\n';
  };
  parser.ontext = text => { if (!stack.at(-1)?.hidden) result += text; };
  parser.oncdata = parser.ontext;
  const fragment = html.replace(/<\?xml[^>]*\?>/gi, '').replace(/<!DOCTYPE[^>]*>/gi, '').replace(/&nbsp;/g, ' ');
  parser.write('<reader-root>' + fragment + '</reader-root>').close();
  return result.replace(/[\t ]+\n/g, '\n').replace(/\n{3,}/g, '\n\n').trim();
}
async function parseBook(filename, encoding, options = {}) {
  const ext = path.extname(filename).toLowerCase();
  if (!require('./documents.cjs').EXTENSIONS.includes(ext)) throw new Error('只支持 TXT、EPUB、PDF、DOCX、Markdown 和 HTML。旧版 DOC 请先另存为 DOCX。');
  const stat = await fs.stat(filename);
  if (!stat.isFile() || !stat.size) throw new Error('文件为空或不是普通文件。');
  if (stat.size > (ext === '.txt' ? MAX_TEXT : 512 * 1024 * 1024)) throw new Error('文件超过安全导入上限（TXT 128MB / EPUB 512MB）。');
  if (ext === '.txt') return { title: path.basename(filename, ext), author: '', text: (await readText(filename, encoding)).replace(/\r\n?/g, '\n') };
  if (ext !== '.epub') return require('./documents.cjs').parseDocument(filename,ext,{readText,htmlText,encoding,options});
  const buffer = await fs.readFile(filename);
  const zip = await JSZip.loadAsync(buffer);
  if (Object.keys(zip.files).length > 50000) throw new Error('EPUB 条目过多。');
  let expanded = 0;
  async function entry(name) {
    const file = zip.file(name);
    if (!file) throw new Error(`EPUB 缺少文件：${name}`);
    // JSZip 私有字段可能缺失，缺失时降级为解压后按 Buffer 长度校验。
    const size = file._data?.uncompressedSize;
    if (Number.isFinite(size)) {
      if (size > 16 * 1024 * 1024 || (expanded += size) > MAX_TEXT) throw new Error('EPUB 解压后的正文超过安全上限。');
      return file.async('nodebuffer').then(data => {
        if (data.length > 16 * 1024 * 1024 || (expanded += data.length - size) > MAX_TEXT) throw new Error('EPUB 解压后的正文超过安全上限。');
        return data;
      });
    }
    const data = await file.async('nodebuffer');
    if (data.length > 16 * 1024 * 1024 || (expanded += data.length) > MAX_TEXT) throw new Error('EPUB 解压后的正文超过安全上限。');
    return data;
  }
  // href 解码失败时回退原值；反斜杠按 posix 分隔符处理。
  const resolveHref = href => {
    const clean = String(href || '').split('#')[0].replace(/\\/g, '/');
    let decoded = clean;
    try { decoded = decodeURIComponent(clean); } catch { /* 保留原值 */ }
    return path.posix.normalize(path.posix.join(path.posix.dirname(packagePath), decoded));
  };
  const container = await xml(decodeXml(await entry('META-INF/container.xml')));
  const packagePath = array(container.container?.rootfiles?.rootfile)[0]?.$?.['full-path'];
  if (!packagePath || packagePath.includes('..')) throw new Error('EPUB 缺少 package 路径。');
  const doc = (await xml(decodeXml(await entry(packagePath)))).package;
  const items = new Map(array(doc.manifest?.item).map(item => [item.$?.id, item.$]));
  const parts = [];
  for (const ref of array(doc.spine?.itemref)) {
    const item = items.get(ref.$?.idref);
    if (!item || ref.$?.linear === 'no' || !/html|xml/.test(item['media-type'])) continue;
    const target = resolveHref(item.href);
    if (target.startsWith('../') || target.startsWith('/') || target.split('/').includes('..')) throw new Error('EPUB 正文路径非法。');
    const part = htmlText(decodeXml(await entry(target)), options);
    if (part) parts.push(part);
  }
  const text = parts.join('\n\n');
  if (!text.trim()) throw new Error('EPUB 没有可读取正文（可能有 DRM 或只有图片）。');
  let cover = '';
  const coverId = array(doc.metadata?.meta).find(item => item.$?.name === 'cover')?.$?.content;
  const coverItem = items.get(coverId) || [...items.values()].find(item => /(?:^|\s)cover-image(?:\s|$)/.test(item.properties || ''));
  if (coverItem && /^image\/(png|jpeg|webp)$/.test(coverItem['media-type'])) {
    const target = resolveHref(coverItem.href);
    const file = !target.startsWith('../') && !target.startsWith('/') && !target.split('/').includes('..') && zip.file(target);
    const coverSize = file?._data?.uncompressedSize;
    if (file && (!Number.isFinite(coverSize) || coverSize <= 1400000)) cover = `data:${coverItem['media-type']};base64,${await file.async('base64')}`;
  }
  return { title: value(array(doc.metadata?.title)[0]) || path.basename(filename, ext), author: value(array(doc.metadata?.creator)[0]), text, cover };
}
module.exports = { decode, decodeXml, readText, parseBook, htmlText };
