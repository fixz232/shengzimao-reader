'use strict';

const fs = require('node:fs');
const fsp = require('node:fs/promises');
const path = require('node:path');
const crypto = require('node:crypto');
const { makeEpub } = require('./file-tasks.cjs');
const { htmlText } = require('./importer.cjs');
const { filterResults } = require('./search-matching.cjs');
const { decodeChapterFont } = require('./tomato-font.cjs');
const { publicEditionFor, downloadPublicEdition } = require('./public-editions.cjs');

const SEARCH_URL = 'https://novel.snssdk.com/api/novel/channel/homepage/search/search/v1/';
const PAGE_HOSTS = new Set(['fanqienovel.com', 'www.fanqienovel.com', 'fqnovel.com', 'www.fqnovel.com']);
const API_HOSTS = new Set([...PAGE_HOSTS, 'novel.snssdk.com']);
const DEFAULT_TIMEOUT = 15000;

const text = value => String(value ?? '').replace(/\u003c/g, '<').replace(/\u003e/g, '>').replace(/\u0026/g, '&').trim();
function decodeHtml(value) {
  return text(value).replace(/<br\s*\/?>/gi, '\n').replace(/<[^>]+>/g, ' ').replace(/&nbsp;/gi, ' ').replace(/&quot;/gi, '"').replace(/&#39;|&apos;/gi, "'").replace(/&amp;/gi, '&').replace(/&#(\d+);/g, (_, n) => String.fromCodePoint(Number(n))).replace(/\s+\n/g, '\n').replace(/\n\s+/g, '\n').replace(/[ \t]{2,}/g, ' ').trim();
}
function safeJson(value) { try { return JSON.parse(value); } catch { return null; } }
function parseScriptJson(html, id) {
  const match = String(html).match(new RegExp(`<script[^>]*id=["']${id}["'][^>]*>([\\s\\S]*?)</script>`, 'i'));
  return match ? safeJson(match[1].trim()) : null;
}
function parseInitialState(html) {
  const script = [...String(html).matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/gi)].map(match => match[1]).find(value => /(?:window\.)?__INITIAL_STATE__\s*=/.test(value));
  if (!script) return null;
  const assignment = script.match(/(?:window\.)?__INITIAL_STATE__\s*=\s*/);
  const start = assignment.index + assignment[0].length;
  if (script[start] !== '{') return null;
  let depth = 0, quoted = false, escaped = false;
  for (let index = start; index < script.length; index++) {
    const char = script[index];
    if (quoted) { if (escaped) escaped = false; else if (char === '\\') escaped = true; else if (char === '"') quoted = false; }
    else if (char === '"') quoted = true;
    else if (char === '{') depth++;
    else if (char === '}' && --depth === 0) return safeJson(script.slice(start, index + 1));
  }
  return null;
}
function walk(value, visitor, seen = new Set()) {
  if (!value || typeof value !== 'object' || seen.has(value)) return undefined;
  seen.add(value); const found = visitor(value); if (found !== undefined) return found;
  if (Array.isArray(value)) for (const item of value) { const result = walk(item, visitor, seen); if (result !== undefined) return result; }
  else for (const item of Object.values(value)) { const result = walk(item, visitor, seen); if (result !== undefined) return result; }
  return undefined;
}
const first = (object, keys) => { for (const key of keys) if (object && object[key] != null && String(object[key]).trim() !== '') return object[key]; return ''; };
const statusText = value => ({ '0': '已完结', '1': '连载中' }[String(value)] || text(value));
function categoryText(value) {
  const categories = typeof value === 'string' ? safeJson(value) : value;
  return Array.isArray(categories) ? categories.map(item => text(item.Name || item.name)).filter(Boolean).join(' / ') : text(value);
}
function updateTime(value) {
  if (!/^\d{10,13}$/.test(String(value))) return text(value);
  const timestamp = Number(value) * (String(value).length === 10 ? 1000 : 1);
  return new Date(timestamp).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false });
}
function bookId(value) { const match = String(value || '').match(/(?:\/page\/|\/book\/|bookId=|book_id=)(\d+)/i) || String(value || '').trim().match(/^(\d+)$/); return match?.[1] || ''; }
function tomatoUrl(id) { if (!/^\d{3,32}$/.test(String(id))) throw new Error('番茄书籍 ID 无效。'); return `https://fanqienovel.com/page/${id}`; }
function allowedUrl(value, kind = 'page') { try { const url = new URL(value); const hosts = kind === 'api' ? API_HOSTS : PAGE_HOSTS; return url.protocol === 'https:' && hosts.has(url.hostname.toLowerCase()); } catch { return false; } }
function assertAllowedUrl(value, kind = 'page') { if (!allowedUrl(value, kind)) throw new Error('番茄请求地址不在允许的公开域名内。'); }

