'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { publicEditionFor, parseEditionDirectory, parseEditionChapter, downloadPublicEdition } = require('../app/public-editions.cjs');
const { decodeChapterFont } = require('../app/tomato-font.cjs');
const { TomatoTasks } = require('../app/tomato.cjs');
const { Library } = require('../app/library.cjs');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');

const book = { bookName: '毛泽东选集（第二卷）', author: '毛泽东 著', bookId: '7293805084226358313', url: 'https://fanqienovel.com/page/7293805084226358313' };
const directory = '# 第一卷 国内革命战争时期\n* [000-其他卷](./src/000-其他卷.md)\n\n# 第二卷 抗日战争时期（上）\n* [018-首篇](./src/018-首篇.md)\n* [019-末篇](./src/019-末篇.md)\n\n# 第三卷 抗日战争时期（下）\n* [058-其他卷](./src/058-其他卷.md)\n';
const markdown = title => `# ${title}  \n（一九三七年）  \n\n> 题解内容。\n\n## 小节标题\n\n${'这是公开版本的有效正文，不是番茄版权页或错误页面。'.repeat(10)}\n\n末尾正文。\n`;
const response = (body, status = 200) => ({ ok: status === 200, status, text: async () => body });
function mockFetch(t, handler) {
  const previous = global.fetch; global.fetch = handler;
  t.after(() => { global.fetch = previous; });
}

test('已知网页字体正确还原汉字，未知字体和缺字保持编码以供拒绝', () => {
  const html = '<style>@font-face{font-family:DNMrHsV173Pd4pgy;src:url(dc027189e0ba4cd.woff2)}</style>';
  const encoded = '\uE521\uE41E\uE4C2\uE463\uE48A';
  assert.equal(decodeChapterFont(html, encoded), '我是人民国');
  assert.equal(decodeChapterFont('', encoded), encoded);
  assert.equal(decodeChapterFont(html.replace('dc027189e0ba4cd', 'new-font'), encoded), encoded);
  assert.equal(decodeChapterFont(html, '\uE000'), '\uE000');
  assert.equal(decodeChapterFont(html, '原本可读的正文'), '原本可读的正文');
});

test('公开版仅按精确书名、分卷和作者匹配，目录不串卷', () => {
  assert.equal(publicEditionFor(book).volume, '二');
  assert.equal(publicEditionFor({ ...book, author: '其他作者' }), null);
  assert.equal(publicEditionFor({ ...book, bookName: '毛泽东选集解读（第二卷）' }), null);
  assert.equal(publicEditionFor({ ...book, bookName: '毛泽东选集' }), null);
  const toc = parseEditionDirectory(directory, '二');
  assert.deepEqual(toc.map(chapter => chapter.title), ['首篇', '末篇']);
  assert.ok(toc.every(chapter => chapter.url.startsWith('https://raw.githubusercontent.com/weiyinfu/MaoZeDongAnthology/')));
  assert.throws(() => parseEditionDirectory(directory, '五'), /分卷/);
  assert.throws(() => parseEditionDirectory(directory.replace('018-首篇.md', '../018-首篇.md'), '二'), /地址无效/);
});

