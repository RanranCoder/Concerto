import FormSurface from '@/components/form/FormSurface'
import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  App,
  Button,
  Checkbox,
  Empty,
  Form,
  Grid,
  Input,
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
  managedRoles,
  roleCatalog,
  roleDetail,
  createRole,
  saveRoleDraft,
  previewRole,
  applyAuthorizationChange,
  previewRoleAssignment,
  setAdministrator,
  governanceLogs,
  effectivePermissions,
  type GovernanceLog,
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
  const [busy, setBusy] = useState(false)
  const [loading, setLoading] = useState(false)
  const [dirty, setDirty] = useState(false)
  const [creation, setCreation] = useState<{
    source?: number
    editing?: number
  }>()
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [templates, setTemplates] = useState<{ code: string; name: string }[]>(
    [],
  )
  const [template, setTemplate] = useState<string>()
  const [preview, setPreview] = useState<ChangePreview>()
  const [people, setPeople] = useState<UserOptionVO[]>([])
  const [person, setPerson] = useState<number>()
  const [adminOpen, setAdminOpen] = useState(false)
  const [adminPerson, setAdminPerson] = useState<number>()
  const [adminGrants, setAdminGrants] = useState<string[]>([])
  const [adminExpires, setAdminExpires] = useState('')
  const [adminVersion, setAdminVersion] = useState(0)
  const [logs, setLogs] = useState<GovernanceLog[]>()
  const [effective, setEffective] = useState<EffectivePermissions>()
  const loadList = useCallback(async () => {
    const [r, c] = await Promise.all([managedRoles(), roleCatalog()])
    setRoles(r)
    setCatalog(c)
    setSelected((id) => id ?? r[0]?.id)
  }, [])
  const loadDetail = useCallback(async (id: number) => {
    setLoading(true)
    try {
      const d = await roleDetail(id)
      setDetail(d)
      setGrants(d.draft ? JSON.parse(d.draft.grants) : d.published)
      setDirty(false)
    } finally {
      setLoading(false)
    }
  }, [])
  useEffect(() => {
    roleTemplates()
      .then(setTemplates)
      .catch(() => {})
    loadList().catch(() => {})
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
          setGrants(d.draft ? JSON.parse(d.draft.grants) : d.published)
          setDirty(false)
        }
      })
      .catch(() => {})
      .finally(() => {
        if (alive) setLoading(false)
      })
    return () => {
      alive = false
    }
  }, [selected])
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
    return () => {
      alive = false
    }
  }, [adminPerson])
  const rows = useMemo(
    () =>
      catalog.filter(
        (p) => !filter || `${p.name} ${p.code} ${p.module}`.includes(filter),
      ),
    [catalog, filter],
  )
  const selectedCodes = new Set(grants.map((g) => g.permission))
  const previewRows = useMemo(() => {
    if (!preview) return []
    const before = preview.details.before ?? []
    const after = preview.details.grants ?? []
    return [...new Set([...before, ...after].map((g) => g.permission))]
      .map((code) => {
        const old = before.find((g) => g.permission === code)
        const next = after.find((g) => g.permission === code)
        return { code, old, next }
      })
      .filter((r) => JSON.stringify(r.old) !== JSON.stringify(r.next))
  }, [preview])
  const previewChange = (action: string) =>
    run(async () => {
      if (!detail || !selected) return
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
        await previewRole(selected, action, detail.role.config_version),
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
    <div>
      <PageHeader
        title="角色与权限"
        description={`${currentUser?.orgName ?? '当前组织'} · 业务角色与管理身份分别授权`}
        extra={
          <Space wrap>
            {caps.includes('org:audit:view') && (
              <Button
                onClick={() => run(async () => setLogs(await governanceLogs()))}
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
                onClick={() => {
                  setName('')
                  setDescription('')
                  setTemplate(undefined)
                  setCreation({})
                }}
              >
                新建角色
              </Button>
            )}
          </Space>
        }
      />
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: screens.md
            ? 'minmax(180px, 1fr) minmax(0, 4fr)'
            : 'minmax(0, 1fr)',
          gap: 16,
        }}
      >
        <GlassCard style={{ padding: 12 }}>
          <div style={{ display: 'grid', gap: 8 }}>
            {roles.map((r) => (
              <Button
                key={r.id}
                block
                type={selected === r.id ? 'primary' : 'default'}
                onClick={() => chooseRole(r.id)}
              >
                {r.name} · {statusLabel[r.status]}
              </Button>
            ))}
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
                    {detail.users.length} 人
                  </span>
                  {canEdit && (
                    <Button
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
                      onClick={() => {
                        setTemplate(undefined)
                        setName(`${detail.role.name}副本`)
                        setDescription('')
                        setCreation({ source: selected })
                      }}
                    >
                      复制角色
                    </Button>
                  )}
                  {canGrant && detail.role.status === 'ACTIVE' && (
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
                <Tabs
                  items={[
                    {
                      key: 'grants',
                      label: '权限配置',
                      children: (
                        <>
                          <p>
                            首版开放全组织范围。部门、本人等范围将在资源关联及校验完成后开放；编辑活动权限也包含现有任务写操作。
                          </p>
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
                                dataIndex: 'module',
                                width: 120,
                              },
                              { title: '操作', dataIndex: 'name' },
                              {
                                title: '允许',
                                width: 70,
                                render: (_, p: CatalogPermission) => (
                                  <Checkbox
                                    aria-label={p.name}
                                    checked={selectedCodes.has(p.code)}
                                    disabled={
                                      !canEdit ||
                                      !p.grantable ||
                                      detail.role.status === 'ARCHIVED'
                                    }
                                    onChange={(e) => {
                                      setGrants((g) =>
                                        e.target.checked
                                          ? [
                                              ...g.filter(
                                                (x) => x.permission !== p.code,
                                              ),
                                              {
                                                permission: p.code,
                                                scope: 'ORG',
                                              },
                                            ]
                                          : g.filter(
                                              (x) => x.permission !== p.code,
                                            ),
                                      )
                                      setDirty(true)
                                    }}
                                  />
                                ),
                              },
                              {
                                title: '数据范围',
                                width: 130,
                                render: (_, p: CatalogPermission) =>
                                  selectedCodes.has(p.code)
                                    ? '本组织全部'
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
                          <Space wrap style={{ marginTop: 16 }}>
                            <span>
                              已选 {grants.length} 项
                              {dirty
                                ? ' · 有未保存修改'
                                : detail.draft
                                  ? ' · 已保存工作稿'
                                  : ''}
                            </span>
                            {canEdit && (
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
                            {canGrant && detail.role.status !== 'ARCHIVED' && (
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
                          </Space>
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
                                disabled={!person || busy}
                                onClick={() =>
                                  run(async () =>
                                    setPreview(
                                      await previewRoleAssignment(
                                        person!,
                                        selected!,
                                      ),
                                    ),
                                  )
                                }
                              >
                                预览角色替换
                              </Button>
                            </Space>
                          )}
                          <Table
                            rowKey="id"
                            dataSource={detail.users}
                            columns={[
                              { title: '姓名', dataIndex: 'realName' },
                              { title: '账号', dataIndex: 'username' },
                              {
                                title: '权限来源',
                                render: (_, user) => (
                                  <Button
                                    type="link"
                                    onClick={() =>
                                      run(async () =>
                                        setEffective(
                                          await effectivePermissions(user.id),
                                        ),
                                      )
                                    }
                                  >
                                    查看有效权限
                                  </Button>
                                ),
                              },
                            ]}
                          />
                        </>
                      ),
                    },
                    {
                      key: 'history',
                      label: '发布历史',
                      children: (
                        <Table
                          rowKey="id"
                          dataSource={detail.history}
                          columns={[
                            { title: '发布版本', dataIndex: 'id' },
                            { title: '发布时间', dataIndex: 'publishedAt' },
                            {
                              title: '权限数量',
                              render: (_, r) => JSON.parse(r.grants).length,
                            },
                          ]}
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
        mode="dialog"
        title="最近 100 条治理记录"
        open={!!logs}
        onCancel={() => setLogs(undefined)}
        footer={null}
        width={800}
      >
        <Table
          rowKey="id"
          dataSource={logs}
          scroll={{ x: 560 }}
          columns={[
            { title: '时间', dataIndex: 'createdAt' },
            {
              title: '操作人',
              render: (_, row) =>
                people.find((p) => p.id === row.actorId)?.realName ??
                `账号 #${row.actorId}`,
            },
            {
              title: '动作',
              render: (_, row) => actionLabel[row.action] ?? row.action,
            },
            {
              title: '对象',
              render: (_, row) =>
                ['ASSIGN', 'DELEGATE'].includes(row.action)
                  ? (people.find((p) => p.id === row.targetId)?.realName ??
                    `账号 #${row.targetId}`)
                  : (roles.find((r) => r.id === row.targetId)?.name ??
                    `历史角色 #${row.targetId}`),
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
          {roles.find((r) => r.id === effective?.roleId)?.name ?? '角色不可用'}
        </p>
        <p>
          管理身份：
          {effective?.managementSource === 'OWNER'
            ? '组织所有者'
            : effective?.managementSource === 'DELEGATED'
              ? '有效管理员委派'
              : '无'}
        </p>
        <p>业务权限（来自当前启用角色的发布配置）</p>
        <Space wrap>
          {effective?.businessPermissions.map((code) => (
            <Tag key={code}>
              {catalog.find((p) => p.code === code)?.name ?? code}
            </Tag>
          ))}
        </Space>
        <p style={{ marginTop: 16 }}>管理能力</p>
        <Space wrap>
          {effective?.managementCapabilities.map((code) => (
            <Tag key={code}>{code}</Tag>
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
            <Form.Item label="初始模板">
              <Select
                allowClear
                placeholder="可选：使用系统模板；留空为空白创建"
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
            await applyAuthorizationChange(preview!.id)
            setPreview(undefined)
            await loadList()
            if (selected) await loadDetail(selected)
            refreshUser(await meApi())
            message.success('变更已生效')
          })
        }
      >
        {preview?.action === 'ASSIGN' ? (
          <p>
            将用户 #{preview.targetId} 的主角色从 #{preview.details.oldRoleId}{' '}
            替换为 #{preview.details.roleId}。其余角色的配置保持独立。
          </p>
        ) : (
          <>
            <p>
              动作：{actionLabel[preview?.action ?? '']}
              ；当前角色使用者都会受到影响。预览 5 分钟内有效。
            </p>
            <Table
              rowKey="code"
              size="small"
              pagination={false}
              dataSource={previewRows}
              columns={[
                { title: '权限', dataIndex: 'code' },
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
              disabled={!adminPerson || busy}
              onClick={() =>
                run(async () => {
                  await setAdministrator(
                    adminPerson!,
                    false,
                    [],
                    undefined,
                    adminVersion,
                  )
                  setAdminOpen(false)
                  message.success('管理身份已撤销，原业务角色保留')
                })
              }
            >
              撤销管理身份
            </Button>
            <Button
              type="primary"
              disabled={!adminPerson || busy}
              onClick={() =>
                run(async () => {
                  await setAdministrator(
                    adminPerson!,
                    true,
                    adminGrants,
                    adminExpires ? `${adminExpires}:00` : undefined,
                    adminVersion,
                  )
                  setAdminOpen(false)
                  message.success('管理员身份与可授权清单已保存')
                })
              }
            >
              保存委派
            </Button>
          </Space>
        }
      >
        <p>
          管理身份与主角色分开。该管理员可管理本组织普通角色和账号，能授予的业务操作限定为下方明确选择的清单。
        </p>
        <Form layout="vertical">
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
