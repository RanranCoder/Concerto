import { useEffect, useState } from 'react'
import {
  Alert,
  Button,
  Empty,
  Input,
  Select,
  Space,
  Spin,
  Table,
  Tag,
} from 'antd'
import FormSurface from '@/components/form/FormSurface'
import PermissionDelta from './PermissionDelta'
import {
  auditPage,
  managementSummary,
  migrationReviews,
  type AuditEntry,
  type ManagementSummary,
  type MigrationReview,
  type CatalogPermission,
} from '@/api/permission'

const capabilityNames: Record<string, string> = {
  'role:view': '查看角色',
  'role:manage': '维护角色',
  'role:grant': '发布业务授权',
  'role:assign': '分配角色',
  'user:view': '查看账号',
  'user:manage': '管理普通账号',
  'org:manage': '组织配置',
  'org:structure:manage': '组织架构',
  'org:invite': '邀请成员',
  'org:audit:view': '查看治理记录',
  'member:import_account': '花名册开户',
}
export const managementLabel = (code: string) => capabilityNames[code] ?? code
const actions: Record<string, string> = {
  PUBLISH: '发布权限',
  ENABLE: '启用角色',
  DISABLE: '停用角色',
  ARCHIVE: '归档角色',
  CREATE_ROLE: '创建角色',
  UPDATE_ROLE: '修改信息',
  SAVE_DRAFT: '保存工作稿',
  ASSIGN: '替换主角色',
  DELEGATE: '管理员委派',
  REJECTED: '操作被拒绝',
  DISCARD_DRAFT: '放弃工作稿',
  DELETE_DRAFT: '删除未发布角色',
}

