import { useCallback, useEffect, useMemo, useRef, useState, type CSSProperties } from 'react'
import { Alert, App, Button, Empty, Input, Select, Spin, Table, Tag, Typography } from 'antd'
import { ApartmentOutlined, FolderOutlined, PlusOutlined, RightOutlined, SearchOutlined, TeamOutlined, ReloadOutlined, SafetyCertificateOutlined } from '@ant-design/icons'
import { Link, useLocation, useSearchParams } from 'react-router-dom'
import { assignableRoles } from '@/api/permission'
import { createInvitation, deleteUnit, getCurrentOrg, getOrgStructure, type OrgAccount, type OrgInfo, type OrgInvitation, type OrgStructureData, type OrgUnitNode } from '@/api/org'
import PageHeader from '@/components/glass/PageHeader'
import FormSurface from '@/components/form/FormSurface'
import LoginOrganization from '@/components/auth/LoginOrganization'
import BrandIdentity from '@/components/BrandIdentity'
import { useAuthStore } from '@/stores/auth'
import { parsePacks, useOrgStore } from '@/stores/org'
import { buildStructureIndex, filterStructure, ORG_TYPE_LABELS, PACK_LABELS } from '@/utils/orgStructure'
import { ProfileEditor, UnitEditor, orgError, type UnitEditorTarget } from './OrgEditors'
import '@/styles/org-v6.css'

type DetailTab = 'children' | 'accounts' | 'info'
function nameOf(account?: OrgAccount) { return account?.realName || account?.username || '' }
function Avatar({ name }: { name: string }) { return <span className="org-avatar" aria-hidden="true">{name.slice(0, 1)}</span> }
function Info({ pairs }: { pairs: [string, string][] }) {
  return <dl className="org-info">{pairs.map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{value}</dd></div>)}</dl>
}

