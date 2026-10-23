"""Standalone review UI; all retained prose is inserted as text, never active markup."""
import base64
import hashlib
import json

SCRIPT = r'''
"use strict";
const packet=JSON.parse(document.getElementById('packet').textContent);
let answers=JSON.parse(document.getElementById('template').textContent),current=0;
const $=id=>document.getElementById(id);
const node=(tag,text,cls)=>{const e=document.createElement(tag);if(text!==undefined)e.textContent=text;if(cls)e.className=cls;return e;};
const answer=id=>answers.cases.find(c=>c.case_id===id);
const complete=row=>row.evaluations.every(r=>Number.isInteger(r.clarity)&&Number.isInteger(r.calibration))&&row.comparisons.every(p=>p.winner&&p.reason.trim());
const progress=()=>{$('progress').textContent=answers.cases.filter(complete).length+' / '+packet.cases.length+' 个案例已填写完整';};
packet.cases.forEach((c,i)=>{const o=node('option','案例 '+(i+1));o.value=i;$('cases').append(o);});
function textArea(label,value,update,max=3000){const wrap=node('label',label,'field'),input=node('textarea');input.value=value;input.maxLength=max;input.rows=2;input.addEventListener('input',()=>{update(input.value);progress();});wrap.append(input);return wrap;}
function choose(label,options,value,update){const wrap=node('label',label,'field'),select=node('select');select.setAttribute('aria-label',label);const blank=node('option','请选择');blank.value='';select.append(blank);options.forEach(([v,t])=>{const option=node('option',t);option.value=v;select.append(option);});select.value=value===null?'':String(value);select.onchange=()=>{update(select.value||null);progress();};wrap.append(select);return wrap;}
function citations(parent,ids,evidence){const refs=node('div',undefined,'citations');ids.forEach(id=>{const b=node('button',id);b.type='button';b.onclick=()=>{const fact=evidence.find(f=>f.id===id);$('citation').textContent=fact?fact.observation+' · '+fact.source:'未找到引用：'+id;$('citation').focus();};refs.append(b);});parent.append(refs);}
function render(){
 const c=packet.cases[current],row=answer(c.case_id);$('cases').value=current;$('heading').textContent='案例 '+(current+1)+' / '+packet.cases.length;$('citation').textContent='点击候选解释中的引用，可定位到记录证据。';
 $('evidence').replaceChildren();c.evidence.forEach(e=>{const li=node('li');li.append(node('code',e.id),node('p',e.observation),node('small',e.source));$('evidence').append(li);});
 $('goals').replaceChildren();c.goals.forEach(g=>$('goals').append(node('li',g.id+'：'+g.text)));
 $('forbidden').replaceChildren();c.forbidden_claims.forEach(f=>$('forbidden').append(node('li',f)));
 $('options').replaceChildren();Object.entries(c.options).forEach(([label,view])=>{
  const rating=row.evaluations.find(r=>r.label===label),card=node('section',undefined,'card');card.append(node('h3','候选 '+label),node('p',view.summary,'summary'));
  view.findings.forEach(f=>{const p=node('p',f.text);citations(p,f.evidenceIds,c.evidence);card.append(p);});
  view.attributions.forEach(a=>{const block=node('div',undefined,'attribution');block.append(node('strong',a.observation),node('p','解释：'+a.interpretation),node('p','不确定性：'+a.uncertainty,'muted'));citations(block,a.citations,c.evidence);card.append(block);});
  if(!view.attributions.length)card.append(node('p','没有额外的跨事件归因。','muted'));
  card.append(node('h4','建议核查'));view.nextChecks.forEach(check=>{const p=node('p',check.text);citations(p,check.evidenceIds,c.evidence);card.append(p);});
  const limits=node('ul');view.limitations.forEach(t=>limits.append(node('li',t)));card.append(limits,node('h4','你的评分'));
  c.goals.forEach(g=>{const l=node('label',undefined,'check'),input=node('input');input.type='checkbox';input.checked=rating.covered_goal_ids.includes(g.id);input.onchange=()=>{rating.covered_goal_ids=input.checked?[...rating.covered_goal_ids,g.id]:rating.covered_goal_ids.filter(id=>id!==g.id);};l.append(input,document.createTextNode(g.id+' 已得到解释支持'));card.append(l);});
  const scores=node('div',undefined,'scores');['clarity','calibration'].forEach((metric,i)=>scores.append(choose(i?'谨慎程度（1–5）':'清晰程度（1–5）',[1,2,3,4,5].map(x=>[String(x),String(x)]),rating[metric],v=>{rating[metric]=v===null?null:Number(v);})));card.append(scores);
  [['unsupported_claims','缺乏证据的断言'],['citation_errors','错误或不支持结论的引用'],['unsafe_advice','越过只读边界的建议']].forEach(([field,title])=>card.append(textArea(title+'（可留空；每行一项）',rating[field].join('\n'),v=>{rating[field]=v.split('\n').map(t=>t.trim()).filter(Boolean);},20000)));
  card.append(textArea('补充说明（可选）',rating.notes,v=>{rating.notes=v;}));$('options').append(card);
 });
 $('comparisons').replaceChildren();row.comparisons.forEach(pair=>{const box=node('section',undefined,'pair');box.append(choose(pair.left+' 与 '+pair.right+' 哪个更有帮助？',[[pair.left,pair.left],[pair.right,pair.right],['TIE','同等有用 / 同等不足']],pair.winner,v=>{pair.winner=v;}),textArea('判断理由（包括平局，完成本案例时必填）',pair.reason,v=>{pair.reason=v;}));$('comparisons').append(box);});
 $('previous').disabled=current===0;$('next').disabled=current===packet.cases.length-1;progress();
}
$('cases').onchange=()=>{current=Number($('cases').value);render();};
$('previous').onclick=()=>{current--;render();};$('next').onclick=()=>{current++;render();};
$('reviewer').oninput=()=>{answers.reviewer_id=$('reviewer').value.trim();};
function validate(a){
 const exact=(v,keys)=>v&&typeof v==='object'&&!Array.isArray(v)&&Object.keys(v).sort().join('|')===keys.slice().sort().join('|');
 const text=(v,max=3000)=>typeof v==='string'&&v.length<=max;
 if(!exact(a,['schema','packet_id','reviewer_id','cases'])||a.schema!=='atlas-human-annotations-v1'||a.packet_id!==packet.packet_id||!text(a.reviewer_id,100)||!Array.isArray(a.cases)||a.cases.length!==packet.cases.length)throw Error('批次或格式不匹配');
 const seen=new Set();for(const row of a.cases){const c=packet.cases.find(c=>c.case_id===row.case_id);if(!exact(row,['case_id','evaluations','comparisons'])||!c||seen.has(row.case_id)||!Array.isArray(row.evaluations)||row.evaluations.length!==3||!Array.isArray(row.comparisons)||row.comparisons.length!==3)throw Error('案例不匹配');seen.add(row.case_id);
  const labels=new Set();for(const r of row.evaluations){if(!exact(r,['label','covered_goal_ids','unsupported_claims','citation_errors','unsafe_advice','clarity','calibration','notes'])||!['A','B','C'].includes(r.label)||labels.has(r.label)||!Array.isArray(r.covered_goal_ids)||new Set(r.covered_goal_ids).size!==r.covered_goal_ids.length||r.covered_goal_ids.some(g=>!c.goals.some(x=>x.id===g))||!text(r.notes))throw Error('评分不匹配');labels.add(r.label);
   for(const m of ['clarity','calibration'])if(r[m]!==null&&(!Number.isInteger(r[m])||r[m]<1||r[m]>5))throw Error('分数应为 1–5');
   for(const f of ['unsupported_claims','citation_errors','unsafe_advice'])if(!Array.isArray(r[f])||r[f].length>20||r[f].some(t=>!text(t,1000)||!t.trim()))throw Error('问题说明过长');}
  const pairs=new Set();for(const p of row.comparisons){const id=p.left+p.right;if(!exact(p,['left','right','winner','reason'])||!['AB','AC','BC'].includes(id)||pairs.has(id)||![null,p.left,p.right,'TIE'].includes(p.winner)||!text(p.reason))throw Error('比较格式错误');pairs.add(id);}
 }return a;
}
$('export').onclick=()=>{try{validate(answers);if(!answers.reviewer_id.trim()){ $('reviewer').focus();throw Error('请先填写评审代号，不需要真实姓名');}const blob=new Blob([JSON.stringify(answers,null,2)+'\n'],{type:'application/json'}),url=URL.createObjectURL(blob),link=node('a');link.href=url;link.download='atlas-review-'+packet.packet_id.slice(0,12)+'.json';document.body.append(link);link.click();link.remove();setTimeout(()=>URL.revokeObjectURL(url),1000);$('message').textContent='已导出。未完成案例会保留为未完成；请保存文件后再关闭页面。';}catch(e){$('message').textContent=e.message;}};
$('import').onchange=async event=>{try{const file=event.target.files[0];if(!file)return;if(file.size>2000000)throw Error('评分文件过大');answers=validate(JSON.parse(await file.text()));$('reviewer').value=answers.reviewer_id;render();$('message').textContent='已恢复本批次评分。';}catch(e){$('message').textContent='无法导入：'+e.message;}event.target.value='';};
render();
'''

