/* V6 organization design review. No requests, credentials, or business writes. */
(() => {
  'use strict';
  const $ = (s, root = document) => root.querySelector(s);
  const E = esc;
  const copy = value => JSON.parse(JSON.stringify(value));
  const types = ['部门', '中心', '小组', '分队'];
  const orgTypes = ['党建办公室', '学生会', '社团', '青年志愿者协会', '科技协会', '其他组织'];
  const steps = ['组织信息', '组织架构', '管理员账号', '确认并创建'];
  const stepNotes = ['名称、类型与专属颜色', '搭建部门与下级小组', '设置第一个登录账号', '核对信息，选择功能'];
  const headings = ['先认识一下你的组织', '让每一份分工，都有位置', '为组织设置一位管理员', '准备好，开启团队协作'];
  let nextId = 100, selected = 1, tab = 'children', query = '', collapsed = new Set(), toastTimer, mobileTreeOpen = false;
  let org = { name: '信息与智能工程学院党建办公室', shortName: '党建办公室', type: '党建办公室', color: '#1668DC', packs: ['党建包'], source: '创建组织时建立' };
  let units = [
    { id: 1, parentId: null, name: '组织部', unitType: '部门', leader: 2, sort: 0 },
    { id: 5, parentId: 1, name: '培训组', unitType: '小组', leader: 5, sort: 0 },
    { id: 6, parentId: 1, name: '组织建设组', unitType: '小组', leader: null, sort: 1 },
    { id: 2, parentId: null, name: '文秘部', unitType: '部门', leader: 1, sort: 1 },
    { id: 7, parentId: 2, name: '综合事务组', unitType: '小组', leader: null, sort: 0 },
    { id: 3, parentId: null, name: '新媒体中心', unitType: '中心', leader: 3, sort: 2 },
    { id: 8, parentId: 3, name: '采编组', unitType: '小组', leader: 6, sort: 0 },
    { id: 9, parentId: 3, name: '视觉组', unitType: '小组', leader: null, sort: 1 },
    { id: 4, parentId: null, name: '青年科技部', unitType: '部门', leader: 4, sort: 3 },
  ];
  // Account records, distinct from the session-based member roster in 成员管理.
  let people = [
    { id: 1, name: '林晓', username: '2024240101', unit: 2, role: '总负责人', status: '正常' },
    { id: 2, name: '陈禾', username: '2024240208', unit: 1, role: '组织部长', status: '正常' },
    { id: 3, name: '周宁', username: '2024240312', unit: 3, role: '部门负责人', status: '正常' },
    { id: 4, name: '王悦', username: '2025250116', unit: 4, role: '干事', status: '正常' },
    { id: 5, name: '李知', username: '2025250221', unit: 5, role: '干事', status: '正常' },
    { id: 6, name: '吴桐', username: '2023230318', unit: 8, role: '干事', status: '正常' },
    { id: 7, name: '赵明', username: '2025250105', unit: 6, role: '干事', status: '已停用' },
  ];
  const initial = copy({org, units, people});
  const host = document.createElement('section');
  host.id = 'org'; host.className = 'view org-view'; host.hidden = true; host.setAttribute('aria-label', '组织架构');
  $('.content').append(host); viewNames.org = '组织架构';
  const label = document.createElement('div'); label.className = 'nav-label'; label.textContent = '系统管理';
  const nav = document.createElement('nav'); nav.className = 'nav'; nav.innerHTML = `<button data-view="org">${icon('users')}组织架构</button>`;
  $('.side-footer').before(label, nav);
  $('.brand').innerHTML = `<img src="assets/brand/concerto-symbol.svg" width="34" height="34" alt=""><div><strong style="font-size:18px;letter-spacing:1px">协奏</strong><small>高校学生组织智能工作台</small></div>`;
  const css = document.createElement('link'); css.rel = 'stylesheet'; css.href = 'PAMS-UI-v6-org.css'; document.head.append(css);
  const dialog = document.createElement('dialog'); dialog.className = 'org-dialog'; dialog.setAttribute('aria-labelledby', 'org-dialog-title'); document.body.append(dialog);
  const auth = document.createElement('dialog'); auth.className = 'org-dialog org-auth-dialog'; auth.setAttribute('aria-labelledby', 'org-auth-title'); document.body.append(auth);
  const toastEl = document.createElement('div'); toastEl.className = 'org-toast'; toastEl.role = 'status'; toastEl.hidden = true; document.body.append(toastEl);
  let onSave = null, authMode = 'login', wizardStep = 0, loginUser = '', created = false, draft = null, focusBeforeDialog;

  const btn = (text, action, value = '', primary = false) => `<button type="button" class="btn${primary ? ' primary' : ''}" data-org-action="${action}" data-value="${E(value)}">${text}</button>`;
  const link = (text, action, value = '') => `<button type="button" class="link" data-org-action="${action}" data-value="${E(value)}">${E(text)}</button>`;
  const find = id => units.find(n => n.id === Number(id));
  const children = id => units.filter(n => n.parentId === id).sort((a, b) => a.sort - b.sort || a.id - b.id);
  const person = id => people.find(p => p.id === id);
  const descendants = id => [id, ...children(id).flatMap(n => descendants(n.id))];
  const countPeople = id => id === 0 ? people.length : people.filter(p => descendants(id).includes(p.unit)).length;
  const path = id => { const n = find(id); return n ? [...(n.parentId ? path(n.parentId) : []), n] : []; };
  const avatar = name => `<span class="avatar" aria-hidden="true">${E(name.slice(0, 1))}</span>`;
  const opts = (values, current) => values.map(v => `<option${v === current ? ' selected' : ''}>${E(v)}</option>`).join('');
  const field = (label, name, value = '', attrs = '') => `<label>${label}<input name="${name}" value="${E(value)}" ${attrs}></label>`;
  const info = pairs => `<dl class="org-info">${pairs.map(([a, b]) => `<div><dt>${E(a)}</dt><dd>${E(b)}</dd></div>`).join('')}</dl>`;
  function toast(text) { toastEl.textContent = text; toastEl.hidden = false; clearTimeout(toastTimer); toastTimer = setTimeout(() => toastEl.hidden = true, 3800); }
  function error(message, root = dialog) { const el = $('.org-form-error', root); el.textContent = message; if (message) { el.tabIndex = -1; el.focus(); } }
  function modal(title, body, save, saveLabel = '保存修改', danger = false) {
    focusBeforeDialog = document.activeElement;
    onSave = save;
    dialog.innerHTML = `<form id="org-editor-form"><header class="org-dialog-head"><h2 id="org-dialog-title">${E(title)}</h2><button type="button" class="icon-btn" data-org-action="close-dialog" aria-label="关闭">${icon('close')}</button></header><div class="org-dialog-content"><div class="org-form-error" role="alert"></div><div class="org-form">${body}</div></div><footer class="org-dialog-foot">${btn('取消', 'close-dialog')}<button class="btn ${danger ? 'danger' : 'primary'}" type="submit">${E(saveLabel)}</button></footer></form>`;
    dialog.showModal();
  }
  dialog.addEventListener('submit', e => {
    e.preventDefault(); const values = Object.fromEntries(new FormData(e.target));
    if (onSave?.(values) !== false) { dialog.close(); render(); }
  });
  dialog.addEventListener('close', () => { if (focusBeforeDialog?.isConnected) focusBeforeDialog.focus(); else $('[data-org-action="edit"], [data-org-action="add-root"]', host)?.focus(); });

  function leaderText(n) { const p = person(n.leader); return p ? `${avatar(p.name)}<span>${E(p.name)}</span>` : '<span class="org-unassigned">待设置负责人</span>'; }
  function matches(n) { return !query || n.name.toLowerCase().includes(query.toLowerCase()) || children(n.id).some(matches); }
  function tree(list) {
    return `<ul>${list.filter(matches).map(n => { const kids = children(n.id), open = query || !collapsed.has(n.id); return `<li><div class="org-tree-row ${selected === n.id ? 'selected' : ''}"><button class="org-fold ${open ? '' : 'collapsed'}" type="button" data-org-action="fold" data-value="${n.id}" aria-label="${open ? '收起' : '展开'}${E(n.name)}" aria-expanded="${!!open}" ${kids.length ? '' : 'disabled'}>${icon('right')}</button><button type="button" class="org-node" data-org-action="select" data-value="${n.id}" ${selected === n.id ? 'aria-current="true"' : ''}>${icon(n.unitType === '小组' || n.unitType === '分队' ? 'users' : 'folder')}<span>${E(n.name)}</span><small aria-label="含下级 ${countPeople(n.id)} 个账号">${countPeople(n.id)}</small></button></div>${kids.length && open ? tree(kids) : ''}</li>`; }).join('')}</ul>`;
  }
  function childCard(n) {
    return `<button class="org-child" type="button" data-org-action="select" data-value="${n.id}"><div class="org-child-top"><span class="org-unit-icon">${icon(n.unitType === '小组' ? 'users' : 'folder')}</span><strong>${E(n.name)}<span class="org-child-type">${E(n.unitType)}</span></strong>${icon('right')}</div><div class="org-child-bottom">${leaderText(n)}<span class="org-child-count">${countPeople(n.id)} 人${children(n.id).length ? ' / ' + children(n.id).length + ' 个下级' : ''}</span></div></button>`;
  }
  function empty(title, desc, action = '') { return `<div class="org-empty">${icon('users')}<h3>${E(title)}</h3><p>${E(desc)}</p>${action}</div>`; }
  function renderBody(n) {
    const id = n?.id ?? 0, kids = children(n ? n.id : null), direct = people.filter(p => !n || p.unit === n.id);
    if (tab === 'children') return `<div class="org-section-caption"><h3>${n ? '直属下级' : '一级部门与中心'}</h3><small>按排序值排列，可点击查看</small></div>${kids.length ? `<div class="org-children">${kids.map(childCard).join('')}</div>` : empty('还没有下级节点', '需要更细的分工时，可以在这里添加小组或分队。', btn('添加下级节点', 'add-child', id))}<div class="org-next">${icon('users')}<div><h3>${n ? '让分工与成员归属保持一致' : '架构已经就位，接下来连接成员'}</h3><p>花名册在成员管理中维护；账号归属与角色在用户管理中设置。</p></div>${link('成员与账号入口', 'member-entry', id)}</div>`;
    if (tab === 'members') return `<div class="org-section-caption"><h3>${n ? '只显示直属账号，不含下级节点' : '当前组织的全部账号'}</h3>${link('成员与账号入口', 'member-entry', id)}</div>${direct.length ? `<div style="overflow-x:auto"><table class="org-table"><thead><tr><th scope="col">姓名 / 账号</th>${n ? '' : '<th scope="col">所属节点</th>'}<th scope="col">主角色</th><th scope="col">账号状态</th></tr></thead><tbody>${direct.map(p => `<tr><td>${avatar(p.name)}${E(p.name)}<small>${E(p.username)}</small></td>${n ? '' : `<td>${E(find(p.unit)?.name ?? '待分配')}</td>`}<td>${E(p.role)}</td><td><span class="badge ${p.status === '正常' ? 'success' : 'neutral'}">${E(p.status)}</span></td></tr>`).join('')}</tbody></table></div><p class="muted" style="font-size:11px;margin-top:14px">账号主角色与节点负责人分别维护。任命负责人不会自动更改角色或管理员身份。</p>` : empty('还没有直属账号', '可以先维护花名册，再由用户管理分配账号与归属。', btn('查看成员与账号入口', 'member-entry', id))}`;
    return n ? `${info([['节点名称', n.name], ['节点类型', n.unitType], ['所属上级', n.parentId ? find(n.parentId).name : org.shortName + '（组织根节点）'], ['节点负责人', person(n.leader)?.name ?? '尚未设置'], ['显示排序', String(n.sort)], ['完整路径', [org.shortName, ...path(n.id).map(x => x.name)].join(' / ') ]])}<div class="org-next">${icon('check')}<div><h3>与创建组织时的架构保持连续</h3><p>节点名称、类型与上下级沿用创建时的数据；后续修改继续使用同一套部门记录。</p></div></div>` : `${info([['组织全称', org.name], ['组织简称', org.shortName], ['组织类型', org.type], ['组织主题色', org.color], ['扩展功能', org.packs.join('、') || '未启用'], ['数据来源', org.source]])}<div class="org-next">${icon('palette')}<div><h3>登录展示与工作台使用同一份组织资料</h3><p>名称、简称与主题色修改后，打开登录页预览即可核对展示。</p></div>${link('编辑组织资料', 'profile')}</div>`;
  }
  function renderTree() {
    const root = $('#org-tree-list', host);
    root.innerHTML = `<div class="org-tree-root"><div class="org-tree-row ${selected === 0 ? 'selected' : ''}"><span class="org-fold"></span><button type="button" class="org-node" data-org-action="select" data-value="0" ${selected === 0 ? 'aria-current="true"' : ''}>${icon('grid')}<span>${E(org.shortName)}</span><small>${people.length}</small></button></div></div>${children(null).some(matches) ? tree(children(null)) : '<p class="org-search-empty">没有匹配的节点，请调整名称。</p>'}`;
  }
  function render() {
    if (selected && !find(selected)) selected = 0;
    const n = find(selected), kids = children(n ? n.id : null), direct = people.filter(p => !n || p.unit === n.id);
    const missing = units.filter(x => !x.leader).length;
    host.style.setProperty('--org-accent', org.color);
    host.innerHTML = `<div class="heading"><div><h1 tabindex="-1">组织架构</h1><p>从组织到部门，让每一份分工和归属清晰可见。</p></div><div class="actions">${btn(icon('palette') + '登录页预览', 'auth-login')}${btn(icon('plus') + '添加一级节点', 'add-root', '', true)}</div></div>
      ${created ? `<div class="org-alert">${E(org.shortName)}的架构已沿用创建时的设置，当前有 ${units.length} 个节点。下一步可设置负责人、完善成员归属。${link('查看成员与账号入口', 'member-entry')}</div>` : ''}
      <div class="org-profile"><span class="org-emblem">${icon('grid')}</span><div class="org-profile-main"><h2>${E(org.name)}</h2><p>${E(org.type)} · ${E(org.source)} · ${org.packs.length ? E(org.packs.join('、')) + '已启用' : '暂未启用扩展功能'}</p></div><div class="org-profile-meta"><div><b>${units.length}</b><small>架构节点</small></div><div><b>${people.length}</b><small>组织账号</small></div><div><b class="${missing ? 'org-unassigned' : ''}">${missing}</b><small>待设负责人</small></div></div>${link('组织资料', 'select-profile')}</div>
      <div class="org-layout"><aside class="org-directory${mobileTreeOpen ? ' mobile-expanded' : ''}" aria-label="架构导航"><header class="org-directory-head"><h2>组织层级 <small>${units.length}</small><span class="org-mobile-current"> / ${E(n?.name ?? org.shortName)}</span></h2><span class="org-desktop-fold">${link(collapsed.size ? '展开全部' : '收起全部', 'fold-all')}</span><button type="button" class="link org-mobile-toggle" data-org-action="mobile-tree" aria-expanded="${mobileTreeOpen}" aria-controls="org-tree-list">${mobileTreeOpen ? '收起导航' : '切换节点'}</button></header><label class="org-tree-search">${icon('search')}<input id="org-search" type="search" placeholder="搜索部门 / 小组" aria-label="搜索架构节点" value="${E(query)}"></label><div class="org-tree" id="org-tree-list"></div><footer class="org-directory-foot">${btn(icon('plus') + '添加一级节点', 'add-root')}<p>右侧数字为含下级的账号数</p></footer></aside>
      <section class="org-detail" aria-label="${E(n?.name ?? org.shortName)}详情"><header class="org-detail-head"><div class="org-path"><button type="button" data-org-action="select" data-value="0">${E(org.shortName)}</button>${n ? path(n.id).map(p => `${icon('right')}<button type="button" data-org-action="select" data-value="${p.id}">${E(p.name)}</button>`).join('') : `${icon('right')}组织总览`}</div><div class="org-title-line"><div><h2>${E(n?.name ?? org.shortName)}</h2><span class="badge neutral">${E(n?.unitType ?? '组织')}</span></div><div class="actions">${btn('编辑' + (n ? '节点' : '组织资料'), n ? 'edit' : 'profile', selected)}${btn(icon('plus') + '添加下级', n ? 'add-child' : 'add-root', selected, true)}</div></div><p class="org-detail-desc">${n ? `属于${E(n.parentId ? find(n.parentId).name : org.shortName)}，当前有 ${kids.length} 个直属下级节点。` : '管理组织的完整架构，沿用创建时建立的部门与小组。'}</p><div class="org-detail-summary">${n ? `<div class="org-person">${n.leader ? leaderText(n) : '<span class="org-unassigned">负责人待设置</span>'}<button type="button" class="org-inline-link" data-org-action="leader" data-value="${n.id}">${n.leader ? '更换' : '设置负责人'}</button></div>` : `<div><span class="muted">一级节点</span><b>${kids.length}</b></div>`}<div><span class="muted">${n ? '直属账号' : '组织账号'}</span><b>${direct.length}</b></div><div><span class="muted">含下级账号</span><b>${countPeople(selected)}</b></div></div></header>
      <div class="org-tabs" role="group" aria-label="节点详情分类">${[['children', n ? '下级节点' : '组织总览', kids.length], ['members', n ? '直属账号' : '组织账号', direct.length], ['info', n ? '节点信息' : '组织资料', null]].map(([id, text, count]) => `<button type="button" data-org-action="tab" data-value="${id}" aria-pressed="${tab === id}">${text}${count !== null ? `<small>${count}</small>` : ''}</button>`).join('')}</div><div class="org-detail-body">${renderBody(n)}</div><footer class="org-detail-foot"><span>${n ? '节点与账号归属共用部门数据；负责人不自动获得权限。' : '组织根节点代表当前组织，不能作为普通部门删除。'}</span>${n ? link('删除节点', 'delete', n.id) : link('登录页预览', 'auth-login')}</footer></section></div>
      <div class="org-review-note"><span>V6 组织架构评审原型 · 示例数据 · 修改仅在本次预览内有效，刷新后恢复。</span><span>${link('演示创建组织', 'auth-register')}　${link('重置演示', 'reset')}</span></div>`;
    renderTree();
    // Only organization-specific chrome follows the active prototype tenant.
    $('.org-label').innerHTML = `<small>当前组织</small><b>${E(org.shortName)}</b>`;
    $('.parent-crumb').textContent = org.shortName;
  }

  function editUnit(id, mode = 'edit') {
    const node = find(id), edit = mode === 'edit', parentId = edit ? node.parentId : mode === 'child' ? Number(id) : null;
    const parentName = parentId ? find(parentId).name : org.shortName + '（组织根节点）';
    modal(edit ? '编辑节点' : parentId ? '添加下级节点' : '添加一级节点',
      `<div class="org-form-note">所属上级：${E(parentName)}${edit ? '<br>编辑节点信息将保留当前的上下级关系。' : ''}</div>` +
      field('节点名称', 'name', edit ? node.name : '', 'required maxlength="100" placeholder="如：组织部、培训组"') +
      `<div class="org-pair"><label>节点类型<select name="unitType">${opts(types, edit ? node.unitType : parentId ? '小组' : '部门')}</select></label>${field('显示排序', 'sort', edit ? node.sort : children(parentId).length, 'type="number" min="0" max="9999" required')}</div>` +
      `<label>节点负责人（选填）<select name="leader"><option value="">暂不设置</option>${people.filter(p => p.status === '正常').map(p => `<option value="${p.id}"${edit && node.leader === p.id ? ' selected' : ''}>${E(p.name)} / ${E(find(p.unit)?.name ?? '待分配')}</option>`).join('')}</select><small>从当前组织账号中选择；不会自动更改账号角色。</small></label>`, values => {
        const name = values.name.trim();
        if (!name) { error('请填写节点名称'); return false; }
        if (units.some(n => n.name === name && (!edit || n.id !== node.id))) { error(`「${name}」已存在，请使用组织内唯一的节点名称。`); return false; }
        const patch = {name, unitType: values.unitType, leader: values.leader ? Number(values.leader) : null, sort: Number(values.sort)};
        if (edit) Object.assign(node, patch); else { const n = {id: nextId++, parentId, ...patch}; units.push(n); selected = n.id; tab = 'children'; if (parentId) collapsed.delete(parentId); }
        toast(edit ? '节点信息已更新到原型' : '节点已添加到原型');
      }, edit ? '保存修改' : '添加节点');
  }
  function setLeader(id) {
    const n = find(id);
    modal('设置' + n.name + '负责人', `<label>从当前组织账号中选择<select name="leader"><option value="">暂不设置</option>${people.filter(p => p.status === '正常').map(p => `<option value="${p.id}"${n.leader === p.id ? ' selected' : ''}>${E(p.name)} / ${E(p.username)}</option>`).join('')}</select></label><div class="org-form-note">负责人用于明确组织分工。账号主角色和管理员身份仍在用户与权限模块单独管理。</div>`, values => { n.leader = values.leader ? Number(values.leader) : null; toast('负责人已更新到原型'); }, '保存负责人');
  }
  function deleteUnit(id) {
    const n = find(id), kids = children(n.id), assigned = people.filter(p => p.unit === n.id), leaderRefs = n.leader ? 1 : 0;
    if (kids.length || assigned.length) {
      modal('暂时无法删除' + n.name, `<div class="org-form-note">${kids.length ? `仍有 ${kids.length} 个直属下级节点，请先逐一处理下级。<br>` : ''}${assigned.length ? `仍有 ${assigned.length} 个直属账号（含停用账号），请先在用户管理中调整归属。` : ''}</div><p style="font-size:12px">为保留组织关系，不提供连同下级和账号一起删除的操作。</p>`, () => {}, '我知道了'); return;
    }
    modal('删除' + n.name + '？', `<p style="font-size:13px">将删除「${E(n.name)}」。当前没有下级节点或直属账号。${leaderRefs ? '节点的负责人关联也将清除，账号本身保留。' : ''}</p><p class="org-form-note">正式落地时还需检查历史业务引用；原型只演示确认流程。</p>`, () => { selected = n.parentId ?? 0; units = units.filter(x => x.id !== n.id); toast('节点已从原型中删除'); }, '确认删除', true);
  }
  function editProfile() {
    modal('编辑组织资料', field('组织全称', 'name', org.name, 'required maxlength="100"') + field('组织简称', 'shortName', org.shortName, 'maxlength="30"') + `<div class="org-pair"><label>组织类型<input value="${E(org.type)}" readonly><small>组织类型沿用创建时的设置。</small></label><label>主题色<input type="color" name="color" value="${E(org.color)}"></label></div><div class="org-form-note">全称、简称与主题色同步用于登录页的组织展示。账号登录后进入其所属组织。</div>`, values => {
      if (!values.name.trim()) { error('请填写组织全称'); return false; }
      org.name = values.name.trim(); org.shortName = values.shortName.trim() || org.name; org.color = values.color;
      toast('组织资料已更新，可打开登录页预览核对');
    });
  }
  function memberEntry() {
    const n = find(selected);
    modal('成员与账号', `<div class="org-form-note">当前组织：${E(org.shortName)}${n ? '<br>当前节点：' + E(n.name) : ''}</div><div><h3>花名册与届别</h3><p class="muted" style="font-size:12px;margin:5px 0 10px">在成员管理维护名册、届别和任职记录；可从花名册批量开户。</p>${btn('打开成员管理原型', 'go-members')}</div><div><h3>账号归属与权限</h3><p class="muted" style="font-size:12px;margin:5px 0">已开户成员在用户管理调整所属节点与主角色，管理员身份在权限模块维护。</p></div><div class="org-form-note">当前 V6 的成员管理使用独立示例数据。正式落地时会共用当前组织与部门 ID，并传入所选部门筛选。</div>${btn('查看邀请码预览', 'invite')}`, () => {}, '完成查看');
  }
  function invite() {
    dialog.close();
    modal('邀请码预览', `<div class="org-form-note">邀请码目前仅支持生成，系统尚无兑换入组入口。当前演示码不能用于注册或登录。</div><label>预设部门<input value="${E(find(selected)?.name ?? '暂不分配') }" readonly></label><label>可分配角色<select name="role"><option>干事</option><option>部门负责人</option></select><small>正式版本只展示当前操作者可分配的角色。</small></label><label>有效期<select name="ttl"><option value="72">72 小时</option><option value="24">24 小时</option><option value="168">7 天</option></select></label>`, values => {
      const code = 'DEMO-' + Math.random().toString(36).slice(2, 10).toUpperCase();
      dialog.close();
      modal('演示邀请码', `<p class="org-code">${code}</p>${info([['预设部门', find(selected)?.name ?? '暂不分配'], ['预设角色', values.role], ['有效期', values.ttl + ' 小时']])}<div class="org-form-note">这是一段演示码。邀请兑换能力完成后，才能形成完整的入组流程。</div>`, () => {}, '完成查看');
      return false;
    }, '生成演示码');
  }

  function newDraft() {
    return { name: '', shortName: '', type: '青年志愿者协会', color: '#1668DC', packs: [], username: '', realName: '', phone: '', units: [{ id: nextId++, name: '组织部', unitType: '部门', children: [] }, { id: nextId++, name: '宣传部', unitType: '部门', children: [] }] };
  }
  const draftCount = list => list.reduce((n, x) => n + 1 + draftCount(x.children), 0);
  function draftNode(id, list = draft.units) { for (const n of list) { if (n.id === Number(id)) return n; const hit = draftNode(id, n.children); if (hit) return hit; } }
  function removeDraftNode(id, list = draft.units) { const i = list.findIndex(n => n.id === Number(id)); if (i >= 0) list.splice(i, 1); else list.forEach(n => removeDraftNode(id, n.children)); }
  function validateTree() {
    if (!draft.units.length) return '请至少添加一个部门或小组';
    const names = new Set(); let issue = '';
    function walk(list) { for (const n of list) { const name = n.name.trim(); if (!name) { issue = '请填写每个部门或小组的名称，或删除空白节点'; return; } if (names.has(name)) { issue = `「${name}」名称重复，请使用不同的名称`; return; } names.add(name); walk(n.children); if (issue) return; } }
    walk(draft.units); return issue;
  }
  function draftTree(list) {
    return `<ul class="org-draft-tree">${list.map(n => `<li><div class="org-draft-row"><input aria-label="节点名称" data-draft-node="${n.id}" data-key="name" value="${E(n.name)}" maxlength="100" placeholder="节点名称"><select aria-label="节点类型" data-draft-node="${n.id}" data-key="unitType">${opts(types, n.unitType)}</select><button type="button" data-org-action="draft-child" data-value="${n.id}" aria-label="为${E(n.name || '未命名节点')}添加子节点">+ 子节点</button><button type="button" data-org-action="draft-remove" data-value="${n.id}" aria-label="删除${E(n.name || '未命名节点')}">${icon('close')}</button></div>${n.children.length ? draftTree(n.children) : ''}</li>`).join('')}</ul>`;
  }
  function reviewTree(list) { return `<ul class="org-review-tree">${list.map(n => `<li>${E(n.name.trim())}<small>${E(n.unitType)}</small>${n.children.length ? reviewTree(n.children) : ''}</li>`).join('')}</ul>`; }
  function authAside() {
    return `<aside class="org-auth-aside"><div class="org-auth-brand"><img src="assets/brand/concerto-symbol.svg" width="34" height="34" alt="">协奏</div><h3>${authMode === 'login' ? '把每一份热爱，<br>汇成共同的成果。' : '从一个组织，<br>到一群同行的人。'}</h3>${authMode === 'register' ? `<div class="org-wizard-steps" aria-label="创建组织步骤">${steps.map((s, i) => `<button type="button" class="org-wizard-step ${i === wizardStep ? 'current' : i < wizardStep ? 'complete' : ''}" data-org-action="wizard-step" data-value="${i}" ${i < wizardStep ? '' : 'disabled'} ${i === wizardStep ? 'aria-current="step"' : ''}><span>${i < wizardStep ? '✓' : i + 1}</span><div><b>${s}</b><small>${stepNotes[i]}</small></div></button>`).join('')}</div>` : `<p>从一次活动到一届传承，<br>让组织里的协作，在这里有序发生。</p><div class="org-auth-map">${icon('grid')} ${E(org.shortName)}<div><span>组织与成员</span><span>活动与协作</span><span>资料与传承</span></div></div>`}<div class="org-auth-note">众智协奏，事事闭环。</div></aside>`;
  }
  function wizardBody() {
    if (wizardStep === 0) return field('组织全称', 'name', draft.name, 'required maxlength="100" placeholder="如：信息与智能工程学院青年志愿者协会" data-draft="name"') + field('组织简称（选填）', 'shortName', draft.shortName, 'maxlength="30" placeholder="如：青年志愿者协会" data-draft="shortName"') + `<div><p style="font-size:12px;margin-bottom:8px">组织类型</p><div class="org-type-grid">${orgTypes.map(t => `<label><input type="radio" name="type" value="${t}" ${draft.type === t ? 'checked' : ''} data-draft="type">${t}</label>`).join('')}</div></div><label>组织主题色<input type="color" name="color" value="${draft.color}" data-draft="color"><small>将用于组织标识与工作台主操作。</small></label>`;
    if (wizardStep === 1) return `<div class="org-form-note">先搭建基本架构即可，创建后可继续调整并设置负责人。节点类型和层级将完整保留。</div><div id="org-draft-tree">${draftTree(draft.units)}</div>${btn(icon('plus') + '添加一级节点', 'draft-root')}<small>支持部门、中心、小组、分队；组织内节点名称须唯一。</small>`;
    if (wizardStep === 2) return `<div class="org-form-note">请填写演示账号。此原型不会连接服务器；密码在离开本步后清空。</div>${field('管理员用户名', 'username', draft.username, 'required maxlength="50" autocomplete="off" placeholder="设置用于登录的用户名" data-draft="username"')}<div class="org-pair">${field('演示密码', 'password', '', 'type="password" required minlength="6" autocomplete="new-password" placeholder="至少 6 位，请勿使用真实密码"')}${field('确认密码', 'confirmPassword', '', 'type="password" required minlength="6" autocomplete="new-password" placeholder="再次输入演示密码"')}</div><div class="org-pair">${field('管理员姓名（选填）', 'realName', draft.realName, 'data-draft="realName" maxlength="50"')}${field('联系电话（选填）', 'phone', draft.phone, 'type="tel" data-draft="phone" maxlength="30"')}</div>`;
    return `<div class="org-review-section"><header><h3>组织信息</h3>${link('修改', 'wizard-step', 0)}</header><strong>${E(draft.name)}</strong><p>${E(draft.type)}${draft.shortName ? ' · ' + E(draft.shortName) : ''}</p></div><div class="org-review-section"><header><h3>组织架构 / ${draftCount(draft.units)} 个节点</h3>${link('修改', 'wizard-step', 1)}</header>${reviewTree(draft.units)}</div><div class="org-review-section"><header><h3>管理员</h3>${link('修改', 'wizard-step', 2)}</header><strong>${E(draft.realName || draft.username)}</strong><p>登录账号：${E(draft.username)} · 已完成密码校验</p></div><div><p style="font-size:12px">扩展功能（选填）</p><div class="org-pack-options">${['党建包', '志愿包', '社团包'].map(p => `<label><input type="checkbox" data-draft-pack="${p}" ${draft.packs.includes(p) ? 'checked' : ''}>${p}</label>`).join('')}</div></div>`;
  }
  function renderAuth() {
    let main;
    if (authMode === 'login') main = `<h2>欢迎回来</h2><p>登录协奏，继续你的组织工作。</p>${created ? `<div class="org-alert">「${E(org.name)}」已创建，请使用管理员账号登录。</div>` : ''}<div class="org-login-brand"><span class="org-emblem" style="background:${E(org.color)}">${icon('grid')}</span><div><small>组织展示</small><b>${E(org.shortName)}</b></div></div><div class="org-login-account-hint">账号由组织管理员分配，登录后自动进入所属组织。这里展示的组织不决定账号归属。</div><form id="org-login-form" class="org-form"><div class="org-form-error" role="alert"></div>${field('用户名', 'username', loginUser, 'required autocomplete="off" placeholder="请输入演示用户名"')}${field('密码', 'password', '', 'type="password" required autocomplete="off" placeholder="请输入任意演示密码"')}<button type="submit" class="btn primary">登录原型工作台</button></form><p class="org-auth-help">仅演示页面流转，不校验真实账号；请勿输入真实密码。</p><div class="org-auth-create"><div><strong>让团队在这里相聚</strong><p>为你的学生组织创建专属工作台</p></div>${link('创建组织', 'auth-register')}</div>`;
    else if (authMode === 'success') main = `<div class="org-success">${icon('check')}<h2>组织创建成功</h2><p>「${E(org.name)}」的原型工作台已准备就绪。</p><div class="org-success-account"><small>管理员账号</small><b>${E(loginUser)}</b><small>${units.length} 个节点已保留，使用管理员账号继续</small></div>${btn('使用管理员账号登录', 'success-login', '', true)}<p>下一步：进入组织架构，设置负责人并完善成员归属。</p></div>`;
    else main = `<div class="org-wizard-counter">创建组织 / 第 ${wizardStep + 1} 步，共 4 步</div><h2 id="org-wizard-heading" tabindex="-1">${headings[wizardStep]}</h2><p>${['这些信息将用于组织展示，之后也可以在设置中修改。', '至少添加一个部门或小组，点击「子节点」可继续添加下级。', '这个账号将负责组织设置、成员与权限管理。', '核对信息后，创建属于团队的工作台。'][wizardStep]}</p><form id="org-wizard-form"><div class="org-form-error" role="alert"></div><div class="org-form">${wizardBody()}</div><footer class="org-wizard-footer">${wizardStep > 0 ? btn('上一步', 'wizard-step', wizardStep - 1) : link('返回登录', 'auth-login')}<button type="submit" class="btn primary">${wizardStep === 3 ? '创建演示组织' : '下一步：' + steps[wizardStep + 1]}</button></footer></form>`;
    auth.innerHTML = `<header class="org-dialog-head"><h2 id="org-auth-title">${authMode === 'register' ? '创建组织' : authMode === 'success' ? '创建完成' : '登录页'} / 流程预览</h2><button type="button" class="icon-btn" data-org-action="close-auth" aria-label="返回组织架构">${icon('close')}</button></header><div class="org-dialog-content"><div class="org-auth-shell">${authAside()}<main class="org-auth-main">${main}</main></div></div>`;
    $('.org-dialog-content', auth).scrollTop = 0;
  }
  function openAuth(mode) {
    authMode = mode;
    if (mode === 'register') { if (!draft) draft = newDraft(); wizardStep = 0; }
    renderAuth(); if (!auth.open) auth.showModal();
  }
  function applyDraft() {
    const issue = validateTree(); if (issue) { error(issue, auth); return false; }
    const flat = [];
    function flatten(list, parentId = null) { list.forEach((n, i) => { flat.push({id: n.id, parentId, name: n.name.trim(), unitType: n.unitType, leader: null, sort: i}); flatten(n.children, n.id); }); }
    flatten(draft.units);
    org = {name: draft.name.trim(), shortName: draft.shortName.trim() || draft.name.trim(), type: draft.type, color: draft.color, packs: [...draft.packs], source: '本次创建组织时建立'};
    units = flat;
    // Existing registration attaches the first administrator to the first root unit.
    people = [{id: nextId++, name: draft.realName.trim() || draft.username.trim(), username: draft.username.trim(), unit: flat[0].id, role: '总负责人', status: '正常'}];
    loginUser = draft.username.trim(); created = true; selected = 0; tab = 'children'; collapsed.clear(); query = '';
    authMode = 'success'; draft = null; render(); renderAuth();
  }
  auth.addEventListener('submit', e => {
    e.preventDefault();
    const values = Object.fromEntries(new FormData(e.target));
    if (e.target.id === 'org-login-form') {
      const username = values.username.trim();
      if (!username) { error('请输入演示用户名', auth); return; }
      if (created && username !== loginUser) { error(`请使用本次创建的管理员账号「${loginUser}」继续演示。`, auth); return; }
      e.target.reset(); auth.close(); showView('org'); toast('已进入组织架构原型，未执行真实登录'); return;
    }
    if (e.target.id !== 'org-wizard-form') return;
    if (wizardStep === 0 && !draft.name.trim()) { error('请填写组织全称', auth); return; }
    if (wizardStep === 1) { const issue = validateTree(); if (issue) { error(issue, auth); return; } }
    if (wizardStep === 2) {
      if (!draft.username.trim()) { error('请填写管理员用户名', auth); return; }
      if (values.password.length < 6) { error('请设置至少 6 位演示密码', auth); return; }
      if (values.password !== values.confirmPassword) { error('两次输入的密码不一致', auth); return; }
      // Deliberately never copy credentials to state, storage, or navigation.
      e.target.elements.password.value = ''; e.target.elements.confirmPassword.value = '';
    }
    if (wizardStep === 3) { applyDraft(); return; }
    wizardStep++; renderAuth(); $('#org-wizard-heading', auth)?.focus();
  });
  auth.addEventListener('close', () => {
    // Closing any preview clears password inputs immediately, while retaining the nonsecret draft.
    auth.querySelectorAll('input[type="password"]').forEach(x => x.value = '');
  });
  auth.addEventListener('input', e => {
    const key = e.target.dataset.draft;
    if (key && draft && e.target.type !== 'radio') draft[key] = e.target.value;
    if (e.target.dataset.draftNode && draft) draftNode(e.target.dataset.draftNode)[e.target.dataset.key] = e.target.value;
  });
  auth.addEventListener('change', e => {
    if (!draft) return;
    if (e.target.dataset.draft) draft[e.target.dataset.draft] = e.target.value;
    if (e.target.dataset.draftNode) draftNode(e.target.dataset.draftNode)[e.target.dataset.key] = e.target.value;
    if (e.target.dataset.draftPack) { const p = e.target.dataset.draftPack; draft.packs = e.target.checked ? [...draft.packs, p] : draft.packs.filter(x => x !== p); }
  });
  host.addEventListener('input', e => { if (e.target.id === 'org-search') { query = e.target.value; renderTree(); } });
  document.addEventListener('click', e => {
    const button = e.target.closest('[data-org-action]'); if (!button) return;
    const action = button.dataset.orgAction, value = button.dataset.value;
    if (action === 'select') { selected = Number(value); tab = 'children'; mobileTreeOpen = false; render(); }
    else if (action === 'select-profile') { selected = 0; tab = 'info'; render(); }
    else if (action === 'tab') { tab = value; render(); }
    else if (action === 'fold') { const id = Number(value); if (collapsed.has(id)) collapsed.delete(id); else collapsed.add(id); renderTree(); }
    else if (action === 'fold-all') { collapsed = collapsed.size ? new Set() : new Set(units.filter(n => children(n.id).length).map(n => n.id)); render(); }
    else if (action === 'mobile-tree') { mobileTreeOpen = !mobileTreeOpen; render(); }
    else if (action === 'add-root') editUnit(0, 'root');
    else if (action === 'add-child') editUnit(Number(value), 'child');
    else if (action === 'edit') editUnit(Number(value));
    else if (action === 'leader') setLeader(Number(value));
    else if (action === 'delete') deleteUnit(Number(value));
    else if (action === 'profile') editProfile();
    else if (action === 'close-dialog') dialog.close();
    else if (action === 'close-auth') auth.close();
    else if (action === 'member-entry') memberEntry();
    else if (action === 'invite') invite();
    else if (action === 'go-members') { dialog.close(); showView('members'); toast('已打开成员管理原型；此模块使用独立示例数据'); }
    else if (action === 'auth-login') openAuth('login');
    else if (action === 'auth-register') openAuth('register');
    else if (action === 'success-login') { authMode = 'login'; renderAuth(); }
    else if (action === 'wizard-step') { wizardStep = Number(value); renderAuth(); $('#org-wizard-heading', auth)?.focus(); }
    else if (action === 'draft-root') { draft.units.push({id: nextId++, name: '', unitType: '部门', children: []}); renderAuth(); auth.querySelectorAll('[data-draft-node][data-key="name"]').item(draftCount(draft.units) - 1)?.focus(); }
    else if (action === 'draft-child') { const n = draftNode(value); const id = nextId++; n.children.push({id, name: '', unitType: '小组', children: []}); renderAuth(); $(`[data-draft-node="${id}"][data-key="name"]`, auth)?.focus(); }
    else if (action === 'draft-remove') {
      const n = draftNode(value);
      if (n.children.length) {
        const row = button.closest('.org-draft-row');
        const existing = row.nextElementSibling;
        if (existing?.classList.contains('org-form-note')) { existing.remove(); return; }
        const confirm = document.createElement('div'); confirm.className = 'org-form-note'; confirm.innerHTML = `将删除「${E(n.name || '未命名节点')}」及 ${draftCount(n.children)} 个下级。 ${link('确认删除', 'draft-confirm-remove', value)} ${link('取消', 'draft-cancel-remove')}`; row.after(confirm);
      } else { removeDraftNode(value); renderAuth(); }
    }
    else if (action === 'draft-confirm-remove') { removeDraftNode(value); renderAuth(); }
    else if (action === 'draft-cancel-remove') button.closest('.org-form-note').remove();
    else if (action === 'reset') {
      modal('重置原型演示？', '<p style="font-size:12px">本次对节点、负责人和演示组织的修改将清空，恢复最初的党建办公室示例。</p>', () => { ({org, units, people} = copy(initial)); selected = 1; tab = 'children'; query = ''; collapsed.clear(); created = false; draft = null; loginUser = ''; toast('演示已重置'); }, '重置演示');
    }
  });
  render();
  const initialView = new URLSearchParams(location.search).get('view');
  if (initialView === 'org' || document.body.dataset.entry === 'org') showView('org', false);
})();
