'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const { TomatoTasks, parseChapterHtml, parseDirectoryResponse } = require('../app/tomato.cjs');
const { parseBook } = require('../app/importer.cjs');
const { Library } = require('../app/library.cjs');

const book = { bookId: '123', bookName: '下载测试书', author: '测试作者', intro: '搜索简介', url: 'https://fanqienovel.com/page/123' };
const toc = [{ id: '456', title: '第一章', url: 'https://fanqienovel.com/reader/456' }, { id: '789', title: '第二章', url: 'https://fanqienovel.com/reader/789' }];
function mockFetch(t, handler) {
  const previous = global.fetch;
  global.fetch = handler;
  t.after(() => { global.fetch = previous; });
}
const response = (body, status = 200) => ({ ok: status >= 200 && status < 300, status, text: async () => typeof body === 'string' ? body : JSON.stringify(body) });
async function harness(t, importPaths) {
  const profile = await fs.mkdtemp(path.join(os.tmpdir(), 'szm-tomato-download-'));
  t.after(() => fs.rm(profile, { recursive: true, force: true }));
  const tasks = new TomatoTasks({ profile, importPaths });
  tasks.state.results = [{ ...book }];
  return tasks;
}

test('章节正文匹配完整 class，跳过字号容器和标题，保留所有段落', () => {
  const html = '<div class="muye-reader-box muye-reader-content-16"><div><h1>第一章</h1></div><div class="muye-reader-content noselect"><div><p>第一段<span>文字</span>。</p><p>第二段。</p><p>第三段。</p></div></div></div>';
  assert.equal(parseChapterHtml(html), '第一段文字。\n\n第二段。\n\n第三段。');
  assert.equal(parseChapterHtml('<div class="reader-content"><p>甲</p><p>乙</p></div>'), '甲\n\n乙');
  assert.equal(parseChapterHtml('<div class="muye-reader-content-16"><h1>只有标题</h1></div>'), '');
});

test('详情页 404 时仍读取目录并保留搜索信息，章节页 404 后标明正文不可用', async t => {
  const tasks = await harness(t);
  mockFetch(t, async url => url.includes('/page/') ? response('not found', 404) : response({ code: 0, data: { chapterList: toc } }));
  const details = await tasks.details(0);
  assert.equal(details.book.author, book.author);
  assert.equal(details.book.intro, book.intro);
  assert.equal(details.toc.length, 2);
  assert.match(details.book.detailWarning, /电脑网页|网页端/);
  assert.equal(tasks.state.error, '');
  global.fetch = async () => response('not found', 404);
  await tasks.download(0, 'epub');
  assert.match(tasks.state.results[0].unavailableReason, /电脑网页正文/);
  let calls = 0;
  global.fetch = async () => { calls++; throw new Error('不应继续请求正文'); };
  await tasks.download(0, 'epub');
  assert.equal(calls, 0);
  assert.match(tasks.state.error, /聚合搜书|其他书源/);
  assert.doesNotMatch(tasks.state.message, /下载完成/);
});

test('只有详情页 404 不阻止公开章节下载', async t => {
  const tasks = await harness(t, async () => ({ imported: 1, errors: [] }));
  mockFetch(t, async url => {
    if (url.includes('/page/')) return response('not found', 404);
    if (url.includes('/directory/')) return response({ data: { chapterList: [toc[0]] } });
    return response('<article><p>详情缺失但章节仍然公开。</p></article>');
  });
  await tasks.download(0);
  assert.equal(tasks.state.error, '');
  assert.match(tasks.state.message, /1 本已导入/);
});

test('详情网络错误不伪装成网页不可用，旧详情不冒充本次成功', async t => {
  const tasks = await harness(t);
  tasks.state.details = { book: { ...book }, toc };
  mockFetch(t, async () => response('server error', 503));
  await assert.rejects(tasks.details(0), /503/);
  assert.equal(tasks.state.details, null);
  assert.equal(tasks.state.results[0].unavailableReason, undefined);
});

