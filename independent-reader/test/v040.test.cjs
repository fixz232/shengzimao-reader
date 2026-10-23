'use strict';
const {test} = require('node:test'), assert = require('node:assert/strict');
const {readingSection} = require('../app/reading-section.cjs');
const {filterResults} = require('../app/search-matching.cjs');
test('普通阅读按原始段落与章节偏移取文，前言及最后一章边界正确', () => {
  const text = '前言\n第一章 开始\n内容\n第二章 结束\n尾声';
  const book = {id:'a',title:'测试',position:0,chapters:[{title:'第一章 开始',position:3},{title:'第二章 结束',position:13}]};
  let section = readingSection(book,text); assert.equal(section.text,'前言\n'); assert.equal(section.next,3); assert.equal(section.previous,null);
  section = readingSection(book,text,3); assert.equal(section.start,3); assert.equal(section.next,13); assert.equal(section.previous,0);
  section = readingSection(book,text,9999); assert.equal(section.end,text.length); assert.equal(section.next,null); assert.equal(section.previous,3);
});
test('超长章节分段保持连续，Unicode 代理对不会切断或造成下一段死循环', () => {
  const text = '甲'.repeat(23999)+'😀'+'乙'.repeat(30000), book = {id:'a',title:'长章',position:0,chapters:[]};
  const first = readingSection(book,text), second = readingSection(book,text,first.next), third = readingSection(book,text,second.next);
  assert.equal(first.end,second.start); assert.equal(second.end,third.start); assert.equal(first.text+second.text+third.text,text);
  assert.equal(first.text.at(-2),'\ud83d'); assert.ok(second.next>first.next); assert.equal(third.next,null);
  assert.equal(readingSection(book,text,third.previous).start,second.start);
});
test('跨站合并规范化书名和作者前缀，但不会把同名不同作者混成一本书', () => {
  const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),window={};
  vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../app/ui/manager-view.js'),'utf8'),{window});
  const groups=window.ReaderViews.groupedResults({online:{results:[{bookName:'《三体》',author:'作者：刘慈欣',matchScore:100},{bookName:'三体',author:'刘慈欣',matchScore:100},{bookName:'三体',author:'另一位作者',matchScore:100}]}},{onlineSite:'',onlineQuery:'',onlineSort:'match'});
  assert.equal(groups.length,2);assert.equal(groups[0].variants.length,2);assert.equal(groups[1].variants.length,1);
});
test('搜书只根据书名作者匹配，精确优先且多关键词须全部命中', () => {
  const books = [{bookName:'三体前传',author:'宝树'}, {bookName:'宇宙',author:'未知',intro:'三体 刘慈欣'}, {bookName:'《三体》',author:'作者：刘慈欣'}, {bookName:'三体外传',author:'刘慈欣'}];
  const exact = filterResults('三体',books); assert.equal(exact[0].bookName,'《三体》'); assert.equal(exact[0].matchType,'书名完全匹配'); assert.equal(exact.length,3);
  const both = filterResults('三体　刘慈欣',books); assert.deepEqual(both.map(book=>book.bookName),['《三体》','三体外传']); assert.equal(both[0].matchScore,100);
  assert.equal(filterResults('刘慈欣',books).length,2); assert.equal(filterResults('完全不相干',books).length,0);
});
test('大小写全半角书名规范化，近似候选只在无直接命中时出现并明确标识', () => {
  const books = [{bookName:'ＡＢＣ 小说',author:'Test'}, {bookName:'斗罗大陆',author:'唐家三少'}];
  assert.equal(filterResults('abc',books)[0].bookName,'ＡＢＣ 小说');
  const fuzzy=filterResults('斗罗大路',books); assert.equal(fuzzy[0].matchType,'近似匹配'); assert.equal(fuzzy[0].matchScore,75);
  assert.equal(filterResults('斗罗大陆',books)[0].matchScore,100); assert.equal(filterResults('三',books).length,1);
});
