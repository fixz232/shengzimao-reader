'use strict';
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {spawn}=require('node:child_process');
const {CDP,until,freePort}=require('./cdp.cjs');
const root=path.resolve(__dirname,'..');
(async()=>{
  const evidence=path.join(root,'.verification','about-ui-'+Date.now());fs.mkdirSync(evidence,{recursive:true});
  const profile=path.join(evidence,'profile'),legacy=path.join(evidence,'legacy');fs.mkdirSync(legacy);new(require('../app/library.cjs').Library)(profile).save();
  const fixtures=path.join(evidence,'fixtures.json');fs.writeFileSync(fixtures,JSON.stringify({importFiles:[]}));
  const port=await freePort(),env={...process.env};delete env.ELECTRON_RUN_AS_NODE;delete env.NODE_OPTIONS;
  const executable=process.env.SZM_TEST_EXECUTABLE,checks=[];let child,manager,logs='';
  const check=(label,value)=>{assert.ok(value,label);checks.push(label);console.log('PASS '+label);};
  try{
    child=spawn(executable||require('electron'),[...(executable?[]:[path.join(__dirname,'test-host.cjs')]),`--profile=${profile}`,`--legacy-profile=${legacy}`,`--test-fixtures=${fixtures}`,`--remote-debugging-port=${port}`],{cwd:root,env,windowsHide:true,stdio:['ignore','pipe','pipe']});child.stdout.on('data',chunk=>logs+=chunk);child.stderr.on('data',chunk=>logs+=chunk);
    const target=await until(async()=>(await(await fetch(`http://127.0.0.1:${port}/json/list`)).json()).find(item=>item.type==='page'&&item.url.includes('mode=manager')),30000);manager=await CDP.connect(target.webSocketDebuggerUrl);await until(()=>manager.eval('!!document.querySelector(".nav")'));
    await manager.click('[data-route="about"]');await until(()=>manager.eval('!!document.querySelector(".about-notices")'));
    check('关于页显示当前版本',await manager.eval(`document.querySelector('#app-version').textContent==='v${require('../package.json').version}'`));
    for(const text of ['AGPL-3.0-only','内容权利与使用边界','数据与网络说明','免责声明与风险提示','致谢与来源','Tomato-Novel-Downloader','不得排除或限制'])check('声明包含 '+text,await manager.eval(`document.querySelector('.about-page').textContent.includes(${JSON.stringify(text)})`));
    check('关于页保留源码、发布页、在线声明及来源入口',await manager.eval('["repository","releases","project","license","tomatoUpstream","disclaimer"].every(kind=>document.querySelector(`[data-kind="${kind}"]`))&&!!document.querySelector("[data-action=consent-review]")'));
    for(const width of [940,560]){
      await manager.call('Emulation.setDeviceMetricsOverride',{width,height:680,deviceScaleFactor:1,mobile:false});
      await manager.eval('document.querySelector(".workspace").scrollTop=0');
      check(width+' 像素关于页无横向溢出',await manager.eval('document.documentElement.scrollWidth<=innerWidth&&document.querySelector(".workspace").scrollWidth<=document.querySelector(".workspace").clientWidth'));
      if(process.env.SZM_SKIP_SCREENSHOTS!=='1')await manager.screenshot(path.join(evidence,`about-${width}-top.png`));
      await manager.eval('document.querySelector(".workspace").scrollTop=document.querySelector(".workspace").scrollHeight');
      check(width+' 像素可以滚动到致谢末尾',await manager.eval('document.querySelector("[data-kind=disclaimer]").getBoundingClientRect().bottom<=innerHeight'));
      if(process.env.SZM_SKIP_SCREENSHOTS!=='1')await manager.screenshot(path.join(evidence,`about-${width}-bottom.png`));
    }
    await manager.call('Emulation.clearDeviceMetricsOverride');await manager.click('[data-action="consent-review"]');await until(()=>manager.eval('!!document.querySelector("#consent-scroll")'));check('关于页可查看完整在线声明',true);
    check('页面无未捕获异常且书库保持空白',manager.errors.length===0&&(await manager.request('snapshot')).books.length===0);
    fs.writeFileSync(path.join(evidence,'report.json'),JSON.stringify({passed:checks.length,checks,packaged:Boolean(executable),environment:{node:process.version,electron:require('electron/package.json').version}},null,2));console.log(JSON.stringify({passed:checks.length,evidence}));await manager.request('quit').catch(()=>{});await until(()=>child.exitCode!==null);
  }finally{manager?.close();if(child?.exitCode===null)child.kill();fs.writeFileSync(path.join(evidence,'runtime.log'),logs);}
})().catch(error=>{console.error(error);process.exitCode=1;});
