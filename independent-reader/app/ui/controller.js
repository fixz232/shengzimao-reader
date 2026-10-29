'use strict';
(() => {
  if (new URLSearchParams(location.search).get('mode') === 'normal') return;
  const root=document.querySelector('#app'),V=window.ReaderViews,readerMode=new URLSearchParams(location.search).get('mode')==='reader',startedAt=Date.now();
  const m={route:'library',searchTab:'aggregate',libraryTab:'library',settingsTab:'appearance',appsTab:'web',query:'',sort:'default',status:'all',more:'all',category:'',tag:'',source:'',advanced:false,savedFilter:'',filterName:'',page:1,selected:new Set(),dialog:null,chapterQuery:'',chapters:[],textQuery:'',textResults:[],textStatus:'',onlineQuery:'',onlineSite:'',onlineSort:'match',onlineFormat:'epub',onlineSelected:new Set(),tomatoQuery:null,tomatoFormat:'epub',tomatoDetails:null,keywordDraft:null,busy:false,windows:[],attachHandle:null,attachTitle:null,appOptions:{}};
  let state,refreshTimer,resizeTimer,noticeTimer,readBusy=false,mouseHidden=true,pointerInside=false,focused=document.hasFocus(),lastInteraction=Date.now(),autoTimer,lastAuto=0,pointerTimer,pointerBusy=false,addressTimer,focusEventAt=0;
  let paintRevision=0,lastPaint=null,locateDismissed=false,lastRevealUntil=0;const previousPages=new Map(),textMeasure=document.createElement('canvas').getContext('2d');
  let snapshotRevision=0,textSearchRevision=0,actionBusy=false,probeBusy=false;
  let lastPageStats=null,lastFingerprint='',boundsFrame=0,boundsEvent=null;
  const request=(command,payload={})=>window.reader.request(command,payload);
  function notify(text,error=false){const node=document.querySelector('#notice');node.textContent=text;node.classList.toggle('error',error);clearTimeout(noticeTimer);noticeTimer=setTimeout(()=>{node.textContent='';},error?10000:4500);}
  async function task(fn){try{return await fn();}catch(error){notify(error.message,true);return null;}}
  function active(){return state?.books.find(book=>book.id===state.activeId);}
  function readingWidth(){const node=document.querySelector('#reader-copy'),style=getComputedStyle(node);textMeasure.font=`${style.fontWeight} ${style.fontSize} ${style.fontFamily}`;return Math.max(1,node.clientWidth-parseFloat(style.paddingLeft)-parseFloat(style.paddingRight));}
  function pageBudget(){const s=state.settings,width=readingWidth(),rows=s.multiLine?s.lineCount:1;const perLine=s.autoFitWidth?Math.ceil(width/Math.max(.5,Math.min(...['i',' ','中','0'].map(char=>textMeasure.measureText(char).width))))+1:s.lineSize;return Math.min(32768,perLine*rows*2);}
  function layout(text,fromEnd=false,oneLine=false){const s=state.settings,width=readingWidth();return window.ReaderLayout.fit(text,width,s.autoFitWidth?Infinity:s.lineSize,oneLine?1:s.multiLine?s.lineCount:1,value=>textMeasure.measureText(value).width,fromEnd);}
  function pageKey(position){const s=state.settings;return [state.activeId,position,innerWidth,s.fontFamily,s.fontSize,s.fontBold,s.autoFitWidth,s.lineSize,s.multiLine,s.lineCount].join('|');}
  let composing=false,renderPending=false;
  function render(){
    if(composing){renderPending=true;return;}
    if(readerMode){renderReader();return;}
    const focus=document.activeElement?.id,selection=document.activeElement?.selectionStart;
    const previousDialog=document.querySelector('#reader-dialog'),dialogType=previousDialog?.dataset.type;
    const fields=previousDialog?Array.from(previousDialog.querySelectorAll('input[id],select[id],textarea[id]')).map(input=>[input.id,input.value]):[];
    const scroll=document.querySelector('.workspace')?.scrollTop||0,dialogScroll=document.querySelector('.dialog-content')?.scrollTop||0;
    applyTheme(state.settings.theme);root.innerHTML=V.render(state,m);
    const dialog=document.querySelector('#reader-dialog');if(dialog){if(dialogType===m.dialog.type)for(const [id,value]of fields){const field=dialog.querySelector('#'+id);if(field)field.value=value;}dialog.showModal();dialog.addEventListener('cancel',()=>{m.dialog=null;});}
    const next=focus&&document.getElementById(focus);if(next){next.focus({preventScroll:true});if(selection!=null&&typeof next.setSelectionRange==='function'&&!['number','range','color'].includes(next.type))next.setSelectionRange(selection,selection);}
    document.querySelector('.workspace').scrollTop=scroll;if(dialog)document.querySelector('.dialog-content').scrollTop=dialogScroll;
  }
  function applyTheme(theme){const tokens={classic:['#2b2d38','#f5f6f9','#4569df','#fff'],ink:['#2d3430','#f8f6f0','#8b713d','#fff'],chinaRed:['#af2b24','#fff6f2','#ce9b38','#fff'],celadon:['#2b6c62','#f1f8f5','#8f7d48','#fff']}[theme]||['#2b2d38','#f5f6f9','#4569df','#fff'];for(const [index,key] of ['--ink','--sidebar','--accent','--button-text'].entries())document.documentElement.style.setProperty(key,tokens[index]);}
  async function refresh(){const revision=++snapshotRevision,wasAutoReading=state?.settings.autoRead;const snapshot=await request('snapshot');if(revision!==snapshotRevision)return;// 旧响应到达时已有更新的请求，直接丢弃。
    if(state?.online.revision!==snapshot.online.revision){m.onlineSelected=new Set();m.onlineSite='';m.onlineQuery='';}
    if(state?.tomato?.revision!==snapshot.tomato?.revision){m.tomatoDetails=null;}
    if(ttsActive&&(!snapshot.settings.ttsEnabled||snapshot.activeId!==state?.activeId))stopTts();
    state=snapshot;if(state.readerRevealUntil!==lastRevealUntil){lastRevealUntil=state.readerRevealUntil;locateDismissed=false;}if(state.settings.autoRead&&!wasAutoReading)lastAuto=Date.now();render();if(readerMode)await paintReader();}
  function rgba(hex,alpha){const value=parseInt(hex.slice(1),16);return `rgba(${value>>16},${value>>8&255},${value&255},${alpha})`;}
  function readerVisibility(){const s=state.settings;if(!state.activeId)return 'content';if(!locateDismissed&&Date.now()<state.readerRevealUntil&&!pointerInside)return 'locate';if(s.mouseControl&&mouseHidden)return 'hidden';if(s.timeoutEnabled&&Date.now()-lastInteraction>s.timeoutMinutes*60000)return 'timeout';if(s.hideOnBlur&&!focused&&!(s.mouseControl&&pointerInside&&s.mouseAutoShow))return 'disguise';return 'content';}
  function disguised(){return readerVisibility()!=='content';}
  function renderReader(){
    document.documentElement.classList.add('reader-document');
    document.body.classList.add('reader-window');const s=state.settings,styles={'--reader-bg':rgba(s.background,s.backgroundOpacity),'--reader-color':rgba(s.fontColor,s.fontOpacity),'--reader-size':s.fontSize+'px','--reader-font':JSON.stringify(s.fontFamily)+',微软雅黑,Arial,sans-serif','--reader-weight':s.fontBold?'700':'400','--reader-highlight':s.highlightColor,'--reader-line-height':Math.ceil(s.fontSize*(s.multiLine?1.45:1.35))+'px','--reader-copy-height':Math.ceil(s.fontSize*(s.multiLine?1.45:1.35))*(s.multiLine?s.lineCount:1)+'px','--reader-active-line':rgba(s.highlightColor,.35)};for(const [key,value]of Object.entries(styles))document.documentElement.style.setProperty(key,value);
    if(!document.querySelector('#reader-copy'))root.innerHTML='<main class="reader-strip"><div class="reader-drag" aria-label="拖动阅读条"></div><p id="reader-copy" class="reader-copy" aria-label="小说正文"></p><div id="reader-progress" class="reader-progress"></div><div class="reader-resize" aria-label="调整阅读条大小"></div></main>';
    document.querySelector('.reader-resize').hidden=!s.resizable;
  }
  const addresses=['https://mail.example.com/inbox/folders/all/unread','https://docs.example.com/zh-CN/workspace/recent'];
  // 内容指纹：书 + 位置 + 排版相关设置 + 窗口宽度都没变时，跳过 read IPC 与重新排版。
  function fingerprint(){const book=active();return [state.activeId,book?.digest,book?.length,book?.position,innerWidth,JSON.stringify(state.settings)].join('|');}
  function disguiseText(){return addresses[state.settings.fakeAddress?Math.floor((Date.now()-startedAt)/15000)%addresses.length:0];}
  function paintReaderVisibility(page){
    const mode=readerVisibility();if(mode!=='content'&&ttsActive)stopTts();const copy=document.querySelector('#reader-copy');
    if(copy)copy.textContent=mode==='hidden'?'':mode==='locate'?'阅读框在这里 · 欢迎使用圣子猫阅读器':mode==='timeout'?'阅读超时':mode==='disguise'?disguiseText():page.text;
    const strip=document.querySelector('.reader-strip');if(!strip)return;
    strip.classList.toggle('disguised',mode==='disguise');
    strip.classList.toggle('content-hidden',mode==='hidden');
    strip.classList.toggle('locating',mode==='locate');
    strip.classList.toggle('active',mode==='content');
  }
  async function paintReader(force=false){
    if(!state)return;
    const fp=fingerprint();
    if(!force&&lastPaint&&lastPaint.fp===fp){paintReaderVisibility(lastPaint.page);return;}
    const revision=++paintRevision,read=await request('read',{count:pageBudget()});if(revision!==paintRevision)return;
    const page=layout(read.text);
    lastPaint={fp,page,read};
    lastPageStats={bookId:state.activeId,perLine:page.counts[0]||state.settings.lineSize};
    paintReaderVisibility(page);
    const progress=read.length?(read.position+page.count>=read.length?1:read.position/read.length):0;
    document.querySelector('#reader-progress').style.width=state.settings.progressLine?progress*100+'%':'0';
  }
  async function move(direction,line=false,fromTts=false){
    if(!fromTts)stopTts();
    if(readBusy||!state.activeId)return;readBusy=true;
    try{
      const read=await request('read',{count:pageBudget()}),page=layout(read.text),key=pageKey(read.position);
      if(direction<0){
        let position=!line?previousPages.get(key):undefined;
        if(position===undefined){const preceding=await request('read',{count:pageBudget(),before:true});position=preceding.position+layout(preceding.text,true,line).start;}
        await request('move',{position});
      }else{
        const n=Math.max(1,line?page.counts[0]||page.count:page.count),next=await request('move',{direction,count:n});
        if(!line){previousPages.set(pageKey(next.position),read.position);if(previousPages.size>256)previousPages.delete(previousPages.keys().next().value);}
      }
      await paintReader(true);
    }finally{readBusy=false;}
  }
  async function openDialog(type,id){const bookId=type==='manage'&&!id&&m.selected.size?'':id||state.activeId;if(!bookId&&!['search','save-filter','manage'].includes(type))throw new Error('请先选择一本书。');if(type==='chapters'){m.chapters=await request('chapters',{id:bookId});m.chapterQuery='';m.chapterLimit=300;}m.dialog={type,bookId};render();}
  async function importBooks(repairId){m.busy=true;render();try{const report=await request('import',repairId?{repairId}:{});if(!report.canceled)notify(`导入 ${report.imported} 本，重复 ${report.duplicates} 本${report.errors.length?'；'+report.errors.map(item=>item.message).join('；'):''}`,Boolean(report.errors.length));}finally{m.busy=false;await refresh();}}
  async function saveFilter(name){
    name=name.trim();if(!name)throw new Error('请输入筛选名称。');
    const filter={name};for(const key of ['query','status','more','sort','category','tag','source'])filter[key]=m[key];
    const filters=[...state.settings.savedFilters],index=filters.findIndex(item=>item.name===name);
    if(index>=0)filters[index]=filter;else filters.push(filter);
    await request('settings',{savedFilters:filters});m.savedFilter=String(index>=0?index:filters.length-1);m.filterName=name;
  }
  async function onAction(button){
    const {action,id}=button.dataset,book=state.books.find(book=>book.id===id);
    document.querySelector('#book-menu')?.hidePopover();
    if(action==='consent-review'){m.route='search';m.reviewConsent=true;m.consentRead=false;m.consentChecked=false;render();return;}
    if(action==='consent-decline'){m.route='library';m.reviewConsent=false;render();return;}
    if(action==='consent-accept'){if(!m.consentRead||!document.querySelector('#consent-checkbox')?.checked)throw new Error('请阅读到底并勾选确认。');await request('online-consent',{version:state.consent.version,read:true,accepted:true});m.reviewConsent=false;await refresh();return;}
    if(action==='online-link'){await request('online-link',{kind:button.dataset.kind});return;}
    if(action==='update-check'){await request('update-check');await refresh();return;}
    if(action==='update-download'){await request('update-download');await refresh();return;}
    if(action==='update-cancel'){await request('update-cancel');await refresh();return;}
    if(action==='update-install'){await request('update-install');return;}
    if(action==='online-details'){m.busy=true;render();try{m.onlineDetails=await request('online-details',{index:Number(button.dataset.index)});m.detailsIndex=Number(button.dataset.index);m.chapterQuery='';m.chapterLimit=300;m.dialog={type:'online-details'};}finally{m.busy=false;await refresh();}return;}
    if(action==='tomato-details'){m.busy=true;render();try{m.tomatoDetails=await request('tomato-details',{index:Number(button.dataset.index)});m.tomatoIndex=Number(button.dataset.index);}finally{m.busy=false;await refresh();}return;}
    if(action==='chapter-more'){m.chapterLimit=(m.chapterLimit||300)+300;render();return;}
    if(action==='update-all'||action==='update-book'){if(!state.consent.accepted){m.route='search';render();return;}await request('online-update',action==='update-book'?{ids:[id]}:{});await refresh();return;}
    if(action==='track-book'){await request('online-track',{id,enabled:!book.tracking});await refresh();return;}
    if(action==='normal-open'){await request('open',{id:id||state.activeId,mode:'normal'});return refresh();}
    if(action==='import'||action==='repair')return importBooks(action==='repair'?id:undefined);
    if(action==='open'||action==='continue'){if(actionBusy)return;actionBusy=true;try{await request('open',{id:id||state.activeId});}finally{actionBusy=false;}return refresh();}
    if(action==='import-route'||action==='legacy-route'){m.route='import';render();return;}
    if(action==='library-tab'){m.libraryTab='library';render();return;}
    if(action==='advanced-filter'){m.advanced=!m.advanced;render();return;}
    if(action==='reset-library-filter'){Object.assign(m,{query:'',status:'all',more:'all',sort:'default',category:'',tag:'',source:'',savedFilter:'',filterName:'',page:1});render();return;}
    if(action==='search-route'||action==='tomato-route'){m.route='search';m.searchTab=action==='tomato-route'?'tomato':'aggregate';render();return;}
    if(action==='view-grid'||action==='view-list'){m.libraryTab='library';await request('settings',{view:action.slice(5)});}
    else if(action==='page-next'||action==='page-prev'){m.page+=action==='page-next'?1:-1;render();return;}
    else if(action==='clear-selection'){m.selected.clear();render();return;}
    else if(action==='book-more'){
      const popover=document.querySelector('#book-menu');popover.innerHTML=V.bookMenu(book);const rect=button.getBoundingClientRect();popover.style.left=Math.max(5,Math.min(innerWidth-215,rect.right-195))+'px';popover.style.top=Math.max(5,Math.min(innerHeight-450,rect.bottom+4))+'px';popover.showPopover();return;
    }
    else if(['favorite','pinned','complete'].includes(action)){const key=action==='complete'?'completed':action;await request('books-update',{ids:[id],patch:{[key]:!book[key]}});}
    else if(action==='remove'||action==='bulk-remove'){const ids=action==='remove'?[id]:[...m.selected];if(!ids.length)throw new Error('请先选择书籍。');await request('books-remove',{ids});ids.forEach(id=>m.selected.delete(id));}
    else if(action==='bulk-favorite'||action==='bulk-pinned'){if(!m.selected.size)throw new Error('请先选择书籍。');await request('books-update',{ids:[...m.selected],patch:{[action==='bulk-favorite'?'favorite':'pinned']:true}});}
    else if(action==='manage'||action==='bulk-manage'){if(!id&&!m.selected.size)throw new Error('请先选择书籍。');await openDialog('manage',id);if(!id)m.dialog.bookId='';return;}
    else if(action==='manage-confirm'){const ids=m.dialog.bookId?[m.dialog.bookId]:[...m.selected],patch={category:document.querySelector('#manage-category').value,tags:document.querySelector('#manage-tags').value.split(/[,，]/).map(value=>value.trim()).filter(Boolean)};for(const key of ['title','author','sourceName','cover','intro']){const input=document.querySelector('#manage-'+key);if(input)patch[key]=input.value;}await request('books-update',{ids,patch});m.dialog=null;}
    else if(action.startsWith('dialog-')){await openDialog({jump:'jump',chapters:'chapters',bookmarks:'bookmarks',search:'search'}[action.slice(7)],id);return;}
    else if(action==='close-dialog'){m.dialog=null;render();return;}
    else if(action==='jump-confirm'){
      const target=state.books.find(book=>book.id===m.dialog.bookId),mode=document.querySelector('#jump-mode').value,value=Number(document.querySelector('#jump-value').value);if(!Number.isFinite(value))throw new Error('请输入有效位置。');
      const position=mode==='percent'?Math.floor(Math.max(0,target.length-1)*value/100):mode==='page'?await measuredPagePosition(target.id,value):value;await request('open',{id:target.id});await request('move',{position});m.dialog=null;
    }
    else if(action==='seek'){await request('open',{id});await request('move',{position:Number(button.dataset.position)});m.dialog=null;}
    else if(action==='bookmark'){if(m.dialog?.bookId&&m.dialog.bookId!==state.activeId)await request('open',{id:m.dialog.bookId});await request('bookmark');notify('书签已保存。');}
    else if(action==='bookmark-remove')await request('bookmark-remove',{id,markId:button.dataset.mark});
    else if(action==='clear-history')await request('clear-history');
    else if(action==='legacy-scan'){const scan=await request('legacy-scan');const statusNode=document.querySelector('#legacy-status');if(statusNode)statusNode.textContent=scan.error||`发现 ${scan.found} 本书，正文缺失 ${scan.missing} 本。${scan.path}`;return;}
    else if(action==='legacy-migrate'){m.busy=true;render();try{const report=await request('legacy-migrate');if(!report.canceled)notify(`复制 ${report.imported} 本，跳过 ${report.skipped} 本，正文缺失 ${report.missing} 本。备份已保存。`);}finally{m.busy=false;await refresh();}return;}
    else if(action==='convert-epub'||action==='convert-txt'){m.busy=true;render();try{const result=await request('convert',{format:action.slice(8)});if(!result.canceled)notify('转换已保存：'+result.file);}finally{m.busy=false;await refresh();}return;}
    else if(action==='settings-save'){await refresh();notify('已保存，设置即时生效。');return;}
    else if(action==='settings-reset')await request('settings-reset');
    else if(action==='choose-icon'||action==='reset-icon')await request(action);
    else if(action==='search-history'){m.keywordDraft=button.dataset.keyword;await request('search',{keyword:m.keywordDraft});}
    else if(action==='clear-search-history')await request('settings',{searchHistory:[]});
    else if(action==='online-cancel')await request(action);
    else if(action==='online-download'){await request(action,{index:Number(button.dataset.index),format:m.onlineFormat});}
    else if(action==='online-download-many'){
      const visible=new Set(V.groupedResults(state,m).map(group=>group.variants[0].index));const indexes=[...(m.onlineSelected||new Set())].filter(index=>visible.has(index)).slice(0,30);
      if(!indexes.length)throw new Error('请先勾选要下载的书籍。');
      m.onlineSelected=new Set();await request('online-download-many',{indexes,format:m.onlineFormat});
      notify(`已加入批量下载队列（${indexes.length} 本）。`);return;
    }
    else if(action==='online-clear-selection'){m.onlineSelected=new Set();render();return;}
    else if(action==='online-retry'){await request('online-retry');notify('已重新提交任务。');return;}
    else if(action==='tomato-cancel')await request('tomato-cancel');
    else if(action==='tomato-download'){const result=await request('tomato-download',{index:Number(button.dataset.index),format:m.tomatoFormat});if(result.error)throw new Error(result.error);notify(result.message);}
    else if(action==='tomato-other-source'){
      const target=state.tomato.results[Number(button.dataset.index)];if(!target)throw new Error('请先选择书籍。');
      m.searchTab='aggregate';m.keywordDraft=target.bookName;m.onlineQuery='';m.onlineSite='';m.onlineSort='match';m.onlineSelected=new Set();
      await request('search',{keyword:m.keywordDraft});
    }
    else if(action==='backup-export'){m.busy=true;render();try{const result=await request('backup-export');if(!result.canceled)notify(`备份已保存：${result.file}（${result.books} 本）`);}finally{m.busy=false;await refresh();}return;}
    else if(action==='backup-import'){m.busy=true;render();try{const result=await request('backup-import');if(!result.canceled)notify(result.message||`已导入 ${result.imported} 本，跳过 ${result.skipped} 本。`);}finally{m.busy=false;await refresh();}return;}
    else if(action==='open-profile'){await request('open-profile');return;}
    else if(action==='copy-link'){await request('copy-link',{index:Number(button.dataset.index)});notify('链接已复制。');return;}
    else if(action==='reset-online-filter'){m.onlineQuery='';m.onlineSite='';m.onlineSort='match';render();return;}
    else if(action==='engine-probe'){if(probeBusy)return;probeBusy=true;try{const result=await request('engine-probe');notify(`内置引擎 ${result.version} 可用，加载 ${result.sources} 个书源。`);}finally{probeBusy=false;}return;}
    else if(action==='save-filter-confirm'){await saveFilter(document.querySelector('#filter-name').value);m.dialog=null;}
    else if(action==='save-filter'){await saveFilter(document.querySelector('#saved-filter-name').value);}
    else if(action==='delete-filter'){if(m.savedFilter==='')return;const index=Number(m.savedFilter);await request('settings',{savedFilters:state.settings.savedFilters.filter((_,i)=>i!==index)});m.savedFilter='';m.filterName='';}
    else if(action==='window-refresh'){m.windows=await request('windows-list');render();return;}
    else if(action==='attach-start'){await request('attachment-start',{...m.appOptions,handle:m.attachHandle,title:m.attachTitle});notify('窗口依附已启用。');return;}
    else if(action==='attach-stop'){await request('attachment-stop');notify('已停止依附。');return;}
    else if(action==='app-web'||action==='app-sudoku'){await request('app-open',{kind:action.slice(4),options:m.appOptions});return;}
    else if(action==='app-save'){m.appOptions={...m.appOptions,attachmentTitle:m.attachTitle,attachmentHandle:m.attachHandle};await request('settings',{appOptions:m.appOptions});notify('应用配置已保存。');}
    else if(action==='plugin-toggle'){const list=new Set(state.settings.disabledPlugins);list.has(button.dataset.plugin)?list.delete(button.dataset.plugin):list.add(button.dataset.plugin);await request('settings',{disabledPlugins:[...list]});}
    else if(action==='plugin-open'){m.appsTab=button.dataset.plugin;render();return;}
    else if(action==='quit'){await request('quit');return;}
    await refresh();
  }
  root.addEventListener('click',event=>task(async()=>{
    const route=event.target.closest('[data-route]');if(route){m.route=route.dataset.route;m.dialog=null;render();return;}
    const tab=event.target.closest('[data-tab]');if(tab){m[tab.dataset.tab]=tab.dataset.value;if(tab.dataset.tab==='status')m.page=1;render();return;}
    const theme=event.target.closest('[data-theme]');if(theme){const colors=V.themes.find(item=>item[0]===theme.dataset.theme)[3];await request('settings',{theme:theme.dataset.theme,fontColor:colors[0],background:colors[1],highlightColor:colors[2]});await refresh();return;}
    const button=event.target.closest('[data-action]');if(button&&!button.disabled)await onAction(button);
  }));
  root.addEventListener('input',event=>{
    if(event.isComposing)return;// 中文输入法组词期间不重排，避免打断候选词。
    const input=event.target;if(input.id==='keyword')m.keywordDraft=input.value;
    for(const [id,key]of [['library-filter','query'],['online-filter','onlineQuery'],['chapter-query','chapterQuery'],['tomato-chapter-filter','tomatoChapterQuery']])if(input.id===id){m[key]=input.value;if(id==='online-filter')m.onlineSelected=new Set();if(id==='chapter-query')m.chapterLimit=300;m.page=1;render();}
    if(input.id==='tomato-keyword')m.tomatoQuery=input.value;
    if(input.id==='text-search-keyword'){m.textQuery=input.value;updateTextSearchList();}
    if(input.id==='saved-filter-name')m.filterName=input.value;
    if(input.dataset.appOption)m.appOptions[input.dataset.appOption]=input.type==='checkbox'?input.checked:['number','range'].includes(input.type)?Number(input.value):input.value;
  });
  // 只重建搜索结果列表与状态行，避免整树 innerHTML 重建导致输入框失焦。
  function updateTextSearchList(){
    const list=document.querySelector('#text-search-results'),statusNode=document.querySelector('#text-search-status');
    if(list)list.innerHTML=m.textResults.map(result=>`<button class="search-hit" data-action="seek" data-id="${window.ReaderViews.e(result.id)}" data-position="${result.position}"><strong>${window.ReaderViews.e(result.title)}</strong><p>${window.ReaderViews.e(result.snippet)}</p></button>`).join('');
    if(statusNode)statusNode.textContent=m.textStatus;
  }
  root.addEventListener('toggle',event=>{if(event.target.id==='saved-filter-disclosure')m.savedOpen=event.target.open;if(event.target.id.startsWith('settings-fold-')){m.closedSettings||={};m.closedSettings[event.target.id.slice(14)]=!event.target.open;}},true);
  root.addEventListener('change',event=>task(async()=>{
    const input=event.target;
    if(input.id==='consent-checkbox'){m.consentChecked=input.checked;document.querySelector('#consent-accept').disabled=!(m.consentRead&&m.consentChecked);return;}
    if(input.dataset.setting){const value=input.type==='checkbox'?input.checked:['number','range'].includes(input.type)||input.dataset.setting==='timeoutMinutes'?Number(input.value):input.value;await request('settings',{[input.dataset.setting]:value});await refresh();return;}
    if(input.dataset.selectBook){input.checked?m.selected.add(input.dataset.selectBook):m.selected.delete(input.dataset.selectBook);render();return;}
    if(input.dataset.onlineSelect!==undefined){m.onlineSelected ||= new Set();const index=Number(input.dataset.onlineSelect);input.checked?m.onlineSelected.add(index):m.onlineSelected.delete(index);render();return;}
    if(input.id==='online-select-all'){m.onlineSelected ||= new Set();const groups=V.groupedResults(state,m);for(const group of groups){const index=group.variants[0].index;input.checked?m.onlineSelected.add(index):m.onlineSelected.delete(index);}render();return;}
    if(input.id==='select-all'){const items=V.books(state,m).slice((m.page-1)*50,m.page*50);for(const book of items)input.checked?m.selected.add(book.id):m.selected.delete(book.id);render();return;}
    const maps={'library-sort':'sort','library-more':'more','library-category':'category','library-tag':'tag','library-source':'source','online-site':'onlineSite','online-sort':'onlineSort','online-format':'onlineFormat','tomato-format':'tomatoFormat'};if(maps[input.id]){m[maps[input.id]]=input.value;if(input.id==='online-site')m.onlineSelected=new Set();m.page=1;render();return;}
    if(input.id==='current-book'&&input.value){await request('open',{id:input.value});await refresh();}
    if(input.id==='saved-filter'&&input.value==='save')await openDialog('save-filter');
    else if(input.id==='saved-filter'){m.savedFilter=input.value;const saved=input.value!==''&&state.settings.savedFilters[Number(input.value)];if(saved){for(const key of ['query','status','more','sort'])m[key]=saved[key]??({query:'',status:'all',more:'all',sort:'default'}[key]);for(const key of ['category','tag','source'])m[key]=saved[key]||'';m.filterName=saved.name;m.page=1;}render();}
    if(input.id==='jump-mode'){document.querySelector('#jump-unit').textContent={percent:'%',page:'页',position:'字符'}[input.value];document.querySelector('#jump-value').min=input.value==='page'?'1':'0';}
    if(input.id==='attach-target'){m.attachHandle=input.value;m.attachTitle=m.windows.find(window=>String(window.handle)===input.value)?.title||'';render();}
    if(input.id==='attach-title')m.attachTitle=input.value;
    if(input.dataset.appOption){m.appOptions[input.dataset.appOption]=input.type==='checkbox'?input.checked:['number','range'].includes(input.type)?Number(input.value):input.value;if(input.dataset.appOption==='preset'){if(input.value==='stealth')Object.assign(m.appOptions,{gray:true,opacity:65,transparent:true});if(input.value==='text')Object.assign(m.appOptions,{gray:true,hideImages:true,transparent:true});}render();}
  }));
  root.addEventListener('submit',event=>{event.preventDefault();task(async()=>{
    if(event.target.id==='search-form'){await request('search',{keyword:document.querySelector('#keyword').value});await refresh();}
    if(event.target.id==='tomato-search-form'){m.tomatoChapterQuery='';await request('tomato-search',{keyword:document.querySelector('#tomato-keyword').value});await refresh();}
    if(event.target.id==='text-search-form'){m.textQuery=document.querySelector('#text-search-keyword').value;m.textStatus='正在搜索正文…';const scope=document.querySelector('#text-search-scope').value;m.textResults=[];render();const revision=++textSearchRevision;const results=await request('local-search',{keyword:m.textQuery,ids:scope==='all'?state.books.filter(book=>!book.missing).map(book=>book.id):[m.dialog?.bookId||state.activeId].filter(Boolean)});if(revision!==textSearchRevision)return;// 旧搜索晚到，丢弃以免覆盖新结果。
      m.textResults=results;m.textStatus=`找到 ${results.length} 条匹配（最多显示 100 条）。`;render();}
  });});
  root.addEventListener('dragover',event=>{if(!readerMode){event.preventDefault();document.querySelector('.dropzone')?.classList.add('dragover');}});
  root.addEventListener('dragleave',()=>document.querySelector('.dropzone')?.classList.remove('dragover'));
  root.addEventListener('drop',event=>{event.preventDefault();if(!readerMode)task(async()=>{m.busy=true;render();try{const paths=Array.from(event.dataTransfer.files).map(file=>window.reader.pathForFile(file));const report=await request('import-dropped',{files:paths});notify(`导入 ${report.imported} 本，重复 ${report.duplicates} 本，失败 ${report.errors.length} 本。`);}finally{m.busy=false;await refresh();}});});
  if(readerMode){
    document.addEventListener('contextmenu',event=>{event.preventDefault();task(()=>request('reader-context-menu'));});
    document.addEventListener('mouseenter',()=>{pointerInside=true;locateDismissed=true;if(state?.settings.mouseAutoShow)mouseHidden=false;lastInteraction=Date.now();task(paintReader);});
    document.addEventListener('mouseleave',()=>{pointerInside=false;if(!drag)mouseHidden=true;task(paintReader);});
    addEventListener('focus',()=>{focused=true;focusEventAt=Date.now();lastInteraction=Date.now();task(paintReader);});addEventListener('blur',()=>{focused=false;focusEventAt=Date.now();task(paintReader);});
    document.addEventListener('keydown',event=>{lastInteraction=Date.now();if(['ArrowRight','ArrowDown',' ','PageDown','ArrowLeft','ArrowUp','PageUp'].includes(event.key)){event.preventDefault();task(()=>move(['ArrowLeft','ArrowUp','PageUp'].includes(event.key)?-1:1,state.settings.lineScroll&&['ArrowUp','ArrowDown'].includes(event.key)));}if(event.key==='Escape'&&!event.defaultPrevented&&!document.querySelector('dialog[open]'))task(()=>request('hide-reader'));if((event.key==='s'||event.key==='S')&&!event.ctrlKey&&!event.metaKey&&!event.altKey&&!event.isComposing){event.preventDefault();toggleTts();}});
    let lastWheel=0;document.addEventListener('wheel',event=>{const delays={steady:250,normal:140,sensitive:50};if(!state?.settings.wheelPaused&&Date.now()-lastWheel>(delays[state?.settings.wheelSensitivity]||140)&&Math.abs(event.deltaY)>2){lastWheel=Date.now();lastInteraction=Date.now();task(()=>move(event.deltaY>0?1:-1,state.settings.lineScroll));}},{passive:true});
    document.addEventListener('dblclick',()=>task(()=>request('show-manager')));
    let drag;
    document.addEventListener('pointerdown',event=>{if(event.button!==0)return;const resize=Boolean(event.target.closest('.reader-resize'));if(resize&&!state.settings.resizable)return;const start={screenX:event.screenX,screenY:event.screenY,resize,ready:false};drag=start;request('window-state').then(bounds=>{if(drag===start)Object.assign(start,{bounds,ready:true});}).catch(()=>{if(drag===start)drag=null;/* IPC 失败时放弃本次拖拽，避免 ready 永远为 false。 */});event.target.setPointerCapture?.(event.pointerId);lastInteraction=Date.now();pointerInside=true;mouseHidden=false;task(paintReader);});
    document.addEventListener('pointermove',event=>{if(!drag?.ready)return;boundsEvent=event;// 每帧最多发一次 window-bounds，合并高频 move。
      if(boundsFrame)return;boundsFrame=requestAnimationFrame(()=>{boundsFrame=0;const move=boundsEvent;boundsEvent=null;if(!move||!drag?.ready)return;const dx=move.screenX-drag.screenX,dy=move.screenY-drag.screenY;if(Math.abs(dx)+Math.abs(dy)<3)return;const patch=drag.resize?{width:drag.bounds.width+dx,height:drag.bounds.height+dy}:{x:drag.bounds.x+dx,y:drag.bounds.y+dy};task(()=>request('window-bounds',patch));});});
    document.addEventListener('pointerup',()=>{drag=null;if(boundsFrame){cancelAnimationFrame(boundsFrame);boundsFrame=0;boundsEvent=null;}});document.addEventListener('pointercancel',()=>{drag=null;if(boundsFrame){cancelAnimationFrame(boundsFrame);boundsFrame=0;boundsEvent=null;}});
    addEventListener('resize',()=>{clearTimeout(resizeTimer);resizeTimer=setTimeout(()=>task(paintReader),80);});
    autoTimer=setInterval(()=>{if(!state)return;task(()=>paintReader());if(state.settings.autoRead&&!ttsActive&&!disguised()&&document.visibilityState==='visible'&&Date.now()-lastAuto>state.settings.autoReadSeconds*1000){lastAuto=Date.now();task(()=>move(1));}},1000);
    // 伪装地址轮换只重绘文本，不触发 read IPC。
    addressTimer=setInterval(()=>{if(state?.settings.fakeAddress&&disguised()&&document.visibilityState==='visible')paintReaderVisibility(lastPaint?.page||{text:''});},15000);
    let tickTimer=setInterval(()=>{if(state?.activeId&&!disguised()&&document.visibilityState==='visible')task(()=>request('reading-tick',{seconds:15}));},15000);
    pointerTimer=setInterval(async()=>{if(pointerBusy||!state)return;pointerBusy=true;try{const native=await request('window-state');if(!native)return;const before=readerVisibility();
      // focus/blur 事件刚发生后 500ms 内不覆盖 focused：事件代表用户意图（合成事件环境里
      // OS 焦点可能与之矛盾），轮询只在事件静默期兜底同步真实焦点。
      if(Date.now()-focusEventAt>500)focused=native.focused;
      if(!native.visible&&ttsActive)stopTts();pointerInside=native.pointerInside;if(pointerInside){locateDismissed=true;if(state.settings.mouseAutoShow)mouseHidden=false;}else if(!drag)mouseHidden=true;if(before!==readerVisibility())await paintReader();}catch(error){notify(error.message,true);}finally{pointerBusy=false;}},200);
    addEventListener('unload',()=>{clearInterval(autoTimer);clearInterval(pointerTimer);clearInterval(addressTimer);clearInterval(tickTimer);stopTts();});
  }
  // ---------- TTS 朗读（阅读窗内，使用系统语音；按 S 或右键菜单切换） ----------
  let ttsActive=false,ttsRevision=0;
  function stopTts(){ttsActive=false;ttsRevision++;try{window.speechSynthesis?.cancel();}catch{}}
  function speakCurrentPage(){
    if(!readerMode||!ttsActive||!state?.settings.ttsEnabled||disguised()||document.visibilityState!=='visible'){stopTts();return;}
    const page=lastPaint?.page,read=lastPaint?.read,text=(page?.text||'').trim();if(!text||!read?.id){stopTts();return;}
    const revision=ttsRevision,voices=window.speechSynthesis.getVoices(),voice=voices.find(voice=>/^zh/i.test(voice.lang)),utter=new SpeechSynthesisUtterance(text);
    if(voice)utter.voice=voice;utter.lang=voice?.lang||'zh-CN';utter.rate=state.settings.ttsRate;utter.volume=state.settings.ttsVolume;
    utter.onend=()=>{if(!ttsActive||revision!==ttsRevision)return;task(async()=>{
      if(disguised()||document.visibilityState!=='visible'){stopTts();return;}
      const current=await request('read',{count:1});if(revision!==ttsRevision)return;
      if(current.id!==read.id||current.position!==read.position||read.position+page.count>=read.length){stopTts();return;}
      await move(1,false,true);if(ttsActive&&revision===ttsRevision)speakCurrentPage();
    });};
    utter.onerror=()=>{if(revision===ttsRevision)stopTts();};window.speechSynthesis.speak(utter);
  }
  function toggleTts(){
    if(!readerMode)return;if(ttsActive){stopTts();notify('已停止朗读。');return;}
    if(!state?.settings.ttsEnabled){notify('请到设置 → 阅读开启朗读。',true);return;}
    if(!('speechSynthesis' in window)){notify('当前环境不支持语音朗读。',true);return;}
    ttsActive=true;ttsRevision++;speakCurrentPage();
  }
  window.reader.onTtsStop?.(()=>stopTts());
  document.addEventListener('visibilitychange',()=>{if(document.visibilityState!=='visible')stopTts();});
  document.addEventListener('compositionstart',()=>{composing=true;});
  document.addEventListener('compositionend',event=>{composing=false;event.target.dispatchEvent(new Event('input',{bubbles:true}));if(renderPending){renderPending=false;render();}});
  root.addEventListener('scroll',event=>{if(event.target.id!=='consent-scroll')return;const node=event.target;if(node.scrollHeight-node.clientHeight-node.scrollTop<=8){m.consentRead=true;const check=document.querySelector('#consent-checkbox');if(check)check.disabled=false;const status=document.querySelector('#consent-read-status');if(status)status.textContent='已阅读到底，请勾选确认。';}},true);
  async function measuredPagePosition(id,pageNumber){
    if(!Number.isSafeInteger(pageNumber)||pageNumber<1||pageNumber>1000000)throw new Error('请输入 1–1000000 的整数页码。');
    const s=state.settings,native=await request('window-state'),width=Math.max(1,(native?.width||s.windowWidth)-20),rows=s.multiLine?s.lineCount:1;
    textMeasure.font=(s.fontBold?'700':'400')+' '+s.fontSize+'px '+JSON.stringify(s.fontFamily)+',微软雅黑,Arial,sans-serif';
    await document.fonts.load(textMeasure.font);
    const budget=Math.min(32768,Math.max(256,Math.ceil(width/.5)*rows*2));let position=0,chunk='',chunkStart=0,length=Infinity;
    for(let n=1;n<pageNumber;n++){
      if(chunk.length-position+chunkStart<budget&&chunkStart+chunk.length<length){const data=await request('page-chunk',{id,start:position});chunk=data.text;chunkStart=position;length=data.length;}
      if(position>=length-1)return Math.max(0,length-1);
      const part=chunk.slice(position-chunkStart,position-chunkStart+budget),fit=window.ReaderLayout.fit(part,width,s.autoFitWidth?Infinity:s.lineSize,rows,value=>textMeasure.measureText(value).width);
      position+=Math.max(1,fit.count);if(position>=length)return Math.max(0,length-1);
      if(n%300===0)await new Promise(resolve=>setTimeout(resolve,0));
    }return position;
  }
  window.reader.onTtsToggle?.(()=>toggleTts());
  window.reader.onChanged(()=>{clearTimeout(refreshTimer);refreshTimer=setTimeout(()=>task(refresh),100);});
  window.reader.onNavigate?.(navigation=>task(async()=>{if(readerMode)return;m.route=navigation.route||'library';m.dialog=null;await refresh();if(navigation.dialog)await openDialog(navigation.dialog,navigation.bookId);}));
  task(refresh);
})();
