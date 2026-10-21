/* Plan studio design prototype. Data stays in this browser; no business API is called. */
(() => {
  'use strict';
  const E = esc, $ = (selector, root = document) => root.querySelector(selector);
  const clone = value => JSON.parse(JSON.stringify(value));
  const chapters = [
    ['background', '活动背景', '说明活动缘起，与本学期工作的联系。'],
    ['purpose', '活动目标', '写清参与者会获得什么，以及如何确认活动效果。'],
    ['content', '内容与安排', '补充参与范围、活动内容及组织方式。'],
    ['flow', '流程与分工', '明确每个环节的时间、负责人和交付内容。'],
    ['budget', '经费预算', '列出用途、金额和经费来源，便于审核。'],
    ['notice', '注意事项', '说明会前准备、现场秩序及材料整理要求。'],
    ['emergency', '应急预案', '说明风险、处置办法和具体责任人。'],
  ];
  const records = new Map(), originals = new Map();
  const metaOf = a => Object.fromEntries(['title','date','time','place','department','owner','theme','audience'].map(k => [k, a[k] || '']));
  const meta = () => shown().meta || record.meta;
  const reviewers = [
    { id: 'zhang', name: '张老师', department: '指导老师' },
    { id: 'li', name: '李老师', department: '学院办公室' },
    { id: 'wang', name: '王同学', department: '组织部' },
  ];
  let active, record, currentId, role = 'author', section = 'background';
  let panel = 'review', mode = 'read', editing = false, draft = null, historyId = null, timer;
  let selectionRange = null, editorDirty = false, touchedFields = new Set();
  let dialogAction = null, dialogTrigger = null;
  const button = (text, action, value = '', cls = '', extra = '') =>
    `<button type="button" class="ps-button ${cls}" data-ps="${action}" data-value="${E(String(value))}" ${extra}${action === 'resolve' && !canResolve() ? ' disabled title="当前视角不能处理此版本的意见"' : ''}>${text}</button>`;
  const glyph = name => {
    const paths = {
      check: '<path d="m5 12 4 4L19 6"/>',
      file: '<path d="M14 3H5v18h14V8zM14 3v5h5M8 12h8M8 16h5"/>',
      comment: '<path d="M21 4H3v13h5l4 4v-4h9zM7 8h10M7 12h6"/>',
      edit: '<path d="m15 4 5 5M4 16 16 4a2 2 0 0 1 4 4L8 20H4z"/>',
      clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
      arrow: '<path d="M5 12h14m-5-5 5 5-5 5"/>',
      download: '<path d="M12 3v12m-4-4 4 4 4-4M4 16v5h16v-5"/>',
    };
    return `<svg viewBox="0 0 24 24" aria-hidden="true">${paths[name] || paths.file}</svg>`;
  };
  const statusTone = status => ({ '草稿': 'blue', '待审核': 'amber', '已通过': 'green', '已驳回': 'red' })[status] || '';
  const badge = status => `<span class="ps-badge ${statusTone(status)}">${E(status)}</span>`;
  const chapterName = key => chapters.find(x => x[0] === key)?.[1] || '全文';
  const now = () => new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false,
  }).format(new Date());
  const key = id => `pams-v6-plan-studio-1-${id}`;
  const isDraft = () => ['草稿', '已驳回'].includes(record.status);
  const reviewerName = id => reviewers.find(user => user.id === id)?.name || '审核人';
  const currentUserName = () => role === 'author' ? active.owner : reviewerName(role);
  const canViewCurrent = () => role === 'author' || record.reviewerIds.includes(role);
  const canViewShown = () => historyId ? record.history.some(v => v.id === historyId) : canViewCurrent();
  const canReview = () => !historyId && role !== 'author' && canViewCurrent() && record.status === '待审核' && record.decisions[role] === '待审核';
  const canResolve = () => canViewShown() && !historyId && (role === 'author' ? isDraft() : canReview());
  const canEdit = () => role === 'author' && isDraft() && !historyId;
  const unresolved = () => record.comments.filter(x => !x.resolved);
  const shown = () => historyId ? record.history.find(x => x.id === historyId) : canViewCurrent() ? record : null;
  function ensureVisibleVersion() {
    if (historyId && !record.history.some(v => v.id === historyId)) historyId = null;
    if (!canViewCurrent() && !historyId) { historyId = record.history[0]?.id || null; panel = 'history'; }
    if (!canViewCurrent()) panel = 'history';
  }
  function initial(a) {
    const p = a.data.plan;
    const fields = {
      background: p.background,
      purpose: p.purpose + '\n活动后收集各学习小组的交流纪要，形成学习成果清单，由组织部跟进落实。',
      content: `以“${a.theme}”为主题，组织专题学习、小组交流和总结分享。\n面向${a.audience}，由${a.department}统筹，${a.owner}负责活动协调。`,
      flow: a.data.agenda.map(x => `${x.time}｜${x.title}｜${x.owner}｜${x.remark}`).join('\n'),
      budget: p.budget + '\n经费来源：学院学生活动专项经费。由负责人统一申请，凭有效票据据实报销。',
      notice: '工作人员提前 30 分钟到场，完成设备调试和签到准备。\n参会人员提前 10 分钟入场，现场保持安静，手机调至静音。\n活动结束后，由宣传组整理照片与交流纪要，会务组核对签到并清理场地。',
      emergency: '投影或音响故障时，会务组启用备用设备；无法恢复时改为线下交流。\n如遇人员身体不适，立即联系校医院并安排专人陪同；现场负责人统一协调。',
    };
    const previous = { ...fields, purpose: p.purpose, budget: p.budget, emergency: '现场突发情况及时报告活动负责人，由负责人协调处理。' };
    return {
      schema: 2, version: p.version, status: p.status, fields, htmlFields: {}, meta: metaOf(a),
      reviewerIds: p.status === '草稿' ? [] : ['zhang'],
      decisions: p.status === '草稿' ? {} : { zhang: p.status === '已通过' ? '已通过' : '待审核' },
      updated: '10/12 16:40', reviewNote: '', submitted: p.status === '待审核' ? clone(fields) : null,
      comments: p.status === '待审核' ? [
        { id: 'budget', chapter: 'budget', author: '张老师', date: '10/12 17:10', text: '请确认 200 元预算已包含全部物料，并明确经费来源和报销方式。', resolved: false },
        { id: 'emergency', chapter: 'emergency', author: '张老师', date: '10/12 17:12', text: '应急预案还需要明确现场联络人，确保设备或人员突发情况有人跟进。', resolved: false },
        { id: 'purpose', chapter: 'purpose', author: '张老师', date: '10/12 16:55', text: '已补充学习成果清单，目标更具体了。', resolved: true },
      ] : [],
      history: [
        { id: 'seed-v2', version: 2, status: '已驳回', fields: previous, meta: metaOf(a), updated: '10/11 15:20', note: '补充预算来源、活动成果与应急处理。' },
        { id: 'seed-v1', version: 1, status: '草稿', fields: { ...previous, notice: '提前核对场地与参会名单。' }, meta: metaOf(a), updated: '10/10 09:30', note: '建立策划框架与活动安排。' },
      ],
    };
  }
  function load(a) {
    if (records.has(a.id)) return records.get(a.id);
    if (!originals.has(a.id)) originals.set(a.id, initial(a));
    let data;
    try {
      data = a.id < 6 ? JSON.parse(localStorage.getItem(key(a.id))) : null;
      if (!data || data.schema !== 2 || !data.meta || !chapters.every(([k]) => typeof data.fields?.[k] === 'string') ||
          !Array.isArray(data.history) || !Array.isArray(data.comments) ||
          !['草稿', '待审核', '已通过', '已驳回'].includes(data.status)) data = null;
    } catch (_) { data = null; }
    data ||= clone(originals.get(a.id));
    data.htmlFields ||= {};
    data.reviewerIds = Array.isArray(data.reviewerIds) ? data.reviewerIds.filter(id => reviewers.some(user => user.id === id)) : data.status === '草稿' ? [] : ['zhang'];
    data.decisions ||= Object.fromEntries(data.reviewerIds.map(id => [id, data.status === '已通过' ? '已通过' : '待审核']));
    records.set(a.id, data);
    return data;
  }
  function sync() {
    Object.assign(active.data.plan, record.fields, { status: record.status, version: record.version, updated: record.updated });
  }
  function persist() {
    sync();
    if (active.id >= 6) return false;
    try { localStorage.setItem(key(active.id), JSON.stringify(record)); return true; }
    catch (_) { toast('浏览器存储不可用，本次修改仅保留到页面关闭。'); return false; }
  }
  function toast(text) {
    let el = $('#ps-toast');
    if (!el) { el = document.createElement('div'); el.id = 'ps-toast'; el.className = 'ps-toast'; el.setAttribute('role', 'status'); document.body.append(el); }
    el.textContent = text; el.hidden = false; clearTimeout(timer); timer = setTimeout(() => { el.hidden = true; }, 4000);
  }
  function stats() {
    const doc = shown();
    return { complete: doc ? chapters.filter(([k]) => doc.fields[k].trim()).length : 0, open: canViewCurrent() && !historyId ? unresolved().length : 0 };
  }
  function context() {
    const { complete, open } = stats();
    if (!canViewCurrent()) return ['clock', '当前版本未向你开放', shown() ? `你未被选为本轮审核人，当前展示历史版本 v${shown().version}。` : '你未被选为本轮审核人，当前也没有可查看的历史版本。', ''];
    if (historyId) return ['clock', `正在查看 v${shown().version} 历史版本`, '历史正文保持只读，可在右侧对比与当前版本的差异。', button('返回当前版本', 'current')];
    if (record.status === '待审核') {
      const pending = record.reviewerIds.filter(id => record.decisions[id] === '待审核').map(reviewerName);
      return ['comment', `v${record.version} 等待${pending.join('、') || '审核人'}审核`, `仅负责人及本轮选定审核人可查看当前版本。已通过 ${record.reviewerIds.length - pending.length} / ${record.reviewerIds.length} 人${open ? `，${open} 条意见待处理。` : '。'}`, role === 'author' ? button('撤回并修订', 'revise') : ''];
    }
    if (record.status === '已通过') return ['check', `v${record.version} 已通过审核`, '定稿已保留。后续修改将另建草稿，重新提交审核。', role === 'author' ? button('创建修订稿', 'revise') : ''];
    if (record.status === '已驳回') return ['comment', '策划书需要修改', record.reviewNote || '根据右侧审核意见修改正文，处理完成后重新提交。', ''];
    return ['edit', `v${record.version} 草稿 · ${complete} / 7 章节已填写`, open ? `还有 ${open} 条意见待处理。修改后原地保存，再选择审核人提交。` : '点击「编辑策划书」直接修改正文，保存后原地回显；提交时勾选审核人。', ''];
  }
  function markup(a) {
    if (currentId !== a.id) { editing = false; draft = null; editorDirty = false; selectionRange = null; }
    active = a; record = load(a);
    if (isDraft()) record.meta = metaOf(a);
    sync();
    if (currentId !== a.id) {
      currentId = a.id; section = 'background'; panel = 'review'; mode = 'read'; historyId = null;
    }
    ensureVisibleVersion();
    const [symbol, title, sub, action] = context(), count = stats();
    const doc = shown();
    if (!doc) return `<section class="ps-studio">${studioHeader(null)}<div class="ps-context"><span class="ps-context-icon">${glyph('clock')}</span><div class="ps-context-copy"><strong>${E(title)}</strong><p>${E(sub)}</p></div></div><div class="ps-empty"><h3>暂无可查看的历史版本</h3><p>当前版本仅向负责人及本轮选定审核人开放。</p></div><div class="ps-local-note">交互原型 · 版本权限在此浏览器内模拟</div></section>`;
    return `<section class="ps-studio" aria-label="策划书工作台">
      ${studioHeader(doc)}
      <div class="ps-context"><span class="ps-context-icon">${glyph(symbol)}</span><div class="ps-context-copy"><strong>${E(title)}</strong><p>${E(sub)}</p></div><div class="ps-actions">${action}</div></div>
      <div class="ps-grid">
        <aside class="ps-outline" aria-label="策划书章节"><div class="ps-outline-label">文档目录 <span>7 章</span></div><nav>${chapters.map(([k, name], i) => { const needsReview = !historyId && canViewCurrent() && record.comments.some(x => x.chapter === k && !x.resolved); return `<button type="button" class="ps-nav" data-ps="section" data-value="${k}" data-active="${k === section}" ${k === section ? 'aria-current="location"' : ''}><span class="ps-nav-number">${String(i + 1).padStart(2, '0')}</span><span>${name}</span><i aria-label="${needsReview ? '有待处理意见' : doc.fields[k].trim() ? '已填写' : '待填写'}" class="ps-nav-dot ${needsReview ? 'warning' : doc.fields[k].trim() ? 'complete' : ''}"></i></button>`; }).join('')}</nav>
          <div class="ps-outline-footer"><span>${glyph('check')} ${count.complete} / 7 章节已填写</span><p>活动信息自动引用<br>正文按章节编写</p>${button('查看活动议程', 'agenda', '', 'quiet')}</div>
        </aside>
        <main class="ps-main"><div class="ps-document-toolbar"><div class="ps-mode" role="group" aria-label="文档视图">${button('正文', 'mode', 'read', '', `aria-pressed="${mode === 'read'}"`)}${button('A4 预览', 'mode', 'print', '', `aria-pressed="${mode === 'print'}" ${editing ? 'disabled' : ''}`)}</div><span class="ps-save-status" id="ps-save-status">${historyId ? '历史版本 · 只读' : editing ? '编辑中 · 尚未保存' : canEdit() ? '已保存 · 点击编辑修改正文' : isDraft() ? '负责人修订中 · 只读' : '审核版本 · 只读'}</span>${canEdit() && !editing ? button(glyph('edit') + '编辑策划书', 'edit', '', 'quiet') : ''}</div>
          ${editing ? editorToolbar() : ''}
          <div class="ps-scroll" id="ps-scroll" tabindex="0" aria-label="策划书正文">${mode === 'read' ? documentBody() : printPreview()}</div>
          <div class="ps-section-footer"><span>${historyId ? '历史正文' : '当前版本'} · ${Object.values(doc.fields).join('').length.toLocaleString('zh-CN')} 字</span><span>${editing ? '直接在正文中编辑，保存后原地回显' : canEdit() ? '编辑时显示文档顶部编辑栏' : '正文与审核意见相互定位'}</span></div>
        </main>
        <aside class="ps-inspector" aria-label="审核与版本">
          <div class="ps-inspector-tabs" role="tablist" aria-label="审核侧栏">${canViewCurrent() ? `<button id="ps-tab-review" role="tab" data-ps="panel" data-value="review" aria-controls="ps-inspector-content" tabindex="${panel === 'review' ? 0 : -1}" aria-selected="${panel === 'review'}">审核意见 <span>${count.open}</span></button>` : ''}<button id="ps-tab-history" role="tab" data-ps="panel" data-value="history" aria-controls="ps-inspector-content" tabindex="${panel === 'history' ? 0 : -1}" aria-selected="${panel === 'history'}">版本记录</button></div>
          <div id="ps-inspector-content" role="tabpanel" aria-labelledby="ps-tab-${panel}">${panel === 'review' ? reviewPanel() : historyPanel()}</div>
        </aside>
      </div>
      <div class="ps-local-note"><span>交互原型 · ${a.id < 6 ? '修改仅保存在此浏览器' : '新建活动修改仅在本次预览保留'}，版本权限使用示例用户模拟</span>${role === 'author' ? button('重置本活动示例', 'reset', '', 'quiet') : ''}</div>
    </section>`;
  }
  function studioHeader(doc) {
    return `<header class="ps-heading"><div class="ps-title"><h2>${glyph('file')}策划书 ${doc ? badge(doc.status) : ''}</h2><p>${doc ? `v${doc.version} · ${E(doc.meta?.owner || active.owner)}负责 / 最近更新 ${E(doc.updated)}` : '当前版本未授权，仅可查看历史版本'}</p></div><div class="ps-actions"><label class="ps-role-label">预览用户 <select id="ps-role" aria-label="预览用户" ${editing ? 'disabled' : ''}><option value="author" ${role === 'author' ? 'selected' : ''}>${E(active.owner)} · 负责人</option>${reviewers.map(user => `<option value="${user.id}" ${role === user.id ? 'selected' : ''}>${user.name} · ${record.reviewerIds.includes(user.id) ? '已选审核人' : '未选中'}</option>`).join('')}</select></label>${doc ? button('Word 试阅', 'word', '', '', editing ? 'disabled' : '') + button(glyph('download') + '导出 Word', 'export', '', '', editing ? 'disabled' : '') : ''}${canEdit() ? button('提交审核', 'submit', '', 'primary', editing ? 'disabled title="请先保存或取消编辑"' : '') : ''}</div></header>`;
  }
  function editorToolbar() {
    return `<div class="ps-editor-toolbar" role="toolbar" aria-label="正文编辑栏"><span class="ps-editor-label">${glyph('edit')} 编辑正文</span><div class="ps-format-actions">${button('<b>B</b>', 'format', 'bold', '', 'aria-label="加粗"')}${button('<i>I</i>', 'format', 'italic', '', 'aria-label="斜体"')}${button('<u>U</u>', 'format', 'underline', '', 'aria-label="下划线"')}<span class="ps-toolbar-separator"></span>${button('• 列表', 'format', 'insertUnorderedList', '', 'aria-label="无序列表"')}${button('1. 列表', 'format', 'insertOrderedList', '', 'aria-label="有序列表"')}${button('清除格式', 'format', 'removeFormat', 'quiet')}</div><div class="ps-editor-save-actions">${button('取消编辑', 'cancel-edit')}${button('保存', 'save-edit', '', 'primary')}</div></div>`;
  }
  function facts() {
    const m = meta();
    return `<dl class="ps-facts">${[['活动时间', m.date + ' ' + m.time], ['活动地点', m.place], ['负责部门', m.department], ['活动负责人', m.owner]].map(([k, v]) => `<div><dt>${k}</dt><dd>${E(v)}</dd></div>`).join('')}</dl>`;
  }
  function cleanHtml(value) {
    const template = document.createElement('template'); template.innerHTML = value || '';
    const allowed = new Set(['P','DIV','BR','STRONG','B','EM','I','U','UL','OL','LI','SPAN']);
    const blocked = new Set(['SCRIPT','STYLE','IFRAME','OBJECT','EMBED','SVG','MATH','INPUT','TEXTAREA','BUTTON','FORM','IMG','LINK','META']);
    function visit(parent) {
      [...parent.children].forEach(node => {
        if (blocked.has(node.tagName)) { node.remove(); return; }
        visit(node);
        if (!allowed.has(node.tagName)) { node.replaceWith(...node.childNodes); return; }
        [...node.attributes].forEach(attribute => node.removeAttribute(attribute.name));
      });
    }
    visit(template.content); return template.innerHTML;
  }
  function captureEditor() {
    if (!editing || !draft) return;
    document.querySelectorAll('.ps-editable[data-field]').forEach(el => {
      if (!touchedFields.has(el.dataset.field)) return;
      draft.fields[el.dataset.field] = el.innerText.replace(/\u00a0/g, ' ').trim();
      draft.htmlFields[el.dataset.field] = cleanHtml(el.innerHTML);
    });
  }
  function sectionContent(k, value, html = '') {
    if (html) return cleanHtml(html);
    if (k === 'flow' && value.split('\n').every(line => line.split('｜').length >= 3)) {
      return `<div class="ps-agenda">${value.split('\n').map(line => {
        const [time, title, owner, ...rest] = line.split('｜');
        return `<div class="ps-agenda-row"><time>${E(time)}</time><div class="ps-agenda-line"><strong>${E(title)}</strong><small>${E(rest.join('｜'))}</small></div><span class="ps-agenda-owner">${E(owner)}</span></div>`;
      }).join('')}</div>`;
    }
    return value.trim() ? value.split('\n').map(line => `<p>${E(line)}</p>`).join('') : '<p class="ps-section-note">本章节尚未填写，请切换负责人视角补充内容。</p>';
  }
  function documentBody() {
    const doc = editing && draft ? draft : shown(), m = meta(), titles = ['一', '二', '三', '四', '五', '六', '七'];
    return `<article class="ps-document ${editing ? 'ps-document-editing' : ''}"><header class="ps-document-header"><p class="ps-doc-kicker">${E(m.department)}</p><h3>${E(m.title)}<br>活动策划书</h3><p class="ps-doc-subtitle">${E(m.theme)}</p></header>${facts()}
      ${chapters.map(([k, name], i) => `<section class="ps-section" id="ps-section-${k}" tabindex="-1"><div class="ps-section-title"><h4>${titles[i]}、${name}</h4>${editing ? '<span class="ps-inline-edit-hint">可直接编辑</span>' : ''}</div><div class="ps-section-body ${editing ? 'ps-editable' : ''}" ${editing ? `contenteditable="true" role="textbox" aria-label="${name}" aria-multiline="true" data-field="${k}" spellcheck="false"` : ''}>${editing ? cleanHtml(doc.htmlFields?.[k] || doc.fields[k].split('\n').map(line => `<p>${E(line) || '<br>'}</p>`).join('')) : sectionContent(k, doc.fields[k], doc.htmlFields?.[k])}</div>${!historyId && record.comments.some(x => x.chapter === k && !x.resolved) ? `<button class="ps-section-note ps-comment-link" data-ps="show-comments" data-value="${k}">${glyph('comment')}${record.comments.filter(x => x.chapter === k && !x.resolved).length} 条待处理意见</button>` : ''}</section>`).join('')}
    </article>`;
  }
  function printPreview() {
    ActivityWord.clearSource?.(active.id);
    return `<div class="ev-plan-workspace ps-a4-workspace"><div class="ps-print-caption">A4 分页预览 · 与导出 Word 使用同一份正文</div>${ActivityWord.markup({ ...active, ...meta() }, { ...active.data.plan, ...shown().fields, version: shown().version }, false)}</div>`;
  }
  function reviewPanel() {
    if (!canViewCurrent()) return historyPanel();
    const { complete, open } = stats();
    if (historyId) return `<div class="ps-empty">${glyph('clock')}<h3>正在查阅历史版本</h3><p>返回当前版本后处理审核意见。</p>${button('返回当前版本', 'current')}</div>`;
    return `<div class="ps-review-head"><h3>提交前检查 <span>${complete === 7 ? '内容齐全' : '待完善'}</span></h3><p>检查结构完整性，内容由审核人确认。</p></div>
      <ul class="ps-checklist"><li><span class="ps-check-icon">${glyph('check')}</span><div><strong>活动信息已关联</strong><small>时间、地点与负责人</small></div></li><li><span class="ps-check-icon ${complete < 7 ? 'warn' : ''}">${complete === 7 ? glyph('check') : '!'}</span><div><strong>策划章节 ${complete} / 7</strong><small>${complete === 7 ? '目标、安排、预算与保障已填写' : '请补全空白章节后提交'}</small></div></li><li><span class="ps-check-icon ${open ? 'warn' : ''}">${open ? '!' : glyph('check')}</span><div><strong>${open ? `${open} 条意见待处理` : '没有待处理意见'}</strong><small>${open ? '点击意见可定位到对应章节' : '可以继续下一步'}</small></div></li></ul>
      <div class="ps-comments-label">审核意见 <span>${record.comments.length}</span></div>
      <div class="ps-threads">${record.comments.length ? record.comments.map(c => `<article class="ps-thread ${c.resolved ? 'resolved' : ''}" id="ps-comment-${E(c.id)}"><div class="ps-thread-author"><span class="ps-avatar">${E(c.author[0])}</span><div><strong>${E(c.author)}</strong><small>${E(c.date)}${c.resolved ? ' · 已处理' : ''}</small></div></div><button class="ps-anchor" data-ps="section" data-value="${c.chapter}">${chapterName(c.chapter)} ${glyph('arrow')}</button><p>${E(c.text)}</p><div class="ps-thread-foot">${record.status !== '已通过' ? button(c.resolved ? '重新打开' : '标记已处理', 'resolve', c.id, 'quiet') : '<span>已随审核归档</span>'}</div></article>`).join('') : '<div class="ps-empty"><p>暂无审核意见。提交后，审核人可按章节提出修改建议。</p></div>'}</div>
      ${canReview() ? `<form class="ps-comment-form" id="ps-comment-form"><label for="ps-comment-chapter">添加审核意见</label><select id="ps-comment-chapter" name="chapter" aria-label="意见关联章节">${chapters.map(([k, name]) => `<option value="${k}" ${k === section ? 'selected' : ''}>${name}</option>`).join('')}</select><textarea name="comment" aria-label="审核意见" placeholder="写清需要修改的内容…" required maxlength="1000" rows="3"></textarea><button class="ps-button" type="submit">添加意见</button></form>` : ''}
      <div class="ps-decision-footer">${reviewProgress()}
      ${canReview() ? `<div class="ps-actions">${button('退回修改', 'reject')}${button('通过审核', 'approve', '', 'primary', open ? 'disabled title="请先处理全部审核意见"' : '')}</div>${open ? '<small>处理全部意见后可通过审核</small>' : ''}` : record.status === '已通过' ? '<span class="ps-badge green">全部审核人已通过，定稿已保留</span>' : `<small>${record.decisions[role] === '已通过' ? '你已通过审核，等待其他审核人确认' : role === 'author' ? '保存正文后，提交时勾选本轮审核人' : '当前由负责人完善策划内容'}</small>`}</div>`;
  }
  function reviewProgress() {
    return record.reviewerIds.length ? `<div class="ps-review-progress"><p>本轮审核人 <small>全部通过后完成审核</small></p>${record.reviewerIds.map(id => `<div><span class="ps-avatar">${reviewerName(id)[0]}</span><strong>${reviewerName(id)}</strong><span class="ps-badge ${record.decisions[id] === '已通过' ? 'green' : 'amber'}">${E(record.decisions[id] || '待审核')}</span></div>`).join('')}</div>` : '<p>本轮审核人尚未选择，提交时可勾选。</p>';
  }
  function historyPanel() {
    const versions = [...(canViewCurrent() ? [{ ...record, id: 'current', note: '当前工作版本' }] : []), ...record.history];
    let comparison = '';
    if (historyId && canViewCurrent()) {
      const old = shown();
      const changed = chapters.filter(([k]) => old.fields[k] !== record.fields[k]);
      comparison = `<div class="ps-compare"><h3>与当前 v${record.version} 对比</h3><p>${changed.length} 个章节有变化</p>${changed.length ? changed.map(([k, name]) => `<section><h4>${name}</h4><div class="ps-diff before"><small>v${old.version}</small><p>${E(old.fields[k] || '尚未填写')}</p></div><div class="ps-diff after"><small>当前 v${record.version}</small><p>${E(record.fields[k] || '尚未填写')}</p></div></section>`).join('') : '<p>正文内容一致，审核状态发生了变化。</p>'}</div>`;
    }
    return `<div class="ps-review-head"><h3>${canViewCurrent() ? '每次提交，都有记录' : '可查看的历史版本'}</h3><p>${canViewCurrent() ? '正文原地保存；修订时保留上一版本。' : '当前版本仅向负责人及本轮选定审核人开放。'}</p></div><ol class="ps-history">${versions.map(v => `<li class="ps-history-item"><button class="ps-history-row" data-ps="history" data-value="${E(v.id)}" ${editing ? 'disabled' : ''} aria-pressed="${historyId ? historyId === v.id : v.id === 'current'}"><i class="ps-history-dot"></i><div><strong>v${v.version} ${v.id === 'current' ? '· 当前版本' : ''}</strong>${badge(v.status)}<p>${E(v.note || '策划书版本记录')}</p><small>${E(v.updated)} · ${E(v.meta?.owner || active.owner)}</small></div></button></li>`).join('')}</ol>${comparison}`;
  }
  function mount() {
    if (!$('.ps-studio')) return;
    if (!canViewShown()) return;
    if (mode === 'print') {
      const stamp = `${active.id}-${role}-${shown().version}-${shown().updated}`;
      buildWord().then(blob => blob.arrayBuffer()).then(buffer => {
        if ($('.ps-a4-workspace') && canViewShown() && stamp === `${active.id}-${role}-${shown().version}-${shown().updated}`) {
          ActivityWord.showBuffer(buffer, `${meta().title}-v${shown().version}.docx`);
        }
      }).catch(() => toast('A4 预览加载失败，请切回正文后重试。'));
    }
    const container = $('#ps-scroll');
    let scheduled = false;
    container?.addEventListener('scroll', () => {
      if (scheduled || mode !== 'read') return;
      scheduled = true;
      requestAnimationFrame(() => {
        scheduled = false;
        const top = container.getBoundingClientRect().top;
        const items = [...container.querySelectorAll('.ps-section')];
        const selected = [...items].reverse().find(el => el.getBoundingClientRect().top <= top + 110) || items[0];
        if (selected) markSection(selected.id.replace('ps-section-', ''));
      });
    }, { passive: true });
  }
  function render(focusSection = null) {
    const host = $('#ev-detail-panel'); if (!host || !active) return;
    captureEditor();
    const position = $('#ps-scroll')?.scrollTop || 0;
    host.innerHTML = markup(active); mount();
    if (focusSection) goSection(focusSection);
    else if ($('#ps-scroll')) $('#ps-scroll').scrollTop = position;
  }
  function markSection(value) {
    section = value;
    document.querySelectorAll('.ps-nav').forEach(el => {
      const selected = el.dataset.value === value; el.dataset.active = String(selected);
      if (selected) el.setAttribute('aria-current', 'location'); else el.removeAttribute('aria-current');
    });
  }
  function goSection(value) {
    if (mode !== 'read') { mode = 'read'; render(); }
    markSection(value);
    const target = $('#ps-section-' + value), canvas = $('#ps-scroll');
    if (target && canvas) {
      canvas.scrollTop += target.getBoundingClientRect().top - canvas.getBoundingClientRect().top - 24;
      target.focus({ preventScroll: true });
    }
    const select = $('#ps-comment-chapter'); if (select) select.value = value;
  }
  function openDialog(title, body, confirm, action, wide = false) {
    let dialog = $('#ps-dialog');
    if (!dialog) { dialog = document.createElement('dialog'); dialog.id = 'ps-dialog'; document.body.append(dialog); }
    dialogTrigger = document.activeElement; dialogAction = action;
    dialog.className = `ps-dialog${wide ? ' ps-dialog-wide' : ''}`;
    dialog.setAttribute('aria-labelledby', 'ps-dialog-title');
    dialog.innerHTML = `<form id="ps-dialog-form"><header><h2 id="ps-dialog-title">${E(title)}</h2><button type="button" class="ps-button quiet" data-ps="close" aria-label="关闭弹窗">✕</button></header><div class="ps-dialog-body">${body}</div><footer>${button(confirm ? '取消' : '关闭', 'close')}${confirm ? `<button type="submit" class="ps-button primary">${E(confirm)}</button>` : ''}</footer></form>`;
    dialog.showModal();
  }
  function closeDialog() { $('#ps-dialog')?.close(); dialogTrigger?.focus(); dialogAction = null; }
  function revision() {
    archiveVersion(record.status === '待审核' ? '撤回审核，另建修订稿。' : record.status === '已通过' ? '审核通过的定稿。' : record.reviewNote || '保留修改前的版本。');
    record.version += 1; record.status = '草稿'; record.submitted = null; record.updated = now();
    record.reviewerIds = []; record.decisions = {};
    historyId = null; editing = false; draft = null; mode = 'read'; persist();
  }
  function archiveVersion(note) {
    record.history.unshift({ id: `version-${Date.now()}`, version: record.version, status: record.status, fields: clone(record.fields), htmlFields: clone(record.htmlFields || {}), meta: clone(record.meta), reviewerIds: [...record.reviewerIds], decisions: clone(record.decisions), updated: record.updated, note });
  }
  async function buildWord() {
    if (!canViewShown() || editing) throw Error('当前版本不可导出');
    const doc = shown(), m = meta(), x = text => E(String(text)).replace(/&#39;/g, '&apos;');
    const para = (text, bold = false, center = false) => `<w:p><w:pPr><w:spacing w:after="160" w:line="360" w:lineRule="auto"/>${center ? '<w:jc w:val="center"/>' : ''}</w:pPr><w:r><w:rPr><w:rFonts w:ascii="SimSun" w:eastAsia="宋体"/><w:sz w:val="${bold ? 30 : 24}"/>${bold ? '<w:b/>' : ''}</w:rPr><w:t xml:space="preserve">${x(text)}</w:t></w:r></w:p>`;
    const rich = html => {
      const template = document.createElement('template'); template.innerHTML = cleanHtml(html);
      const run = (text, style = {}) => `<w:r><w:rPr><w:rFonts w:ascii="SimSun" w:eastAsia="宋体"/><w:sz w:val="24"/>${style.bold ? '<w:b/>' : ''}${style.italic ? '<w:i/>' : ''}${style.underline ? '<w:u w:val="single"/>' : ''}</w:rPr><w:t xml:space="preserve">${x(text)}</w:t></w:r>`;
      const inline = (node, style = {}) => node.nodeType === Node.TEXT_NODE ? run(node.textContent, style) : node.nodeName === 'BR' ? '<w:r><w:br/></w:r>' : [...node.childNodes].map(child => inline(child, { bold: style.bold || ['B','STRONG'].includes(node.nodeName), italic: style.italic || ['I','EM'].includes(node.nodeName), underline: style.underline || node.nodeName === 'U' })).join('');
      const paragraph = (runs, prefix = '') => `<w:p><w:pPr><w:spacing w:after="160" w:line="360" w:lineRule="auto"/></w:pPr>${prefix ? run(prefix) : ''}${runs}</w:p>`;
      const block = (node, prefix = '') => {
        if (['UL','OL'].includes(node.nodeName)) return [...node.children].map((li, i) => block(li, node.nodeName === 'UL' ? '• ' : `${i + 1}. `)).join('');
        if (node.nodeName === 'DIV' && [...node.children].some(child => ['P','DIV','UL','OL'].includes(child.nodeName))) return [...node.childNodes].map(child => block(child)).join('');
        return paragraph(inline(node), prefix);
      };
      return [...template.content.childNodes].map(node => block(node)).join('');
    };
    const body = para(m.title + '活动策划书', true, true) + para(m.department + ' / v' + doc.version, false, true) +
      para('活动时间：' + m.date + ' ' + m.time) + para('活动地点：' + m.place) + para('负责人：' + m.owner) +
      chapters.map(([k, title], i) => para(`${i + 1}. ${title}`, true) + (doc.htmlFields?.[k] ? rich(doc.htmlFields[k]) : doc.fields[k].split('\n').map(line => para(line)).join(''))).join('');
    const zip = new JSZip();
    zip.file('[Content_Types].xml', '<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>');
    zip.file('_rels/.rels', '<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>');
    zip.file('word/document.xml', `<?xml version="1.0" encoding="UTF-8"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>${body}<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/></w:sectPr></w:body></w:document>`);
    return zip.generateAsync({ type: 'blob', mimeType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document' });
  }
  async function exportWord() {
    if (!canViewShown() || editing) { toast('请先保存正文，或选择有权查看的历史版本'); return; }
    if (!window.JSZip) { toast('Word 导出组件未加载，请刷新页面重试。'); return; }
    const doc = shown(), title = meta().title;
    try {
      const blob = await buildWord();
      const url = URL.createObjectURL(blob), anchor = document.createElement('a');
      anchor.href = url; anchor.download = `${title}-策划书-v${doc.version}.docx`;
      document.body.append(anchor); anchor.click(); anchor.remove(); setTimeout(() => URL.revokeObjectURL(url), 1000);
      toast(`已导出 v${doc.version} 策划书`);
    } catch (_) { toast('Word 导出失败，请重试。'); }
  }
  function handle(action, value) {
    if (!record) return;
    if (editing && ['submit','history','current','word','agenda','reset','revise'].includes(action)) { toast('请先保存或取消编辑'); return; }
    if (action === 'section') { if (canViewShown()) goSection(value); return; }
    if (action === 'edit') {
      if (!canEdit() || editing) return;
      draft = { fields: clone(record.fields), htmlFields: clone(record.htmlFields || {}) };
      touchedFields = new Set(); editing = true; editorDirty = false; mode = 'read'; selectionRange = null; render();
      const visible = $('#ps-section-' + section + ' .ps-editable'); visible?.focus({preventScroll:true}); return;
    }
    if (action === 'save-edit') {
      if (!editing || !canEdit()) return;
      captureEditor(); record.fields = clone(draft.fields); record.htmlFields = clone(draft.htmlFields); record.updated = now();
      editing = false; draft = null; editorDirty = false; selectionRange = null; persist(); render();
      toast(active.id < 6 ? '已保存，正文已原地更新' : '已在本次预览保存，正文已原地更新'); return;
    }
    if (action === 'cancel-edit') {
      if (!editing) return;
      editing = false; draft = null; editorDirty = false; selectionRange = null; render(); toast('已取消编辑，恢复保存前的正文'); return;
    }
    if (action === 'format') {
      if (!editing || !canEdit() || !['bold','italic','underline','insertUnorderedList','insertOrderedList','removeFormat'].includes(value)) return;
      if (selectionRange && selectionRange.startContainer.isConnected) { const selected = getSelection(); selected.removeAllRanges(); selected.addRange(selectionRange); }
      else $('.ps-editable')?.focus({preventScroll:true});
      const anchor = getSelection()?.anchorNode, anchorElement = anchor?.nodeType === Node.ELEMENT_NODE ? anchor : anchor?.parentElement;
      const field = anchorElement?.closest('.ps-editable'); if (field) touchedFields.add(field.dataset.field);
      document.execCommand(value, false); captureEditor(); editorDirty = true;
      const status = $('#ps-save-status'); if (status) status.textContent = '编辑中 · 有未保存修改'; return;
    }
    if (action === 'mode') { if (!canViewShown() || editing) return; mode = value; render(); return; }
    if (action === 'panel') { if (!canViewCurrent() && value !== 'history') return; panel = value; render(); $('#ps-tab-' + value)?.focus(); return; }
    if (action === 'show-comments') {
      if (!canViewCurrent() || historyId) return;
      panel = 'review'; render(value);
      const comment = record.comments.find(c => c.chapter === value && !c.resolved);
      if (comment) $('#ps-comment-' + comment.id)?.scrollIntoView({ block: 'nearest', behavior: 'auto' });
      return;
    }
    if (action === 'history') { if (value === 'current' ? !canViewCurrent() : !record.history.some(v => v.id === value)) return; historyId = value === 'current' ? null : value; mode = 'read'; panel = 'history'; render('background'); return; }
    if (action === 'current') { if (!canViewCurrent()) return; historyId = null; render('background'); return; }
    if (action === 'resolve') {
      if (!canResolve()) return;
      const item = record.comments.find(c => c.id === value); if (!item) return;
      item.resolved = !item.resolved; persist(); render(); toast(item.resolved ? '已标记处理完成' : '已重新打开审核意见'); return;
    }
    if (action === 'revise') {
      if (role !== 'author' || historyId || !['待审核', '已通过'].includes(record.status)) return;
      openDialog(record.status === '待审核' ? '撤回审核并创建修订稿' : '创建修订稿',
        `<p>v${record.version} 的正文将保留在版本记录中。创建 v${record.version + 1} 草稿后，可按意见修改并重新提交审核。</p>`,
        '创建修订稿', () => { revision(); closeDialog(); render(); toast('已创建修订稿，可开始编辑'); }); return;
    }
    if (action === 'submit') {
      if (!canEdit() || editing) return;
      const missing = chapters.filter(([k]) => !record.fields[k].trim());
      if (missing.length) { toast('请先填写：' + missing.map(x => x[1]).join('、')); goSection(missing[0][0]); return; }
      openDialog('提交策划书审核', `<p>提交 <strong>v${record.version}</strong>，勾选本轮审核人。只有负责人和选中的审核人可以查看当前版本，其他用户只能查看历史版本。</p><fieldset class="ps-reviewer-picker"><legend>选择审核人（至少一位）</legend>${reviewers.filter(user => user.name !== active.owner).map(user => `<label><input type="checkbox" name="reviewer" value="${user.id}"><span class="ps-avatar">${user.name[0]}</span><span><strong>${user.name}</strong><small>${user.department}</small></span></label>`).join('')}</fieldset><p class="ps-review-rule">多人审核：全部通过后完成审核，任一人退回即进入修订。</p><p class="ps-submit-error" id="ps-submit-error" role="alert" hidden>请至少勾选一位审核人。</p>${unresolved().length ? `<p class="ps-dialog-warning">还有 ${unresolved().length} 条意见未处理，请先处理后提交。</p>` : ''}`,
        unresolved().length ? '' : '确认提交', form => {
          if (!canEdit() || unresolved().length) return;
          const selected = [...new Set(form.getAll('reviewer'))].filter(id => reviewers.some(user => user.id === id && user.name !== active.owner));
          if (!selected.length) { $('#ps-submit-error').hidden = false; $('.ps-reviewer-picker input')?.focus(); return; }
          record.reviewerIds = selected; record.decisions = Object.fromEntries(selected.map(id => [id, '待审核']));
          record.status = '待审核'; record.submitted = clone(record.fields); record.updated = now(); record.reviewNote = '';
          persist(); closeDialog(); render(); toast('已提交给' + selected.map(reviewerName).join('、') + '审核');
        }); return;
    }
    if (action === 'approve' || action === 'reject') {
      if (!canReview()) return;
      if (action === 'approve' && unresolved().length) return;
      openDialog(action === 'approve' ? '通过策划书审核' : '退回修改',
        action === 'approve' ? `<p>确认 v${record.version} 的活动目标、执行安排、预算与应急预案已满足要求。通过后保存审核记录，由负责人确认活动进入执行阶段。</p>` :
          '<label for="ps-reject-reason">退回原因 <textarea id="ps-reject-reason" name="reason" required maxlength="1000" rows="4" placeholder="请说明需要补充或调整的内容"></textarea></label>',
        action === 'approve' ? '确认通过' : '确认退回', form => {
          if (!canReview() || (action === 'approve' && unresolved().length)) return;
          const reason = String(form.get('reason') || '').trim();
          if (action === 'reject' && !reason) { $('#ps-reject-reason').setCustomValidity('请填写具体的退回原因'); $('#ps-reject-reason').reportValidity(); return; }
          const actor = currentUserName();
          record.decisions[role] = action === 'approve' ? '已通过' : '已退回';
          record.status = action === 'reject' ? '已驳回' : record.reviewerIds.every(id => record.decisions[id] === '已通过') ? '已通过' : '待审核';
          record.reviewNote = reason ? actor + '：' + reason : actor + '已通过审核'; record.updated = now();
          // Keep the submitted snapshot before any returned document can be edited.
          if (action === 'reject') {
            archiveVersion(actor + '退回：' + reason);
            record.version += 1; record.status = '已驳回'; record.submitted = null;
            record.reviewerIds = []; record.decisions = {};
          }
          persist(); closeDialog(); render(); toast(action === 'reject' ? '已退回修改，提交版本已保留为历史' : record.status === '已通过' ? '全部审核人已通过，策划书已定稿' : '已记录你的通过意见，等待其他审核人');
        }); return;
    }
    if (action === 'close') { closeDialog(); return; }
    if (action === 'export') { exportWord(); return; }
    if (action === 'word') {
      if (!canViewShown() || editing) return;
      mode = 'read'; render(); ActivityWord.clearSource?.(active.id);
      const stamp = `${active.id}-${role}-${shown().version}-${shown().updated}`;
      openDialog('Word 文件试阅', '<p class="ps-word-hint">选择 .docx 文件按原稿预览。试阅不会替换当前策划正文。</p>' +
        '<div class="ps-actions">' + button('选择 Word 文件', 'pick-word') + '</div>' +
        `<div class="ev-plan-workspace">${ActivityWord.markup({ ...active, ...meta() }, { ...active.data.plan, ...shown().fields, version: shown().version }, false)}</div>`, '', null, true);
      buildWord().then(blob => blob.arrayBuffer()).then(buffer => {
        if ($('#ps-dialog[open] #ev-word-frame') && canViewShown() && stamp === `${active.id}-${role}-${shown().version}-${shown().updated}`) ActivityWord.showBuffer(buffer, `${meta().title}-v${shown().version}.docx`);
      }).catch(() => toast('文档试阅加载失败，请关闭后重试。')); return;
    }
    if (action === 'pick-word') { ActivityWord.handle('word-import'); return; }
    if (action === 'agenda') { setDetailTab('agenda'); return; }
    if (action === 'reset') {
      if (role !== 'author') return;
      openDialog('重置本活动的预览数据', '<p>清除本活动在此浏览器中的策划修改、审核意见与版本记录，恢复最初示例。其他活动不受影响。</p>', '重置示例', () => {
        try { localStorage.removeItem(key(active.id)); } catch (_) {}
        records.set(active.id, clone(originals.get(active.id)));
        currentId = null; historyId = null; closeDialog(); render(); toast('已恢复本活动示例');
      }); return;
    }
  }
  document.addEventListener('click', e => {
    const target = e.target.closest('[data-ps]'); if (!target || target.disabled) return;
    handle(target.dataset.ps, target.dataset.value);
  });
  document.addEventListener('input', e => {
    if (e.target.id === 'ps-reject-reason') e.target.setCustomValidity('');
    if (!e.target.closest('.ps-editable') || !editing || !canEdit()) return;
    touchedFields.add(e.target.closest('.ps-editable').dataset.field); captureEditor(); editorDirty = true;
    const status = $('#ps-save-status'); if (status) status.textContent = '编辑中 · 有未保存修改';
  });
  document.addEventListener('change', e => {
    if (e.target.id === 'ps-role') {
      if (editing || !['author', ...reviewers.map(user => user.id)].includes(e.target.value)) return;
      role = e.target.value; historyId = null; panel = 'review'; selectionRange = null; render(); $('#ps-role')?.focus();
    }
    if (e.target.matches('.ps-reviewer-picker input') && $('#ps-submit-error')) $('#ps-submit-error').hidden = true;
  });
  document.addEventListener('submit', e => {
    if (e.target.id === 'ps-dialog-form') { e.preventDefault(); dialogAction?.(new FormData(e.target)); }
    if (e.target.id !== 'ps-comment-form') return;
    e.preventDefault();
    if (!canReview()) return;
    const form = new FormData(e.target), text = String(form.get('comment') || '').trim();
    if (!text) { toast('请填写具体的审核意见'); return; }
    record.comments.unshift({ id: 'comment-' + Date.now(), chapter: String(form.get('chapter')), author: currentUserName(), date: now(), text, resolved: false });
    persist(); render(); toast('已添加审核意见');
  });
  document.addEventListener('keydown', e => {
    if (editing && (e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') { e.preventDefault(); handle('save-edit'); return; }
    if (editing && e.key === 'Escape') { e.preventDefault(); handle('cancel-edit'); return; }
    const tab = e.target.closest('.ps-inspector-tabs [role="tab"]');
    if (tab && ['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(e.key)) {
      e.preventDefault(); panel = !canViewCurrent() ? 'history' : e.key === 'Home' ? 'review' : e.key === 'End' ? 'history' : panel === 'review' ? 'history' : 'review';
      render(); $('#ps-tab-' + panel)?.focus();
    }
  });
  document.addEventListener('selectionchange', () => {
    if (!editing) return;
    const selection = getSelection(); if (!selection?.rangeCount) return;
    const range = selection.getRangeAt(0), start = range.startContainer.nodeType === Node.ELEMENT_NODE ? range.startContainer : range.startContainer.parentElement;
    const end = range.endContainer.nodeType === Node.ELEMENT_NODE ? range.endContainer : range.endContainer.parentElement;
    if (start?.closest('.ps-editable') && end?.closest('.ps-editable') === start.closest('.ps-editable')) selectionRange = range.cloneRange();
  });
  document.addEventListener('mousedown', e => { if (e.target.closest('[data-ps="format"]')) e.preventDefault(); });
  document.addEventListener('paste', e => { if (!editing || !e.target.closest('.ps-editable')) return; e.preventDefault(); document.execCommand('insertText', false, e.clipboardData.getData('text/plain')); });
  document.addEventListener('drop', e => { if (e.target.closest('.ps-editable')) e.preventDefault(); });
  window.addEventListener('beforeunload', e => { if (editing && editorDirty) { e.preventDefault(); e.returnValue = ''; } });
  window.PlanStudio = { markup, mount };
})();
