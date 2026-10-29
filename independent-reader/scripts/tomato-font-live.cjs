'use strict';
const fs = require('node:fs/promises'), path = require('node:path'), assert = require('node:assert/strict');
const { TomatoTasks } = require('../app/tomato.cjs');
const { Library } = require('../app/library.cjs');
(async () => {
  const evidence = path.resolve(__dirname, '..', '.verification', 'tomato-font-live-' + Date.now());
  const library = new Library(path.join(evidence, 'profile'));
  const tasks = new TomatoTasks({ profile: library.root, importPaths: async (files, metadata, options) => {
    const result = await library.importFile(files[0], undefined, { ...options, metadata });
    return { imported: result.duplicate ? 0 : 1, duplicates: result.duplicate ? 1 : 0, errors: [] };
  } });
  tasks.search('我不是戏神'); await tasks.pending;
  assert.equal(tasks.state.error, '');
  const index = tasks.state.results.findIndex(book => book.bookId === '7276384138653862966');
  assert.ok(index >= 0);
  const details = await tasks.details(index), allCount = details.toc.length;
  const available = details.toc.map((chapter, position) => ({ chapter, position })).filter(item => !item.chapter.locked);
  assert.ok(available.length >= 3, '至少需要3个未锁定的公开章节');
  const positions = [available[0].position, available[Math.floor(available.length / 2)].position, available.at(-1).position];
  // Deliberately sample three real chapters; this does not claim a whole-novel download.
  tasks.state.details.toc = positions.map(position => details.toc[position]);
  await tasks.download(index, 'txt');
  assert.equal(tasks.state.error, '');
  const book = new Library(library.root).snapshot().books[0];
  assert.ok(book); assert.equal(book.chapters.length, 3);
  const body = await fs.readFile(path.join(library.root, 'books', book.file), 'utf8');
  assert.doesNotMatch(body, /[\uE000-\uF8FF]/u);
  assert.ok(body.includes('我……是谁？'));
  assert.ok(body.length > 5000);
  const report = { title: book.title, actualSource: book.sourceName, fullCatalogueCount: allCount, availableChapterCount: available.length, sampledPositions: positions,
    importedChapters: book.chapters, characters: body.length, privateUseCharacters: false, persisted: true,
    scope: '真实番茄未锁定公开章节的首、中、末3章字体还原与入库；未下载整部小说' };
  await fs.writeFile(path.join(evidence, 'report.json'), JSON.stringify(report, null, 2));
  console.log(JSON.stringify({ evidence, report }, null, 2));
})().catch(error => { console.error(error); process.exitCode = 1; });
