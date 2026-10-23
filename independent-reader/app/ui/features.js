'use strict';
window.ReaderFeatures=(()=>{
  const e=value=>String(value??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const button=(action,label,attrs='')=>'<button data-action="'+action+'" '+attrs+'>'+label+'</button>';
  function declaration(state,m){
    return '<h1>在线功能使用声明</h1><p class="intro">首次使用前，请阅读到底并确认。声明更新后需要重新确认。</p>'+
      '<section class="panel"><div id="consent-scroll" class="consent-scroll" tabindex="0" role="region" aria-label="在线功能声明">'+
      '<h2>来源与开源许可</h2><p>圣子猫独立版的在线检索、目录解析和下载能力使用开源项目 SoNovel。桌面界面与适配层由本项目独立实现。SoNovel 项目地址为 https://github.com/freeok/so-novel。本软件保留 AGPL-3.0 许可、对应源码和来源说明，随安装包提供。</p>'+
      '<div class="toolbar">'+button('online-link','SoNovel 项目','data-kind="project"')+button('online-link','上游声明','data-kind="disclaimer"')+button('online-link','开源许可','data-kind="license"')+'</div>'+
      '<h2>番茄小说来源</h2><p>番茄搜索使用 novel.snssdk.com 公开接口，详情、目录和公开章节来自 fanqienovel.com，并参考 Tomato-Novel-Downloader 的功能流程。番茄内容版权属于作者及其他权利人；本软件不授予下载、复制、传播或商业使用权。请先确认你拥有相应权限，并遵守番茄平台服务条款。</p><p>本功能不绕过登录、付费、验证码或其他访问控制；源站限流、接口变化、地区限制或章节权限可能导致搜索和下载停止。参考项目采用 MIT License，详情请查看其仓库和本项目来源记录。</p><div class="toolbar">'+button('online-link','番茄公开页面','data-kind="tomato"')+button('online-link','参考项目','data-kind="tomatoUpstream"')+'</div>'+
      '<h2>内容与授权</h2><p>在线内容由第三方网站提供，版权归原作者及其他权利人所有。本软件不提供作品授权，也不保证第三方网站有权传播内容。请仅检索、下载和转换您有权访问和使用的作品，尊重作者、网站服务条款及适用法律，不得用于未经授权的传播或商业用途。建议优先选择作者与正版平台。</p>'+
      '<h2>网络访问与数据</h2><p>搜索会将关键词发往启用的第三方书源；读取详情、检查更新及下载会访问相应网站。第三方可能记录网络地址和访问行为。书库、阅读进度与确认记录保存在本机；导入书库备份不会替您确认本声明。</p>'+
      '<h2>可用性与风险说明</h2><p>第三方内容的准确性、完整性、时效性与持续可用性无法保证。网页规则改变、网络故障可能导致目录缺失或下载失败。增量更新只在旧目录可核对时追加新章节，不负责替换源站对旧章节的修订。重要书库请定期导出备份。</p>'+
      '<p>导入转换可能丢失原排版、图片或复杂表格。PDF 仅支持可提取文字的文档，不包含扫描件 OCR。不得将本工具用于绕过付费、登录、访问控制或其他限制。</p>'+
      '<h2>责任与退出</h2><p>请根据您的用途自行判断来源和使用权限。开源许可及上游声明可通过上方按钮查阅；本说明不排除法律规定不能排除的责任。若不同意，可返回书库继续使用本地阅读和文档转换功能。</p>'+
      '<p class="muted">声明版本：'+e(state.consent?.version||'')+' · 已阅读至此</p></div>'+
      '<p id="consent-read-status" class="muted">'+(m.consentRead?'已阅读到底。':'请滚动阅读到底，再勾选确认。')+'</p>'+
      '<label class="consent-check"><input id="consent-checkbox" type="checkbox" '+(m.consentRead?'':'disabled')+' '+(m.consentChecked?'checked':'')+'>我已阅读并理解以上说明，同意遵守使用条件。</label>'+
      '<div class="toolbar">'+button('consent-decline','返回书库')+button('consent-accept','同意并进入在线搜书','id="consent-accept" class="primary" '+(m.consentRead&&m.consentChecked?'':'disabled'))+'</div></section>';
  }
  function catalog(chapters,query,limit,remote,book){
    const norm=value=>String(value||'').normalize('NFKC').toLowerCase();
    const terms=norm(query).trim().split(/\s+/).filter(Boolean);
    const matches=chapters.map((chapter,index)=>({...chapter,index})).filter(chapter=>terms.every(term=>norm(chapter.title+' '+(chapter.index+1)).includes(term)));
    const visible=matches.slice(0,limit||300);
    return '<p class="muted">共 '+chapters.length+' 章，匹配 '+matches.length+' 章，显示 '+visible.length+' 章</p><div class="dialog-list chapter-results">'+
      visible.map(chapter=>remote?'<div class="chapter-entry"><small>'+(chapter.index+1)+'</small><span>'+e(chapter.title)+'</span></div>':button('seek',e(chapter.title),'data-id="'+e(book.id)+'" data-position="'+chapter.position+'" '+(chapter.position<=book.position&&(chapters[chapter.index+1]?.position??Infinity)>book.position?'aria-current="true"':''))).join('')+
      (matches.length?'':'<p class="muted">没有匹配章节，请尝试章节名、序号或多个关键词。</p>')+'</div>'+
      (matches.length>(limit||300)?button('chapter-more','再显示 300 章'):'');
  }
  function facts(book){
    return '<div class="result-facts">'+[['状态',book.status],['字数',book.wordCount||book.words],['更新',book.lastUpdateTime],['章节',book.chapterCount]].map(([label,value])=>'<span>'+label+'：'+e(value||'源站未提供')+'</span>').join('')+'</div><p class="result-intro">'+e(book.intro||'暂无简介，可读取详情查看')+'</p>';
  }
  function details(state,m){
    const details=m.onlineDetails,info=details?.book||{};
    return '<h3>'+e(info.bookName||'')+'</h3><dl class="detail-grid">'+[['作者',info.author],['分类',info.category],['状态',info.status],['字数',info.wordCount],['更新',info.lastUpdateTime],['最新章节',info.latestChapter],['书源',info.sourceName],['目录',(details?.toc.length||0)+' 章']].map(([label,value])=>'<div><dt>'+label+'</dt><dd>'+e(value||'源站未提供')+'</dd></div>').join('')+'</dl><p class="detail-intro">'+e(info.intro||'暂无简介')+'</p><p class="path">'+e(info.url)+'</p><label for="chapter-query">搜索在线目录</label><input id="chapter-query" type="search" placeholder="章节名、序号或多个关键词" value="'+e(m.chapterQuery)+'">'+catalog(details?.toc||[],m.chapterQuery,m.chapterLimit,true);
  }
  function tracking(state){
    return '<section class="tracking-bar"><div><strong>追更书架</strong><small>'+state.books.filter(book=>book.tracking&&book.onlineUrl).length+' 本追更中 · 仅追加新增章节</small></div><label><input data-setting="autoUpdate" type="checkbox" '+(state.settings.autoUpdate?'checked':'')+'>每 30 分钟自动检查</label>'+button('update-all','全部检查并更新',state.online.running?'disabled':'')+'</section>';
  }
  return {declaration,catalog,facts,details,tracking};
})();
