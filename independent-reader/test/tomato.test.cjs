'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { normalizeSearchResponse, parseBookHtml, parseDirectoryResponse, parseChapterHtml, tomatoUrl, TomatoTasks } = require('../app/tomato.cjs');

test('番茄搜索结果标准化并去重', () => {
  const result = normalizeSearchResponse({ data: { books: [{ book_id: '123', title: '测试书', author: '作者' }, { book_id: '123', title: '重复' }] } });
  assert.equal(result.length, 1); assert.equal(result[0].url, 'https://fanqienovel.com/page/123');
});
test('番茄详情支持 INITIAL_STATE 赋值和目录多种字段', () => {
  const html = '<script>window.__INITIAL_STATE__ = {"bookName":"详情书","author":"甲","intro":"简介"};</script><h1>备用标题</h1>';
  assert.equal(parseBookHtml(html, '123').bookName, '详情书');
  const toc = parseDirectoryResponse({ data: { chapter_list: [{ chapter_id: '1', chapter_title: '第一章' }, { chapter_id: '1', chapter_title: '重复' }, { chapter_id: '2', title: '第二章' }] } }, '123');
  assert.deepEqual(toc.map(item => item.title), ['第一章', '第二章']);
});
test('番茄章节正文解析与 URL 限制', () => {
  assert.equal(parseChapterHtml('<article><p>这是公开正文内容，长度足够。</p></article>'), '这是公开正文内容，长度足够。');
  assert.throws(() => tomatoUrl('12'), /ID 无效/);
});
test('带 id 的 JSON 脚本支持换行与引号，不先当 HTML 处理破坏 JSON', () => {
  const state={props:{book:{bookName:'脚本详情书',author:'作者',abstract:'简介里有 "引号" 和 <b>正文</b>',wordNumber:12345}}};
  for(const id of ['__INITIAL_STATE__','__NEXT_DATA__']){
    const html=`<script type="application/json" id="${id}">\n${JSON.stringify(state,null,2)}\n</script>`;
    const book=parseBookHtml(html,'123');assert.equal(book.bookName,'脚本详情书');assert.equal(book.wordCount,'12345');assert.equal(book.intro,'简介里有 "引号" 和 正文');
  }
});
test('番茄详情任务等待 pending 后才返回结果', async () => {
  const tasks = new TomatoTasks({ notify: () => {} });
  tasks.state.results = [{ bookId: '123', bookName: '测试', url: 'https://fanqienovel.com/page/123' }];
  const oldFetch = global.fetch;
  global.fetch = async url => ({ ok: true, text: async () => url.includes('/page/') ? '<h1>测试</h1><article>公开正文</article>' : JSON.stringify({ data: { chapterList: [{ id: '1', title: '第一章' }] } }) });
  try { const details = await tasks.details(0); assert.equal(details.toc.length, 1); } finally { global.fetch = oldFetch; }
});

test('番茄搜索使用可用的公开搜索接口和完整参数，过滤推荐及非小说内容', async () => {
  const oldFetch = global.fetch;
  let requestUrl;
  global.fetch = async url => {
    requestUrl = new URL(url);
    return { ok: true, text: async () => JSON.stringify({ code: 0, data: { ret_data: [
      { book_id: '7276384138653862966', title: '我不是戏神', author: '三九音域', genre: '0' },
      { book_id: '7387309822351264830', title: '我不是戏神', author: '有声主播', genre: '4' },
      { book_id: '7639588356636036120', title: '我不是戏神', author: '漫画', genre: '905' },
      { book_id: '6982529841564224526', title: '我在精神病院学斩神', author: '三九音域', genre: '0' },
      { book_id: 'invalid', title: '我不是戏神', genre: '0' }
    ] } }) };
  };
  try {
    const tasks = new TomatoTasks(); tasks.search('我不是戏神'); await tasks.pending;
    assert.equal(requestUrl.hostname, 'novel.snssdk.com');
    assert.equal(requestUrl.pathname, '/api/novel/channel/homepage/search/search/v1/');
    assert.equal(requestUrl.searchParams.get('q'), '我不是戏神');
    assert.equal(requestUrl.searchParams.get('aid'), '1967');
    assert.equal(requestUrl.searchParams.get('device_platform'), 'android');
    assert.equal(tasks.state.error, ''); assert.equal(tasks.state.results.length, 1);
    assert.equal(tasks.state.results[0].author, '三九音域');
  } finally { global.fetch = oldFetch; }
});

