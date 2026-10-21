import { useEffect, useRef, useState } from 'react'
import { Alert, App, Button, Popconfirm, Select, Space, Table, Tag } from 'antd'
import { DownloadOutlined, ImportOutlined, ReloadOutlined } from '@ant-design/icons'
import { isAxiosError } from 'axios'
import FormSurface, { FormSection } from '@/components/form/FormSurface'
import { useAuthStore } from '@/stores/auth'
import { assignableRoles, type ManagedRole } from '@/api/permission'
import {
  applyAccountImport, issueAccountCredentials, listAccountBatches, listAccountImportSessions,
  listUnregisteredMembers, previewAccountImport,
  type AccountCredential, type AccountImportPreview, type AccountImportResult,
  type MemberSessionVO, type UnregisteredMember,
} from '@/api/member'

// Keep dropdowns inside the drawer's scroll/focus surface. Real option elements
// also make mouse and touch selection consistent for these small role lists.
const popupContainer = (trigger: HTMLElement) => trigger.closest<HTMLElement>('.ant-drawer-body') ?? document.body

export default function MemberAccountImport({ sessionId: initialSessionId, onCreated }: {
  sessionId?: number; onCreated?: () => void
}) {
  const { message } = App.useApp()
  const capabilities = useAuthStore(s => s.user?.managementCapabilities)
  const allowed = ['member:import_account', 'user:manage', 'role:assign'].every(c => capabilities?.includes(c))
  const [open, setOpen] = useState(false)
  const [sessions, setSessions] = useState<MemberSessionVO[]>([])
  const [roles, setRoles] = useState<ManagedRole[]>([])
  const [sessionId, setSessionId] = useState<number>()
  const [candidates, setCandidates] = useState<UnregisteredMember[]>([])
  const [selected, setSelected] = useState<number[]>([])
  const [roleIds, setRoleIds] = useState<Record<number, number>>({})
  const [bulkRole, setBulkRole] = useState<number>()
  const [preview, setPreview] = useState<AccountImportPreview>()
  const [result, setResult] = useState<AccountImportResult>()
  const [recent, setRecent] = useState<AccountImportResult[]>([])
  const [credentials, setCredentials] = useState<AccountCredential[]>()
  const [metaLoading, setMetaLoading] = useState(false)
  const [listLoading, setListLoading] = useState(false)
  const [refreshKey, setRefreshKey] = useState(0)
  const loading = metaLoading || listLoading
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [historyError, setHistoryError] = useState('')
  const running = useRef(false)

  useEffect(() => {
    if (!open || !allowed) return
    let active = true
    setMetaLoading(true); setError(''); setHistoryError(''); setRecent([])
    listAccountImportSessions()
      .then(nextSessions => {
        if (!active) return
        setSessions(nextSessions)
        setSessionId(nextSessions.find(s => s.id === initialSessionId)?.id
          ?? nextSessions.find(s => s.isCurrent === 1)?.id ?? nextSessions[0]?.id)
      })
      .catch(() => { if (active) setError('开户资料加载失败，请关闭后重试。') })
      .finally(() => { if (active) setMetaLoading(false) })
    // History is optional; failure must not discard valid sessions and roles.
    listAccountBatches().then(batches => { if (active) setRecent(batches) })
      .catch(() => { if (active) setHistoryError('开户历史加载失败，仍可选择成员和角色。') })
    return () => { active = false }
  }, [open, allowed, initialSessionId])

  useEffect(() => {
    if (!open || !allowed || sessionId == null) return
    let active = true
    setListLoading(true); setCandidates([]); setRoles([]); setSelected([]); setRoleIds({}); setBulkRole(undefined); setPreview(undefined)
    Promise.all([listUnregisteredMembers(sessionId), assignableRoles()])
      .then(([rows, nextRoles]) => {
        if (!active) return
        const available = new Set(nextRoles.map(r => r.id))
        setCandidates(rows); setRoles(nextRoles)
        setRoleIds(Object.fromEntries(rows
          .filter(row => row.recommendedRoleId != null && available.has(row.recommendedRoleId))
          .map(row => [row.id, row.recommendedRoleId!])))
      })
      .catch(() => { if (active) setError('成员或角色加载失败，请点击“刷新名单”重试。') })
      .finally(() => { if (active) setListLoading(false) })
    return () => { active = false }
  }, [open, allowed, sessionId, refreshKey])

  if (!allowed) return null
  const options = roles.map(r => ({ value: r.id, label: r.name }))
  const close = () => {
    if (running.current) return
    setOpen(false); setSessionId(undefined); setResult(undefined); setPreview(undefined)
    setCredentials(undefined); setSelected([]); setRoleIds({}); setCandidates([]); setBulkRole(undefined)
    setMetaLoading(false); setListLoading(false)
  }
  const run = async (work: () => Promise<void>) => {
    if (running.current) return
    running.current = true; setBusy(true); setError('')
    try { await work() }
    catch (err) {
      setError(isAxiosError(err) ? err.response?.data?.message || '操作失败，请重试。' : '操作失败，请重试。')
      if (isAxiosError(err) && [403, 409].includes(err.response?.status ?? 0)) setPreview(undefined)
    } finally { running.current = false; setBusy(false) }
  }
  const confirm = () => run(async () => {
    if (!preview) return
    const created = await applyAccountImport(preview.id)
    setResult(created); setCredentials(undefined)
    setRecent(prev => [created, ...prev.filter(r => r.id !== created.id)].slice(0, 20))
    onCreated?.()
    message.success(`已创建 ${created.created} 个账号，请下载并逐人交付设密链接`)
  })
  const download = () => run(async () => {
    if (!result) return
    // Load the writer before issuing secrets so a failed chunk load does not invalidate links.
    const XLSX = await import('xlsx')
    const links = credentials ?? await issueAccountCredentials(result.id)
    setCredentials(links)
    if (!links.length) { message.info('此批次没有待激活账号'); return }
    const sheet = XLSX.utils.json_to_sheet(links.map(c => ({
      姓名: c.name, 用户名: c.username, 部门: c.deptName ?? '未分配', 角色: c.roleName,
      设密链接: `${window.location.origin}/activate-account#token=${c.token}`,
      有效期至: c.expiresAt.replace('T', ' '),
      使用说明: '请仅将本人的链接交付给对应成员；设置至少8位密码后登录。',
    })))
    sheet['!cols'] = [{ wch: 12 }, { wch: 20 }, { wch: 18 }, { wch: 18 }, { wch: 100 }, { wch: 24 }, { wch: 60 }]
    const book = XLSX.utils.book_new()
    XLSX.utils.book_append_sheet(book, sheet, '开户凭证')
    XLSX.writeFile(book, `开户凭证-${result.id.slice(0, 8)}.xlsx`)
  })

  return <>
    <Button icon={<ImportOutlined />} onClick={() => setOpen(true)}>从花名册导入账号</Button>
    <FormSurface mode="drawer" width={980} title="从花名册批量开户" open={open}
      description="选择成员、确认授权，再将每个人的一次性设密链接分别交付。"
      onCancel={close} closable={!busy} keyboard={!busy} confirmLoading={busy}
      footer={<Space wrap>
        <Button onClick={close} disabled={busy}>{result ? '完成' : '取消'}</Button>
        {!result && preview && <Button disabled={busy} onClick={() => setPreview(undefined)}>返回调整</Button>}
        {!result && !preview && <Button type="primary" loading={busy} disabled={loading || !selected.length || selected.some(id => !roleIds[id])}
          onClick={() => run(async () => {
            if (sessionId == null) return
            setPreview(await previewAccountImport({ sessionId, memberIds: selected,
              roleIds: Object.fromEntries(selected.map(id => [id, roleIds[id]])) }))
          })}>预览授权（{selected.length} 人）</Button>}
        {!result && preview && <Button type="primary" loading={busy} onClick={confirm}>确认创建 {preview.rows.length} 个账号</Button>}
        {result && (credentials
          ? <Button type="primary" icon={<DownloadOutlined />} loading={busy} onClick={download}>再次下载开户凭证</Button>
          : <Popconfirm title="生成并下载设密链接？" description="仅处理待激活账号。此前未使用的链接将失效，新链接有效期为 48 小时。"
            onConfirm={download} disabled={busy} okText="生成并下载" cancelText="取消">
            <Button type="primary" icon={<DownloadOutlined />} loading={busy}
              disabled={result.accounts.every(a => !a.canIssue)}>下载开户凭证</Button>
          </Popconfirm>)}
      </Space>}>
      {error && <Alert type="error" showIcon message={error} style={{ marginBottom: 16 }} />}
      {historyError && <Alert type="warning" showIcon message={historyError} style={{ marginBottom: 16 }} />}
      {result ? <FormSection title={`已创建 ${result.created} 个账号`} description="账号在成员设置密码后启用。凭证包含个人设密链接，请逐人交付；重新生成会替换尚未使用的旧链接。">
        <Table rowKey="userId" size="small" dataSource={result.accounts} pagination={{ pageSize: 10 }} scroll={{ x: 620 }} columns={[
          { title: '姓名', dataIndex: 'name' }, { title: '用户名', dataIndex: 'username' },
          { title: '部门', dataIndex: 'deptName', render: v => v || '未分配' }, { title: '角色', dataIndex: 'roleName' },
          { title: '激活状态', render: (_, row) => <Tag color={row.activated ? 'success' : 'default'}>{row.activated ? '已激活' : row.canIssue ? '待设密' : '需单独处理'}</Tag> },
        ]} />
      </FormSection> : preview ? <FormSection title="逐人确认账号与授权" description={`预览有效期至 ${preview.expiresAt.replace('T', ' ')}。业务权限范围为本组织全部；不会授予管理员身份。`}>
        <Table rowKey="memberId" size="small" dataSource={preview.rows} pagination={{ pageSize: 10 }} scroll={{ x: 700 }} columns={[
          { title: '姓名', dataIndex: 'name', width: 90 }, { title: '用户名', dataIndex: 'username', width: 130 },
          { title: '部门', dataIndex: 'deptName', width: 120, render: v => v || '未分配' },
          { title: '角色', dataIndex: 'roleName', width: 120 },
          { title: '业务权限', render: (_, row) => row.permissionNames.join('、') || '无业务权限' },
        ]} />
      </FormSection> : <>
        {recent.length > 0 && <FormSection title="凭证补发" description="可重新打开本人最近 20 次开户结果，补发尚未激活账号的设密链接。">
          <Select aria-label="选择历史开户批次" placeholder="查看最近开户批次" style={{ width: '100%' }} disabled={busy}
            getPopupContainer={popupContainer}
            options={recent.map(r => ({ value: r.id, label: `${r.id.slice(0, 8)} · ${r.created} 人 · ${r.accounts.filter(a => a.canIssue).length} 人待激活` }))}
            onChange={id => { setResult(recent.find(r => r.id === id)); setCredentials(undefined); setError('') }} />
        </FormSection>}
        <FormSection title="选择届别与成员" description="按职位和部门自动预选可分配角色，可逐人调整。仅显示有有效学号、在职且未开户的成员；单次最多 200 人。">
          <Space wrap><Select aria-label="选择届别" placeholder="选择届别" value={sessionId} disabled={busy || loading} style={{ width: 240 }}
            getPopupContainer={popupContainer}
            options={sessions.map(s => ({ value: s.id, label: `${s.name}${s.isCurrent === 1 ? '（当前）' : ''}` }))}
            onChange={id => { setSessionId(id); setError('') }} />
            <Button icon={<ReloadOutlined />} disabled={busy || loading || sessionId == null} onClick={() => { setRefreshKey(v => v + 1); setError('') }}>刷新名单</Button>
          </Space>
          {!loading && !error && !sessions.length && <Alert type="info" message="请先在成员管理中建立届别并导入花名册。" />}
          {!loading && !error && !roles.length && <Alert type="warning" message="没有可分配的已发布角色，请联系组织所有者配置角色或授权范围。" />}
          <Space wrap style={{ margin: '12px 0' }}>
            <Select aria-label="批量选择角色" placeholder="为选中成员统一选择角色" value={bulkRole} options={options} disabled={busy || loading} loading={listLoading} style={{ width: 240 }} onChange={setBulkRole}
              getPopupContainer={popupContainer} virtual={options.length > 100} showSearch optionFilterProp="label" />
            <Button disabled={busy || !bulkRole || !selected.length} onClick={() => setRoleIds(prev => ({ ...prev, ...Object.fromEntries(selected.map(id => [id, bulkRole!])) }))}>应用到选中成员</Button>
          </Space>
          <Table<UnregisteredMember> rowKey="id" size="small" loading={loading} dataSource={candidates} pagination={{ pageSize: 10 }} scroll={{ x: 680 }}
            locale={{ emptyText: '该届暂无可开户的在职成员' }} rowSelection={{ selectedRowKeys: selected,
              onChange: keys => setSelected(keys.map(Number).slice(0, 200)),
              getCheckboxProps: row => ({ disabled: busy || (selected.length >= 200 && !selected.includes(row.id)) }),
            }} columns={[
              { title: '姓名', dataIndex: 'name', width: 100 }, { title: '学号 / 用户名', dataIndex: 'studentNo', width: 140 },
              { title: '部门', dataIndex: 'deptName', width: 120, render: v => v || '未分配' }, { title: '职位', dataIndex: 'positionLabel', width: 90 },
              { title: '开户角色', width: 240, render: (_, row) => <>
                <Select aria-label={`${row.name}的开户角色`} style={{ width: '100%' }} placeholder="请选择角色" options={options} value={roleIds[row.id]} disabled={busy || loading}
                  getPopupContainer={popupContainer} virtual={options.length > 100} showSearch optionFilterProp="label"
                  onChange={id => setRoleIds(prev => ({ ...prev, [row.id]: id }))} />
                {!roleIds[row.id] && row.recommendationReason && <div className="records-note">{row.recommendationReason}</div>}
              </> },
            ]} />
        </FormSection>
      </>}
    </FormSurface>
  </>
}
