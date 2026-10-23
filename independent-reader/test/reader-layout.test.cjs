'use strict';
const {test}=require('node:test'),assert=require('node:assert/strict');
const {fit}=require('../app/ui/reader-layout.js');
const measure=text=>Array.from(text).reduce((width,char)=>width+(/[\x00-\x7f]/.test(char)?5:10),0);
test('mixed Latin/CJK fills available pixels, not a conservative character estimate',()=>{
  const text='中文 ABC 123 空格 第二段后续正文';
  const result=fit(text,100,200,1,measure);
  assert.ok(measure(result.text)<=100);assert.ok(measure(result.text+text.slice(result.count,result.count+1))>100);
  assert.ok(result.count>Math.floor(100/(10*1.2)));
});
test('manual per-line limit is retained',()=>{assert.equal(fit('一二三四五六七八九十',1000,4,1,measure).text,'一二三四');});
test('automatic width ignores the old 20-character limit for one and two lines',()=>{
  const text='阅读框 中文 ABC 123 空格。'.repeat(30);
  for(const rows of [1,2]){const page=fit(text,400,Infinity,rows,measure);assert.equal(page.lines.length,rows);assert.equal(page.lines.join(''),text.slice(0,page.count));for(const line of page.lines){assert.ok(Array.from(line).length>20);assert.ok(measure(line)>390&&measure(line)<=400);}}
});
test('multiline pages use measured width for each line and account for every character',()=>{
  const text='中文 ABC 123 空格 第二段后续正文'.repeat(5),page=fit(text,100,200,3,measure);
  assert.equal(page.lines.length,3);assert.equal(page.lines.join(''),text.slice(0,page.count));
  for(const line of page.lines)assert.ok(measure(line)<=100);
});
test('backward layout does not skip preceding characters',()=>{
  const text='中文 ABC 123 空格 第二段后续正文',page=fit(text,100,200,2,measure,true);
  assert.equal(page.lines.join(''),text.slice(page.start));assert.equal(page.start+page.count,text.length);
});
test('combining marks and emoji are kept together',()=>{
  const text='a\u0301🙂👨‍👩‍👧‍👦中文',page=fit(text,10000,2,1,()=>10);
  assert.equal(page.text,'a\u0301🙂');assert.equal(page.count,4);
});
test('empty book boundary yields an empty previous page',()=>{assert.deepEqual(fit('',100,20,1,measure,true),{text:'',lines:[],counts:[],count:0,start:0});});
