'use strict';
(() => {
  if (new URLSearchParams(location.search).get('mode') !== 'normal') return;
  document.body.classList.add('normal-window');
  const root = document.querySelector('#app'), e = window.ReaderViews.e;
  const request = (command, payload = {}) => window.reader.request(command, payload);
  let state, section, chapters = [], revision = 0, refreshRevision = 0, lastSaved = -1;
  let saveTimer, refreshTimer, noticeTimer, restoring = false, catalogLimit = 300, scrollPosition = 0;
  let saveQueue = Promise.resolve();
  root.innerHTML = '<header class="normal-header"><button data-normal="library">书库</button><span class="normal-brand">圣子猫阅读器</span><select id="normal-book" aria-label="切换书籍"></select><span class="normal-spacer"></span><div class="normal-actions"><button data-normal="catalog" aria-expanded="true" aria-controls="normal-catalog">目录</button><button data-normal="pin" aria-pressed="false">置顶</button><button data-normal="fullscreen">全屏 F11</button><button data-normal="stealth">隐蔽模式</button></div></header><div class="normal-tools"><label>字号 <input id="normal-font" type="range" min="16" max="36" step="1"><output id="normal-font-value"></output></label><label>主题 <select id="normal-theme"><option value="light">明亮</option><option value="sepia">护眼</option><option value="dark">夜间</option></select></label><button data-normal="bookmark">添加书签</button><small>滚轮阅读 · ← / → 切换章节 · Esc 退出全屏</small></div><div class="normal-layout"><aside id="normal-catalog" class="normal-catalog"><h2>章节目录</h2><input id="normal-catalog-query" type="search" placeholder="搜索章节名或序号" aria-label="搜索章节目录"><small id="normal-catalog-count"></small><nav class="normal-catalog-list" aria-label="章节目录"></nav></aside><main class="normal-content" tabindex="0" aria-label="阅读正文"><article class="normal-article"></article><div class="normal-chapter-nav"><button data-normal="previous">上一章 / 段</button><button data-normal="next">下一章 / 段</button></div></main></div><footer class="normal-footer"><span id="normal-chapter-label">准备阅读</span><input id="normal-progress" type="range" min="0" max="10000" value="0" aria-label="全书阅读进度"><output id="normal-progress-value">0.00%</output><span>进度自动保存</span></footer>';
  const content = root.querySelector('.normal-content'), article = root.querySelector('.normal-article');
  function notify(message) {
    const node = document.querySelector('#notice'); node.textContent = message;
    clearTimeout(noticeTimer); noticeTimer = setTimeout(() => { node.textContent = ''; }, 5000);
  }
  const task = fn => Promise.resolve().then(fn).catch(error => notify(error.message));
  function currentBook() { return state?.books.find(book => book.id === state.activeId && !book.missing); }
  function applySettings() {
    const settings = state.settings;
    document.body.dataset.readingTheme = settings.normalTheme;
    document.body.style.setProperty('--normal-font-size', settings.normalFontSize + 'px');
    root.querySelector('#normal-font').value = settings.normalFontSize;
    root.querySelector('#normal-font-value').textContent = settings.normalFontSize + 'px';
    root.querySelector('#normal-theme').value = settings.normalTheme;
    const pin = root.querySelector('[data-normal=pin]'); pin.setAttribute('aria-pressed', String(settings.alwaysOnTop));
    pin.textContent = settings.alwaysOnTop ? '已置顶' : '置顶';
  }
  function updateProgress(position) {
    const progress = section?.length > 1 ? position / (section.length - 1) : 0;
    root.querySelector('#normal-progress').value = Math.round(progress * 10000);
    root.querySelector('#normal-progress-value').textContent = (progress * 100).toFixed(2) + '%';
  }
  function renderCatalog() {
    const query = root.querySelector('#normal-catalog-query').value.trim().toLocaleLowerCase();
    const tokens = query.split(/\s+/).filter(Boolean);
    const rows = chapters.map((chapter, index) => ({...chapter, index})).filter(chapter => tokens.every(token => (chapter.title + ' ' + (chapter.index + 1)).toLocaleLowerCase().includes(token)));
    root.querySelector('#normal-catalog-count').textContent = query ? '找到 ' + rows.length + ' / ' + chapters.length + ' 章' : '共 ' + chapters.length + ' 章';
    root.querySelector('.normal-catalog-list').innerHTML = rows.slice(0, catalogLimit).map(chapter => '<button data-position="' + chapter.position + '" aria-current="' + (chapter.index === section?.chapterIndex) + '">' + e(chapter.title) + '</button>').join('') + (rows.length > catalogLimit ? '<button data-normal="catalog-more">显示更多章节</button>' : rows.length ? '' : '<p class="muted">' + (query ? '没有匹配的章节。' : '未识别出章节，可直接滚动阅读。') + '</p>');
  }
  function positionAtTop() {
    if (!section) return 0;
    const bounds = content.getBoundingClientRect(), y = bounds.top + 30;
    if (article.querySelector('h1').getBoundingClientRect().bottom > y) return section.start;
    const paragraphs = [...article.querySelectorAll('p[data-position]')];
    const paragraph = paragraphs.find(node => node.getBoundingClientRect().bottom > y) || paragraphs.at(-1);
    if (!paragraph) return section.start;
    const rect = paragraph.getBoundingClientRect();
    const range = document.caretRangeFromPoint(Math.max(rect.left + 2, bounds.left + 2), Math.max(rect.top + 2, y));
    let offset = 0;
    if (range && range.startContainer === paragraph.firstChild) offset = range.startOffset;
    return Math.min(section.end - 1, Number(paragraph.dataset.position) + offset);
  }
  function restorePosition(position) {
    if (!section) return;
    restoring = true;
    const paragraph = [...article.querySelectorAll('[data-position]')].findLast(node => Number(node.dataset.position) <= position);
    content.scrollTop = 0;
    if (position > section.start && paragraph?.firstChild?.nodeType === Node.TEXT_NODE) {
      const range = document.createRange(), text = paragraph.firstChild;
      const offset = Math.min(text.length, Math.max(0, position - Number(paragraph.dataset.position)));
      range.setStart(text, offset); range.setEnd(text, Math.min(text.length, offset + 1));
      const rect = range.getBoundingClientRect();
      content.scrollTop += rect.top - content.getBoundingClientRect().top - 30;
    }
    scrollPosition = position; updateProgress(position);
    requestAnimationFrame(() => { restoring = false; });
  }
  async function savePosition(position = positionAtTop()) {
    clearTimeout(saveTimer);
    if (!section || document.visibilityState !== 'visible' || position === lastSaved) return saveQueue;
    const id = section.id; lastSaved = position; scrollPosition = position; updateProgress(position);
    saveQueue = saveQueue.catch(() => {}).then(() => request('move', {id, position}));
    try { await saveQueue; } catch (error) { lastSaved = -1; throw error; }
  }
  async function loadSection(position) {
    clearTimeout(saveTimer); const ownRevision = ++revision;
    const next = await request('reading-section', {position});
    if (ownRevision !== revision) return;
    section = next; lastSaved = next.position;
    let offset = next.start;
    const lines = next.text.split('\n').map((line, index) => {
      const start = offset; offset += line.length + 1;
      if (index === 0 && line.trim() === next.chapterTitle.trim()) return '';
      return '<p data-position="' + start + '"' + (line.trim() ? '' : ' class="blank-line"') + '>' + (e(line) || '<br>') + '</p>';
    }).join('');
    article.innerHTML = '<h1 data-position="' + next.start + '">' + e(next.chapterTitle) + '</h1>' + lines;
    document.title = next.title + ' — 圣子猫阅读器';
    root.querySelector('#normal-chapter-label').textContent = next.chapterTitle;
    root.querySelector('[data-normal=previous]').disabled = next.previous === null;
    root.querySelector('[data-normal=next]').disabled = next.next === null;
    restorePosition(next.position); renderCatalog();
  }
  async function refresh() {
    const ownRevision = ++refreshRevision, snapshot = await request('snapshot');
    if (ownRevision !== refreshRevision) return;
    const previous = currentBook(); state = snapshot; applySettings();
    const book = currentBook(), selector = root.querySelector('#normal-book');
    const bookOptions = state.books.filter(item => !item.missing).map(item => '<option value="' + e(item.id) + '">' + e(item.title) + '</option>').join('');
    if (selector.innerHTML !== bookOptions) selector.innerHTML = bookOptions;
    selector.value = book?.id || '';
    for (const name of ['bookmark','previous','next']) if (!book) root.querySelector('[data-normal=' + name + ']').disabled = true;
    if (!book) { section = null; chapters = []; article.innerHTML = '<div class="normal-empty"><h1>打开一本书，开始阅读</h1><p>从书库选择一本书，或先导入本地文档。</p><button data-normal="library">打开书库</button></div>'; renderCatalog(); return; }
    root.querySelector('[data-normal=bookmark]').disabled = false;
    if (!section || section.id !== book.id || previous?.length !== book.length || previous?.digest !== book.digest || book.position !== lastSaved) {
      chapters = await request('chapters', {id:book.id});
      if (ownRevision !== refreshRevision) return;
      await loadSection(book.position);
    }
    const windowState = await request('normal-window');
    root.querySelector('[data-normal=fullscreen]').textContent = windowState.fullscreen ? '退出全屏 F11' : '全屏 F11';
  }
  async function seek(position) {
    if (!section) return;
    await savePosition();
    const result = await request('move', {id:section.id, position});
    lastSaved = result.position; await loadSection(result.position);
  }
  root.addEventListener('click', event => task(async () => {
    const chapter = event.target.closest('.normal-catalog-list [data-position]');
    if (chapter) { await seek(Number(chapter.dataset.position)); if (innerWidth <= 900) toggleCatalog(false); return; }
    const button = event.target.closest('[data-normal]'); if (!button || button.disabled) return;
    switch (button.dataset.normal) {
      case 'library': await savePosition(); await request('show-manager'); break;
      case 'catalog': toggleCatalog(root.querySelector('#normal-catalog').hidden); break;
      case 'catalog-more': catalogLimit += 300; renderCatalog(); break;
      case 'pin': await request('settings', {alwaysOnTop:!state.settings.alwaysOnTop}); await refresh(); break;
      case 'fullscreen': { const value = await request('normal-window'); await request('normal-window', {fullscreen:!value.fullscreen}); await refresh(); break; }
      case 'stealth': await savePosition(); await request('reading-mode', {mode:'stealth'}); break;
      case 'bookmark': await savePosition(); await request('bookmark'); notify('书签已保存'); break;
      case 'previous': if (section?.previous !== null) await seek(section.previous); break;
      case 'next': if (section?.next !== null) await seek(section.next); break;
    }
  }));
  function toggleCatalog(open) { const position = section ? positionAtTop() : 0; root.querySelector('#normal-catalog').hidden = !open; root.querySelector('[data-normal=catalog]').setAttribute('aria-expanded', String(open)); if (section) restorePosition(position); }
  root.addEventListener('input', event => {
    if (event.target.id === 'normal-catalog-query') { catalogLimit = 300; renderCatalog(); }
    if (event.target.id === 'normal-font') { const position = positionAtTop(); document.body.style.setProperty('--normal-font-size', event.target.value + 'px'); root.querySelector('#normal-font-value').textContent = event.target.value + 'px'; restorePosition(position); }
    if (event.target.id === 'normal-progress') root.querySelector('#normal-progress-value').textContent = (Number(event.target.value) / 100).toFixed(2) + '%';
  });
  root.addEventListener('change', event => task(async () => {
    const input = event.target;
    if (input.id === 'normal-book') { await savePosition(); await request('open', {id:input.value, mode:'normal'}); await refresh(); }
    if (input.id === 'normal-font') { await savePosition(scrollPosition); await request('settings', {normalFontSize:Number(input.value)}); }
    if (input.id === 'normal-theme') await request('settings', {normalTheme:input.value});
    if (input.id === 'normal-progress' && section) await seek(Math.floor(Number(input.value) / 10000 * (section.length - 1)));
  }));
  content.addEventListener('scroll', () => {
    if (restoring || !section || document.visibilityState !== 'visible') return;
    clearTimeout(saveTimer); saveTimer = setTimeout(() => task(() => savePosition()), 200);
  }, {passive:true});
  document.addEventListener('keydown', event => {
    if (event.key === 'F11') { event.preventDefault(); root.querySelector('[data-normal=fullscreen]').click(); return; }
    if (event.key === 'Escape') { task(async () => { await request('normal-window', {fullscreen:false}); await refresh(); }); return; }
    if (/^(INPUT|SELECT|TEXTAREA|BUTTON)$/.test(event.target.tagName) || event.ctrlKey || event.altKey || event.metaKey) return;
    if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') { event.preventDefault(); root.querySelector('[data-normal=' + (event.key === 'ArrowLeft' ? 'previous' : 'next') + ']').click(); }
  });
  window.reader.onChanged(() => { clearTimeout(refreshTimer); refreshTimer = setTimeout(() => task(refresh), 100); });
  window.flushNormalProgress = () => savePosition();
  const tickTimer = setInterval(() => { if (section && document.visibilityState === 'visible') task(() => request('reading-tick', {seconds:15})); }, 15000);
  addEventListener('unload', () => { clearInterval(tickTimer); clearTimeout(saveTimer); clearTimeout(refreshTimer); });
  addEventListener('resize', () => { if (section) restorePosition(scrollPosition); });
  toggleCatalog(innerWidth > 900); task(refresh);
})();
