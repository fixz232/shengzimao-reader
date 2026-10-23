'use strict';
const fs = require('node:fs');
const fsp = require('node:fs/promises');
const path = require('node:path');
const crypto = require('node:crypto');
const { Worker } = require('node:worker_threads');

const defaults = () => ({ version: 1, readerDefaultsVersion:1, books: [], activeId: '', settings: {
  fontSize:13, lineSize:20, autoFitWidth:true, lineCount:3, multiLine:false, opacity:1, alwaysOnTop:false,
  fontFamily:'Microsoft YaHei', fontColor:'#20242c', background:'#dee1e6', backgroundOpacity:.8,
  fontOpacity:1, fontBold:false, highlightColor:'#ff1010', hideOnBlur:false, mouseControl:true,
  mouseAutoShow:true, fakeAddress:true, timeoutEnabled:false, timeoutMinutes:5,
  bossKeyEnabled:true, bossKey:'CommandOrControl+Shift+H', hideTaskbar:false, resizable:true,
  windowWidth:420, windowHeight:24, windowTitle:'圣子猫单行阅读器', wheelSensitivity:'normal',
  lineScroll:false, progressLine:false, autoRead:false, autoReadSeconds:4, wheelPaused:false,
  theme:'classic', view:'grid', footnoteMode:'inline', imageMode:'alt', searchHistory:[], savedFilters:[], disabledPlugins:[],
  ttsEnabled:false, ttsRate:1, ttsVolume:1, autoUpdate:false,
  readingMode:'stealth', normalFontSize:22, normalTheme:'light'
} });
const clamp = (n, lo, hi) => Math.min(hi, Math.max(lo, Number(n) || 0));
const hash = input => crypto.createHash('sha256').update(input).digest('hex');
const stamp = () => new Date().toISOString().replace(/[:.]/g, '-');
const MAX_IMPORT_WORKERS = 3;
// 每 10MB +30s，下限 120s，上限 20min。
const importTimeout = size => Math.min(20 * 60000, Math.max(120000, Math.ceil(size / (10 * 1024 * 1024)) * 30000));
class Library {
  constructor(root) {
    this.root = path.resolve(root);
    fs.mkdirSync(path.join(this.root, 'books'), { recursive: true });
    this.file = path.join(this.root, 'library.json');
    this.state = defaults();
    this.recovery = '';
    if (fs.existsSync(this.file)) {
      try { this.state = this.readState(this.file); }
      catch (error) {
        fs.copyFileSync(this.file, `${this.file}.damaged-${stamp()}`);
        try { this.state = this.readState(`${this.file}.bak`); this.recovery = '主书库损坏，已从备份恢复；损坏文件已保留。'; this.skipBackupOnce = true; }
        catch { throw new Error(`独立版书库与备份均无法解析，已停止写入：${error.message}`); }
      }
    }
    this.cache = null;
    // 在途导入去重（按绝对路径）+ worker 并发上限排队。
    this.importing = new Map();
    this.activeImports = 0;
    this.importQueue = [];
  }
  // 限制同时运行的导入 worker 数；同一路径的并发导入复用同一 Promise。
  async runImport(filename, task) {
    const key = path.resolve(filename).toLowerCase();
    if (this.importing.has(key)) return this.importing.get(key);
    const run = (async () => {
      while (this.activeImports >= MAX_IMPORT_WORKERS) await new Promise(resolve => this.importQueue.push(resolve));
      this.activeImports++;
      try { return await task(); }
      finally {
        this.activeImports--;
        this.importQueue.shift()?.();
        this.importing.delete(key);
      }
    })();
    this.importing.set(key, run);
    return run;
  }
  readState(file) {
    const state = JSON.parse(fs.readFileSync(file, 'utf8'));
    if (state.version !== 1 || !Array.isArray(state.books)) throw new Error('书库结构或版本不支持。');
    const settings = { ...defaults().settings, ...state.settings };
    // Apply the requested reader defaults once on upgrade, not on every launch.
    // Books, progress, typography and the optional manual line limit are retained.
    if (!state.readerDefaultsVersion) Object.assign(settings, { autoFitWidth:true, mouseControl:true, mouseAutoShow:true, hideOnBlur:false, progressLine:false });
    return { ...defaults(), ...state, readerDefaultsVersion:1, settings };
  }
  save() {
    const temp = `${this.file}.tmp`;
    const fd = fs.openSync(temp, 'w');
    try { fs.writeFileSync(fd, JSON.stringify(this.state, null, 2)); fs.fsyncSync(fd); }
    finally { fs.closeSync(fd); }
    if (fs.existsSync(this.file) && !this.skipBackupOnce) fs.copyFileSync(this.file, `${this.file}.bak`);
    fs.renameSync(temp, this.file);
    this.skipBackupOnce = false;
  }
  scheduleSave() {
    if (this.saveTimer) return;
    this.saveTimer = setTimeout(() => { this.saveTimer = null; try { this.save(); } catch { this.recovery = '书库自动保存失败，将在退出时重试。'; } }, 500);
    this.saveTimer.unref?.();
  }
  flushSave() { if (this.saveTimer) { clearTimeout(this.saveTimer); this.saveTimer = null; this.save(); } }
  book(id) {
    const book = this.state.books.find(book => book.id === id);
    if (!book) throw new Error('书籍不存在。');
    return book;
  }
  textPath(book) {
    if (!/^[a-zA-Z0-9-]+\.txt$/.test(book.file || '')) throw new Error('书籍文件未就绪，请重新关联。');
    return path.join(this.root, 'books', book.file);
  }
  snapshot() {
    return { ...this.state, recovery: this.recovery, books: this.state.books.map(book => {
      let exists = false;
      try { exists = Boolean(book.file) && fs.existsSync(this.textPath(book)); } catch { exists = false; }
      return { ...book, missing: !exists, file: exists ? book.file : '' };
    }) };
  }
  async importFile(filename, repairId, options = {}) {
    return this.runImport(filename, () => this.runImportFile(filename, repairId, options));
  }
  async runImportFile(filename, repairId, { signal, metadata:onlineMetadata } = {}) {
    signal?.throwIfAborted();
    const id = crypto.randomUUID();
    const output = path.join(this.root, 'books', `${id}.txt`);
    // 先写临时文件，成功后 rename，失败/超时不留半成品。
    const temp = `${output}.tmp`;
    let stat;
    try { stat = await fsp.stat(filename); } catch { throw new Error('文件不存在或无法读取。'); }
    const metadata = await new Promise((resolve, reject) => {
      const worker = new Worker(path.join(__dirname, 'import-worker.cjs'), { workerData: { filename, output: temp, onlineChapters:onlineMetadata?.onlineChapters, options: { footnoteMode: this.state.settings.footnoteMode, imageMode: this.state.settings.imageMode } } });
      // 超时按文件大小放大（每 10MB +30s，120s 起步，20min 封顶）。
      const timer = setTimeout(() => finish(reject)(new Error('导入超时，文件过大或损坏。')), importTimeout(stat.size));
      let settled = false;
      const abort = () => finish(reject)(new Error('任务已取消。'));
      const finish = fn => value => { if(settled)return;settled=true;clearTimeout(timer);signal?.removeEventListener('abort',abort);worker.terminate().finally(()=>fn(value)); };
      signal?.addEventListener('abort', abort, { once:true });
      if (signal?.aborted) abort();
      worker.once('message', finish(data => data.error ? reject(new Error(data.error)) : resolve(data)));
      worker.once('error', finish(reject));
      worker.once('exit', code => finish(() => { if (code) reject(new Error(`导入进程退出：${code}`)); else reject(new Error('导入进程未返回结果。')); })(undefined));
    }).catch(async error => { await fsp.unlink(temp).catch(() => {}); throw error; });
    await fsp.rename(temp, output).catch(async error => { await fsp.unlink(temp).catch(() => {}); throw error; });
    if (signal?.aborted) { await fsp.unlink(output).catch(() => {}); throw new Error('任务已取消。'); }
    const duplicate = !repairId && this.state.books.find(book => book.digest === metadata.digest);
    if (duplicate) { await fsp.unlink(output); return { book: duplicate, duplicate: true }; }
    const before=JSON.parse(JSON.stringify(this.state));let book;
    try{
    if (repairId) {
      book = this.book(repairId);
      const retained={title:book.title,author:book.author,cover:book.cover};
      Object.assign(book, metadata, { file: `${id}.txt`, position: clamp(book.position, 0, metadata.length - 1), missing: false, format:path.extname(filename).slice(1).toUpperCase() });
      for(const [key,value]of Object.entries(retained))if(value)book[key]=value;
      for(const mark of book.bookmarks)mark.position=clamp(mark.position,0,metadata.length-1);
      this.cache = null;
    } else {
      book = { id, ...metadata, file: `${id}.txt`, position: 0, bookmarks: [], favorite: false, createdAt: Date.now(), lastReadAt: 0, format: path.extname(filename).slice(1).toUpperCase() };
      this.state.books.push(book);
    }
    if(onlineMetadata)Object.assign(book,onlineMetadata);
    this.save();
    }catch(error){this.state=before;this.cache=null;await fsp.unlink(output).catch(()=>{});throw error;}
    return { book, duplicate: false };
  }
  open(id) {
    const book = this.book(id);
    const text = fs.readFileSync(this.textPath(book), 'utf8');
    if (!text.trim()) throw new Error('书籍正文为空，请重新关联。');
    this.cache = { id, text };
    book.length = text.length;
    book.position = clamp(book.position, 0, text.length - 1);
    book.lastReadAt = Date.now();
    this.state.activeId = id;
    this.save();
    return this.read();
  }
  read(options = {}) {
    if (!this.state.activeId) return { text: '欢迎使用圣子猫阅读器', title: '圣子猫阅读器', position: 0, length: 0, progress: 0, count: 34, bookmarks: [] };
    const book = this.book(this.state.activeId);
    if (this.cache?.id !== book.id) this.open(book.id);
    const text = this.cache.text;
    const count = Math.floor(clamp(options.count ?? this.state.settings.lineSize * (this.state.settings.multiLine ? this.state.settings.lineCount : 1), 1, 32768));
    const anchor = Math.floor(clamp(book.position, 0, text.length - 1));
    let start = options.before ? Math.max(0, anchor - count) : anchor;
    if (start > 0 && /[\uDC00-\uDFFF]/.test(text[start])) start--;
    let end = options.before ? anchor : Math.min(start + count, text.length);
    if (end < text.length && /[\uD800-\uDBFF]/.test(text[end - 1])) end++;
    return { id: book.id, title: book.title, text: text.slice(start, end).replace(/[\r\n\t]/g, ' '), position: start, anchorPosition:anchor, length: text.length, count: end - start, progress: end >= text.length ? 1 : start / text.length, bookmarks: book.bookmarks };
  }
  move(options = {}) {
    const book = this.book(this.state.activeId);
    if (this.cache?.id !== book.id) this.open(book.id);
    const current = this.read(options);
    let position = Number.isFinite(options.position) ? options.position : current.position + (options.direction < 0 ? -Math.max(1, Math.floor(clamp(options.count || current.count, 1, 2000))) : current.count);
    position = Math.floor(clamp(position, 0, book.length - 1));
    if (/[\uDC00-\uDFFF]/.test(this.cache.text[position]) && position > 0) position--;
    book.position = position;
    book.lastReadAt = Date.now();
    this.scheduleSave();
    return this.read(options);
  }
  bookmark() {
    const book = this.book(this.state.activeId);
    if (!book.bookmarks.some(mark => mark.position === book.position)) book.bookmarks.push({ id: crypto.randomUUID(), position: book.position, title: this.read({ count: 24 }).text, createdAt: Date.now() });
    this.scheduleSave(); return this.read();
  }
  removeBookmark(bookId, markId) { const book = this.book(bookId); book.bookmarks = book.bookmarks.filter(mark => mark.id !== markId); this.save(); }
  favorite(id) { const book = this.book(id); book.favorite = !book.favorite; this.save(); }
  archive(id) {
    this.book(id);
    this.state.books = this.state.books.filter(book => book.id !== id);
    if (this.state.activeId === id) { this.state.activeId = ''; this.cache = null; }
    // Keep the text on disk so removal is recoverable from library.json.bak.
    this.save();
  }
  settings(patch) {
    const settings = this.state.settings;
    for (const key of ['fontSize', 'lineSize', 'lineCount', 'opacity', 'backgroundOpacity', 'fontOpacity', 'timeoutMinutes', 'windowWidth', 'windowHeight', 'autoReadSeconds', 'ttsRate', 'ttsVolume', 'normalFontSize']) if (key in patch) {
      const [min, max] = { fontSize: [5, 40], lineSize: [10, 200], lineCount: [1, 12], opacity: [0.3, 1], backgroundOpacity: [0, 1], fontOpacity: [0, 1], timeoutMinutes: [1, 120], windowWidth: [240, 2000], windowHeight: [20, 720], autoReadSeconds: [1, 120], ttsRate: [0.5, 2], ttsVolume: [0, 1], normalFontSize: [16, 36] }[key];
      if (!Number.isFinite(Number(patch[key]))) throw new Error('设置值必须是数字。');
      settings[key] = clamp(patch[key], min, max);
    }
    for (const key of ['alwaysOnTop', 'hideOnBlur', 'autoFitWidth', 'multiLine', 'fontBold', 'mouseControl', 'mouseAutoShow', 'fakeAddress', 'timeoutEnabled', 'bossKeyEnabled', 'hideTaskbar', 'resizable', 'lineScroll', 'progressLine', 'autoRead', 'wheelPaused', 'ttsEnabled','autoUpdate']) if (key in patch) settings[key] = Boolean(patch[key]);
    for (const key of ['fontColor', 'background', 'highlightColor']) if (key in patch && /^#[0-9a-f]{6}$/i.test(patch[key])) settings[key] = patch[key];
    if (typeof patch.fontFamily === 'string' && patch.fontFamily.length <= 100) settings.fontFamily = patch.fontFamily;
    if (['grid', 'list'].includes(patch.view)) settings.view = patch.view;
    for (const [key, values] of Object.entries({ readingMode:['stealth','normal'], normalTheme:['light','sepia','dark'], theme: ['classic','ink','chinaRed','celadon'], wheelSensitivity: ['steady','normal','sensitive'], footnoteMode: ['inline','skip','remove'], imageMode: ['alt','placeholder','skip','none'] })) if (values.includes(patch[key])) settings[key] = patch[key];
    for (const key of ['windowTitle','bossKey']) if (typeof patch[key] === 'string' && patch[key].length <= 120) settings[key] = patch[key];
    for (const key of ['searchHistory','disabledPlugins']) if (Array.isArray(patch[key])) settings[key] = patch[key].filter(item=>typeof item==='string').slice(0,20);
    if (Array.isArray(patch.savedFilters)) settings.savedFilters = patch.savedFilters.filter(item=>item && typeof item.name==='string').slice(0,20);
    if (patch.appOptions && typeof patch.appOptions === 'object' && !Array.isArray(patch.appOptions)) {
      const allowed = ['url','title','preset','width','height','opacity','zoom','gray','hideImages','transparent','top','difficulty','sudokuWidth','sudokuHeight','attachReader','attachApps','offsetX','offsetY','attachmentTitle','attachmentHandle'];
      const clean = {};
      for (const key of allowed) if (key in patch.appOptions) clean[key] = patch.appOptions[key];
      if (JSON.stringify(clean).length <= 65536) settings.appOptions = clean;
    }
    this.save(); return settings;
  }
  updateBooks(ids, patch) {
    ids.forEach(id => this.book(id));
    if ('title' in patch && (typeof patch.title !== 'string' || !patch.title.trim())) throw new Error('书名不能为空。');
    if ('cover' in patch && patch.cover && !/^https?:\/\/[^\s]+$/i.test(patch.cover) && !/^data:image\/(?:png|jpeg|webp);base64,[A-Za-z0-9+/=]+$/i.test(patch.cover)) throw new Error('封面仅支持 HTTP/HTTPS 图片地址或 PNG/JPEG/WebP data URL。');
    for (const id of ids) {
      const book = this.book(id);
      for (const key of ['favorite','pinned','completed']) if (key in patch) book[key] = Boolean(patch[key]);
      if (typeof patch.category === 'string') book.category = patch.category.slice(0,60);
      if (Array.isArray(patch.tags)) book.tags = patch.tags.map(item => String(item).slice(0,30)).filter(item => item).slice(0,20);
      for (const [key,max] of [['title',250],['author',120],['sourceName',120],['intro',10000],['cover',2000000]]) if (typeof patch[key] === 'string') book[key] = patch[key].trim().slice(0,max);
    }
    this.save();
  }
  archiveMany(ids) {
    for (const id of ids) this.book(id);
    this.state.books = this.state.books.filter(book => !ids.includes(book.id));
    if (ids.includes(this.state.activeId)) { this.state.activeId=''; this.cache=null; }
    this.save();
  }
  clearHistory() {
    for (const book of this.state.books) book.lastReadAt = 0;
    this.save();
  }
  resetSettings() {
    this.state.settings = defaults().settings;
    this.save(); return this.state.settings;
  }
  chapters(id) {
    const book=this.book(id);
    if (!Array.isArray(book.chapters) || book.chapters.some(chapter=>!Number.isFinite(chapter.position))) {
      book.chapters=findChapters(fs.readFileSync(this.textPath(book),'utf8'));this.save();
    }
    return book.chapters;
  }
  chapterMove(direction) {
    const book=this.book(this.state.activeId), chapters=this.chapters(book.id);
    const target = direction>0 ? chapters.find(chapter=>chapter.position>book.position) : [...chapters].reverse().find(chapter=>chapter.position<book.position);
    if(!target)throw new Error(direction>0?'已经是最后一章。':'已经是第一章。');
    return this.move({position:target.position});
  }
  recordReading(seconds) {
    if(!this.state.activeId)return;
    const book=this.book(this.state.activeId), now=new Date();
    const day=`${now.getFullYear()}-${String(now.getMonth()+1).padStart(2,'0')}-${String(now.getDate()).padStart(2,'0')}`;
    seconds=clamp(seconds,0,30);book.readingSeconds=(book.readingSeconds||0)+seconds;
    book.readingDailyStats ||= {};book.readingDailyStats[day]=(book.readingDailyStats[day]||0)+seconds;this.scheduleSave();
  }
}
function findChapters(text) {
  const chapters=[];
  // 支持“第 1 章”（数字两侧空格）与“话/話”；match.index 跳过行首空白。
  const pattern=/^[\t ]*((?:第[\t ]*[\d零〇一二三四五六七八九十百千万两]+[\t ]*[章回卷节部集篇话話]|chapter\s+\d+)[^\r\n]{0,100})[\t ]*$/gim;
  const titles=[];
  let match;
  while((match=pattern.exec(text))&&titles.length<30000)titles.push({title:match[1].trim(),position:match.index+match[0].length-match[0].replace(/^[\t ]+/,'').length});
  // 连续/末尾孤立标题不产生零长度章节：与前一章标题行重叠的章节并入下一章。
  for(const item of titles){
    const previous=chapters[chapters.length-1];
    if(previous&&item.position<=previous.position+previous.title.length)continue;
    chapters.push(item);
  }
  return chapters;
}
function legacyPosition(book) {
  const automatic = book.autoBookmark;
  if (automatic && Number(automatic.updatedAt) > Number(book.readPositionUpdatedAt || 0) && Number.isFinite(Number(automatic.position))) return Math.max(0, Number(automatic.position));
  if (Number.isFinite(Number(book.readPosition))) return Math.max(0, Number(book.readPosition));
  if (Number.isFinite(Number(automatic?.position))) return Math.max(0, Number(automatic.position));
  return Math.max(0, (Number(book.page || 1) - 1) * Number(book.readPositionPageSize || 34));
}
function scanLegacy(folder) {
  const file = path.join(folder, 'single-reader-state.json');
  if (!fs.existsSync(file)) return { file, books: [], error: '未找到旧版书库文件。' };
  const bytes = fs.readFileSync(file);
  const state = JSON.parse(bytes.toString('utf8'));
  const books = new Map();
  const blocks = [state.Book, state['single-reader-state']?.Book];
  for (const block of blocks) for (const book of [...(Array.isArray(block?.books) ? block.books : []), ...(block?.book?.path ? [block.book] : [])]) {
    if (!book || typeof book.path !== 'string') continue;
    const key = path.resolve(folder, book.path).toLowerCase();
    const previous = books.get(key);
    const updated = item => Math.max(Number(item.readPositionUpdatedAt) || 0, Number(item.autoBookmark?.updatedAt) || 0, Number(item.lastReadAt) || 0);
    const preferred = !previous || updated(book) > updated(previous) ? book : previous;
    const bookmarks = new Map();
    for (const mark of [...(Array.isArray(previous?.bookmarks) ? previous.bookmarks : []), ...(Array.isArray(book.bookmarks) ? book.bookmarks : [])]) {
      if (mark && !mark.auto) bookmarks.set(`${mark.position ?? mark.page}\0${mark.title || ''}`, mark);
    }
    books.set(key, { ...previous, ...preferred, path: path.resolve(folder, book.path), bookmarks: [...bookmarks.values()] });
  }
  return { file, bytes, digest: hash(bytes), books: [...books.values()] };
}
async function migrateLegacy(library, folder) {
  if (path.resolve(folder).toLowerCase() === library.root.toLowerCase()) throw new Error('旧版与新版数据目录不能相同。');
  const scan = scanLegacy(folder);
  if (scan.error) throw new Error(scan.error);
  const backup = path.join(library.root, 'migration-backups', `${stamp()}-${crypto.randomUUID().slice(0, 8)}`);
  fs.mkdirSync(backup, { recursive: true });
  // Capture the exact state inspected; never write into the legacy directory.
  fs.writeFileSync(path.join(backup, 'single-reader-state.json'), scan.bytes, { flag: 'wx' });
  const settingsFile = path.join(folder, 'single-reader-settings.json');
  if (fs.existsSync(settingsFile)) fs.copyFileSync(settingsFile, path.join(backup, 'single-reader-settings.json'));
  const report = { imported: 0, skipped: 0, missing: 0, errors: [], sourceDigest: scan.digest, backup };
  for (const old of scan.books) {
    const legacyKey = hash(path.resolve(folder).toLowerCase() + '\0' + old.path.toLowerCase());
    const existing = library.state.books.find(book => book.legacyKey === legacyKey);
    if (existing) { report.skipped++; continue; }
    const id = crypto.randomUUID();
    const book = { id, legacyKey, title: String(old.title || path.basename(old.path)), author: String(old.author || old.metadata?.creator || ''), file: '', format: String(old.type || 'TXT').toUpperCase(), length: 0, position: legacyPosition(old), bookmarks: [], favorite: Boolean(old.favorite), pinned: Boolean(old.pinned), category: String(old.category||''), tags: Array.isArray(old.tags)?old.tags.map(String):[], cover: /^data:image\/(png|jpe?g|webp);base64,/i.test(old.cover||'')?String(old.cover).slice(0,2000000):'', readingSeconds: Number(old.readingSeconds)||0, readingDailyStats: old.readingDailyStats||{}, createdAt: Date.now(), lastReadAt: Number(old.lastReadAt) || 0, legacyOrigin: old.path };
    Object.assign(book,{sourceName:String(old.sourceName||old.source||'本地'),intro:String(old.intro||old.description||''),completed:Boolean(old.completed),createdAt:Number(old.createdAt||old.importedAt)||book.createdAt});
    if(/^https?:\/\/[^\s]+$/i.test(old.cover||''))book.cover=String(old.cover).slice(0,2000);
    for (const mark of Array.isArray(old.bookmarks) ? old.bookmarks : []) {
      if (mark.auto) continue;
      book.bookmarks.push({ id: crypto.randomUUID(), title: String(mark.title || '旧版书签'), position: Math.max(0, Number(mark.position ?? ((mark.page - 1) * (mark.lineSize || 34))) || 0), createdAt: Number(mark.updatedAt) || 0 });
    }
    try {
      const stat = fs.statSync(old.path);
      if (!stat.isFile()) throw new Error('原路径不是文件');
      if (path.extname(old.path).toLowerCase() === '.txt') {
        // Preserve characters and offsets exactly for legacy UTF-8 converted books.
        const { decode } = require('./importer.cjs');
        const text = decode(await fsp.readFile(old.path));
        if (!text.trim()) throw new Error('原正文为空');
        book.file = `${id}.txt`; book.length = text.length; book.digest = hash(text); book.chapters = findChapters(text);
        await fsp.writeFile(library.textPath(book), text, { encoding: 'utf8', flag: 'wx' });
      } else {
        const { parseBook } = require('./importer.cjs');
        const parsed = await parseBook(old.path);
        book.file = `${id}.txt`; book.length = parsed.text.length; book.digest = hash(parsed.text); book.chapters = findChapters(parsed.text); if (!book.cover) book.cover = parsed.cover || '';
        await fsp.writeFile(library.textPath(book), parsed.text, { encoding: 'utf8', flag: 'wx' });
      }
      book.position = clamp(book.position, 0, book.length - 1);
      book.bookmarks.forEach(mark => { mark.position = clamp(mark.position, 0, book.length - 1); });
      report.imported++;
    } catch (error) { book.file = ''; report.missing++; report.errors.push({ title: book.title, reason: error.message }); }
    library.state.books.push(book);
    library.save();
  }
  fs.writeFileSync(path.join(backup, 'report.json'), JSON.stringify(report, null, 2));
  return report;
}
module.exports = { Library, scanLegacy, migrateLegacy, legacyPosition, defaults, findChapters };
