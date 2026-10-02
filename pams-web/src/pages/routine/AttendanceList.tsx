import { useCallback, useEffect, useMemo, useState } from 'react'
import { App, Button, DatePicker, Input, Modal, Select, Space, Table, Tag } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { AuditOutlined, CheckOutlined, SearchOutlined } from '@ant-design/icons'
import { useSearchParams } from 'react-router-dom'
import dayjs from 'dayjs'
import GlassCard from '@/components/glass/GlassCard'
import GlassTable from '@/components/glass/GlassTable'
import PageHeader from '@/components/glass/PageHeader'
import {
  ATTENDANCE_MANUAL_OPTIONS,
  ATTENDANCE_STATUS_MAP,
  ATTENDANCE_STATUS_OPTIONS,
  getAttendanceStats,
  listAttendanceAudits,
  listAttendances,
  makeupAttendance,
  updateAttendanceStatus,
  type AttendanceAuditVO,
  type AttendanceFilter,
  type AttendanceStats,
  type AttendanceVO,
} from '@/api/attendance'
import { listActivities, type ActivityVO } from '@/api/activity'
import { useAuthStore } from '@/stores/auth'
import { Form, Input as AInput } from 'antd'

const STATUS_COLOR: Record<string, string> = {
  PENDING: 'default',
  NORMAL: 'green',
  LATE: 'orange',
  ABSENT: 'red',
  LEAVE: 'blue',
  EARLY_LEAVE: 'magenta',
  MAKEUP: 'purple',
  CANCELED: 'default',
}

interface Row extends AttendanceVO {
  key: number
}

function StatCard({ label, value, color }: { label: string; value: number; color?: string }) {
  return (
    <div
      style={{
        flex: 1,
        minWidth: 100,
        background: 'var(--color-bg-card, #fff)',
        border: '1px solid var(--color-border)',
        borderRadius: 12,
        padding: '12px 16px',
        textAlign: 'center',
      }}
    >
      <div style={{ fontSize: 22, fontWeight: 700, color: color ?? 'var(--color-text)' }}>{value}</div>
      <div style={{ fontSize: 12, color: 'var(--color-text-secondary)' }}>{label}</div>
    </div>
  )
}

const defaultStats: AttendanceStats = {
  totalActivities: 0,
  shouldAttend: 0,
  signed: 0,
  normal: 0,
  late: 0,
  absent: 0,
  leave: 0,
  makeup: 0,
  pending: 0,
}

