import { useEffect, useRef, useState } from 'react'
import { Alert, App, Avatar, Button, Checkbox, Empty, Input, Select, Space, Tabs } from 'antd'
import type { TableColumnsType } from 'antd'
import { PlusOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons'
import { useNavigate, useSearchParams } from 'react-router-dom'
import PageHeader from '@/components/glass/PageHeader'
import FormSurface from '@/components/form/FormSurface'
import MemberAccountImport from '@/components/records/MemberAccountImport'
import { AdminTable, InfoList, LoadError, StatsStrip, StatusTag, errorText } from '@/components/admin/AdminSurface'
import { listDepts, type DeptVO } from '@/api/dept'
import { changeUserStatuses, deleteUser, listUsers, userSummary, type UserStatusResult, type UserSummary, type UserVO } from '@/api/user'
import { useAuthStore } from '@/stores/auth'
import { usePreferencesStore } from '@/stores/preferences'
import UserEditor from './UserEditor'
import UserDetails from './UserDetails'

export const managementLabel = (source: UserVO['managementSource']) => source === 'OWNER' ? '组织所有者' : source === 'DELEGATED' ? '组织管理员' : source === 'NONE' ? '普通用户' : '身份未提供'
export const userStatus = (user: UserVO) => <StatusTag tone={user.pendingActivation ? 'warning' : user.status === 1 ? 'success' : 'neutral'}>{user.pendingActivation ? '待设密' : user.status === 1 ? '启用' : '停用'}</StatusTag>
type StatusFilter = 'ENABLED' | 'PENDING' | 'DISABLED'

export default function UserList() {
  const {message} = App.useApp(), navigate = useNavigate(), [params] = useSearchParams()
  const currentUser = useAuthStore(s => s.user), density = usePreferencesStore(s => s.userListDensity)
  const caps = currentUser?.managementCapabilities || [], canManage = caps.includes('user:manage'), canAssign = caps.includes('role:assign')
  const requestedDept = Number(params.get('deptId')) || undefined
  const [dept, setDept] = useState<number | undefined>(requestedDept), [depts, setDepts] = useState<DeptVO[]>([]), [metaError, setMetaError] = useState('')
  const [keyword, setKeyword] = useState(''), [search, setSearch] = useState(''), [roleId, setRoleId] = useState<number>()
  const [accountStatus, setAccountStatus] = useState<StatusFilter>(), [managementSource, setManagementSource] = useState<UserVO['managementSource']>()
  const [paging, setPaging] = useState({page: 1, size: 10}), [refresh, setRefresh] = useState(0)
  const [data, setData] = useState<UserVO[]>([]), [total, setTotal] = useState(0), [summary, setSummary] = useState<UserSummary>()
  const [loading, setLoading] = useState(true), [summaryLoading, setSummaryLoading] = useState(true), [error, setError] = useState(''), [summaryError, setSummaryError] = useState('')
  const [selected, setSelected] = useState<UserVO[]>([]), [editing, setEditing] = useState<{user?: UserVO}>(), [detail, setDetail] = useState<UserVO>()
  const [statusChange, setStatusChange] = useState<{users: UserVO[]; status: number}>(), [results, setResults] = useState<UserStatusResult[]>()
  const [deleting, setDeleting] = useState<UserVO>(), [confirmation, setConfirmation] = useState(''), [operationError, setOperationError] = useState(''), [busy, setBusy] = useState(false)
  const running = useRef(false)
  const reload = () => setRefresh(v => v + 1)
  useEffect(() => {setDept(requestedDept)}, [requestedDept])
  useEffect(() => {const timer = setTimeout(() => setSearch(keyword.trim()), 250); return () => clearTimeout(timer)}, [keyword])
  useEffect(() => {setPaging(p => ({...p, page: 1})); setSelected([])}, [dept, search, roleId, accountStatus, managementSource])
  useEffect(() => {
    let active = true
    listDepts().then(list => {if (active) {setDepts(list); setMetaError('')}}).catch(() => {if (active) setMetaError('部门列表加载失败，仍可查看账号。')})
    setSummaryLoading(true)
    userSummary().then(next => {if (active) {setSummary(next); setSummaryError('')}}).catch(() => {if (active) {setSummary(undefined); setSummaryError('账号统计加载失败，请刷新重试。')}}).finally(() => {if (active) setSummaryLoading(false)})
    return () => {active = false}
  }, [refresh])
  useEffect(() => {
    let active = true; setLoading(true); setError('')
    listUsers({keyword: search || undefined, deptId: dept, roleId, accountStatus, managementSource, ...paging})
      .then(result => {if (active) {setData(result.records); setTotal(result.total); if (paging.page > 1 && !result.records.length) setPaging(p => ({...p, page: 1}))}})
      .catch(err => {if (active) {setData([]); setError(errorText(err, '账号列表加载失败，请重试。'))}}).finally(() => {if (active) setLoading(false)})
    return () => {active = false}
  }, [dept, search, roleId, accountStatus, managementSource, paging, refresh])
  const mutable = (user: UserVO) => canManage && user.id !== currentUser?.id && user.managementSource != null && user.managementSource !== 'OWNER' && (currentUser?.isOwner || user.managementSource === 'NONE')
  const editable = (user: UserVO) => mutable(user) && canAssign && !user.pendingActivation
  const selectable = (user: UserVO) => mutable(user) && !user.pendingActivation
  const selectRows = (next: UserVO[]) => {if (next.length > 100) return void message.warning('每次最多选择 100 个账号'); setSelected(next)}
  const requestStatus = (users: UserVO[], status: number) => {setStatusChange({users, status}); setResults(undefined); setOperationError('')}
  const applyStatus = async () => {
    if (!statusChange || running.current) return
    running.current = true; setBusy(true); setOperationError('')
    try {const result = await changeUserStatuses(statusChange.users.map(u => u.id), statusChange.status); setResults(result); setSelected(selected.filter(u => result.some(r => r.id === u.id && r.status === 'FAILED'))); reload()}
    catch (err) {setOperationError(errorText(err, '账号状态修改失败，请重试。'))} finally {running.current = false; setBusy(false)}
  }
  const remove = async () => {
    if (!deleting || confirmation !== deleting.username || running.current) return
    running.current = true; setBusy(true); setOperationError('')
    try {await deleteUser(deleting.id); setSelected(old => old.filter(u => u.id !== deleting.id)); setDeleting(undefined); reload(); message.success('账号已删除')}
    catch (err) {setOperationError(errorText(err, '账号删除失败，请重试。'))} finally {running.current = false; setBusy(false)}
  }
  const reset = () => {setKeyword(''); setSearch(''); setDept(undefined); setRoleId(undefined); setAccountStatus(undefined); setManagementSource(undefined); setSelected([]); setPaging(p => ({...p, page: 1}))}
  const name = (user: UserVO) => <div className="admin-user-name"><Avatar>{user.realName?.slice(0, 1) || '?'}</Avatar><div><button className="admin-text-button" onClick={() => setDetail(user)}>{user.realName || user.username}</button>{user.id === currentUser?.id && <StatusTag>当前账号</StatusTag>}<small>{user.username}</small></div></div>
  const actions = (user: UserVO) => <Space size={4}><Button type="link" onClick={() => setDetail(user)}>查看</Button>{canManage && <Button type="link" disabled={!editable(user)} title={user.pendingActivation ? '待设密账号请在开户流程中维护' : !editable(user) ? '当前账号无权编辑此用户' : undefined} onClick={() => setEditing({user})}>编辑</Button>}</Space>
  const columns: TableColumnsType<UserVO> = [
    {title: '用户 / 登录账号', key: 'name', width: 220, render: (_, user) => name(user)}, {title: '所属部门', dataIndex: 'deptName', render: value => value || '未分配部门'},
    {title: '业务主角色', dataIndex: 'roleName', render: value => <StatusTag tone="brand">{value || '未分配角色'}</StatusTag>},
    {title: '管理身份', key: 'management', render: (_, user) => managementLabel(user.managementSource)}, {title: '账号状态', key: 'status', render: (_, user) => userStatus(user)},
    {title: '操作', key: 'actions', align: 'right', width: 130, render: (_, user) => actions(user)},
  ]
  const counts = {all: summary?.total, ENABLED: summary?.enabled, PENDING: summary?.pendingActivation, DISABLED: summary?.disabled}
  return <div className="admin-v6 users-v6">
    <PageHeader title="用户管理" description="管理本组织登录账号、部门归属和主角色，核对账号的实际访问权限。" extra={<>
      <Button icon={<ReloadOutlined aria-hidden="true" />} onClick={reload} loading={loading}>刷新</Button>{caps.includes('role:view') && <Button onClick={() => navigate('/admin/permissions')}>角色与权限</Button>}
      {canManage && <MemberAccountImport onCreated={reload} />}{canManage && canAssign && <Button type="primary" icon={<PlusOutlined aria-hidden="true" />} onClick={() => setEditing({})}>新增用户</Button>}
    </>} />
    {(summaryError || metaError) && <LoadError message={[summaryError, metaError].filter(Boolean).join(' ')} retry={reload} />}
    <StatsStrip loading={summaryLoading} items={[{label: '本组织账号', value: summary?.total ?? '—', note: '登录账号与成员名册分别管理'}, {label: '启用账号', value: summary?.enabled ?? '—', note: '可以正常登录'}, {label: '待设密', value: summary?.pendingActivation ?? '—', note: '等待用户完成首次设置', warning: true}, {label: '管理身份', value: summary ? summary.ownerCount + summary.administratorCount : '—', note: `所有者 ${summary?.ownerCount ?? '—'} · 管理员 ${summary?.administratorCount ?? '—'}`}]} />
    {!!summary?.pendingActivation && <div className="admin-account-alert"><div><strong>{summary.pendingActivation} 个账号尚未完成密码设置</strong><small>由开户经办人核对交付状态；完成首次设置后才可正常登录。</small></div><Button type="link" onClick={() => setAccountStatus('PENDING')}>查看待设密账号</Button></div>}
    <section className="admin-workspace"><Tabs activeKey={accountStatus || 'all'} onChange={key => setAccountStatus(key === 'all' ? undefined : key as StatusFilter)} items={[['all', '全部账号'], ['ENABLED', '启用'], ['PENDING', '待设密'], ['DISABLED', '停用']].map(([key, label]) => ({key, label: <>{label} <small>{counts[key as keyof typeof counts] ?? '—'}</small></>}))} />
      <div className="admin-toolbar"><Input prefix={<SearchOutlined aria-hidden="true" />} aria-label="搜索姓名、用户名或学号" placeholder="搜索姓名、用户名或学号" allowClear value={keyword} onChange={e => setKeyword(e.target.value)} />
        <label>部门<Select aria-label="部门筛选" allowClear placeholder="全部部门" value={dept} options={depts.map(d => ({value: d.id, label: d.name}))} onChange={setDept} /></label>
        <label>角色<Select aria-label="角色筛选" allowClear placeholder="全部角色" value={roleId} options={summary?.roles.map(r => ({value: r.id, label: r.name}))} onChange={setRoleId} /></label>
        <label>身份<Select aria-label="管理身份筛选" allowClear placeholder="全部身份" value={managementSource} options={(['OWNER', 'DELEGATED', 'NONE'] as const).map(value => ({value, label: managementLabel(value)}))} onChange={setManagementSource} /></label><Button type="link" onClick={reset}>重置</Button>
      </div>
      {selected.length > 0 && <div className="admin-batch"><strong>已选择 {selected.length} 个账号</strong><Button type="link" onClick={() => requestStatus(selected, 1)}>启用账号</Button><Button type="link" onClick={() => requestStatus(selected, 0)}>停用账号</Button><Button type="link" onClick={() => setSelected([])}>取消选择</Button></div>}
      <AdminTable rows={data} total={total} {...paging} loading={loading} error={error} retry={reload} onPage={(page, size) => setPaging({page, size})} columns={columns} compact={density === 'compact'} rowSelection={canManage ? {selectedRowKeys: selected.map(u => u.id), preserveSelectedRowKeys: true, onChange: (_, rows) => selectRows(rows), getCheckboxProps: user => ({disabled: !selectable(user), 'aria-label': `选择${user.realName}`})} : undefined}
        renderMobile={user => <><div className="admin-mobile-user">{canManage && <Checkbox aria-label={`选择${user.realName}`} disabled={!selectable(user)} checked={selected.some(p => p.id === user.id)} onChange={e => selectRows(e.target.checked ? [...selected, user] : selected.filter(p => p.id !== user.id))} />}{name(user)}</div><div className="admin-mobile-meta"><span>部门：{user.deptName || '未分配部门'}</span><StatusTag tone="brand">{user.roleName || '未分配角色'}</StatusTag><span>{managementLabel(user.managementSource)}</span>{userStatus(user)}</div><div className="admin-mobile-actions">{actions(user)}</div></>}
        empty={<Empty description="没有匹配的账号"><Button onClick={reset}>清除筛选</Button></Empty>} />
      <p className="admin-table-note">业务主角色决定工作权限，所有者与管理员身份单独授予。停用会阻止登录，成员档案仍保留。</p>
    </section>
    {editing && <UserEditor user={editing.user} depts={depts} onClose={() => setEditing(undefined)} onSaved={() => {setEditing(undefined); setPaging(p => ({...p, page: 1})); setSelected([]); reload()}} />}
    {detail && <UserDetails user={detail} canEdit={editable(detail)} canManage={mutable(detail)} canChangeStatus={selectable(detail)} onClose={() => setDetail(undefined)} onEdit={() => {setEditing({user: detail}); setDetail(undefined)}} onStatus={() => {requestStatus([detail], detail.status === 1 ? 0 : 1); setDetail(undefined)}} onDelete={() => {setDeleting(detail); setConfirmation(''); setOperationError(''); setDetail(undefined)}} />}
    <FormSurface title={results ? '账号状态处理结果' : statusChange?.status === 1 ? '启用账号' : '停用账号'} open={!!statusChange} onCancel={() => {if (!busy) {setStatusChange(undefined); setResults(undefined)}}} closable={!busy} keyboard={!busy} footer={results ? <Button type="primary" onClick={() => {setStatusChange(undefined); setResults(undefined)}}>完成</Button> : <Space><Button disabled={busy} onClick={() => setStatusChange(undefined)}>取消</Button><Button type="primary" danger={statusChange?.status === 0} loading={busy} onClick={applyStatus}>确认{statusChange?.status === 1 ? '启用' : '停用'}</Button></Space>}>
      {operationError && <Alert type="error" showIcon message={operationError} className="admin-form-error" />}
      {results ? <><p className="admin-note">更新 {results.filter(r => r.status === 'UPDATED').length} 个，无需修改 {results.filter(r => r.status === 'UNCHANGED').length} 个，失败 {results.filter(r => r.status === 'FAILED').length} 个。</p><ul className="admin-result-list">{results.map(result => <li key={result.id}><strong>{statusChange?.users.find(u => u.id === result.id)?.realName || result.id}</strong><span className={result.status === 'FAILED' ? 'admin-error-text' : ''}>{result.message}</span></li>)}</ul></>
        : <><p>将对 {statusChange?.users.length} 个账号生效。</p><ul className="admin-result-list">{statusChange?.users.map(user => <li key={user.id}><strong>{user.realName}</strong><small>{user.username}</small></li>)}</ul><Alert type="warning" showIcon message={statusChange?.status === 0 ? '停用后无法登录。历史业务和成员档案保留。' : '启用后恢复登录，业务权限由当前主角色决定。'} /></>}
    </FormSurface>
    <FormSurface title="删除账号" open={!!deleting} onCancel={() => {if (!busy) setDeleting(undefined)}} closable={!busy} keyboard={!busy} onOk={remove} okText="删除账号" confirmLoading={busy} okButtonProps={{danger: true, disabled: confirmation !== deleting?.username}} cancelButtonProps={{disabled: busy}}>
      {operationError && <Alert type="error" message={operationError} className="admin-form-error" />}
      <InfoList items={[{label: '姓名', value: deleting?.realName}, {label: '登录账号', value: deleting?.username}]} /><Alert type="warning" showIcon message="删除后该账号无法登录，也不能在此页恢复。若只需暂停使用，请先停用账号。" />
      <label className="admin-delete-confirm">输入用户名确认删除<Input aria-label="输入用户名确认删除" value={confirmation} onChange={e => setConfirmation(e.target.value)} autoComplete="off" /></label>
    </FormSurface>
  </div>
}
