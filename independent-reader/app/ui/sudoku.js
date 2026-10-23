'use strict';
(() => {
  const sheet=document.querySelector('#sheet'),level=new URLSearchParams(location.search).get('difficulty')||'easy';let solution,puzzle,selected=0;
  const shuffle=values=>{for(let i=values.length-1;i>0;i--){const j=Math.floor(Math.random()*(i+1));[values[i],values[j]]=[values[j],values[i]];}return values;};
  function countSolutions(board,limit=2){let index=-1,candidates=[];for(let i=0;i<81;i++){if(board[i])continue;const r=Math.floor(i/9),c=i%9,used=new Set();for(let j=0;j<9;j++){used.add(board[r*9+j]);used.add(board[j*9+c]);used.add(board[(Math.floor(r/3)*3+Math.floor(j/3))*9+Math.floor(c/3)*3+j%3]);}const possible=[1,2,3,4,5,6,7,8,9].filter(n=>!used.has(n));if(!possible.length)return 0;if(index<0||possible.length<candidates.length){index=i;candidates=possible;}if(possible.length===1)break;}if(index<0)return 1;let count=0;for(const number of candidates){board[index]=number;count+=countSolutions(board,limit-count);board[index]=0;if(count>=limit)break;}return count;}
  function digHoles(target,done){
    let removed=0;const order=shuffle(Array.from({length:81},(_,i)=>i));
    for(const index of order){
      const value=puzzle[index];puzzle[index]=0;
      if(countSolutions([...puzzle])!==1)puzzle[index]=value;else removed++;
      if(removed>=target)break;
    }
    done(removed);
  }
  // Dig holes in time-sliced frames so the UI never freezes; fall back to a lighter
  // difficulty if the budget runs out before reaching the requested hole count.
  function digAsync(target,onDone){
    const startedAt=Date.now(),order=shuffle(Array.from({length:81},(_,i)=>i));
    let cursor=0,removed=0;
    const budget=level==='expert'?6000:3000;
    (function step(){
      const frameEnd=performance.now()+12;
      while(cursor<order.length&&removed<target&&performance.now()<frameEnd){
        const index=order[cursor++],value=puzzle[index];puzzle[index]=0;
        if(countSolutions([...puzzle])!==1)puzzle[index]=value;else removed++;
      }
      if(removed>=target||cursor>=order.length||Date.now()-startedAt>budget){
        if(removed<target&&level!=='easy'){// 超时回退：按简单难度补挖，保证棋盘可用。
          let extra=0;for(const index of order.slice(cursor)){if(removed+extra>=38)break;const value=puzzle[index];puzzle[index]=0;if(countSolutions([...puzzle])!==1)puzzle[index]=value;else extra++;}
          removed+=extra;
        }
        onDone(removed);return;
      }
      setTimeout(step,0);
    })();
  }
  function create(){
    const newButton=document.querySelector('#new');
    if(newButton.disabled)return;// 生成期间禁止连点，防止叠加多个挖洞任务。
    newButton.disabled=true;
    const rows=shuffle([0,1,2]).flatMap(group=>shuffle([0,1,2]).map(row=>group*3+row)),cols=shuffle([0,1,2]).flatMap(group=>shuffle([0,1,2]).map(col=>group*3+col)),digits=shuffle([1,2,3,4,5,6,7,8,9]);
    solution=rows.flatMap(row=>cols.map(col=>digits[(row*3+Math.floor(row/3)+col)%9]));puzzle=[...solution];
    render();// 先渲染棋盘（#status 在 render 里生成），之后才能安全写状态文本。
    status().textContent='正在生成棋盘…';
    const target={easy:38,medium:46,hard:51,expert:55}[level]||38;
    setTimeout(()=>digAsync(target,()=>{
      newButton.disabled=false;render();select(0);
    }),0);
  }
  function render(){sheet.innerHTML='<tr><th></th>'+Array.from({length:9},(_,i)=>'<th>'+String.fromCharCode(65+i)+'</th>').join('')+'<th class="note-head">备注</th></tr>'+Array.from({length:9},(_,row)=>'<tr><th>'+(row+1)+'</th>'+Array.from({length:9},(_,col)=>{const index=row*9+col;return '<td class="cell"><input aria-label="'+String.fromCharCode(65+col)+(row+1)+'" data-index="'+index+'" inputmode="numeric" maxlength="1" value="'+(puzzle[index]||'')+'" '+(puzzle[index]?'readonly':'')+'></td>';}).join('')+(row===0?'<td class="note" rowspan="9"><strong>季度排班表</strong><p>难度：'+({easy:'简单',medium:'普通',hard:'困难',expert:'专家'}[level]||'简单')+'</p><p>绿色格为原始数据，白色格可录入。</p><div class="keypad">'+[1,2,3,4,5,6,7,8,9].map(n=>'<button data-digit="'+n+'">'+n+'</button>').join('')+'</div><p id="status" role="status">休息一下，不算摸鱼。</p></td>':'')+'</tr>').join('');}
  function select(index){selected=index;sheet.querySelectorAll('.selected').forEach(cell=>cell.classList.remove('selected'));const cell=sheet.querySelector(`[data-index="${index}"]`);cell.parentElement.classList.add('selected');const name=cell.getAttribute('aria-label');document.querySelector('#cell-name').textContent=name;document.querySelector('#formula-value').textContent=`=IF(${name}=${solution[index]},"OK","继续")`;}
  const status=()=>document.querySelector('#status');
  sheet.addEventListener('input',event=>{const cell=event.target;if(!cell.matches('input'))return;cell.value=cell.value.replace(/[^1-9]/g,'').slice(-1);cell.classList.remove('invalid');});
  sheet.addEventListener('focusin',event=>{if(event.target.matches('input'))select(Number(event.target.dataset.index));});
  sheet.addEventListener('click',event=>{const button=event.target.closest('[data-digit]');if(!button)return;const cell=sheet.querySelector(`[data-index="${selected}"]`);if(!cell.readOnly){cell.value=button.dataset.digit;cell.classList.remove('invalid');}cell.focus({preventScroll:true});});
  sheet.addEventListener('keydown',event=>{const offsets={ArrowUp:-9,ArrowDown:9,ArrowLeft:-1,ArrowRight:1};if(event.key in offsets){event.preventDefault();const current=Number(event.target.dataset.index),row=Math.floor(current/9),col=current%9;const blocked=event.key==='ArrowUp'&&row===0||event.key==='ArrowDown'&&row===8||event.key==='ArrowLeft'&&col===0||event.key==='ArrowRight'&&col===8;if(blocked)return;const target=current+offsets[event.key];if(target>=0&&target<81)sheet.querySelector(`[data-index="${target}"]`).focus();}});
  document.querySelector('#new').onclick=create;document.querySelector('#close').onclick=()=>window.close();document.querySelector('#reset').onclick=()=>{const cell=sheet.querySelector(`[data-index="${selected}"]`);if(!cell.readOnly){cell.value='';cell.classList.remove('invalid');status().textContent='已清除当前单元格。';}else status().textContent='原始数据不可修改。';};
  document.querySelector('#check').onclick=()=>{let wrong=0,empty=0;for(const cell of sheet.querySelectorAll('input')){const invalid=cell.value&&Number(cell.value)!==solution[Number(cell.dataset.index)];cell.classList.toggle('invalid',Boolean(invalid));if(invalid)wrong++;if(!cell.value)empty++;}status().textContent=wrong?`${wrong} 个单元格需要检查。`:empty?`当前填写正确，还有 ${empty} 格。`:'全部正确，完成！';};
  document.querySelector('#hint').onclick=()=>{const cells=[...sheet.querySelectorAll('input:not([readonly])')],cell=cells.find(cell=>Number(cell.dataset.index)===selected&&Number(cell.value)!==solution[selected])||cells.find(cell=>Number(cell.value)!==solution[Number(cell.dataset.index)]);if(cell){cell.value=solution[Number(cell.dataset.index)];cell.classList.remove('invalid');cell.focus({preventScroll:true});status().textContent='已填入一格提示。';}};
  create();
})();
