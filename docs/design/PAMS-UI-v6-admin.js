/* V6 review prototype: knowledge, users, settings. Session-local sample data only. */
(() => {
  'use strict';
  const $ = (s, r = document) => r.querySelector(s), E = esc;
  const icons = {
    shield:'<path d="M12 3 4 6v6c0 5 8 9 8 9s8-4 8-9V6z"/><path d="m8 12 3 3 5-6"/>',
    settings:'<path d="M4 7h16M4 17h16"/><circle cx="9" cy="7" r="3" fill="var(--surface)"/><circle cx="15" cy="17" r="3" fill="var(--surface)"/>',
    refresh:'<path d="M20 7v5h-5M4 17v-5h5"/><path d="M6 7a7 7 0 0 1 12-1l2 6M4 12l2 6a7 7 0 0 0 12-1"/>',
    check:'<path d="m5 12 4 4L19 6"/>',
    search:'<circle cx="10" cy="10" r="6"/><path d="m15 15 5 5"/>',
    server:'<rect x="3" y="4" width="18" height="6" rx="2"/><rect x="3" y="14" width="18" height="6" rx="2"/><path d="M7 7h.01M7 17h.01M16 7h2M16 17h2"/>',
    chevron:'<path d="m9 5 7 7-7 7"/>',
    link:'<path d="M14 4h6v6M20 4l-9 9M10 4H5a1 1 0 0 0-1 1v14a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-5"/>',
    info:'<circle cx="12" cy="12" r="9"/><path d="M12 11v6M12 7h.01"/>',
  };
  const I = name => icons[name] ? `<svg aria-hidden="true" viewBox="0 0 24 24">${icons[name]}</svg>` : icon(name);
  const tag = (text, tone = '') => `<span class="a-tag ${tone}">${E(text)}</span>`;
  const status = (text, tone = 'green') => `<span class="a-tag ${tone}"><i class="a-dot"></i>${E(text)}</span>`;
  const b = (text, action, value = '', primary = false, attrs = '') => `<button type="button" class="btn${primary?' primary':''}" data-a="${action}" data-value="${E(value)}" ${attrs}>${text}</button>`;
  const l = (text, action, value = '', attrs = '') => `<button type="button" class="link" data-a="${action}" data-value="${E(value)}" ${attrs}>${text}</button>`;
  const options = (values, selected) => values.map(v => `<option${v === selected?' selected':''}>${E(v)}</option>`).join('');
  const info = pairs => `<dl class="a-info">${pairs.map(([k,v])=>`<div><dt>${E(k)}</dt><dd>${v}</dd></div>`).join('')}</dl>`;
  const empty = (title, description, action = '') => `<div class="a-empty">${I('search')}<h3>${E(title)}</h3><p>${E(description)}</p>${action}</div>`;
  const stats = values => `<div class="a-overview">${values.map(([label,value,note,tone])=>`<div class="a-stat ${tone||''}"><span>${E(label)}</span><strong>${E(value)}</strong><small>${E(note)}</small></div>`).join('')}</div>`;
  const title = (name, desc, actions) => `<div class="heading"><div><h1>${E(name)}</h1><p>${E(desc)}</p></div><div class="actions">${actions}</div></div>`;
  const head = (name, desc = '', action = '') => `<header class="a-panel-head"><div><h2>${E(name)}</h2>${desc?`<p>${E(desc)}</p>`:''}</div>${action}</header>`;
  const section = (name, desc, body, action = '') => `<section class="surface">${head(name,desc,action)}<div class="a-panel-body">${body}</div></section>`;
  const related = (name, desc, action, value, ic = 'link') => `<button class="a-related" data-a="${action}" data-value="${E(value)}">${I(ic)}<span><strong>${E(name)}</strong><small>${E(desc)}</small></span><span class="chevron">${I('chevron')}</span></button>`;
  const search = (id, placeholder, value) => `<label class="a-search">${I('search')}<span class="sr-only">${E(placeholder)}</span><input id="${id}" type="search" placeholder="${E(placeholder)}" value="${E(value)}" autocomplete="off"></label>`;
  const select = (id, label, values, selected) => `<label class="a-select" for="${id}">${E(label)}<select id="${id}">${options(values,selected)}</select></label>`;
  const review = key => `<div class="a-review-mark"><span>评审原型 · 所有数据与操作均为本地演示</span>${l('设计说明与能力边界','review',key)}</div>`;
  const paginate = (total, page, size, action) => {
    const pages = Math.max(1,Math.ceil(total/size));
    return `<div class="a-bottom"><span>${total?`${(page-1)*size+1}–${Math.min(page*size,total)} 条，共 ${total} 条`:'共 0 条'}</span><div class="a-pages"><button data-a="${action}" data-value="${page-1}" aria-label="上一页" ${page===1?'disabled':''}>‹</button>${Array.from({length:pages},(_,i)=>`<button data-a="${action}" data-value="${i+1}" ${i+1===page?'aria-current="page"':''}>${i+1}</button>`).join('')}<button data-a="${action}" data-value="${page+1}" aria-label="下一页" ${page===pages?'disabled':''}>›</button></div></div>`;
  };
  const sources = ['全部资料','制度与指引','活动资料','通知公告','宣传内容','材料与模板','业务快照'];
  let docs = [
    {id:1,title:'中国共产党发展党员工作细则',source:'制度与指引',type:'制度',category:'党员发展',scope:'全员可见',tier:'全文检索',chunks:12,date:'2026-10-19 09:30',path:'第三章 · 发展对象的确定和考察',body:'党组织应当指定一至两名正式党员作入党积极分子的培养联系人。培养联系人应当了解入党积极分子的政治觉悟、道德品质、现实表现和家庭情况等，做好培养教育工作。',origin:'人工录入',version:'2026 年整理版'},
    {id:2,title:'2026 年秋季入党积极分子培训方案',source:'活动资料',type:'活动策划',category:'教育培训',scope:'按来源权限',tier:'全文检索',chunks:6,date:'2026-10-19 09:25',path:'培训安排 · 组织分工',body:'培训采用集中授课、专题研讨与实践学习相结合的方式开展。组织部负责报名和学员管理，文秘部负责会务安排，新媒体中心负责宣传与影像归档。',origin:'活动管理',version:'已审核策划'},
    {id:3,title:'关于开展十月份主题党日活动的通知',source:'通知公告',type:'通知',category:'组织生活',scope:'全员可见',tier:'全文检索',chunks:2,date:'2026-10-18 16:20',path:'活动要求',body:'各部门结合实际安排主题学习，提前明确活动负责人、参与对象和完成时间，活动结束后统一提交学习记录与总结材料。',origin:'通知公告',version:'已发布'},
    {id:4,title:'学生组织活动材料归档指引',source:'制度与指引',type:'操作指引',category:'材料归档',scope:'全员可见',tier:'全文检索',chunks:5,date:'2026-10-18 14:10',path:'归档清单 · 命名要求',body:'活动结束后应整理策划书、签到记录、新闻稿、图片和总结。文件命名包含日期、活动名称和材料类型，提交前由负责人检查内容完整性。',origin:'人工录入',version:'V1.2'},
    {id:5,title:'青年理论学习小组研讨活动新闻稿',source:'宣传内容',type:'新闻稿',category:'理论学习',scope:'按来源权限',tier:'全文检索',chunks:3,date:'2026-10-17 18:40',path:'活动纪实',body:'青年理论学习小组围绕本月学习主题开展交流。与会成员结合课程学习与组织工作分享体会，提出下一阶段实践安排。',origin:'内容宣传',version:'已发布'},
    {id:6,title:'学生组织活动总结模板',source:'材料与模板',type:'模板',category:'活动总结',scope:'按来源权限',tier:'标题匹配',chunks:1,date:'2026-10-17 11:00',path:'模板名称与用途',body:'用于记录活动目标、执行情况、参与情况、经验与改进建议。该来源仅提供名称与用途检索，附件正文不参与检索。',origin:'模板库',version:'通用模板'},
    {id:7,title:'2026 届成员结构概览',source:'业务快照',type:'事实快照',category:'组织成员',scope:'按来源权限',tier:'全文检索',chunks:1,date:'2026-10-19 02:00',asOf:'2026-10-19 02:00',path:'成员统计 · 本届概览',body:'截至 2026 年 10 月 19 日 02:00，本届组织成员共 42 人，分布于组织部、文秘部、新媒体中心和青年科技部。数据只包含汇总计数，不包含个人明细。',origin:'成员管理',version:'每日快照'},
    {id:8,title:'党员发展阶段汇总',source:'业务快照',type:'事实快照',category:'党务台账',scope:'部长及以上',tier:'全文检索',chunks:1,date:'2026-10-19 02:00',asOf:'2026-10-19 02:00',path:'党员发展 · 阶段分布',body:'截至 2026 年 10 月 19 日 02:00，党员发展台账按发展阶段统计。入党积极分子 24 人，发展对象 10 人，预备党员 8 人。本快照为汇总资料。',origin:'党务台账',version:'每日快照'},
  ];
  let queries = [
    {id:1,q:'活动结束后需要归档哪些材料？',time:'2026-10-19 09:42',result:'已引用依据',hit:5,allowed:4,filtered:1,duration:'328 ms',doc:4},
    {id:2,q:'下个月的培训安排确定了吗？',time:'2026-10-19 09:35',result:'没有依据',hit:0,allowed:0,filtered:0,duration:'186 ms',doc:null},
    {id:3,q:'本届组织一共有多少成员？',time:'2026-10-19 09:21',result:'已引用依据',hit:2,allowed:2,filtered:0,duration:'245 ms',doc:7},
    {id:4,q:'培训活动如何申请教室？',time:'2026-10-18 16:42',result:'相关度过低',hit:3,allowed:2,filtered:1,duration:'211 ms',doc:2},
    {id:5,q:'入党积极分子的培养联系人如何安排？',time:'2026-10-18 15:10',result:'未标注出处',hit:4,allowed:4,filtered:0,duration:'302 ms',doc:1},
  ];
  const depts = ['全部部门','组织部','文秘部','新媒体中心','青年科技部','未分配部门'];
  const roles = ['总负责人','组织部长','部门负责人','干事'];
  const roleGrants = {
    干事:['查看活动','查看个人排班','查看公开资料'],
    部门负责人:['查看活动','查看个人排班','查看公开资料','维护活动','管理排班','维护部门档案'],
    组织部长:['查看活动','查看个人排班','查看公开资料','维护活动','管理排班','维护部门档案','维护党员发展台账'],
    总负责人:['查看活动','查看个人排班','查看公开资料','维护活动','管理排班','维护部门档案','维护党员发展台账','查看组织汇总'],
  };
  let people = [
    {id:1,name:'林晓',username:'2024240101',dept:'文秘部',role:'总负责人',management:'组织所有者',status:'启用',student:'2024240101',phone:'138****6021'},
    {id:2,name:'陈禾',username:'2024240208',dept:'组织部',role:'组织部长',management:'组织管理员',status:'启用',student:'2024240208',phone:'139****3186'},
    {id:3,name:'周宁',username:'2024240312',dept:'新媒体中心',role:'部门负责人',management:'普通用户',status:'启用',student:'2024240312',phone:'137****0568'},
    {id:4,name:'王悦',username:'2025250116',dept:'青年科技部',role:'干事',management:'普通用户',status:'启用',student:'2025250116',phone:'—'},
    {id:5,name:'李知',username:'2025250221',dept:'组织部',role:'干事',management:'普通用户',status:'启用',student:'2025250221',phone:'—'},
    {id:6,name:'吴桐',username:'2023230318',dept:'新媒体中心',role:'干事',management:'普通用户',status:'启用',student:'2023230318',phone:'—'},
    {id:7,name:'赵明',username:'2025250105',dept:'组织部',role:'干事',management:'普通用户',status:'停用',student:'2025250105',phone:'—'},
    {id:8,name:'许安',username:'2026260102',dept:'文秘部',role:'干事',management:'普通用户',status:'待设密',pendingActivation:true,student:'2026260102',phone:'—'},
    {id:9,name:'宋雨',username:'2026260118',dept:'未分配部门',role:'干事',management:'普通用户',status:'待设密',pendingActivation:true,student:'2026260118',phone:'—'},
  ];
  const k = {tab:'内容台账',source:'全部资料',q:'',tier:'全部方式',result:'全部结果',sort:'最近更新',page:1,mode:'正常',lastIndexedAt:'2026-10-19 09:30'};
  const u = {tab:'全部账号',q:'',dept:'全部部门',role:'全部角色',management:'全部身份',selected:new Set(),page:1,density:'标准'};
  const s = {tab:'运行概览',name:'信息与智能工程学院党建办公室',shortName:'党建办公室',theme:document.documentElement.dataset.theme||'light',density:'标准',savedDensity:'标准',dirty:false,diag:'ready',lastCheck:'2026-10-19 09:45:00'};
  const hosts = {};
  Object.entries({knowledge:'知识库',users:'用户管理',settings:'系统设置'}).forEach(([id,name])=>{
    const el = document.createElement('section');el.id=id;el.className='view a-view';el.hidden=true;el.setAttribute('aria-label',name);$('.content').append(el);hosts[id]=el;viewNames[id]=name;
  });
  const kbNav = [...document.querySelectorAll('.nav button')].find(el=>el.textContent.trim()==='知识库');
  if(kbNav){kbNav.disabled=false;kbNav.removeAttribute('title');kbNav.dataset.view='knowledge';}
  const systemNav = $('[data-view="org"]').closest('nav');
  systemNav.insertAdjacentHTML('beforeend',`<button data-view="users">${I('users')}用户管理</button><button data-view="settings">${I('settings')}系统设置</button>`);
  const css = document.createElement('link');css.rel='stylesheet';css.href='PAMS-UI-v6-admin.css';document.head.append(css);
  const dialog = document.createElement('dialog');dialog.id='a-review-dialog';dialog.className='a-dialog';dialog.setAttribute('aria-labelledby','a-dialog-title');document.body.append(dialog);
  const toastEl = document.createElement('div');toastEl.className='a-toast';toastEl.role='status';toastEl.hidden=true;document.body.append(toastEl);
  let toastTimer, focusBefore, submitHandler, currentDialog = '', pendingUser = null;
  const toast = text => {toastEl.textContent=text;toastEl.hidden=false;clearTimeout(toastTimer);toastTimer=setTimeout(()=>toastEl.hidden=true,4200);};
  function modal(name, desc, body, foot = b('关闭','close'), drawer = false, onSubmit = null, kind = ''){
    if(!dialog.open)focusBefore=document.activeElement;
    submitHandler=onSubmit;currentDialog=kind;
    dialog.classList.toggle('a-drawer',drawer);
    dialog.innerHTML=`<${onSubmit?'form':'div'} class="a-dialog-frame" ${onSubmit?'id="a-editor"':''}><header class="a-dialog-head"><div><h2 id="a-dialog-title">${E(name)}</h2>${desc?`<p>${E(desc)}</p>`:''}</div><button class="icon-btn" type="button" data-a="close" aria-label="关闭${E(name)}">${I('close')}</button></header><div class="a-dialog-body"><div class="a-form-error" id="a-error" role="alert"></div>${body}</div><footer class="a-dialog-foot">${foot}</footer></${onSubmit?'form':'div'}>`;
    if(!dialog.open)dialog.showModal();
    const focus=$('input:not([disabled]),textarea,select',dialog)||$('#a-dialog-title',dialog);focus.tabIndex=focus.tabIndex<0?-1:focus.tabIndex;focus.focus({preventScroll:true});
  }
  dialog.addEventListener('close',()=>{currentDialog='';if(focusBefore?.isConnected)focusBefore.focus();});
  dialog.addEventListener('submit',e=>{e.preventDefault();submitHandler?.(Object.fromEntries(new FormData(e.target)));});
  const error = text => {$('#a-error',dialog).textContent=text;$('#a-error',dialog).scrollIntoView({block:'nearest'});};
  const submit = text => `<button class="btn primary" type="submit">${E(text)}</button>`;
  function getDocs(){return docs.filter(d=>(k.source==='全部资料'||d.source===k.source)&&(k.tier==='全部方式'||d.tier===k.tier)&&`${d.title} ${d.category} ${d.path} ${d.body}`.toLowerCase().includes(k.q.toLowerCase())).sort((a,b)=>k.sort==='标题顺序'?a.title.localeCompare(b.title,'zh-CN'):b.date.localeCompare(a.date));}
  function renderKnowledge(){
    const snapshots=docs.filter(d=>d.type==='事实快照'),noAnswer=queries.filter(q=>q.result!=='已引用依据').length;
    hosts.knowledge.innerHTML=title('知识库','管理灵思可引用的资料，核对内容来源、数据时效与检索结果。',`<details class="a-maintenance"><summary class="btn">${I('settings')}维护工具</summary><div class="a-menu">${l('重建全部检索索引','reindex')}${l('刷新业务快照','refresh-snapshots')}<small>日常自动更新；手动维护用于补齐与修复。</small></div></details>${b(I('plus')+' 录入文档','new-doc','',true)}`)+
      `<div class="a-context ${k.mode==='降级'?'warning':''}"><span>${I(k.mode==='降级'?'info':'check')}<b>${k.mode==='降级'?'检索已降级为模糊匹配':'全文检索可用'}</b><small>${k.mode==='降级'?'可继续使用，检索速度与相关性可能下降。':'资料随业务变更自动更新'}</small></span><span><small>最近成功更新 ${k.lastIndexedAt.slice(5)}</small>${l('查看知识源','source-info')}</span></div>`+
      stats([['资料条目',docs.length,'按当前账号可见范围统计'],['检索分块',docs.reduce((n,d)=>n+d.chunks,0),'来自 '+(sources.length-1)+' 类知识源'],['事实快照',snapshots.length,'最新数据截至 '+snapshots.map(d=>d.asOf).sort().at(-1).slice(5)],['我的未答复记录',noAnswer,'累计 '+queries.length+' 次检索','attention']])+
      `<div class="a-workbench"><aside class="surface a-rail" aria-label="知识来源"><h2>按来源浏览</h2>${sources.map((src,i)=>`<button data-a="kb-source" data-value="${E(src)}" aria-pressed="${k.source===src}">${I(i===0?'book':i===6?'server':'folder')}${E(src)}<span>${src==='全部资料'?docs.length:docs.filter(d=>d.source===src).length}</span></button>`).join('')}<hr><p>来源权限同步生效。<br>资料数量不包含当前账号无权访问的内容。</p>${l('了解可见范围','source-info')}</aside><section class="surface"><nav class="a-tabs" aria-label="知识库视图">${['内容台账','事实快照','检索记录'].map(t=>`<button data-a="kb-tab" data-value="${t}" aria-pressed="${k.tab===t}">${t}${t==='检索记录'?'<span>我的</span>':''}</button>`).join('')}</nav><div id="a-kb-pane">${knowledgePane()}</div></section></div>`+review('knowledge');
  }
  function knowledgePane(){
    if(k.mode==='加载中')return empty('正在加载资料','正在读取当前账号可见的知识内容。',b('显示加载完成','kb-state','正常'));
    if(k.mode==='加载失败')return empty('资料加载失败','暂时无法读取知识库，请重试。',b('重新加载','kb-state','正常',true));
    if(k.tab==='检索记录'){
      const rows=queries.filter(q=>(k.result==='全部结果'||q.result===k.result)&&q.q.includes(k.q));
      return `<div class="a-toolbar">${search('a-kb-query','搜索我的提问',k.q)}${select('a-kb-result','结果',['全部结果','已引用依据','没有依据','相关度过低','未标注出处'],k.result)}${l('重置','kb-reset')}</div><p class="a-toolbar-note">仅展示当前账号的检索记录。未答复时保留具体原因，便于补充资料与核对依据。</p>${rows.length?`<div class="table-scroll"><table class="a-table"><thead><tr><th>提问内容</th><th>检索结果</th><th>可用 / 命中</th><th>时间</th><th>操作</th></tr></thead><tbody>${rows.map(q=>`<tr><td class="a-main-cell"><strong>${E(q.q)}</strong><small>灵思对话 · ${q.duration}</small></td><td>${status(q.result,q.result==='已引用依据'?'green':'amber')}</td><td>${q.allowed} / ${q.hit}</td><td>${q.time.slice(5)}</td><td class="a-operations">${l('查看详情','query-detail',q.id)}</td></tr>`).join('')}</tbody></table></div>${paginate(rows.length,1,10,'kb-page')}`:empty('没有匹配的检索记录','尝试其他关键词或清除结果筛选。',b('清除筛选','kb-reset'))}<div class="a-explain">命中内容经权限复核后成为可用依据；回答仍需通过相关度与出处检查。</div>`;
    }
    if(k.tab==='事实快照'){
      const rows=docs.filter(d=>d.type==='事实快照'&&(k.source==='全部资料'||k.source===d.source)&&`${d.title} ${d.category}`.includes(k.q));
      return `<div class="a-toolbar">${search('a-kb-query','搜索快照名称、业务分类',k.q)}<span class="toolbar-spacer"></span>${b(I('refresh')+' 刷新快照','refresh-snapshots')}</div><p class="a-toolbar-note">按日生成的业务汇总。数字对应“数据截至”时间，最新情况请以业务页面为准。</p>${rows.length?`<div class="table-scroll"><table class="a-table"><thead><tr><th>快照名称</th><th>数据截至</th><th>可见范围</th><th>更新频率</th><th>操作</th></tr></thead><tbody>${rows.map(d=>`<tr><td class="a-main-cell"><strong>${E(d.title)}</strong><small>${E(d.origin)} · 仅汇总计数</small></td><td>${d.asOf.slice(5)}<small>生成于 ${d.date.slice(11)}</small></td><td>${tag(d.scope)}</td><td>每日</td><td class="a-operations">${l('查看快照','doc-detail',d.id)}</td></tr>`).join('')}</tbody></table></div>${paginate(rows.length,1,10,'kb-page')}`:empty('当前范围没有事实快照','可选择“业务快照”来源，或清除关键词。',b('查看全部快照','snapshot-all'))}<div class="a-explain">快照沿用来源的访问权限，不包含成员个人明细。</div>`;
    }
    const all=k.mode==='空状态'?[]:getDocs();k.page=Math.min(k.page,Math.max(1,Math.ceil(all.length/6)));
    const rows=all.slice((k.page-1)*6,k.page*6);
    return `<div class="a-toolbar">${search('a-kb-query','搜索标题、正文、条款',k.q)}${select('a-kb-tier','检索',['全部方式','全文检索','标题匹配'],k.tier)}${select('a-kb-sort','排序',['最近更新','标题顺序'],k.sort)}${l('重置','kb-reset')}</div>${rows.length?`<div class="table-scroll"><table class="a-table"><thead><tr><th>资料名称 / 来源</th><th>检索方式</th><th>可见范围</th><th>更新于</th><th>操作</th></tr></thead><tbody>${rows.map(d=>`<tr><td class="a-main-cell"><button class="a-title-button" data-a="doc-detail" data-value="${d.id}"><span class="a-doc-icon">${I(d.type==='事实快照'?'server':'file')}</span><span><strong>${E(d.title)}</strong><span class="a-inline-meta">${E(d.origin)}<span>·</span>${E(d.category)}<span>·</span>${d.chunks} 个分块</span></span></button></td><td>${tag(d.tier,d.tier==='全文检索'?'blue':'')}<small>${d.tier==='全文检索'?'正文可作为引用':'附件正文不参与检索'}</small></td><td>${tag(d.scope)}</td><td>${d.date.slice(5,10)}<small>${d.date.slice(11)}</small></td><td class="a-operations">${l('查看','doc-detail',d.id)}</td></tr>`).join('')}</tbody></table></div>${paginate(all.length,k.page,6,'kb-page')}`:empty(k.mode==='空状态'?'知识库还没有资料':'没有找到匹配资料',k.mode==='空状态'?'录入制度或指引，让灵思的回答有据可查。':'请调整来源、检索方式或关键词。',b(k.mode==='空状态'?'录入第一份文档':'清除筛选',k.mode==='空状态'?'new-doc':'kb-reset','',true))}<div class="a-explain">默认按资料聚合展示。在详情中核对完整正文与检索分块；业务资料请回到来源页面维护。</div>`;
  }
  const refreshKBPane=()=>{$('#a-kb-pane').innerHTML=knowledgePane();};
  function showDoc(id){
    const d=docs.find(x=>x.id===Number(id));if(!d)return;
    const snapshot=d.type==='事实快照';
    modal(d.title,snapshot?'事实快照 · 汇总数据':'资料详情 · 核对来源与引用',`${snapshot?`<div class="a-snapshot-date">数据截至 ${E(d.asOf)}；不代表此刻实时数据。</div>`:''}${info([['资料来源',E(d.origin)],['业务分类',E(d.category)],['可见范围',tag(d.scope)],['检索方式',tag(d.tier,'blue')],['版本 / 状态',E(d.version)],['最近更新',E(d.date)]])}<h3 class="a-section-title">${snapshot?'汇总内容':'正文预览'}${tag(d.chunks+' 个检索分块')}</h3><article class="a-article"><h3>${E(d.path)}</h3><p>${E(d.body)}</p></article><p class="a-disclosure">${snapshot?'仅展示汇总计数，个人明细仍在原业务页面按权限查看。':d.tier==='标题匹配'?'本来源只检索标题和用途，附件正文不会进入回答依据。':'灵思引用时会保留资料标题和条款路径，便于追溯。'}</p>${!snapshot?`<h3 class="a-section-title">引用定位</h3><div class="a-chunk"><h3>${E(d.path)}</h3><small>分块 1 / ${d.chunks} · ${E(d.title)}</small><p>${E(d.body)}</p></div>${d.chunks>1?l('查看完整分块示例','doc-chunks',d.id):''}`:''}`,b('关闭','close')+(d.origin==='人工录入'?b('编辑文档','edit-doc',d.id):b('查看来源说明','origin',d.id)),true);
  }
  function editDoc(id){
    const d=docs.find(x=>x.id===Number(id));
    modal(d?'编辑文档':'录入文档','录入后按可见范围建立检索索引。',`<div class="a-form"><label>文档标题<input name="title" required maxlength="200" placeholder="例如：学生组织活动材料归档指引" value="${E(d?.title||'')}"></label><div class="a-two"><label>文档类型<select name="type">${options(['制度','上级文件','操作指引','常见问答'],d?.type||'制度')}</select></label><label>业务分类<input name="category" maxlength="50" placeholder="例如：党员发展" value="${E(d?.category||'')}"></label></div><label>版本<input name="version" maxlength="50" value="${E(d?.version||'')}" placeholder="例如：2026 年修订版"></label><label>正文<textarea name="body" required rows="9" placeholder="粘贴完整正文，保留章节与条款编号。">${E(d?.body||'')}</textarea><small>请保留来源与条款信息，便于核对引用。</small></label><label>可见范围<select name="scope">${options(['全员可见','部长及以上'],d?.scope||'全员可见')}</select><small>组织内授权用户可检索；不会扩大原业务权限。</small></label></div>`,b('取消','close')+submit('保存并建立索引'),true,values=>{
      if(!values.title.trim()||!values.body.trim())return error('请填写文档标题与完整正文。');
      const updated={...d,...values,title:values.title.trim(),body:values.body.trim(),source:'制度与指引',origin:'人工录入',tier:'全文检索',chunks:Math.max(1,Math.ceil(values.body.length/300)),date:'2026-10-19 09:50',path:'文档正文',version:values.version||'未标注版本',category:values.category||'未分类'};
      if(d)Object.assign(d,updated);else docs.unshift({...updated,id:Math.max(...docs.map(x=>x.id))+1});
      k.mode='正常';k.lastIndexedAt='2026-10-19 09:50';k.source='全部资料';k.q='';k.tier='全部方式';k.tab='内容台账';k.page=1;dialog.close();renderKnowledge();toast('文档已保存并建立索引（仅原型）');
    },'doc-edit');
  }
  function showQuery(id){
    const q=queries.find(x=>x.id===Number(id)),d=docs.find(x=>x.id===q.doc);
    const desc={没有依据:'当前可见资料中没有命中内容。可补充相关制度、指引，或尝试更具体的提问。',相关度过低:'命中的资料与提问相关度不足，系统没有用弱相关内容生成答案。',未标注出处:'本次生成结果未提供可核验的出处，系统未直接展示答案。',已引用依据:'本次回答通过相关度与出处检查，可按引用定位核对原文。'};
    modal('检索记录详情','我的提问 · '+q.time,`<article class="a-article"><p>${E(q.q)}</p></article><h3 class="a-section-title">${status(q.result,q.result==='已引用依据'?'green':'amber')}</h3><p class="a-muted-note">${desc[q.result]}</p><h3 class="a-section-title">依据筛选过程</h3><div class="a-log-flow"><div>命中内容<strong>${q.hit}</strong></div><div>权限复核排除<strong>${q.filtered}</strong></div><div>可用依据<strong>${q.allowed}</strong></div></div><p class="a-disclosure">权限复核排除的内容不展示标题与正文。可用依据仍需通过相关度和出处检查。</p>${d?`<h3 class="a-section-title">可查看的命中资料</h3>${related(d.title,d.path,'doc-detail',d.id,'file')}`:''}${info([['检索耗时',q.duration],['来源','灵思 · 对话']])}`,b('关闭','close')+(q.result==='没有依据'?b('补充资料','new-doc','',true):''),true);
  }
  function getPeople(){return people.filter(p=>(u.tab==='全部账号'||p.status===u.tab)&&(u.dept==='全部部门'||p.dept===u.dept)&&(u.role==='全部角色'||p.role===u.role)&&(u.management==='全部身份'||p.management===u.management)&&`${p.name} ${p.username} ${p.student}`.toLowerCase().includes(u.q.toLowerCase()));}
  function renderUsers(){
    const pending=people.filter(p=>p.status==='待设密').length;
    hosts.users.innerHTML=title('用户管理','管理本组织登录账号、部门归属和主角色，核对账号的实际访问权限。',b('角色与权限','role-help')+b(I('file')+' 花名册开户','import')+b(I('plus')+' 新增用户','new-user','',true))+
      stats([['本组织账号',people.length,'登录账号与成员名册分别管理'],['启用账号',people.filter(p=>p.status==='启用').length,'可以正常登录'],['待设密',pending,'等待用户完成首次设置','attention'],['管理身份',people.filter(p=>p.management!=='普通用户').length,'所有者 1 · 管理员 1']])+
      (pending?`<div class="a-account-alert"><div><b>${pending} 个账号尚未完成密码设置</b><small>请由开户经办人核对交付状态；首次设置完成后，账号才可正常登录。</small></div>${l('查看待设密账号','user-tab','待设密')}</div>`:'')+
      `<section class="surface"><nav class="a-tabs" aria-label="账号状态">${['全部账号','启用','待设密','停用'].map(t=>`<button data-a="user-tab" data-value="${t}" aria-pressed="${u.tab===t}">${t}<span>${t==='全部账号'?people.length:people.filter(p=>p.status===t).length}</span></button>`).join('')}</nav><div class="a-toolbar">${search('a-user-query','搜索姓名、用户名或学号',u.q)}${select('a-user-dept','部门',depts,u.dept)}${select('a-user-role','角色',['全部角色',...roles],u.role)}${select('a-user-management','身份',['全部身份','组织所有者','组织管理员','普通用户'],u.management)}${l('重置','user-reset')}</div><div id="a-user-pane">${userPane()}</div></section>`+review('users');
  }
  function userPane(){
    const all=getPeople();u.page=Math.min(u.page,Math.max(1,Math.ceil(all.length/6)));const rows=all.slice((u.page-1)*6,u.page*6);
    const chosen=people.filter(p=>u.selected.has(p.id));
    return (chosen.length?`<div class="a-batch"><span>已选择 ${chosen.length} 个账号</span>${l('启用账号','batch-status','启用')}${l('停用账号','batch-status','停用')}${l('取消选择','clear-selection')}</div>`:'')+
      (rows.length?`<div class="table-scroll"><table class="a-table a-user-table ${u.density==='紧凑'?'compact':''}"><thead><tr><th class="a-check"><input type="checkbox" id="a-select-users" aria-label="选择本页可管理账号" ${rows.filter(p=>p.management==='普通用户').every(p=>u.selected.has(p.id))?'checked':''} ${rows.every(p=>p.management!=='普通用户')?'disabled':''}></th><th>用户 / 登录账号</th><th>所属部门</th><th>业务主角色</th><th>管理身份</th><th>账号状态</th><th>操作</th></tr></thead><tbody>${rows.map(p=>`<tr><td class="a-check"><input type="checkbox" data-user-select="${p.id}" aria-label="选择${E(p.name)}" ${u.selected.has(p.id)?'checked':''} ${p.management!=='普通用户'?'disabled title="受保护的管理账号"':''}></td><td class="a-main-cell"><div class="a-user"><span class="avatar">${E(p.name.slice(0,1))}</span><div><button class="a-title-button a-user-name" data-a="user-detail" data-value="${p.id}"><strong>${E(p.name)}</strong>${p.id===1?tag('当前账号'):''}</button><small>${E(p.username)}</small></div></div></td><td>${E(p.dept)}</td><td>${tag(p.role,'blue')}</td><td>${p.management==='普通用户'?'<span class="muted">普通用户</span>':tag(p.management)}</td><td>${status(p.status,p.status==='启用'?'green':p.status==='待设密'?'amber':'')}</td><td class="a-operations">${l('查看','user-detail',p.id)}　${l('编辑','edit-user',p.id,p.management!=='普通用户'?'disabled title="受保护账号，请由对应治理入口维护"':'')}</td></tr>`).join('')}</tbody></table></div>${paginate(all.length,u.page,6,'user-page')}`:empty('没有匹配的账号','调整部门、角色或账号状态后重试。',b('清除筛选','user-reset')))+`<div class="a-explain">业务主角色决定工作权限；所有者与管理员身份单独授予。账号停用会阻止登录，成员档案仍保留。</div>`;
  }
  const refreshUserPane=()=>{$('#a-user-pane').innerHTML=userPane();};
  function showUser(id){
    const p=people.find(x=>x.id===Number(id));if(!p)return;
    const protectedUser=p.management!=='普通用户';
    modal(p.name,'账号详情 · '+p.username,`${info([['账号状态',status(p.status,p.status==='启用'?'green':p.status==='待设密'?'amber':'')],['学号',E(p.student||'—')],['联系电话',E(p.phone||'—')],['所属部门',E(p.dept)],['业务主角色',tag(p.role,'blue')],['管理身份',tag(p.management)]])}<h3 class="a-section-title">实际访问权限${l('查看权限说明','user-permissions',p.id)}</h3><p class="a-muted-note">根据已发布角色授权计算。${protectedUser?'管理能力来源单独核对。':'普通用户不因角色名称获得管理能力。'}</p><ul class="a-mini-list"><li><span>活动管理</span><span>${p.role==='干事'?'查看与参与':'查看、创建与维护'}</span></li><li><span>排班考勤</span><span>${p.role==='干事'?'查看个人安排':'查看与管理'}</span></li><li><span>知识库</span><span>按来源权限访问</span></li><li><span>账号与权限治理</span><span>${protectedUser?p.management:'未授予'}</span></li></ul>${p.status==='待设密'?'<div class="a-confirm">账号等待首次密码设置。通过花名册开户的账号由经办人交付一次性设密信息，此处不展示明文凭证。</div>':''}${protectedUser?'<div class="a-confirm">此账号为受保护的管理账号。管理身份需由有权人员在角色与权限中维护。</div>':''}`,b('关闭','close')+(!protectedUser?b(p.status==='停用'?'启用账号':'停用账号','user-status',p.id)+b('编辑资料','edit-user',p.id,true):''),true);
    if(!protectedUser)$('.a-dialog-foot',dialog).insertAdjacentHTML('afterbegin',`<span style="margin-right:auto">${l('删除账号','delete-user',p.id)}</span>`);
  }
  function editUser(id, draft){
    const p=people.find(x=>x.id===Number(id));if(p&&p.management!=='普通用户')return;
    const v=draft||p||{};
    modal(p?'编辑用户':'新增用户','账号资料与业务角色分别填写；管理员身份在独立入口维护。',`<div class="a-form"><fieldset><legend>账号资料</legend><label>用户名<input name="username" required pattern="[a-zA-Z0-9_]+" maxlength="50" value="${E(v.username||'')}" ${p?'disabled':''} placeholder="字母、数字或下划线"><small>${p?'创建后不能修改。':'用于登录，须在本组织内唯一。'}</small></label><label>姓名<input name="name" required maxlength="50" value="${E(v.name||'')}" placeholder="真实姓名"></label><div class="a-two"><label>学号<input name="student" maxlength="20" value="${E(v.student||'')}"></label><label>联系电话<input name="phone" maxlength="20" value="${E(v.phone==='—'?'':v.phone||'')}"></label></div></fieldset><fieldset><legend>部门与业务角色</legend><label>所属部门<select name="dept">${options(depts.slice(1),v.dept||'未分配部门')}</select></label><label>业务主角色<select name="role">${options(roles,v.role||'干事')}</select><small>一个账号对应一个主角色；替换前将展示权限影响。</small></label></fieldset><fieldset><legend>登录与状态</legend>${!p?'<label>初始密码<input type="password" name="password" required minlength="8" maxlength="100" autocomplete="new-password" placeholder="至少 8 位"><small>原型不保留密码。批量开户请使用花名册开户流程。</small></label>':''}<label>账号状态<select name="status">${options(p?.status==='待设密'?['待设密','停用']:['启用','停用'],v.status||'启用')}</select></label></fieldset></div>`,b('取消','close')+submit(p?'预览并保存':'创建用户'),true,values=>{
      if(!values.name.trim())return error('请填写姓名。');
      if(!p&&people.some(x=>x.username===values.username))return error('此用户名已存在，请使用其他用户名。');
      delete values.password;
      const updated={...values,name:values.name.trim(),username:p?.username||values.username,student:values.student.trim(),phone:values.phone.trim()||'—'};
      if(p&&p.role!==values.role){pendingUser={id:p.id,values:updated};return previewRole(p,updated);}
      saveUser(p,updated);
    },'user-edit');
  }
  function saveUser(p,values){if(p)Object.assign(p,values);else people.unshift({...values,id:Math.max(...people.map(x=>x.id))+1,management:'普通用户'});dialog.close();u.tab='全部账号';u.q='';u.dept='全部部门';u.role='全部角色';u.page=1;renderUsers();toast(p?'用户资料已保存（仅原型）':'用户已创建（仅原型）');}
  function previewRole(p,values){
    const added=roleGrants[values.role].filter(x=>!roleGrants[p.role].includes(x)),removed=roleGrants[p.role].filter(x=>!roleGrants[values.role].includes(x));
    modal('确认替换业务主角色','请核对角色与权限变化。',`<p class="a-muted-note">正在调整 ${E(p.name)} 的业务主角色。</p><div class="a-role-diff"><div><small>原角色</small><strong>${E(p.role)}</strong></div><span>→</span><div><small>新角色</small><strong>${E(values.role)}</strong></div></div>${info([['所属部门',E(values.dept)],['管理身份','普通用户（不变）'],['授权范围','本组织']])}<h3 class="a-section-title">权限变化（示例）</h3><ul class="a-mini-list">${added.map(x=>`<li><span>${E(x)}</span>${tag('新增','green')}</li>`).join('')}${removed.map(x=>`<li><span>${E(x)}</span>${tag('收回','amber')}</li>`).join('')}${!added.length&&!removed.length?'<li>业务操作范围无变化</li>':''}</ul><div class="a-confirm">保存后采用新主角色的业务授权，原角色权限不再保留；管理身份保持不变。</div><p class="a-muted-note">当前为交互预览，示例角色不代表线上权限配置。</p>`,b('返回编辑','back-user',p.id)+b('确认替换并保存','confirm-role',p.id,true));
  }
  function confirmStatus(ids,target){
    const list=people.filter(p=>ids.includes(p.id)&&p.management==='普通用户');
    const eligible=list.filter(p=>p.status!==target&&!(target==='启用'&&p.pendingActivation));
    if(!eligible.length){toast(target==='启用'?'没有可启用账号；待设密账号需先完成密码设置。':'所选账号已停用。');return;}
    modal(target+'账号',`将对 ${eligible.length} 个账号生效。`,`<ul class="a-mini-list">${eligible.map(p=>`<li><span>${E(p.name)}</span><small>${E(p.username)}</small></li>`).join('')}</ul><div class="a-confirm">${target==='停用'?'停用后无法登录系统，成员档案与历史业务记录保留。':'启用后恢复登录，业务访问范围由当前主角色决定。'}</div>${list.length!==eligible.length?'<p class="a-muted-note">状态无需变更或尚未设密的账号已跳过。</p>':''}`,b('取消','close')+b('确认'+target,'apply-status',eligible.map(p=>p.id).join(',')+'|'+target,true,target==='停用'?'data-danger':''));
  }
  function importFlow(){
    modal('花名册开户','从已有花名册为未关联账号的成员开户。',`<div class="a-context"><span>${I('info')}选择成员 → 核对授权 → 确认开户 → 安全交付</span></div><p class="a-muted-note">开户沿用成员的姓名、学号与部门；每位成员需核对角色与授权范围，已有账号不会重复创建。</p><h3 class="a-section-title">本轮交互示例</h3><ul class="a-mini-list"><li><span>许安 · 文秘部</span>${status('待设密','amber')}</li><li><span>宋雨 · 未分配部门</span>${status('待设密','amber')}</li></ul><div class="a-confirm">本原型展示开户后的交付状态，不生成真实账号或一次性密码凭证。完整开户沿用系统现有花名册流程。</div>`,b('关闭','close')+b('查看待设密账号','pending-accounts','',true),true);
  }
  const settingsTabs=['运行概览','组织与功能','使用偏好','存储与诊断'];
  function renderSettings(){
    hosts.settings.innerHTML=title('系统设置','查看系统运行情况，管理组织入口与个人使用偏好。',b(I('refresh')+' 刷新状态','system-refresh'))+
      `<div class="a-settings-layout"><nav class="surface a-rail" aria-label="系统设置分类"><h2>设置分类</h2>${settingsTabs.map((t,i)=>`<button data-a="settings-tab" data-value="${t}" aria-pressed="${s.tab===t}">${I(['server','users','palette','folder'][i])}${t}</button>`).join('')}</nav><div class="a-settings-content">${settingsPane()}</div></div>`+review('settings');
  }
  function settingsPane(){
    if(s.tab==='运行概览')return `<section class="surface"><div class="a-running"><span class="a-health-icon">${I('check')}</span><div><h2>系统服务运行正常</h2><p>最近检查 ${s.lastCheck} · 当前组织：${E(s.shortName)}</p></div>${l('查看诊断','settings-tab','存储与诊断')}</div><div class="a-service-grid"><div class="a-service"><small>应用服务</small><strong>${status('连接正常')}</strong><small>基础健康检查已通过</small></div><div class="a-service"><small>知识检索</small><strong>${status(k.mode==='降级'?'模糊匹配':'全文检索',k.mode==='降级'?'amber':'green')}</strong><small>维护与更新请前往知识库</small></div><div class="a-service"><small>文件存储</small><strong>${tag('目录已配置')}</strong><small>读写能力需单独核验</small></div></div></section>`+
      section('系统信息','运行信息与当前会话。',info([['产品名称','协奏 Concerto · 学生组织智能工作台'],['所属组织',E(s.name)],['应用版本',tag('0.1.0','blue')+'<small>示例版本，以部署为准</small>'],['当前登录','林晓'+tag('组织所有者')],['界面主题',document.documentElement.dataset.theme==='dark'?'深色':'浅色']]))+
      `<section class="surface">${head('常用管理入口','根据任务进入对应工作区。')}${related('组织架构','维护部门、下级小组和负责人','route','org','users')}${related('用户管理','账号状态、部门归属与业务主角色','route','users','shield')}${related('知识库','资料内容、事实快照与检索记录','route','knowledge','book')}</section>`;
    if(s.tab==='组织与功能')return section('组织信息','组织资料与架构使用统一维护入口。',info([['组织名称',E(s.name)],['组织简称',E(s.shortName)],['组织类型','党建办公室'],['品牌颜色','<span style="display:flex;align-items:center;gap:8px"><span style="width:14px;height:14px;background:var(--brand);border-radius:3px"></span>院校蓝</span>']]),b('前往组织架构','route','org'))+
      section('功能与领域包','通用工作区与组织领域能力分开管理。',`<ul class="a-mini-list"><li><span>活动、排班考勤、成员与档案</span>${status('通用工作区')}</li><li><span>灵思与知识库</span>${status('智能协作')}</li><li><span>党建包 · 党务台账</span>${tag('已启用','blue')}</li></ul><p class="a-disclosure">入口显示同时受组织领域包和账号权限控制。领域包调整请在组织管理中核对，不在此直接切换。</p>`)+
      `<section class="surface">${related('角色与权限','业务授权与管理员身份分别维护','role-help','','shield')}${related('组织架构','统一维护组织名称、部门与负责人','route','org','users')}</section>`;
    if(s.tab==='使用偏好')return `<section class="surface">${head('界面与列表','仅影响当前浏览器，不改变其他成员的使用设置。')}<div class="a-panel-body"><div class="a-form-row"><div><strong>界面主题</strong><p>立即切换，和右上角主题按钮保持同步。</p></div><div class="a-choice">${[['light','浅色'],['dark','深色']].map(([v,t])=>`<button data-a="theme" data-value="${v}" aria-pressed="${document.documentElement.dataset.theme===v}">${I(v==='light'?'sun':'moon')} ${t}</button>`).join('')}</div></div><div class="a-form-row"><div><strong>用户列表密度</strong><p>标准模式便于阅读，紧凑模式在同屏显示更多账号。</p></div><div class="a-choice">${['标准','紧凑'].map(v=>`<button data-a="density" data-value="${v}" aria-pressed="${s.density===v}">${v}</button>`).join('')}</div></div><div class="a-form-row"><div><strong>预览效果</strong><p>仅示意用户列表的行高变化。</p></div><span class="a-tag">${s.density==='标准'?'标准行高 74 px':'紧凑行高 54 px'}</span></div></div><footer class="a-savebar"><span>${s.dirty?'有未保存的密度修改':'偏好已保存到当前浏览器'}</span><div class="actions">${b('取消修改','discard-preferences','',false,s.dirty?'':'disabled')}${b('保存偏好','save-preferences','',true,s.dirty?'':'disabled')}</div></footer></section>`+section('语言与时间','全站日期与时段采用一致口径。',info([['界面语言','简体中文'],['显示时区','Asia/Shanghai（UTC+08:00）'],['日期格式','YYYY-MM-DD · 24 小时制']]))+`<p class="a-muted-note">通知消息在通知中心查看；账号密码在个人账号入口维护。</p>`;
    return section('文件存储','只读展示服务端配置。',info([['上传目录','<code class="a-code">/app/uploads</code>'],['目录来源','部署环境配置'],['可用空间','未提供监测数据'],['读写验证','未执行文件读写测试']])+`<p class="a-disclosure">目录路径不等于读写正常。如需调整目录，请由部署维护人员修改服务配置。</p>`)+
      section('连接诊断','检查系统信息与基础健康响应。',`<div class="a-diagnostic"><div><span>系统信息<small>应用版本与上传目录配置</small></span>${status(s.diag==='failed'?'读取失败':'可读取',s.diag==='failed'?'red':'green')}</div><div><span>基础健康检查<small>应用能够返回健康响应</small></span>${status(s.diag==='failed'?'连接失败':'正常',s.diag==='failed'?'red':'green')}</div><div><span>数据库、磁盘、备份<small>当前接口未提供专项监测</small></span>${tag('未检测')}</div></div><p class="a-disclosure">基础健康正常仅表明应用可响应，不能代替数据库、磁盘和备份检查。</p>`,b('运行诊断','diagnose'))+
      section('维护入口','按业务范围定位问题。',`<p class="a-muted-note">资料没有及时更新时，先检查知识源和索引时间；账号无法访问时，先检查账号状态与角色授权。</p><div class="actions" style="justify-content:flex-start;margin-top:15px">${b('知识库维护','route','knowledge')}${b('账号检查','route','users')}</div>`);
  }
  function navigate(id){const url=new URL(location.href);url.searchParams.set('view',id);url.searchParams.delete('module');url.searchParams.delete('page');history.replaceState(null,'',url);showView(id);}
  function route(id){if(s.dirty&&!hosts.settings.hidden){return modal('离开前保存偏好？','当前列表密度尚未保存。','<p class="a-muted-note">选择保存后离开，或放弃这次密度修改。</p>',b('继续编辑','close')+b('放弃并离开','leave-discard',id)+b('保存并离开','leave-save',id,true));}navigate(id);}
  function persistPreferences(){s.savedDensity=s.density;u.density=s.density;s.dirty=false;try{localStorage.setItem('pams-v6-review-density',s.density);}catch{}renderSettings();renderUsers();}
  function sourceInfo(){modal('知识来源与可见范围','资料来源决定内容与访问边界。',`<p class="a-muted-note">知识库是业务内容的检索视图。资料更新回到来源维护，人工录入文档可在知识库编辑。</p><ul class="a-mini-list">${sources.slice(1).map(x=>`<li><span>${E(x)}</span><small>${docs.filter(d=>d.source===x).length} 条可见资料</small></li>`).join('')}</ul><div class="a-confirm">全文来源检索正文，材料与模板仅检索标题和用途。内容台账、详情和检索记录均需重新校验来源权限。</div><p class="a-muted-note">全员可见指本组织的授权成员；“按来源权限”继承对应业务的访问约束。</p>`,b('知道了','close'),true);}
  function reviewNotes(key){
    const content={
      knowledge:['知识库','按资料聚合内容台账，保留原有分块核对、检索记录与事实快照。将“重建索引”降为维护操作，主操作改为录入文档。','已有：来源、分块、人工文档增改、事实快照、个人检索记录与维护接口。','设计建议：按资料聚合、分类检索与排序需要列表聚合支持；“我的未答复”是明确口径的界面聚合。'],
      users:['用户管理','将账号、角色、管理员身份分列；表单保留单主角色，替换前展示确认页。自有账号与其他管理账号有保护提示。','已有：账号增改、部门筛选、角色预览应用、有效权限查询和花名册开户。','设计建议：状态与身份筛选、列表多选、批量启停需补充接口与逐项结果；本原型只操作普通示例账号。'],
      settings:['系统设置','设置按运行概览、组织与功能、使用偏好、存储与诊断组织。组织、账号和知识库链接到现有工作区。','已有：版本、上传目录、基础健康响应；组织资料、主题和领域包已有各自入口。','设计建议：状态聚合与列表密度偏好；数据库、磁盘容量和备份状态保持“未检测”，不据基础 ping 推断。'],
    }[key];
    modal(content[0]+' · 设计评审','本轮只制作原型，等待审查后再落地。',`<h3 class="a-section-title">信息结构</h3><p class="a-muted-note">${content[1]}</p><h3 class="a-section-title">现有能力</h3><p class="a-muted-note">${content[2]}</p><h3 class="a-section-title">待评审的设计增强</h3><p class="a-muted-note">${content[3]}</p><h3 class="a-section-title">参考依据</h3><ul class="a-mini-list"><li><a href="https://ant.design/docs/spec/research-list/" target="_blank" rel="noopener">Ant Design 列表页规范</a></li><li><a href="https://ant.design/docs/spec/data-display/" target="_blank" rel="noopener">Ant Design 数据展示规范</a></li><li><a href="https://ant.design/docs/spec/data-entry/" target="_blank" rel="noopener">Ant Design 数据录入规范</a></li></ul>${key==='knowledge'?`<h3 class="a-section-title">查看异常状态</h3><div class="actions" style="justify-content:flex-start;flex-wrap:wrap">${['正常','空状态','加载中','加载失败','降级'].map(v=>b(v,'kb-state',v)).join('')}</div>`:''}<p class="a-disclosure">所有统计均来自本地示例数据，界面未连接任何业务接口。主题与列表密度可保存为本地偏好。</p>`,b('关闭','close'),true);
  }
  document.addEventListener('input',e=>{
    if(e.target.id==='a-kb-query'){k.q=e.target.value;k.page=1;refreshKBPane();const input=$('#a-kb-query');input.focus();input.setSelectionRange(k.q.length,k.q.length);}
    if(e.target.id==='a-user-query'){u.q=e.target.value;u.page=1;refreshUserPane();}
  });
  document.addEventListener('change',e=>{
    const id=e.target.id,v=e.target.value;
    const map={'a-kb-tier':'tier','a-kb-sort':'sort','a-kb-result':'result'};
    if(map[id]){k[map[id]]=v;k.page=1;refreshKBPane();}
    const umap={'a-user-dept':'dept','a-user-role':'role','a-user-management':'management'};
    if(umap[id]){u[umap[id]]=v;u.page=1;refreshUserPane();}
    if(e.target.matches('[data-user-select]')){const n=Number(e.target.dataset.userSelect);e.target.checked?u.selected.add(n):u.selected.delete(n);refreshUserPane();}
    if(id==='a-select-users'){getPeople().slice((u.page-1)*6,u.page*6).filter(p=>p.management==='普通用户').forEach(p=>e.target.checked?u.selected.add(p.id):u.selected.delete(p.id));refreshUserPane();}
  });
  document.addEventListener('click',e=>{
    const el=e.target.closest('[data-a]');if(!el||el.disabled)return;const a=el.dataset.a,v=el.dataset.value;
    if(a==='close')dialog.close();
    else if(a==='review')reviewNotes(v);
    else if(a==='kb-tab'){k.tab=v;k.q='';k.page=1;renderKnowledge();}
    else if(a==='kb-source'){k.source=v;k.page=1;if(k.tab==='检索记录')k.tab='内容台账';renderKnowledge();}
    else if(a==='kb-reset'){Object.assign(k,{q:'',tier:'全部方式',source:'全部资料',result:'全部结果',page:1,mode:'正常'});renderKnowledge();}
    else if(a==='kb-page'){k.page=Number(v);refreshKBPane();}
    else if(a==='kb-state'){k.mode=v;dialog.close();renderKnowledge();}
    else if(a==='snapshot-all'){k.source='全部资料';k.q='';renderKnowledge();}
    else if(a==='source-info')sourceInfo();
    else if(a==='doc-detail')showDoc(v);
    else if(a==='doc-chunks'){const d=docs.find(x=>x.id===Number(v));modal('检索分块示例',d.title,`<div class="a-chunk"><h3>${E(d.path)}</h3><small>分块 1 / ${d.chunks}</small><p>${E(d.body)}</p></div><p class="a-disclosure">当前仅绘制一个完整分块作为交互样例；正式页面应由分块接口展示全部结果。</p>`,b('返回资料','doc-detail',d.id),true);}
    else if(a==='origin'){const d=docs.find(x=>x.id===Number(v));modal('来源资料',d.origin,info([['资料名称',E(d.title)],['所属工作区',E(d.origin)],['维护方式','在来源业务页面编辑，知识库自动同步']])+`<p class="a-muted-note">原型只展示来源说明，正式落地时需使用来源记录 ID 定位到具体业务详情。</p>`,b('返回资料','doc-detail',d.id));}
    else if(a==='new-doc')editDoc();
    else if(a==='edit-doc')editDoc(v);
    else if(a==='query-detail')showQuery(v);
    else if(a==='reindex')modal('重建全部检索索引','用于资料缺失或索引异常时修复。',`<p class="a-muted-note">将重新整理当前组织知识源的检索副本。日常内容变更会自动更新，无需反复执行。</p><div class="a-confirm">共 ${sources.length-1} 类示例知识源。重建不修改业务原文，也不会扩大来源权限。</div>`,b('取消','close')+b('开始重建','apply-reindex','',true));
    else if(a==='apply-reindex'){dialog.close();k.mode='正常';k.lastIndexedAt='2026-10-19 09:50';renderKnowledge();toast('示例索引重建完成，业务原文保持原样（仅原型）');}
    else if(a==='refresh-snapshots')modal('刷新业务快照','重新计算当前组织的业务汇总。','<p class="a-muted-note">日常快照按日生成。刚更新一批业务数据时，可手动刷新，让灵思使用新的汇总数据。</p>',b('取消','close')+b('刷新快照','apply-snapshots','',true));
    else if(a==='apply-snapshots'){docs.filter(d=>d.type==='事实快照').forEach(d=>{d.asOf='2026-10-19 09:50';d.date=d.asOf;d.body=d.body.replace('02:00','09:50');});dialog.close();k.tab='事实快照';k.source='全部资料';renderKnowledge();toast('快照已刷新，数据截至时间已更新（仅原型）');}
    else if(a==='user-tab'){u.tab=v;u.page=1;renderUsers();}
    else if(a==='user-reset'){Object.assign(u,{q:'',dept:'全部部门',role:'全部角色',management:'全部身份',tab:'全部账号',page:1});u.selected.clear();renderUsers();}
    else if(a==='user-page'){u.page=Number(v);refreshUserPane();}
    else if(a==='user-detail')showUser(v);
    else if(a==='delete-user'){
      const p=people.find(x=>x.id===Number(v));if(!p||p.management!=='普通用户')return;
      modal('删除账号','核对目标账号后再删除。',`${info([['姓名',E(p.name)],['登录账号',E(p.username)],['当前状态',E(p.status)]])}<div class="a-confirm">删除后，该账号无法继续登录，也不能在此页恢复。如只需暂停使用，可先停用账号。</div><div class="a-form"><label>输入用户名确认删除<input name="confirmation" required autocomplete="off" placeholder="${E(p.username)}"></label></div>`,b('取消','user-detail',p.id)+`<button class="btn" data-danger type="submit">删除账号</button>`,false,values=>{if(values.confirmation!==p.username)return error('用户名不一致，请核对后重试。');people=people.filter(x=>x.id!==p.id);u.selected.delete(p.id);dialog.close();renderUsers();toast('账号已删除（仅原型）');});
    }
    else if(a==='new-user')editUser();
    else if(a==='edit-user')editUser(v);
    else if(a==='back-user')editUser(v,pendingUser?.values);
    else if(a==='confirm-role'){if(pendingUser?.id===Number(v))saveUser(people.find(p=>p.id===Number(v)),pendingUser.values);pendingUser=null;}
    else if(a==='user-status'){const p=people.find(x=>x.id===Number(v));confirmStatus([p.id],p.status==='停用'?'启用':'停用');}
    else if(a==='batch-status')confirmStatus([...u.selected],v);
    else if(a==='apply-status'){const [ids,target]=v.split('|');people.filter(p=>ids.split(',').map(Number).includes(p.id)&&p.management==='普通用户').forEach(p=>p.status=target);u.selected.clear();dialog.close();renderUsers();toast('账号已'+target+'（仅原型）');}
    else if(a==='clear-selection'){u.selected.clear();refreshUserPane();}
    else if(a==='import')importFlow();
    else if(a==='pending-accounts'){dialog.close();u.tab='待设密';u.page=1;renderUsers();showView('users');}
    else if(a==='role-help')modal('角色与权限','业务角色和管理身份独立维护。',`<h3 class="a-section-title">业务主角色</h3><p class="a-muted-note">决定活动、排班、档案等业务权限。角色草稿需预览发布后才会生效，账号替换角色也需预览确认。</p><h3 class="a-section-title">管理身份</h3><p class="a-muted-note">所有者或有效的管理员委派提供治理能力。部门或角色名称不会自动授予账号管理能力。</p><div class="a-confirm">现有系统的“角色与权限”工作区继续负责角色发布、管理员委派与治理记录；本轮原型不重绘该工作区。</div>`,b('关闭','close'),true);
    else if(a==='user-permissions'){const p=people.find(x=>x.id===Number(v));modal('有效权限',p.name+' · '+p.role,`${info([['业务授权来源',E(p.role)+' · 已发布配置'],['管理能力来源',E(p.management)],['业务授权范围','本组织']])}<h3 class="a-section-title">可执行的业务操作（示例）</h3><ul class="a-mini-list">${roleGrants[p.role].map(x=>`<li><span>${E(x)}</span>${tag('已授予','green')}</li>`).join('')}</ul><p class="a-disclosure">部门归属不会自动授予管理能力。以上为原型示例，正式权限以组织已发布配置为准。</p>`,b('返回账号','user-detail',p.id),true);}
    else if(a==='settings-tab'){s.tab=v;renderSettings();}
    else if(a==='route'){dialog.close();route(v);}
    else if(a==='theme'){setTheme(v);s.theme=v;renderSettings();}
    else if(a==='density'){s.density=v;s.dirty=v!==s.savedDensity;renderSettings();}
    else if(a==='discard-preferences'){s.density=s.savedDensity;s.dirty=false;renderSettings();}
    else if(a==='save-preferences'){persistPreferences();toast('偏好已保存到当前浏览器');}
    else if(a==='leave-discard'){s.density=s.savedDensity;s.dirty=false;dialog.close();navigate(v);renderSettings();}
    else if(a==='leave-save'){persistPreferences();dialog.close();navigate(v);}
    else if(a==='system-refresh'){s.lastCheck='2026-10-19 09:50:00';renderSettings();toast('系统状态已刷新（示例响应）');}
    else if(a==='diagnose'){s.diag='ready';s.lastCheck='2026-10-19 09:50:00';renderSettings();toast('基础连接检查通过；磁盘与备份未检测（仅原型）');}
  });
  // Protect unsaved preference changes across the original prototype's navigation.
  document.addEventListener('click',e=>{
    const nav=e.target.closest('[data-view]');if(!nav)return;
    if(!hosts.settings.hidden&&s.dirty&&nav.dataset.view!=='settings'){e.preventDefault();e.stopImmediatePropagation();route(nav.dataset.view);return;}
    if(['knowledge','users','settings'].includes(nav.dataset.view)){
      const url=new URL(location.href);url.searchParams.set('view',nav.dataset.view);url.searchParams.delete('module');url.searchParams.delete('page');history.replaceState(null,'',url);
      if(nav.dataset.view==='settings')renderSettings();
    }
  },true);
  $('#theme-toggle').addEventListener('click',()=>renderSettings());
  try{if(localStorage.getItem('pams-v6-review-density')==='紧凑')s.density=s.savedDensity=u.density='紧凑';}catch{}
  renderKnowledge();renderUsers();renderSettings();
  const initial=new URLSearchParams(location.search).get('view')||document.body.dataset.entry;if(hosts[initial])showView(initial,false);
})();
