#!/usr/bin/env python3
"""Build a standalone read-only replay from retained real-model results. No API or database access."""
import argparse
import json
from pathlib import Path

HTML = r'''<!doctype html>
<html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Atlas · 跨事件解释对照</title>
<style>
:root{font-family:Inter,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;color:#172b45;background:#f3f6fa;font-size:15px}*{box-sizing:border-box}body{margin:0}header{padding:28px max(5vw,24px);background:#12283f;color:#f5f8fc}h1{font-size:28px;letter-spacing:-.8px;margin:7px 0 12px}header p{max-width:900px;color:#bdcddd;line-height:1.65}.eyebrow{color:#72d7c2;font-size:12px;letter-spacing:2px}main{max-width:1500px;margin:auto;padding:28px 4vw}select{background:white;padding:12px;border:1px solid #bdccd9;border-radius:8px;color:#172b45;max-width:100%;font-size:15px}.toolbar{display:flex;gap:16px;align-items:center;flex-wrap:wrap;margin:4px 0 22px}.badge{background:#e2f4ee;padding:6px 10px;border-radius:5px;font-size:12px}.warning{background:#fff2d6;border:1px solid #e8ce92;border-radius:8px;padding:14px;line-height:1.65}.timeline{display:flex;gap:12px;overflow:auto;margin:18px 0}.event{background:white;border-top:3px solid #3675a5;border-radius:7px;padding:14px;min-width:155px;box-shadow:0 2px 6px #172b4509}.event strong{font-size:13px}.event small{display:block;margin-top:6px;color:#60758a}.columns{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:18px;margin-top:22px}.card{background:white;border:1px solid #dce4ed;border-radius:12px;padding:20px;line-height:1.6}.card.ai{border-top:4px solid #0f8675}.card h2{font-size:17px;margin:0 0 14px}.summary{font-weight:600}.finding{font-size:13px;color:#536a80}.insight{padding-top:14px;margin-top:14px;border-top:1px solid #e4eaf0}.observed{font-weight:600;font-size:14px}.hypothesis{margin:10px 0;font-size:14px}.uncertainty{color:#697785;font-size:13px}button.citation{border:0;background:#edf2f8;color:#275a83;border-radius:4px;padding:4px 7px;margin:5px 4px 0 0;cursor:pointer;font-size:11px}button.citation:hover{background:#d5e8f8}.evidence{margin:20px 0;background:#e8f2fc;padding:16px;border-radius:8px;min-height:75px;overflow-wrap:anywhere;line-height:1.6}footer{font-size:12px;color:#687e92;line-height:1.7;margin-top:24px}details{font-size:13px;color:#536a80;margin-top:16px}pre{white-space:pre-wrap;overflow-wrap:anywhere}@media(max-width:980px){.columns{grid-template-columns:1fr}header{padding:25px}.toolbar{display:block}select{margin-bottom:10px}}
</style>
<header><div class="eyebrow">ATLAS / EVIDENCE EXPLORER</div><h1>复杂异常，逐条解释</h1>
<p>把当前交易状态放回事件序列，比较规则版、强化规则版和 AI 解释。时间与引用可核对；可能原因仍是待核实假设。</p>
<span class="badge" style="color:#123f35">只读 · 本地结果回放 · 不发起模型请求或交易</span></header>
<main><div class="toolbar"><label for="cases">选择案例</label><select id="cases"></select><span id="mode" class="badge"></span></div>
<div class="warning">这是实测结果的静态回放。程序验证引用、状态与时间关系，不能保证模型文字的语义正确；模型评审也不能代替人工核实。</div>
<div id="timeline" class="timeline"></div><div class="columns" id="columns"></div>
<div id="evidence" class="evidence" aria-live="polite">点击任一证据标签，查看对应的事件、规则或回执。</div>
<details><summary>查看本案例完整证据</summary><pre id="raw"></pre></details>
<footer id="footer"></footer></main>
<script id="data" type="application/json">__DATA__</script>
<script>
const study=JSON.parse(document.getElementById('data').textContent);
const names={receipt_before_uncertainty:'回执早于首次 UNKNOWN',receipt_recorded_later:'回执记录晚于首次 UNKNOWN',persistent_unknown_without_receipt:'持续 UNKNOWN，未观察到回执',eventual_execution:'从 UNKNOWN 到最终执行',eventual_venue_rejection:'从 UNKNOWN 到场所拒绝',delay_before_submission:'延迟发生在提交之前',old_receipt_state_still_unknown:'已有回执，状态仍未结算',rapid_terminal_counterexample:'快速恢复，不能误报长期延迟',timestamp_sequence_disagreement:'时间戳与事件顺序不一致',sparse_unknown_counterexample:'证据稀少，不能确定根因',missing_event_control:'事件缺失，应当弃答',foreign_receipt_control:'回执身份不符，应当弃答'};
const el=(tag,text,cls)=>{const n=document.createElement(tag);if(text!==undefined)n.textContent=text;if(cls)n.className=cls;return n;};
const select=document.getElementById('cases');study.results.forEach((row,i)=>{const opt=el('option',names[row.id]||row.id);opt.value=i;select.append(opt);});
function render(index){
 const row=study.results[index],facts=row.ai.evidence||[];document.getElementById('mode').textContent=row.ai.abstained?'证据不足 · 已弃答':'记录状态：'+row.ai.tradeStatus;
 const evidence=document.getElementById('evidence');evidence.textContent='点击任一证据标签，查看对应的事件、规则或回执。';
 const timeline=document.getElementById('timeline');timeline.replaceChildren();facts.filter(x=>['event','receipt'].includes(x.kind)).forEach(f=>{const item=el('div',undefined,'event');item.append(el('strong',f.kind==='receipt'?'匹配回执':'事件 '+f.id.split(':').pop()),el('small',f.observation));timeline.append(item);});
 const columns=document.getElementById('columns');columns.replaceChildren();
 const variants=[['规则版',row.rules,[]],['强化规则版',row.rules,row.strong_rule_attributions],['AI 跨事件解释',row.ai,row.ai.attributions||[]]];
 variants.forEach(([title,base,attrs],i)=>{const card=el('section',undefined,'card'+(i===2?' ai':''));card.append(el('h2',title),el('p',base.summary,'summary'));
  const cite=(parent,ids)=>ids.forEach(id=>{const button=el('button',id.startsWith('receipt:')?'回执':id.startsWith('rule:')?'规则 '+id.split(':')[1].split('@')[0]:id.startsWith('receipt_lookup:')?'回执查询':'事件 '+id.split(':').pop(),'citation');button.type='button';button.onclick=()=>{const f=facts.find(x=>x.id===id);evidence.textContent=f?f.observation+' · 来源：'+f.source:'未找到证据';};parent.append(button);});
  base.findings.forEach(f=>{const p=el('p',f.text,'finding');cite(p,f.evidenceIds);card.append(p);});
  attrs.forEach(a=>{const block=el('div',undefined,'insight');block.append(el('div',a.observedRelation,'observed'),el('p',(i===2?'AI 假设：':'规则解释：')+a.interpretation,'hypothesis'),el('p','尚不确定：'+a.uncertainty,'uncertainty'));cite(block,a.evidenceIds);card.append(block);});
  if(!attrs.length)card.append(el('p',base.abstained?'未生成归因。':'本版本没有额外的跨事件归因。','uncertainty'));
  columns.append(card);
 });
 document.getElementById('raw').textContent=JSON.stringify(facts,null,2);
 document.getElementById('footer').textContent='数据集：'+study.dataset_version+' · 模型：'+study.model+' · 生成提示版本：'+study.generator_prompt+'。当前结论以记录状态与规则事实为准。没有修改额度、批准、提交、重试或执行交易的入口。';
}
select.onchange=()=>render(Number(select.value));render(0);
</script></html>'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--results", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    report = json.loads(args.results.read_text())
    # Script data must never break out into active HTML; every displayed field uses textContent.
    encoded = json.dumps(report, ensure_ascii=False).replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(HTML.replace("__DATA__", encoded))
    print("Built standalone read-only comparison replay.")


if __name__ == "__main__":
    main()
