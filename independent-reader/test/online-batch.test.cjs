'use strict';
process.env.SZM_ALLOW_PRIVATE_URLS = '1'; // 与现有测试一致：本地合成链接不触发 DNS 复检
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { OnlineTasks, filterResults, aggregateSources } = require('../app/online.cjs');
function fixture(t) { const root = fs.mkdtempSync(path.join(os.tmpdir(), 'szm-online-batch-')); t.after(() => fs.rmSync(root, { recursive: true, force: true })); return root; }
const books = (...pairs) => pairs.map(([url, bookName, sourceId, sourceName]) => ({ url, bookName, author: '测试作者', sourceId, sourceName }));
const waitEmpty = async dir => { for (let i = 0; i < 80; i++) { if (!fs.existsSync(dir) || !fs.readdirSync(dir).length) return true; await new Promise(resolve => setTimeout(resolve, 10)); } return false; };
// 假引擎：不启动 Java。modes 控制每条链接的行为（ok/throw/slow），可中途改写用于重试场景。
function harness(t, { modes = {}, searchResult = [], duplicates = [], searchError = '' } = {}) {
  const root = fixture(t), calls = [], imports = [], snapshots = [];
  const state = { modes, searchError, searchResult, duplicates };
  let online, active = 0, peak = 0;
  const runner = async request => {
    const { input, signal } = request;
    calls.push({ input, states: online.state.batch ? online.state.batch.items.map(item => item.state) : [] });
    active += 1; peak = Math.max(peak, active);
    try {
      const mode = state.modes[input.url] || 'ok';
      if (mode === 'slow') await new Promise(resolve => { const timer = setTimeout(resolve, 250); signal.addEventListener('abort', () => { clearTimeout(timer); resolve(); }, { once: true }); });
      else await new Promise(resolve => setTimeout(resolve, 5));
      if (signal.aborted) throw new Error('下载被中断。');
      if (mode === 'throw') throw new Error('合成下载失败。');
      if (input.action === 'search') { if (state.searchError) throw new Error(state.searchError); return { work: path.join(root, 'jobs', 'search'), data: state.searchResult }; }
      const work = path.join(root, 'jobs', String(calls.length));
      const file = path.join(work, 'downloads', `合成书.${input.format}`);
      fs.mkdirSync(path.dirname(file), { recursive: true }); fs.writeFileSync(file, '第一章 合成正文');
      return { work, data: [file] };
    } finally { active -= 1; }
  };
  online = new OnlineTasks({ notify: () => snapshots.push(online.snapshot()), importPaths: async (files, metadata) => { imports.push({ files: [...files], metadata }); return state.duplicates.includes(metadata.onlineUrl) ? { imported: 0, duplicates: 1, errors: [] } : { imported: 1, duplicates: 0, errors: [] }; }, runner });
  online.state.results = [];
  return { root, online, calls, imports, snapshots, state, peak: () => peak, setBooks: list => { online.state.results = list.map(book => ({ ...book })); } };
}
test('批量下载串行执行，状态按 pending→downloading→done 流转并保留 batch', async t => {
  const h = harness(t); h.setBooks(books(['https://example.com/a', '书甲', 's1', '站点一'], ['https://example.com/b', '书乙', 's1', '站点一'], ['https://example.com/c', '书丙', 's2', '站点二']));
  const started = h.online.downloadMany([0, 1, 2], 'txt');
  assert.equal(started.running, true); assert.equal(started.batch.total, 3); assert.equal(started.batch.format, 'txt');
  assert.deepEqual(started.batch.items.map(item => [item.index, item.bookName, item.sourceName]), [[0, '书甲', '站点一'], [1, '书乙', '站点一'], [2, '书丙', '站点二']]);
  assert.deepEqual(started.batch.items.map(item => item.state), ['downloading', 'pending', 'pending']);
  await h.online.pending;
  assert.equal(h.calls.length, 3); assert.equal(h.peak(), 1); // 串行：任一时刻只有一个引擎任务
  assert.deepEqual(h.calls[0].states, ['downloading', 'pending', 'pending']);
  assert.deepEqual(h.calls[1].states, ['done', 'downloading', 'pending']);
  assert.deepEqual(h.calls[2].states, ['done', 'done', 'downloading']);
  const batch = h.online.state.batch;
  assert.deepEqual(batch.items.map(item => item.state), ['done', 'done', 'done']);
  assert.equal(batch.done, 3); assert.equal(batch.failed, 0); assert.equal(batch.total, 3);
  assert.equal(h.online.state.running, false); assert.match(h.online.state.message, /批量下载结束：成功 3 本/);
  assert.equal(h.imports.length, 3); assert.equal(h.imports[0].metadata.sourceName, '站点一');
  assert.notEqual(h.online.snapshot().batch, batch); assert.notEqual(h.online.snapshot().batch.items[0], batch.items[0]); // snapshot 深拷贝
  assert.ok(await waitEmpty(path.join(h.root, 'jobs')), '每条完成后应清理该条任务目录');
});
test('批量下载中途取消后剩余条目与当前条目均为 skipped', async t => {
  const h = harness(t, { modes: { 'https://example.com/a': 'slow' } }); h.setBooks(books(['https://example.com/a', '书甲', 's1', '站点一'], ['https://example.com/b', '书乙', 's1', '站点一'], ['https://example.com/c', '书丙', 's2', '站点二']));
  h.online.downloadMany([0, 1, 2], 'epub');
  await new Promise(resolve => setTimeout(resolve, 30));
  const cancelled = h.online.cancel();
  assert.equal(cancelled.message, '任务已取消。');
  await h.online.pending;
  assert.deepEqual(h.online.state.batch.items.map(item => item.state), ['skipped', 'skipped', 'skipped']);
  assert.deepEqual(h.online.state.batch.items.map(item => item.message), ['已取消', '已取消', '已取消']);
  assert.equal(h.online.state.batch.done, 0); assert.equal(h.online.state.batch.failed, 0);
  assert.equal(h.online.state.running, false); assert.equal(h.online.state.message, '任务已取消。');
  assert.equal(h.calls.length, 1); // 取消后不再启动新的引擎任务
});
test('已入库的重复书计为 skipped 而非 failed', async t => {
  const h = harness(t, { duplicates: ['https://example.com/b'] }); h.setBooks(books(['https://example.com/a', '书甲', 's1', '站点一'], ['https://example.com/b', '书乙', 's1', '站点一']));
  h.online.downloadMany([0, 1], 'txt'); await h.online.pending;
  const batch = h.online.state.batch;
  assert.deepEqual(batch.items.map(item => item.state), ['done', 'skipped']);
  assert.equal(batch.items[1].message, '已在书库'); assert.equal(batch.done, 1); assert.equal(batch.failed, 0);
});
test('单条下载失败只记录 failed，后续条目继续执行', async t => {
  const h = harness(t, { modes: { 'https://example.com/b': 'throw' } }); h.setBooks(books(['https://example.com/a', '书甲', 's1', '站点一'], ['https://example.com/b', '书乙', 's1', '站点一'], ['https://example.com/c', '书丙', 's2', '站点二']));
  h.online.downloadMany([0, 1, 2], 'txt'); await h.online.pending;
  const batch = h.online.state.batch;
  assert.deepEqual(batch.items.map(item => item.state), ['done', 'failed', 'done']);
  assert.equal(batch.items[1].message, '合成下载失败。'); assert.equal(batch.failed, 1); assert.equal(batch.done, 2);
  assert.equal(h.online.state.error, ''); assert.equal(h.calls.length, 3); assert.equal(h.imports.length, 2);
});
test('批量下载超过 30 条或索引非法时拒绝执行', async t => {
  const h = harness(t); h.setBooks(Array.from({ length: 31 }, (_, index) => ({ url: `https://example.com/${index}`, bookName: `书${index}`, sourceId: 's1', sourceName: '站点一' })));
  assert.throws(() => h.online.downloadMany(Array.from({ length: 31 }, (_, index) => index), 'txt'), /最多 30 条/);
  assert.equal(h.online.state.batch, null); assert.equal(h.online.state.running, false);
  assert.throws(() => h.online.downloadMany([0, '1'], 'txt'), /选择书籍/);
  assert.throws(() => h.online.downloadMany([0, 99], 'txt'), /选择书籍/);
  assert.throws(() => h.online.downloadMany(0, 'txt'), /请先选择/);
  assert.throws(() => h.online.downloadMany([0], 'zip'), /格式/);
  assert.equal(h.calls.length, 0);
});
test('搜索要求多个关键词同时命中书名或作者，并过滤无关书籍', async t => {
  const searchResult = [
    { url: 'https://example.com/1', bookName: '三体', author: '刘慈欣', sourceId: 'a', sourceName: '站点甲', intro: '' },
    { url: 'https://example.com/2', bookName: '三体前传', author: '宝树', sourceId: 'a', sourceName: '站点甲', intro: '' },
    { url: 'https://example.com/3', bookName: '红楼梦', author: '曹雪芹', sourceId: 'b', sourceName: '站点乙', intro: '古代家族故事' },
    { url: 'https://example.com/4', bookName: '宇宙', author: '未知', sourceId: 'a', sourceName: '站点甲', intro: '关于三体 刘慈欣的作品' },
  ];
  const h = harness(t, { searchResult });
  const started = h.online.search('三体 刘慈欣');
  assert.equal(started.running, true); assert.equal(started.elapsedMs, 0);
  await h.online.pending;
  assert.equal(h.calls[0].input.keyword, '三体 刘慈欣'); // 引擎协议不变：原串直接透传
  assert.deepEqual(h.online.state.results.map(book => book.matchScore), [100]);
  assert.equal(h.online.state.results.length, 1); // 不再因简介碰巧含词而误命中
  assert.deepEqual(h.online.state.sources, [{ sourceId: 'a', sourceName: '站点甲', count: 1 }]);
  assert.equal(h.online.snapshot().sources.length, 1); assert.equal(h.online.state.error, '');
  assert.ok(h.online.state.elapsedMs > 0, '结束时写入 online.elapsedMs');
});
test('搜索成功后重新 start 会清空 batch 与 sources', async t => {
  const h = harness(t, { searchResult: [{ url: 'https://example.com/1', bookName: '三体', author: '刘慈欣', sourceId: 'a', sourceName: '站点甲' }] });
  h.online.state.batch = { format: 'txt', total: 0, done: 0, failed: 0, items: [] };
  h.online.state.sources = [{ sourceId: 'a', sourceName: '站点甲', count: 1 }];
  h.online.search('三体'); await h.online.pending;
  assert.equal(h.online.state.batch, null);
  assert.deepEqual(h.online.state.sources.map(source => source.sourceId), ['a']);
  h.online.search('刘慈欣'); await h.online.pending;
  assert.equal(h.online.state.batch, null);
});
test('filterResults/aggregateSources 为纯函数且 matchScore 落在 0–100', () => {
  const scored = filterResults('三体', [{ bookName: '三体', author: '' }, { bookName: '三体三体三体', author: '三体' }, { bookName: '无关', author: '', intro: '依然无关' }]);
  assert.deepEqual(scored.map(book => book.matchScore), [100, 100]);
  assert.equal(filterResults('', [{ bookName: '三体' }])[0].matchScore, 0);
  assert.deepEqual(aggregateSources([{ sourceId: 'a', sourceName: '甲' }, { sourceId: 'b', sourceName: '乙' }, { sourceId: 'a', sourceName: '甲' }, { sourceId: 'a', sourceName: '甲' }]), [{ sourceId: 'a', sourceName: '甲', count: 3 }, { sourceId: 'b', sourceName: '乙', count: 1 }]);
  assert.deepEqual(aggregateSources([]), []);
});
test('running 时 downloadMany/retryFailed 抛已有在线任务', async t => {
  const h = harness(t, { modes: { 'https://example.com/a': 'slow' } }); h.setBooks(books(['https://example.com/a', '书甲', 's1', '站点一'], ['https://example.com/b', '书乙', 's1', '站点一']));
  h.online.downloadMany([0, 1], 'txt');
  assert.throws(() => h.online.downloadMany([0], 'txt'), /已有在线任务正在运行/);
  assert.throws(() => h.online.retryFailed(), /已有在线任务正在运行/);
  h.online.cancel(); await h.online.pending;
});
test('retryFailed 只重试批量里 failed 的条目，否则重跑上次失败的搜索', async t => {
  const h = harness(t, { modes: { 'https://example.com/b': 'throw' } }); h.setBooks(books(['https://example.com/a', '书甲', 's1', '站点一'], ['https://example.com/b', '书乙', 's1', '站点一']));
  h.online.downloadMany([0, 1], 'epub'); await h.online.pending;
  assert.equal(h.online.state.batch.failed, 1);
  h.state.modes['https://example.com/b'] = 'ok';
  const retried = h.online.retryFailed();
  assert.equal(retried.batch.total, 1); assert.equal(retried.batch.format, 'epub');
  await h.online.pending;
  assert.deepEqual(h.online.state.batch.items.map(item => [item.index, item.state]), [[1, 'done']]);
  assert.equal(h.online.state.batch.failed, 0);
  // 搜索失败后再重试同关键词
  h.state.searchError = '引擎暂时不可用。';
  h.online.search('三体'); await h.online.pending;
  assert.equal(h.online.state.error, '引擎暂时不可用。'); assert.equal(h.online.state.keyword, '三体');
  h.state.searchError = ''; h.state.searchResult = [{ url: 'https://example.com/1', bookName: '三体', author: '刘慈欣', sourceId: 'a', sourceName: '站点甲' }];
  h.online.retryFailed(); await h.online.pending;
  assert.equal(h.online.state.error, ''); assert.equal(h.online.state.results.length, 1); assert.equal(h.calls.at(-1).input.keyword, '三体');
  // 无失败任务时明确报错
  assert.throws(() => h.online.retryFailed(), /没有需要重试/);
});
