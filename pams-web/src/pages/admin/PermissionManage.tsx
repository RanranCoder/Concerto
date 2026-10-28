import './permissions.css'
import RoleLinkedRecords from './RoleLinkedRecords'
import PermissionDelta from './PermissionDelta'
import { useBlocker } from 'react-router-dom'
import PermissionExplanation from './PermissionExplanation'
import PermissionGovernance, { managementLabel } from './PermissionGovernance'
import { grantChanges, togglePermission } from './permissionEditing'
import FormSurface from '@/components/form/FormSurface'
import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  Alert,
  App,
  Button,
  Checkbox,
  Empty,
  Form,
  Grid,
  Input,
  Pagination,
  Select,
  Space,
  Spin,
  Table,
  Tabs,
  Tag,
} from 'antd'
import PageHeader from '@/components/glass/PageHeader'
import GlassCard from '@/components/glass/GlassCard'
import { useAuthStore } from '@/stores/auth'
import { meApi } from '@/api/auth'
import { listUserOptions, type UserOptionVO } from '@/api/user'
import {
  roleTemplates,
  updateRoleMetadata,
  getAdministrator,
  managedRolePage,
  roleReferences,
  discardRoleDraft,
  deleteUnpublishedRole,
  roleCatalog,
  roleDetail,
  createRole,
  saveRoleDraft,
  previewRole,
  applyAuthorizationChange,
  previewRoleAssignment,
  previewAdministrator,
  authorizationChange,
  effectivePermissions,
  type EffectivePermissions,
  type ManagedRole,
  type RoleDetail,
  type CatalogPermission,
  type Grant,
  type ChangePreview,
} from '@/api/permission'

