'use strict';
const { app, BrowserWindow, ipcMain, dialog, globalShortcut, Tray, Menu, nativeImage, screen, clipboard, shell } = require('electron');
const { autoUpdater } = require('electron-updater');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const fs = require('node:fs');
const { Library, scanLegacy, migrateLegacy } = require('./library.cjs');
const { OnlineTasks, runEngine } = require('./online.cjs');
const { TomatoTasks } = require('./tomato.cjs');
const { runFileTask } = require('./file-tasks.cjs');
const { WindowTools, listWindows } = require('./window-tools.cjs');
const { exportBackup, importBackup } = require('./backup.cjs');
const consent = require('./consent.cjs');
const { readingSection } = require('./reading-section.cjs');
const { DesktopUpdater } = require('./desktop-updater.cjs');
const documentExtensions=require('./documents.cjs').EXTENSIONS.map(ext=>ext.slice(1));

app.setName('shengzimao-reader-independent');
const arg = prefix => process.argv.find(value => value.startsWith(prefix))?.slice(prefix.length);
const dataDir = path.resolve(arg('--profile=') || path.join(app.getPath('appData'), 'shengzimao-reader-independent'));
const legacyDir = path.resolve(arg('--legacy-profile=') || path.join(app.getPath('appData'), 'single-reader'));
app.setPath('userData', dataDir);
if (!app.requestSingleInstanceLock()) app.quit();
else boot();