HTML = '''<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'sha256-__HASH__'; style-src 'unsafe-inline'; connect-src 'none'; base-uri 'none'; form-action 'none'">
<title>Atlas · 解释盲评</title><style>
*{box-sizing:border-box}body{margin:0;background:#f3f6f8;color:#173048;font:15px/1.65 system-ui,sans-serif}header{background:#14334b;color:white;padding:28px 4vw}header p{max-width:1050px;color:#cfdee7}h1{font-size:27px}h2{font-size:21px}h3{font-size:20px}h4{margin-bottom:10px}main{max-width:1600px;margin:auto;padding:24px 3vw}button,select,input,textarea{font:inherit}button,select,input[type=text]{border:1px solid #acbfcc;border-radius:6px;background:white;color:#173048;padding:9px 12px}button{cursor:pointer}button:disabled{opacity:.4;cursor:default}.toolbar{display:flex;align-items:center;gap:12px;flex-wrap:wrap;margin:15px 0}.panel{background:white;padding:20px;border:1px solid #d5e0e7;border-radius:10px;margin-bottom:20px}.options{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:16px}.card{background:white;border:1px solid #cddbe4;border-radius:10px;padding:20px;overflow-wrap:anywhere}.card h3{border-bottom:3px solid #268b81;padding-bottom:12px}.attribution{border-top:1px solid #dbe6ed;padding-top:14px;margin-top:14px}.summary{font-weight:650}.muted,small{color:#5b7182}.field{display:block;margin:12px 0}textarea{display:block;width:100%;border:1px solid #adbfcc;border-radius:5px;padding:9px;margin-top:6px}.field select{margin:6px 8px;max-width:100%}.scores,.comparisons{display:flex;gap:16px;flex-wrap:wrap}.pair{flex:1;min-width:240px}.check{display:block;margin:8px 0}.check input{margin-right:8px}.citations{display:flex;flex-wrap:wrap;gap:5px;margin:7px 0}.citations button{font-size:11px;padding:3px 5px;overflow-wrap:anywhere;max-width:100%;text-align:left;background:#edf4f9}.evidence li{padding:10px 0;border-bottom:1px solid #e4ebef}.evidence code{overflow-wrap:anywhere}.evidence p{margin:5px 0}.notice{padding:14px;background:#fff2d9;border-radius:7px}#citation{background:#e1f2f1;padding:16px;overflow-wrap:anywhere;margin:20px 0}#message{color:#234f6c}footer{font-size:13px;margin:25px 0;color:#5b7182}@media(max-width:1050px){.options{grid-template-columns:1fr}.pair{flex-basis:100%}}
</style></head><body><header><h1>Atlas · 哪个解释更有帮助？</h1><p>请依据记录证据比较三个匿名候选。先判断陈述是否得到支持，再看是否解释了异常、是否说清不确定性。允许平局；不要因为解释更长就给高分。</p><p>本页只读，无模型、交易或额度操作。评分保存在当前页面内存；导出后可恢复。不会上传、自动保存或联系任何人。</p></header><main>
<div class="toolbar"><label>评审代号 <input id="reviewer" type="text" maxlength="100" autocomplete="off" placeholder="自选代号，无需真实姓名"></label><button type="button" id="export">导出评分 / 保存进度</button><label>恢复评分 <input id="import" type="file" accept="application/json,.json"></label><span id="progress"></span></div><p id="message" role="status"></p>
<details class="panel"><summary>评分说明</summary><p>清晰程度：1 难以理解，3 基本可用，5 简洁具体、容易核对。谨慎程度：1 把推测写成定论或越权，3 大体谨慎但有模糊表述，5 清楚区分事实、假设、未知与只读边界。</p><p>只勾选该候选确实覆盖的目标。问题说明应指出具体句子及原因。引用 ID 存在，不代表能支持解释。事件时间戳不等于回执到达或可见时间；事件条数不等于实际提交次数。</p><p>匿名仅隐藏来源元数据；措辞可能提供线索。请在提交评分前不要查看组织者的来源映射或原始研究结果。参考目标是开发案例的评审辅助，不是独立真相。</p></details>
<div class="toolbar"><button id="previous" type="button">上一例</button><select id="cases" aria-label="选择案例"></select><button id="next" type="button">下一例</button><h2 id="heading"></h2></div>
<details class="panel" open><summary>记录证据</summary><ol id="evidence" class="evidence"></ol></details><section class="panel"><h3>评审目标</h3><ol id="goals"></ol><h4>应避免的断言</h4><ul id="forbidden"></ul></section>
<div id="options" class="options"></div><div id="citation" tabindex="-1" aria-live="polite"></div><section class="panel"><h3>两两比较</h3><div id="comparisons" class="comparisons"></div></section><footer>候选顺序按批次随机排列。未完成案例不会当作平局或胜出。评分结果需由组织者在本地验证、汇总；浏览器页面不揭示来源。</footer>
</main><script type="application/json" id="packet">__PACKET__</script><script type="application/json" id="template">__TEMPLATE__</script><script>__SCRIPT__</script></body></html>'''


def encode(value):
    return json.dumps(value, ensure_ascii=False).replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026")


def render(packet, template):
    script_hash = base64.b64encode(hashlib.sha256(SCRIPT.encode()).digest()).decode()
    # Replace data last; model or annotation strings never become template directives.
    return HTML.replace("__HASH__", script_hash).replace("__SCRIPT__", SCRIPT).replace(
        "__TEMPLATE__", encode(template)).replace("__PACKET__", encode(packet))
