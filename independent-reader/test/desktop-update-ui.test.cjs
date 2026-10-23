'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');

const source=fs.readFileSync(path.join(__dirname,'../app/ui/manager-view.js'),'utf8');
const window={ReaderFeatures:{}};
vm.runInNewContext(source,{window});

function about(update={phase:'idle',progress:0}){
  return window.ReaderViews.render({version:'0.5.0',profile:'C:/profile',runtime:{platform:'win32',arch:'x64',electron:'33.4.11'},settings:{theme:'classic'},books:[],online:{running:false,message:''},update},{route:'about',dialog:null});
}

test('about page links to the repository and Releases and exposes update check',()=>{
  const html=about();
  assert.match(html,/圣子猫阅读器/);assert.match(html,/data-action="online-link" data-kind="repository"[^>]*>项目源码/);
  assert.match(html,/data-kind="releases"[^>]*>GitHub Releases/);assert.match(html,/data-action="update-check"[^>]*>检查更新/);
  assert.match(html,/https:\/\/github.com\/fixz232\/shengzimao-reader/);
});
test('about page discloses licenses, rights, network boundaries, risks and acknowledgements',()=>{
  const html=about();
  for(const text of ['AGPL-3.0-only','MIT','内容权利与使用边界','数据与网络说明','免责声明与风险提示','致谢与来源','软件开源许可不等于作品授权','不自动上传书库','novel.snssdk.com','不得排除或限制','Tomato-Novel-Downloader','LevineHua/single-reader','Mozilla PDF.js'])assert.ok(html.includes(text),text);
  assert.match(html,/data-kind="tomatoUpstream"/);assert.match(html,/data-kind="disclaimer"/);
});

test('about page disables check in development and while cancellation or install is pending',()=>{
  for(const update of [{phase:'idle',supported:false},{phase:'cancelling'},{phase:'confirming'},{phase:'installing'}]){
    assert.match(about(update),/data-action="update-check"[^>]*disabled/);
  }
  assert.match(about({phase:'downloaded',version:'0.6.0',error:'保存失败'}),/role="alert">保存失败/);
});

test('about page shows safe release notes, progress, cancel, and install actions',()=>{
  const downloading=about({phase:'downloading',version:'0.6.0',progress:35.7,releaseNotes:'',error:''});
  assert.match(downloading,/正在下载 v0\.6\.0（35%）/);assert.match(downloading,/data-action="update-cancel"/);assert.match(downloading,/role="progressbar"[^>]*aria-valuenow="35"/);
  const downloaded=about({phase:'downloaded',version:'0.6.0',releaseNotes:'修复 & <script>alert(1)<\/script>',progress:100});
  assert.match(downloaded,/data-action="update-install"/);assert.match(downloaded,/修复 &amp; &lt;script&gt;/);assert.doesNotMatch(downloaded,/<script>alert\(1\)<\/script>/);
});