function boot() {
  if (dataDir.toLowerCase() === legacyDir.toLowerCase() || dataDir.toLowerCase().startsWith(legacyDir.toLowerCase()+path.sep)) throwFatal(new Error('禁止把新配置目录指向旧书库。'));
  let library, manager, reader, normalReader, tray, online, tomato, tools, desktopUpdater, quitting = false, migrationBusy = false, boundsTimer, settingBounds = false, appliedBounds = '', shortcut = '';
  let updateTimer, quitAfterFlush = false, readerRevealUntil = 0;
  const resources = app.isPackaged ? path.join(process.resourcesPath, 'independent') : path.join(__dirname, '..', 'resources');
  const html = path.join(__dirname, 'ui', 'index.html');
  const allowedFile = pathToFileURL(html).href;
  const options = () => ({ preload: path.join(__dirname, 'preload.cjs'), contextIsolation: true, nodeIntegration: false, sandbox: true });
  function send(channel, data) {
    for (const window of [manager, reader, normalReader]) if (window && !window.isDestroyed()) window.webContents.send(channel, data);
  }
  function changed() { send('reader:changed', { activeId: library.state.activeId }); }
  function protect(window) {
    window.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
    window.webContents.on('will-navigate', (event, target) => { if (target.split('#')[0].split('?')[0] !== allowedFile) event.preventDefault(); });
    window.webContents.on('render-process-gone', () => { if (!quitting) { const wasReader = window === reader, wasNormal = window === normalReader; window.destroy(); if (wasReader) reader = null; else if (wasNormal) normalReader = null; else manager = null; showManager(); } });
  }
  function showManager(navigation) {
    if (manager && !manager.isDestroyed()) {
      if (manager.isMinimized()) manager.restore(); manager.show(); manager.focus();
      if (navigation && typeof navigation === 'object') {
        if (manager.webContents.isLoading()) manager.webContents.once('did-finish-load', () => manager.webContents.send('reader:navigate', navigation));
        else manager.webContents.send('reader:navigate', navigation);
      }
      return manager;
    }
    manager = new BrowserWindow({ width: 940, height: 620, minWidth: 560, minHeight: 440, title: '圣子猫阅读器 · 独立版', icon: path.join(resources, 'icon.ico'), show: false, backgroundColor: '#ffffff', webPreferences: options() });
    protect(manager); manager.setMenuBarVisibility(false);
    manager.once('ready-to-show', () => { manager.show(); changed(); });
    manager.on('closed', () => { manager = null; changed(); });
    if (navigation && typeof navigation === 'object') manager.webContents.once('did-finish-load', () => manager.webContents.send('reader:navigate', navigation));
    manager.loadFile(html, { query: { mode: 'manager' } }).catch(fatal);
    return manager;
  }
  function fitBounds(bounds) {
    const width = Math.round(Math.max(240, Math.min(2000, Number(bounds.width) || 420)));
    const height = Math.round(Math.max(20, Math.min(720, Number(bounds.height) || 24)));
    const proposed = { x: Math.round(Number(bounds.x)||0), y: Math.round(Number(bounds.y)||0), width, height };
    const display = screen.getDisplayMatching(proposed), area = display.workArea;
    proposed.width = Math.min(width, area.width); proposed.height = Math.min(height, area.height);
    proposed.x = Math.max(area.x, Math.min(proposed.x, area.x + area.width - proposed.width));
    proposed.y = Math.max(area.y, Math.min(proposed.y, area.y + area.height - proposed.height));
    // Integer DIP origins on fractional-pixel boundaries can enlarge a frameless
    // Windows window. Choose a nearby physical-pixel origin, within this screen.
    const aligned=(value,min,max,origin)=>{
      for(let delta=0;delta<=12;delta++)for(const candidate of [value-delta,value+delta])
        if(candidate>=min&&candidate<=max&&Math.abs((candidate-origin)*display.scaleFactor-Math.round((candidate-origin)*display.scaleFactor))<.0001)return candidate;
      return value;
    };
    proposed.x=aligned(proposed.x,area.x,area.x+area.width-proposed.width,display.bounds.x);
    proposed.y=aligned(proposed.y,area.y,area.y+area.height-proposed.height,display.bounds.y);
    return proposed;
  }
  function saveBounds() {
    clearTimeout(boundsTimer);
    boundsTimer = setTimeout(() => {
      if (!reader || reader.isDestroyed() || quitting) return;
      library.state.readerBounds = reader.getBounds();
      Object.assign(library.state.settings, { windowWidth: library.state.readerBounds.width, windowHeight: library.state.readerBounds.height });
      library.save(); changed();
    }, 350);
  }
  function registerShortcut() {
    globalShortcut.unregisterAll(); shortcut = '';
    const settings = library.state.settings;
    if (!settings.bossKeyEnabled) return;
    try { if (globalShortcut.register(settings.bossKey, toggleReader)) shortcut = settings.bossKey; else throw new Error('快捷键已被占用'); }
    catch { library.recovery = '老板键不可用，可通过书库或托盘恢复阅读窗。'; }
  }
  function applyReaderSettings() {
    applyPinned(normalReader);
    if (!reader || reader.isDestroyed()) return;
    const settings = library.state.settings;
    reader.setOpacity(settings.opacity);
    reader.setSkipTaskbar(settings.hideTaskbar); reader.setResizable(settings.resizable);
    reader.setTitle(settings.windowTitle || '圣子猫单行阅读器');
    const customIcon = settings.customIcon && /^\.(ico|png|jpe?g)$/i.test(settings.customIcon) ? path.join(dataDir, 'reader-icon' + settings.customIcon) : '';
    reader.setIcon(customIcon && fs.existsSync(customIcon) ? customIcon : path.join(resources, 'icon.ico'));
    reader.setMinimumSize(240, 20);
    const bounds = reader.getBounds();
    const lineHeight = Math.ceil(settings.fontSize * (settings.multiLine ? 1.45 : 1.35));
    const height = Math.max(settings.windowHeight, lineHeight * (settings.multiLine ? settings.lineCount : 1) + 8);
    settingBounds = true;
    const target = fitBounds({ ...bounds, width: settings.windowWidth, height }); appliedBounds = JSON.stringify(target);
    reader.setBounds(target);
    const actual = reader.getBounds();
    appliedBounds = JSON.stringify(actual);
    // The reference keeps the expanded size when reducing the font/line count.
    // Persist the actual window, not only the pre-expansion settings value.
    if (height > settings.windowHeight) {
      settings.windowHeight = actual.height;
      library.state.readerBounds = actual;
      library.save();
    }
    settingBounds = false;
    applyPinned(reader);
  }
  function applyPinned(window) {
    if (!window || window.isDestroyed()) return;
    const pinned = library.state.settings.alwaysOnTop;
    // Apply after native style/bounds changes and again after show/restore.
    // The explicit Windows level keeps the reader above ordinary floating tools.
    window.setAlwaysOnTop(pinned, pinned ? 'screen-saver' : 'normal');
    if (pinned && window.isVisible()) window.moveTop();
  }
  function showNormalReader() {
    reader?.hide();
    if (!normalReader || normalReader.isDestroyed()) {
      normalReader = new BrowserWindow({ width:1080, height:760, minWidth:640, minHeight:440,
        title:'圣子猫阅读器', show:false, backgroundColor:'#f6f7f9', icon:path.join(resources,'icon.ico'), webPreferences:options() });
      protect(normalReader); normalReader.setMenuBarVisibility(false);
      normalReader.once('ready-to-show', () => { if (library.state.settings.readingMode !== 'normal') return; normalReader.maximize(); normalReader.show(); applyPinned(normalReader); });
      for (const event of ['show','restore']) normalReader.on(event, () => applyPinned(normalReader));
      normalReader.on('enter-full-screen', () => changed());
      normalReader.on('leave-full-screen', () => changed());
      normalReader.on('closed', () => { normalReader = null; });
      let closeAfterFlush = false;
      const window = normalReader;
      window.on('close', event => {
        if (quitting || closeAfterFlush) return;
        event.preventDefault(); closeAfterFlush = true;
        flushNormalProgress().finally(() => { if (!window.isDestroyed()) window.close(); });
      });
      normalReader.loadFile(html, { query:{ mode:'normal' } }).catch(fatal);
    } else { if (normalReader.isMinimized()) normalReader.restore(); normalReader.show(); normalReader.focus(); applyPinned(normalReader); }
  }
  function showReader() {
    if (library.state.settings.readingMode === 'normal') { showNormalReader(); return; }
    normalReader?.hide();
    if (!reader || reader.isDestroyed()) {
      const settings = library.state.settings;
      const area = screen.getPrimaryDisplay().workArea;
      const initialHeight = Math.max(settings.windowHeight, Math.ceil(settings.fontSize * (settings.multiLine ? 1.45 : 1.35)) * (settings.multiLine ? settings.lineCount : 1) + 8);
      const initial = fitBounds({ x: area.x, y: area.y + area.height - initialHeight, width: settings.windowWidth, height: initialHeight, ...library.state.readerBounds });
      reader = new BrowserWindow({ ...initial, minWidth: 240, minHeight: 20, title: settings.windowTitle, frame: false, transparent: true, show: false, resizable: settings.resizable, icon: path.join(resources, 'icon.ico'), backgroundColor: '#00000000', webPreferences: { ...options(), backgroundThrottling:false } });
      protect(reader);
      reader.once('ready-to-show', () => {
        applyReaderSettings();
        if (library.state.settings.readingMode === 'stealth') {
          // Give the user the full hint duration after the window is actually ready.
          if (readerRevealUntil) readerRevealUntil = Date.now() + 5000;
          reader.show(); changed();
        }
      });
      for (const event of ['show','restore']) reader.on(event, () => applyPinned(reader));
      reader.on('hide', () => reader?.webContents.send('reader:tts-stop'));
      reader.on('closed', () => { reader = null; });
      for (const event of ['moved', 'resized']) reader.on(event, () => { if (!settingBounds && !tools?.attached && JSON.stringify(reader.getBounds()) !== appliedBounds) saveBounds(); });
      reader.loadFile(html, { query: { mode: 'reader' } }).catch(fatal);
    } else { applyReaderSettings(); if (reader.isMinimized()) reader.restore(); reader.show(); reader.focus(); }
  }
  function toggleReader() { const current = library.state.settings.readingMode === 'normal' ? normalReader : reader; if (current?.isVisible()) current.hide(); else showReader(); }
  async function flushNormalProgress({strict=false}={}) {
    if (!normalReader || normalReader.isDestroyed()) return;
    let timer;
    try { await Promise.race([normalReader.webContents.executeJavaScript('window.flushNormalProgress?.()'),new Promise((resolve,reject) => { timer = setTimeout(()=>reject(new Error('保存普通阅读进度超时，请稍后再安装更新。')), 1200); })]); }
    catch (error) { console.error('[阅读进度]', error.message); if(strict)throw error; }
    finally { clearTimeout(timer); }
  }
  function readerContext() {
    const settings = library.state.settings, hasBook = Boolean(library.state.activeId);
    const run = fn => () => { try { fn(); } catch (error) { library.recovery = error.message; changed(); } };
    const toggle = key => run(() => { library.settings({ [key]: !settings[key] }); applyReaderSettings(); changed(); });
    Menu.buildFromTemplate([
      { label:'菜单', click:() => showManager({ route:'library' }) }, { type:'separator' },
      { label:'上一章', enabled:hasBook, click:run(() => { library.chapterMove(-1); changed(); }) },
      { label:'下一章', enabled:hasBook, click:run(() => { library.chapterMove(1); changed(); }) },
      { label:'跳转百分比...', enabled:hasBook, click:() => showManager({ route:'library', dialog:'jump', bookId:library.state.activeId }) },
      { type:'separator' }, { label:'暂停滚轮翻页', type:'checkbox', checked:settings.wheelPaused, click:toggle('wheelPaused') },
      { label:'自动阅读', type:'checkbox', checked:settings.autoRead, click:toggle('autoRead') },
      { label:'添加书签', enabled:hasBook, click:run(() => { library.bookmark(); changed(); }) },
      { label:'朗读当前内容', enabled:hasBook && settings.ttsEnabled, click:() => reader?.webContents.send('reader:tts-toggle') },
      { label:'窗口置顶', type:'checkbox', checked:settings.alwaysOnTop, click:toggle('alwaysOnTop') },
      { label:'切换到普通大屏阅读', click:run(() => { library.settings({readingMode:'normal'}); showReader(); changed(); }) },
      { type:'separator' }, { label:'退出程序', click:() => app.quit() }
    ]).popup({ window:reader });
  }
  function fatal(error) { console.error('[启动失败]', error && error.stack || error); dialog.showErrorBox('独立版启动失败（旧版未改动）', error.message || String(error)); app.quit(); }
  function throwFatal(error) { fatal(error); throw error; }
  const confirmWindow = () => manager && !manager.isDestroyed() ? manager : (reader && !reader.isDestroyed() ? reader : undefined);
  async function importPaths(paths, repairId, metadata, options={}) {
    const report = { imported: 0, duplicates: 0, errors: [] };
    for (const filename of paths) {
      try { options.signal?.throwIfAborted(); const result = await library.importFile(filename, repairId,{...options,metadata}); if (result.duplicate) report.duplicates++; else report.imported++; }
      catch (error) { report.errors.push({ file: path.basename(filename), message: error.message }); }
    }
    changed(); return report;
  }
  async function action(command, payload = {}, source) {
    if (!payload || typeof payload !== 'object' || Array.isArray(payload)) throw new Error('请求格式错误。');
    if (['update-check','update-download','update-cancel','update-install'].includes(command) && source !== manager) throw new Error('请在关于页面操作桌面版更新。');
    if(['search','online-download','online-download-many','online-retry','online-details','online-update','tomato-search','tomato-details','tomato-download'].includes(command)&&!consent.accepted(library.state))throw new Error('请先阅读并同意在线功能声明。');
    switch (command) {
      case 'snapshot': return { ...library.snapshot(), online: online.snapshot(), tomato: tomato.snapshot(), update:desktopUpdater?.snapshot()||{phase:'idle',version:'',releaseNotes:'',progress:0,error:''}, consent:{version:consent.VERSION,accepted:consent.accepted(library.state)}, profile: dataDir, version: app.getVersion(), runtime:{electron:process.versions.electron,platform:process.platform,arch:process.arch}, readerRevealUntil, shortcut, attachment: Boolean(tools.attached) };
      case 'reading-section': {
        const book = library.book(library.state.activeId);
        if (library.cache?.id !== book.id) library.open(book.id);
        library.chapters(book.id);
        return readingSection(book, library.cache.text, payload.position);
      }
      case 'reading-mode': {
        if (!['stealth','normal'].includes(payload.mode)) throw new Error('阅读模式无效。');
        library.settings({readingMode:payload.mode}); showReader(); changed(); return true;
      }
      case 'normal-window': {
        if (source !== normalReader || !normalReader || normalReader.isDestroyed()) throw new Error('只能操作普通阅读窗口。');
        if (payload.fullscreen !== undefined) normalReader.setFullScreen(payload.fullscreen === true);
        return {fullscreen:normalReader.isFullScreen(), maximized:normalReader.isMaximized(), alwaysOnTop:normalReader.isAlwaysOnTop()};
      }
      case 'online-consent': {consent.accept(library,payload);changed();return true;}
      case 'online-link': {const links={...consent.LINKS,repository:'https://github.com/fixz232/shengzimao-reader',releases:'https://github.com/fixz232/shengzimao-reader/releases'};if(!Object.hasOwn(links,payload.kind))throw new Error('未知链接。');await shell.openExternal(links[payload.kind]);return true;}
      case 'online-details': return online.details(payload.index);
      case 'online-update': return online.update(payload.ids||library.state.books.filter(book=>book.tracking&&book.onlineUrl).map(book=>book.id));
      case 'online-track': {const book=library.book(String(payload.id));if(!book.onlineUrl)throw new Error('此书没有原站链接。');book.tracking=payload.enabled===true;library.save();changed();return true;}
      case 'update-check': return desktopUpdater.check();
      case 'update-download': return desktopUpdater.download();
      case 'update-cancel': return desktopUpdater.cancel();
      case 'update-install': return desktopUpdater.install({confirm:async()=>{const result=await dialog.showMessageBox(confirmWindow(),{type:'question',buttons:['取消','安装并重启'],defaultId:0,cancelId:0,message:`已下载圣子猫阅读器 v${desktopUpdater.snapshot().version}，现在安装并重启吗？`,detail:'安装前会保存阅读进度与书库数据。'});return result.response===1;},flushProgress:async()=>{await flushNormalProgress({strict:true});if(reader&&!reader.isDestroyed())library.state.readerBounds=reader.getBounds();library.save();library.flushSave();}});
      case 'page-chunk': {
        const book=library.book(String(payload.id));if(library.cache?.id!==book.id)library.open(book.id);
        const start=Math.max(0,Math.min(book.length,Math.floor(Number(payload.start)||0)));
        return {text:library.cache.text.slice(start,start+32768).replace(/[\r\n\t]/g,' '),length:book.length};
      }
      case 'import': {
        const result = await dialog.showOpenDialog(confirmWindow(), { title: payload.repairId ? '重新关联书籍' : '导入本地文档', filters: [{ name: '可读取文档', extensions: documentExtensions }], properties: payload.repairId ? ['openFile'] : ['openFile', 'multiSelections'] });
        return result.canceled ? { canceled: true } : importPaths(result.filePaths, payload.repairId);
      }
      case 'open': { if (payload.mode !== undefined && !['stealth','normal'].includes(payload.mode)) throw new Error('阅读模式无效。'); const result = library.open(String(payload.id)); if (payload.mode) library.settings({readingMode:payload.mode}); showReader(); changed(); return result; }
      case 'import-dropped': {
        if (!Array.isArray(payload.files) || payload.files.length > 200 || payload.files.some(file => typeof file !== 'string' || !path.isAbsolute(file))) throw new Error('拖入的文件列表无效。');
        const valid = [], invalid = [];
        for (const file of payload.files) {
          try {
            if (!documentExtensions.includes(path.extname(file).slice(1).toLowerCase())) throw new Error('支持 TXT、EPUB、PDF、DOCX、Markdown 和 HTML。');
            if (!fs.statSync(file).isFile()) throw new Error('路径不是文件。');
            valid.push(file);
          } catch (error) { invalid.push({ file: path.basename(file), message: error.message }); }
        }
        const report = await importPaths(valid);
        report.errors.unshift(...invalid);
        return report;
      }
      case 'read': return library.read({ count: payload.count, before:payload.before === true });
      case 'move': { if (payload.id !== undefined && payload.id !== library.state.activeId) throw new Error('当前阅读书籍已切换。'); const result = library.move(payload); changed(); return result; }
      case 'bookmark': { const result = library.bookmark(); changed(); return result; }
      case 'bookmark-remove': library.removeBookmark(String(payload.id), String(payload.markId)); changed(); return true;
      case 'favorite': library.favorite(String(payload.id)); changed(); return true;
      case 'books-update': {
        if (!Array.isArray(payload.ids) || !payload.ids.length || !payload.patch || typeof payload.patch !== 'object') throw new Error('请选择书籍与管理操作。');
        library.updateBooks(payload.ids, payload.patch); changed(); return true;
      }
      case 'chapters': return library.chapters(String(payload.id));
      case 'clear-history': library.clearHistory(); changed(); return true;
      case 'books-remove': {
        if (!Array.isArray(payload.ids) || !payload.ids.length) throw new Error('请选择书籍。');
        payload.ids.forEach(id => library.book(id));
        const choice = await dialog.showMessageBox(confirmWindow(), { type:'question', buttons:['取消','从书库移除'], defaultId:0, cancelId:0, message:`移除选中的 ${payload.ids.length} 本书？`, detail:'仅移除独立版条目，正文文件与书库备份保留；旧版不受影响。' });
        if (choice.response === 1) { library.archiveMany(payload.ids); changed(); }
        return choice.response === 1;
      }
      case 'remove': {
        const book = library.book(String(payload.id));
        const choice = await dialog.showMessageBox(confirmWindow(), { type: 'question', buttons: ['取消', '从新版书库移除'], defaultId: 0, cancelId: 0, message: `移除《${book.title}》？`, detail: '只移除独立版的条目。保留正文文件与上一次书库备份；旧版不受影响。' });
        if (choice.response === 1) { library.archive(book.id); changed(); }
        return choice.response === 1;
      }
      case 'settings': { const result = library.settings(payload); if ('disabledPlugins' in payload) result.disabledPlugins.forEach(kind=>tools.disable(kind)); applyReaderSettings(); if ('readingMode' in payload && (reader?.isVisible() || normalReader?.isVisible())) showReader(); if ('bossKey' in payload || 'bossKeyEnabled' in payload) registerShortcut(); changed(); return result; }
      case 'settings-reset': library.resetSettings(); applyReaderSettings(); if (reader?.isVisible() || normalReader?.isVisible()) showReader(); registerShortcut(); changed(); return true;
      case 'choose-icon': {
        const result = await dialog.showOpenDialog(confirmWindow(), { title:'选择窗口图标', filters:[{name:'图标',extensions:['ico','png','jpg','jpeg']}], properties:['openFile'] });
        if (result.canceled) return false;
        const file = result.filePaths[0], ext = path.extname(file).toLowerCase();
        if (!['.ico','.png','.jpg','.jpeg'].includes(ext) || fs.statSync(file).size > 8*1024*1024 || nativeImage.createFromPath(file).isEmpty()) throw new Error('请选择有效图标（8 MB 以内）。');
        fs.copyFileSync(file, path.join(dataDir, 'reader-icon' + ext)); library.state.settings.customIcon = ext;
        library.save(); applyReaderSettings(); changed(); return true;
      }
      case 'reset-icon': delete library.state.settings.customIcon; library.save(); applyReaderSettings(); changed(); return true;
      case 'convert': {
        const format = payload.format;
        if (!['txt','epub'].includes(format)) throw new Error('转换格式无效。');
        const input = await dialog.showOpenDialog(confirmWindow(), { title:'选择要转换的文档', filters:[{ name:'文档', extensions:documentExtensions }], properties:['openFile'] });
        if (input.canceled) return { canceled:true };
        const sourceFile = input.filePaths[0];
        const output = await dialog.showSaveDialog(confirmWindow(), { title:'保存转换后的小说', defaultPath:path.join(path.dirname(sourceFile),path.basename(sourceFile,path.extname(sourceFile)) + '.' + format), filters:[{ name:format.toUpperCase(), extensions:[format] }] });
        if (output.canceled) return { canceled:true };
        if (path.extname(output.filePath).toLowerCase() !== '.'+format) throw new Error('保存文件的扩展名与转换格式不符。');
        if (path.resolve(output.filePath).toLowerCase().startsWith(legacyDir.toLowerCase()+path.sep)) throw new Error('独立版不会向旧书库目录写入，请选择其他位置。');
        return runFileTask({ action:'convert', input:sourceFile, output:output.filePath, options:library.state.settings });
      }
      case 'local-search': {
        if (!Array.isArray(payload.ids) || !payload.ids.length) throw new Error('请先选择搜索的书籍。');
        const books = [...new Set(payload.ids)].map(id => library.book(id)).filter(book => book.file).map(book => ({ id:book.id, title:book.title, path:library.textPath(book),size:fs.statSync(library.textPath(book)).size }));
        return runFileTask({ action:'search', books, keyword:payload.keyword });
      }
      case 'legacy-scan': {
        const scan = scanLegacy(legacyDir);
        return { found: scan.books.length, missing: scan.books.filter(book => !fs.existsSync(book.path)).length, path: legacyDir, error: scan.error || '', digest: scan.digest };
      }
      case 'legacy-migrate': {
        if (migrationBusy) throw new Error('迁移正在进行，请稍候。');
        const scan = scanLegacy(legacyDir);
        if (scan.error) throw new Error(scan.error);
        const result = await dialog.showMessageBox(confirmWindow(), { type: 'question', buttons: ['取消', '备份并复制到独立版'], defaultId: 0, cancelId: 0, message: `复制旧版的 ${scan.books.length} 本书？`, detail: '旧书库只读，不会移动、覆盖或删除。备份与正文副本存入独立版目录；再次迁移跳过已迁移条目，不覆盖新版进度。旧书源不迁移。' });
        if (result.response !== 1) return { canceled: true };
        migrationBusy = true;
        try { const report = await migrateLegacy(library, legacyDir); changed(); return report; }
        finally { migrationBusy = false; }
      }
      case 'backup-export': {
        const output = await dialog.showSaveDialog(confirmWindow(), { title: '导出书库备份', defaultPath: path.join(app.getPath('documents'), `书库备份-${new Date().toISOString().slice(0,10)}.szmbak`), filters: [{ name: '圣子猫备份', extensions: ['szmbak'] }] });
        if (output.canceled || !output.filePath) return { canceled: true };
        return exportBackup({ dataDir, library, target: output.filePath });
      }
      case 'backup-import': {
        const input = await dialog.showOpenDialog(confirmWindow(), { title: '导入书库备份', filters: [{ name: '圣子猫备份', extensions: ['szmbak', 'zip'] }], properties: ['openFile'] });
        if (input.canceled || !input.filePaths?.length) return { canceled: true };
        const report = await importBackup({ dataDir, library, source: input.filePaths[0] });
        if(report.settingsRestored){applyReaderSettings();if(reader?.isVisible()||normalReader?.isVisible())showReader();registerShortcut();}
        changed();
        return { ...report, message: `已导入 ${report.imported} 本，跳过重复 ${report.skipped} 本${report.missingText ? `，正文缺失 ${report.missingText} 本` : ''}${report.invalid ? `，无效 ${report.invalid} 条` : ''}。` };
      }
      case 'open-profile': {
        const result = await shell.openPath(dataDir);
        if (result) throw new Error('无法打开数据目录：' + result);
        return true;
      }
      case 'search': {
        const result = online.search(String(payload.keyword || ''));
        library.settings({ searchHistory:[result.keyword,...library.state.settings.searchHistory.filter(keyword => keyword !== result.keyword)].slice(0,12) }); changed(); return result;
      }
      case 'tomato-search': { const result = tomato.search(String(payload.keyword || '')); library.settings({ searchHistory:[result.keyword,...library.state.settings.searchHistory.filter(keyword => keyword !== result.keyword)].slice(0,12) }); changed(); return result; }
      case 'tomato-cancel': return tomato.cancel();
      case 'tomato-details': await tomato.details(payload.index); return tomato.snapshot().details;
      case 'tomato-download': await tomato.download(payload.index, payload.format); return tomato.snapshot();
      case 'online-cancel': return online.cancel();
      case 'online-download': return online.download(payload.index,payload.format);
      case 'online-download-many': return online.downloadMany(payload.indexes, payload.format);
      case 'online-retry': return online.retryFailed();
      case 'copy-link': {
        const book = online.state.results[payload.index]; if (!book || !Number.isInteger(payload.index)) throw new Error('当前结果不存在。');
        clipboard.writeText(book.url); return true;
      }
      case 'engine-probe': {
        if (online.state.running) return online.snapshot();
        online.probeResult = null;
        online.start({ action:'probe' }, ({ data }) => { online.probeResult = data; });
        return online.pending.then(() => online.probeResult);
      }
      case 'windows-list': return listWindows();
      case 'attachment-start': if (library.state.settings.disabledPlugins.includes('attachment')) throw new Error('窗口依附已停用。'); return tools.start(payload);
      case 'attachment-stop': tools.stop(); return true;
      case 'app-open': if (library.state.settings.disabledPlugins.includes(payload.kind)) throw new Error('此工具已停用。'); return tools.open(payload.kind,payload.options);
      case 'reader-context-menu': readerContext(); return true;
      case 'window-state': {
        if (!reader || reader.isDestroyed()) return null;
        const bounds = reader.getBounds(), pointer = screen.getCursorScreenPoint();
        return { ...bounds, visible:reader.isVisible(), focused:reader.isFocused(), minimized:reader.isMinimized(), alwaysOnTop:reader.isAlwaysOnTop(), managerOpen:Boolean(manager && !manager.isDestroyed()), pointerInside:pointer.x>=bounds.x && pointer.y>=bounds.y && pointer.x<bounds.x+bounds.width && pointer.y<bounds.y+bounds.height };
      }
      case 'window-bounds': {
        if (source !== reader || !reader || reader.isDestroyed()) throw new Error('只能调整自身阅读窗。');
        for (const key of Object.keys(payload)) if (!['x','y','width','height'].includes(key) || !Number.isFinite(payload[key])) throw new Error('窗口坐标无效。');
        if (!Object.keys(payload).length) return reader.getBounds();
        if (('width' in payload || 'height' in payload) && !library.state.settings.resizable) return reader.getBounds();
        const current = reader.getBounds();
        reader.setBounds(fitBounds({ x: current.x, y: current.y, width: current.width, height: current.height, ...payload, x: 'x' in payload ? Math.round(payload.x) : current.x, y: 'y' in payload ? Math.round(payload.y) : current.y })); saveBounds(); return reader.getBounds();
      }
      case 'reading-tick': if ((source === reader && reader?.isVisible()) || (source === normalReader && normalReader?.isVisible())) library.recordReading(payload.seconds); return true;
      case 'show-manager': showManager(); return true;
      case 'show-reader': showReader(); return true;
      case 'hide-reader': reader?.hide(); normalReader?.hide(); return true;
      case 'quit': app.quit(); return true;
      default: throw new Error('操作未授权。');
    }
  }
  app.on('second-instance', () => { if (library) { readerRevealUntil = Date.now() + 5000; showReader(); changed(); } showManager(); });
  app.on('activate', () => showManager());
  app.on('window-all-closed', () => { if (!tray) app.quit(); });
  app.on('before-quit', event => {
    if (normalReader && !normalReader.isDestroyed() && !quitAfterFlush) { event.preventDefault(); quitAfterFlush = true; flushNormalProgress().finally(() => app.quit()); return; }
    if (reader && !reader.isDestroyed() && library) { library.state.readerBounds = reader.getBounds(); library.save(); }
    library?.flushSave();
    quitting = true; clearInterval(updateTimer); clearTimeout(boundsTimer); online?.cancel(); tools?.close(); globalShortcut.unregisterAll();
  });
  app.whenReady().then(() => {
    const firstLaunch = !fs.existsSync(path.join(dataDir,'library.json')) && !fs.existsSync(path.join(dataDir,'library.json.bak'));
    library = new Library(dataDir);
    desktopUpdater = new DesktopUpdater({ updater:autoUpdater, isPackaged:app.isPackaged, platform:process.platform, onState:()=>changed() });
    online = new OnlineTasks({ resources, profile: dataDir, library, notify: () => changed(), importPaths:(files,metadata,options)=>importPaths(files,undefined,metadata,options) });
    tomato = new TomatoTasks({ profile: dataDir, notify: () => changed(), importPaths:(files,metadata,options)=>importPaths(files,undefined,metadata,options) });
    tools = new WindowTools({ getReader:() => reader, icon:path.join(resources,'icon.ico'), notify:message => { library.recovery = message; changed(); } });
    ipcMain.handle('reader:request', async (event, request) => {
      try {
        const window = BrowserWindow.fromWebContents(event.sender);
        if (![manager, reader, normalReader].includes(window) || event.senderFrame !== event.sender.mainFrame || !event.senderFrame.url.startsWith(allowedFile + '?')) throw new Error('请求来源未授权。');
        if (!request || typeof request.command !== 'string') throw new Error('请求格式错误。');
        return { ok: true, value: await action(request.command, request.payload, window) };
      } catch (error) { return { ok: false, error: error.message }; }
    });
    const icon = nativeImage.createFromPath(path.join(resources, 'icon.ico'));
    if (!icon.isEmpty()) {
      tray = new Tray(icon); tray.setToolTip('圣子猫阅读器 · 独立版');
      tray.setContextMenu(Menu.buildFromTemplate([{ label: '打开书库', click: showManager }, { label: '显示 / 隐藏阅读窗', click: toggleReader }, { type: 'separator' }, { label: '退出独立版', click: () => app.quit() }]));
      tray.on('double-click', showManager);
    }
    registerShortcut();
    updateTimer=setInterval(()=>{if(!quitting&&library.state.settings.autoUpdate&&consent.accepted(library.state)&&!online.state.running){const ids=library.state.books.filter(book=>book.tracking&&book.onlineUrl).slice(0,200).map(book=>book.id);if(ids.length)try{online.update(ids);}catch(error){library.recovery=error.message;changed();}}},30*60*1000);updateTimer.unref();
    const previousBook = library.snapshot().books.find(book=>book.id===library.state.activeId);
    if(library.state.settings.readingMode==='stealth')readerRevealUntil=Date.now()+5000;
    if(firstLaunch || previousBook&&!previousBook.missing)showReader();else {showManager();if(library.state.settings.readingMode==='stealth')showReader();}
  }).catch(fatal);
}
