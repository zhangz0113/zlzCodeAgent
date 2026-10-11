(() => {
  'use strict';
  const payload = JSON.parse(document.getElementById('report-data').textContent), data = payload.data;
  const el = (tag, text, cls) => { const n = document.createElement(tag); if (text != null) n.textContent = text; if (cls) n.className = cls; return n; };
  const add = (parent, tag, text, cls) => { const n = el(tag, text, cls); parent.append(n); return n; };
  const link = (parent, title, path) => { const n = add(parent, 'a', title); n.href = path.split('/').map(encodeURIComponent).join('/'); return n; };
  const detail = (parent, title, cls = '') => { const n = add(parent, 'details', null, cls); add(n, 'summary', title); return add(n, 'div'); };
  const json = (parent, value) => add(parent, 'pre', JSON.stringify(value, null, 2));
  const pct = x => x == null ? '不适用' : (x * 100).toFixed(1) + '%';
  const labels = {PASS:'通过',FAIL:'失败',ERROR:'环境／执行错误',PENDING_REVIEW:'待人工审核',NOT_RUN:'未执行',DATA_ISSUE:'报告数据问题',SAME_EXECUTION:'同次执行',OVERLAPPING_ATTEMPTS:'尝试样本重叠',NOT_COMPARABLE:'条件不可比',LIMITED_COMPARISON:'有限比较',INSUFFICIENT_OUTCOMES:'结论不足',ADDED:'新增案例',MISSING:'缺失案例',VERSION_CHANGED:'案例版本变化',OBSERVED_IMPROVEMENT:'本次观察改善',OBSERVED_REGRESSION:'本次观察退化',UNCHANGED:'表现不变',REPEATED_DISTRIBUTION:'重复结果分布'};
  document.title = data.title + ' · CodeAgent';
  document.getElementById('title').textContent = data.title;
  document.getElementById('subtitle').textContent = '生成于 ' + data.generatedAt + ' · 离线报告快照';
  const overview = document.getElementById('overview'), results = document.getElementById('results'), nav = document.getElementById('links');
  function summary(parent, s) {
    const cards = add(parent, 'div', null, 'cards');
    for (const [name,value] of [['计划尝试',s.planned], ...Object.entries(s.counts).map(([k,v]) => [labels[k] || k,v])]) {
      const card = add(cards,'div',null,'metric'); add(card,'span',name,'muted'); add(card,'strong',value);
    }
    add(parent,'p',`有效判定通过率 ${pct(s.passRate)}，分母 ${s.decided}；已执行 ${s.executed}。错误、待审、未执行及数据问题不进入有效分母。`,'notice');
    const cost = detail(parent,'用量与覆盖范围');
    const table = add(cost,'table');
    const head = add(table,'tr'); ['指标','已记录合计','有数据 Run / 已知 Run'].forEach(x=>add(head,'th',x));
    for (const [name,m] of Object.entries(s.metrics)) { const row=add(table,'tr'); [name,m.observedTotal ?? '未知',`${m.recordedRuns} / ${m.totalRuns}`].forEach(x=>add(row,'td',x)); }
    add(cost,'p',`尝试累计耗时：${s.cumulativeDurationMs == null ? '未知' : (s.cumulativeDurationMs/1000).toFixed(1)+' 秒'}；耗时有记录 ${s.durationRecordedAttempts} / ${s.planned}。`);
  }
  function filters(items, getter) {
    const bar = add(results,'div',null,'filters'), controls=[];
    for(const [key,title] of [['group','条件组'],['status','结果'],['category','能力']]) {
      if(key==='category' && payload.kind!=='report') continue;
      const label=add(bar,'label',title+' '), select=add(label,'select'); const all=add(select,'option','全部'); all.value='';
      [...new Set(items.map(item=>getter(item,key)))].filter(Boolean).forEach(v=>{const o=add(select,'option',labels[v]||v);o.value=v;});
      controls.push([key,select]);
    }
    const label=add(bar,'label','案例搜索 '), search=add(label,'input'); search.type='search'; search.placeholder='案例 ID';
    const count=add(bar,'span','', 'muted');
    const apply=()=>{ let visible=0; items.forEach((item,i)=>{const show=controls.every(([k,c])=>!c.value||getter(item,k)===c.value)&&item.caseId.toLowerCase().includes(search.value.toLowerCase()); elements[i].classList.toggle('hidden',!show); if(show)visible++;});count.textContent=`显示 ${visible} / ${items.length}`; };
    const elements=[]; controls.forEach(([,c])=>c.addEventListener('change',apply));search.addEventListener('input',apply);
    return {elements,apply};
  }
  if(payload.kind==='report') {
    link(nav,'Markdown 汇总','report.md'); link(nav,'JSON 明细','report.json');
    summary(overview,data.summary);add(overview,'p',data.metricScope,'muted');
    data.dataIssues.forEach(x=>add(overview,'p',x,'notice'));
    const cats=detail(overview,'各能力类别统计');
    const table=add(cats,'table');const head=add(table,'tr');['能力','尝试','PASS / FAIL','待审 / 错误 / 未执行','有效判定通过率'].forEach(x=>add(head,'th',x));
    Object.entries(data.categories).forEach(([category,s])=>{const row=add(table,'tr');[category,s.planned,`${s.counts.PASS} / ${s.counts.FAIL}`,`${s.counts.PENDING_REVIEW} / ${s.counts.ERROR} / ${s.counts.NOT_RUN}`,pct(s.passRate)].forEach(x=>add(row,'td',x));});
    const conditions=document.getElementById('conditions');add(conditions,'h2','实验条件与来源');
    data.groups.forEach(g=>{const body=detail(conditions,`${g.label} · ${g.suite.id}`,'panel');json(body,g);});
    const identity=detail(conditions,'报告生成器与消费文件摘要');json(identity,{generator:data.generator,sourceHashes:data.sourceHashes});
    if(data.issues.length) {
      const issues=document.getElementById('issues');add(issues,'h2','问题分析 · 不改变原始判定');
      data.issues.forEach(card=>{const e=card.current.entry, box=add(issues,'article',null,'issue');link(box,e.title,'issues/'+card.path);add(box,'p',`${e.origin} / ${e.confidence} / ${e.status}`,'muted');add(box,'p',e.observed);add(box,'p','下一步：'+e.nextAction);});
    }
    add(results,'h2','逐题结果与证据');
    const filtering=filters(data.rows,(r,k)=>r[k]);
    data.rows.forEach(r=>{
      const container=add(results,'details',null,'case');filtering.elements.push(container);
      const heading=add(container,'summary'); add(heading,'span',labels[r.status]||r.status,'badge '+r.status);heading.append(document.createTextNode(`${r.caseId} v${r.caseVersion} · ${r.group} · 第 ${r.attemptIndex} 次`));
      const box=add(container,'div');add(box,'p',r.reason+' — '+r.detail);add(box,'p',`初始：${r.initialStatus ?? '未记录'} → 当前：${r.status} · attemptId：${r.attemptId ?? '未创建'}`,'muted');
      r.pendingChecks.forEach(x=>add(box,'p','待审：'+x,'notice'));r.missingEvidence.forEach(x=>add(box,'p','缺失：'+x,'notice'));
      data.issues.filter(card=>card.current.entry.attempts.some(ref=>ref.group===r.group&&ref.attemptId===r.attemptId)).forEach(card=>{const p=add(box,'p','关联问题：');link(p,card.current.entry.title,'issues/'+card.path);});
      add(box,'h3','步骤与回答');r.steps.forEach(step=>{const d=detail(box,`${step.id} · Session ${step.session} · ${step.status}`);add(d,'p',`Run：${step.runId ?? '未执行'}；错误：${step.errorCode ?? '无'}`,'muted');add(d,'pre',step.answer??'没有最终回答');json(d,step.metrics);});
      const checks=detail(box,`自动检查（${Array.isArray(r.checks)?r.checks.length:0} 项）`);const table=add(checks,'table',null,'checks');
      if(Array.isArray(r.checks))r.checks.forEach(c=>{const tr=add(table,'tr');add(tr,'td',c.passed?'通过':'失败',c.passed?'PASS':'FAIL');add(tr,'td',c.id);add(tr,'td',c.detail);});
      const effective=detail(box,'实际条件与耗时');json(effective,{conditions:r.conditions,runDurationMs:r.runDurationMs,verificationDurationMs:r.verificationDurationMs,totalDurationMs:r.totalDurationMs});
      const evidence=detail(box,`证据文件（${Object.keys(r.evidence).length}）`), list=add(evidence,'ul',null,'evidence');Object.entries(r.evidence).forEach(([name,path])=>link(add(list,'li'),name,path));
    });filtering.apply();
  } else {
    link(nav,'基线报告',data.baseline);link(nav,'候选报告',data.candidate);link(nav,'Markdown 汇总','comparison.md');link(nav,'JSON 明细','comparison.json');
    add(overview,'p',data.experiment.hypothesis,'panel');const exp=detail(overview,'声明变量与环境依据','panel');json(exp,data.experiment);
    add(overview,'p','比较只使用保存的观察结果。重复序号不代表相同随机条件；成本不抵消正确性失败。','notice');
    add(results,'h2','逐题比较');const filtering=filters(data.changes,(r,k)=>k==='status'?r.conclusion:r[k]);
    data.changes.forEach(c=>{const box=add(results,'article',null,'case');filtering.elements.push(box);const h=add(box,'h3');add(h,'span',labels[c.conclusion]||c.conclusion,'badge '+c.conclusion);h.append(document.createTextNode(`${c.caseId} v${c.caseVersion} · ${c.group}`));
      const sides=add(box,'div',null,'two');for(const [name,s]of[['基线',c.baseline],['候选',c.candidate]]){const side=add(sides,'div');add(side,'strong',name);add(side,'p',`PASS ${s.counts.PASS} / 有效 ${s.decided} · ${pct(s.passRate)}`);json(side,s.counts);}
      c.reasons.forEach(r=>add(box,'p',r,'notice'));const changes=detail(box,'声明变量差异');json(changes,c.declaredDifferences);
      const cost=detail(box,'资源观察与两侧均通过案例的成本');json(cost,{scope:'已记录主循环用量；通过样本数及覆盖率必须一起阅读',baselineAll:c.baseline.metrics,candidateAll:c.candidate.metrics,baselinePassing:c.baselinePassingCost,candidatePassing:c.candidatePassingCost});
    });filtering.apply();
  }
})();
