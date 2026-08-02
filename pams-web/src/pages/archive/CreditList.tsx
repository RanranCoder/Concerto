import { useCallback, useEffect, useMemo, useState } from 'react'
import { Button, Form, Input, InputNumber, message, Popconfirm, Select, Space, Statistic } from 'antd'
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'
import GlassCard from '@/components/glass/GlassCard'
import GlassModal from '@/components/glass/GlassModal'
import GlassTable from '@/components/glass/GlassTable'
import PageHeader from '@/components/glass/PageHeader'
import {
  createCredit,
  deleteCredit,
  listCredits,
  CREDIT_BASIS_OPTIONS,
  type CreditVO,
} from '@/api/credit'
import { listActivities, type ActivityVO } from '@/api/activity'

interface CreditFormValues {
  personName: string
  studentNo?: string
  activityId?: number
  project: string
  credit: number
  basis?: string
  remark?: string
}

export default function CreditList() {
  const [data, setData] = useState<CreditVO[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(false)
  const [keyword, setKeyword] = useState('')
  const [page, setPage] = useState(1)
  const [size, setSize] = useState(10)
  const [activities, setActivities] = useState<ActivityVO[]>([])
  const [addOpen, setAddOpen] = useState(false)
  const [saving, setSaving] = useState(false)
  const [form] = Form.useForm<CreditFormValues>()
  // 顶部总分值汇总：一次性拉当前搜索条件下全部记录求和（忽略分页，素拓记录量级小）
  const [summaryRecords, setSummaryRecords] = useState<CreditVO[]>([])
  const [summaryCount, setSummaryCount] = useState(0)

  const fetchList = useCallback(() => {
    setLoading(true)
    listCredits({ keyword: keyword || undefined, page, size })
      .then((res) => {
        setData((res.records ?? []).map((r) => ({ ...r, key: r.id })))
        setTotal(res.total)
      })
      .catch(() => {
        /* http 拦截已提示 */
      })
      .finally(() => setLoading(false))
  }, [keyword, page, size])

  useEffect(() => {
    fetchList()
  }, [fetchList])

  // 关联活动名映射（activityId → name）
  useEffect(() => {
    listActivities({ size: 100 })
      .then((res) => setActivities(res.records ?? []))
      .catch(() => {
        /* 无权限时留空，仅显示 #id */
      })
  }, [])

  // 顶部汇总：总加分人次（当前筛选条件下的总数，忽略分页）/ 总分值
  const refreshSummary = useCallback(() => {
    listCredits({ keyword: keyword || undefined, size: 1000 })
      .then((res) => {
        setSummaryRecords(res.records ?? [])
        setSummaryCount(res.total)
      })
      .catch(() => {
        /* http 拦截已提示 */
      })
  }, [keyword])

  useEffect(() => {
    refreshSummary()
  }, [refreshSummary])

  const summaryCreditTotal = useMemo(
    () => summaryRecords.reduce((s, c) => s + (c.credit ?? 0), 0),
    [summaryRecords],
  )

  const activityNameOf = (id: number | null): string => {
    if (id == null) return '-'
    return activities.find((a) => a.id === id)?.name ?? `#${id}`
  }

  const openAdd = () => {
    form.resetFields()
    setAddOpen(true)
  }

  const handleSave = async () => {
    const values = await form.validateFields()
    setSaving(true)
    try {
      await createCredit({
        personName: values.personName.trim(),
        studentNo: values.studentNo?.trim() || null,
        activityId: values.activityId ?? null,
        project: values.project.trim(),
        credit: values.credit,
        basis: values.basis || null,
        remark: values.remark?.trim() || null,
      })
      message.success('加分记录已添加')
      setAddOpen(false)
      setPage(1)
      fetchList()
      refreshSummary()
    } catch {
      /* 校验失败或 http 拦截已提示 */
    } finally {
      setSaving(false)
    }
  }

  const handleDelete = async (id: number) => {
    try {
      await deleteCredit(id)
      message.success('已删除')
      fetchList()
      refreshSummary()
    } catch {
      /* http 拦截已提示 */
    }
  }

  const columns = [
    { title: '姓名', dataIndex: 'personName', key: 'personName', width: 110 },
    { title: '学号', dataIndex: 'studentNo', key: 'studentNo', width: 130, render: (v: string) => v || '-' },
    { title: '项目', dataIndex: 'project', key: 'project', ellipsis: true },
    {
      title: '分值',
      dataIndex: 'credit',
      key: 'credit',
      width: 90,
      align: 'center' as const,
      render: (v: number) => v.toFixed(2),
    },
    {
      title: '依据',
      dataIndex: 'basis',
      key: 'basis',
      width: 90,
      render: (v: string) => (v === 'PARTICIPATE' ? '参与' : v === 'ANSWER' ? '答题' : v || '-'),
    },
    {
      title: '关联活动',
      key: 'activity',
      width: 150,
      ellipsis: true,
      render: (_: unknown, r: CreditVO) => activityNameOf(r.activityId),
    },
    {
      title: '时间',
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 170,
      render: (t: string) => (t ? dayjs(t).format('YYYY-MM-DD HH:mm') : '-'),
    },
    {
      title: '操作',
      key: 'action',
      width: 90,
      render: (_: unknown, r: CreditVO) => (
        <Popconfirm title="确认删除该加分记录？" onConfirm={() => handleDelete(r.id)} okText="删除" cancelText="取消">
          <Button type="link" size="small" danger icon={<DeleteOutlined />}>
            删除
          </Button>
        </Popconfirm>
      ),
    },
  ]

  return (
    <div>
      <PageHeader
        title="素拓加分"
        description="活动素拓加分台账：记录参与 / 答题等加分，汇总人次与总分值"
        extra={
          <Button type="primary" icon={<PlusOutlined />} onClick={openAdd}>
            新增加分
          </Button>
        }
      />

      <GlassCard style={{ padding: 16, marginBottom: 16 }}>
        <Space size="large" wrap>
          <Statistic title="总加分人次" value={summaryCount} suffix="人次" />
          <Statistic title="总分值" value={summaryCreditTotal} precision={2} />
          <span style={{ color: 'var(--color-text-secondary)', fontSize: 12 }}>
            汇总按当前搜索条件（忽略分页）
          </span>
        </Space>
      </GlassCard>

      <GlassCard style={{ padding: 16, marginBottom: 16 }}>
        <Input.Search
          placeholder="搜索姓名 / 学号 / 项目"
          allowClear
          style={{ width: 260 }}
          onSearch={(v) => {
            setKeyword(v)
            setPage(1)
          }}
        />
      </GlassCard>

      <GlassTable<CreditVO>
        columns={columns}
        dataSource={data}
        rowKey="id"
        loading={loading}
        pagination={{
          current: page,
          pageSize: size,
          total,
          showSizeChanger: true,
          showTotal: (t) => `共 ${t} 条`,
          onChange: (p, s) => {
            setPage(p)
            setSize(s)
          },
        }}
      />

      <GlassModal
        title="新增加分"
        open={addOpen}
        onCancel={() => setAddOpen(false)}
        footer={
          <Space>
            <Button onClick={() => setAddOpen(false)}>取消</Button>
            <Button type="primary" loading={saving} onClick={handleSave}>
              保存
            </Button>
          </Space>
        }
      >
        <Form form={form} layout="vertical" preserve={false}>
          <Form.Item name="personName" label="姓名" rules={[{ required: true, message: '请输入姓名' }]}>
            <Input maxLength={50} placeholder="素拓加分对象姓名" />
          </Form.Item>
          <Form.Item name="studentNo" label="学号">
            <Input maxLength={20} placeholder="选填" />
          </Form.Item>
          <Form.Item name="activityId" label="关联活动">
            <Select
              allowClear
              showSearch
              optionFilterProp="label"
              options={activities.map((a) => ({ value: a.id, label: a.name }))}
              placeholder="选填"
            />
          </Form.Item>
          <Form.Item name="project" label="加分项目" rules={[{ required: true, message: '请输入加分项目' }]}>
            <Input maxLength={100} placeholder="如 党日活动参与加分" />
          </Form.Item>
          <Form.Item name="credit" label="分值" rules={[{ required: true, message: '请输入分值' }]}>
            <InputNumber
              min={0}
              max={99.99}
              precision={2}
              step={0.5}
              style={{ width: '100%' }}
              placeholder="0 ~ 99.99"
            />
          </Form.Item>
          <Form.Item name="basis" label="依据">
            <Select allowClear options={CREDIT_BASIS_OPTIONS} placeholder="参与 / 答题（选填）" />
          </Form.Item>
          <Form.Item name="remark" label="备注">
            <Input.TextArea rows={2} maxLength={200} placeholder="选填" />
          </Form.Item>
        </Form>
      </GlassModal>
    </div>
  )
}
