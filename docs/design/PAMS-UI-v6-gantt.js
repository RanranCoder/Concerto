/* Review-only activity planning workspace. All changes stay in memory. */
(() => {
  'use strict';
  const E = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const $ = (s,r=document)=>r.querySelector(s);
  const svg = name => {
    const paths = {plus:'M12 5v14M5 12h14',back:'m14 6-6 6 6 6',close:'m6 6 12 12M18 6 6 18',search:'M21 21l-5-5M18 10a8 8 0 1 1-16 0 8 8 0 0 1 16 0',alert:'m12 3 10 18H2zM12 9v5M12 17h.01',calendar:'M4 5h16v16H4zM8 3v4M16 3v4M4 10h16',chevron:'m9 5 7 7-7 7',folder:'M3 7h6l2 2h10v12H3zM3 7V4h7l2 3',check:'m5 12 4 4 10-10',link:'m9 15 6-6M8 9l-3 3a4 4 0 0 0 6 6l3-3M10 9l3-3a4 4 0 0 1 6 6l-3 3'};
    return `<svg aria-hidden="true" viewBox="0 0 24 24"><path d="${paths[name]||paths.calendar}"/></svg>`;
  };
  const btn=(text,action,value='',primary=false,extra='')=>`<button type="button" class="gt-btn${primary?' primary':''}" data-gt="${action}" data-id="${E(value)}" ${extra}>${text}</button>`;
  const link=(text,action,value='')=>`<button type="button" class="gt-link" data-gt="${action}" data-id="${E(value)}">${text}</button>`;
  const dayMs=86400000, num=s=>Date.parse(s+'T00:00:00Z')/dayMs;
  const add=(s,n)=>new Date((num(s)+n)*dayMs).toISOString().slice(0,10);
  const fmt=s=>s?`${Number(s.slice(5,7))}/${Number(s.slice(8,10))}`:'未排期';
  const range=t=>t.start?`${fmt(t.start)} — ${fmt(t.end)}`:'未排期';
  const depts=['党建办公室','组织部','宣传部','文秘部','未分配部门'];
  const databases=new Map();
  let active, tasks=[], nextId=100, undoSnapshot=null, undoActivity=null, noticeTimer, trigger, initialForm='', pendingChange, currentTask=null, scenarioBackup=null;
  const state={query:'',dept:'全部部门',filter:'all',view:matchMedia('(max-width:700px)').matches?'list':'gantt',zoom:'day',offset:0,collapsed:new Set(),deps:true,role:'manager',scenario:'normal',selected:null};
  const root=document.createElement('section');root.id='gantt';root.className='view gt';root.hidden=true;$('.content').append(root);
  const drawer=document.createElement('dialog');drawer.className='gt-dialog gt-drawer';drawer.setAttribute('aria-labelledby','gt-drawer-title');document.body.append(drawer);
  const confirm=document.createElement('dialog');confirm.className='gt-dialog gt-confirm';confirm.setAttribute('aria-labelledby','gt-confirm-title');document.body.append(confirm);
  const toast=document.createElement('div');toast.className='gt-toast';toast.role='status';toast.hidden=true;document.body.append(toast);
  const ref=()=>add(active.date,-2);
  const manager=()=>state.role==='manager'&&active.status!=='已归档';
  const canProgress=()=>state.role!=='viewer'&&active.status!=='已归档';
  function seed(a){
    if(databases.has(a.id))return databases.get(a.id);
    const d=n=>add(a.date,n);
    const rows=[
      [1,'确认活动目标与分工','党建办公室',a.owner,-10,-8,100,null,false,'basic','明确活动目标、参加范围及各部门分工。'],
      [2,'策划初稿编写','党建办公室',a.owner,-7,-5,100,1,false,'plan','补充活动流程、预算与应急预案。'],
      [3,'策划定稿确认','党建办公室',a.owner,-4,-4,100,2,true,'plan','确认文档定稿后，再到策划书页面提交审核。任务进度不会替代审批。'],
      [4,'场地与设备确认','组织部','周同学',-7,-3,60,null,false,'agenda','联系场地，完成投影与音响调试。'],
      [5,'核对参会名单','组织部','陈同学',-5,-1,75,3,false,'signin','核对参加人员后，在签到页面维护应签名单。'],
      [6,'现场会务准备','组织部','周同学',-1,0,0,4,false,'agenda','布置场地，复核议程和现场工作人员分工。'],
      [7,'预热推文发布','宣传部','林同学',-5,-2,80,3,false,'articles','检查稿件及配图，到推文页面完成审核与发布。'],
      [8,'活动摄影与报道','宣传部','林同学',0,2,0,7,false,'articles','活动当天采集照片，并完成新闻报道。'],
      [9,'活动举办','文秘部',a.owner,0,0,0,6,true,'agenda','活动日里程碑。确认各项准备工作后按议程执行。'],
      [10,'汇总签到记录','文秘部','王同学',1,2,0,9,false,'signin','活动后核对签到与补签记录。'],
      [11,'活动材料归档','文秘部','王同学',3,5,0,10,false,'materials','汇集策划、名单、签到汇总、照片与报道材料。'],
      [12,'准备备用物资','未分配部门','',null,null,0,null,false,'materials','待确认负责部门、负责人和日期。']
    ].map(([id,name,dept,owner,start,end,progress,dep,milestone,module,description])=>({id,name,dept,owner,start:start===null?'':d(start),end:end===null?'':d(end),progress,dep,milestone,module,description}));
    if(a.status==='已归档')rows.forEach(t=>t.progress=100);
    databases.set(a.id,rows);a.done=rows.filter(t=>t.progress===100).length;a.total=rows.length;return rows;
  }
  const predecessor=t=>tasks.find(x=>x.id===t.dep);
  const conflict=t=>!!(t.start&&predecessor(t)?.end&&t.start<=predecessor(t).end);
  const late=t=>!!(t.end&&t.end<ref()&&t.progress<100);
  const tone=t=>t.progress===100?'done':conflict(t)?'conflict':late(t)?'late':!t.start||t.start>ref()&&t.progress===0?'wait':'';
  const status=t=>t.progress===100?'已完成':!t.start?'未排期':late(t)?'已逾期':t.start>ref()&&t.progress===0?'待开始':'进行中';
  const statusHTML=t=>`<span class="gt-state ${tone(t)}">${status(t)}${conflict(t)?' · 前置冲突':''}</span>`;
  const modules={basic:'基本信息',plan:'策划书',agenda:'议程',signin:'签到',materials:'材料',articles:'推文'};
  function summary(a){
    const list=seed(a),done=list.filter(t=>t.progress===100).length,reference=add(a.date,-2);
    const overdue=list.filter(t=>t.end&&t.end<reference&&t.progress<100),unscheduled=list.filter(t=>!t.start);
    return `<section class="ev-panel gt"><header class="ev-head"><div><h2>任务计划</h2><p>按部门分工，跟进活动前后各项准备</p></div><div>${link('管理任务计划','open',a.id)}</div></header><div class="gt-summary"><div class="gt-summary-line"><strong>${done} / ${list.length} 项完成</strong><div class="gt-track"><i style="width:${done/list.length*100}%"></i></div><span>${Math.round(done/list.length*100)}%</span></div><div class="gt-risk">${svg('alert')}<p>${overdue.length} 项逾期 · ${unscheduled.length} 项未排期 <small>（演示基准日 ${fmt(reference)}）</small></p></div><div class="gt-summary-list">${list.filter(t=>t.progress<100).slice(0,3).map(t=>`<button data-gt="summary-task" data-id="${t.id}" data-activity="${a.id}"><span>${E(t.name)}<small>${E(t.dept)} / ${E(t.owner||'待分配')} · ${range(t)}</small></span><span>${t.progress}% ${svg('chevron')}</span></button>`).join('')}</div><small>任务完成不会自动推进活动阶段或完成策划审核。</small></div></section>`;
  }
  function updateUrl(){const u=new URL(location.href);u.searchParams.set('view','gantt');u.searchParams.set('activity',active.id);u.searchParams.delete('tab');history.replaceState(null,'',u);}
  function open(a,taskId){
    unmount();
    active=a;tasks=seed(a);Object.assign(state,{query:'',dept:'全部部门',filter:'all',offset:0,scenario:'normal',selected:null});state.collapsed.clear();
    document.querySelectorAll('.view').forEach(v=>v.hidden=v!==root);$('.ps-detail')?.classList.remove('ps-detail');$('#crumb').textContent='活动管理 / 任务计划';
    document.querySelectorAll('.nav [data-view]').forEach(b=>{const yes=b.dataset.view==='activities';b.classList.toggle('active',yes);if(yes)b.setAttribute('aria-current','page');else b.removeAttribute('aria-current');});
    scenarioBackup=null;render();updateUrl();window.scrollTo(0,0);$('h1',root).tabIndex=-1;$('h1',root).focus({preventScroll:true});if(taskId)details(taskId);
  }
  function unmount(){if(root.classList.contains('gt-embedded')){root.classList.remove('gt-embedded');root.classList.add('view');root.hidden=true;$('.content').append(root);}}
  function mount(a,host){if(!host)return;if(!active||active.id!==a.id){Object.assign(state,{query:'',dept:'全部部门',filter:'all',offset:0,selected:null});state.collapsed.clear();}active=a;tasks=seed(a);scenarioBackup=null;state.scenario='normal';root.classList.remove('view');root.classList.add('gt-embedded');root.hidden=false;host.append(root);render();}
  function navigate(tab='basic') {root.hidden=true;window.ActivityPrototype.open(active.id,tab);}
  function filtered(){return tasks.filter(t=>(state.dept==='全部部门'||t.dept===state.dept)&&(!state.query||`${t.name} ${t.owner}`.toLowerCase().includes(state.query.toLowerCase()))&&({all:true,late:late(t),conflict:conflict(t),unscheduled:!t.start,risk:late(t)||conflict(t)||!t.start})[state.filter]);}
  function counts(){return {all:tasks.length,late:tasks.filter(late).length,conflict:tasks.filter(conflict).length,unscheduled:tasks.filter(t=>!t.start).length};}
  function render(){
    if(!active)return;const count=counts(),done=tasks.filter(t=>t.progress===100).length;
    root.innerHTML=`${link(svg('back')+'返回活动详情','back')}<header class="gt-heading"><div><div class="gt-kicker">${E(active.department)} / <span class="ev-tag blue">${E(active.status)}</span></div><h1>${E(active.title)} <span style="font-weight:400;color:var(--muted)">/ 任务计划</span></h1><p>${svg('calendar')} 活动日 ${E(active.date)}　${E(active.time)}　${E(active.place)}　负责人 ${E(active.owner)}</p></div><div class="gt-actions"><label class="gt-mode">演示身份<select id="gt-role" aria-label="演示身份"><option value="manager" ${state.role==='manager'?'selected':''}>任务管理员</option><option value="executor" ${state.role==='executor'?'selected':''}>执行成员</option><option value="viewer" ${state.role==='viewer'?'selected':''}>只读查看者</option></select></label>${manager()?btn(svg('plus')+'新建任务','new','',true):''}</div></header><nav class="gt-subnav" aria-label="活动相关工作"><button aria-current="page" data-gt="home">任务计划</button>${Object.entries(modules).map(([key,label])=>`<button data-gt="module" data-id="${key}">${label}</button>`).join('')}</nav><div class="gt-statusline"><div class="gt-progress-summary"><strong>${done}<span style="font-size:13px;font-weight:400;color:var(--muted)"> / ${tasks.length} 项完成</span></strong><div class="gt-track"><i style="width:${tasks.length?done/tasks.length*100:0}%"></i></div><span>${tasks.length?Math.round(done/tasks.length*100):0}%</span></div><div class="gt-statfilters" aria-label="按任务情况筛选">${[['all','全部'],['late','逾期'],['conflict','前置冲突'],['unscheduled','未排期']].map(([key,label])=>`<button class="gt-chip" data-gt="filter" data-id="${key}" aria-pressed="${state.filter===key}">${label}<b>${count[key]}</b></button>`).join('')}</div></div><div class="gt-risk">${svg('alert')}<p>${active.status==='已归档'?'活动已归档，任务计划以只读方式保留。':state.role==='executor'?'你可以更新本组织任务进度。任务安排由管理员维护。':state.role==='viewer'?'当前为只读查看，可以查看任务安排、依赖和进度。':`${count.late} 项任务已逾期，${count.conflict} 项存在前置冲突，${count.unscheduled} 项尚未排期。建议先处理会影响活动准备的任务。`}</p>${link('查看风险','filter','risk')}</div><section class="gt-workspace" aria-label="任务工作区"><div class="gt-toolbar"><label class="gt-search">${svg('search')}<input id="gt-query" placeholder="搜索任务、负责人" aria-label="搜索任务、负责人" value="${E(state.query)}"></label><select id="gt-dept" aria-label="负责部门"><option>全部部门</option>${depts.map(d=>`<option ${d===state.dept?'selected':''}>${E(d)}</option>`).join('')}</select><div class="gt-tools-right"><div class="gt-toggle" aria-label="任务视图">${[['list','列表'],['gantt','甘特图']].map(([v,n])=>`<button data-gt="view" data-id="${v}" aria-pressed="${state.view===v}">${n}</button>`).join('')}</div>${state.view==='gantt'?`<select id="gt-zoom" aria-label="时间尺度">${[['day','日视图'],['week','周视图'],['month','月视图']].map(([v,n])=>`<option value="${v}" ${state.zoom===v?'selected':''}>${n}</option>`).join('')}</select>${btn('定位今日','today')}${btn('适合全部','fit')}`:''}</div></div><div id="gt-body"></div><footer class="gt-chartfoot"><div class="gt-legend"><span><i class="done"></i>已完成</span><span><i></i>进行中</span><span><i class="late"></i>逾期 / 冲突</span><span><i class="wait"></i>待开始</span><span><i class="diamond"></i>里程碑</span></div><span id="gt-result" aria-live="polite"></span></footer></section><div class="gt-note"><span>演示基准日 ${ref()} · 活动日 ${active.date} · 点击任务查看详情${manager()?'，拖动时间条可预览改期':''}</span><div class="gt-actions">${link('设计说明','review')}<label>示例状态 <select id="gt-scenario" aria-label="示例状态"><option value="normal">正常数据</option><option value="empty">首次使用</option><option value="loading">加载中</option><option value="error">加载失败</option></select></label></div></div>`;
    $('button[data-gt=back]',root).classList.add('gt-back');$('#gt-scenario').value=state.scenario;if(state.zoom==='fit'&&$('#gt-zoom'))$('#gt-zoom').add(new Option('全览','fit',true,true));renderBody();
  }
  function empty(title,desc,actions){return `<div class="gt-empty">${svg('calendar')}<h2>${title}</h2><p>${desc}</p><div class="gt-actions" style="justify-content:center">${actions}</div></div>`;}
  function renderBody(){
    const host=$('#gt-body');if(!host)return;const list=filtered(),hasFilter=state.filter!=='all'||state.query||state.dept!=='全部部门';$('#gt-result').innerHTML=`显示 ${list.length} / ${tasks.length} 项任务${hasFilter?' · 已筛选　'+link('清除筛选','reset'):''}`;
    if(state.scenario==='loading'){host.innerHTML='<div class="gt-loading" role="status" aria-label="正在加载任务"><p>正在加载任务计划…</p><i></i><i></i><i></i></div>';return;}
    if(state.scenario==='error'){host.innerHTML=empty('任务计划加载失败','无法获取任务安排，重试会保留当前筛选条件。',btn('重新加载','retry','',true));return;}
    if(state.scenario==='empty'){host.innerHTML=empty('从第一项准备任务开始','先明确任务和负责部门，再安排日期与前后置关系。',manager()?btn('新建第一项任务','new','',true)+btn('返回示例数据','retry'):btn('返回示例数据','retry'));return;}
    if(!list.length){host.innerHTML=empty('没有符合条件的任务','试试其他任务名称，或清除当前筛选条件。',btn('清除筛选','reset'));return;}
    if(state.view==='list')host.innerHTML=listHTML(list);else host.innerHTML=chartHTML(list);
  }
  function listHTML(list){return `<div class="gt-list"><table aria-label="活动任务列表"><thead><tr>${['任务 / 负责人','负责部门','起止日期','状态','进度','前置任务'].map(n=>`<th scope="col">${n}</th>`).join('')}</tr></thead><tbody>${list.map(t=>`<tr><td><button class="gt-taskname" data-gt="task" data-id="${t.id}">${t.milestone?'◇ ':''}${E(t.name)}</button><small class="gt-mobile-meta">${E(t.dept)} / ${E(t.owner||'待分配')}</small><small class="gt-desktop-owner">${E(t.owner||'待分配')}</small></td><td data-cell="dept">${E(t.dept)}</td><td>${range(t)}</td><td>${statusHTML(t)}</td><td data-cell="progress">${t.progress}%</td><td data-cell="dependency">${predecessor(t)?'前置：'+E(predecessor(t).name):'无前置任务'}</td></tr>`).join('')}</tbody></table></div>`;}
  function chartHTML(list){
    const dated=tasks.filter(t=>t.start&&t.end),start=add(dated.map(t=>t.start).sort()[0]||ref(),-1+state.offset),end=add(dated.map(t=>t.end).sort().at(-1)||active.date,2),days=Math.max(18,num(end)-num(start)+1);
    let px=state.zoom==='day'?42:state.zoom==='week'?23:12;
    if(state.zoom==='fit')px=Math.max(15,(root.clientWidth-(root.clientWidth<700?180:310))/days);
    const width=days*px,at=s=>(num(s)-num(start))*px;
    const rows=[],rowMap=new Map();let y=65;
    const shade=Array.from({length:days},(_,i)=>{const dt=add(start,i),wd=new Date(dt+'T00:00:00Z').getUTCDay();return `${wd===0||wd===6?`<i class="gt-weekend" style="left:${i*px}px"></i>`:''}${dt===ref()?`<i class="gt-today-shade" style="left:${i*px}px"></i>`:''}`;}).join('');
    for(const dept of depts){const group=list.filter(t=>t.dept===dept);if(!group.length)continue;rows.push(`<div class="gt-gridrow gt-group"><div class="gt-label"><button data-gt="collapse" data-id="${E(dept)}" aria-expanded="${!state.collapsed.has(dept)}"><span>${state.collapsed.has(dept)?'▸':'▾'}</span>${E(dept)}<small>${group.filter(t=>t.progress===100).length} / ${group.length} 完成</small></button></div><div class="gt-lane"></div></div>`);y+=37;if(state.collapsed.has(dept))continue;
      for(const t of group){const rowHeight=matchMedia('(max-width:700px)').matches?66:58;rowMap.set(t.id,{y:y+rowHeight/2,x:t.start?at(t.start):null,end:t.end?at(t.end)+(t.milestone?px/2:px):null});y+=rowHeight;
        const left=t.start?at(t.start):0,barWidth=t.start?Math.max(px,(num(t.end)-num(t.start)+1)*px):0;
        const bar=t.start?`<button class="gt-bar ${tone(t)} ${t.milestone?'milestone':''}" style="left:${left+(t.milestone?px/2-12:3)}px;width:${t.milestone?160:Math.max(12,barWidth-6)}px;--progress:${t.progress}%" data-gt="task" data-id="${t.id}" data-draggable="${manager()&&!t.milestone}" aria-label="${E(t.name)}，${range(t)}，${status(t)}，进度 ${t.progress}%">${t.milestone?`<i class="gt-diamond"></i><span>${E(t.name)} · ${fmt(t.start)}</span>`:`<span>${barWidth>100?E(t.name):''}</span><b>${t.progress}%</b>`}</button>`:`<div class="gt-unscheduled">尚未安排日期 ${manager()?link('安排日期','edit',t.id):''}</div>`;
        rows.push(`<div class="gt-gridrow ${state.selected===t.id?'selected':''}" data-row="${t.id}"><div class="gt-label"><span class="gt-tasknum">${String(t.id).padStart(2,'0')}</span><div class="gt-labeltext"><button class="gt-taskname" data-gt="task" data-id="${t.id}">${t.milestone?'◇ ':''}${E(t.name)}</button><small><b>${E(t.owner||'待分配')}</b>${statusHTML(t)}</small></div><span class="gt-avatar" aria-hidden="true">${E(t.owner?.slice(0,1)||'待')}</span></div><div class="gt-lane" style="overflow:hidden">${shade}${bar}</div></div>`);
      }
    }
    const monthLabels=new Set(),ruler=Array.from({length:days},(_,i)=>{const dt=add(start,i),week=new Date(dt+'T00:00:00Z').getUTCDay(),month=dt.slice(0,7);let m='';if(!monthLabels.has(month)){monthLabels.add(month);m=`<span class="gt-month" style="left:${i*px+8}px">${dt.slice(0,4)} 年 ${Number(dt.slice(5,7))} 月</span>`;}return `${m}<div class="gt-date ${dt===ref()?'is-today':''} ${week===0||week===6?'weekend':''}" style="width:${px}px">${px<20&&i%3!==0?'':dt.slice(8).replace(/^0/,'')}<small>${dt===ref()?'今天':dt===active.date?'活动日':px>=30?['日','一','二','三','四','五','六'][week]:''}</small></div>`;}).join('');
    const deps=state.deps?list.filter(t=>t.dep&&rowMap.has(t.dep)&&rowMap.has(t.id)&&t.start&&predecessor(t)?.end).map(t=>{const from=rowMap.get(t.dep),to=rowMap.get(t.id);return `<path class="${conflict(t)?'conflict':''}" marker-end="url(#gt-arrow)" d="M${from.end} ${from.y} H${from.end+9} V${to.y} H${to.x}"/>`;}).join(''):'';
    const line=(date,extra='')=>at(date)>=0&&at(date)<width?`<i class="gt-marker ${extra}" style="left:calc(var(--gt-label) + ${at(date)+px/2}px)"></i>`:'';
    return `<div style="padding:8px 15px;border-bottom:1px solid var(--line);display:flex;align-items:center;justify-content:space-between;font-size:11px;color:var(--muted);gap:10px"><span>${fmt(start)} — ${fmt(add(start,days-1))}</span><div class="gt-actions">${link('‹ 前一周','prev')}${link('后一周 ›','next')}<label><input id="gt-deps" type="checkbox" ${state.deps?'checked':''}> 依赖线</label></div></div><div class="gt-chartscroll" tabindex="0" aria-label="任务时间轴，可横向滚动"><div class="gt-chart" style="--gt-day:${px}px;width:calc(var(--gt-label) + ${width}px)"><div class="gt-gridrow gt-charthead"><div class="gt-label">任务 / 负责人 / 状态</div><div class="gt-ruler">${ruler}</div></div>${rows.join('')}${line(ref())}${line(active.date,'gt-event-marker')}<svg class="gt-deps" aria-hidden="true" viewBox="0 0 ${width} ${y}" preserveAspectRatio="none" style="height:${y}px"><defs><marker id="gt-arrow" markerWidth="7" markerHeight="7" refX="6" refY="3.5" orient="auto"><path d="M0 0 7 3.5 0 7z"/></marker></defs>${deps}</svg></div></div>`;
  }
  function notify(message,undo=false){clearTimeout(noticeTimer);toast.innerHTML=`<span>${E(message)}</span>${undo?'<button data-gt="undo">撤销</button>':''}`;toast.hidden=false;noticeTimer=setTimeout(()=>toast.hidden=true,undo?10000:4500);}
  function snapshot(){undoSnapshot=JSON.stringify(tasks);undoActivity=active.id;}
  function sync(){databases.set(active.id,tasks);active.done=tasks.filter(t=>t.progress===100).length;active.total=tasks.length;}
  function commit(message){sync();render();root.querySelector(`[data-gt=task][data-id="${state.selected}"]`)?.focus({preventScroll:true});notify(message,true);}
  function setScenario(value){if(scenarioBackup){tasks=scenarioBackup;scenarioBackup=null;databases.set(active.id,tasks);undoSnapshot=null;toast.hidden=true;}if(value==='empty'){scenarioBackup=tasks;tasks=[];}state.scenario=value;render();}
  function formState(){const f=$('form',drawer);return f?JSON.stringify([...new FormData(f)]):'';}
  function openDrawer(title,subtitle,body,foot,mode='detail'){
    trigger=document.activeElement;drawer.innerHTML=`<form id="gt-form" data-mode="${mode}"><header class="gt-drawer-head"><div><h2 id="gt-drawer-title">${E(title)}</h2><p>${E(subtitle)}</p></div><button type="button" class="gt-close" data-gt="close" aria-label="关闭任务抽屉">${svg('close')}</button></header><div class="gt-dialog-body">${body}<div class="gt-error" id="gt-form-error" role="alert" hidden></div></div><div class="gt-unsaved" hidden><p>有尚未保存的修改，离开会丢失本次输入。</p><div class="gt-actions">${btn('继续编辑','continue')}${btn('放弃修改','discard')}</div></div><footer class="gt-dialog-foot">${foot}</footer></form>`;
    if(!drawer.open)drawer.showModal();initialForm=formState();
  }
  function closeDrawer(force=false){if(!force&&initialForm!==formState()){$('.gt-unsaved',drawer).hidden=false;$('[data-gt=continue]',drawer).focus();return;}drawer.close();currentTask=null;root.querySelector(`[data-gt=task][data-id="${state.selected}"]`)?.focus();if(!root.hidden&&!state.selected&&trigger?.isConnected)trigger.focus();}
  function details(id){
    const t=tasks.find(t=>t.id===Number(id));if(!t)return;currentTask=t;state.selected=t.id;renderBody();const pred=predecessor(t),next=tasks.filter(n=>n.dep===t.id);
    const body=`${statusHTML(t)}<h3 class="gt-detail-title">${E(t.name)}</h3><dl class="gt-detail-meta"><div><dt>负责部门</dt><dd>${E(t.dept)}</dd></div><div><dt>负责人</dt><dd>${E(t.owner||'待分配')}</dd></div><div><dt>${t.milestone?'里程碑日期':'起止日期'}</dt><dd>${t.milestone?fmt(t.start):range(t)}</dd></div><div><dt>任务类型</dt><dd>${t.milestone?'里程碑':'普通任务'}</dd></div></dl>${late(t)||conflict(t)?`<div class="gt-risk">${svg('alert')}<p>${late(t)?`已逾期 ${num(ref())-num(t.end)} 天。`:''}${conflict(t)?`开始日期早于前置任务完成后的次日，请调整日期。`:''}</p></div>`:''}<div class="gt-contextbox"><h3>前后置关系</h3><p>前置任务：${pred?link(E(pred.name),'task',pred.id):'无'}</p><p>后续任务：${next.length?next.map(n=>link(E(n.name),'task',n.id)).join('、'):'无'}</p></div><div class="gt-update"><label for="gt-progress">${canProgress()?'更新执行进度':'执行进度'} <output id="gt-progress-value">${t.progress}%</output></label><input id="gt-progress" name="progress" type="range" min="0" max="100" step="5" value="${t.progress}" ${canProgress()?'':'disabled'} aria-label="任务执行进度">${canProgress()?`<div class="gt-progress-presets">${[0,25,50,75,100].map(p=>`<button type="button" data-gt="preset" data-id="${p}">${p===100?'已完成':p+'%'}</button>`).join('')}</div>`:''}</div><div class="gt-contextbox"><h3>任务说明</h3><p>${E(t.description||'暂无说明')}</p></div><div class="gt-contextbox"><h3>相关活动工作</h3>${link('打开'+modules[t.module||'basic'],'related',t.module||'basic')}<p>任务进度与业务状态分别维护；标记完成不会自动审核策划书、发布推文或推进活动阶段。</p></div>`;
    openDrawer('任务详情',`任务 ${String(t.id).padStart(2,'0')} · ${E(active.title)}`,body,`${manager()?btn('编辑安排','edit',t.id):''}${btn('关闭','close')}${canProgress()?'<button type="submit" class="gt-btn primary">保存进度</button>':''}`);
  }
  function editor(id){
    if(!manager())return;const t=tasks.find(t=>t.id===Number(id))||{id:nextId,name:'',dept:state.dept==='全部部门'?'未分配部门':state.dept,owner:'',start:'',end:'',progress:0,dep:null,milestone:false,description:''};currentTask=t;
    const field=(label,name,value,type='text',extra='')=>`<label>${label}<input name="${name}" type="${type}" value="${E(value)}" ${extra}></label>`;
    openDrawer(tasks.includes(t)?'编辑任务安排':'新建任务','先明确分工，再安排日期；未排期任务也可以保存。',`<div class="gt-fields"><label class="wide">任务名称 *<input name="name" maxlength="100" required value="${E(t.name)}" placeholder="例如：核对参会名单"></label><label>负责部门<select name="dept">${depts.map(d=>`<option ${d===t.dept?'selected':''}>${E(d)}</option>`).join('')}</select></label>${field('负责人','owner',t.owner,'text','maxlength="50" placeholder="填写负责人姓名"')}<label class="gt-check wide"><input type="checkbox" name="milestone" ${t.milestone?'checked':''}>设为里程碑（单日关键节点）</label>${field('开始日期','start',t.start,'date')}${field('结束日期','end',t.end,'date',t.milestone?'disabled':'')}<small class="wide" id="gt-date-hint">${t.milestone?'里程碑只需选择开始日期。':'日期可暂不填写；填写时需同时选择开始和结束日期。'}</small><label class="wide">前置任务<select name="dep"><option value="">无前置任务</option>${tasks.filter(n=>n.id!==t.id).map(n=>`<option value="${n.id}" ${n.id===t.dep?'selected':''}>${E(n.name)}（${fmt(n.end)}）</option>`).join('')}</select></label><label class="wide">当前进度 <output id="gt-progress-value">${t.progress}%</output><input type="range" id="gt-progress" name="progress" min="0" max="100" value="${t.progress}"></label><label class="wide">任务说明<textarea name="description" maxlength="2000" placeholder="说明交付内容、准备要求等">${E(t.description)}</textarea></label></div><div class="gt-contextbox"><h3>日期与依赖</h3><p>前置任务完成后的次日开始；保存前会显示冲突与后续任务影响。不会静默调整其他任务。</p></div>`,`${tasks.includes(t)?'<button type="button" class="gt-btn gt-delete" data-gt="delete" data-id="'+t.id+'">删除任务</button>':''}${btn('取消','close')}<button type="submit" class="gt-btn primary">${tasks.includes(t)?'保存安排':'创建任务'}</button>`,'edit');
  }
  function error(message){$('#gt-form-error',drawer).textContent=message;$('#gt-form-error',drawer).hidden=false;$('#gt-form-error',drawer).scrollIntoView({block:'nearest'});}
  function validate(t){
    if(!t.name.trim())throw Error('请填写任务名称。');if(!!t.start!==!!t.end)throw Error('请同时填写开始和结束日期，或同时留空。');if(t.end<t.start)throw Error('结束日期不能早于开始日期。');
    let id=t.dep;const seen=new Set([t.id]);while(id){if(seen.has(id))throw Error('前置关系形成循环，请选择其他任务。');seen.add(id);id=tasks.find(n=>n.id===id)?.dep;}
  }
  function impactFor(candidate){
    const copies=tasks.map(t=>({...t})),target=copies.find(t=>t.id===candidate.id);if(target)Object.assign(target,candidate);else copies.push({...candidate});
    const impacts=[],queue=[candidate.id],visited=new Set();
    while(queue.length){const id=queue.shift();if(visited.has(id))continue;visited.add(id);const parent=copies.find(t=>t.id===id);for(const t of copies.filter(t=>t.dep===id)){if(t.start&&parent.end&&t.start<=parent.end){const before={...t},days=num(t.end)-num(t.start);t.start=add(parent.end,1);t.end=add(t.start,days);impacts.push({before,after:{...t}});}queue.push(t.id);}}
    return impacts;
  }
  function showConfirm(title,body,foot){confirm.innerHTML=`<form id="gt-confirm-form"><header class="gt-drawer-head"><h2 id="gt-confirm-title">${E(title)}</h2><button type="button" class="gt-close" data-gt="cancel-confirm" aria-label="关闭确认">${svg('close')}</button></header><div class="gt-dialog-body">${body}</div><footer class="gt-dialog-foot">${btn('取消','cancel-confirm')}${foot}</footer></form>`;confirm.showModal();}
  function propose(candidate,always=false){
    validate(candidate);const impacts=impactFor(candidate),parent=tasks.find(t=>t.id===candidate.dep),ownConflict=candidate.start&&parent?.end&&candidate.start<=parent.end;
    if(!impacts.length&&!ownConflict&&!always){apply(candidate,[]);return;}
    pendingChange={candidate,impacts};
    const old=tasks.find(t=>t.id===candidate.id);
    showConfirm('确认任务安排',`<p style="font-size:13px"><strong>${E(candidate.name)}</strong></p><div class="gt-impact-row"><small>当前任务</small><p>${old?range(old)+' → ':''}${range(candidate)}</p></div>${ownConflict?`<div class="gt-error">与前置任务「${E(parent.name)}」存在日期冲突。仅保存当前安排会保留风险提示。</div>`:''}${impacts.length?`<h3 class="gt-form-section">${impacts.length} 项后续任务需要顺延</h3>${impacts.map(({before,after})=>`<div class="gt-impact-row"><strong>${E(before.name)}${before.progress===100?'（已完成）':''}</strong><small>${range(before)} → ${range(after)}</small></div>`).join('')}<label class="gt-radio"><input type="radio" name="scope" value="only" checked><span>仅保存当前任务<small>保留后续任务日期，继续显示冲突提醒。</small></span></label><label class="gt-radio"><input type="radio" name="scope" value="cascade"><span>同时顺延 ${impacts.length} 项后续任务<small>保持各任务原工期；${impacts.some(i=>i.before.progress===100)?'包含已完成任务，请核实历史日期。':'不修改后续任务的完成进度。'}</small></span></label>`:'<p style="font-size:12px;color:var(--muted);margin-top:15px">没有需要自动顺延的后续任务。</p>'}${impacts.some(i=>i.after.end>active.date)?'<p style="font-size:12px;color:var(--warning);margin-top:14px">顺延将使部分任务晚于活动日，请确认是否影响现场准备。</p>':''}`,`<button type="submit" class="gt-btn primary">确认保存</button>`);
  }
  function apply(candidate,impacts){snapshot();const old=tasks.find(t=>t.id===candidate.id);if(old)Object.assign(old,candidate);else{tasks.push({...candidate});nextId++;}impacts.forEach(({after})=>Object.assign(tasks.find(t=>t.id===after.id),after));confirm.close();closeDrawer(true);if(state.scenario==='empty')state.scenario='normal';commit(impacts.length?`已保存任务并顺延 ${impacts.length} 项后续任务`:'任务已保存');}
  function review(){showConfirm('甘特图设计审查',`<div class="gt-review"><p>本次为交互原型。数据只保存在浏览器内存，刷新恢复，不连接业务接口。</p><h3>建议按这条路线体验</h3><ol><li>点“返回活动详情”，检查任务摘要是否能帮助发现风险，再进入任务计划。</li><li>切换“逾期 / 前置冲突 / 未排期”，尝试部门筛选和任务搜索。</li><li>点击“核对参会名单”，查看前后置关系，更新进度或进入签到页面。</li><li>编辑“场地与设备确认”的截止日期至活动日前一天，检查改期影响；也可拖动时间条。</li><li>新建无日期任务，检查未排期入口；切换执行成员和只读身份。</li><li>切换手机尺寸、明暗主题，以及加载、空和失败状态。</li></ol><h3>需要你重点决定</h3><p>部门分组是否符合工作习惯；是否允许前置结束当天交接；改期时的影响预览与保存范围是否清楚。</p><h3>原型边界</h3><p>批量顺延、循环依赖防护和撤销需要正式后端补充一致性支持。示例业务入口按任务内容建议，任务完成不自动改变活动阶段和业务审核结果。</p><p>日期尺中的“今天”按界面标出的演示基准日计算。</p></div>`,btn('开始审查','cancel-confirm','',true));}
  document.addEventListener('click',e=>{
    const el=e.target.closest('[data-gt]');if(!el)return;e.preventDefault();const action=el.dataset.gt,id=el.dataset.id;
    if(action==='open'||action==='summary-task'){const a=window.ActivityPrototype.get(Number(action==='open'?id:el.dataset.activity));if(a){window.ActivityPrototype.open(a.id,'gantt');if(action==='summary-task')details(Number(id));}return;}
    if(!active)return;
    if(action==='back'){navigate();return;}if(action==='module'){navigate(id);return;}if(action==='related'){if(initialForm!==formState()){closeDrawer();return;}closeDrawer(true);navigate(id);return;}
    if(action==='home')return;
    if(action==='filter'){state.filter=id;render();return;}if(action==='reset'){Object.assign(state,{query:'',dept:'全部部门',filter:'all'});render();return;}if(action==='view'){state.view=id;render();return;}
    if(action==='prev'||action==='next'){state.offset+=action==='prev'?-7:7;renderBody();return;}
    if(action==='today'){const pos=num(ref())-num(tasks.filter(t=>t.start).map(t=>t.start).sort()[0]||ref())+1;state.offset=0;renderBody();const sc=$('.gt-chartscroll',root),px=parseFloat($('.gt-chart',root)?.style.getPropertyValue('--gt-day')||42);if(sc)sc.scrollLeft=Math.max(0,pos*px-sc.clientWidth/2+100);return;}
    if(action==='fit'){state.zoom='fit';state.offset=0;state.collapsed.clear();render();return;}
    if(action==='collapse'){state.collapsed.has(id)?state.collapsed.delete(id):state.collapsed.add(id);renderBody();root.querySelector(`[data-gt=collapse][data-id="${CSS.escape(id)}"]`)?.focus();return;}
    if(action==='task'){if(drawer.open&&initialForm!==formState()){closeDrawer();return;}details(id);return;}if(action==='new'){editor();return;}if(action==='edit'){if(drawer.open&&initialForm!==formState()){closeDrawer();return;}editor(id);return;}
    if(action==='close'){closeDrawer();return;}if(action==='discard'){closeDrawer(true);return;}if(action==='continue'){$('.gt-unsaved',drawer).hidden=true;$('input,textarea,button',drawer).focus();return;}
    if(action==='cancel-confirm'){confirm.close();return;}
    if(action==='preset'){const input=$('#gt-progress',drawer);input.value=id;$('#gt-progress-value',drawer).textContent=id+'%';return;}
    if(action==='retry'){setScenario('normal');return;}
    if(action==='undo'){if(undoSnapshot&&undoActivity===active.id){tasks=JSON.parse(undoSnapshot);undoSnapshot=null;sync();render();notify('已撤销上次任务修改');}else notify('请返回原活动撤销修改');return;}
    if(action==='review'){review();if(root.classList.contains('gt-embedded'))$('.gt-review ol li',confirm).textContent='从“基本信息”查看任务摘要，再切换到前方蓝色“任务计划”标签，检查活动上下文是否清楚。';return;}
    if(action==='delete'){const t=tasks.find(t=>t.id===Number(id)),children=tasks.filter(n=>n.dep===t.id);if(children.length){error(`无法删除：${children.map(n=>n.name).join('、')} 仍依赖此任务，请先编辑后续任务解除前置关系。`);return;}pendingChange={deleteId:t.id};showConfirm('删除任务',`<p style="font-size:13px">确认删除「${E(t.name)}」？本次删除可通过底部提示撤销。</p>`,`<button type="submit" class="gt-btn" style="color:var(--danger)">删除任务</button>`);return;}
  });
  document.addEventListener('input',e=>{if(e.target.id==='gt-query'){state.query=e.target.value;renderBody();}if(e.target.id==='gt-progress')$('#gt-progress-value',drawer).textContent=e.target.value+'%';});
  document.addEventListener('change',e=>{
    if(e.target.id==='gt-role'){state.role=e.target.value;render();}if(e.target.id==='gt-dept'){state.dept=e.target.value;renderBody();}if(e.target.id==='gt-zoom'){state.zoom=e.target.value;renderBody();}if(e.target.id==='gt-deps'){state.deps=e.target.checked;renderBody();}if(e.target.id==='gt-scenario')setScenario(e.target.value);
    if(e.target.name==='milestone'&&drawer.contains(e.target)){const end=$('[name=end]',drawer),start=$('[name=start]',drawer);end.disabled=e.target.checked;if(e.target.checked)end.value=start.value;$('#gt-date-hint').textContent=e.target.checked?'里程碑只需选择开始日期。':'日期可暂不填写；填写时需同时选择开始和结束日期。';}
  });
  drawer.addEventListener('cancel',e=>{e.preventDefault();closeDrawer();});
  drawer.addEventListener('submit',e=>{e.preventDefault();if(!currentTask)return;try{const f=new FormData(e.target);if(e.target.dataset.mode==='detail'){if(!canProgress())return;snapshot();currentTask.progress=Number(f.get('progress'));closeDrawer(true);commit('执行进度已保存');}else{if(!manager())return;const milestone=f.has('milestone'),candidate={...currentTask,name:String(f.get('name')).trim(),dept:String(f.get('dept')),owner:String(f.get('owner')).trim(),start:String(f.get('start')),end:milestone?String(f.get('start')):String(f.get('end')),dep:f.get('dep')?Number(f.get('dep')):null,progress:Number(f.get('progress')),description:String(f.get('description')),milestone};propose(candidate);}}catch(err){error(err.message);}});
  confirm.addEventListener('submit',e=>{e.preventDefault();if(!pendingChange)return;if(pendingChange.deleteId){snapshot();tasks=tasks.filter(t=>t.id!==pendingChange.deleteId);confirm.close();closeDrawer(true);commit('任务已删除');}else apply(pendingChange.candidate,new FormData(e.target).get('scope')==='cascade'?pendingChange.impacts:[]);pendingChange=null;});
  let drag=null,suppressClick=false;
  root.addEventListener('pointerdown',e=>{const bar=e.target.closest('[data-draggable=true]');if(!bar||e.button!==0)return;const px=parseFloat($('.gt-chart',root).style.getPropertyValue('--gt-day'));drag={bar,x:e.clientX,px,id:Number(bar.dataset.id),left:parseFloat(bar.style.left),delta:0};bar.setPointerCapture(e.pointerId);});
  root.addEventListener('pointermove',e=>{if(!drag)return;drag.delta=Math.round((e.clientX-drag.x)/drag.px);drag.bar.style.transform=`translateX(${drag.delta*drag.px}px)`;drag.bar.style.cursor='grabbing';});
  root.addEventListener('pointerup',()=>{if(!drag)return;const d=drag;drag=null;d.bar.style.transform='';d.bar.style.cursor='';if(!d.delta)return;suppressClick=true;setTimeout(()=>suppressClick=false,300);const t=tasks.find(t=>t.id===d.id);propose({...t,start:add(t.start,d.delta),end:add(t.end,d.delta)},true);});
  root.addEventListener('pointercancel',()=>{if(drag){drag.bar.style.transform='';drag=null;}});
  root.addEventListener('click',e=>{if(suppressClick){e.preventDefault();e.stopImmediatePropagation();}},true);
  window.ActivityGantt={summary,open,mount,unmount};
  document.addEventListener('DOMContentLoaded',()=>{const q=new URLSearchParams(location.search);if(q.get('view')==='gantt'||!q.has('view')&&document.body.dataset.entry==='gantt'){const a=window.ActivityPrototype.get(Number(q.get('activity')||1));if(a){if(q.get('view')==='gantt')open(a);else window.ActivityPrototype.open(a.id,'gantt');}}});
})();
