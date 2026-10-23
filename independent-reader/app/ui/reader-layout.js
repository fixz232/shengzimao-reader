'use strict';
// Measure the rendered text, rather than treating Latin and CJK glyphs as equal.
(() => {
  const segmenter=new Intl.Segmenter('zh-CN',{granularity:'grapheme'});
  const segments=text=>Array.from(segmenter.segment(text),item=>item.segment);
  let prefixCache={font:null,prefix:null};
  // Character-width prefix sums, cached per measure font so binary search becomes O(log n) lookups.
  function prefixWidths(chars,measure){
    if(prefixCache.font===measure&&prefixCache.prefix.length===chars.length+1){
      let ok=true;const prefix=prefixCache.prefix;
      for(let i=0;i<chars.length;i++)if(prefix[i+1]-prefix[i]!==measure(chars[i])){ok=false;break;}
      if(ok)return prefix;
    }
    const prefix=new Float64Array(chars.length+1);
    for(let i=0;i<chars.length;i++)prefix[i+1]=prefix[i]+measure(chars[i]);
    prefixCache={font:measure,prefix};
    return prefix;
  }
  function fittingCount(chars,width,limit,measure,fromEnd=false){
    const prefix=prefixWidths(chars,measure),length=Math.min(chars.length,limit);
    const total=off=>fromEnd?prefix[chars.length]-prefix[chars.length-off]:prefix[off];
    if(total(length)<=width+.01)return length||Math.min(1,chars.length);
    let low=0,high=length;
    while(low<high){const mid=Math.ceil((low+high)/2);if(total(mid)<=width+.01)low=mid;else high=mid-1;}
    return low||Math.min(1,chars.length);
  }
  function fit(text,width,limit,lineCount,measure,fromEnd=false){
    const chars=segments(text),lines=[],counts=[];let consumed=0;
    width=Math.max(1,width);limit=Math.max(1,Math.floor(limit));lineCount=Math.max(1,Math.floor(lineCount));
    for(let row=0;row<lineCount&&chars.length;row++){
      const size=fittingCount(chars,width,limit,measure,fromEnd),part=(fromEnd?chars.splice(chars.length-size,size):chars.splice(0,size)).join('');
      consumed+=part.length;if(fromEnd){lines.unshift(part);counts.unshift(part.length);}else{lines.push(part);counts.push(part.length);}
    }
    return {text:lines.join('\n'),lines,counts,count:consumed,start:fromEnd?text.length-consumed:0};
  }
  const api={fit};if(typeof module!=='undefined'&&module.exports)module.exports=api;else window.ReaderLayout=api;
})();
