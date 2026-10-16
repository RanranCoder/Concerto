import FormSurface from '@/components/form/FormSurface'
import { useEffect, useMemo, useState } from 'react'
import {
  App,
  Button,
  DatePicker,
  Empty,
  Form,
  Input,
  Select,
  Space,
  Spin,
  Table,
  Tabs,
} from 'antd'
import { DownloadOutlined, SearchOutlined } from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import { useSearchParams } from 'react-router-dom'
import dayjs from 'dayjs'
import PageHeader from '@/components/glass/PageHeader'
import GlassCard from '@/components/glass/GlassCard'
import RoutineLayout, {
  RoutineNote,
  RoutineStatus,
  RoutineSummary,
} from '@/components/routine/RoutineLayout'
import {
  ATTENDANCE_MANUAL_OPTIONS,
  ATTENDANCE_STATUS_MAP,
  ATTENDANCE_STATUS_OPTIONS,
  listAttendances,
  listMyAttendances,
  listAttendanceAudits,
  updateAttendanceStatus,
  makeupAttendance,
  type AttendanceVO,
  type AttendanceAuditVO,
  type AttendanceFilter,
} from '@/api/attendance'
import { listActivities, type ActivityVO } from '@/api/activity'
import { ACTIVITY_TYPE_OPTIONS } from '@/api/activityStatus'
import { useAuthStore } from '@/stores/auth'
import { downloadCsv } from '@/utils/routine'

