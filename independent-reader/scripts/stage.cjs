'use strict';
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { execFileSync } = require('node:child_process');
const root = path.resolve(__dirname, '..');
const parent = path.dirname(root);
const resource = path.join(root, 'resources');
const upstream = path.join(parent, 'vendor', 'so-novel');
const sha256 = file => crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
function copy(from, to) {
  if (['.git', 'target', '.env'].includes(path.basename(from))) return;
  if (fs.statSync(from).isDirectory()) {
    fs.mkdirSync(to, { recursive: true });
    for (const name of fs.readdirSync(from)) copy(path.join(from, name), path.join(to, name));
  } else {
    if (fs.existsSync(to) && fs.statSync(from).size === fs.statSync(to).size && sha256(from) === sha256(to)) return;
    fs.mkdirSync(path.dirname(to), { recursive: true });
    // CopyFile overwrites in place; avoid Node 25 fs.cp's failing unlink path on Windows.
    fs.copyFileSync(from, to);
  }
}
function stage() {
  fs.mkdirSync(path.join(resource, 'sonovel'), { recursive: true });
  const jar = path.join(upstream, 'target', 'app-jar-with-dependencies.jar');
  if (!fs.existsSync(jar)) throw new Error('需要先从 vendor/so-novel 构建带依赖的 jar。');
  const status = execFileSync('git', ['-C', upstream, 'status', '--porcelain'], { encoding: 'utf8' }).trim();
  if (status) throw new Error('SoNovel 源码有未确认修改，停止暂存以免对应源码与二进制不一致。');
  const javac = process.env.SZM_JAVAC || 'D:\\java21\\bin\\javac.exe';
  if (!fs.existsSync(javac)) throw new Error('设置 SZM_JAVAC 指向 JDK 21 的 javac。');
  copy(jar, path.join(resource, 'sonovel', 'app.jar'));
  copy(path.join(parent, 'static', 'sonovel', 'runtime-minimal'), path.join(resource, 'sonovel', 'runtime'));
  copy(path.join(parent, 'static', 'sonovel', 'rules'), path.join(resource, 'sonovel', 'rules'));
  copy(path.join(parent, 'build', 'icons', 'icon.ico'), path.join(resource, 'icon.ico'));
  copy(path.join(upstream, 'LICENSE'), path.join(resource, 'sonovel', 'LICENSE'));
  copy(path.join(upstream, 'bundle', 'DISCLAIMER.md'), path.join(resource, 'sonovel', 'DISCLAIMER.md'));
  copy(path.join(upstream, 'LICENSE'), path.join(root, 'LICENSE'));
  execFileSync(javac, ['-encoding', 'UTF-8', '--release', '21', '-proc:none', '-cp', jar, '-d', path.join(resource, 'sonovel', 'adapter'), path.join(root, 'engine', 'ReaderEngine.java')], { stdio: 'inherit' });
  copy(upstream, path.join(resource, 'source', 'so-novel'));
  // Remove only superseded, task-generated UI files from earlier staged builds.
  for(const name of ['ui.js','styles.css']){const stale=path.join(resource,'source','independent-reader','app','ui',name);if(fs.existsSync(stale))fs.unlinkSync(stale);}
  for (const name of ['app','engine','scripts','test','package.json','package-lock.json','README.md','PROVENANCE.md','PARITY.md','LICENSE']) if (fs.existsSync(path.join(root,name))) copy(path.join(root,name), path.join(resource, 'source', 'independent-reader', name));
  const manifest = { createdAt: new Date().toISOString(), soNovel: { repository: 'https://github.com/freeok/so-novel', commit: execFileSync('git', ['-C',upstream,'rev-parse','HEAD'], { encoding: 'utf8' }).trim(), worktree: status, jarSha256: sha256(jar) }, icon: { source: 'user supplied', sha256: sha256(path.join(resource,'icon.ico')) }, adapter: { sourceSha256: sha256(path.join(root,'engine','ReaderEngine.java')), classSha256: sha256(path.join(resource,'sonovel','adapter','org','shengzimao','reader','ReaderEngine.class')) }, exclusions: ['old desktop src','old dist','.electron-vue','legacy profile','private novels'] };
  fs.writeFileSync(path.join(resource, 'BUILD_PROVENANCE.json'), JSON.stringify(manifest,null,2));
  console.log(JSON.stringify(manifest,null,2));
}
if (require.main === module) stage();
module.exports = { stage };
