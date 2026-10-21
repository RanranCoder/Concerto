/* V6 activity design review. All edits are in-memory examples; no application API is called. */
(() => {
  'use strict';
  const $ = (s, r = document) => r.querySelector(s), E = esc;
  const listHost = $('#activities'), detailHost = $('#detail');
  listHost.classList.add('ev-scope'); detailHost.classList.add('ev-scope');
  const phases = ['已下达','排期中','策划审核','执行中','已完成','已归档'];
  const types = ['党课','主题团日','主题党日','竞赛','志愿服务','讲座','会议','其他'];
  const tabs = [['basic','基本信息'],['materials','材料'],['plan','策划书'],['agenda','议程'],['seat','座位表'],['score','评分'],['signin','签到'],['articles','推文']];
  const I = (name) => name === 'edit' ? '<svg aria-hidden="true" viewBox="0 0 24 24"><path d="m15 4 5 5M4 16 16 4a2 2 0 0 1 4 4L8 20H4z"/></svg>' : icon(name);
  const tag = (text, tone = '') => `<span class="ev-tag ${tone}">${E(text)}</span>`;
  const statusTag = text => tag(text, ({'已下达':'amber','排期中':'blue','策划审核':'amber','执行中':'blue','已完成':'green','已通过':'green','待审核':'amber','待签到':'amber','已签到':'green','草稿':'','已驳回':'red','已发布':'green'})[text] || '');
  const B = (text, action, value = '', primary = false, extra = '') => `<button type="button" class="btn${primary?' primary':''}" data-ev="${action}" data-value="${E(value)}" ${extra}>${text}</button>`;
  const L = (text, action, value = '') => `<button type="button" class="link" data-ev="${action}" data-value="${E(value)}">${text}</button>`;
  const opts = (values, selected) => values.map(v => `<option value="${E(v)}"${v===selected?' selected':''}>${E(v)}</option>`).join('');
  const head = (title, desc='', actions='') => `<header class="ev-head"><div><h2>${E(title)}</h2>${desc?`<p>${E(desc)}</p>`:''}</div><div class="ev-actions">${actions}</div></header>`;
  const panel = (title, desc, body, actions='') => `<section class="ev-panel">${head(title,desc,actions)}${body}</section>`;
  const info = pairs => `<dl class="ev-side-dl">${pairs.map(([k,v])=>`<div><dt>${E(k)}</dt><dd>${v}</dd></div>`).join('')}</dl>`;
  const progress = (done,total) => `<div class="ev-progress" role="progressbar" aria-label="任务完成进度" aria-valuemin="0" aria-valuemax="${total}" aria-valuenow="${done}"><span style="width:${total?done/total*100:0}%"></span></div>`;
  const empty = (title, desc, action='') => `<div class="ev-empty">${I('folder')}<h3>${E(title)}</h3><p>${E(desc)}</p>${action}</div>`;
  const scrollTable = (headers, rows, cls='') => {
    const template=document.createElement('template');
    template.innerHTML=`<div class="ev-scroll"><table class="ev-table ${cls}"><thead><tr>${headers.map(h=>`<th scope="col">${h}</th>`).join('')}</tr></thead><tbody>${rows}</tbody></table></div>`;
    const labels=[...template.content.querySelectorAll('thead th')].map(th=>th.textContent.replace(/[↑↓]/g,'').trim());
    template.content.querySelectorAll('tbody tr').forEach(row=>[...row.children].forEach((td,i)=>td.dataset.label=labels[i]||''));
    return template.innerHTML;
  };
  let nextId = 20;
  const sample = activities.map((a,i) => ({...a, status:['执行中','策划审核','排期中','已下达','已归档','已完成'][i], type:['党课','主题党日','会议','志愿服务','其他','讲座'][i], theme:['坚定理想信念，筑牢思想根基','深学细悟，笃行实干','做好秋季学期组织工作','志愿同行，服务校园','凝聚新力量，启航新学期','学习榜样精神，汲取奋进力量'][i], host:'周同学', audience:'本院党员及入党积极分子', description:'围绕活动主题开展集中学习与交流，结合学院实际落实组织分工。通过专题分享、小组讨论与总结交流，明确后续学习任务，并做好活动记录和材料整理。', data:null}));
  function makeData(a){
    return {
      plan:{status:a.status==='策划审核'?'待审核':a.status==='已下达'?'草稿':'已通过',version:3,updated:'2026-10-12 16:40',purpose:'加强理论学习，提升思想认识，推动学习成果转化为服务师生、参与学院建设的具体行动。',background:'为持续推进学院组织建设，结合本学期工作安排，组织开展本次集中学习与交流活动。',budget:'资料印制 120 元，场地布置 80 元，合计 200 元。'},
      materials:[{id:1,name:'活动策划书（定稿）.docx',type:'策划书',ext:'DOCX',size:'48 KB',owner:a.owner,date:'2026-10-12 16:40',body:'一、活动背景\n结合本学期工作安排开展主题学习。\n二、活动目的\n加强理论学习，提升思想认识。\n三、组织分工\n负责部门统筹活动，负责人协调会务与材料整理。'},{id:2,name:'参会人员名单.xlsx',type:'名单',ext:'XLSX',size:'26 KB',owner:'周同学',date:'2026-10-12 15:20',body:'姓名,班级,身份\n陈同学,计科2301,党员\n林同学,软件2302,预备党员\n王同学,计科2301,积极分子'},{id:3,name:'会务准备清单.pdf',type:'其他',ext:'PDF',size:'132 KB',owner:a.owner,date:'2026-10-11 10:00',body:'会务准备清单\n场地设备调试\n参会名单核对\n活动材料印制\n工作人员分工确认'}],
      agenda:[{id:1,time:'18:30',duration:30,title:'参会签到与入场',owner:'周同学',remark:'核对名单，引导参会人员按区域就座。'},{id:2,time:'19:00',duration:5,title:'主持人开场',owner:'周同学',remark:'介绍本次活动主题、议程及主讲嘉宾。'},{id:3,time:'19:05',duration:45,title:'主题学习与专题分享',owner:'张老师',remark:'围绕活动主题开展学习，结合实际案例分享。'},{id:4,time:'19:50',duration:25,title:'小组讨论与交流',owner:a.owner,remark:'各组代表交流学习感受，记录讨论要点。'},{id:5,time:'20:15',duration:15,title:'总结与合影',owner:a.owner,remark:'总结本次活动，布置后续学习任务。'}],
      seats:Array.from({length:48},(_,i)=>({name:i<4?['张老师','李老师','王老师','赵老师'][i]:i<42?['陈同学','林同学','周同学','王同学','刘同学','李同学'][i%6]:'',zone:i<8?'嘉宾区':'参会区'})),
      rules:[{name:'内容质量',max:40},{name:'现场表现',max:35},{name:'协作配合',max:25}],
      scores:[{name:'第一学习小组',group:'计科2301',dims:[37,32,24]},{name:'第二学习小组',group:'软件2302',dims:[35,33,23]},{name:'第三学习小组',group:'计科2302',dims:[36,31,24]},{name:'第四学习小组',group:'软件2301',dims:[34,30,23]}],
      roster:Array.from({length:12},(_,i)=>({id:i+1,name:['陈同学','林同学','周同学','王同学','刘同学','李同学','赵同学','吴同学','郑同学','孙同学','何同学','许同学'][i],no:'2023'+String(1001+i),class:['计科2301','软件2302','计科2302'][i%3],identity:i<3?'党员':i<7?'预备党员':'积极分子',signed:i<8,time:i<8?'18:'+String(31+i*2):'',method:'扫码'})),
      articles:[{id:1,title:a.title+'｜预热',type:'预热',status:'已发布',author:'林同学',deadline:'2026-10-12 18:00',body:'围绕本次活动的主题、时间和参与方式，向师生介绍活动安排，邀请大家关注与参与。'},{id:2,title:a.title+'｜活动报道',type:'报道',status:'待审核',author:'周同学',deadline:'2026-10-15 12:00',body:'学院组织开展主题学习活动，参会师生围绕活动主题进行学习与交流。活动进一步明确了学习任务与工作方向。'}],
      logs:[['更新策划书至 v3',a.owner,'10月12日 16:40'],['确认活动议程','周同学','10月12日 15:20'],['创建活动并明确分工',a.owner,'10月10日 09:30']]
    };
  }
  sample.forEach((a,i)=>{a.done=[8,3,2,0,10,6][i];a.data=makeData(a);});
  const s = {query:'',phase:'全部活动',type:'全部类型',department:'全部负责部门',density:'standard',sort:1,page:1,size:6,tab:'basic',scenario:'正常',materialType:'全部材料',materialQuery:'',signinTab:'应签名单',signinQuery:'',signinFilter:'全部状态',seatView:'座位图',selectedSeat:0,planVersion:'v3 · 当前版本'};
  let active = sample[1], submitAction = null, toastTimer, trigger = null;
  const dialog = document.createElement('dialog'); dialog.id='ev-dialog';dialog.className='ev-dialog';document.body.append(dialog);
  const toast = document.createElement('div');toast.className='ev-toast';toast.role='status';toast.hidden=true;document.body.append(toast);
  function notify(message){toast.textContent=message;toast.hidden=false;clearTimeout(toastTimer);toastTimer=setTimeout(()=>toast.hidden=true,3800);}
  let initialFormState='',closingDrawer=false;
  const formState=()=>JSON.stringify([...new FormData($('#ev-dialog-form'))].map(([key,value])=>[key,value instanceof File?value.name+':'+value.size:value]));
  function requestClose(){
    if(dialog.classList.contains('ev-drawer')&&submitAction&&initialFormState!==formState()){
      $('.ev-unsaved',dialog).hidden=false;$('[data-ev="continue-edit"]',dialog).focus();return;
    }
    closeDialog();
  }
  function closeDialog(){
    if(closingDrawer)return;
    if(dialog.classList.contains('ev-drawer')&&!matchMedia('(prefers-reduced-motion: reduce)').matches){closingDrawer=true;dialog.classList.add('ev-drawer-closing');setTimeout(()=>{dialog.close();closingDrawer=false;},160);}else dialog.close();
  }
  function openDialog(title,desc,body,onSubmit=null,submitText='保存原型',danger=false,mode='auto'){
    trigger=document.activeElement;submitAction=onSubmit;
    const drawer=mode==='drawer'||mode==='auto'&&!!onSubmit&&body.includes('class="ev-form"');
    const drawerContext=/^(新建活动|编辑活动)$/.test(title)?'活动管理':active.title;
    dialog.className='ev-dialog'+(drawer?' ev-drawer':'');
    dialog.innerHTML=`<form id="ev-dialog-form"><header><div>${drawer?'<span class="ev-drawer-context">'+E(active.title)+'</span>':''}<h2 id="ev-dialog-title">${E(title)}</h2><p>${E(desc)}</p></div><button type="button" class="icon-btn" data-ev="close" aria-label="${drawer?'关闭侧边栏':'关闭弹窗'}">${I('close')}</button></header><div class="ev-dialog-body"><div id="ev-form-error" class="ev-form-error" role="alert" hidden></div>${body}</div><div class="ev-unsaved" hidden role="alert"><div><strong>还有未保存的修改</strong><p>继续编辑，或放弃本次修改后关闭。</p></div>${B('继续编辑','continue-edit')}${B('放弃修改','discard-edit')}</div><footer>${drawer&&onSubmit?'<span class="ev-drawer-footnote">保存后更新当前活动</span>':''}${B(onSubmit?'取消':'关闭','close')}${onSubmit?`<button class="btn ${danger?'ev-danger-btn':'primary'}" type="submit">${E(submitText)}</button>`:''}</footer></form>`;
    dialog.setAttribute('aria-labelledby','ev-dialog-title');if(!dialog.open)dialog.showModal();
    initialFormState=formState();
    if(drawer){
      $('.ev-drawer-context',dialog).textContent=drawerContext;
      const sections=/^(新建活动|编辑活动)$/.test(title)?[['title','活动基本信息','明确活动名称、主题与负责部门。'],['date','时间与地点','确认活动日期和举办地点。'],['organizer','组织与人员','明确组织单位、负责人和参与范围。'],['description','活动说明','补充活动内容和相关要求。']]:/^(创建推文任务|编辑推文任务)$/.test(title)?[['title','任务安排','明确稿件类型、负责人和截止时间。'],['body','稿件内容','撰写正文，检查关键信息。'],['status','审核与发布','核对审核状态和发布地址。']]:[];
      sections.forEach(([name,label,description])=>{const field=$(`[name="${name}"]`,dialog)?.closest('label');if(field)field.insertAdjacentHTML('beforebegin',`<section class="ev-form-section wide"><h3>${label}</h3><p>${description}</p></section>`);});
    }
  }
  dialog.addEventListener('cancel',e=>{e.preventDefault();requestClose();});
  dialog.addEventListener('close',()=>{if(trigger?.isConnected)trigger.focus();});
  dialog.addEventListener('submit',e=>{e.preventDefault();if(!submitAction)return;const f=new FormData(e.target);try{if(submitAction(f)!==false){dialog.close();notify('已更新本次预览，刷新页面后恢复示例数据');}}catch(error){const box=$('#ev-form-error');box.hidden=false;box.textContent=error.message;}});
  function field(label,name,value='',type='text',required=false,wide=false){return `<label class="${wide?'wide':''}">${E(label)}${type==='textarea'?`<textarea name="${name}"${required?' required':''}>${E(value)}</textarea>`:`<input name="${name}" type="${type}" value="${E(value)}"${required?' required':''}${type==='number'?' min="0" step="1"':''}>`}</label>`;}
  const selectField = (label,name,values,value,wide=false) => `<label class="${wide?'wide':''}">${E(label)}<select name="${name}">${opts(values,value)}</select></label>`;
  function review(){return `<div class="ev-review"><span>设计评审原型 · 示例数据，操作仅在本次预览生效</span><label>页面状态 <select aria-label="切换原型页面状态" id="ev-scenario">${opts(['正常','空数据','加载中','加载失败'],s.scenario)}</select></label></div>`;}
  function scenarioBody(retry){
    if(s.scenario==='加载中')return `<section class="ev-panel" aria-busy="true" aria-label="正在加载"><div class="ev-skeleton">${'<i></i>'.repeat(7)}</div><div class="ev-foot">正在加载数据… ${L('结束加载演示',retry)}</div></section>`;
    if(s.scenario==='加载失败')return `<section class="ev-panel">${empty('暂时无法加载','连接中断，请重试。当前筛选条件会保留。',B('重新加载',retry,'',true))}</section>`;
    return '';
  }
  function filtered(){return sample.filter(a=>(s.phase==='全部活动'||a.status===s.phase)&&(s.type==='全部类型'||a.type===s.type)&&(s.department==='全部负责部门'||a.department===s.department)&&(!s.query||[a.title,a.theme,a.owner].join(' ').toLowerCase().includes(s.query.toLowerCase()))).sort((a,b)=>s.sort*a.date.localeCompare(b.date));}
  function listMarkup(){
    const stats=[['全部活动',sample.length,'当前组织的活动'],['策划审核',sample.filter(a=>a.status==='策划审核').length,'待处理的策划'],['执行中',sample.filter(a=>a.status==='执行中').length,'跟进执行进度'],['已归档',sample.filter(a=>a.status==='已归档').length,'查看历史材料']];
    return `<div class="heading"><div><h1 id="activities-title">活动管理</h1><p>从活动下达到材料归档，协同完成每一步</p></div>${B(I('plus')+'新建活动','new-activity','',true)}</div><div class="ev-summary">${stats.map(([label,value,note],i)=>`<button class="ev-stat ${i===1?'attention':''}" data-ev="phase" data-value="${label}" aria-label="筛选${label}，${value}项"><span>${label}</span><strong>${value}</strong><small>${note}</small></button>`).join('')}</div><section class="ev-panel"><div class="ev-filter-tabs" role="group" aria-label="按活动状态筛选">${['全部活动',...phases].map(p=>`<button data-ev="phase" data-value="${p}" aria-pressed="${s.phase===p}">${p}<small>${p==='全部活动'?sample.length:sample.filter(a=>a.status===p).length}</small></button>`).join('')}</div><div class="ev-toolbar"><label class="ev-field ev-search">${I('search')}<input id="ev-query" type="search" aria-label="搜索活动名称、主题或负责人" placeholder="搜索活动名称 / 主题 / 负责人" value="${E(s.query)}"></label><label class="ev-field"><span class="sr-only">活动类型</span><select id="ev-type">${opts(['全部类型',...types],s.type)}</select></label><label class="ev-field"><span class="sr-only">负责部门</span><select id="ev-department">${opts(['全部负责部门',...new Set(sample.map(a=>a.department))],s.department)}</select></label>${L('清除筛选','reset')}<div class="segmented ev-push" role="group" aria-label="列表密度"><button data-ev="density" data-value="standard" aria-pressed="${s.density==='standard'}">标准</button><button data-ev="density" data-value="compact" aria-pressed="${s.density==='compact'}">紧凑</button></div></div><div id="ev-list-body"></div></section>${review()}`;
  }
  function renderList(){listHost.innerHTML=listMarkup();renderRows();}
  function renderRows(){
    const host=$('#ev-list-body');if(!host)return;
    const exceptional=scenarioBody('retry-list');if(exceptional){host.innerHTML=exceptional;return;}
    const results=s.scenario==='空数据'?[]:filtered(), pageCount=Math.max(1,Math.ceil(results.length/s.size));s.page=Math.min(s.page,pageCount);
    const rows=results.slice((s.page-1)*s.size,s.page*s.size).map(a=>`<tr><td><button class="ev-name" data-ev="open" data-value="${a.id}">${E(a.title)}</button><small>${E(a.department)} / ${E(a.place)}</small></td><td>${E(a.type)}</td><td>${statusTag(a.status)}</td><td><span class="ev-count">${a.done} / ${a.total}</span>${progress(a.done,a.total)}</td><td><span class="ev-count">${a.data.materials.length} 份</span></td><td><span class="person"><span class="avatar">${E(a.owner.slice(0,1))}</span>${E(a.owner)}</span></td><td>${E(a.date)}</td><td><div class="ev-actions">${L('详情','open',a.id)}${L('编辑','edit-activity',a.id)}</div></td></tr>`).join('');
    host.innerHTML=results.length?scrollTable(['活动名称','类型','状态','任务进度','材料','负责人',`<button class="sort-button" data-ev="sort" aria-label="按活动日期${s.sort===1?'降序':'升序'}排列">活动日期 ${s.sort===1?'↑':'↓'}</button>`,'操作'],rows,s.density==='compact'?'compact':'')+`<div class="ev-foot"><span aria-live="polite">共 ${results.length} 项活动${s.query||s.phase!=='全部活动'||s.type!=='全部类型'||s.department!=='全部负责部门'?' · 已筛选':''}</span><div class="ev-page-controls"><button data-ev="page" data-value="${s.page-1}" ${s.page===1?'disabled':''} aria-label="上一页">‹</button><button aria-current="page" aria-label="第${s.page}页">${s.page}</button><button data-ev="page" data-value="${s.page+1}" ${s.page===pageCount?'disabled':''} aria-label="下一页">›</button><label><span class="sr-only">每页条数</span><select id="ev-size">${[3,6,10].map(n=>`<option value="${n}"${n===s.size?' selected':''}>${n} 项 / 页</option>`).join('')}</select></label></div></div>`:empty('没有匹配的活动','试着调整搜索词或筛选条件，也可以创建一项活动。',B('清除筛选','reset')+' '+B('新建活动','new-activity','',true));
    const dateHeader=host.querySelector('[data-ev="sort"]')?.closest('th');if(dateHeader)dateHeader.setAttribute('aria-sort',s.sort===1?'ascending':'descending');
  }
  function counts(key){const d=active.data;return ({materials:d.materials.length,agenda:d.agenda.length,articles:d.articles.length})[key];}
  function detailShell(){
    const a=active,idx=phases.indexOf(a.status);
    detailHost.innerHTML=`<button class="ev-back" data-view="activities">${I('left')}返回活动列表</button><header class="ev-identity"><div>${statusTag(a.status)}<span class="ev-code">ACT-2026-${String(a.id+1).padStart(3,'0')}</span><h1 id="detail-title">${E(a.title)}</h1><div class="ev-meta"><span>${I('calendar')}${E(a.date)}　${E(a.time)}</span><span>${I('folder')}${E(a.place)}</span><span>${I('users')}${E(a.department)} / ${E(a.owner)}</span></div></div><div class="ev-actions">${B(I('edit')+'编辑活动','edit-activity',a.id)}${idx<phases.length-1?B('推进至'+phases[idx+1],'advance','',true):tag('材料已归档')}</div></header><div class="ev-stage-container"><div class="ev-lifecycle" role="list" aria-label="活动生命周期">${phases.map((p,i)=>`<div role="listitem" class="ev-stage ${i<idx?'done':i===idx?'current':''}"${i===idx?' aria-current="step"':''}><i>${i<idx?'✓':i+1}</i><span>${p}</span></div>`).join('')}</div></div><nav class="ev-detail-nav" role="tablist" aria-label="活动详情">${tabs.map(([key,name])=>`<button role="tab" id="ev-tab-${key}" data-ev-tab="${key}" aria-controls="ev-detail-panel" aria-selected="${key===s.tab}" tabindex="${key===s.tab?0:-1}">${name}${counts(key)!==undefined?`<span class="ev-tab-count">${counts(key)}</span>`:''}</button>`).join('')}</nav><div id="ev-detail-panel" role="tabpanel" aria-labelledby="ev-tab-${s.tab}" tabindex="0"></div>${review()}`;
    renderPane();
    const nav=$('.ev-detail-nav'),selected=$('[aria-selected="true"]',nav);
    if(nav&&selected&&nav.scrollWidth>nav.clientWidth)nav.scrollLeft=Math.max(0,selected.offsetLeft-nav.offsetLeft-16);
  }
  function activityEditor(id){
    const a=sample.find(x=>x.id===Number(id));
    openDialog(a?'编辑活动':'新建活动','保存仅更新原型示例；正式实现沿用当前字段与权限。',`<div class="ev-form">${field('活动名称 *','title',a?.title||'','text',true,true)}${selectField('活动类型','type',types,a?.type||'主题党日')}${selectField('负责部门','department',['组织部','党建办公室','文秘部','青年科技部'],a?.department||'党建办公室')}${field('活动主题','theme',a?.theme||'','text',false,true)}${field('开始日期 *','date',a?.date||'2026-10-20','date',true)}${field('结束日期 *','endDate',a?.endDate||a?.date||'2026-10-20','date',true)}${field('活动地点 *','place',a?.place||'','text',true)}${field('组织单位','organizer',a?.organizer||a?.department||'党建办公室')}${field('负责人 *','owner',a?.owner||'','text',true)}${field('主持人','host',a?.host||'')}${field('面向对象','audience',a?.audience||'')}${field('活动描述','description',a?.description||'','textarea',false,true)}</div>`,f=>{
      const v=Object.fromEntries(f);if(v.endDate<v.date)throw Error('结束日期不能早于开始日期');['title','owner','place'].forEach(k=>{if(!v[k].trim())throw Error('活动名称、负责人和地点不能为空');});
      if(a){Object.assign(a,v);a.data.logs.unshift(['编辑活动信息',v.owner,'刚刚']);}
      else {const n={...v,id:nextId++,status:'已下达',time:'19:00 — 20:30',done:0,total:0};n.data=makeData(n);n.data.materials=[];n.data.agenda=[];n.data.articles=[];n.data.roster=[];n.data.scores=[];n.data.rules=[];n.data.seats=n.data.seats.map(x=>({...x,name:''}));n.data.logs=[['创建活动',v.owner,'刚刚']];sample.unshift(n);}
      if(!a)Object.assign(s,{query:'',phase:'全部活动',type:'全部类型',department:'全部负责部门',page:1,sort:-1,scenario:'正常'});
      renderList();if(!detailHost.hidden)detailShell();
    },a?'保存修改':'创建活动');
  }
  function basic(){
    const a=active,d=a.data;
    const fields=[['活动类型',E(a.type)],['活动主题',E(a.theme||'未填写')],['活动状态',statusTag(a.status)],['组织单位',E(a.organizer||a.department)],['负责人',E(a.owner)],['主持人',E(a.host||'未填写')],['活动日期',E(a.date)],['活动时间',E(a.time)],['面向对象',E(a.audience||'未填写')]];
    return `<div class="ev-columns"><div class="ev-stack">${panel('活动信息','组织安排与基本资料',`<dl class="ev-info ev-pad">${fields.map(([k,v])=>`<div><dt>${k}</dt><dd>${v}</dd></div>`).join('')}<div class="wide"><dt>活动描述</dt><dd>${E(a.description||'暂无描述')}</dd></div></dl>`,L('编辑信息','edit-activity',a.id))}${panel('执行准备','按活动流程检查准备情况',`<ul class="ev-checklist">${[['活动策划书',d.plan.status==='已通过',d.plan.status==='待审核'?'策划已提交，等待负责人审核':'确认活动目标、分工及应急安排','plan'],['会场与议程',d.agenda.length>0,`${d.agenda.length} 个环节，核对主持人与会务安排`,'agenda'],['参会与签到',d.roster.length>0,`${d.roster.length} 人在应签名单中，活动前核对人员`,'signin'],['宣传与归档',d.articles.every(x=>x.status==='已发布')&&d.articles.length>0,'跟进预热、报道及相关材料','articles']].map(([title,done,note,tab])=>`<li><span class="ev-check ${done?'':'pending'}">${done?'✓':'!'}</span><div><strong>${title}</strong><p>${E(note)}</p></div>${L('查看','tab',tab)}</li>`).join('')}</ul>`)}${panel('任务甘特图','准备任务摘要，完整任务在独立甘特图中管理',`<div class="ev-scroll"><div class="ev-gantt"><div class="ev-gantt-date">准备事项</div>${['10/10','10/11','10/12','10/13','10/14'].map(x=>`<div class="ev-gantt-date">${x}</div>`).join('')}<div>策划与议程</div><div class="ev-gantt-bar complete" style="grid-column:2/5">已完成</div><div style="grid-column:5/7"></div><div>场地与名单</div><div style="grid-column:2/3"></div><div class="ev-gantt-bar" style="grid-column:3/6">准备中</div><div></div><div>宣传与材料</div><div style="grid-column:2/4"></div><div class="ev-gantt-bar" style="grid-column:4/7">跟进中</div></div></div>`,L('查看完整任务','gantt'))}</div><aside class="ev-stack">${panel('准备进度','',`<div class="ev-pad">${info([['任务完成',`${a.done} / ${a.total} 项`],['关联材料',`${d.materials.length} 份`],['策划书',statusTag(d.plan.status)],['活动负责人',E(a.owner)]])}</div>`)}<div class="ev-note ${a.status==='策划审核'?'amber':'neutral'}">${a.status==='策划审核'?'策划待审核。请先核对活动安排、预算与应急预案，再推进到执行阶段。':'活动阶段与各模块数据分别维护。推进阶段前，请核对相应准备事项。'}</div>${panel('近期动态','',`<ul class="ev-audit ev-pad">${d.logs.slice(0,5).map(([title,name,date])=>`<li>${E(title)}<small>${E(name)} / ${E(date)}</small></li>`).join('')}</ul>`)}</aside></div>`;
  }
  function materials(){
    const d=active.data;
    const rows=d.materials.filter(m=>(s.materialType==='全部材料'||m.type===s.materialType)&&m.name.includes(s.materialQuery)).map(m=>`<tr><td><div class="ev-filecell"><span class="ev-filemark">${E(m.ext)}</span><div><button class="ev-name" data-ev="file" data-value="${m.id}">${E(m.name)}</button><small>${E(m.size)} / 关联本活动</small></div></div></td><td>${E(m.type)}</td><td>${E(m.owner)}</td><td>${E(m.date)}</td><td><div class="ev-actions">${L('预览','file',m.id)}${L('下载示例','download-file',m.id)}</div></td></tr>`).join('');
    return panel('活动材料','集中查看当前活动关联的文件',`<div class="ev-toolbar"><label class="ev-field ev-search">${I('search')}<input id="ev-material-query" aria-label="搜索材料名称" placeholder="搜索材料名称" value="${E(s.materialQuery)}"></label><label class="ev-field"><span class="sr-only">材料类型</span><select id="ev-material-type">${opts(['全部材料','策划书','名单','其他','照片','新闻稿'],s.materialType)}</select></label><span class="ev-muted ev-push">${d.materials.length} 份关联材料</span></div><div id="ev-material-results">${rows?scrollTable(['文件名称','材料类型','上传人','更新时间','操作'],rows):empty('暂无匹配材料','更换材料类型或上传当前活动需要的文件。',B('上传材料','upload','',true))}</div><div class="ev-foot">文件归属与分类沿用材料库，上传时自动关联当前活动。</div>`,B(I('plus')+'上传材料','upload','',true));
  }
  function plan(){
    if(window.PlanStudio)return window.PlanStudio.markup(active);
    const a=active,p=a.data.plan,old=s.planVersion.startsWith('v2');
    return `<section class="ev-panel ev-plan-workspace">${head('活动策划书','按原稿阅读、检查与审核',`<label class="ev-field"><span class="sr-only">策划书版本</span><select id="ev-plan-version">${opts([`v${p.version} · 当前版本`,'v2 · 历史版本'],s.planVersion)}</select></label>${B('打开 Word','word-import')}${B('下载 Word','word-download')}${B('审核与版本','plan-details')}${B('编辑策划','edit-plan','',true)}`)}${old?`<div class="ev-review-banner">${tag('历史版本')}<span>当前查看 v2，仅供对照。</span><div class="ev-actions">${B('返回当前版本','current-plan')}</div></div>`:`<div class="ev-review-banner">${statusTag(p.status)}<span>v${p.version} / ${E(a.owner)} 更新于 ${E(p.updated)}</span><div class="ev-actions">${p.status==='待审核'?B('驳回','reject-plan')+B('通过审核','approve-plan','',true):p.status==='草稿'||p.status==='已驳回'?B('提交审核','submit-plan','',true):''}</div></div>`}${ActivityWord.markup(a,p,old)}</section>`;
  }
  function agenda(){
    const d=active.data,total=d.agenda.reduce((n,x)=>n+x.duration,0);
    return `<div class="ev-columns">${panel('活动议程',`${d.agenda.length} 个环节 / 共 ${total} 分钟`,d.agenda.length?`<div class="ev-timeline">${d.agenda.map((x,i)=>`<div class="ev-agenda-item"><div class="ev-agenda-time">${E(x.time)}<br><small>${x.duration} 分钟</small></div><div class="ev-agenda-track"><i></i></div><div class="ev-agenda-content"><div class="ev-agenda-title"><h3>${E(x.title)}</h3><div class="ev-actions">${L('编辑','edit-agenda',x.id)}${i>0?L('上移','up-agenda',x.id):''}${L('删除','delete-agenda',x.id)}</div></div><p>${E(x.remark)}</p><p>负责人：${E(x.owner)}</p></div></div>`).join('')}</div>`:empty('尚未安排议程','添加活动环节，明确每一步的时间与负责人。',B('添加环节','new-agenda','',true)),B(I('plus')+'添加环节','new-agenda','',true))}<aside class="ev-stack">${panel('会务安排','',`<div class="ev-pad">${info([['活动日期',E(active.date)],['主持人',E(active.host||'待确认')],['活动地点',E(active.place)],['议程时长',total+' 分钟']])}</div>`)}<div class="ev-note neutral">时间、环节和负责人集中呈现，便于现场查阅。调整顺序后，请同步核对各环节时间。</div>${panel('议程文件','',`<div class="ev-pad"><p class="ev-muted">将当前议程导出为文本示例，供评审信息结构。</p><div style="margin-top:12px">${B('导出议程示例','download-agenda')}</div></div>`)}</aside></div>`;
  }
  function seats(){
    const d=active.data,assigned=d.seats.filter(x=>x.name).length,seat=d.seats[s.selectedSeat];
    const map=`<div class="ev-scroll"><div class="ev-seat-map"><div class="ev-stage-platform">讲台 / 主席台</div>${Array.from({length:6},(_,r)=>`<div class="ev-seat-row"><b>${r+1} 排</b>${d.seats.slice(r*8,r*8+8).map((x,c)=>`<button class="ev-seat ${x.zone==='嘉宾区'?'speaker':''} ${!x.name?'ev-vacant':''} ${c===3?'aisle':''} ${r*8+c===s.selectedSeat?'selected':''}" data-ev="seat" data-value="${r*8+c}" aria-pressed="${r*8+c===s.selectedSeat}" aria-label="${r+1}排${c+1}座，${E(x.name||'空座')}，${x.zone}">${x.name?E(x.name):'空座'}</button>`).join('')}</div>`).join('')}<div class="ev-seat-legend"><span><i class="guest"></i>嘉宾区</span><span><i></i>参会区</span><span><i class="vacant"></i>空座</span></div></div></div>`;
    const rows=scrollTable(['座位','姓名','区域','操作'],d.seats.map((x,i)=>`<tr><td>${Math.floor(i/8)+1} 排 ${i%8+1} 座</td><td>${E(x.name||'未分配')}</td><td>${E(x.zone)}</td><td>${L('分配座位','seat',i)}</td></tr>`).join(''));
    return `<div class="ev-columns">${panel('座位安排',`共 48 座 / 已分配 ${assigned} 座 / 空余 ${48-assigned} 座`,s.seatView==='座位图'?map:rows,`<div class="segmented" role="group" aria-label="座位视图">${['座位图','名单'].map(v=>`<button data-ev="seat-view" data-value="${v}" aria-pressed="${s.seatView===v}">${v}</button>`).join('')}</div>`)}<aside class="ev-stack">${panel('当前座位',`${Math.floor(s.selectedSeat/8)+1} 排 ${s.selectedSeat%8+1} 座`, `<div class="ev-pad">${info([['所属区域',E(seat.zone)],['已分配人员',E(seat.name||'未分配')]])}<div class="ev-actions" style="margin-top:20px">${B('分配人员','assign-seat','',true)}${seat.name?L('清空座位','clear-seat'):''}</div></div>`)}${panel('布局设置','',`<div class="ev-pad">${info([['布局类型','剧院式'],['排数 / 每排座位','6 排 / 8 座'],['过道','第 4 座后']])}<p class="ev-muted" style="margin-top:16px">点击座位后分配人员；名单视图适合逐项核对。正式布局编辑保留现有画布与表格编辑能力。</p></div>`)}<div class="ev-note neutral">本版以浅色底区分嘉宾区与参会区，同时提供区域文字与人员姓名。正式布局继续使用系统的区域色设置。</div></aside></div>`;
  }
  function score(){
    const d=active.data,total=d.rules.reduce((n,x)=>n+x.max,0),sorted=d.scores.map(x=>({...x,total:x.dims.reduce((a,b)=>a+b,0)})).sort((a,b)=>b.total-a.total);let rank=0,last=null;
    return `<div class="ev-stack">${panel('评分规则',`按维度分别录入，当前总分 ${total} 分`,d.rules.length?`<div class="ev-rules">${d.rules.map((r,i)=>`<div class="ev-rule"><h3>${E(r.name)}</h3><strong>${r.max}<small> 分</small></strong><p>满分 ${r.max} 分 ${L('编辑','rule',i)}</p></div>`).join('')}</div>`:empty('尚未设置评分规则','先设置评分维度和满分，再录入评分记录。',B('新增维度','rule','new',true)),B('新增维度','rule','new'))}${panel('评分结果',`${sorted.length} 条记录 / 同分同名次`,sorted.length?scrollTable(['名次','参评对象','分组',...d.rules.map(r=>`${E(r.name)} <small>/${r.max}</small>`),'总分','操作'],sorted.map((x,i)=>{if(x.total!==last)rank=i+1;last=x.total;return `<tr><td><span class="ev-rank ${rank===1?'first':''}">${rank}</span></td><td>${E(x.name)}</td><td>${E(x.group||'—')}</td>${d.rules.map((r,j)=>`<td>${x.dims[j]??'—'}</td>`).join('')}<td><strong>${x.total}</strong><small>满分 ${total}</small></td><td>${L('查看','score-detail',d.scores.findIndex(y=>y.name===x.name))}</td></tr>`;}).join('')):empty('还没有评分记录','选择参评对象，按已设定的维度登记得分。',B('录入评分','new-score','',true,d.rules.length?'':'disabled')),B(I('plus')+'录入评分','new-score','',true,d.rules.length?'':'disabled'))}<div class="ev-note neutral">每个维度限制在 0 至该维度满分之间。总分按维度相加，名次采用同分同名次（如 1、2、2、4）。</div></div>`;
  }
  function signin(){
    const d=active.data,signed=d.roster.filter(x=>x.signed).length,records=s.signinTab==='签到记录';
    const results=d.roster.filter(x=>(!records||x.signed)&&(s.signinFilter==='全部状态'||(s.signinFilter==='已签到'?x.signed:!x.signed))&&[x.name,x.no,x.class].join(' ').includes(s.signinQuery));
    return `<section class="ev-panel"><div class="ev-attend-head"><div class="ev-numbers"><div><span>应签人数</span><strong>${d.roster.length}<small> 人</small></strong></div><div><span>已签到</span><strong>${signed}<small> 人</small></strong></div><div><span>未签到</span><strong>${d.roster.length-signed}<small> 人</small></strong></div><div><span>签到率</span><strong>${d.roster.length?Math.round(signed/d.roster.length*100):0}<small>%</small></strong></div></div><div class="ev-actions">${B('签到设置','signin-settings')}${B('展示签到码','qr','',true)}</div></div><div class="ev-filter-tabs" role="group" aria-label="签到管理视图">${['应签名单','签到记录'].map(t=>`<button data-ev="signin-tab" data-value="${t}" aria-pressed="${s.signinTab===t}">${t}<small>${t==='应签名单'?d.roster.length:signed}</small></button>`).join('')}</div><div class="ev-toolbar"><label class="ev-field ev-search">${I('search')}<input id="ev-signin-query" type="search" aria-label="搜索姓名、学号或班级" placeholder="搜索姓名 / 学号 / 班级" value="${E(s.signinQuery)}"></label><label class="ev-field"><span class="sr-only">签到状态</span><select id="ev-signin-filter">${opts(['全部状态','已签到','未签到'],s.signinFilter)}</select></label><div class="ev-actions ev-push">${B('导出名单','export-roster')}${records?B('新增签到','add-signin'):B('导入应签名单','import-roster')}</div></div><div id="ev-signin-results">${results.length?scrollTable(['姓名','学号 / 班级','身份','签到状态','签到方式','签到时间','操作'],results.map(x=>`<tr><td>${E(x.name)}</td><td>${E(x.no)}<small>${E(x.class)}</small></td><td>${E(x.identity)}</td><td>${statusTag(x.signed?'已签到':'待签到')}</td><td>${x.signed?E(x.method):'—'}</td><td>${x.signed?E(x.time):'—'}</td><td>${x.signed?L('查看记录','signin-record',x.id):L('补签','mark-signin',x.id)}</td></tr>`).join('')):empty('没有匹配的人员','调整姓名、学号或签到状态筛选，也可以导入应签名单。',B('导入应签名单','import-roster'))}</div><div class="ev-foot"><span>共 ${results.length} 人 / 当前视图：${s.signinTab}</span><span>应签名单与签到记录分别维护</span></div></section>`;
  }
  function articles(){
    const d=active.data;
    return `<div class="ev-columns">${panel('活动推文','从预热到报道，跟进撰写、审核与发布',d.articles.length?d.articles.map(x=>`<article class="ev-article-row"><div class="ev-article-cover">${I('file')}<small>${E(x.type)}<br>图文稿件</small></div><div>${statusTag(x.status)}<h3>${E(x.title)}</h3><p>负责人 ${E(x.author)}　截止 ${E(x.deadline)}</p><p style="margin-top:7px">${E(x.status==='已发布'?'已完成发布，保留稿件与发布记录':x.status==='待审核'?'正文已提交，等待审核意见':'补充正文与配图后提交审核')}</p></div><div class="ev-actions">${L('查看稿件','article',x.id)}${x.status!=='已发布'?L(x.status==='待审核'?'审核':'编辑','edit-article',x.id):''}</div></article>`).join(''):empty('还没有推文任务','为本次活动创建预热或报道任务，明确撰写人和截止时间。',B('创建推文任务','new-article','',true)),B(I('plus')+'创建推文任务','new-article','',true))}<aside class="ev-stack">${panel('宣传进度','',`<div class="ev-pad">${info([['推文任务',d.articles.length+' 项'],['待审核',d.articles.filter(x=>x.status==='待审核').length+' 项'],['已发布',d.articles.filter(x=>x.status==='已发布').length+' 项']])}</div>`)}${panel('快捷创建','',`<div class="ev-pad ev-stack"><p class="ev-muted">按活动阶段建立任务，再填写负责人和截止时间。</p>${B('创建预热','new-article','预热')}${B('创建活动报道','new-article','报道')}</div>`)}<div class="ev-note neutral">任务状态与稿件内容同屏呈现。发布前核对正文、配图、审核意见和发布地址。</div></aside></div>`;
  }
  const renderers={basic,materials,plan,agenda,seat:seats,score,signin,articles};
  function renderPane(){
    const host=$('#ev-detail-panel');if(!host)return;
    detailHost.classList.toggle('ps-detail',s.tab==='plan'&&s.scenario==='正常'&&!!window.PlanStudio);
    host.setAttribute('aria-labelledby','ev-tab-'+s.tab);
    const exceptional=scenarioBody('retry-detail');
    host.innerHTML=exceptional||(s.scenario==='空数据'?`<section class="ev-panel">${empty('暂无'+tabs.find(t=>t[0]===s.tab)[1]+'数据','当前为设计评审的空状态示例，可返回查看完整业务内容。',B('查看示例数据','retry-detail','',true))}</section>`:renderers[s.tab]());
    $$('.ev-detail-nav [role="tab"]').forEach(b=>{const selected=b.dataset.evTab===s.tab;b.setAttribute('aria-selected',String(selected));b.tabIndex=selected?0:-1;});
    if(s.tab==='plan'&&s.scenario==='正常'){if(window.PlanStudio)window.PlanStudio.mount();else window.ActivityWord?.mount();}
  }
  function $$(selector){return [...document.querySelectorAll(selector)];}
  function switchTab(tab,focus=false){
    s.tab=({overview:'basic',tasks:'basic',basic:'basic'})[tab]||tab;if(!renderers[s.tab])s.tab='basic';s.scenario='正常';
    renderPane();const control=$('#ev-scenario',detailHost);if(control)control.value=s.scenario;
    if(focus){const button=$('#ev-tab-'+s.tab);button?.focus();button?.scrollIntoView({block:'nearest',inline:'nearest'});}
    updateURL('detail');
  }
  function updateURL(view){try{const url=new URL(location.href);url.searchParams.set('view',view);if(view==='detail'){url.searchParams.set('activity',active.id);url.searchParams.set('tab',s.tab);}else{url.searchParams.delete('activity');url.searchParams.delete('tab');}history.replaceState(null,'',url);}catch(e){}}
  const originalShow=showView;
  showView=function(id,focus=true){if(id==='activities'){renderList();updateURL('activities');}originalShow(id,focus);};
  setDetailTab=function(tab,focus=false){switchTab(tab,focus);};
  openActivity=function(id,tab='basic'){active=sample.find(a=>a.id===Number(id))||sample[0];s.tab=({overview:'basic',tasks:'basic'})[tab]||tab;if(!renderers[s.tab])s.tab='basic';s.planVersion=`v${active.data.plan.version} · 当前版本`;s.scenario='正常';s.selectedSeat=0;detailShell();originalShow('detail');updateURL('detail');};
  function log(text){active.data.logs.unshift([text,active.owner,'刚刚']);}
  function refresh(){if(detailHost.hidden)renderList();else detailShell();}
  function download(name,body,mime='text/plain;charset=utf-8'){
    const url=URL.createObjectURL(new Blob(['\uFEFF'+body],{type:mime}));const a=document.createElement('a');a.href=url;a.download=name;document.body.append(a);a.click();a.remove();setTimeout(()=>URL.revokeObjectURL(url),1000);notify('已下载原型文本示例');
  }
  function confirm(title,body,action,button='确认') {openDialog(title,'仅作用于当前原型示例。',`<p style="font-size:13px;line-height:1.9">${body}</p>`,action,button);}
  function editAgenda(id){
    const item=active.data.agenda.find(x=>x.id===Number(id));
    openDialog(item?'编辑议程环节':'添加议程环节','时间与负责人用于现场执行。',`<div class="ev-form">${field('环节名称 *','title',item?.title||'','text',true,true)}${field('开始时间 *','time',item?.time||'20:30','time',true)}${field('时长（分钟） *','duration',item?.duration||15,'number',true)}${field('负责人 *','owner',item?.owner||active.owner,'text',true,true)}${field('环节说明','remark',item?.remark||'','textarea',false,true)}</div>`,f=>{const v=Object.fromEntries(f);v.duration=Number(v.duration);if(!v.title.trim()||!v.owner.trim())throw Error('请填写环节名称与负责人');if(v.duration<=0)throw Error('时长须大于 0 分钟');if(item)Object.assign(item,v);else active.data.agenda.push({...v,id:nextId++});log('更新活动议程');detailShell();},item?'保存修改':'添加环节');
  }
  function ruleEditor(value){
    const d=active.data,index=Number(value),r=value==='new'?null:d.rules[index];
    openDialog(r?'编辑评分维度':'新增评分维度','设置维度名称和分值范围。',`<div class="ev-form">${field('维度名称 *','name',r?.name||'','text',true)}${field('满分 *','max',r?.max||20,'number',true)}</div>`,f=>{const name=String(f.get('name')).trim(),max=Number(f.get('max'));if(!name||max<=0)throw Error('维度名称不能为空，满分须大于 0');if(d.rules.some((x,i)=>x.name===name&&i!==index))throw Error('维度名称已存在');if(r){if(d.scores.some(x=>(x.dims[index]||0)>max))throw Error('已有得分超过新的满分，请先处理评分记录');Object.assign(r,{name,max});}else d.rules.push({name,max});detailShell();});
  }
  function newScore(){
    const d=active.data;if(!d.rules.length)return;
    openDialog('录入评分','按评分规则填写，总分自动求和。',`<div class="ev-form">${field('参评对象 *','name','','text',true)}${field('分组','group')}${d.rules.map((r,i)=>`<label>${E(r.name)}（满分 ${r.max}） *<input name="dim${i}" type="number" min="0" max="${r.max}" step="1" required></label>`).join('')}</div>`,f=>{const name=String(f.get('name')).trim(),dims=d.rules.map((r,i)=>{const n=Number(f.get('dim'+i));if(!Number.isFinite(n)||n<0||n>r.max)throw Error('得分必须在 0 至该维度满分之间');return n;});if(!name)throw Error('请填写参评对象');if(d.scores.some(x=>x.name===name))throw Error('该参评对象已存在评分记录');d.scores.push({name,group:String(f.get('group')),dims});log('新增评分记录');detailShell();},'保存评分');
  }
  function articleEditor(id,type='预热'){
    const d=active.data,x=d.articles.find(a=>a.id===Number(id));
    openDialog(x?'编辑推文任务':'创建推文任务','分配撰写负责人，明确稿件类型和截止时间。',`<div class="ev-form">${field('推文标题 *','title',x?.title||active.title+'｜'+type,'text',true,true)}${selectField('稿件类型','type',['预热','报道'],x?.type||type)}${field('负责人 *','author',x?.author||active.owner,'text',true)}${field('截止时间 *','deadline',x?.deadline.replace(' ','T')||'2026-10-21T18:00','datetime-local',true,true)}${field('稿件正文','body',x?.body||'','textarea',false,true)}${x?selectField('稿件状态','status',['草稿','待审核','已驳回','已通过','已发布'],x.status,true):''}${field('发布地址（标记已发布时必填）','url',x?.url||'','url',false,true)}</div>`,f=>{const v=Object.fromEntries(f);if(!v.title.trim()||!v.author.trim())throw Error('标题与负责人不能为空');if(v.status==='已发布'&&!v.url)throw Error('请填写发布地址后再标记已发布');if(v.status!=='草稿'&&v.status&&!v.body.trim())throw Error('提交审核或发布前请填写正文');v.deadline=v.deadline.replace('T',' ');if(x)Object.assign(x,v);else d.articles.push({...v,id:nextId++,status:'草稿'});log('更新推文任务');detailShell();},x?'保存任务':'创建任务');
  }
  function handle(action,value){
    const d=active.data;
    if(window.ActivityWord?.handle(action,value))return;
    if(action==='close'){requestClose();return;}
    if(action==='continue-edit'){$('.ev-unsaved',dialog).hidden=true;return;}
    if(action==='discard-edit'){closeDialog();return;}
    if(action==='open'){openActivity(value);return;}
    if(action==='tab'){switchTab(value);return;}
    if(action==='phase'){s.phase=value;s.page=1;renderList();return;}
    if(action==='reset'){Object.assign(s,{query:'',phase:'全部活动',type:'全部类型',department:'全部负责部门',page:1,scenario:'正常'});renderList();return;}
    if(action==='density'){s.density=value;renderList();return;}
    if(action==='sort'){s.sort*=-1;renderRows();return;}
    if(action==='page'){s.page=Number(value);renderRows();return;}
    if(action==='new-activity'){activityEditor();return;}
    if(action==='edit-activity'){activityEditor(value);return;}
    if(action==='retry-list'||action==='retry-detail'){s.scenario='正常';refresh();return;}
    if(action==='advance'){
      const idx=phases.indexOf(active.status);if(idx>=5)return;
      if(active.status==='策划审核'&&d.plan.status!=='已通过'){openDialog('策划尚未通过审核','请先完成策划审核，再推进到执行中。',`<div class="ev-note amber">当前策划状态：${E(d.plan.status)}。请在策划书页面核对内容并完成审核。</div><div style="margin-top:16px">${B('查看策划书','go-plan','',true)}</div>`);return;}
      confirm('推进活动阶段',`将「${E(active.title)}」从 ${statusTag(active.status)} 推进至 ${statusTag(phases[idx+1])}。各模块资料保留，请确认准备事项已完成。`,()=>{active.status=phases[idx+1];log('活动推进至'+active.status);detailShell();},'确认推进');return;
    }
    if(action==='go-plan'){dialog.close();switchTab('plan');return;}
    if(action==='gantt'){openDialog('活动任务甘特图','正式落地连接现有独立甘特图页面。',`<div class="ev-note neutral">本活动共 ${active.total} 项任务，已完成 ${active.done} 项。任务编辑、负责人分配与依赖关系沿用现有甘特图能力。</div><div style="margin-top:20px">${scrollTable(['任务','负责人','状态'],[['策划与议程',active.owner,'已完成'],['场地与名单','周同学','进行中'],['宣传与材料','林同学','待处理']].map(([n,o,t])=>`<tr><td>${n}</td><td>${E(o)}</td><td>${tag(t,t==='已完成'?'green':'blue')}</td></tr>`).join(''))}</div>`);return;}
    if(action==='upload'){
      openDialog('上传活动材料','选择本地文件，原型仅展示名称与大小；文件不会上传。',`<div class="ev-form"><label class="wide">选择文件 *<input type="file" name="file" required></label>${selectField('材料类型','type',['策划书','名单','其他','照片','新闻稿'],'其他')}${field('上传人','owner',active.owner)}<div class="wide ev-note neutral">关联活动：${E(active.title)}。原型不读取文件内容。</div></div>`,f=>{const file=f.get('file');if(!file?.size)throw Error('请选择一个非空文件');d.materials.push({id:nextId++,name:file.name,type:String(f.get('type')),owner:String(f.get('owner'))||active.owner,ext:(file.name.split('.').pop()||'FILE').toUpperCase().slice(0,5),size:Math.max(1,Math.round(file.size/1024))+' KB',date:'2026-10-20 14:30',body:'这是原型中选取的本地文件。原型仅记录文件名与大小，没有读取或上传文件内容。'});log('添加材料关联（原型）');detailShell();},'添加到原型');return;
    }
    if(action==='file'||action==='download-file'){
      const m=d.materials.find(x=>x.id===Number(value));if(!m)return;
      if(action==='download-file'){download('原型示例-'+m.name+'.txt',m.body);return;}
      openDialog(m.name,'原型仅展示示例文本，非真实文档渲染。',`<div class="ev-actions" style="margin-bottom:18px"><span class="ev-filemark">${E(m.ext)}</span><div><strong>${E(m.name)}</strong><p class="ev-muted">${E(m.type)} / ${E(m.size)} / ${E(m.owner)}</p></div></div><div class="ev-note neutral" style="white-space:pre-wrap">${E(m.body)}</div><div style="margin-top:18px">${B('下载文本示例','download-file',m.id)}</div>`);return;
    }
    if(action==='plan-details'){
      const p=d.plan;openDialog('审核与版本','活动策划书',`<div class="ev-stack">${panel('审核信息','',`<div class="ev-pad">${info([['当前版本','v'+p.version],['策划状态',statusTag(p.status)],['提交人',E(active.owner)],['审核人','张老师']])}</div>`)}${panel('版本记录','',`<ul class="ev-audit ev-pad"><li>v${p.version} · 当前版本<small>${E(p.updated)}</small></li><li>v2 · 修改稿<small>补充活动流程与责任分工</small></li><li>v1 · 初稿<small>建立策划框架</small></li></ul>`)}<div class="ev-note neutral">策划审核与活动阶段分别展示。审核通过后，由负责人确认推进活动阶段。</div></div>`,null,'',false,'drawer');return;
    }
    if(action==='edit-plan'){
      const p=d.plan;openDialog('编辑策划书','完善策划内容后保存为新版本，再提交审核。',`<div class="ev-form"><section class="ev-form-section wide"><h3>活动目标</h3><p>说明开展活动的背景与预期成果。</p></section>${field('活动背景 *','background',p.background,'textarea',true,true)}${field('活动目的 *','purpose',p.purpose,'textarea',true,true)}<section class="ev-form-section wide"><h3>执行安排</h3><p>明确活动内容、责任分工与经费使用。</p></section>${field('活动内容','content',p.content||'集中学习、小组交流与总结分享。','textarea',false,true)}${field('活动流程','flow',p.flow||d.agenda.map(x=>x.time+' '+x.title+' / '+x.owner).join('\n'),'textarea',false,true)}${field('经费预算 *','budget',p.budget,'textarea',true,true)}<section class="ev-form-section wide"><h3>保障措施</h3><p>提前列出现场注意事项与应急处理方式。</p></section>${field('注意事项','notice',p.notice||'提前核对场地和参会名单，检查设备与签到安排。','textarea',false,true)}${field('应急预案','emergency',p.emergency||'设备故障时切换备用设备，突发情况及时报告活动负责人。','textarea',false,true)}<div class="ev-note neutral wide">保存后生成新版本，策划状态变为草稿。正式编辑继续使用系统的 Word 编辑器与模板能力。</div></div>`,f=>{Object.assign(p,Object.fromEntries(f));p.version++;p.status='草稿';p.updated='2026-10-20 14:30';s.planVersion=`v${p.version} · 当前版本`;log('更新策划书至 v'+p.version);detailShell();},'保存新版本');return;
    }
    if(action==='current-plan'){s.planVersion=`v${d.plan.version} · 当前版本`;renderPane();return;}
    if(action==='approve-plan'){confirm('通过策划审核','请确认活动目标、流程、预算及应急预案完整。通过后将保留本次审核记录。',()=>{d.plan.status='已通过';log('策划书审核通过');detailShell();},'通过审核');return;}
    if(action==='reject-plan'){openDialog('驳回策划书','说明需要修改的内容，便于提交人修订。',`<div class="ev-form">${field('修改意见 *','reason','','textarea',true,true)}</div>`,f=>{if(!String(f.get('reason')).trim())throw Error('请填写修改意见');d.plan.status='已驳回';log('策划书已驳回：'+f.get('reason'));detailShell();},'确认驳回',true);return;}
    if(action==='submit-plan'){confirm('提交策划审核','将当前策划书版本提交审核，审核前可继续预览文档。',()=>{d.plan.status='待审核';log('提交策划书审核');detailShell();},'提交审核');return;}
    if(action==='download-plan'){download('原型示例-活动策划书.txt',active.title+'\n'+d.plan.background+'\n'+d.plan.purpose+'\n'+d.plan.budget);return;}
    if(action==='new-agenda'||action==='edit-agenda'){editAgenda(action==='new-agenda'?null:value);return;}
    if(action==='delete-agenda'){const x=d.agenda.find(x=>x.id===Number(value));if(!x)return;confirm('删除议程环节',`删除「${E(x.title)}」后，请重新核对议程时间。`,()=>{d.agenda=d.agenda.filter(y=>y!==x);log('删除议程环节');detailShell();},'删除环节');return;}
    if(action==='up-agenda'){const i=d.agenda.findIndex(x=>x.id===Number(value));if(i>0)[d.agenda[i-1],d.agenda[i]]=[d.agenda[i],d.agenda[i-1]];renderPane();notify('环节已上移，请核对开始时间');return;}
    if(action==='download-agenda'){download('原型示例-活动议程.txt',d.agenda.map(x=>`${x.time} ${x.title} ${x.duration}分钟 / ${x.owner}\n${x.remark}`).join('\n\n'));return;}
    if(action==='seat-view'){s.seatView=value;renderPane();return;}
    if(action==='seat'){s.selectedSeat=Number(value);renderPane();return;}
    if(action==='assign-seat'){const x=d.seats[s.selectedSeat];openDialog('分配座位',`${Math.floor(s.selectedSeat/8)+1} 排 ${s.selectedSeat%8+1} 座 / ${x.zone}`,`<div class="ev-form">${field('人员姓名 *','name',x.name,'text',true,true)}</div>`,f=>{const name=String(f.get('name')).trim();if(!name)throw Error('请填写人员姓名');x.name=name;renderPane();},'分配座位');return;}
    if(action==='clear-seat'){const x=d.seats[s.selectedSeat];confirm('清空座位',`移除当前座位上的「${E(x.name)}」，保留座位与区域信息。`,()=>{x.name='';renderPane();},'清空座位');return;}
    if(action==='rule'){ruleEditor(value);return;}
    if(action==='new-score'){newScore();return;}
    if(action==='score-detail'){const x=d.scores[Number(value)];openDialog('评分记录',x.name,`<div class="ev-pad">${info(d.rules.map((r,i)=>[r.name,`${x.dims[i]??'未评分'} / ${r.max}`]).concat([['总分',x.dims.reduce((a,b)=>a+b,0)+' 分'],['分组',E(x.group)]]))}</div>`);return;}
    if(action==='signin-tab'){s.signinTab=value;s.signinFilter='全部状态';renderPane();return;}
    if(action==='signin-settings'){openDialog('签到设置','正式落地复用当前签到字段配置。',`<div class="ev-form"><label class="wide">签到展示字段</label>${['姓名（必填）','学号（必填）','班级','身份'].map((n,i)=>`<label style="display:flex;align-items:center"><input type="checkbox" name="fields" value="${n}" checked ${i<2?'disabled':''} style="width:auto;min-height:auto">${n}</label>`).join('')}<div class="wide ev-note neutral">本原型仅展示字段配置布局。签到二维码、定位校验与实际签到由现有服务处理。</div></div>`,()=>{},'保存预览');return;}
    if(action==='qr'){openDialog('活动签到入口','设计评审：此处不生成有效签到码。',`<div class="ev-empty" style="padding:26px 15px;min-height:0">${I('calendar')}<h3>${E(active.title)}</h3><p>正式页面展示系统生成的动态签到二维码及有效期。</p><div class="ev-note neutral">签到入口预留区域<br>原型不提供可扫描的二维码</div><p style="margin-top:18px">应签 ${d.roster.length} 人 / 已签 ${d.roster.filter(x=>x.signed).length} 人</p></div>`);return;}
    if(action==='signin-record'){const x=d.roster.find(x=>x.id===Number(value));openDialog('签到记录',x.name,`<div class="ev-pad">${info([['姓名',E(x.name)],['学号',E(x.no)],['签到方式',E(x.method)],['签到时间',E(active.date+' '+x.time)],['签到状态',statusTag('已签到')]])}</div>`);return;}
    if(action==='mark-signin'){const x=d.roster.find(x=>x.id===Number(value));openDialog('补签登记',`${x.name} / ${x.no}`,`<div class="ev-form">${field('签到时间 *','time','19:02','time',true)}${field('补签原因 *','reason','','textarea',true,true)}</div>`,f=>{if(!String(f.get('reason')).trim())throw Error('请填写补签原因');x.signed=true;x.time=String(f.get('time'));x.method='补签';log('补签：'+x.name+' / '+f.get('reason'));renderPane();},'确认补签');return;}
    if(action==='import-roster'){openDialog('导入应签名单','粘贴名单即可预览导入，原型不上传文件。',`<div class="ev-form"><div class="wide ev-note neutral">每行一人，使用英文逗号分隔：姓名,学号,班级。保存时校验学号重复。</div>${field('人员名单 *','roster','郑同学,20231021,计科2301\n许同学,20231022,软件2302','textarea',true,true)}</div>`,f=>{const lines=String(f.get('roster')).trim().split(/\r?\n/),seen=new Set(d.roster.map(x=>x.no));const incoming=lines.map((line,i)=>{const [name,no,cl]=line.split(',').map(x=>x.trim());if(!name||!no||!cl)throw Error(`第 ${i+1} 行格式不完整`);if(seen.has(no))throw Error(`学号 ${no} 已存在，请核对后导入`);seen.add(no);return {id:nextId++,name,no,class:cl,identity:'积极分子',signed:false,time:'',method:'扫码'};});d.roster.push(...incoming);log('导入应签名单');renderPane();},'导入原型名单');return;}
    if(action==='add-signin'){openDialog('新增签到','补录到场人员并添加签到记录。',`<div class="ev-form">${field('姓名 *','name','','text',true)}${field('学号 *','no','','text',true)}${field('班级 *','class','','text',true)}${field('签到时间 *','time','19:00','time',true)}</div>`,f=>{const v=Object.fromEntries(f);if(!v.name.trim()||!v.no.trim())throw Error('姓名与学号不能为空');const x=d.roster.find(x=>x.no===v.no);if(x?.signed)throw Error('该学号已有签到记录');if(x)Object.assign(x,{signed:true,time:v.time,method:'手动'});else d.roster.push({...v,id:nextId++,identity:'积极分子',signed:true,method:'手动'});renderPane();},'保存签到记录');return;}
    if(action==='export-roster'){const safe=v=>'"'+String(v).replace(/"/g,'""').replace(/^[=+@-]/,"'$&")+'"';download('原型示例-签到名单.csv',[['姓名','学号','班级','签到状态','签到时间'],...d.roster.map(x=>[x.name,x.no,x.class,x.signed?'已签到':'未签到',x.time])].map(row=>row.map(safe).join(',')).join('\r\n'),'text/csv;charset=utf-8');return;}
    if(action==='new-article'){articleEditor(null,value||'预热');return;}
    if(action==='edit-article'){articleEditor(value);return;}
    if(action==='article'){const x=d.articles.find(x=>x.id===Number(value));openDialog(x.title,'活动推文 / '+x.type,`<div class="ev-actions" style="margin-bottom:18px">${statusTag(x.status)}<span class="ev-muted">负责人 ${E(x.author)} / 截止 ${E(x.deadline)}</span></div><article class="ev-paper" style="padding:0"><h2>${E(x.title)}</h2><p>活动稿件示例</p><p style="margin-top:20px">${E(x.body||'尚未填写正文')}</p></article>${x.status!=='已发布'?`<div style="margin-top:20px">${B('编辑任务与稿件','edit-article',x.id,true)}</div>`:''}`,null,'',false,'drawer');return;}
  }
  document.addEventListener('click',e=>{const b=e.target.closest('[data-ev]');if(b){handle(b.dataset.ev,b.dataset.value);return;}const tab=e.target.closest('[data-ev-tab]');if(tab)switchTab(tab.dataset.evTab);});
  document.addEventListener('keydown',e=>{const button=e.target.closest('[data-ev-tab]');if(!button)return;const index=tabs.findIndex(x=>x[0]===button.dataset.evTab);const next=({'ArrowRight':(index+1)%8,'ArrowLeft':(index+7)%8,'Home':0,'End':7})[e.key];if(next!==undefined){e.preventDefault();switchTab(tabs[next][0],true);}});
  function preserveInput(id,render){const input=$('#'+id),position=input?.selectionStart;render();const replacement=$('#'+id);replacement?.focus();if(replacement&&position!==null&&position!==undefined&&replacement.type!=='search')replacement.setSelectionRange(position,position);}
  document.addEventListener('input',e=>{
    if(e.target.id==='ev-query'){s.query=e.target.value;s.page=1;renderRows();}
    if(e.target.id==='ev-material-query'){s.materialQuery=e.target.value;preserveInput('ev-material-query',renderPane);}
    if(e.target.id==='ev-signin-query'){s.signinQuery=e.target.value;preserveInput('ev-signin-query',renderPane);}
  });
  document.addEventListener('change',e=>{
    const value=e.target.value;
    if(e.target.id==='ev-type'){s.type=value;s.page=1;renderRows();}
    if(e.target.id==='ev-department'){s.department=value;s.page=1;renderRows();}
    if(e.target.id==='ev-size'){s.size=Number(value);s.page=1;renderRows();}
    if(e.target.id==='ev-material-type'){s.materialType=value;renderPane();}
    if(e.target.id==='ev-plan-version'){s.planVersion=value;renderPane();}
    if(e.target.id==='ev-signin-filter'){s.signinFilter=value;renderPane();}
    if(e.target.id==='ev-scenario'){s.scenario=value;if(detailHost.hidden)renderRows();else renderPane();}
  });
  renderList();detailShell();
  const url=new URLSearchParams(location.search),view=url.get('view');
  if(view==='detail')openActivity(url.get('activity')||1,url.get('tab')||'basic');
  else if(view==='activities')showView('activities',false);
  else if(!view&&document.body.dataset.entry==='plan')openActivity(1,'plan');
})();
