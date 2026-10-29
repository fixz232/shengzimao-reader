'use strict';
// Network acceptance check. Uses an isolated library, the production downloader,
// the real import worker, and a second Library instance to verify persistence.
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const { TomatoTasks } = require('../app/tomato.cjs');
const { Library } = require('../app/library.cjs');
const { parseBook } = require('../app/importer.cjs');

(async () => {
  const evidence = path.resolve(__dirname, '..', '.verification', 'tomato-live-' + Date.now());
  await fs.mkdir(evidence, { recursive: true });
  const library = new Library(path.join(evidence, 'profile'));
  let exported;
  const tasks = new TomatoTasks({ profile: library.root, notify: () => {
    if (/正在下载/.test(tasks.state.message)) console.log(tasks.state.message);
  }, importPaths: async (files, metadata, options) => {
    exported = path.join(evidence, metadata.title + path.extname(files[0]));
    await fs.copyFile(files[0], exported);
    const result = await library.importFile(files[0], undefined, { ...options, metadata });
    return { imported: result.duplicate ? 0 : 1, duplicates: result.duplicate ? 1 : 0, errors: [] };
  } });
  tasks.search('毛泽东选集'); await tasks.pending;
  assert.equal(tasks.state.error, '');
  const index = tasks.state.results.findIndex(book => book.bookId === '7293805084226358313');
  assert.ok(index >= 0, '真实搜索必须找到用户截图中的书籍 ID');
  await tasks.download(index, 'epub');
  assert.equal(tasks.state.error, '');
  assert.match(tasks.state.message, /1 本已导入/);
  const saved = new Library(library.root).snapshot().books;
  assert.equal(saved.length, 1);
  const book = saved[0];
  const parsed = await parseBook(exported);
  const body = await fs.readFile(path.join(library.root, 'books', book.file), 'utf8');
  assert.equal(book.onlineChapters.length, 40);
  assert.equal(book.chapters.length, 40);
  assert.match(body, /反对日本进攻的方针、办法和前途/);
  assert.match(body, /论持久战/);
  assert.match(body, /关于打退第二次反共高潮的总结/);
  assert.ok(body.length > 100000);
  assert.doesNotMatch(body, /[\uE000-\uF8FF]/u);
  assert.match(book.sourceName, /公开文集/);
  assert.equal(book.tracking, false);
  assert.equal(parsed.text, body);
  await fs.writeFile(path.join(evidence, book.title + '.txt'), body);
  const report = { title: book.title, bookId: '7293805084226358313', actualSource: book.sourceName, sourceUrl: book.onlineUrl,
    originalDirectory: 42, downloadedChapters: book.onlineChapters.length, readerChapters: book.chapters.length,
    characters: body.length, first: book.chapters[0], middle: book.chapters[20], last: book.chapters.at(-1),
    imported: true, persisted: true, epubParsed: true, textExported: true, privateUseCharacters: false, exported, profile: library.root };
  await fs.writeFile(path.join(evidence, 'report.json'), JSON.stringify(report, null, 2));
  console.log(JSON.stringify({ evidence, report }, null, 2));
})().catch(error => { console.error(error); process.exitCode = 1; });