function normalizeSearchResponse(value) {
  const array = walk(value, item => {
    if (!Array.isArray(item) || !item.length) return undefined;
    return item.some(entry => entry && typeof entry === 'object' && (first(entry, ['book_id', 'bookId', 'bookIdStr', 'title', 'book_name']))) ? item : undefined;
  }) || [];
  const seen = new Set(); const result = [];
  for (const item of array) {
    const id = String(first(item, ['book_id', 'bookId', 'bookIdStr', 'id']) || bookId(first(item, ['url', 'book_url']))).trim();
    const title = text(first(item, ['title', 'book_name', 'bookName', 'name'])); if (!/^\d{3,32}$/.test(id) || !title || seen.has(id) || ['4', '905'].includes(String(item.genre))) continue;
    seen.add(id); result.push({ bookName: title.slice(0, 250), author: text(first(item, ['author', 'author_name', 'authorName'])).slice(0, 120), category: text(first(item, ['category', 'category_name', 'categoryName'])).slice(0, 120), status: statusText(first(item, ['creation_status', 'creationStatus', 'status', 'serial_status', 'serialStatus'])).slice(0, 80), wordCount: text(first(item, ['word_count', 'wordCount', 'words'])).slice(0, 40), intro: decodeHtml(first(item, ['intro', 'description', 'abstract'])).slice(0, 10000), cover: text(first(item, ['cover', 'thumbUri', 'cover_url', 'thumb_url', 'image'])).slice(0, 2000), url: tomatoUrl(id), bookId: id, sourceId: 'tomato', sourceName: '番茄小说', matchType: '番茄搜索' });
  }
  return result;
}