const statusLabel = {
  DRAFT: '草稿',
  ACTIVE: '启用',
  DISABLED: '停用',
  ARCHIVED: '归档',
}
const actionLabel: Record<string, string> = {
  PUBLISH: '发布权限',
  ENABLE: '启用角色',
  DISABLE: '停用角色',
  ARCHIVE: '归档角色',
  CREATE_ROLE: '新建角色',
  UPDATE_ROLE: '修改角色信息',
  SAVE_DRAFT: '保存工作稿',
  DELETE_DRAFT: '删除草稿',
  ASSIGN: '替换主角色',
  DELEGATE: '调整管理员委派',
}
export default function PermissionManage() {
  const screens = Grid.useBreakpoint()
  const { message, modal } = App.useApp()
  const currentUser = useAuthStore((s) => s.user)
  const refreshUser = useAuthStore((s) => s.refreshUser)
  const caps = currentUser?.managementCapabilities ?? []
  const canEdit = caps.includes('role:manage')
  const canGrant = caps.includes('role:grant')
  const canAssign = caps.includes('role:assign')
  const [roles, setRoles] = useState<ManagedRole[]>([])
  const [catalog, setCatalog] = useState<CatalogPermission[]>([])
  const [selected, setSelected] = useState<number>()
  const [detail, setDetail] = useState<RoleDetail>()
  const [grants, setGrants] = useState<Grant[]>([])
  const [filter, setFilter] = useState('')
  const [rolePage, setRolePage] = useState(1)
  const [roleTotal, setRoleTotal] = useState(0)
  const [reason, setReason] = useState('')
  const [referencesOpen, setReferencesOpen] = useState(false)
  const [roleFilter, setRoleFilter] = useState('')
  const [statusFilter, setStatusFilter] = useState('')
  const [onlyDraft, setOnlyDraft] = useState(false)
  const [module, setModule] = useState('')
  const [onlySelected, setOnlySelected] = useState(false)
  const [onlyChanged, setOnlyChanged] = useState(false)
  const [onlyRisk, setOnlyRisk] = useState(false)
  const [topTab, setTopTab] = useState('roles')
  const [showPublished, setShowPublished] = useState(false)
  const [historyGrants, setHistoryGrants] = useState<Grant[]>()
  const [loadError, setLoadError] = useState('')
  const [busy, setBusy] = useState(false)
  const [loading, setLoading] = useState(false)
  const [dirty, setDirty] = useState(false)
  const blocker = useBlocker(dirty)
  useEffect(() => {
    if (blocker.state !== 'blocked') return
    const dialog = modal.confirm({
      title: '放弃尚未保存的修改？',
      okText: '放弃修改',
      cancelText: '继续编辑',
      onOk: () => {
        setDirty(false)
        blocker.proceed()
      },
      onCancel: () => blocker.reset(),
    })
    return () => dialog.destroy()
  }, [blocker, modal])
  const [creation, setCreation] = useState<{
    source?: number
    editing?: number
  }>()
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [templates, setTemplates] = useState<{ code: string; name: string }[]>(
    [],
  )
  const [template, setTemplate] = useState<string[]>([])
  const [preview, setPreview] = useState<ChangePreview>()
  const [people, setPeople] = useState<UserOptionVO[]>([])
  const [person, setPerson] = useState<number>()
  const [adminOpen, setAdminOpen] = useState(false)
  const [adminPerson, setAdminPerson] = useState<number>()
  const [adminGrants, setAdminGrants] = useState<string[]>([])
  const [adminExpires, setAdminExpires] = useState('')
  const [adminVersion, setAdminVersion] = useState(0)
  const [adminLoading, setAdminLoading] = useState(false)
  const [effectiveUserId, setEffectiveUserId] = useState<number>()
  const [effective, setEffective] = useState<EffectivePermissions>()
  const loadList = useCallback(async () => {
    const [r, c] = await Promise.all([
      managedRolePage(rolePage, roleFilter, statusFilter, onlyDraft),
      roleCatalog(),
    ])
    setRoles(r.items)
    setRoleTotal(r.total)
    setCatalog(c)
    setSelected((id) => id ?? r.items[0]?.id)
  }, [rolePage, roleFilter, statusFilter, onlyDraft])
  useEffect(() => {
    if (!currentUser?.managementCapabilities.includes('role:view')) return
    roleCatalog()
      .then(setCatalog)
      .catch(() => {})
  }, [currentUser?.authzVersion])
  const loadDetail = useCallback(
    async (id: number) => {
      setLoading(true)
      try {
        const d = await roleDetail(id)
        setDetail(d)
        setShowPublished(!canEdit || d.actions?.canEdit === false)
        setGrants(d.draft ? JSON.parse(d.draft.grants) : d.published)
        setDirty(false)
      } finally {
        setLoading(false)
      }
    },
    [canEdit],
  )
  useEffect(() => {
    roleTemplates()
      .then(setTemplates)
      .catch(() => {})
    loadList().catch(() => setLoadError('角色与权限目录读取失败，请重试。'))
    listUserOptions()
      .then(setPeople)
      .catch(() => {})
  }, [loadList])
  useEffect(() => {
    if (!selected) return
    let alive = true
    setDetail(undefined)
    setLoading(true)
    roleDetail(selected)
      .then((d) => {
        if (alive) {
          setDetail(d)
          setShowPublished(!canEdit || d.actions?.canEdit === false)
          setGrants(d.draft ? JSON.parse(d.draft.grants) : d.published)
          setDirty(false)
        }
      })
      .catch(() => {
        if (alive) setLoadError('角色详情读取失败，请重试。')
      })
      .finally(() => {
        if (alive) setLoading(false)
      })
    return () => {
      alive = false
    }
  }, [selected, canEdit])
  useEffect(() => {
    const listener = (e: BeforeUnloadEvent) => {
      if (dirty) e.preventDefault()
    }
    window.addEventListener('beforeunload', listener)
    return () => window.removeEventListener('beforeunload', listener)
  }, [dirty])
  const run = async (work: () => Promise<void>) => {
    setBusy(true)
    try {
      await work()
    } catch {
      /* API interceptor displays errors; keep the form */
    } finally {
      setBusy(false)
    }
  }
  useEffect(() => {
    if (!adminPerson) return
    let alive = true
    setAdminLoading(true)
    setAdminGrants([])
    setAdminExpires('')
    getAdministrator(adminPerson)
      .then((d) => {
        if (alive) {
          setAdminGrants(d.grantable)
          setAdminVersion(d.version)
          setAdminExpires(d.expiresAt?.slice(0, 16) ?? '')
        }
      })
      .catch(() => {})
      .finally(() => {
        if (alive) setAdminLoading(false)
      })
    return () => {
      alive = false
    }
  }, [adminPerson])
  const baseline = detail?.draft
    ? (JSON.parse(detail.draft.grants) as Grant[])
    : (detail?.published ?? [])
  const changes = grantChanges(detail?.published ?? [], grants)
  const viewGrants = showPublished ? (detail?.published ?? []) : grants
  const modules = [
    ...new Map(
      catalog.map((p) => [p.module, p.groupName ?? p.module]),
    ).entries(),
  ]
  const roleCanEdit = canEdit && (detail?.actions?.canEdit ?? true)
  const roleCanGrant = canGrant && (detail?.actions?.canGrant ?? true)
  const rows = useMemo(
    () =>
      catalog.filter(
        (p) =>
          (!filter ||
            `${p.name} ${p.code} ${p.module} ${p.description ?? ''}`
              .toLowerCase()
              .includes(filter.toLowerCase())) &&
          (!module || p.module === module) &&
          (!onlySelected || viewGrants.some((g) => g.permission === p.code)) &&
          (!onlyChanged || changes.some((g) => g.code === p.code)) &&
          (!onlyRisk || p.risk),
      ),
    [
      catalog,
      filter,
      module,
      onlySelected,
      onlyChanged,
      onlyRisk,
      viewGrants,
      changes,
    ],
  )
  const selectedCodes = new Set(viewGrants.map((g) => g.permission))
  const previewRows = grantChanges(
    preview?.details.before ?? [],
    preview?.details.grants ?? [],
  )
  const editPermission = (p: CatalogPermission, checked: boolean) => {
    try {
      const next = togglePermission(catalog, grants, p.code, checked)
      const apply = () => {
        setGrants(next.grants)
        setDirty(true)
      }
      if (next.changed.length > 1)
        modal.confirm({
          title: checked ? '同时添加必要依赖？' : '同时撤销依赖此权限的操作？',
          content: next.changed
            .map(
              (code) =>
                catalog.find((item) => item.code === code)?.name ?? code,
            )
            .join('、'),
          onOk: apply,
        })
      else apply()
    } catch (error) {
      message.warning(error instanceof Error ? error.message : '无法调整此操作')
    }
  }
  const previewChange = (action: string) =>
    run(async () => {
      if (!detail || !selected) return
      if (['DISABLE', 'ARCHIVE'].includes(action)) {
        const refs = await roleReferences(selected)
        if (refs.blockingTotal > 0) {
          setReferencesOpen(true)
          return
        }
      }
      if (dirty && ['PUBLISH', 'ENABLE'].includes(action)) {
        await saveRoleDraft(
          selected,
          detail.draft?.version ?? 0,
          grants,
          detail.draft ? JSON.parse(detail.draft.grants) : detail.published,
        )
        await loadDetail(selected)
      }
      setPreview(
        await previewRole(selected, action, detail.role.config_version, reason),
      )
    })
  const chooseRole = (id: number) => {
    if (!dirty) setSelected(id)
    else
      modal.confirm({
        title: '放弃尚未保存的修改？',
        onOk: () => {
          setDirty(false)
          setSelected(id)
        },
      })
  }
  return (
    <div className="permission-workspace">
      <PageHeader
        title="角色与权限"
        description={`${currentUser?.orgName ?? '当前组织'} · 业务角色与管理身份分别授权`}
        extra={
          <Space wrap>
            <Select
              aria-label="查询人员有效权限"
              placeholder="查询人员有效权限"
              allowClear
              showSearch
              optionFilterProp="label"
              style={{ minWidth: 190 }}
              value={effectiveUserId}
              options={people.map((p) => ({ value: p.id, label: p.realName }))}
              onChange={(id) => {
                setEffectiveUserId(id)
                if (id)
                  void run(async () =>
                    setEffective(await effectivePermissions(id)),
                  )
                else setEffective(undefined)
              }}
            />
            {caps.includes('org:audit:view') && (
              <Button
                disabled={dirty}
                title={dirty ? '请先保存或取消本地修改' : undefined}
                onClick={() => setTopTab('audit')}
              >
                治理记录
              </Button>
            )}
            {currentUser?.isOwner && (
              <Button onClick={() => setAdminOpen(true)}>设置组织管理员</Button>
            )}
            {canEdit && (
              <Button
                type="primary"
                disabled={busy || dirty}
                title={dirty ? '请先保存或取消本地修改' : undefined}
                onClick={() => {
                  setName('')
                  setDescription('')
                  setTemplate([])
                  setCreation({})
                }}
              >
                新建角色
              </Button>
            )}
          </Space>
        }
      />
      <Tabs
        className="permission-top-tabs"
        activeKey={topTab}
        onChange={(value) => {
          if (!dirty) setTopTab(value)
          else
            modal.confirm({
              title: '放弃尚未保存的修改？',
              onOk: () => {
                setGrants(baseline)
                setDirty(false)
                setTopTab(value)
              },
            })
        }}
        items={[
          { key: 'roles', label: '业务角色' },
          { key: 'management', label: '管理身份' },
          ...(caps.includes('org:audit:view')
            ? [{ key: 'audit', label: '变更记录' }]
            : []),
          { key: 'migration', label: '迁移核对' },
        ]}
      />
      {loadError && (
        <Alert
          type="error"
          message={loadError}
          action={
            <Button
              onClick={() =>
                run(async () => {
                  await loadList()
                  if (selected && !dirty) await loadDetail(selected)
                  setLoadError('')
                })
              }
            >
              重试
            </Button>
          }
        />
      )}
      {topTab !== 'roles' && (
        <PermissionGovernance
          mode={topTab}
          catalog={catalog}
          onDelegate={(id) => {
            setAdminPerson(id)
            setAdminOpen(true)
          }}
          onSelectRole={(id) => {
            setSelected(id)
            setTopTab('roles')
          }}
        />
      )}
      <div
        style={{
          display: topTab === 'roles' ? 'grid' : 'none',
          gridTemplateColumns: screens.md
            ? 'minmax(180px, 1fr) minmax(0, 4fr)'
            : 'minmax(0, 1fr)',
          gap: 16,
        }}
      >
        <GlassCard style={{ padding: 12 }}>
          {!screens.md && (
            <Select
              aria-label="选择业务角色"
              showSearch
              optionFilterProp="label"
              style={{ width: '100%' }}
              value={selected}
              onChange={chooseRole}
              options={roles.map((role) => ({
                value: role.id,
                label: `${role.name} · ${statusLabel[role.status]}`,
              }))}
            />
          )}
          <div style={{ display: screens.md ? 'block' : 'none' }}>
            <Input
              aria-label="搜索角色"
              placeholder="搜索角色或职责"
              value={roleFilter}
              onChange={(e) => {
                setRoleFilter(e.target.value)
                setRolePage(1)
              }}
              allowClear
            />
            <Select
              aria-label="角色状态"
              value={statusFilter}
              onChange={(value) => {
                setStatusFilter(value)
                setRolePage(1)
              }}
              style={{ width: '100%', margin: '10px 0' }}
              options={[
                { value: '', label: '全部状态' },
                ...Object.entries(statusLabel).map(([value, label]) => ({
                  value,
                  label,
                })),
              ]}
            />
            <Checkbox
              checked={onlyDraft}
              onChange={(e) => {
                setOnlyDraft(e.target.checked)
                setRolePage(1)
              }}
            >
              仅看有工作稿
            </Checkbox>
            <div className="permission-role-list" style={{ marginTop: 12 }}>
              {roles.map((r) => (
                <Button
                  key={r.id}
                  block
                  type={selected === r.id ? 'primary' : 'default'}
                  onClick={() => chooseRole(r.id)}
                >
                  <span>
                    <span className="permission-role-name">
                      {r.name}
                      <small>{statusLabel[r.status]}</small>
                    </span>
                    <span className="permission-role-meta">
                      {r.description || '尚未填写职责说明'}
                    </span>
                    <span className="permission-role-meta">
                      {r.userCount ?? 0} 个账号 · {r.permissionCount ?? 0}{' '}
                      项生效权限
                      {r.draftRevisionId ? ' · 有工作稿' : ''}
                    </span>
                    <span className="permission-role-meta">
                      {{ COPY: '复制', TEMPLATE: '模板', BLANK: '空白' }[
                        r.sourceKind ?? ''
                      ] ?? '存量迁移'}{' '}
                      · {r.updatedByName || '历史记录'}
                      {r.updatedAt
                        ? ' · ' + new Date(r.updatedAt).toLocaleString('zh-CN')
                        : ''}
                    </span>
                  </span>
                </Button>
              ))}
            </div>
            <Pagination
              size="small"
              current={rolePage}
              pageSize={20}
              total={roleTotal}
              showSizeChanger={false}
              onChange={setRolePage}
            />
          </div>
        </GlassCard>
        <GlassCard style={{ padding: 20, minWidth: 0 }}>
          <Spin spinning={loading}>
            {detail ? (
              <>
                <Space wrap style={{ marginBottom: 16 }}>
                  <strong>{detail.role.name}</strong>
                  <Tag>{statusLabel[detail.role.status]}</Tag>
                  <span>
                    版本 {detail.role.config_version} · 使用账号{' '}
                    {detail.impact?.userCount ?? detail.users.length} 人
                  </span>
                  {roleCanEdit && (
                    <Button
                      disabled={dirty}
                      onClick={() => {
                        setName(detail.role.name)
                        setDescription(
                          roles.find((r) => r.id === selected)?.description ??
                            '',
                        )
                        setCreation({ editing: selected })
                      }}
                    >
                      修改信息
                    </Button>
                  )}
                  {canEdit && (
                    <Button
                      disabled={dirty}
                      onClick={() => {
                        setTemplate([])
                        setName(`${detail.role.name}副本`)
                        setDescription('')
                        setCreation({ source: selected })
                      }}
                    >
                      复制角色
                    </Button>
                  )}
                  {roleCanGrant && detail.role.status === 'ACTIVE' && (
                    <Button
                      danger
                      disabled={busy || dirty}
                      onClick={() => previewChange('DISABLE')}
                    >
                      停用
                    </Button>
                  )}
                  {canGrant && detail.role.status === 'DISABLED' && (
                    <Button
                      disabled={busy || dirty}
                      onClick={() => previewChange('ARCHIVE')}
                    >
                      归档
                    </Button>
                  )}
                </Space>
                <Space wrap style={{ marginBottom: 16 }}>
                  <Button
                    disabled={busy || dirty}
                    onClick={() => setReferencesOpen(true)}
                  >
                    查看引用清单
                  </Button>
                  {roleCanEdit && detail.draft && (
                    <Button
                      danger
                      disabled={busy || dirty}
                      onClick={() =>
                        modal.confirm({
                          title:
                            detail.role.status === 'DRAFT'
                              ? '删除尚未发布的角色？'
                              : '放弃已保存工作稿？',
                          content: '此操作会核对最新版本，已发布历史会保留。',
                          onOk: () =>
                            run(async () => {
                              if (detail.role.status === 'DRAFT') {
                                await deleteUnpublishedRole(
                                  selected!,
                                  detail.role.config_version,
                                  detail.draft!.version,
                                )
                                setSelected(undefined)
                                setDetail(undefined)
                              } else {
                                await discardRoleDraft(
                                  selected!,
                                  detail.role.config_version,
                                  detail.draft!.version,
                                )
                                await loadDetail(selected!)
                              }
                              await loadList()
                              message.success('已处理工作稿')
                            }),
                        })
                      }
                    >
                      {detail.role.status === 'DRAFT'
                        ? '删除未发布角色'
                        : '放弃已保存工作稿'}
                    </Button>
                  )}
                </Space>
                {(roleCanGrant || canAssign) && (
                  <Form.Item label="本次变更原因（选填）">
                    <Input.TextArea
                      aria-label="变更原因"
                      maxLength={500}
                      value={reason}
                      onChange={(e) => setReason(e.target.value)}
                      placeholder="如：职责调整、人员交接；原因会随预览绑定并留在审计记录中"
                    />
                  </Form.Item>
                )}
                <Tabs
                  items={[
                    {
                      key: 'grants',
                      label: '权限配置',
                      children: (
                        <>
                          {detail.actions?.reason && (
                            <Alert
                              type="info"
                              message={detail.actions.reason}
                            />
                          )}
                          {detail.migrationReview?.some(
                            (r) => r.status === 'PENDING',
                          ) && (
                            <Alert
                              type="warning"
                              showIcon
                              message="此角色有待核对考勤权限。工作稿保留原候选授权，请确认哪些职责应保留，再预览发布。"
                            />
                          )}
                          <p className="permission-caption">
                            {showPublished
                              ? detail.role.status === 'ACTIVE'
                                ? '当前显示已发布配置，正在生效。'
                                : '当前显示已发布配置；未发布、停用或归档的角色不产生业务权限。'
                              : detail.draft
                                ? '当前显示已保存工作稿，发布前不会改变实际权限。'
                                : '当前显示角色配置。修改后需预览发布。'}{' '}
                            本期授权范围为本组织，仍受作者、指定审核人和业务状态限制。
                          </p>
                          <Space wrap>
                            <Button
                              size="small"
                              type={showPublished ? 'primary' : 'default'}
                              disabled={dirty}
                              onClick={() => setShowPublished(true)}
                            >
                              已发布配置
                            </Button>
                            <Button
                              size="small"
                              type={!showPublished ? 'primary' : 'default'}
                              onClick={() => setShowPublished(false)}
                            >
                              工作稿
                            </Button>
                          </Space>
                          <nav
                            className="permission-module-nav"
                            aria-label="业务权限分组"
                          >
                            <button
                              aria-pressed={!module}
                              onClick={() => setModule('')}
                            >
                              全部业务
                            </button>
                            {modules.map(([code, label]) => (
                              <button
                                key={code}
                                aria-pressed={module === code}
                                onClick={() => setModule(code)}
                              >
                                {label}
                              </button>
                            ))}
                          </nav>
                          <Space wrap>
                            <Checkbox
                              checked={onlySelected}
                              onChange={(e) =>
                                setOnlySelected(e.target.checked)
                              }
                            >
                              仅看已授权
                            </Checkbox>
                            <Checkbox
                              checked={onlyChanged}
                              onChange={(e) => setOnlyChanged(e.target.checked)}
                            >
                              仅看改动
                            </Checkbox>
                            <Checkbox
                              checked={onlyRisk}
                              onChange={(e) => setOnlyRisk(e.target.checked)}
                            >
                              重点影响操作
                            </Checkbox>
                          </Space>
                          <Input
                            placeholder="搜索模块、操作或权限码"
                            value={filter}
                            onChange={(e) => setFilter(e.target.value)}
                            style={{ marginBottom: 12 }}
                          />
                          <Table
                            rowKey="code"
                            size="small"
                            pagination={{ pageSize: 12 }}
                            scroll={{ x: 520 }}
                            dataSource={rows}
                            columns={[
                              {
                                title: '模块',
                                render: (_, p: CatalogPermission) =>
                                  p.groupName ?? p.module,
                                width: 120,
                              },
                              {
                                title: '操作',
                                render: (_, p: CatalogPermission) => (
                                  <div>
                                    <div className="permission-op-name">
                                      {p.name}
                                      {p.risk && (
                                        <Tag color="warning">重点影响</Tag>
                                      )}
                                      {changes.some(
                                        (g) => g.code === p.code,
                                      ) && <Tag color="processing">待发布</Tag>}
                                    </div>
                                    <div className="permission-op-description">
                                      {p.description}
                                    </div>
                                    {!!p.dependencies?.length && (
                                      <div className="permission-op-description">
                                        依赖：
                                        {p.dependencies
                                          .map(
                                            (code) =>
                                              catalog.find(
                                                (item) => item.code === code,
                                              )?.name ?? code,
                                          )
                                          .join('、')}
                                      </div>
                                    )}
                                  </div>
                                ),
                              },
                              {
                                title: '允许',
                                width: 70,
                                render: (_, p: CatalogPermission) => (
                                  <Checkbox
                                    aria-label={p.name}
                                    checked={selectedCodes.has(p.code)}
                                    disabled={
                                      !roleCanEdit ||
                                      showPublished ||
                                      !p.grantable ||
                                      detail.role.status === 'ARCHIVED'
                                    }
                                    onChange={(e) =>
                                      editPermission(p, e.target.checked)
                                    }
                                  />
                                ),
                              },
                              {
                                title: '数据范围',
                                width: 130,
                                render: (_, p: CatalogPermission) =>
                                  selectedCodes.has(p.code)
                                    ? p.code.includes(':self:') ||
                                      p.code === 'schedule:check'
                                      ? '本人'
                                      : '本组织（受业务规则约束）'
                                    : '—',
                              },
                              {
                                title: '说明',
                                render: (_, p: CatalogPermission) => (
                                  <span
                                    style={{ color: 'var(--text-secondary)' }}
                                  >
                                    {p.reason || p.code}
                                    {p.packVersion != null &&
                                      p.packVersion > 0 &&
                                      p.grantable &&
                                      grants.some(
                                        (g) =>
                                          g.permission === p.code &&
                                          g.packVersion != null &&
                                          g.packVersion !== p.packVersion,
                                      ) && (
                                        <Button
                                          type="link"
                                          size="small"
                                          disabled={!canEdit}
                                          onClick={() => {
                                            setGrants((g) =>
                                              g.map((x) =>
                                                x.permission === p.code
                                                  ? {
                                                      permission: p.code,
                                                      scope: 'ORG',
                                                    }
                                                  : x,
                                              ),
                                            )
                                            setDirty(true)
                                          }}
                                        >
                                          重新确认授权
                                        </Button>
                                      )}
                                  </span>
                                ),
                              },
                            ]}
                          />
                          <div className="permission-sticky-actions">
                            <Space wrap>
                              <span>
                                已选 {viewGrants.length} 项 · 待发布{' '}
                                {changes.length} 项
                                {dirty
                                  ? ' · 有未保存修改'
                                  : detail.draft
                                    ? ' · 已保存工作稿'
                                    : ''}
                              </span>
                              {roleCanEdit && !showPublished && (
                                <Button
                                  loading={busy}
                                  disabled={
                                    !dirty || detail.role.status === 'ARCHIVED'
                                  }
                                  onClick={() =>
                                    run(async () => {
                                      await saveRoleDraft(
                                        selected!,
                                        detail.draft?.version ?? 0,
                                        grants,
                                        detail.draft
                                          ? JSON.parse(detail.draft.grants)
                                          : detail.published,
                                      )
                                      await loadDetail(selected!)
                                      message.success(
                                        '工作稿已保存，当前权限尚未改变',
                                      )
                                    })
                                  }
                                >
                                  保存工作稿
                                </Button>
                              )}
                              {roleCanGrant &&
                                !showPublished &&
                                detail.role.status !== 'ARCHIVED' && (
                                  <Button
                                    type="primary"
                                    loading={busy}
                                    disabled={
                                      !dirty &&
                                      !detail.draft &&
                                      detail.role.status === 'ACTIVE'
                                    }
                                    onClick={() =>
                                      previewChange(
                                        detail.role.status === 'DISABLED'
                                          ? 'ENABLE'
                                          : 'PUBLISH',
                                      )
                                    }
                                  >
                                    预览并发布
                                  </Button>
                                )}
                              {dirty && (
                                <Button
                                  onClick={() => {
                                    setGrants(baseline)
                                    setDirty(false)
                                  }}
                                >
                                  取消本地修改
                                </Button>
                              )}
                            </Space>
                          </div>
                        </>
                      ),
                    },
                    {
                      key: 'users',
                      label: '使用用户',
                      children: (
                        <>
                          {canAssign && detail.role.status === 'ACTIVE' && (
                            <Space wrap style={{ marginBottom: 16 }}>
                              <Select
                                showSearch
                                optionFilterProp="label"
                                placeholder="选择要替换主角色的用户"
                                value={person}
                                onChange={setPerson}
                                style={{ minWidth: 240 }}
                                options={people.map((p) => ({
                                  value: p.id,
                                  label: `${p.realName} · ${p.deptName ?? '无部门'}`,
                                }))}
                              />
                              <Button
                                disabled={!person || busy || dirty}
                                onClick={() =>
                                  run(async () =>
                                    setPreview(
                                      await previewRoleAssignment(
                                        person!,
                                        selected!,
                                        reason,
                                      ),
                                    ),
                                  )
                                }
                              >
                                预览角色替换
                              </Button>
                            </Space>
                          )}
                          <RoleLinkedRecords
                            key={selected}
                            roleId={selected!}
                            mode="members"
                            onEffective={(id) =>
                              run(async () => {
                                setEffectiveUserId(id)
                                setEffective(await effectivePermissions(id))
                              })
                            }
                          />
                        </>
                      ),
                    },
                    {
                      key: 'history',
                      label: '发布历史',
                      children: (
                        <RoleLinkedRecords
                          key={selected}
                          roleId={selected!}
                          mode="history"
                          onHistory={(data) =>
                            setHistoryGrants(JSON.parse(data))
                          }
                        />
                      ),
                    },
                  ]}
                />
              </>
            ) : (
              <Empty description="请选择角色或创建新角色" />
            )}
          </Spin>
        </GlassCard>
      </div>
      <FormSurface
        title="角色引用清单"
        open={referencesOpen}
        footer={null}
        onCancel={() => setReferencesOpen(false)}
      >
        {selected && <RoleLinkedRecords roleId={selected} mode="references" />}
      </FormSurface>
      <FormSurface
        title="历史配置与当前生效配置对比"
        open={!!historyGrants}
        onCancel={() => setHistoryGrants(undefined)}
        footer={null}
        width={720}
      >
        <p className="permission-caption">
          历史版本只读，不覆盖当前授权。以下显示历史版本与当前生效配置的差异。
        </p>
        <Table
          rowKey="code"
          size="small"
          dataSource={grantChanges(
            historyGrants ?? [],
            detail?.published ?? [],
          )}
          columns={[
            {
              title: '操作',
              render: (_, row) =>
                catalog.find((p) => p.code === row.code)?.name ?? row.code,
            },
            {
              title: '历史版本',
              render: (_, row) => (row.old ? '已授权' : '无'),
            },
            {
              title: '当前生效',
              render: (_, row) => (row.next ? '已授权' : '无'),
            },
          ]}
        />
      </FormSurface>

      <FormSurface
        mode="dialog"
        title="有效权限与来源"
        open={!!effective}
        onCancel={() => setEffective(undefined)}
        footer={null}
      >
        <p>
          主角色：
          {effective?.roleName ??
            roles.find((r) => r.id === effective?.roleId)?.name ??
            '角色不可用'}
        </p>
        <p>
          管理身份：
          {effective?.managementSource === 'OWNER'
            ? '组织所有者'
            : effective?.managementSource === 'DELEGATED'
              ? '有效管理员委派'
              : '无'}
        </p>
        {effectiveUserId && <PermissionExplanation userId={effectiveUserId} />}
        <p>业务权限（来自当前启用角色的发布配置）</p>
        <Space wrap>
          {effective?.businessPermissions.map((code) => (
            <Tag key={code}>
              {catalog.find((p) => p.code === code)?.name ?? code}
            </Tag>
          ))}
        </Space>
        <Table
          rowKey="permission"
          size="small"
          pagination={{ pageSize: 8 }}
          dataSource={effective?.configured ?? []}
          columns={[
            {
              title: '已配置操作',
              render: (_, row) =>
                catalog.find((p) => p.code === row.permission)?.name ??
                row.permission,
            },
            {
              title: '生效状态',
              render: (_, row) => (row.effective ? '有效' : row.reason),
            },
          ]}
        />
        <p style={{ marginTop: 16 }}>管理能力</p>
        <Space wrap>
          {effective?.managementCapabilities.map((code) => (
            <Tag key={code}>{managementLabel(code)}</Tag>
          ))}
        </Space>
      </FormSurface>
      <FormSurface
        mode="dialog"
        title={
          creation?.editing
            ? '修改角色信息'
            : creation?.source
              ? '复制为独立角色'
              : '新建角色'
        }
        open={!!creation}
        okText={creation?.editing ? '保存角色' : '创建角色'}
        confirmLoading={busy}
        onCancel={() => setCreation(undefined)}
        onOk={() =>
          run(async () => {
            if (!name.trim()) {
              message.warning('请输入角色名称')
              return
            }
            if (creation?.editing) {
              await updateRoleMetadata(
                creation.editing,
                detail!.role.config_version,
                name.trim(),
                description,
              )
              setCreation(undefined)
              await loadList()
              await loadDetail(creation.editing)
              return
            }
            const id = await createRole(
              name.trim(),
              description,
              creation?.source,
              template,
            )
            setCreation(undefined)
            setDirty(false)
            await loadList()
            setSelected(id)
            message.success('已创建草稿，请配置并发布权限')
          })
        }
      >
        <Form layout="vertical">
          {!creation?.source && !creation?.editing && (
            <Form.Item label="职责模板（可组合）">
              <Select
                allowClear
                mode="multiple"
                placeholder="可选多个职责模板；留空为空白创建"
                value={template}
                onChange={setTemplate}
                style={{ width: '100%', marginBottom: 12 }}
                options={templates.map((t) => ({
                  value: t.code,
                  label: t.name,
                }))}
              />
            </Form.Item>
          )}
          <Form.Item label="角色名称" required>
            <Input
              aria-label="角色名称"
              placeholder="角色名称"
              maxLength={100}
              value={name}
              onChange={(e) => setName(e.target.value)}
            />
          </Form.Item>
          <Form.Item label="职责说明">
            <Input.TextArea
              aria-label="角色说明"
              placeholder="职责说明"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
            />
          </Form.Item>
          <p>新角色不会自动继承管理员身份或使用用户。</p>
        </Form>
      </FormSurface>
      <FormSurface
        mode="dialog"
        title="确认授权变更"
        okText="确认变更"
        open={!!preview}
        confirmLoading={busy}
        onCancel={() => setPreview(undefined)}
        onOk={() =>
          run(async () => {
            try {
              await applyAuthorizationChange(preview!.id)
            } catch (error) {
              const result = await authorizationChange(preview!.id)
              if (result.status !== 'APPLIED') throw error
            }
            setPreview(undefined)
            setReason('')
            await loadList()
            if (selected && !dirty) await loadDetail(selected)
            refreshUser(await meApi())
            message.success('变更已生效')
          })
        }
      >
        <p>变更原因：{preview?.details.reason || '未填写'}</p>
        {preview?.action === 'DELEGATE' ? (
          <>
            <p>
              {preview.details.active
                ? '保存管理员身份与可授权清单'
                : '撤销管理身份，原业务角色保留'}
              ：
              {people.find((p) => p.id === preview.targetId)?.realName ??
                `账号 #${preview.targetId}`}
            </p>
            <p>
              原可授权清单：
              {preview.details.delegationBefore?.grantable
                .map(
                  (code) => catalog.find((p) => p.code === code)?.name ?? code,
                )
                .join('、') || '无'}
            </p>
            <p>
              新可授权清单：
              {preview.details.grantable
                ?.map(
                  (code) => catalog.find((p) => p.code === code)?.name ?? code,
                )
                .join('、') || '无'}
            </p>
            <p>
              有效期：
              {preview.details.expiresAt
                ? new Date(preview.details.expiresAt).toLocaleString('zh-CN')
                : '长期'}
            </p>
          </>
        ) : preview?.action === 'ASSIGN' ? (
          <>
            <p>
              将{' '}
              {people.find((p) => p.id === preview.targetId)?.realName ??
                `账号 #${preview.targetId}`}{' '}
              的主角色从{' '}
              {roles.find((r) => r.id === preview.details.oldRoleId)?.name ??
                '原角色'}{' '}
              替换为{' '}
              {roles.find((r) => r.id === preview.details.roleId)?.name ??
                '目标角色'}
              。
            </p>
            <p>
              授权范围为本组织，仍受作者、指定审核人和业务状态限制。高影响操作请重点核对。
            </p>
            <PermissionDelta
              before={preview.details.before ?? []}
              after={preview.details.grants ?? []}
              catalog={catalog}
            />
          </>
        ) : (
          <>
            <p>
              动作：{actionLabel[preview?.action ?? '']}
              ；当前角色使用者都会受到影响。预览有效期：
              {preview?.expiresAt
                ? new Date(preview.expiresAt).toLocaleTimeString('zh-CN')
                : '5 分钟'}
              。
            </p>
            {preview?.details.impact && (
              <div className="permission-impact">
                <span>
                  影响账号 <strong>{preview.details.impact.userCount}</strong>
                </span>
                <span>
                  有效账号{' '}
                  <strong>{preview.details.impact.activeUserCount}</strong>
                </span>
                <span>
                  岗位引用{' '}
                  <strong>{preview.details.impact.positions.length}</strong>
                </span>
                <span>
                  有效邀请 <strong>{preview.details.impact.invitations}</strong>
                </span>
              </div>
            )}
            <Table
              rowKey="code"
              size="small"
              pagination={false}
              dataSource={previewRows}
              columns={[
                {
                  title: '权限',
                  render: (_, row) => (
                    <span>
                      {catalog.find((p) => p.code === row.code)?.name ??
                        row.code}
                      <small className="permission-op-code">{row.code}</small>
                    </span>
                  ),
                },
                {
                  title: '原配置',
                  render: (_, r) =>
                    r.old
                      ? `本组织全部${r.old.packVersion ? ` · 领域包授权批次 ${r.old.packVersion}` : ''}`
                      : '无',
                },
                {
                  title: '新配置',
                  render: (_, r) =>
                    r.next
                      ? `本组织全部${r.next.packVersion ? ` · 领域包授权批次 ${r.next.packVersion}` : ''}`
                      : '撤销',
                },
              ]}
            />
          </>
        )}
      </FormSurface>
      <FormSurface
        mode="drawer"
        description="选择人员，明确可授权范围与有效期。"
        title="组织管理员委派"
        open={adminOpen}
        confirmLoading={busy}
        onCancel={() => setAdminOpen(false)}
        footer={
          <Space>
            <Button onClick={() => setAdminOpen(false)}>取消</Button>
            <Button
              danger
              disabled={!adminPerson || busy || adminLoading}
              onClick={() =>
                run(async () => {
                  setPreview(
                    await previewAdministrator(
                      adminPerson!,
                      false,
                      [],
                      undefined,
                      adminVersion,
                      reason,
                    ),
                  )
                  setAdminOpen(false)
                  message.info('请核对变更后确认撤销')
                })
              }
            >
              撤销管理身份
            </Button>
            <Button
              type="primary"
              disabled={!adminPerson || busy || adminLoading}
              onClick={() =>
                run(async () => {
                  setPreview(
                    await previewAdministrator(
                      adminPerson!,
                      true,
                      adminGrants,
                      adminExpires ? `${adminExpires}:00` : undefined,
                      adminVersion,
                      reason,
                    ),
                  )
                  setAdminOpen(false)
                  message.info('请核对变更后确认保存')
                })
              }
            >
              预览委派
            </Button>
          </Space>
        }
      >
        <p>
          管理身份与主角色分开。该管理员可管理本组织普通角色和账号，能授予的业务操作限定为下方明确选择的清单。
        </p>
        <Form layout="vertical">
          <Form.Item label="变更原因（选填）">
            <Input.TextArea
              maxLength={500}
              value={reason}
              onChange={(e) => setReason(e.target.value)}
            />
          </Form.Item>
          <Form.Item label="委派给" required>
            <Select
              aria-label="委派给"
              showSearch
              optionFilterProp="label"
              placeholder="选择账号"
              value={adminPerson}
              onChange={setAdminPerson}
              style={{ width: '100%' }}
              options={people
                .filter((p) => p.id !== currentUser?.id)
                .map((p) => ({
                  value: p.id,
                  label: `${p.realName} (${p.id})`,
                }))}
            />
          </Form.Item>
          <Form.Item label="可授予的业务操作" required>
            <Select
              aria-label="可授予的业务操作"
              mode="multiple"
              placeholder="明确选择可授予的业务操作"
              value={adminGrants}
              onChange={setAdminGrants}
              style={{ width: '100%' }}
              options={catalog
                .filter((p) => p.grantable)
                .map((p) => ({
                  value: p.code,
                  label: `${p.module} · ${p.name}`,
                }))}
            />
          </Form.Item>
          <Form.Item label="管理身份有效期" extra="留空为长期有效。">
            <Input
              aria-label="管理身份有效期"
              type="datetime-local"
              value={adminExpires}
              onChange={(e) => setAdminExpires(e.target.value)}
            />
          </Form.Item>
        </Form>
      </FormSurface>
    </div>
  )
}