export default function AttendanceList() {
  const { message } = App.useApp()
  const canManage = (useAuthStore((s) => s.user?.permissions) ?? []).some(
    (p) => p === 'attendance:manage' || p === 'checkin:manage' || p === 'schedule:check',
  )

  const [filters, setFilters] = useState<AttendanceFilter>({})
  const [rows, setRows] = useState<Row[]>([])
  const [stats, setStats] = useState<AttendanceStats>(defaultStats)
  const [loading, setLoading] = useState(false)
  const [activities, setActivities] = useState<ActivityVO[]>([])
  // 联动：从排班列表/活动详情带 ?activityId= 进入时预选该活动
  const [searchParams, setSearchParams] = useSearchParams()
  const urlActivityId = (() => {
    const v = searchParams.get('activityId')
    return v && /^\d+$/.test(v) ? Number(v) : undefined
  })()

  // 编辑状态
  const [editTarget, setEditTarget] = useState<Row | null>(null)
  const [editForm] = Form.useForm<{ newStatus: string; remark?: string }>()
  const [audits, setAudits] = useState<AttendanceAuditVO[]>([])
  const [auditOpen, setAuditOpen] = useState(false)

  useEffect(() => {
    listActivities({ page: 1, size: 200 }).then((r) => setActivities(r?.records ?? [])).catch(() => {})
  }, [])

  const refresh = useCallback(async (f: AttendanceFilter) => {
    setLoading(true)
    try {
      const [list, st] = await Promise.all([listAttendances(f), getAttendanceStats(f)])
      setRows((list ?? []).map((x) => ({ ...x, key: x.id })))
      setStats(st ?? defaultStats)
    } catch {
      /* http 拦截已提示 */
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    refresh(filters)
  }, [filters, refresh])

  // 联动：接收 ?activityId= 一次性预选活动后清除参数，避免刷新残留
  useEffect(() => {
    if (!urlActivityId) return
    setFilters((f) => ({ ...f, activityId: urlActivityId }))
    const next = new URLSearchParams(searchParams)
    next.delete('activityId')
    setSearchParams(next, { replace: true })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const openEdit = (r: Row) => {
    setEditTarget(r)
    editForm.setFieldsValue({ newStatus: r.status === 'PENDING' ? undefined : r.status, remark: r.remark ?? undefined })
  }

  const handleSaveStatus = async () => {
    const v = await editForm.validateFields()
    if (!editTarget) return
    try {
      await updateAttendanceStatus(editTarget.id, { newStatus: v.newStatus, remark: v.remark })
      message.success('状态已更新（审计已记录）')
      setEditTarget(null)
      refresh(filters)
    } catch {
      /* http 拦截已提示 */
    }
  }

  const handleMakeup = async (r: Row) => {
    try {
      await makeupAttendance(r.id)
      message.success('已补签')
      refresh(filters)
    } catch {
      /* http 拦截已提示 */
    }
  }

  const openAudit = async (r: Row) => {
    try {
      setAudits(await listAttendanceAudits(r.id))
      setAuditOpen(true)
    } catch {
      /* http 拦截已提示 */
    }
  }

  const columns = useMemo<ColumnsType<Row>>(
    () => [
      { title: '姓名', dataIndex: 'personName', key: 'personName', width: 110 },
      { title: '学号', dataIndex: 'studentNo', key: 'studentNo', width: 120, render: (v) => v || '-' },
      { title: '活动名称', dataIndex: 'activityName', key: 'activityName', ellipsis: true },
      { title: '活动日期', dataIndex: 'activityDate', key: 'activityDate', width: 110, render: (v) => v || '-' },
      {
        title: '活动时间',
        key: 'timeRange',
        width: 130,
        render: (_, r) =>
          r.activityStart ? `${(r.activityStart ?? '').slice(0, 5)}-${(r.activityEnd ?? '').slice(0, 5)}` : '-',
      },
      {
        title: '签到时间',
        dataIndex: 'checkedInAt',
        key: 'checkedInAt',
        width: 150,
        render: (v) => (v ? dayjs(v).format('YYYY-MM-DD HH:mm') : '-'),
      },
      {
        title: '考勤状态',
        dataIndex: 'status',
        key: 'status',
        width: 100,
        fixed: 'right',
        render: (v: string, r) => (
          <Space size={4}>
            <Tag color={STATUS_COLOR[v] ?? 'default'}>{ATTENDANCE_STATUS_MAP[v] ?? v}</Tag>
            {r.statusSource === 'MANUAL' && <Tag color="geekblue">人工</Tag>}
          </Space>
        ),
      },
      { title: '签到次数', dataIndex: 'signCount', key: 'signCount', width: 90, align: 'center' as const },
      { title: '备注', dataIndex: 'remark', key: 'remark', ellipsis: true, render: (v) => v || '-' },
      {
        title: '操作',
        key: 'action',
        width: 190,
        fixed: 'right',
        render: (_, r) => (
          <Space size={4}>
            <Button size="small" icon={<AuditOutlined />} onClick={() => openAudit(r)}>
              审计
            </Button>
            {canManage && (
              <>
                <Button size="small" icon={<CheckOutlined />} onClick={() => openEdit(r)}>
                  改状态
                </Button>
                {r.status !== 'MAKEUP' && (
                  <Button size="small" onClick={() => handleMakeup(r)}>
                    补签
                  </Button>
                )}
              </>
            )}
          </Space>
        ),
      },
    ],
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [canManage],
  )

  return (
    <div>
      <PageHeader title="活动考勤中心" description="自动判定的排班考勤 + 人工调整（含审计）" />

      <GlassCard style={{ padding: 16, marginBottom: 16 }}>
        <Space wrap style={{ display: 'flex' }}>
          <StatCard label="今日活动" value={stats.totalActivities} color="var(--color-red)" />
          <StatCard label="应签到" value={stats.shouldAttend} />
          <StatCard label="已签到" value={stats.signed} color="green" />
          <StatCard label="正常" value={stats.normal} color="green" />
          <StatCard label="迟到" value={stats.late} color="orange" />
          <StatCard label="缺勤" value={stats.absent} color="red" />
          <StatCard label="请假" value={stats.leave} color="blue" />
        </Space>
      </GlassCard>

      <GlassCard style={{ padding: 16, marginBottom: 16 }}>
        <Space wrap>
          <Select
            placeholder="活动"
            allowClear
            showSearch
            optionFilterProp="label"
            style={{ width: 180 }}
            options={activities.map((a) => ({ value: a.id, label: a.name }))}
            onChange={(v) => setFilters((f) => ({ ...f, activityId: v }))}
          />
          <DatePicker
            placeholder="活动日期"
            style={{ width: 140 }}
            onChange={(d) => setFilters((f) => ({ ...f, date: d ? d.format('YYYY-MM-DD') : undefined }))}
          />
          <DatePicker.RangePicker
            placeholder={['开始日期', '结束日期']}
            onChange={(ds) =>
              setFilters((f) => ({
                ...f,
                dateFrom: ds?.[0] ? ds[0].format('YYYY-MM-DD') : undefined,
                dateTo: ds?.[1] ? ds[1].format('YYYY-MM-DD') : undefined,
              }))
            }
          />
          <Input
            placeholder="姓名 / 学号"
            allowClear
            style={{ width: 160 }}
            prefix={<SearchOutlined />}
            onChange={(e) => setFilters((f) => ({ ...f, keyword: e.target.value || undefined }))}
          />
          <Select
            placeholder="考勤状态"
            allowClear
            style={{ width: 140 }}
            options={ATTENDANCE_STATUS_OPTIONS}
            onChange={(v) => setFilters((f) => ({ ...f, status: v }))}
          />
        </Space>
      </GlassCard>

      <GlassTable<Row>
        columns={columns}
        dataSource={rows}
        rowKey="id"
        loading={loading}
        scroll={{ x: 1100 }}
        pagination={{ pageSize: 20, showTotal: (t) => `共 ${t} 条` }}
        locale={{ emptyText: '暂无考勤数据' }}
      />

      {/* 修改考勤状态 */}
      <Modal
        title="修改考勤状态"
        open={!!editTarget}
        onCancel={() => setEditTarget(null)}
        onOk={handleSaveStatus}
        okText="确认修改"
      >
        <Form form={editForm} layout="vertical">
          <Form.Item name="newStatus" label="新状态" rules={[{ required: true, message: '请选择状态' }]}>
            <Select options={ATTENDANCE_MANUAL_OPTIONS} placeholder="选择考勤状态" />
          </Form.Item>
          <Form.Item name="remark" label="修改原因 / 备注">
            <AInput.TextArea maxLength={255} rows={3} placeholder="如：因病请假 / 现场签到补录" />
          </Form.Item>
        </Form>
      </Modal>

      {/* 审计记录 */}
      <Modal
        title="考勤修改审计"
        open={auditOpen}
        onCancel={() => setAuditOpen(false)}
        footer={null}
        width={560}
      >
        {audits.length === 0 ? (
          <div style={{ textAlign: 'center', color: 'var(--color-text-secondary)', padding: 24 }}>暂无人工修改记录</div>
        ) : (
          <Table<AttendanceAuditVO>
            size="small"
            rowKey="id"
            pagination={false}
            dataSource={audits}
            columns={[
              {
                title: '变更',
                key: 'change',
                render: (_, a) => (
                  <Space size={4}>
                    <Tag>{a.oldStatus ? ATTENDANCE_STATUS_MAP[a.oldStatus] ?? a.oldStatus : '-'}</Tag>
                    →
                    <Tag color={STATUS_COLOR[a.newStatus ?? ''] ?? 'default'}>
                      {a.newStatus ? ATTENDANCE_STATUS_MAP[a.newStatus] ?? a.newStatus : '-'}
                    </Tag>
                  </Space>
                ),
              },
              { title: '操作人', dataIndex: 'operatorName', key: 'operatorName', width: 100 },
              {
                title: '时间',
                dataIndex: 'operateTime',
                key: 'operateTime',
                width: 150,
                render: (v) => (v ? dayjs(v).format('YYYY-MM-DD HH:mm') : '-'),
              },
              { title: '备注', dataIndex: 'remark', key: 'remark' },
            ]}
          />
        )}
      </Modal>
    </div>
  )
}