export default function PermissionGovernance({
  mode,
  catalog,
  onDelegate,
  onSelectRole,
}: {
  mode: string
  catalog: CatalogPermission[]
  onDelegate: (id?: number) => void
  onSelectRole: (id: number) => void
}) {
  const [summary, setSummary] = useState<ManagementSummary>()
  const [reviews, setReviews] = useState<MigrationReview[]>([])
  const [logs, setLogs] = useState<AuditEntry[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [keyword, setKeyword] = useState('')
  const [action, setAction] = useState('')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [retry, setRetry] = useState(0)
  const [detail, setDetail] = useState<AuditEntry>()
  const [migrationDetail, setMigrationDetail] = useState<MigrationReview>()
  useEffect(() => {
    let alive = true
    setLoading(true)
    setError('')
    const request =
      mode === 'management'
        ? managementSummary().then((data) => {
            if (alive) setSummary(data)
          })
        : mode === 'migration'
          ? migrationReviews().then((data) => {
              if (alive) setReviews(data)
            })
          : auditPage(page, 20, keyword, action, from, to).then((data) => {
              if (alive) {
                setLogs(data.items)
                setTotal(data.total)
              }
            })
    request
      .catch(() => {
        if (alive) setError('读取失败，请重试。')
      })
      .finally(() => {
        if (alive) setLoading(false)
      })
    return () => {
      alive = false
    }
  }, [mode, page, keyword, action, from, to, retry])
  const name = (code: string) =>
    catalog.find((p) => p.code === code)?.name ?? code
  const jsonDetail = (() => {
    try {
      return JSON.parse(detail?.details ?? '{}') as Record<string, unknown>
    } catch {
      return {}
    }
  })()
  return (
    <section className="permission-governance">
      {error && (
        <Alert
          type="error"
          message={error}
          action={<Button onClick={() => setRetry((v) => v + 1)}>重试</Button>}
        />
      )}
      <Spin spinning={loading}>
        {mode === 'management' ? (
          <>
            <h2>{summary?.isOwner ? '组织所有者与管理员' : '我的管理身份'}</h2>
            <p className="permission-caption">
              管理身份决定你能替谁配置什么；业务角色决定你自己可以访问什么。
            </p>
            {!summary?.isOwner && summary?.ownDelegation && (
              <p>
                委派状态：
                {summary.ownDelegation.active ? '有效' : '已撤销或到期'} · 版本{' '}
                {summary.ownDelegation.version} · 有效期{' '}
                {summary.ownDelegation.expiresAt
                  ? new Date(summary.ownDelegation.expiresAt).toLocaleString(
                      'zh-CN',
                    )
                  : '长期'}{' '}
                · 最近调整 {summary.ownDelegation.updatedByName || '历史记录'}{' '}
                {summary.ownDelegation.updatedAt || ''}
              </p>
            )}
            <div className="permission-management-split">
              <section>
                <h3>当前管理能力</h3>
                <Space wrap>
                  {summary?.capabilities.map((code) => (
                    <Tag key={code}>{managementLabel(code)}</Tag>
                  ))}
                </Space>
              </section>
              <section>
                <h3>可授予的业务操作</h3>
                <p className="permission-caption">
                  下列清单不代表你本人拥有这些业务访问权。
                </p>
                <Space wrap>
                  {summary?.grantable.map((code) => (
                    <Tag key={code}>{name(code)}</Tag>
                  ))}
                </Space>
              </section>
            </div>
            {summary?.isOwner && (
              <>
                <div className="permission-section-heading">
                  <h3>管理员委派</h3>
                  <Button type="primary" onClick={() => onDelegate()}>
                    设置组织管理员
                  </Button>
                </div>
                <Table
                  rowKey="id"
                  dataSource={summary.administrators}
                  pagination={{ pageSize: 10 }}
                  columns={[
                    { title: '姓名', dataIndex: 'realName' },
                    { title: '版本', dataIndex: 'version' },
                    {
                      title: '最近调整',
                      render: (_, row) => (
                        <>
                          {row.updatedByName || '历史记录'}{' '}
                          {row.updatedAt || ''}
                        </>
                      ),
                    },
                    {
                      title: '状态',
                      render: (_, row) =>
                        row.status === 'ACTIVE' &&
                        (!row.expiresAt || new Date(row.expiresAt) > new Date())
                          ? '有效'
                          : '已撤销或到期',
                    },
                    {
                      title: '有效期',
                      render: (_, row) =>
                        row.expiresAt
                          ? new Date(row.expiresAt).toLocaleString('zh-CN')
                          : '长期',
                    },
                    {
                      title: '操作',
                      render: (_, row) => (
                        <Button type="link" onClick={() => onDelegate(row.id)}>
                          查看及调整
                        </Button>
                      ),
                    },
                  ]}
                />
              </>
            )}
          </>
        ) : mode === 'migration' ? (
          <>
            <h2>存量授权迁移核对</h2>
            <Alert
              type="info"
              showIcon
              message={
                reviews.some((r) => r.status === 'PENDING')
                  ? '存在早期升级遗留的待确认考勤工作稿，须由所有者核对后预览发布。'
                  : '此处展示升级时的职责映射和已批准考勤结果。旧发布版本保持可追溯；升级前报告和所有者确认记录由部署流程留存。'
              }
            />
            <Table
              rowKey="role_id"
              dataSource={reviews}
              pagination={{ pageSize: 10 }}
              columns={[
                {
                  title: '角色',
                  render: (_, row) =>
                    row.roleName ?? row.rolename ?? `角色 #${row.role_id}`,
                },
                {
                  title: '核对状态',
                  render: (_, row) => (
                    <Tag
                      color={row.status === 'PENDING' ? 'warning' : undefined}
                    >
                      {row.status === 'PENDING'
                        ? '待核对考勤权限'
                        : row.status === 'APPROVED'
                          ? '所有者已确认切换'
                          : row.status === 'RESOLVED'
                            ? '已核对发布'
                            : '已完成职责映射'}
                    </Tag>
                  ),
                },
                {
                  title: '操作',
                  render: (_, row) => (
                    <Button type="link" onClick={() => setMigrationDetail(row)}>
                      查看迁移差异
                    </Button>
                  ),
                },
              ]}
              locale={{
                emptyText: <Empty description="没有需要迁移的存量角色" />,
              }}
            />
          </>
        ) : (
          <>
            <h2>变更记录</h2>
            <div className="permission-toolbar">
              <label>
                开始日期
                <Input
                  aria-label="记录开始日期"
                  type="date"
                  value={from}
                  onChange={(e) => {
                    setFrom(e.target.value)
                    setPage(1)
                  }}
                />
              </label>
              <label>
                结束日期
                <Input
                  aria-label="记录结束日期"
                  type="date"
                  value={to}
                  min={from}
                  onChange={(e) => {
                    setTo(e.target.value)
                    setPage(1)
                  }}
                />
              </label>
              <Input
                aria-label="搜索变更记录"
                placeholder="搜索操作人或角色名称"
                value={keyword}
                onChange={(e) => {
                  setKeyword(e.target.value)
                  setPage(1)
                }}
                allowClear
              />
              <Select
                aria-label="变更动作"
                value={action}
                onChange={(value) => {
                  setAction(value)
                  setPage(1)
                }}
                options={[
                  { value: '', label: '全部动作' },
                  ...Object.entries(actions).map(([value, label]) => ({
                    value,
                    label,
                  })),
                ]}
              />
            </div>
            <Table
              rowKey="id"
              dataSource={logs}
              pagination={{
                current: page,
                total,
                pageSize: 20,
                onChange: setPage,
                showSizeChanger: false,
              }}
              scroll={{ x: 680 }}
              columns={[
                {
                  title: '时间',
                  render: (_, row) =>
                    new Date(row.createdAt).toLocaleString('zh-CN'),
                },
                {
                  title: '操作人',
                  render: (_, row) =>
                    row.actorName ?? row.actorname ?? `账号 #${row.actorId}`,
                },
                {
                  title: '动作',
                  render: (_, row) => actions[row.action] ?? row.action,
                },
                {
                  title: '对象',
                  render: (_, row) =>
                    row.targetName ??
                    row.targetname ??
                    `历史对象 #${row.targetId}`,
                },
                {
                  title: '详情',
                  render: (_, row) => (
                    <Button type="link" onClick={() => setDetail(row)}>
                      查看变更
                    </Button>
                  ),
                },
              ]}
            />
          </>
        )}
      </Spin>
      <FormSurface
        title="迁移前后权限差异"
        open={!!migrationDetail}
        footer={null}
        onCancel={() => setMigrationDetail(undefined)}
        width={720}
      >
        {migrationDetail && (
          <>
            <PermissionDelta
              before={JSON.parse(migrationDetail.before_json)}
              after={JSON.parse(migrationDetail.after_json)}
              catalog={catalog}
            />
            <Button
              onClick={() => {
                onSelectRole(migrationDetail.role_id)
                setMigrationDetail(undefined)
              }}
            >
              打开当前角色
            </Button>
          </>
        )}
      </FormSurface>
      <FormSurface
        title="变更详情"
        open={!!detail}
        onCancel={() => setDetail(undefined)}
        footer={null}
        width={720}
      >
        {detail && (
          <p>
            结果：{detail.outcome === 'REJECTED' ? '已拒绝' : '已生效'} · 原因：
            {detail.reason || '未填写'}
            {detail.changeId ? ' · 变更编号 ' + detail.changeId : ''}
          </p>
        )}
        <p>{actions[detail?.action ?? ''] ?? detail?.action}</p>
        {Array.isArray(jsonDetail.before) ||
        Array.isArray(jsonDetail.grants) ? (
          <div className="permission-management-split">
            <section>
              <h3>原配置</h3>
              {((jsonDetail.before as { permission: string }[]) ?? []).map(
                (g) => (
                  <p key={g.permission}>{name(g.permission)}</p>
                ),
              )}
            </section>
            <section>
              <h3>变更后</h3>
              {((jsonDetail.grants as { permission: string }[]) ?? []).map(
                (g) => (
                  <p key={g.permission}>{name(g.permission)}</p>
                ),
              )}
            </section>
          </div>
        ) : (
          <pre className="permission-json">
            {JSON.stringify(
              jsonDetail,
              (key, value) =>
                key === 'permission' && typeof value === 'string'
                  ? name(value)
                  : value,
              2,
            )}
          </pre>
        )}
      </FormSurface>
    </section>
  )
}
