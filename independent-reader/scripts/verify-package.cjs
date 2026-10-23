'use strict';
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),asar=require('@electron/asar');
function verifyPackage(archive){
  let checked=0;
  for(const name of asar.listPackage(archive)){
    const file=name.replace(/^[\\/]+/,''),info=asar.statFile(archive,file);
    if(info.files||info.link)continue;
    if(!info.integrity?.hash)throw new Error('打包文件缺少完整性信息：'+file);
    const bytes=info.unpacked?fs.readFileSync(path.join(archive+'.unpacked',file)):asar.extractFile(archive,file);
    const digest=crypto.createHash('sha256').update(bytes).digest('hex');
    if(digest!==info.integrity.hash)throw new Error('ASAR 内容校验失败，停止生成安装包：'+file+'。请使用 Node.js 24 LTS 重新构建。');
    checked++;
  }
  console.log('ASAR 完整性通过：'+checked+' 个文件。');
  return checked;
}
module.exports={verifyPackage};
if(require.main===module)verifyPackage(path.resolve(process.argv[2]||'release/win-unpacked/resources/app.asar'));