for (const format of ['txt', 'epub']) test(`公开章节完整下载并导入 ${format.toUpperCase()}，临时文件清理`, async t => {
  let imported;
  const tasks = await harness(t, async (files, metadata, options) => {
    imported = { parsed: await parseBook(files[0]), metadata, signal: options.signal };
    const result = await library.importFile(files[0], undefined, { ...options, metadata });
    return { imported: result.duplicate ? 0 : 1, duplicates: result.duplicate ? 1 : 0, errors: [] };
  });
  const library = new Library(tasks.profile);
  mockFetch(t, async url => {
    if (url.includes('/page/')) return response('<h1>下载测试书</h1>');
    if (url.includes('/directory/')) return response({ data: { chapterList: toc } });
    return response('<div class="muye-reader-box muye-reader-content-16"><h1>章节标题</h1><div class="muye-reader-content"><div><p>完整第一段。</p><p>完整最后一段。</p></div></div></div>');
  });
  await tasks.download(0, format);
  assert.equal(tasks.state.error, '');
  assert.match(tasks.state.message, /1 本已导入/);
  assert.match(imported.parsed.text, /完整第一段/);
  assert.equal((imported.parsed.text.match(/完整最后一段/g) || []).length, 2);
  assert.equal(imported.metadata.author, book.author);
  assert.equal(imported.metadata.onlineChapters.length, 2);
  assert.equal(imported.signal.aborted, false);
  const saved = new Library(tasks.profile).snapshot().books;
  assert.equal(saved.length, 1);
  assert.equal(saved[0].title, book.bookName);
  assert.equal(saved[0].author, book.author);
  assert.equal(saved[0].onlineChapters.length, 2);
  assert.deepEqual(await fs.readdir(path.join(tasks.profile, 'tomato-jobs')), []);
});

test('目录保留付费和锁章标记，下载前拒绝未开放章节', async t => {
  for (const flags of [{ needPay: 1 }, { isChapterLock: true }, { isPaidPublication: true }, { isPaidStory: true }]) {
    const parsed = parseDirectoryResponse({ data: { chapterList: [{ ...toc[0], ...flags }] } });
    assert.equal(parsed[0].locked, true);
  }
  const tasks = await harness(t, async () => { assert.fail('锁章不能入库'); });
  tasks.state.details = { book: { ...book }, toc: [{ ...toc[0], locked: true }] };
  mockFetch(t, async () => { assert.fail('锁章不能请求正文'); });
  await tasks.download(0);
  assert.match(tasks.state.error, /付费|锁定|未开放/);
});

test('空正文、仅标题和字体编码正文不作为成功下载入库', async t => {
  const tasks = await harness(t, async () => { assert.fail('无有效正文不能入库'); });
  tasks.state.details = { book: { ...book }, toc: [toc[0]] };
  let currentHtml;
  mockFetch(t, async () => response(currentHtml));
  for (const html of ['<h1>第一章</h1>', '<article>第一章</article>', '<div class="muye-reader-content"><p>这是\uE400编码正文</p></div>']) {
    delete tasks.state.details.book.unavailableReason;
    currentHtml = html;
    await tasks.download(0);
    assert.match(tasks.state.error, /正文|字体/);
    assert.doesNotMatch(tasks.state.message, /下载完成/);
  }
});

test('导入失败报告真实错误并清理已写入的临时文件', async t => {
  const tasks = await harness(t, async () => ({ imported: 0, duplicates: 0, errors: [{ file: '测试.txt', message: '磁盘写入失败' }] }));
  tasks.state.details = { book: { ...book }, toc: [toc[0]] };
  mockFetch(t, async () => response('<article><p>有效公开正文。</p></article>'));
  await tasks.download(0);
  assert.match(tasks.state.error, /磁盘写入失败/);
  assert.doesNotMatch(tasks.state.message, /下载完成/);
  assert.deepEqual(await fs.readdir(path.join(tasks.profile, 'tomato-jobs')), []);
});

test('下载取消后迟到的章节响应不能入库或改写新搜索状态', async t => {
  const tasks = await harness(t, async () => { assert.fail('取消后不能入库'); });
  tasks.state.details = { book: { ...book }, toc: [toc[0]] };
  let resolveChapter;
  mockFetch(t, url => url.includes('/reader/') ? new Promise(resolve => { resolveChapter = resolve; }) : Promise.resolve(response({ data: { ret_data: [] } })));
  const pending = tasks.download(0);
  await new Promise(resolve => setImmediate(resolve));
  tasks.cancel();
  tasks.search('新搜索');
  await tasks.pending;
  resolveChapter(response('<article><p>迟到的正文。</p></article>'));
  await pending;
  assert.equal(tasks.state.keyword, '新搜索');
  assert.match(tasks.state.message, /没有找到/);
  assert.equal(tasks.state.error, '');
});