test('公开版保留段落、题解和末段，标题不符和空正文不能入库', () => {
  const body = parseEditionChapter(markdown('首篇'), '首篇');
  assert.match(body, /题解内容/);
  assert.match(body, /小节标题/);
  assert.ok(body.endsWith('末尾正文。'));
  assert.doesNotMatch(body, /^#|^>/mu);
  assert.match(parseEditionChapter(markdown('首篇⑴'), '首篇'), /末尾正文/);
  assert.throws(() => parseEditionChapter(markdown('其他文章'), '首篇'), /标题不匹配/);
  assert.throws(() => parseEditionChapter('# 首篇\n空', '首篇'), /无效/);
});

for (const format of ['txt', 'epub']) test(`番茄 404 后真实导入公开版 ${format}，来源和目录持久化，重复下载仍可用`, async t => {
  const profile = await fs.mkdtemp(path.join(os.tmpdir(), 'szm-public-edition-'));
  t.after(() => fs.rm(profile, { recursive: true, force: true }));
  const library = new Library(profile);
  const tasks = new TomatoTasks({ profile, importPaths: async (files, metadata, options) => {
    const result = await library.importFile(files[0], undefined, { ...options, metadata });
    return { imported: result.duplicate ? 0 : 1, duplicates: result.duplicate ? 1 : 0 };
  } });
  tasks.state.results = [{ ...book }];
  tasks.state.details = { book: { ...book }, toc: [{ id: '456', title: '版权信息', url: 'https://fanqienovel.com/reader/456' }, { id: '789', title: '番茄锁定篇目', url: 'https://fanqienovel.com/reader/789', locked: true }] };
  let originalRequests = 0;
  mockFetch(t, async url => {
    if (url.startsWith('https://fanqienovel.com')) { originalRequests++; assert.ok(url.endsWith('/456'), '不能请求锁定章节'); return response('not found', 404); }
    if (url.endsWith('README.md')) return response(directory);
    return response(markdown(decodeURIComponent(url).includes('018-') ? '首篇' : '末篇'));
  });
  await tasks.download(0, format);
  assert.equal(tasks.state.error, '');
  assert.match(tasks.state.message, /公开文集下载完成/);
  assert.equal(tasks.state.details.toc.length, 2);
  const saved = new Library(profile).snapshot().books;
  assert.equal(saved.length, 1);
  assert.equal(saved[0].title, book.bookName);
  assert.equal(saved[0].chapters.length, 2);
  assert.match(saved[0].sourceName, /公开文集/);
  assert.equal(saved[0].tracking, false);
  assert.match(saved[0].onlineUrl, /github.com\/weiyinfu/);
  const body = await fs.readFile(path.join(profile, 'books', saved[0].file), 'utf8');
  assert.equal((body.match(/末尾正文/g) || []).length, 2);
  assert.match(body, /版本：公开文集版本/);
  await tasks.download(0, format);
  assert.equal(tasks.state.error, '');
  assert.match(tasks.state.message, /重复导入/);
  assert.equal(originalRequests, 1, '再次下载直接使用已显示的公开版目录');
  assert.deepEqual(await fs.readdir(path.join(profile, 'tomato-jobs')), []);
});

test('公开源网络失败或取消不能生成残缺书籍或迟到的成功消息', async t => {
  const controller = new AbortController();
  mockFetch(t, async url => url.endsWith('README.md') ? response(directory) : response('unavailable', 503));
  await assert.rejects(downloadPublicEdition(publicEditionFor(book), { signal: controller.signal }), /503/);
  let resolveBody;
  global.fetch = async url => url.endsWith('README.md') ? response(directory) : new Promise(resolve => { resolveBody = resolve; });
  let progress = 0;
  const pending = downloadPublicEdition(publicEditionFor(book), { signal: controller.signal, onProgress: () => progress++ });
  await new Promise(resolve => setImmediate(resolve));
  controller.abort(); resolveBody(response(markdown('首篇')));
  await assert.rejects(pending, /abort/i);
  assert.equal(progress, 0);
});

test('连接失败使用同一提交的CDN并保持后续连接，来源拒绝访问时不换域重试', async t => {
  const requested=[];
  mockFetch(t, async (url, options) => {
    requested.push(url); assert.equal(options.redirect, 'error');
    if (url.startsWith('https://raw.githubusercontent.com')) throw new TypeError('fetch failed');
    assert.ok(url.startsWith('https://cdn.jsdelivr.net/gh/weiyinfu/MaoZeDongAnthology@cf86018096980815a5b885dd9d7211da29fbf287/'));
    return response(url.endsWith('README.md') ? directory : markdown(decodeURIComponent(url).includes('018-') ? '首篇' : '末篇'));
  });
  const result=await downloadPublicEdition(publicEditionFor(book));
  assert.equal(result.parts.length, 2);
  assert.equal(requested.filter(url=>url.startsWith('https://raw.githubusercontent.com')).length, 1);
  let denied=0;
  global.fetch=async()=>{denied++;return response('denied',403);};
  await assert.rejects(downloadPublicEdition(publicEditionFor(book)), /403/);
  assert.equal(denied, 1);
});
