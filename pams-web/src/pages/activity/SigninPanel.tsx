import { useCallback, useEffect, useMemo, useState } from 'react'
import type { ColumnsType } from 'antd/es/table'
import { Button, DatePicker, Empty, Form, Input, Popconfirm, Select, Space, Tag, message } from 'antd'
import { DeleteOutlined, DownloadOutlined, PlusOutlined, SearchOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'
import GlassCard from '@/components/glass/GlassCard'
import GlassModal from '@/components/glass/GlassModal'
import GlassTable from '@/components/glass/GlassTable'
import SigninQR from '@/components/signin/SigninQR'
import {
  countSignins,
  createSignin,
  deleteSignin,
  listSignins,
  type SigninSave,
  type SigninVO,
} from '@/api/signin'

const SIGN_TYPE_TEXT: Record<string, string> = { MANUAL: '手动', SCAN: '扫码' }
const IDENTITY_OPTIONS = ['党建干事', '发展对象', '预备党员', '入党积极分子']

/** CSV 字段转义：含逗号/引号/换行的字段加引号包裹并转义内部引号 */
function csvCell(v: string | null | undefined): string {
  const s = v ?? ''
  if (/[",\n\r]/.test(s)) return `"${s.replace(/"/g, '""')}"`
  return s
}

/** 把签到列表转为 CSV（带中文表头与 BOM），并通过 Blob + a.download 下载 */
function exportCsv(rows: SigninVO[]) {
  const header = ['序号', '姓名', '学号', '班级', '身份', '签到时间']
  const lines = rows.map((r, i) =>
    [
      String(i + 1),
      csvCell(r.name),
      csvCell(r.studentNo),
      csvCell(r.className),
      csvCell(r.identityType),
      r.signTime ? dayjs(r.signTime).format('YYYY-MM-DD HH:mm') : '',
    ].join(','),
  )
  const csv = '﻿' + [header.join(','), ...lines].join('\r\n')
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `签到名单_${dayjs().format('YYYYMMDD_HHmmss')}.csv`
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}

export default function SigninPanel({ activityId }: { activityId: number }) {
  const [list, setList] = useState<SigninVO[]>([])
  const [count, setCount] = useState(0)
  const [loading, setLoading] = useState(false)
  const [modalOpen, setModalOpen] = useState(false)
  const [saving, setSaving] = useState(false)
  const [formInit, setFormInit] = useState<Record<string, unknown>>()
  const [form] = Form.useForm()

  const fetchData = useCallback(async () => {
    setLoading(true)
    try {
      const [rows, c] = await Promise.all([listSignins(activityId), countSignins(activityId)])
      setList(rows ?? [])
      setCount(c ?? 0)
    } catch {
      /* http 拦截已提示 */
    } finally {
      setLoading(false)
    }
  }, [activityId])

  useEffect(() => {
    fetchData()
  }, [fetchData])

  const openCreate = () => {
    // GlassModal destroyOnHidden 关闭即卸载，回填用 initialValues（挂载时生效）
    setFormInit({ signType: 'MANUAL', signTime: dayjs() })
    setModalOpen(true)
  }

  const handleSave = async () => {
    const values = await form.validateFields()
    setSaving(true)
    try {
      const payload: SigninSave = {
        activityId,
        name: values.name,
        studentNo: values.studentNo || null,
        className: values.className || null,
        identityType: values.identityType || null,
        signType: values.signType ?? 'MANUAL',
        signTime: values.signTime ? values.signTime.format('YYYY-MM-DD HH:mm:ss') : null,
        location: values.location || null,
      }
      await createSignin(payload)
      message.success('已新增签到')
      setModalOpen(false)
      fetchData()
    } catch {
      /* http 拦截已提示 */
    } finally {
      setSaving(false)
    }
  }

  const handleDelete = async (id: number) => {
    try {
      await deleteSignin(id)
      message.success('已删除')
      fetchData()
    } catch {
      /* http 拦截已提示 */
    }
  }

  const columns = useMemo<ColumnsType<SigninVO>>(
    () => [
      { title: '姓名', dataIndex: 'name', key: 'name' },
      { title: '学号', dataIndex: 'studentNo', key: 'studentNo', render: (v: string | null) => v || '-' },
      { title: '班级', dataIndex: 'className', key: 'className', render: (v: string | null) => v || '-' },
      { title: '身份', dataIndex: 'identityType', key: 'identityType', render: (v: string | null) => v || '-' },
      {
        title: '签到方式',
        dataIndex: 'signType',
        key: 'signType',
        width: 100,
        render: (v: string | null) => (v ? SIGN_TYPE_TEXT[v] ?? v : '-'),
      },
      {
        title: '签到时间',
        dataIndex: 'signTime',
        key: 'signTime',
        width: 160,
        render: (v: string | null) => (v ? dayjs(v).format('YYYY-MM-DD HH:mm') : '-'),
      },
      { title: '定位', dataIndex: 'location', key: 'location', render: (v: string | null) => v || '-' },
      {
        title: '操作',
        key: 'action',
        width: 80,
        render: (_: unknown, r: SigninVO) => (
          <Popconfirm title="确认删除该签到记录？" onConfirm={() => handleDelete(r.id)} okText="删除" cancelText="取消">
            <Button type="link" size="small" danger icon={<DeleteOutlined />} />
          </Popconfirm>
        ),
      },
    ],
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [],
  )

  return (
    <div>
      <GlassCard style={{ padding: 16, marginBottom: 12 }}>
        <SigninQR activityId={activityId} onSigned={fetchData} />
      </GlassCard>
      <GlassCard style={{ padding: 16, marginBottom: 12 }}>
        <Space wrap style={{ display: 'flex', justifyContent: 'space-between' }}>
          <Space wrap>
            <Tag color="red" style={{ fontSize: 14, padding: '2px 12px' }}>
              总签到 {count} 人
            </Tag>
            <SearchOutlined style={{ color: 'var(--color-text-secondary)' }} />
          </Space>
          <Space wrap>
            <Button icon={<DownloadOutlined />} onClick={() => exportCsv(list)} disabled={list.length === 0}>
              导出 CSV
            </Button>
            <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
              新增签到
            </Button>
          </Space>
        </Space>
      </GlassCard>

      <GlassTable<SigninVO>
        columns={columns}
        dataSource={list.map((x) => ({ ...x, key: x.id }))}
        rowKey="id"
        loading={loading}
        pagination={list.length > 10 ? { pageSize: 10, showTotal: (t) => `共 ${t} 条` } : false}
        locale={{ emptyText: <Empty description="暂无签到记录" /> }}
      />

      <GlassModal
        title="新增签到"
        open={modalOpen}
        onCancel={() => setModalOpen(false)}
        footer={
          <Space>
            <Button onClick={() => setModalOpen(false)}>取消</Button>
            <Button type="primary" loading={saving} onClick={handleSave}>
              确认
            </Button>
          </Space>
        }
      >
        <Form form={form} layout="vertical" preserve={false} initialValues={formInit}>
          <Form.Item name="name" label="姓名" rules={[{ required: true, message: '请输入姓名' }]}>
            <Input maxLength={50} placeholder="请输入姓名" />
          </Form.Item>
          <Form.Item name="studentNo" label="学号">
            <Input maxLength={20} placeholder="请输入学号" />
          </Form.Item>
          <Form.Item name="className" label="班级">
            <Input maxLength={100} placeholder="请输入班级" />
          </Form.Item>
          <Form.Item name="identityType" label="身份">
            <Select placeholder="选择身份" allowClear options={IDENTITY_OPTIONS.map((v) => ({ value: v, label: v }))} />
          </Form.Item>
          <Space size={12} style={{ display: 'flex' }}>
            <Form.Item name="signType" label="签到方式" style={{ flex: 1 }} rules={[{ required: true, message: '请选择签到方式' }]}>
              <Select
                options={[
                  { value: 'MANUAL', label: '手动' },
                  { value: 'SCAN', label: '扫码' },
                ]}
              />
            </Form.Item>
            <Form.Item name="signTime" label="签到时间" style={{ flex: 1 }}>
              <DatePicker showTime style={{ width: '100%' }} placeholder="选择时间" />
            </Form.Item>
          </Space>
          <Form.Item name="location" label="定位">
            <Input maxLength={255} placeholder="如 学院楼 A301" />
          </Form.Item>
        </Form>
      </GlassModal>
    </div>
  )
}
