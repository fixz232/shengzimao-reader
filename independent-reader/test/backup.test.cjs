'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { Library } = require('../app/library.cjs');
const { exportBackup, importBackup } = require('../app/backup.cjs');

const fixture = () => fs.mkdtempSync(path.join(os.tmpdir(), 'szm-backup-'));

test('导出后可在全新配置目录完整恢复书库、进度与书签', async () => {
  const root = fixture();
  const source = path.join(root, 'source');
  const library = new Library(source);
  const file = path.join(root, '小说.txt');
  fs.writeFileSync(file, '第一章 开始\n' + '恢复测试的合成正文。'.repeat(200));
  const { book } = await library.importFile(file);
  library.open(book.id); library.move({ position: 180 }); library.bookmark();
  const exported = await exportBackup({ dataDir: source, library, target: path.join(root, '备份.szmbak') });
  assert.equal(exported.books, 1); assert.equal(exported.included, 1); assert.ok(exported.size > 0);

  const target = path.join(root, 'target');
  const restored = new Library(target);
  const report = await importBackup({ dataDir: target, library: restored, source: path.join(root, '备份.szmbak') });
  assert.equal(report.imported, 1); assert.equal(report.invalid, 0); assert.equal(report.missingText, 0);
  const copy = restored.state.books[0];
  assert.equal(copy.id, book.id);
  assert.equal(copy.position, 180);
  assert.equal(copy.bookmarks.length, 1);
  restored.open(copy.id);
  assert.ok(restored.read({ count: 40 }).text.includes('恢复测试的合成正文'));
});

test('重复导入同一备份只跳过不覆盖', async () => {
  const root = fixture();
  const source = path.join(root, 'source');
  const library = new Library(source);
  const file = path.join(root, 'a.txt');
  fs.writeFileSync(file, '第一章 测试\n' + '内容。'.repeat(120));
  const { book } = await library.importFile(file);
  library.open(book.id); library.move({ position: 60 });
  const backupFile = path.join(root, 'bak.szmbak');
  await exportBackup({ dataDir: source, library, target: backupFile });

  const target = path.join(root, 'target');
  const restored = new Library(target);
  await importBackup({ dataDir: target, library: restored, source: backupFile });
  restored.open(book.id); restored.move({ position: 300 });
  const again = await importBackup({ dataDir: target, library: restored, source: backupFile });
  assert.equal(again.imported, 0); assert.equal(again.skipped, 1);
  assert.equal(restored.state.books[0].position, 300, '重复导入不得回退已有的新进度');
});

test('备份文件缺少索引时明确报错', async () => {
  const root = fixture();
  const JSZip = require('jszip');
  const zip = new JSZip();
  zip.file('readme.txt', 'not a backup');
  fs.writeFileSync(path.join(root, 'bad.szmbak'), await zip.generateAsync({ type: 'nodebuffer' }));
  const library = new Library(path.join(root, 'lib'));
  await assert.rejects(
    importBackup({ dataDir: path.join(root, 'lib'), library, source: path.join(root, 'bad.szmbak') }),
    /缺少 library\.json/
  );
});