test('空响应、HTML 验证页和业务错误保留可重试提示，不误报无结果', async () => {
  const oldFetch = global.fetch;
  try {
    for (const body of ['', '<html>验证</html>', '{"code":-2,"data":{}}']) {
      global.fetch = async () => ({ ok: true, text: async () => body });
      const tasks = new TomatoTasks(); tasks.search('书名'); await tasks.pending;
      assert.equal(tasks.state.running, false);
      assert.match(tasks.state.error, /重试|稍后/);
      assert.doesNotMatch(tasks.state.error, /无法解析的数据/);
      assert.doesNotMatch(tasks.state.message, /没有找到/);
    }
    global.fetch = async () => ({ ok: true, text: async () => '{"code":0,"data":{"ret_data":[]}}' });
    const tasks = new TomatoTasks(); tasks.search('空结果'); await tasks.pending;
    assert.equal(tasks.state.error, ''); assert.match(tasks.state.message, /没有找到/);
  } finally { global.fetch = oldFetch; }
});

test('嵌套初始状态保留书籍简介、分类、封面和真实字数，不取作者简介', () => {
  const state = { common: { nested: {} }, page: { bookName: '含有 } 和 " 的书', author: '作者', status: 1, creationStatus: 0, categoryV2: JSON.stringify([{ Name: '都市高武' }]), abstract: '这是小说简介', description: '这是作者介绍', wordNumber: 123456, thumbUri: 'https://example.com/book.png', lastChapterTitle: '完本感言', chapterTotal: 12 } };
  const html = `<script>(function(){window.__INITIAL_STATE__=${JSON.stringify(state)};})()</script>`;
  const book = parseBookHtml(html, '123');
  assert.equal(book.bookName, state.page.bookName); assert.equal(book.intro, '这是小说简介');
  assert.equal(book.wordCount, '123456'); assert.equal(book.category, '都市高武');
  assert.equal(book.status, '已完结'); assert.equal(book.cover, state.page.thumbUri);
  assert.equal(book.latestChapter, '完本感言'); assert.equal(book.chapterCount, 12);
});

test('书籍 ID 和官网链接直接读取详情页，不把 ID 当关键词发起搜索', async () => {
  const oldFetch = global.fetch;
  const calls = [];
  global.fetch = async url => { calls.push(url); return { ok: true, text: async () => '<h1>直接定位书籍</h1>' }; };
  try {
    for (const query of ['7276384138653862966', 'https://fanqienovel.com/page/7276384138653862966']) {
      const tasks = new TomatoTasks(); tasks.search(query); await tasks.pending;
      assert.equal(tasks.state.error, ''); assert.equal(tasks.state.results[0].bookName, '直接定位书籍');
      assert.equal(calls.at(-1), 'https://fanqienovel.com/page/7276384138653862966');
    }
  } finally { global.fetch = oldFetch; }
});

test('取消后立即重搜，旧响应不会覆盖新任务或取消消息', async () => {
  const oldFetch = global.fetch;
  let resolveOld;
  global.fetch = async url => {
    const q = new URL(url).searchParams.get('q');
    if (q === '旧书') return new Promise(resolve => { resolveOld = resolve; });
    return { ok: true, text: async () => '{"code":0,"data":{"ret_data":[{"book_id":"456","title":"新书"}]}}' };
  };
  try {
    const tasks = new TomatoTasks(); tasks.search('旧书'); const oldPending = tasks.pending;
    await new Promise(resolve => setImmediate(resolve));
    assert.throws(() => tasks.search('正在运行时的搜索'), /正在运行/);
    assert.equal(tasks.state.keyword, '旧书');
    tasks.cancel(); assert.equal(tasks.state.message, '番茄任务已取消。');
    tasks.search('新书'); await tasks.pending;
    resolveOld({ ok: true, text: async () => '{"code":0,"data":{"ret_data":[{"book_id":"123","title":"旧书"}]}}' });
    await oldPending;
    assert.equal(tasks.state.keyword, '新书'); assert.equal(tasks.state.results[0].bookName, '新书');
    assert.equal(tasks.state.error, ''); assert.match(tasks.state.message, /找到 1 本/);
  } finally { global.fetch = oldFetch; }
});
