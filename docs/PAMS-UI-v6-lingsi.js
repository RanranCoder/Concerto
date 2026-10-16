/* V6 灵思交互原型。所有状态仅保存在内存，无模型或业务 API 调用。 */
(() => {
  'use strict';
  const root = document.getElementById('lingsi');
  if (!root) return;
  const $ = (s, at = root) => at.querySelector(s);
  const $$ = (s, at = root) => [...at.querySelectorAll(s)];
  const E = esc, I = icon;
  const avatar = '<img class="ls-avatar" src="../pams-web/public/lingsi/ranran-avatar-sm.jpg" alt="灵思头像">';
  const modeLabels = {standard:'标准模式',readonly:'只读模式',kb:'资料问答'};
  const statusLabels = {pending:'待确认',done:'已完成',running:'进行中',input:'待补充',stopped:'已停止',error:'需重试',canceled:'已取消'};
  const modeNotes = {standard:'可查询、起草和办理事项，改动数据前由你确认',readonly:'只查询业务数据，不执行写入',kb:'仅检索已有资料，回答附带出处'};
  const scenes = {
    plan:{title:'准备培训班策划初稿',category:'活动筹备',icon:'calendar',tone:'',mode:'standard',action:'保存策划初稿',result:'策划初稿',count:'6 个章节',prompt:'参考上一期培训班材料，整理第四十期培训班的策划初稿和准备事项。',desc:'参考历史策划，准备初稿与会务清单',hasWrite:true},
    schedule:{title:'安排培训班活动值班',category:'排班考勤',icon:'clock',tone:'duty',mode:'standard',action:'保存排班草稿',result:'排班草稿',count:'3 个班次',prompt:'根据本部门无课表，为第四十期培训班安排活动值班，先给我看排班清单。',desc:'结合无课表匹配人员，列明冲突与空缺',hasWrite:true},
    article:{title:'起草培训班新闻稿',category:'内容宣传',icon:'file',tone:'document',mode:'standard',action:'保存新闻稿草稿',result:'新闻稿草稿',count:'1 篇草稿',prompt:'根据第四十期培训班的活动资料，帮我起草一篇新闻稿，缺少的事实先标出来。',desc:'结合活动事实起草文稿，缺失信息标注待补',hasWrite:true},
    kb:{title:'查询活动材料归档要求',category:'资料问答',icon:'book',tone:'ai',mode:'kb',prompt:'活动结束后需要归档哪些材料？请把要求和出处一起列出来。',desc:'从制度、公告和历史文书中查找依据'},
    party:{title:'查看成员发展阶段',category:'党务与成员',icon:'users',tone:'',mode:'readonly',prompt:'汇总当前可查看的党务成员发展阶段，我想先了解整体情况。',desc:'汇总可查看的发展阶段与成员情况'},
    pending:{title:'整理我的待处理事项',category:'日常事务',icon:'grid',tone:'',mode:'readonly',prompt:'帮我汇总当前待处理的活动任务，按截止时间列出准备事项。',desc:'查看活动任务与待办，明确下一步'},
    credit:{title:'核对活动素拓加分清单',category:'档案与素拓',icon:'users',tone:'document',mode:'standard',action:'批量加分',result:'加分清单',count:'3 条加分记录',prompt:'根据第四十期培训班的签到记录，生成素拓加分清单，确认后再加分。',desc:'按签到记录预览清单，确认后批量加分',hasWrite:true}
  };
  let nextId = 20, toastTimer;
  const runTimers = new Map();
  const state = {mode:'standard',collab:true,taskId:null,filter:'all',query:'',refs:[],preview:null,previewTab:'content',tasksOpen:false};
  const tasks = [
    {id:1,scene:'plan',title:scenes.plan.title,status:'pending',group:'今天',time:'09:30',prompt:scenes.plan.prompt,mode:'standard',collab:true,refs:[0],messages:[]},
    {id:2,scene:'schedule',title:scenes.schedule.title,status:'pending',group:'今天',time:'09:12',prompt:scenes.schedule.prompt,mode:'standard',collab:true,refs:[0],messages:[]},
    {id:3,scene:'kb',title:scenes.kb.title,status:'done',group:'昨天',time:'16:40',prompt:scenes.kb.prompt,mode:'kb',collab:false,refs:[],messages:[]},
    {id:4,scene:'article',title:scenes.article.title,status:'done',group:'更早',time:'10月12日',prompt:scenes.article.prompt,mode:'standard',collab:true,refs:[0],messages:[]},
    {id:5,scene:'pending',title:scenes.pending.title,status:'done',group:'更早',time:'10月12日',prompt:scenes.pending.prompt,mode:'readonly',collab:false,refs:[],messages:[]}
  ];
  const current = () => tasks.find(t => t.id === state.taskId);
  const pendingCount = () => tasks.filter(t => t.status === 'pending').length;
  function toast(message){const el = document.getElementById('ls-toast');el.textContent=message;el.hidden=false;clearTimeout(toastTimer);toastTimer=setTimeout(()=>el.hidden=true,3200)}
  function category(scene){const s=scenes[scene];return `<span class="ls-category ${s.tone}">${I(s.icon)}</span>`}
  function tag(status){return `<span class="badge ${status==='pending'||status==='input'?'warning':status==='done'?'success':status==='error'?'danger':'neutral'}">${statusLabels[status]}</span>`}
  function taskMode(task){return task?.mode||state.mode}
  function actionBtn(label,action,extra='',primary=false){return `<button type="button" class="btn ${primary?'primary':''}" data-ls-action="${action}" ${extra}>${label}</button>`}
  function renderShell(){
    root.innerHTML=`<div class="ls-workbench" id="ls-workbench">
      <aside class="ls-tasks" id="ls-tasks" aria-label="灵思任务列表">
        <div class="ls-task-brand"><span class="ls-ai-icon">${I('spark')}</span>灵思 <small>Agent</small></div>
        <button class="btn primary ls-new" data-ls-action="new">${I('plus')}新建任务</button>
        <label class="ls-search">${I('search')}<span class="sr-only">搜索任务</span><input id="ls-task-search" type="search" placeholder="搜索任务"></label>
        <div class="ls-task-filters" role="group" aria-label="任务状态筛选"><button data-ls-filter="all" aria-pressed="true">全部</button><button data-ls-filter="pending" aria-pressed="false">待确认 <span id="ls-pending-count">2</span></button><button data-ls-filter="done" aria-pressed="false">已完成</button></div>
        <div class="ls-task-list" id="ls-task-list"></div>
        <div class="ls-tasks-footer"><button data-ls-action="capabilities">${I('book')}能力与资料范围</button><small>当前组织：党建办公室<br>示例角色：文秘部部长</small></div>
      </aside>
      <div class="ls-main">
        <header class="ls-main-head"><div class="ls-main-heading"><button class="icon-btn ls-mobile-tasks" data-ls-action="tasks" aria-label="打开任务列表" aria-controls="ls-tasks" aria-expanded="false">${I('menu')}</button><div><h1 id="lingsi-title">新任务</h1><small id="ls-task-subtitle">组织工作，从一句话开始</small></div></div><div class="ls-head-actions"><span id="ls-task-status"></span><button class="btn" data-ls-action="preview-toggle" id="ls-preview-toggle" hidden aria-expanded="false" aria-controls="ls-preview">${I('file')}工作成果</button><button class="icon-btn" data-ls-action="guide" title="原型审查说明" aria-label="原型审查说明">${I('grid')}</button></div></header>
        <div class="ls-scroll" id="ls-scroll"><div class="ls-reading" id="ls-thread"></div></div>
        <div class="ls-composer-wrap"><form class="ls-composer" id="ls-composer"><div class="ls-ref-chips" id="ls-ref-chips"></div><label for="ls-input" class="sr-only">输入任务说明</label><textarea id="ls-input" placeholder="说说你要准备什么，或选择一个下方的工作入口…" maxlength="4000"></textarea><div class="ls-composer-tools"><button type="button" class="icon-btn" data-ls-action="attach" aria-label="关联活动" title="关联活动">${I('plus')}</button><label class="sr-only" for="ls-mode">运行模式</label><select id="ls-mode" class="ls-mode-select"><option value="standard">标准模式</option><option value="readonly">只读模式</option><option value="kb">资料问答</option></select><label class="ls-collab" title="标准模式下按任务需要使用专家协作"><input type="checkbox" id="ls-collab" checked>自动协作</label><span class="ls-model" title="这是交互原型，模型服务未连接"><span class="ls-dot"></span>示例演示</span><button class="ls-send" id="ls-send" type="submit" disabled aria-label="发送任务"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 19V5m-6 6 6-6 6 6"/></svg></button></div></form><div class="ls-composer-note"><span id="ls-mode-note">${modeNotes.standard}</span><span>Enter 发送 / Shift + Enter 换行</span></div><div class="ls-composer-note"><button type="button" data-ls-action="guide">V6 交互原型 · 仅修改示例数据，刷新恢复</button><span>由灵思生成的内容需核对</span></div></div>
      </div>
      <aside class="ls-preview" id="ls-preview" hidden aria-label="工作成果预览"></aside>
      <div class="ls-screen-dim" data-ls-action="close-overlay" aria-hidden="true"></div>
    </div>`;
    const dialog=document.createElement('dialog');dialog.id='ls-dialog';dialog.className='ls-dialog';dialog.setAttribute('aria-labelledby','ls-dialog-title');document.body.append(dialog);
    const notify=document.createElement('div');notify.id='ls-toast';notify.className='ls-toast';notify.hidden=true;notify.setAttribute('role','status');document.body.append(notify);
  }
  function renderTasks(){
    $('#ls-pending-count').textContent=pendingCount();
    $$('[data-ls-filter]').forEach(b=>b.setAttribute('aria-pressed',String(b.dataset.lsFilter===state.filter)));
    const list=tasks.filter(t=>(state.filter==='all'||t.status===state.filter)&&t.title.includes(state.query.trim()));
    $('#ls-task-list').innerHTML=list.length?['今天','昨天','更早'].map(group=>{const rows=list.filter(t=>t.group===group);return rows.length?`<span class="ls-group-label">${group}</span>${rows.map(t=>`<button class="ls-task" data-ls-task="${t.id}" ${t.id===state.taskId?'aria-current="page"':''}><span class="ls-task-title">${E(t.title)}</span><span class="ls-task-meta"><i class="ls-dot ${t.status}"></i>${statusLabels[t.status]}<span style="margin-left:auto">${t.time}</span></span></button>`).join('')}`:''}).join(''):`<div class="ls-task-empty">没有匹配的任务<br><button class="link" data-ls-action="reset-search">重置筛选</button></div>`;
  }
  function starterKeys(){return state.mode==='kb'?['kb']:state.mode==='readonly'?['pending','party','kb']:['plan','schedule','article','kb','party','pending']}
  function welcome(){
    return `<div class="ls-welcome"><div class="ls-greeting">${avatar}<div><h2>你好，我是灵思</h2><p>把资料、人员和安排，连成可以推进的工作。</p></div></div>
      <div class="ls-work-note"><strong>${state.mode==='standard'?'先准备，再确认，接着推进。':state.mode==='readonly'?'先看清情况，再决定下一步。':'从已有资料中，找到有出处的答案。'}</strong>${state.mode==='standard'?'你说目标，我查资料、整理方案。涉及系统数据的变更，会先列出清单。':modeNotes[state.mode]}</div>
      <div class="ls-starters-head"><h3>从一件具体的事开始</h3><span>${modeLabels[state.mode]}</span></div>
      <div class="ls-starters">${starterKeys().map(k=>`<button class="ls-starter" data-ls-seed="${k}">${category(k)}<span><strong>${scenes[k].category}</strong><small>${scenes[k].desc}</small></span><svg class="ls-arrow"><use href="#i-right"/></svg></button>`).join('')}</div>
      ${state.mode==='kb'?'<div class="ls-state-note">例如：活动结束后要归档哪些材料？找不到依据时会说明资料不足。</div>':''}
      <div class="ls-recents"><span>继续最近的任务</span><button data-ls-task="1">${I('calendar')}培训班策划 <span class="ls-dot pending"></span></button><button data-ls-task="2">${I('clock')}活动排班 <span class="ls-dot pending"></span></button></div>
    </div>`;
  }
  function process(task){
    const kb=task.scene==='kb', collab=task.collab&&task.mode==='standard'&&['plan','schedule','article'].includes(task.scene);
    return `<details class="ls-process"><summary>${I('check')}${kb?'已检索 3 份参考资料':collab?'已完成资料检索与专家协作':'已完成业务查询'}<span style="margin-left:auto">${kb?'2.6':'8.4'} 秒 / 查看过程</span></summary><div class="ls-process-list"><div class="ls-process-row">${I('check')}<span>${kb?'检索组织资料':'读取关联活动与已有资料'}<p>${kb?'匹配制度、材料清单与历史归档记录':'第四十期培训班 / 当前角色可查看的数据'}</p></span><small>完成</small></div><details class="ls-process-detail"><summary>查看查询条件与结果</summary><p>查询范围：${kb?'当前组织的归档制度与相关资料':'第四十期培训班的活动资料'}<br>查询结果：3 份参考资料（示例）。正式数据以实际工具返回为准。</p></details><details class="ls-process-detail"><summary>思考记录（示例）</summary><p>先核对资料适用范围，再整理本期需要准备的内容；没有事实依据的字段保留待补标记。</p><small>本段为静态样例，用于审查思考记录的展开位置。</small></details><div class="ls-process-row">${I('check')}<span>${collab?'整理业务方案':'汇总查询结果'}<p>${task.scene==='schedule'?'匹配无课表，检查时段重叠与人员空缺':task.scene==='plan'?'参考历史策划，保留待核对字段':'核对业务内容与引用来源'}</p></span><small>完成</small></div>${collab?'<div class="ls-team"><span>检索</span><span>'+({plan:'策划',schedule:'排班',article:'文书'}[task.scene])+'</span><span>审查</span></div>':''}<div class="ls-process-row">${I('check')}<span>${task.scene==='schedule'?'审查：保留 1 个未分配时段，待人工补齐':task.scene==='plan'?'审查：时间、主讲人与名单仍需人工核对':'已检查结果与资料来源'}<p>本过程为静态示例，可用于审查信息层次。</p></span><small>完成</small></div></div></details>`;
  }
  function artifact(task,key,label,note){return `<button class="ls-artifact-row" data-ls-preview="${key}">${category(task.scene)}<span><strong>${label}</strong><small>${note}</small></span><span>预览</span></button>`}
  function artifacts(task){
    const s=scenes[task.scene];
    if(!s.hasWrite)return '';
    return `<div class="ls-artifacts"><div class="ls-artifacts-head"><span>${task.scene==='plan'?'2 项工作成果':'1 项工作成果'}</span><span class="muted">${task.status==='done'?(task.scene==='credit'?'已完成加分':'已保存草稿'):'尚未保存'}</span></div>${artifact(task,'content',task.scene==='plan'?'培训班策划初稿':s.result,task.scene==='plan'?'6 个章节 / 3 份参考资料':task.scene==='schedule'?'3 个候选班次 / 1 个空缺':task.scene==='credit'?'3 条可加分记录 / 每人 1 分（示例）':'活动事实待核对')}${task.scene==='plan'?artifact(task,'checklist','会务准备清单','5 项准备事项 / 建议清单'):''}</div>`;
  }
  function sources(){return `<div class="ls-sources"><span>参考资料</span><button data-ls-source="0">1 上一期活动策划</button><button data-ls-source="1">2 活动材料归档指引</button><button data-ls-source="2">3 培训班会务清单</button></div>`}
  function resultBody(task){
    switch(task.scene){
      case 'plan':return `<p>已参考上一期材料整理策划初稿，并附上会务准备清单。你可以先在右侧核对内容，再决定是否保存。</p><h3>需要你先核对的内容</h3><ul><li>培训安排、主讲人与参训范围。</li><li>名单、签到方式以及现场负责人。</li><li>策划书中的待补字段已标注，不会替你填写未知事实。</li></ul>${artifacts(task)}`;
      case 'schedule':return `<p>已根据无课表示例匹配出 <strong>3 个可保存班次</strong>。另有 1 个时段没有足够的空闲人员，需要你补齐。</p><div style="overflow:auto"><table class="ls-result-table"><thead><tr><th>日期与时段</th><th>值班人员</th><th>核对情况</th></tr></thead><tbody><tr><td>10月15日 14:00–15:40</td><td>林同学、陈同学</td><td>无冲突</td></tr><tr><td>10月15日 16:00–17:40</td><td>周同学、王同学</td><td>无冲突</td></tr><tr><td>10月16日 14:00–15:40</td><td>李同学、赵同学</td><td>无冲突</td></tr></tbody></table></div>${artifacts(task)}`;
      case 'article':return `<p>新闻稿已按“活动概况、现场内容、活动总结”整理成草稿。主讲人、实际参与人数和现场照片仍需补充。</p><p>预览中的待补字段已明确标注，保存后仍是草稿，可继续到内容宣传中核对与编辑。</p>${artifacts(task)}`;
      case 'kb':return `<p>根据当前可检索的归档指引示例，活动结束后应整理以下材料：</p><ol><li><strong>策划与审批材料</strong>：策划书、活动议程和审核记录。</li><li><strong>参与与执行记录</strong>：参会名单、签到记录和现场材料。</li><li><strong>总结与宣传材料</strong>：活动总结、新闻稿及相关图片。</li></ol><p>建议按活动归集，核对名称、版本与负责人。具体要求请打开资料原文复核。</p>${sources()}<div class="ls-state-note">这是归档流程的示例回答，引用材料为原型样例。</div>`;
      case 'party':return `<p>当前可查看的示例成员共有 4 人，发展阶段分布如下。</p><table class="ls-result-table"><thead><tr><th>发展阶段</th><th>人数</th><th>说明</th></tr></thead><tbody><tr><td>入党积极分子</td><td>1</td><td>持续培养</td></tr><tr><td>重点发展对象</td><td>1</td><td>材料核对</td></tr><tr><td>预备党员</td><td>1</td><td>考察跟进</td></tr><tr><td>正式党员</td><td>1</td><td>台账维护</td></tr></tbody></table><div class="ls-state-note">当前展示汇总情况。查看具体成员时，按现有党务台账权限进入对应页面。</div>`;
      case 'pending':return `<p>整理了 3 项待推进的示例任务，按截止时间排列。</p><table class="ls-result-table"><thead><tr><th>准备事项</th><th>截止时间</th><th>下一步</th></tr></thead><tbody><tr><td>核对参训名单</td><td>10月14日 18:00</td><td><button class="link" data-detail="0" data-detail-tab="signin">查看名单</button></td></tr><tr><td>完善培训班策划</td><td>10月15日 12:00</td><td><button class="link" data-ls-task="1">继续策划</button></td></tr><tr><td>整理活动新闻素材</td><td>10月16日 17:00</td><td><button class="link" data-detail="0" data-detail-tab="materials">查看材料</button></td></tr></tbody></table><p>以上是活动任务清单；是否需要审批请到相应业务页核对。</p>`;
      case 'credit':return `<p>已按签到记录整理 3 条可加分记录，另有 1 条未签到记录跳过。核对项目、分值和名单后，再确认批量加分。</p>${artifacts(task)}<div class="ls-state-note">加分项目：活动参与 / 每人 1 分（示例）。已存在的活动参与记录将跳过，避免重复加分。</div>`;
      default:return '';
    }
  }
  function skipped(task){return task.scene==='schedule'?'<div class="ls-skip"><strong>未纳入本次保存：1 个时段</strong><br>10月16日 16:00–17:40：空闲人员不足，尚缺 2 人。请调整时段或补选人员。</div>':task.scene==='credit'?'<div class="ls-skip"><strong>已跳过：1 条记录</strong><br>王同学：未签到，未纳入本次加分预览。</div>':''}
  function confirm(task){
    const s=scenes[task.scene];
    if(!s.hasWrite)return '';
    if(task.status==='done')return `<div class="ls-receipt">${I('check')}<div><strong>${task.scene==='credit'?'已为 3 人加分（示例）':s.result+'已保存（示例）'}</strong><small>${s.count} / 关联第四十期培训班。${task.scene==='schedule'?'3 个班次仍为草稿，未发布。':task.scene==='plan'?'会务清单仅供参考，未生成活动任务。':task.scene==='credit'?'每人 1 分，可到素拓台账核对。':'可继续核对内容。'}</small></div></div>${skipped(task)}`;
    if(task.status==='canceled')return `<div class="ls-state-note">已取消本次${s.action}，未产生示例写入。草稿仍可预览。<br>${actionBtn('重新核对清单','reopen')}</div>${skipped(task)}`;
    if(task.status!=='pending')return '';
    return `<section class="ls-confirm" aria-label="待确认变更"><div class="ls-confirm-head"><span>${task.scene==='credit'?'加分前请核对清单':'保存前请核对变更'}</span>${tag('pending')}</div><div class="ls-confirm-body"><p>${s.action}，共 <strong>${s.count}</strong>。${task.scene==='plan'?'会务清单不会自动转为活动任务。':task.scene==='schedule'?'仅保存草稿，发布需到活动排班中操作。':task.scene==='credit'?'确认后新增素拓台账记录，每人 1 分（示例）。':'保存为草稿，不会发布。'}</p><div class="ls-change-row"><span>关联活动</span><b>第四十期培训班</b></div><div class="ls-change-row"><span>本次操作</span><b>${s.action} / ${s.count}</b></div><div class="ls-change-row"><span>${task.scene==='plan'?'写入范围':task.scene==='schedule'?'匹配依据':'数据来源'}</span><b>${task.scene==='plan'?'策划书章节，不改活动基本信息':task.scene==='schedule'?'文秘部无课表，候选匹配仅为示例':'活动资料与执行记录'}</b></div>${skipped(task)}<div class="ls-confirm-actions">${actionBtn('确认'+s.action,'confirm',state.mode!=='standard'?'disabled':'',true)}${actionBtn('取消本次操作','cancel')}<span>${state.mode!=='standard'?'切换标准模式后可确认':'本页仅演示，不写入系统'}</span></div></div></section>`;
  }
  function thread(task){
    const refs=(task.refs||[]).map(id=>activities.find(a=>a.id===id)).filter(Boolean);
    const user=`<div class="ls-user"><div class="ls-user-meta"><span>你 / ${E(task.time)}</span><span class="ls-inline-actions"><button data-ls-action="edit">编辑并重发</button></span></div>${E(task.prompt)}${refs.map(a=>`<div class="ls-associated">${I('calendar')}${E(a.title)}</div>`).join('')}</div>`;
    const head=`<div class="ls-answer-head">${avatar}灵思<small>${modeLabels[taskMode(task)]} / 示例结果</small></div>`;
    let body;
    if(task.status==='input')body=`<section class="ls-confirm"><div class="ls-confirm-head"><span>还需要补充排班条件</span>${tag('input')}</div><form class="ls-confirm-body ls-input-form" id="ls-input-form"><p>先补齐日期、时段和部门，再生成排班清单供你核对。</p><div class="ls-form-grid"><label>开始日期<input type="date" name="startDate" value="2026-10-15" required></label><label>结束日期<input type="date" name="endDate" value="2026-10-16" required></label><label>开始时间<input type="time" name="startTime" value="14:00" required></label><label>结束时间<input type="time" name="endTime" value="15:40" required></label><label>参与部门<select name="department"><option>文秘部</option><option>组织部</option><option>新媒体中心</option><option>青年科技部</option></select></label><label>每时段人数<input type="number" name="people" min="1" max="10" value="2" required></label></div><p id="ls-form-error" class="ls-form-error" role="alert"></p><div class="ls-confirm-actions"><button type="submit" class="btn primary">补充并继续</button>${actionBtn('取消补充','cancel-input')}<span>当前表单为示例，结果使用固定样例</span></div></form></section>`;
    else if(task.status==='running')body=`<div class="ls-busy-line"><span class="ls-loader"></span>正在读取关联资料、准备示例结果…</div><div class="ls-state-note">可点击输入区的停止按钮结束演示。真实执行过程将在这里逐步更新。</div>`;
    else if(task.status==='stopped')body=`<div class="ls-state-note">任务已停止，未执行任何写入。${actionBtn('继续这项任务','retry')}</div>`;
    else if(task.status==='error')body=`<div class="ls-state-note error"><strong>资料读取失败</strong><br>示例模拟连接中断，本次未执行写入。可重试这项任务。<br>${actionBtn('重新尝试','retry')}</div>`;
    else body=`${process(task)}<div class="ls-answer">${resultBody(task)}${confirm(task)}</div><div class="ls-answer-foot"><span>由灵思生成 / 内容与事实需核对</span><button data-ls-action="copy">复制结果</button></div>`;
    return user+head+body+(task.messages||[]).map(message=>`<div class="ls-user">${E(message)}</div><div class="ls-state-note">补充要求已保留在本次示例任务中。原型未连接模型，预览继续展示固定样例；正式实现时由灵思根据补充内容重新生成结果。</div>`).join('');
  }
  function render(){
    const task=current();
    $('#lingsi-title').textContent=task?task.title:'新任务';
    $('#ls-task-subtitle').textContent=task?`${scenes[task.scene].category} / ${task.refs?.length?'已关联活动':'当前组织资料'}`:'组织工作，从一句话开始';
    $('#ls-task-status').innerHTML=task?(task.status==='pending'?`<button class="ls-status-jump" data-ls-action="view-confirm" title="跳到待确认清单">${tag(task.status)}</button>`:tag(task.status)):'';
    $('#ls-preview-toggle').hidden=!task||!scenes[task.scene].hasWrite||['running','input','stopped','error'].includes(task.status);
    $('#ls-preview-toggle').setAttribute('aria-expanded',String(Boolean(state.preview)));
    $('#ls-thread').innerHTML=task?thread(task):welcome();
    renderTasks();renderComposer();renderPreview();renderOverlay();
  }
  function renderComposer(){
    $('#ls-mode').value=state.mode;
    $('#ls-collab').checked=state.mode==='standard'&&state.collab;
    $('#ls-collab').disabled=state.mode!=='standard';
    $('#ls-mode-note').textContent=modeNotes[state.mode];
    $('#ls-input').placeholder=state.mode==='kb'?'问一个资料问题，例如：活动结束后要归档哪些材料？':current()?'补充要求，或继续询问这项工作…':'说说你要准备什么，或从上方选择一个工作入口…';
    $('#ls-ref-chips').innerHTML=state.refs.map(id=>`<span class="ls-ref-chip">${I('calendar')}<span>${E(activities.find(a=>a.id===id)?.title||'活动')}</span><button type="button" data-ls-remove-ref="${id}" aria-label="移除关联活动">${I('close')}</button></span>`).join('');
    const busy=current()?.status==='running';
    $('#ls-send').disabled=!busy&&!$('#ls-input').value.trim();
    $('#ls-send').setAttribute('aria-label',busy?'停止任务':'发送任务');
    $('#ls-send').innerHTML=busy?'<svg viewBox="0 0 24 24" aria-hidden="true"><rect x="6" y="6" width="12" height="12" rx="1"/></svg>':'<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 19V5m-6 6 6-6 6 6"/></svg>';
    $('#ls-mode').disabled=busy;$('#ls-collab').disabled=busy||state.mode!=='standard';
  }
  function renderOverlay(){
    $('#ls-workbench').classList.toggle('has-preview',Boolean(state.preview));
    $('#ls-workbench').classList.toggle('tasks-open',state.tasksOpen);
    $('.ls-mobile-tasks').setAttribute('aria-expanded',String(state.tasksOpen));
  }
  const sourceData=[
    {name:'第三十九期培训班策划书',type:'历史策划 / 示例资料',date:'2026年9月',quote:'活动筹备按组织协调、现场会务、资料归集三部分安排。',note:'本次用于参考结构与准备事项。往期时间、主讲人与人数不能直接沿用。'},
    {name:'活动材料归档指引',type:'制度文档 / 示例资料',date:'2026年9月',quote:'活动材料应按活动归集，包含策划、执行记录与总结材料，并注明责任部门。',note:'本次用于展示回答附带出处的方式。此段为原型文案，不代表真实制度。'},
    {name:'培训班会务准备清单',type:'材料模板 / 示例资料',date:'2026年10月',quote:'会前核对场地、设备、名单与签到安排；会后整理新闻、照片和活动总结。',note:'本次用于生成会务建议清单。建议事项没有自动写入活动任务。'}
  ];
  function documentContent(task){
    switch(task.scene){
      case 'plan':return `<h2>第四十期培训班<br>活动策划初稿</h2><p class="ls-paper-sub">文秘部 / 待核对稿</p><h3>一、活动目的</h3><p>结合本期培训目标，组织专题学习与交流，做好培训过程记录与会务服务。</p><h3>二、活动信息</h3><p>活动名称：第四十期培训班<br>时间：<span class="ls-placeholder">待核对培训安排</span><br>地点：学术报告厅<br>主讲人：<span class="ls-placeholder">待补充</span><br>参训范围：<span class="ls-placeholder">以确认名单为准</span></p><h3>三、活动流程</h3><ol><li>现场签到与入场引导。</li><li>主持人介绍培训安排。</li><li>专题授课与交流。</li><li>现场总结与离场安排。</li></ol><h3>四、部门分工</h3><p>组织部核对名单，文秘部负责会务与资料，新媒体中心整理宣传素材。</p><h3>五、准备事项</h3><p>提前核对场地、投影与音响，准备签到名单、主持稿和现场联络方式。</p><h3>六、活动总结与归档</h3><p>汇总签到、现场照片和新闻材料，按活动归集并检查版本与负责人。</p>`;
      case 'schedule':return `<h2>培训班活动值班安排</h2><p class="ls-paper-sub">文秘部 / 排班草稿</p>${resultBody({...task,scene:'schedule'}).split('<div class="ls-artifacts">')[0]}<h3>未分配时段</h3>${skipped(task)}<h3>保存后的下一步</h3><p>在活动排班页面补齐空缺、复核人员，再发布安排。</p>`;
      case 'article':return `<h2>第四十期培训班<br>新闻稿初稿</h2><p class="ls-paper-sub">活动报道 / 未发布</p><p>为做好本期培训工作，信息与智能工程学院组织开展第四十期培训班专题学习。</p><p>活动由 <span class="ls-placeholder">待补充：主讲人</span> 主讲，围绕 <span class="ls-placeholder">待补充：授课主题</span> 进行讲解与交流，参训同学结合学习内容展开讨论。</p><p>本次培训共 <span class="ls-placeholder">待核对：实际参与人数</span> 人参加。组织部、文秘部与新媒体中心配合完成名单核对、现场会务和素材整理。</p><p><span class="ls-placeholder">待补充：现场亮点与采访内容</span></p><h3>发布前核对</h3><ul><li>核对活动日期、地点和人物称谓。</li><li>补充现场照片及图片说明。</li><li>按内容宣传流程提交审核。</li></ul>`;
      case 'credit':return `<h2>活动素拓加分清单</h2><p class="ls-paper-sub">第四十期培训班 / ${task.status==='done'?'已完成示例加分':'待确认'}</p><table class="ls-result-table"><thead><tr><th>成员</th><th>签到情况</th><th>加分值</th></tr></thead><tbody><tr><td>林同学</td><td>已签到</td><td>1 分</td></tr><tr><td>陈同学</td><td>已签到</td><td>1 分</td></tr><tr><td>周同学</td><td>已签到</td><td>1 分</td></tr></tbody></table>${skipped(task)}<h3>核对事项</h3><p>加分项目为“活动参与”，分值为示例。实际执行前需确认适用标准和参与名单，重复记录将自动跳过。</p>`;
      default:return '';
    }
  }
  function checklist(){return `<h2>会务准备清单</h2><p class="ls-paper-sub">工作建议 / 未写入活动任务</p><ol><li><strong>组织部：</strong>核对参训名单和分组，确认参加范围。</li><li><strong>文秘部：</strong>确认场地、设备、主持稿和现场联络人。</li><li><strong>会务人员：</strong>准备签到点位、纸质备用名单和引导标识。</li><li><strong>新媒体中心：</strong>确认拍摄安排与新闻素材收集方式。</li><li><strong>活动负责人：</strong>会后整理签到、新闻与总结，核对归档材料。</li></ol><p>各项负责人和截止时间需要根据本期安排另行确认。</p>`}
  function referenceContent(index){const indexes=index===undefined?[0,1,2]:[index];return indexes.map(i=>{const s=sourceData[i];return `<section class="ls-reference"><h3>${i+1}. ${s.name}</h3><small>${s.type} / ${s.date}</small><blockquote>${s.quote}</blockquote><p>${s.note}</p></section>`}).join('')}
  function renderPreview(){
    const panel=$('#ls-preview'), task=current();
    panel.hidden=!state.preview;
    if(!state.preview)return;
    const isSource=state.preview.type==='source';
    let content,title;
    if(isSource){title='资料来源';content=referenceContent(state.preview.index)}
    else if(!task){state.preview=null;panel.hidden=true;return}
    else {
      title=state.preview.key==='checklist'?'会务准备清单':scenes[task.scene].result;
      content=state.previewTab==='sources'?referenceContent():state.previewTab==='changes'?`<div class="ls-preview-notice">本面板用于核对本次变更，确认入口保留在对话中。</div>${confirm(task).replace(/<div class="ls-confirm-actions">[\s\S]*?<\/div>/g,'').replace(/<button[\s\S]*?<\/button>/g,'')}`:`<div class="ls-document-meta"><span>第四十期培训班</span><span>${task.status==='done'?'已保存示例':'待核对稿'}</span></div>${task.scene==='plan'||task.scene==='article'?'<div class="ls-preview-notice">黄色字段仍需补充或核对，请勿作为已确认事实。</div>':''}<article class="ls-document">${state.preview.key==='checklist'?checklist():documentContent(task)}</article>`;
    }
    panel.innerHTML=`<header class="ls-preview-head"><div><h2>${title}</h2><small>${isSource?'引用片段与使用说明':'与对话对照核对'}</small></div><button class="icon-btn" data-ls-action="close-preview" aria-label="关闭工作成果预览">${I('close')}</button></header>${!isSource?`<div class="ls-preview-tabs" role="tablist" aria-label="成果内容"><button id="ls-tab-content" data-ls-tab="content" role="tab" aria-selected="${state.previewTab==='content'}" aria-controls="ls-preview-content" tabindex="${state.previewTab==='content'?0:-1}">内容预览</button><button id="ls-tab-changes" data-ls-tab="changes" role="tab" aria-selected="${state.previewTab==='changes'}" aria-controls="ls-preview-content" tabindex="${state.previewTab==='changes'?0:-1}">本次变更</button><button id="ls-tab-sources" data-ls-tab="sources" role="tab" aria-selected="${state.previewTab==='sources'}" aria-controls="ls-preview-content" tabindex="${state.previewTab==='sources'?0:-1}">参考资料</button></div>`:''}<div class="ls-preview-scroll" id="ls-preview-content" ${!isSource?`role="tabpanel" aria-labelledby="ls-tab-${state.previewTab}" tabindex="0"`:''}>${content}</div><footer class="ls-preview-foot"><span>${isSource?'示例资料 / 以真实制度为准':task.status==='done'?'已产生示例结果，真实系统未写入':'尚未写入 / 请在对话中确认'}</span>${!isSource?actionBtn('回到对话','back-to-chat'):actionBtn('返回对话','close-preview')}</footer>`;
  }
  function closePreview(){state.preview=null;renderPreview();renderOverlay();$('#ls-preview-toggle').setAttribute('aria-expanded','false')}
  function preview(key){state.tasksOpen=false;state.preview={type:'artifact',key};state.previewTab='content';renderPreview();renderOverlay();$('#ls-preview-toggle').setAttribute('aria-expanded','true');$('.ls-preview-head .icon-btn').focus({preventScroll:true})}
  function modal(title,body,foot=''){
    const dialog=document.getElementById('ls-dialog');if(dialog.open)dialog.close();
    dialog.innerHTML=`<div class="dialog-head"><h2 id="ls-dialog-title">${title}</h2><button class="icon-btn" data-ls-action="close-dialog" aria-label="关闭">${I('close')}</button></div><div class="dialog-body">${body}</div>${foot?`<div class="dialog-foot">${foot}</div>`:''}`;dialog.showModal();
  }
  function capabilities(){
    const kb=state.mode==='kb',read=state.mode==='readonly';
    const rows=kb?[['制度与公告','查找已接入知识库的组织制度与公告。'],['策划与文稿','检索历史策划书、推文和材料中的相关片段。'],['事实与名册','检索当前账号有权限查看的事实快照与成员名册。']]:[['活动筹备','查询活动概况、任务和签到统计'+(!read?'；创建活动、起草策划、生成活动任务。':'。')],['排班考勤','查询共同空闲时间、考勤记录'+(!read?'；根据无课表生成活动排班。':'。')],['内容与档案','检索材料、推文与素拓记录'+(!read?'；起草活动文稿、预览并批量加分。':'。')],['党务与成员','查询成员发展进度'+(!read?'；按权限登记成员。':'。')],['通知与待办','查询通知与待处理事项'+(!read?'；按权限发送通知、发布公告。':'。')]];
    modal('当前能力与资料范围',`<p><strong>${modeLabels[state.mode]}</strong>：${modeNotes[state.mode]}。</p>${rows.map(([name,desc])=>`<div class="ls-capability-group"><strong>${name}</strong><p>${desc}</p></div>`).join('')}<p style="margin-top:16px">${kb?'资料问答不调用业务工具。':read?'只读模式下不展示写入能力。':'标准模式按任务需要自动协作。涉及写入先展示清单，确认后执行。'} 正式系统中的能力范围以当前角色与部门权限为准。</p>${!kb&&!read?'<p>素拓先展示参与与跳过清单，确认后批量加分；排班生成后保持草稿状态，复核后发布。</p><p>'+actionBtn('查看素拓加分示例','seed-credit')+'</p>':''}`,actionBtn('知道了','close-dialog', '',true));
  }
  function guide(){modal('V6 灵思原型审查说明',`<p>本次重新设计沿用系统 V6 的明暗主题、院校蓝主操作、业务分类色和字号尺度。当前仅更新设计原型。</p><h3>建议按这个顺序审查</h3><ol><li>新任务：查看业务入口，点击任一入口会填入输入框。</li><li>最近任务：打开“准备培训班策划初稿”，展开过程、预览初稿、核对参考资料。</li><li>确认流程：在对话中确认保存，查看回执与任务状态；也可取消后重新核对。</li><li>排班示例：检查未分配时段是否始终可见。</li><li>模式切换：检查只读与资料问答的提示及能力范围。</li><li>窄屏和深色：右侧成果改为抽屉，任务列表通过按钮打开。</li></ol><h3>查看运行状态</h3><p>发送一个示例任务可看到进行中状态，并能停止。下方按钮用于审查恢复路径与信息补全。</p><div class="ls-demo-options">${actionBtn('查看待补信息','demo-input')}${actionBtn('查看失败示例','demo-error')}${actionBtn('查看资料不足','demo-empty')}</div><p style="margin-top:16px">输入任意文字不会调用模型。演示结果使用固定样例，所有确认、取消与保存仅改变本页状态，刷新恢复。排班候选人员的确认前预览为设计提案，现有工具在确认后匹配人员。</p>`,actionBtn('开始审查','close-dialog','',true))}
  function selectTask(id){const task=tasks.find(t=>t.id===Number(id));if(!task)return;state.taskId=task.id;state.mode=task.mode;state.collab=task.collab;state.refs=[];state.preview=null;state.tasksOpen=false;$('#ls-input').value='';render();$('#ls-scroll').scrollTop=0}
  function newTask(){state.taskId=null;state.refs=[];state.preview=null;state.tasksOpen=false;$('#ls-input').value='';render();$('#ls-scroll').scrollTop=0;$('#ls-input').focus()}
  function seed(key){
    const s=scenes[key];if(!s)return;
    if(current())newTask();state.mode=s.mode;$('#ls-input').value=s.prompt;renderComposer();$('#ls-thread').innerHTML=welcome();$('#ls-input').focus();
    if(['plan','schedule','article','credit'].includes(key)){state.refs=[0];renderComposer()}
  }
  function inferScene(text){if(state.mode==='kb')return 'kb';if(/排班|值班|无课/.test(text))return 'schedule';if(/新闻|推文|文稿/.test(text))return 'article';if(/素拓|加分/.test(text))return 'credit';if(/党员|发展阶段|成员/.test(text))return 'party';if(/待办|待处理|截止/.test(text))return 'pending';if(/归档|制度|资料/.test(text)&&!/策划/.test(text))return 'kb';return 'plan'}
  function send(){
    if(current()?.status==='running'){stop();return}
    const text=$('#ls-input').value.trim();if(!text)return;
    if(current()){
      const task=current();task.messages.push(text);$('#ls-input').value='';state.refs=[];render();$('#ls-scroll').scrollTop=$('#ls-scroll').scrollHeight;toast('补充要求已保留；当前原型展示固定样例');return;
    }
    let scene=inferScene(text);
    if(state.mode==='readonly'&&scenes[scene].hasWrite){
      modal('当前为只读模式',`<p>这项示例任务需要准备${scenes[scene].result}并核对写入。你可以切换为标准模式继续，或保持只读查看业务情况。</p>`,actionBtn('保持只读','close-dialog')+actionBtn('切换标准模式','switch-standard','',true));return;
    }
    const task={id:nextId++,scene,title:scenes[scene].title,status:'running',group:'今天',time:'刚刚',prompt:text,mode:state.mode,collab:state.collab,refs:[...state.refs],messages:[]};
    tasks.unshift(task);state.taskId=task.id;state.refs=[];$('#ls-input').value='';render();$('#ls-scroll').scrollTop=0;
    finishLater(task);
  }
  function finishLater(task){clearTimeout(runTimers.get(task.id));runTimers.set(task.id,setTimeout(()=>{runTimers.delete(task.id);if(task.status!=='running')return;task.status=scenes[task.scene].hasWrite?'pending':'done';if(current()?.id===task.id)render();else renderTasks();toast('示例结果已准备好')},1800))}
  function stop(){const task=current();if(task?.status!=='running')return;clearTimeout(runTimers.get(task.id));runTimers.delete(task.id);task.status='stopped';render();toast('任务已停止，未执行写入')}
  function confirmTask(){
    const task=current();if(!task||task.status!=='pending')return;
    if(state.mode!=='standard'){toast('当前模式不允许写入，请切换标准模式再核对');return}
    task.status='done';render();toast(task.scene==='credit'?'已为 3 人加分（仅示例）':scenes[task.scene].result+'已保存（仅示例）');
  }
  function routeToAgent(){showView('lingsi',false);const url=new URL(location.href);url.searchParams.set('module','lingsi');url.searchParams.delete('page');history.replaceState(null,'',url)}
  renderShell();render();
  $('#ls-task-search').addEventListener('input',e=>{state.query=e.target.value;renderTasks()});
  $('#ls-input').addEventListener('input',renderComposer);
  $('#ls-input').addEventListener('keydown',e=>{if(e.key==='Enter'&&!e.shiftKey&&!e.isComposing){e.preventDefault();send()}});
  $('#ls-composer').addEventListener('submit',e=>{e.preventDefault();send()});
  root.addEventListener('submit',e=>{
    if(e.target.id!=='ls-input-form')return;e.preventDefault();
    const values=Object.fromEntries(new FormData(e.target));
    if(values.endDate<values.startDate){$('#ls-form-error').textContent='结束日期不能早于开始日期。';return}
    if(values.endTime<=values.startTime){$('#ls-form-error').textContent='结束时间必须晚于开始时间。';return}
    const task=current();if(task?.status!=='input')return;
    task.generationInput=values;task.status='running';render();finishLater(task);
  });
  $('#ls-mode').addEventListener('change',e=>{state.mode=e.target.value;renderComposer();$('#ls-thread').innerHTML=current()?thread(current()):welcome();renderPreview();toast(modeNotes[state.mode])});
  $('#ls-collab').addEventListener('change',e=>state.collab=e.target.checked);
  document.addEventListener('click',e=>{
    const target=e.target;
    const route=target.closest('[data-view="lingsi"]');if(route)routeToAgent();
    const task=target.closest('[data-ls-task]');if(task)selectTask(task.dataset.lsTask);
    const filter=target.closest('[data-ls-filter]');if(filter){state.filter=filter.dataset.lsFilter;renderTasks()}
    const starter=target.closest('[data-ls-seed]');if(starter)seed(starter.dataset.lsSeed);
    const artifactEl=target.closest('[data-ls-preview]');if(artifactEl)preview(artifactEl.dataset.lsPreview);
    const source=target.closest('[data-ls-source]');if(source){state.tasksOpen=false;state.preview={type:'source',index:Number(source.dataset.lsSource)};renderPreview();renderOverlay();$('.ls-preview-head .icon-btn').focus({preventScroll:true})}
    const tab=target.closest('[data-ls-tab]');if(tab){state.previewTab=tab.dataset.lsTab;renderPreview();$(`#ls-tab-${state.previewTab}`).focus({preventScroll:true})}
    const remove=target.closest('[data-ls-remove-ref]');if(remove){state.refs=state.refs.filter(id=>id!==Number(remove.dataset.lsRemoveRef));renderComposer()}
    const ref=target.closest('[data-ls-add-ref]');if(ref){const id=Number(ref.dataset.lsAddRef);if(!state.refs.includes(id))state.refs.push(id);document.getElementById('ls-dialog').close();renderComposer();$('#ls-input').focus()}
    const act=target.closest('[data-ls-action]');if(!act)return;
    const taskNow=current();
    switch(act.dataset.lsAction){
      case 'new':newTask();break;
      case 'tasks':closePreview();state.tasksOpen=!state.tasksOpen;renderOverlay();if(state.tasksOpen)$('#ls-task-search').focus();break;
      case 'close-overlay':closePreview();state.tasksOpen=false;renderOverlay();break;
      case 'close-preview':closePreview();$('#ls-preview-toggle').focus();break;
      case 'preview-toggle':state.preview?closePreview():preview('content');break;
      case 'back-to-chat':closePreview();$('#ls-input').focus();break;
      case 'guide':guide();break;
      case 'capabilities':capabilities();break;
      case 'seed-credit':document.getElementById('ls-dialog').close();seed('credit');break;
      case 'close-dialog':document.getElementById('ls-dialog').close();break;
      case 'attach':modal('关联活动',`<p>选择活动作为本次提问的背景，方便灵思定位资料。原型展示当前系统中的活动样例。</p><div class="ls-picker-list">${activities.map(a=>`<button data-ls-add-ref="${a.id}">${I('calendar')}<span>${E(a.title)}<small>${E(a.department)} / ${a.date}</small></span></button>`).join('')}</div>`);break;
      case 'confirm':confirmTask();break;
      case 'view-confirm':$('#ls-thread .ls-confirm')?.scrollIntoView({behavior:'smooth',block:'center'});break;
      case 'cancel':if(taskNow?.status==='pending'){taskNow.status='canceled';render();toast('已取消本次操作，未产生示例写入')}break;
      case 'reopen':if(taskNow?.status==='canceled'){taskNow.status='pending';render()}break;
      case 'edit':if(taskNow){$('#ls-input').value=taskNow.prompt;renderComposer();$('#ls-input').focus();toast('原文已放回输入框，可编辑后发送')}break;
      case 'retry':if(taskNow&&['error','stopped'].includes(taskNow.status)){if(taskNow.needsInput&&!taskNow.generationInput){taskNow.status='input';render()}else{taskNow.status='running';render();finishLater(taskNow)}}break;
      case 'cancel-input':if(taskNow?.status==='input'){taskNow.status='stopped';render();toast('已取消补充，未执行写入')}break;
      case 'copy':if(taskNow){const text=$('.ls-answer')?.innerText||'';if(navigator.clipboard)navigator.clipboard.writeText(text).then(()=>toast('已复制结果'),()=>toast('复制不可用，请选中文字复制'));else toast('复制不可用，请选中文字复制')}break;
      case 'reset-search':state.query='';state.filter='all';$('#ls-task-search').value='';renderTasks();break;
      case 'switch-standard':state.mode='standard';document.getElementById('ls-dialog').close();renderComposer();$('#ls-input').focus();break;
      case 'demo-input':document.getElementById('ls-dialog').close();newTask();state.mode='standard';tasks.unshift({id:nextId++,scene:'schedule',title:'补充排班生成条件',status:'input',needsInput:true,group:'今天',time:'刚刚',prompt:'帮我安排第四十期培训班的活动值班。',mode:'standard',collab:true,refs:[0],messages:[]});state.taskId=tasks[0].id;render();break;
      case 'demo-error':document.getElementById('ls-dialog').close();newTask();state.mode='standard';$('#ls-input').value=scenes.plan.prompt;send();clearTimeout(runTimers.get(current().id));runTimers.delete(current().id);current().status='error';render();break;
      case 'demo-empty':document.getElementById('ls-dialog').close();newTask();state.mode='kb';render();$('#ls-thread').innerHTML=`<div class="ls-user">去年活动临时场地调整的审批记录在哪里？</div><div class="ls-answer-head">${avatar}灵思<small>资料问答 / 示例结果</small></div><div class="ls-state-note"><strong>当前资料中没有找到相关审批记录</strong><br>可以补充活动全称和时间，或先在材料库核对该记录是否已归档。<br>${actionBtn('换一个资料问题','new')}</div>`;break;
    }
  });
  root.addEventListener('keydown',e=>{
    const tab=e.target.closest('[data-ls-tab]');if(!tab)return;const keys=['content','changes','sources'];let idx=keys.indexOf(state.previewTab);if(e.key==='ArrowRight')idx=(idx+1)%3;else if(e.key==='ArrowLeft')idx=(idx+2)%3;else if(e.key==='Home')idx=0;else if(e.key==='End')idx=2;else return;e.preventDefault();state.previewTab=keys[idx];renderPreview();$(`#ls-tab-${state.previewTab}`).focus();
  });
  document.addEventListener('keydown',e=>{if(e.key==='Escape'){if(state.preview){closePreview();$('#ls-input').focus()}state.tasksOpen=false;renderOverlay()}});
  const params=new URLSearchParams(location.search);
  if(params.get('module')==='lingsi'){routeToAgent();const demo=params.get('scene');if(demo&&scenes[demo]){const found=tasks.find(t=>t.scene===demo);if(found)selectTask(found.id);else seed(demo)}}
})();
