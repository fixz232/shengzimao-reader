'use strict';
const VERSION='2026-10-23-v1';
const LINKS={project:'https://github.com/freeok/so-novel',disclaimer:'https://github.com/freeok/so-novel/blob/main/bundle/DISCLAIMER.md',license:'https://github.com/freeok/so-novel/blob/main/LICENSE',tomato:'https://fanqienovel.com',tomatoUpstream:'https://github.com/zhongbai2333/Tomato-Novel-Downloader'};
function accepted(state){return state.onlineConsent?.version===VERSION&&state.onlineConsent?.accepted===true;}
function accept(library,payload){
  if(payload.version!==VERSION||payload.read!==true||payload.accepted!==true)throw new Error('请先阅读并明确同意当前版本的在线功能声明。');
  library.state.onlineConsent={version:VERSION,accepted:true,acceptedAt:Date.now()};library.save();return true;
}
module.exports={VERSION,LINKS,accepted,accept};