function normalizeBookObject(value, id) {
  const object = walk(value, item => item && typeof item === 'object' && (first(item, ['bookName', 'book_name', 'title']) && (first(item, ['author', 'author_name', 'authorName']) || first(item, ['chapterCount', 'chapter_count']))) ? item : undefined) || {};
  const book = { bookName: text(first(object, ['bookName', 'book_name', 'title', 'name'])), author: text(first(object, ['author', 'author_name', 'authorName'])), category: text(first(object, ['category', 'categoryV2', 'category_name', 'categoryName'])), status: text(first(object, ['creation_status', 'creationStatus', 'status', 'serial_status', 'serialStatus'])), wordCount: text(first(object, ['wordCount', 'wordNumber', 'word_count', 'words'])), lastUpdateTime: text(first(object, ['lastUpdateTime', 'lastPublishTime', 'last_update_time', 'updateTime', 'updated_at'])), latestChapter: text(first(object, ['latestChapter', 'lastChapterTitle', 'lastChapterName', 'last_chapter_name'])), intro: decodeHtml(first(object, ['intro', 'abstract', 'book_desc', 'description'])), cover: text(first(object, ['cover', 'thumbUri', 'cover_url', 'thumb_url', 'image'])), url: tomatoUrl(id), bookId: id, sourceId: 'tomato', sourceName: '番茄小说' };
  const count = Number(first(object, ['chapterCount', 'chapter_count', 'chapterTotal', 'chapter_total'])); if (Number.isFinite(count) && count > 0) book.chapterCount = count;
  book.status = statusText(first(object, ['creation_status', 'creationStatus', 'status', 'serial_status', 'serialStatus']));
  book.category = categoryText(book.category);
  book.lastUpdateTime = updateTime(book.lastUpdateTime);
  return book;
}
function parseBookHtml(html, id) {
  // 番茄页面既有脚本 id 形式，也有 `window.__INITIAL_STATE__ = …` 赋值形式。
  // 两种形式都只解析 JSON，不执行页面脚本。
  const initial = parseScriptJson(html, '__INITIAL_STATE__') || parseInitialState(html);
  const next = parseScriptJson(html, '__NEXT_DATA__') || initial;
  let book = normalizeBookObject(next || {}, id);
  const pick = (selector, fallback = '') => { const match = String(html).match(selector); return match ? decodeHtml(match[1]) : fallback; };
  if (!book.bookName) book.bookName = pick(/<h1[^>]*>([\s\S]*?)<\/h1>/i);
  if (!book.author) book.author = pick(/(?:作者|author)[^<]{0,40}<[^>]*>([\s\S]*?)<\/[^>]+>/i);
  if (!book.intro) book.intro = pick(/(?:简介|description|book-desc)[^>]*>([\s\S]*?)<\/[^>]+>/i);
  if (!book.cover) { const image = String(html).match(/<(?:img)[^>]+(?:src|data-src)=["']([^"']+)["']/i); book.cover = image?.[1] || ''; }
  const tags = [...String(html).matchAll(/class=["'][^"']*(?:tag|label)[^"']*["'][^>]*>([^<]+)</gi)].map(item => decodeHtml(item[1])).filter(Boolean);
  if (tags.length) book.tags = [...new Set(tags)].slice(0, 20);
  return book;
}

function chapterArray(value) {
  const keys = ['chapterList', 'chapter_list', 'chapters', 'item_list', 'items', 'list'];
  const direct = walk(value, item => { if (!item || typeof item !== 'object') return undefined; for (const key of keys) if (Array.isArray(item[key])) return item[key]; return undefined; });
  if (Array.isArray(direct)) return direct;
  const volume = walk(value, item => item && Array.isArray(item.chapterListWithVolume) ? item.chapterListWithVolume.flatMap(group => Array.isArray(group) ? group : group?.chapters || group?.chapterList || []) : undefined);
  return Array.isArray(volume) ? volume : [];
}
function parseDirectoryResponse(value, bookIdValue) {
  const chapters = [], seen = new Set();
  for (const item of chapterArray(value)) {
    if (!item || typeof item !== 'object') continue;
    const id = String(first(item, ['item_id', 'itemId', 'chapter_id', 'chapterId', 'id'])).trim();
    const title = decodeHtml(first(item, ['title', 'chapter_title', 'chapterTitle', 'chapter_name', 'name'])); if (!id || !title || seen.has(id)) continue;
    seen.add(id); const rawUrl = first(item, ['url', 'chapter_url', 'chapterUrl']); const url = rawUrl && allowedUrl(rawUrl) ? rawUrl : `https://fanqienovel.com/reader/${id}`;
    const locked = ['needPay', 'isChapterLock', 'isPaidPublication', 'isPaidStory'].some(key => [true, 1, '1', 'true'].includes(item[key]));
    chapters.push({ id, title: title.slice(0, 250), url, ...(locked ? { locked: true } : {}) });
  }
  return chapters;
}
function parseChapterHtml(html) {
  // Match class tokens, not the outer muye-reader-content-16 font-size wrapper.
  // Balance the selected element so nested divs and every paragraph are retained.
  const source = String(html), elements = [...source.matchAll(/<(div|section|article)\b[^>]*>/gi)];
  const content = elements.find(match => {
    const classes = match[0].match(/\bclass\s*=\s*["']([^"']*)["']/i)?.[1] || '';
    return /(?:^|\s)(?:muye-reader-content|reader-content|chapter-content)(?:\s|$)/i.test(classes) || /\bid\s*=\s*["']content["']/i.test(match[0]);
  }) || elements.find(match => match[1].toLowerCase() === 'article');
  if (content) {
    const start = content.index + content[0].length, tags = new RegExp(`</?${content[1]}\\b[^>]*>`, 'gi');
    tags.lastIndex = start;
    let depth = 1, match;
    while ((match = tags.exec(source))) {
      depth += match[0].startsWith('</') ? -1 : /\/\s*>$/.test(match[0]) ? 0 : 1;
      if (!depth) return htmlText(source.slice(start, match.index));
    }
  }
  return '';
}

async function fetchText(url, { timeout = DEFAULT_TIMEOUT, kind = 'page', signal } = {}) {
  assertAllowedUrl(url, kind); const controller = new AbortController(); const timer = setTimeout(() => controller.abort(), Math.max(1000, timeout));
  const onAbort = () => controller.abort(); signal?.addEventListener('abort', onAbort, { once: true }); if (signal?.aborted) controller.abort();
  try { const response = await fetch(url, { headers: { accept: kind === 'api' ? 'application/json,text/plain,*/*' : 'text/html,application/xhtml+xml', 'user-agent': 'Mozilla/5.0 (compatible; ShengzimaoReader/0.5)' }, signal: controller.signal }); if (!response.ok) throw Object.assign(new Error(`番茄请求失败（HTTP ${response.status}）。`), { status: response.status }); return await response.text(); }
  catch (error) { if (error.name === 'AbortError') throw new Error(signal?.aborted ? '番茄任务已取消。' : '番茄请求超时。'); throw error; }
  finally { clearTimeout(timer); signal?.removeEventListener('abort', onAbort); }
}
async function fetchJson(url, options = {}) {
  const body = await fetchText(url, { ...options, kind: 'api' });
  if (!body.trim()) throw new Error('番茄暂未返回搜索或目录数据，请稍后重试，也可打开番茄官网核对。');
  const value = safeJson(body);
  if (!value || typeof value !== 'object') throw new Error('番茄服务暂不可用或需要网页验证，请稍后重试或打开番茄官网。');
  if (value.code != null && Number(value.code) !== 0) throw new Error('番茄请求未成功，请稍后重试或打开番茄官网核对。');
  return value;
}

class TomatoTasks {
  constructor({ profile, notify = () => {}, importPaths } = {}) { this.profile = profile; this.notify = notify; this.importPaths = importPaths; this.state = { running: false, keyword: '', results: [], message: '', error: '', details: null, revision: 0 }; this.controller = null; this.pending = Promise.resolve(); }
  snapshot() { return { ...this.state, results: this.state.results.map(item => ({ ...item })), details: this.state.details ? { ...this.state.details, book: { ...this.state.details.book }, toc: this.state.details.toc.map(item => ({ ...item })) } : null }; }
  start(task) { if (this.state.running) throw new Error('已有番茄任务正在运行，请先取消。'); this.state.running = true; this.state.error = ''; this.controller = new AbortController(); const controller = this.controller; this.pending = Promise.resolve().then(() => task(controller.signal)).catch(error => { if (this.controller === controller && !controller.signal.aborted) { this.state.error = error.message; this.state.message = '番茄任务未完成，可重试。'; } }).finally(() => { if (this.controller === controller) { this.state.running = false; this.controller = null; this.notify(); } }); this.notify(); return this.snapshot(); }
  cancel() { this.controller?.abort(); this.state.message = '番茄任务已取消。'; this.state.running = false; this.notify(); return this.snapshot(); }
  search(keyword) {
    keyword = String(keyword || '').trim();
    if (!keyword || keyword.length > 120) throw new Error('请输入 1–120 个字符的番茄书名或作者。');
    if (this.state.running) throw new Error('已有番茄任务正在运行，请先取消。');
    this.state.keyword = keyword; this.state.results = []; this.state.details = null; this.state.message = '正在搜索番茄小说…'; this.state.revision++;
    return this.start(async signal => {
      const id = bookId(keyword);
      let results;
      if (id) {
        const url = tomatoUrl(id), html = await fetchText(url, { signal });
        const book = parseBookHtml(html, id);
        if (!book.bookName) throw new Error('没有读取到这本番茄小说，请核对书籍 ID 后重试。');
        results = [book];
      } else {
        const params = new URLSearchParams({ device_platform: 'android', parent_enterfrom: 'novel_channel_search.tab.', offset: '0', aid: '1967', q: keyword });
        const value = await fetchJson(`${SEARCH_URL}?${params}`, { signal });
        results = filterResults(keyword, normalizeSearchResponse(value)).slice(0, 100);
      }
      if (signal.aborted) return;
      this.state.results = results;
      this.state.message = results.length ? `找到 ${results.length} 本番茄小说。` : '没有找到匹配的番茄小说，请换个关键词。';
    });
  }
  async details(index) {
    if (!Number.isInteger(index) || !this.state.results[index]) throw new Error('请从番茄搜索结果选择书籍。');
    if (this.state.running) throw new Error('已有番茄任务正在运行，请先取消。');
    const item = this.state.results[index];
    this.state.details = null; this.state.message = '正在读取番茄详情和目录…';
    let result;
    this.start(async signal => {
      const book = { ...item }; delete book.unavailableReason; delete book.detailWarning;
      try {
        const parsed = parseBookHtml(await fetchText(item.url, { signal }), item.bookId);
        Object.assign(book, Object.fromEntries(Object.entries(parsed).filter(([, value]) => value !== '' && value != null)));
      } catch (error) {
        if (error.status !== 404) throw error;
        book.detailWarning = '番茄电脑网页详情暂不可用（HTTP 404），已保留搜索信息和公开目录。正文能否下载以章节页面为准。';
      }
      const data = await fetchJson(`https://fanqienovel.com/api/reader/directory/detail?bookId=${encodeURIComponent(item.bookId)}`, { signal });
      const toc = parseDirectoryResponse(data, item.bookId);
      if (!toc.length) throw new Error('番茄源站未返回有效目录。');
      if (signal.aborted) return;
      book.chapterCount = toc.length;
      const edition = publicEditionFor(book);
      if (edition) book.publicEditionNotice = edition.notice;
      result = { book, toc }; this.state.details = result;
      delete item.unavailableReason; delete item.detailWarning; Object.assign(item, book);
      this.state.message = `已读取《${book.bookName}》的 ${toc.length} 章目录。${book.detailWarning || ''}`;
    });
    await this.pending;
    if (!result) throw new Error(this.state.error || '番茄详情读取失败或任务已取消。');
    return { book: { ...result.book }, toc: result.toc.map(chapter => ({ ...chapter })) };
  }
  async download(index, format = 'txt') {
    if (!['txt', 'epub'].includes(format)) throw new Error('番茄入库格式只支持 TXT 或 EPUB。');
    if (!Number.isInteger(index) || !this.state.results[index]) throw new Error('请从番茄搜索结果选择书籍。');
    if (this.state.running) throw new Error('已有番茄任务正在运行，请先取消。');
    const item = this.state.results[index];
    const details = this.state.details?.book.bookId === item.bookId ? this.snapshot().details : await this.details(index);
    this.state.message = '正在下载番茄公开章节…';
    this.start(async signal => {
      const edition = publicEditionFor(details.book);
      if (details.book.unavailableReason && !edition) throw new Error(details.book.unavailableReason);
      const unavailable = reason => {
        item.unavailableReason = reason;
        if (this.state.details?.book.bookId === item.bookId) this.state.details.book.unavailableReason = reason;
        throw new Error(reason);
      };
      let parts = [], toc = details.toc, sourceName = '番茄小说', onlineUrl = item.url, tracking = true;
      const loadPublicEdition = async () => {
        this.state.message = edition.notice; this.notify();
        const downloaded = await downloadPublicEdition(edition, { signal, onProgress: (done, total, title) => {
          this.state.message = `正在下载公开文集（${done}/${total}）：${title}`; this.notify();
        } });
        signal.throwIfAborted();
        ({ parts, toc, sourceName, onlineUrl } = downloaded); tracking = false;
        delete item.unavailableReason;
        if (this.state.details?.book.bookId === item.bookId) {
          delete this.state.details.book.unavailableReason;
          this.state.details.book.downloadSourceName = sourceName;
          this.state.details.book.chapterCount = toc.length;
          this.state.details.toc = toc;
        }
      };
      if (edition && details.book.downloadSourceName === edition.sourceName) await loadPublicEdition();
      else try { for (const chapter of details.toc) {
        signal.throwIfAborted();
        if (chapter.locked) throw new Error(`番茄章节「${chapter.title}」为付费或锁定章节，未开放公开正文。可使用聚合搜书核对其他书源。`);
        let html;
        try { html = await fetchText(chapter.url, { signal }); }
        catch (error) {
          if (error.status !== 404 || signal.aborted) throw error;
          error.chapterTitle = chapter.title;
          throw error;
        }
        signal.throwIfAborted();
        const body = decodeChapterFont(html, parseChapterHtml(html));
        if (!body || body.trim() === chapter.title.trim()) throw new Error(`番茄章节「${chapter.title}」没有公开正文，已停止下载。请使用聚合搜书查找其他书源。`);
        if (/[\uE000-\uF8FF]/u.test(body)) unavailable(`番茄章节「${chapter.title}」使用网页专用字体编码，无法作为普通正文入库。请使用聚合搜书查找其他书源，或在官网阅读。`);
        parts.push(`${chapter.title}\n\n${body}`);
        this.state.message = `正在下载番茄章节（${parts.length}/${details.toc.length}）：${chapter.title}`; this.notify();
      } } catch (error) {
        if (error.status !== 404 || signal.aborted) throw error;
        // The alternate edition is independently public. Do not request locked
        // Fanqie chapters or carry Fanqie's access flags over to a different site.
        if (!edition) unavailable(`番茄章节「${error.chapterTitle}」未提供电脑网页正文（HTTP 404）。请使用聚合搜书查找其他书源，或前往番茄 App 核对。`);
        await loadPublicEdition();
      }
      signal.throwIfAborted();
      const dir = path.join(this.profile || process.cwd(), 'tomato-jobs');
      await fsp.mkdir(dir, { recursive: true });
      const title = details.book.bookName || '番茄小说', content = `《${title}》\n作者：${details.book.author || '未知'}\n${tracking ? '' : `来源：${sourceName}\n${onlineUrl}\n版本：公开文集版本，不含番茄版权页、分卷扉页。\n`}\n${parts.join('\n\n')}`;
      const file = path.join(dir, `${crypto.randomUUID()}.${format}`);
      try {
        const bytes = format === 'epub' ? await makeEpub({ title, author: details.book.author || '', text: content }) : content;
        signal.throwIfAborted(); await fsp.writeFile(file, bytes, 'utf8'); signal.throwIfAborted();
        if (!this.importPaths) throw new Error('书库导入服务不可用，请重试。');
        const report = await this.importPaths([file], { title, sourceName, onlineUrl, author: details.book.author, intro: details.book.intro, category: details.book.category, status: details.book.status, latestChapter: tracking ? details.book.latestChapter : toc.at(-1).title, lastUpdateTime: tracking ? details.book.lastUpdateTime : '', onlineChapters: toc, tracking }, { signal });
        signal.throwIfAborted();
        if (report?.errors?.length) throw new Error(`番茄下载后入库失败：${report.errors.map(error => typeof error === 'string' ? error : error.message).join('；')}`);
        if (!report?.imported && !report?.duplicates) throw new Error('番茄下载后未能入库，请重试。');
        this.state.message = report.imported ? `${tracking ? '番茄' : '公开文集'}下载完成，${report.imported} 本已导入${tracking ? '。' : `（${toc.length} 篇，来源：${sourceName}）。`}` : '这本书籍已在书库中，已跳过重复导入。';
      } finally { await fsp.unlink(file).catch(() => {}); }
    });
    await this.pending;
    return this.snapshot();
  }
}

module.exports = { TomatoTasks, SEARCH_URL, allowedUrl, assertAllowedUrl, tomatoUrl, normalizeSearchResponse, parseBookHtml, parseDirectoryResponse, parseChapterHtml, chapterArray, decodeHtml };
