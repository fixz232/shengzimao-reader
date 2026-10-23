'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const window = {};
for (const file of ['features.js', 'manager-view.js']) vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../app/ui', file), 'utf8'), { window });
function render(searchTab, overrides = {}, modelOverrides = {}) {
  const state = { books: [], settings: { searchHistory: [] }, consent: { accepted: true }, online: { results: [], running: false }, tomato: { results: [], running: false, keyword: '旧关键词' }, ...overrides };
  const model = { route: 'search', searchTab, onlineQuery: '', onlineSite: '', onlineSort: 'match', onlineFormat: 'epub', tomatoQuery: '新关键词', dialog: null, ...modelOverrides };
  return window.ReaderViews.render(state, model);
}
test('在线搜书内提供聚合和番茄标签，侧栏不再单列番茄小说', () => {
  for (const tab of ['aggregate', 'tomato']) {
    const html = render(tab);
    assert.match(html, /data-route="search" aria-current="page"/);
    assert.doesNotMatch(html, /data-route="tomato"/);
    assert.match(html, /data-tab="searchTab" data-value="aggregate"/);
    assert.match(html, /data-tab="searchTab" data-value="tomato"/);
    assert.equal((html.match(/<h1>/g) || []).length, 1);
    assert.match(html, new RegExp(`data-value="${tab}" aria-pressed="true"`));
  }
  assert.match(render('aggregate'), /id="search-form"/);
  assert.doesNotMatch(render('aggregate'), /id="tomato-search-form"/);
  assert.match(render('tomato'), /id="tomato-search-form"/);
  assert.doesNotMatch(render('tomato'), /id="search-form"/);
});
test('番茄输入草稿优先于上次搜索，切页刷新不会被旧词覆盖', () => {
  assert.match(render('tomato'), /id="tomato-keyword"[^>]*value="新关键词"/);
});
test('聚合搜索失败时不依赖结果或筛选状态显示重试入口', () => {
  for (const results of [[], [{ bookName: '测试书', author: '作者', sourceId: '1', sourceName: '来源' }]]) {
    const html = render('aggregate', { online: { results, running: false, error: '网络失败' } });
    assert.equal((html.match(/data-action="online-retry"/g) || []).length, 1);
    assert.ok(html.indexOf('data-action="online-retry"') < html.indexOf('id="online-sort"') || !results.length);
    assert.doesNotMatch(render('aggregate', { online: { results, running: true, error: '旧错误' } }), /data-action="online-retry"/);
  }
  const failed = render('aggregate', { online: { results: [], running: false, batch: { items: [{ state: 'failed', bookName: '测试书' }] } } });
  assert.match(failed, /重试 1 条失败项/);
  const filtered=render('aggregate',{online:{results:[{bookName:'测试书',author:'作者'}],running:false,error:'网络失败'}},{onlineQuery:'没有这本书'});
  assert.match(filtered,/没有符合筛选的结果/);assert.match(filtered,/data-action="online-retry"/);
});
test('声明同时保护两个搜索标签，番茄后台任务可回到在线搜书', () => {
  for (const tab of ['aggregate', 'tomato']) {
    const html = render(tab, { consent: { accepted: false } });
    assert.match(html, /id="consent-scroll"/);
    assert.doesNotMatch(html, /id="tomato-search-form"|id="search-form"/);
  }
  const state = { books: [], settings: {}, online: { running: false }, tomato: { running: true } };
  const html = window.ReaderViews.render(state, { route: 'about', dialog: null });
  assert.match(html, /data-action="tomato-route"/);
});