export default function OrgStructure() {
  const { message, modal } = App.useApp()
  const user = useAuthStore(s => s.user)
  const has = (code: string) => user?.permissions?.includes(code) ?? false
  const canStructure = has('org:structure:manage'), canProfile = has('org:manage'), canInvite = has('org:invite')
  const location = useLocation()
  const [params, setParams] = useSearchParams()
  const selectedId = Number(params.get('unit')) || 0
  const [org, setOrg] = useState<OrgInfo | null>(null)
  const [data, setData] = useState<OrgStructureData | null>(null)
  const [loading, setLoading] = useState(true), [loadError, setLoadError] = useState('')
  const [tab, setTab] = useState<DetailTab>('children'), [query, setQuery] = useState('')
  const [collapsed, setCollapsed] = useState<Set<number>>(new Set()), [mobileOpen, setMobileOpen] = useState(false)
  const [editor, setEditor] = useState<UnitEditorTarget | null>(null)
  const [deletingNode, setDeletingNode] = useState<OrgUnitNode | null>(null)
  const [deleting, setDeleting] = useState(false), [deleteError, setDeleteError] = useState('')
  const deleteBusy = useRef(false)
  const [profileOpen, setProfileOpen] = useState(false), [previewOpen, setPreviewOpen] = useState(false), [entryOpen, setEntryOpen] = useState(false)
  const [inviteOpen, setInviteOpen] = useState(false), [roles, setRoles] = useState<{ value: string; label: string }[]>([]), [role, setRole] = useState<string>()
  const [roleError, setRoleError] = useState(''), [roleLoading, setRoleLoading] = useState(false), [ttl, setTtl] = useState(72)
  const [invitation, setInvitation] = useState<OrgInvitation | null>(null), [inviting, setInviting] = useState(false)
  const inviteBusy = useRef(false), version = useRef(0)
  const primary = useOrgStore(s => s.primaryColor)
  const load = useCallback(async () => {
    const request = ++version.current
    setLoading(true); setLoadError('')
    try {
      const [info, structure] = await Promise.all([getCurrentOrg(), getOrgStructure()])
      if (request === version.current) { setOrg(info); setData(structure) }
    } catch (err) { if (request === version.current) setLoadError(orgError(err, '组织架构加载失败，请重试。')) }
    finally { if (request === version.current) setLoading(false) }
  }, [])
  useEffect(() => { void load(); return () => { version.current++ } }, [load])
  const units = data?.units ?? [], accounts = data?.accounts ?? []
  const index = useMemo(() => buildStructureIndex(units, accounts), [units, accounts])
  const accountMap = useMemo(() => new Map(accounts.map(a => [a.id, a])), [accounts])
  const selected = index.byId.get(selectedId), currentId = selected?.id ?? 0
  const childNodes = selected?.children ?? units, direct = selected ? index.direct.get(selected.id) ?? [] : accounts
  const shortName = org?.shortName || org?.name || '当前组织', allNodes = [...index.byId.values()]
  const filtered = useMemo(() => filterStructure(units, query), [units, query])
  const missing = allNodes.filter(n => !n.leaderUserId || !accountMap.has(n.leaderUserId)).length
  const created = (location.state as { orgCreated?: number } | null)?.orgCreated === org?.id
  const packs = org ? parsePacks(org.enabledPacks) : []
  const disabled = loading || !!loadError

  function select(id: number) { setParams(id ? { unit: String(id) } : {}); setTab('children'); setMobileOpen(false) }
  function leader(node: OrgUnitNode) {
    const account = node.leaderUserId ? accountMap.get(node.leaderUserId) : undefined
    if (!account) return <span className="org-pending">待设置负责人</span>
    return <><Avatar name={nameOf(account)} /><span>{nameOf(account)}{account.status !== 1 && <span className="org-pending">（已停用）</span>}</span></>
  }
  function openCreate(parentId: number | null) { setEditor({ kind: 'create', parentId }) }
  async function savedUnit(id: number) { setEditor(null); select(id); setCollapsed(new Set()); await load(); message.success('节点信息已保存') }
  function removeSelected() {
    if (!selected) return
    if (selected.children.length || direct.length) {
      modal.info({ title: `暂时无法删除${selected.name}`, content: <p>{selected.children.length ? `仍有 ${selected.children.length} 个直属下级节点，请先处理下级。` : ''}{direct.length ? `仍有 ${direct.length} 个直属账号（含停用账号），请先在用户管理中调整归属。` : ''}</p>, okText: '我知道了' }); return
    }
    setDeleteError(''); setDeletingNode(selected)
  }
  async function confirmDelete() {
    if (!deletingNode || deleteBusy.current) return
    deleteBusy.current = true; setDeleting(true); setDeleteError('')
    try {
      await deleteUnit(deletingNode.id)
      select(deletingNode.parentId ?? 0); setDeletingNode(null); await load(); message.success('节点已删除')
    } catch (err) { setDeleteError(orgError(err, '删除失败，请检查关联记录后重试。')) }
    finally { deleteBusy.current = false; setDeleting(false) }
  }
  async function loadRoles() {
    setRoleLoading(true); setRoleError('')
    try { const values = await assignableRoles(); setRoles(values.map(r => ({ value: r.code, label: r.name }))); setRole(current => values.some(r => r.code === current) ? current : values[0]?.code) }
    catch (err) { setRoles([]); setRole(undefined); setRoleError(orgError(err, '可分配角色加载失败，请重试。')) }
    finally { setRoleLoading(false) }
  }
  async function invite() {
    if (!role || inviteBusy.current) return
    inviteBusy.current = true; setInviting(true)
    try { setInvitation(await createInvitation({ roleCode: role, orgUnitId: currentId || undefined, ttlHours: ttl })) }
    catch { /* centralized request feedback; preserve inputs */ }
    finally { inviteBusy.current = false; setInviting(false) }
  }
  function tree(nodes: OrgUnitNode[]) {
    return <ul>{nodes.map(node => {
      const open = !!query.trim() || !collapsed.has(node.id)
      return <li key={node.id}><div className={`org-tree-row${currentId === node.id ? ' selected' : ''}`}>
        <button type="button" className="org-fold" disabled={!node.children.length} aria-label={`${open ? '收起' : '展开'}${node.name}`} aria-expanded={open}
          onClick={() => setCollapsed(values => { const next = new Set(values); if (next.has(node.id)) next.delete(node.id); else next.add(node.id); return next })}><RightOutlined rotate={open ? 90 : 0} /></button>
        <button type="button" className="org-node" aria-current={currentId === node.id ? 'page' : undefined} onClick={() => select(node.id)}>
          {node.unitType === '小组' || node.unitType === '分队' ? <TeamOutlined /> : <FolderOutlined />}<span>{node.name}</span><small aria-label={`含下级 ${index.totals.get(node.id) ?? 0} 个账号`}>{index.totals.get(node.id) ?? 0}</small></button>
        </div>{open && !!node.children.length && tree(node.children)}</li>
    })}</ul>
  }
  const memberUrl = `/members${currentId ? `?deptId=${currentId}` : ''}`, userUrl = `/admin/users${currentId ? `?deptId=${currentId}` : ''}`
  const related = <div className="org-next"><TeamOutlined /><div><h3>让分工与成员归属保持一致</h3><p>花名册在成员管理中维护；账号归属与角色在用户管理中设置。</p></div><Button type="link" onClick={() => setEntryOpen(true)}>成员与账号入口</Button></div>

  return <div className="ui-v6 org-v6">
    <PageHeader title="组织架构" description="从组织到部门，让每一份分工和归属清晰可见。" extra={<>
      <Button icon={<ReloadOutlined aria-hidden />} onClick={load} loading={loading}>刷新</Button><Button onClick={() => setPreviewOpen(true)} disabled={!org}>登录页预览</Button>
      {canStructure && <Button type="primary" icon={<PlusOutlined aria-hidden />} disabled={disabled} onClick={() => openCreate(null)}>添加一级节点</Button>}
    </>} />
    {loadError && <Alert type="error" showIcon message={loadError} action={<Button onClick={load}>重新加载</Button>} />}
    <Spin spinning={loading}>{org && data ? <>
      {created && <Alert type="info" showIcon className="org-created-notice" message={`${shortName}的架构已沿用创建时的设置。下一步可设置负责人，再添加组织成员。`} />}
      <section className="org-profile" aria-label="组织摘要"><span className="org-emblem">{org.logoUrl ? <img src={org.logoUrl} alt="" /> : <ApartmentOutlined />}</span>
        <div className="org-profile-main"><h2>{org.name}</h2><p>{ORG_TYPE_LABELS[org.type] || org.type} · {packs.length ? packs.map(p => PACK_LABELS[p] || p).join('、') + '已启用' : '暂未启用扩展功能'}</p></div>
        <div className="org-profile-meta"><div><b>{allNodes.length}</b><small>架构节点</small></div><div><b>{accounts.length}</b><small>组织账号</small></div><div><b className={missing ? 'org-pending' : ''}>{missing}</b><small>待设负责人</small></div></div>
        <Button type="link" onClick={() => { select(0); setTab('info') }}>组织资料</Button>
      </section>
      <div className="org-layout"><aside className={`org-directory${mobileOpen ? ' mobile-expanded' : ''}`} aria-label="架构导航">
        <header className="org-directory-head"><h2>组织层级 <small>{allNodes.length}</small><span className="org-mobile-current"> / {selected?.name || shortName}</span></h2>
          <Button type="link" className="org-desktop-fold" onClick={() => setCollapsed(collapsed.size ? new Set() : new Set(allNodes.filter(n => n.children.length).map(n => n.id)))}>{collapsed.size ? '展开全部' : '收起全部'}</Button>
          <Button type="link" className="org-mobile-toggle" aria-expanded={mobileOpen} aria-controls="org-tree-navigation" onClick={() => setMobileOpen(!mobileOpen)}>{mobileOpen ? '收起导航' : '切换节点'}</Button></header>
        <Input className="org-tree-search" prefix={<SearchOutlined />} placeholder="搜索部门 / 小组" aria-label="搜索架构节点" allowClear value={query} onChange={e => setQuery(e.target.value)} />
        <nav className="org-tree" id="org-tree-navigation" aria-label="选择架构节点"><div className="org-tree-root"><div className={`org-tree-row${!currentId ? ' selected' : ''}`}><span className="org-fold" /><button type="button" className="org-node" aria-current={!currentId ? 'page' : undefined} onClick={() => select(0)}><ApartmentOutlined /><span>{shortName}</span><small>{accounts.length}</small></button></div></div>
          {filtered.length ? tree(filtered) : <p className="org-search-empty">{units.length ? '没有匹配的节点，请调整名称。' : '还没有部门，添加一个作为协作的起点。'}</p>}</nav>
        <footer className="org-directory-foot">{canStructure && <Button block type="dashed" icon={<PlusOutlined aria-hidden />} disabled={disabled} onClick={() => openCreate(null)}>添加一级节点</Button>}<p>右侧数字为含下级的账号数</p></footer>
      </aside><section className="org-detail" aria-label={`${selected?.name || shortName}详情`}>
        <header className="org-detail-head"><nav className="org-path" aria-label="节点路径"><button type="button" onClick={() => select(0)}>{shortName}</button>{(index.paths.get(currentId) ?? []).map(n => <span key={n.id}><RightOutlined /><button type="button" onClick={() => select(n.id)}>{n.name}</button></span>)}{!selected && <span><RightOutlined />组织总览</span>}</nav>
          <div className="org-title-line"><div><h2>{selected?.name || shortName}</h2><Tag>{selected?.unitType || '组织'}</Tag></div><div className="org-detail-actions">
            {(selected ? canStructure : canProfile) && <Button disabled={disabled} onClick={() => selected ? setEditor({kind: 'edit', node: selected, parentId: selected.parentId}) : setProfileOpen(true)}>编辑{selected ? '节点' : '组织资料'}</Button>}
            {canStructure && <Button type="primary" disabled={disabled} icon={<PlusOutlined aria-hidden />} onClick={() => openCreate(currentId || null)}>添加下级</Button>}</div></div>
          <p className="org-detail-desc">{selected ? `属于${selected.parentId ? index.byId.get(selected.parentId)?.name || shortName : shortName}，当前有 ${childNodes.length} 个直属下级节点。` : '管理组织的完整架构，沿用创建时建立的部门与小组。'}</p>
          <div className="org-detail-summary">{selected ? <div className="org-person">{leader(selected)}{canStructure && <Button type="link" disabled={disabled} onClick={() => setEditor({kind: 'leader', node: selected, parentId: selected.parentId})}>{selected.leaderUserId ? '更换' : '设置负责人'}</Button>}</div> : <div><span>一级节点</span><b>{units.length}</b></div>}<div><span>{selected ? '直属账号' : '组织账号'}</span><b>{direct.length}</b></div><div><span>含下级账号</span><b>{currentId ? index.totals.get(currentId) ?? 0 : accounts.length}</b></div></div>
        </header>
        <div className="org-tabs" role="group" aria-label="节点详情分类">{([
          ['children', selected ? '下级节点' : '组织总览', childNodes.length], ['accounts', selected ? '直属账号' : '组织账号', direct.length], ['info', selected ? '节点信息' : '组织资料', null],
        ] as const).map(([id, label, count]) => <button type="button" key={id} aria-pressed={tab === id} onClick={() => setTab(id)}>{label}{count !== null && <small>{count}</small>}</button>)}</div>
        <div className="org-detail-body">
          {!selected && !!index.unassigned.length && <Alert type="warning" showIcon message={`${index.unassigned.length} 个账号尚未分配架构节点，可在用户管理中完善归属。`} />}
          {tab === 'children' && <><div className="org-section-caption"><h3>{selected ? '直属下级' : '一级部门与中心'}</h3><small>按排序值排列，可点击查看</small></div>{childNodes.length ? <div className="org-children">{childNodes.map(n => <button type="button" className="org-child" key={n.id} onClick={() => select(n.id)}><div className="org-child-top"><span className="org-unit-icon">{n.unitType === '小组' ? <TeamOutlined /> : <FolderOutlined />}</span><strong>{n.name}<span className="org-child-type">{n.unitType}</span></strong><RightOutlined /></div><div className="org-child-bottom">{leader(n)}<span className="org-child-count">{index.totals.get(n.id) ?? 0} 人{n.children.length ? ` / ${n.children.length} 个下级` : ''}</span></div></button>)}</div> : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={<><strong>还没有{selected ? '下级' : '架构'}节点</strong><p>添加部门、小组或分队，让每一份分工有明确的位置。</p></>}>{canStructure && <Button disabled={disabled} onClick={() => openCreate(currentId || null)}>添加{selected ? '下级' : '一级'}节点</Button>}</Empty>}{related}</>}
          {tab === 'accounts' && <><div className="org-section-caption"><h3>{selected ? '只显示直属账号，不含下级节点' : '当前组织的全部账号'}</h3><Button type="link" onClick={() => setEntryOpen(true)}>成员与账号入口</Button></div><Table<OrgAccount> rowKey="id" size="small" dataSource={direct} scroll={{x: selected ? 490 : 620}}
            pagination={direct.length > 10 ? { pageSize: 10, showSizeChanger: false, showTotal: total => `共 ${total} 个账号` } : false} columns={[
              { title: '姓名 / 账号', dataIndex: 'realName', render: (_, a) => <div className="org-account"><Avatar name={nameOf(a)} /><div>{nameOf(a)}<small>{a.username}</small></div></div> },
              ...(!selected ? [{title: '所属节点', dataIndex: 'deptId', render: (id: number | null) => id ? index.byId.get(id)?.name || '待分配' : '待分配'}] : []),
              { title: '主角色', dataIndex: 'roleName', render: value => value || '未分配' }, { title: '账号状态', dataIndex: 'status', render: value => <Tag color={value === 1 ? 'success' : 'default'}>{value === 1 ? '正常' : '已停用'}</Tag> },
            ]} locale={{emptyText: '还没有直属账号，可从花名册开户或在用户管理分配归属'}} /><p className="org-form-hint">任命节点负责人不会自动更改账号主角色或管理员身份。</p></>}
          {tab === 'info' && <>{selected ? <Info pairs={[
            ['节点名称', selected.name], ['节点类型', selected.unitType], ['所属上级', selected.parentId ? index.byId.get(selected.parentId)?.name || shortName : `${shortName}（组织根节点）`], ['节点负责人', nameOf(accountMap.get(selected.leaderUserId ?? 0)) || '尚未设置'], ['显示排序', String(selected.sortOrder ?? 0)], ['完整路径', [shortName, ...(index.paths.get(currentId) ?? []).map(n => n.name)].join(' / ')],
          ]} /> : <Info pairs={[
            ['组织全称', org.name], ['组织简称', org.shortName || '未填写'], ['组织类型', ORG_TYPE_LABELS[org.type] || org.type], ['组织主题色', primary], ['扩展功能', packs.length ? packs.map(p => PACK_LABELS[p] || p).join('、') : '未启用'],
          ]} />}<div className="org-next"><SafetyCertificateOutlined /><div><h3>{selected ? '从创建到日常维护，沿用同一份架构' : '登录展示与工作台使用同一份组织资料'}</h3><p>{selected ? '修改节点名称后，按部门关联的成员与工作资料继续归属原节点。' : '名称、简称与主题色修改后，可以打开登录页预览核对展示。'}</p></div>{!selected && canProfile && <Button type="link" disabled={disabled} onClick={() => setProfileOpen(true)}>编辑组织资料</Button>}</div></>}
        </div><footer className="org-detail-foot"><span>{selected ? '调整分工后，请同步核对成员归属与账号权限。' : '组织根节点代表当前组织，不能作为普通部门删除。'}</span>{selected && canStructure && <Button type="link" danger disabled={disabled} onClick={removeSelected}>删除节点</Button>}</footer>
      </section></div>
    </> : !loading && !loadError ? <Empty description="组织信息尚未就绪，请刷新重试" /> : <div className="org-loading-space" />}</Spin>
    {editor && <UnitEditor key={`${editor.kind}-${editor.node?.id ?? editor.parentId ?? 'root'}`} target={editor} accounts={accounts} allNodes={allNodes} parentName={editor.parentId ? index.byId.get(editor.parentId)?.name || shortName : `${shortName}（组织根节点）`} onCancel={() => setEditor(null)} onSaved={savedUnit} />}
    {profileOpen && org && <ProfileEditor org={org} onCancel={() => setProfileOpen(false)} onSaved={async () => { setProfileOpen(false); await Promise.all([load(), useOrgStore.getState().load()]); message.success('组织资料已保存') }} />}
    <FormSurface open={!!deletingNode} title={`删除${deletingNode?.name || '节点'}？`} onCancel={deleting ? undefined : () => setDeletingNode(null)}
      onOk={confirmDelete} okText="确认删除" okButtonProps={{ danger: true }} confirmLoading={deleting} closable={!deleting} keyboard={!deleting} cancelButtonProps={{disabled: deleting}}>
      {deleteError && <Alert type="error" showIcon message={deleteError} />}
      <p>删除前会检查关联资料；有花名册或历史工作记录的节点需要保留。</p>
    </FormSurface>
    <FormSurface open={entryOpen} title="成员与账号" onCancel={() => setEntryOpen(false)} footer={<Button onClick={() => setEntryOpen(false)}>完成查看</Button>}>
      <p className="org-form-hint">当前组织：{shortName}{selected ? ` / ${selected.name}` : ''}</p><div className="org-entry"><h3>花名册与届别</h3><p>维护成员名册、届别和任职记录；可从花名册批量开户。</p>{has('member:view') && <Link to={memberUrl}>打开成员管理</Link>}</div>
      <div className="org-entry"><h3>账号归属与权限</h3><p>已开户成员在用户管理调整所属节点与主角色，管理员身份在权限管理中维护。</p>{has('user:view') && <Link to={userUrl}>打开用户管理</Link>}{has('role:view') && <Link to="/admin/permissions">打开权限管理</Link>}</div>
      {canInvite && <Button onClick={() => { setEntryOpen(false); setInvitation(null); setInviteOpen(true); void loadRoles() }}>查看邀请码</Button>}
    </FormSurface>
    <FormSurface open={inviteOpen} title="邀请码" description={`预设部门：${selected?.name || '暂不分配'}`} onCancel={inviting ? undefined : () => setInviteOpen(false)} closable={!inviting} keyboard={!inviting}
      footer={<><Button disabled={inviting} onClick={() => setInviteOpen(false)}>关闭</Button><Button type="primary" disabled={!role || roleLoading || !!invitation} loading={inviting} onClick={invite}>生成邀请码</Button></>}>
      <Alert type="info" showIcon message="当前仅支持生成邀请码，尚未开放兑换入组入口。邀请码不能直接用于注册或登录。" />
      {roleError && <Alert type="error" message={roleError} action={<Button onClick={loadRoles}>重试</Button>} />}
      <div className="org-invite-fields"><label>可分配角色<Select aria-label="可分配角色" value={role} options={roles} loading={roleLoading} disabled={inviting || !!invitation} onChange={setRole} placeholder="暂无可分配角色" /></label><label>有效期<Select aria-label="邀请有效期" value={ttl} options={[{value:24,label:'24 小时'},{value:72,label:'72 小时'},{value:168,label:'7 天'}]} disabled={inviting || !!invitation} onChange={setTtl} /></label></div>
      {invitation && <div className="org-invitation-result"><Typography.Paragraph copyable>{invitation.code}</Typography.Paragraph><p>到期时间：{invitation.expireAt?.replace('T', ' ') || '未设置'}</p></div>}
    </FormSurface>
    <FormSurface open={previewOpen} title="登录页预览" description="此处仅预览组织展示，实际归属由账号决定。" width={600} onCancel={() => setPreviewOpen(false)} footer={<Button onClick={() => setPreviewOpen(false)}>完成预览</Button>}>
      {org && <div className="org-login-preview" style={{'--auth-brand': primary} as CSSProperties}><BrandIdentity /><h2>欢迎回来</h2><p>登录协奏，继续你的组织工作。</p><LoginOrganization selected={org} /><p className="org-form-hint">账号由组织管理员分配，登录后自动进入所属组织。</p><Link to="/login" target="_blank" rel="noreferrer">打开登录页</Link></div>}
    </FormSurface>
  </div>
}