function matchFilters(r: AttendanceVO, f: AttendanceFilter) {
  return (
    (!f.activityId || r.activityId === f.activityId) &&
    (!f.date || r.activityDate === f.date) &&
    (!f.dateFrom || (!!r.activityDate && r.activityDate >= f.dateFrom)) &&
    (!f.dateTo || (!!r.activityDate && r.activityDate <= f.dateTo)) &&
    (!f.type || r.activityType === f.type) &&
    (!f.status || r.status === f.status) &&
    (!f.keyword || `${r.personName} ${r.studentNo ?? ''}`.includes(f.keyword))
  )
}
export default function AttendanceList() {
  const { message } = App.useApp(),
    permissions = useAuthStore((s) => s.user?.permissions) ?? []
  const canViewAll = permissions.some((p) =>
      ['attendance:view', 'schedule:check'].includes(p),
    ),
    canManage = permissions.some((p) =>
      ['attendance:manage', 'checkin:manage', 'schedule:check'].includes(p),
    ),
    canAudit = permissions.some((p) =>
      ['attendance:view', 'attendance:manage', 'schedule:check'].includes(p),
    )
  const [params, setParams] = useSearchParams()
  const scope = !canViewAll || params.get('scope') === 'mine' ? 'mine' : 'all'
  const initialActivity = Number(params.get('activityId')) || undefined
  const [filters, setFilters] = useState<AttendanceFilter>({
      activityId: initialActivity,
    }),
    [advanced, setAdvanced] = useState(false)
  const [rows, setRows] = useState<AttendanceVO[]>([]),
    [loading, setLoading] = useState(false),
    [revision, setRevision] = useState(0),
    [activities, setActivities] = useState<ActivityVO[]>([])
  const [detail, setDetail] = useState<AttendanceVO | null>(null),
    [mode, setMode] = useState<'detail' | 'edit' | 'makeup'>('detail'),
    [audits, setAudits] = useState<AttendanceAuditVO[]>([]),
    [auditLoading, setAuditLoading] = useState(false),
    [saving, setSaving] = useState(false)
  const [form] = Form.useForm<{ newStatus: string; remark: string }>()
  useEffect(() => {
    listActivities({ page: 1, size: 200 })
      .then((r) => setActivities(r.records ?? []))
      .catch(() => {})
  }, [])
  useEffect(() => {
    setFilters((f) => ({ ...f, activityId: initialActivity }))
  }, [initialActivity])
  useEffect(() => {
    let current = true
    setLoading(true)
    const request =
      scope === 'mine' ? listMyAttendances() : listAttendances(filters)
    request
      .then((r) => {
        if (current) setRows(r ?? [])
      })
      .catch(() => {
        if (current) setRows([])
      })
      .finally(() => {
        if (current) setLoading(false)
      })
    return () => {
      current = false
    }
  }, [filters, scope, revision])
  useEffect(() => {
    let current = true
    setAudits([])
    if (!detail || !canAudit) return
    setAuditLoading(true)
    listAttendanceAudits(detail.id)
      .then((r) => {
        if (current) setAudits(r ?? [])
      })
      .catch(() => {})
      .finally(() => {
        if (current) setAuditLoading(false)
      })
    return () => {
      current = false
    }
  }, [detail?.id, canAudit, revision])
  const visible = useMemo(
    () => rows.filter((r) => matchFilters(r, filters)),
    [rows, filters],
  )
  const count = (s: string) => visible.filter((r) => r.status === s).length
  const signed = visible.filter((r) =>
    ['NORMAL', 'LATE', 'MAKEUP'].includes(r.status),
  ).length
  const change = (patch: Partial<AttendanceFilter>) =>
    setFilters((f) => ({ ...f, ...patch }))
  const open = (r: AttendanceVO, next: typeof mode) => {
    setDetail(r)
    setMode(next)
  }
  const update = async (v: { newStatus: string; remark: string }) => {
    if (!detail || !canManage) return
    const remark = v.remark.trim()
    if (!remark) {
      form.setFields([{ name: 'remark', errors: ['请填写修改原因'] }])
      return
    }
    if (mode === 'edit' && v.newStatus === detail.status) {
      message.warning('请选择不同于当前结果的考勤状态')
      return
    }
    setSaving(true)
    try {
      if (mode === 'makeup' || v.newStatus === 'MAKEUP')
        await makeupAttendance(detail.id, remark)
      else
        await updateAttendanceStatus(detail.id, {
          newStatus: v.newStatus,
          remark,
        })
      message.success(
        mode === 'makeup' || v.newStatus === 'MAKEUP'
          ? '已补签并记录审计'
          : '考勤已修正并记录审计',
      )
      setDetail(null)
      setRevision((n) => n + 1)
    } catch {
    } finally {
      setSaving(false)
    }
  }
  const columns: ColumnsType<AttendanceVO> = [
    {
      title: '成员 / 学号',
      key: 'person',
      width: 150,
      render: (_, r) => (
        <>
          <strong>{r.personName}</strong>
          <small>{r.studentNo || '未登记学号'}</small>
        </>
      ),
    },
    {
      title: '活动 / 班次',
      key: 'activity',
      render: (_, r) => (
        <>
          <strong>{r.activityName || '例行事务'}</strong>
          <small>
            {r.activityDate || '未设置日期'}　
            {r.activityStart
              ? `${r.activityStart.slice(0, 5)}–${r.activityEnd?.slice(0, 5) ?? '待定'}`
              : r.sessionName || '未设置时段'}
          </small>
        </>
      ),
    },
    {
      title: '考勤结果',
      dataIndex: 'status',
      width: 100,
      render: (s) => <RoutineStatus status={s} />,
    },
    {
      title: '签到时间',
      key: 'time',
      width: 130,
      render: (_, r) => (
        <>
          {r.checkedInAt ? dayjs(r.checkedInAt).format('HH:mm') : '—'}
          <small>{r.signCount} 次签到</small>
        </>
      ),
    },
    {
      title: '来源',
      dataIndex: 'statusSource',
      width: 100,
      render: (s) => <small>{s === 'MANUAL' ? '人工修正' : '自动判定'}</small>,
    },
    {
      title: '操作',
      key: 'actions',
      width: 130,
      render: (_, r) => (
        <Space>
          <Button type="link" onClick={() => open(r, 'detail')}>
            详情
          </Button>
          {scope === 'all' && canManage && r.status !== 'CANCELED' && (
            <Button type="link" onClick={() => open(r, 'edit')}>
              修正
            </Button>
          )}
        </Space>
      ),
    },
  ]
  const table = (
    <GlassCard>
      <div className="routine-toolbar">
        <label className="routine-filter wide">
          活动
          <Select
            aria-label="考勤活动"
            placeholder="全部活动"
            allowClear
            showSearch
            optionFilterProp="label"
            value={filters.activityId}
            onChange={(v) => {
              change({ activityId: v })
              const p = new URLSearchParams(params)
              if (v) p.set('activityId', String(v))
              else p.delete('activityId')
              setParams(p, { replace: true })
            }}
            options={activities.map((a) => ({ value: a.id, label: a.name }))}
          />
        </label>
        <label className="routine-filter">
          班次日期
          <DatePicker
            aria-label="考勤日期"
            value={filters.date ? dayjs(filters.date) : null}
            onChange={(d) =>
              change({
                date: d?.format('YYYY-MM-DD'),
                dateFrom: undefined,
                dateTo: undefined,
              })
            }
          />
        </label>
        <label className="routine-filter wide">
          姓名 / 学号
          <Input
            aria-label="考勤人员查询"
            prefix={<SearchOutlined />}
            allowClear
            value={filters.keyword ?? ''}
            onChange={(e) => change({ keyword: e.target.value || undefined })}
            placeholder="输入姓名或学号"
          />
        </label>
        <label className="routine-filter">
          考勤状态
          <Select
            aria-label="考勤状态"
            allowClear
            placeholder="全部状态"
            options={ATTENDANCE_STATUS_OPTIONS}
            value={filters.status}
            onChange={(status) => change({ status })}
          />
        </label>
        <Button
          type="link"
          onClick={() => {
            setFilters({})
            const p = new URLSearchParams(params)
            p.delete('activityId')
            setParams(p, { replace: true })
          }}
        >
          重置
        </Button>
        <Button type="link" onClick={() => setAdvanced((v) => !v)}>
          {advanced ? '收起筛选' : '更多筛选'}
        </Button>
        {advanced && (
          <div className="routine-advanced">
            <label className="routine-filter">
              日期范围
              <DatePicker.RangePicker
                aria-label="考勤日期范围"
                value={
                  filters.dateFrom && filters.dateTo
                    ? [dayjs(filters.dateFrom), dayjs(filters.dateTo)]
                    : null
                }
                onChange={(d) =>
                  change({
                    date: undefined,
                    dateFrom: d?.[0]?.format('YYYY-MM-DD'),
                    dateTo: d?.[1]?.format('YYYY-MM-DD'),
                  })
                }
              />
            </label>
            <label className="routine-filter">
              活动类型
              <Select
                aria-label="考勤活动类型"
                allowClear
                placeholder="全部类型"
                options={ACTIVITY_TYPE_OPTIONS}
                value={filters.type}
                onChange={(type) => change({ type })}
              />
            </label>
          </div>
        )}
      </div>
      <div className="routine-caption">
        <span>
          {[
            'NORMAL',
            'LATE',
            'ABSENT',
            'LEAVE',
            'MAKEUP',
            'EARLY_LEAVE',
            'CANCELED',
          ]
            .map((s) => `${ATTENDANCE_STATUS_MAP[s]} ${count(s)}`)
            .join('　')}
        </span>
        <span>摘要随筛选更新</span>
      </div>
      <Table
        className="routine-data-table"
        rowKey="id"
        columns={columns}
        dataSource={visible}
        loading={loading}
        scroll={{ x: 950 }}
        pagination={{ pageSize: 15, showTotal: (n) => `共 ${n} 条考勤记录` }}
        locale={{
          emptyText: <Empty description="没有匹配的考勤记录，请调整筛选" />,
        }}
      />
    </GlassCard>
  )
  return (
    <RoutineLayout>
      <PageHeader
        title={scope === 'mine' ? '我的考勤' : '考勤中心'}
        description="查看排班对应的签到结果，人工修正保留原因和完整操作记录。"
        extra={
          <Button
            icon={<DownloadOutlined />}
            disabled={!visible.length}
            onClick={() =>
              downloadCsv('考勤明细', [
                [
                  '姓名',
                  '学号',
                  '活动',
                  '日期',
                  '状态',
                  '签到时间',
                  '来源',
                  '备注',
                ],
                ...visible.map((r) => [
                  r.personName,
                  r.studentNo,
                  r.activityName,
                  r.activityDate,
                  ATTENDANCE_STATUS_MAP[r.status] || r.status,
                  r.checkedInAt,
                  r.statusSource,
                  r.remark,
                ]),
              ])
            }
          >
            导出明细
          </Button>
        }
      />
      <RoutineSummary
        items={[
          { label: '当前记录', value: visible.length, note: '筛选范围' },
          { label: '已签到', value: signed, note: '正常 / 迟到 / 补签' },
          { label: '待签到', value: count('PENDING'), note: '活动结束前' },
          {
            label: '缺勤 / 迟到',
            value: count('ABSENT') + count('LATE'),
            note: '需要核实',
            attention: true,
          },
        ]}
      />
      <Tabs
        className="routine-tabs"
        activeKey={scope}
        onChange={(key) => {
          const p = new URLSearchParams(params)
          p.set('scope', key)
          setParams(p, { replace: true })
          setDetail(null)
        }}
        items={[
          ...(canViewAll
            ? [{ key: 'all', label: '考勤管理', children: table }]
            : []),
          { key: 'mine', label: '我的考勤', children: table },
        ]}
      />
      <RoutineNote>
        活动排班发布后进入待签到；活动结束后，未签到自动判为缺勤。人工修正的记录不会被自动任务覆盖。
      </RoutineNote>
      <FormSurface
        mode="drawer"
        title={
          mode === 'detail'
            ? '考勤详情'
            : mode === 'makeup'
              ? '人工补签'
              : '人工修正考勤'
        }
        className="routine-drawer"
        open={!!detail}
        width="min(540px, 100vw)"
        onCancel={() => setDetail(null)}
        destroyOnHidden
        footer={
          <Space>
            <Button onClick={() => setDetail(null)}>关闭</Button>
            {mode !== 'detail' && (
              <Button
                type="primary"
                loading={saving}
                onClick={() => form.submit()}
              >
                {mode === 'makeup' ? '确认补签' : '保存修正'}
              </Button>
            )}
          </Space>
        }
      >
        {detail && (
          <>
            <div className="routine-detail-heading">
              <h3>
                {detail.personName}　<RoutineStatus status={detail.status} />
              </h3>
              <p>
                学号 {detail.studentNo || '未登记'} /{' '}
                {detail.statusSource === 'MANUAL' ? '人工修正' : '自动判定'}
              </p>
            </div>
            <dl className="routine-detail-kv">
              <dt>关联活动</dt>
              <dd>{detail.activityName || '例行事务'}</dd>
              <dt>班次时间</dt>
              <dd>
                {detail.activityDate || '未设置日期'}{' '}
                {detail.activityStart?.slice(0, 5)}–
                {detail.activityEnd?.slice(0, 5)}
              </dd>
              <dt>签到信息</dt>
              <dd>
                {detail.checkedInAt
                  ? dayjs(detail.checkedInAt).format('YYYY-MM-DD HH:mm')
                  : '未签到'}{' '}
                / {detail.signCount} 次记录
              </dd>
              <dt>备注</dt>
              <dd>{detail.remark || '无'}</dd>
            </dl>
            {mode !== 'detail' ? (
              <Form
                form={form}
                layout="vertical"
                key={`${detail.id}-${mode}`}
                initialValues={{
                  newStatus:
                    detail.status === 'PENDING' ? undefined : detail.status,
                  remark: '',
                }}
                onFinish={update}
              >
                {mode === 'edit' && (
                  <Form.Item
                    name="newStatus"
                    label="修正后状态"
                    rules={[{ required: true, message: '请选择考勤状态' }]}
                  >
                    <Select
                      aria-label="修正后状态"
                      options={ATTENDANCE_MANUAL_OPTIONS}
                    />
                  </Form.Item>
                )}
                <Form.Item
                  name="remark"
                  label={mode === 'makeup' ? '补签原因' : '修改原因'}
                  rules={[
                    { required: true, whitespace: true, message: '请填写原因' },
                  ]}
                >
                  <Input.TextArea
                    aria-label={mode === 'makeup' ? '补签原因' : '修改原因'}
                    rows={3}
                    maxLength={255}
                    placeholder="填写已核实的原因，如因病请假、现场签到漏记"
                  />
                </Form.Item>
                <RoutineNote>
                  保存后记录操作人、时间、原状态和新状态；自动任务不再覆盖此记录。
                </RoutineNote>
              </Form>
            ) : (
              scope === 'all' &&
              canManage &&
              detail.status !== 'CANCELED' && (
                <Space>
                  <Button type="primary" onClick={() => setMode('edit')}>
                    人工修正
                  </Button>
                  {detail.status !== 'MAKEUP' && (
                    <Button onClick={() => setMode('makeup')}>补签</Button>
                  )}
                </Space>
              )
            )}
            {mode === 'detail' && canAudit && (
              <section className="routine-audit">
                <h3>人工修改记录</h3>
                <Spin spinning={auditLoading}>
                  {audits.length ? (
                    audits
                      .slice()
                      .sort((a, b) =>
                        (b.operateTime ?? '').localeCompare(
                          a.operateTime ?? '',
                        ),
                      )
                      .map((a) => (
                        <div key={a.id} className="routine-audit-row">
                          <RoutineStatus status={a.oldStatus} /> →{' '}
                          <RoutineStatus status={a.newStatus} />
                          <p>{a.remark || '未填写备注'}</p>
                          <small>
                            {a.operatorName || '未知操作人'} /{' '}
                            {a.operateTime
                              ? dayjs(a.operateTime).format('YYYY-MM-DD HH:mm')
                              : '时间未记录'}
                          </small>
                        </div>
                      ))
                  ) : (
                    <p className="routine-muted">暂无人工修改记录。</p>
                  )}
                </Spin>
              </section>
            )}
          </>
        )}
      </FormSurface>
    </RoutineLayout>
  )
}
