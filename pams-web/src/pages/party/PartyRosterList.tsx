import { useCallback, useEffect, useState } from 'react'
import { App, Button, Form, Input, Popconfirm, Select, Space } from 'antd'
import { DeleteOutlined, EditOutlined, PlusOutlined } from '@ant-design/icons'
import GlassCard from '@/components/glass/GlassCard'
import GlassModal from '@/components/glass/GlassModal'
import GlassTable from '@/components/glass/GlassTable'
import PageHeader from '@/components/glass/PageHeader'
import { useAuthStore } from '@/stores/auth'
import {
  listRosters,
  createRoster,
  updateRoster,
  deleteRoster,
  ROSTER_TYPES,
  ROSTER_TYPE_MAP,
  GENDER_OPTIONS,
  type PartyRosterVO,
} from '@/api/party'

type RosterRecord = PartyRosterVO & { key: number }

interface RosterFormValues {
  rosterType: string
  name: string
  gender?: string
  studentNo?: string
  className?: string
  branchName?: string
  issueNo?: string
  remark?: string
}

export default function PartyRosterList() {
  const { message } = App.useApp()
  const user = useAuthStore((s) => s.user)
  const isMinisterOrAbove = (user?.roleLevel ?? 0) >= 3

  const [type, setType] = useState<string | undefined>()
  const [issueNo, setIssueNo] = useState<string | undefined>()
  const [list, setList] = useState<RosterRecord[]>([])
  const [loading, setLoading] = useState(false)
  const [modalOpen, setModalOpen] = useState(false)
  const [editing, setEditing] = useState<PartyRosterVO | null>(null)
  const [formInit, setFormInit] = useState<Partial<RosterFormValues>>()
  const [saving, setSaving] = useState(false)
  const [form] = Form.useForm<RosterFormValues>()

  const fetchList = useCallback(async () => {
    setLoading(true)
    try {
      const res = await listRosters({ type, issueNo })
      setList((res ?? []).map((r) => ({ ...r, key: r.id })))
    } catch {
      /* http 拦截已提示 */
    } finally {
      setLoading(false)
    }
  }, [type, issueNo])

  useEffect(() => {
    fetchList()
  }, [fetchList])

  const openCreate = () => {
    setEditing(null)
    setFormInit(undefined)
    setModalOpen(true)
  }

  const openEdit = (r: PartyRosterVO) => {
    setEditing(r)
    // GlassModal destroyOnHidden 关闭即卸载，回填用 initialValues（挂载时生效）
    setFormInit({
      rosterType: r.rosterType,
      name: r.name,
      gender: r.gender ?? undefined,
      studentNo: r.studentNo ?? undefined,
      className: r.className ?? undefined,
      branchName: r.branchName ?? undefined,
      issueNo: r.issueNo ?? undefined,
      remark: r.remark ?? undefined,
    })
    setModalOpen(true)
  }

  const handleSave = async () => {
    const values = await form.validateFields()
    setSaving(true)
    try {
      const payload = {
        rosterType: values.rosterType,
        name: values.name,
        gender: values.gender || null,
        studentNo: values.studentNo || null,
        className: values.className || null,
        branchName: values.branchName || null,
        issueNo: values.issueNo || null,
        remark: values.remark || null,
      }
      if (editing) {
        await updateRoster(editing.id, payload)
        message.success('名单已更新')
      } else {
        await createRoster(payload)
        message.success('名单已新增')
      }
      setModalOpen(false)
      fetchList()
    } catch {
      /* 校验失败或 http 拦截已提示 */
    } finally {
      setSaving(false)
    }
  }

  const handleDelete = async (id: number) => {
    try {
      await deleteRoster(id)
      message.success('已删除')
      fetchList()
    } catch {
      /* http 拦截已提示 */
    }
  }

  const columns = [
    { title: '姓名', dataIndex: 'name', key: 'name' },
    { title: '性别', dataIndex: 'gender', key: 'gender', render: (v: string) => v || '-' },
    { title: '学号', dataIndex: 'studentNo', key: 'studentNo', render: (v: string) => v || '-' },
    { title: '班级', dataIndex: 'className', key: 'className', render: (v: string) => v || '-' },
    { title: '所在支部', dataIndex: 'branchName', key: 'branchName', render: (v: string) => v || '-' },
    {
      title: '类型',
      dataIndex: 'rosterType',
      key: 'rosterType',
      render: (v: string) => ROSTER_TYPE_MAP[v] ?? v,
    },
    { title: '期数', dataIndex: 'issueNo', key: 'issueNo', render: (v: string) => v || '-' },
    ...(isMinisterOrAbove
      ? [
          {
            title: '操作',
            key: 'action',
            width: 140,
            render: (_: unknown, r: RosterRecord) => (
              <Space size="small">
                <Button type="link" size="small" icon={<EditOutlined />} onClick={() => openEdit(r)}>
                  编辑
                </Button>
                <Popconfirm title="确认删除该名单？" onConfirm={() => handleDelete(r.id)} okText="删除" cancelText="取消">
                  <Button type="link" size="small" danger icon={<DeleteOutlined />}>
                    删除
                  </Button>
                </Popconfirm>
              </Space>
            ),
          },
        ]
      : []),
  ]

  return (
    <div>
      <PageHeader
        title="入党名单台账"
        description="推优 / 通过 / 汇总 / 发展对象 / 转移名单"
        extra={
          isMinisterOrAbove && (
            <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
              新增名单
            </Button>
          )
        }
      />

      <GlassCard style={{ padding: 16, marginBottom: 16 }}>
        <Space wrap>
          <Select
            placeholder="名单类型"
            allowClear
            options={ROSTER_TYPES as unknown as { value: string; label: string }[]}
            style={{ width: 160 }}
            value={type}
            onChange={setType}
          />
          <Input.Search
            placeholder="期数，如 40"
            allowClear
            style={{ width: 200 }}
            onSearch={(v) => setIssueNo(v || undefined)}
          />
        </Space>
      </GlassCard>

      <GlassTable<RosterRecord>
        columns={columns}
        dataSource={list}
        rowKey="id"
        loading={loading}
        pagination={false}
      />

      <GlassModal
        title={editing ? '编辑名单' : '新增名单'}
        open={modalOpen}
        onCancel={() => setModalOpen(false)}
        footer={
          <Space>
            <Button onClick={() => setModalOpen(false)}>取消</Button>
            <Button type="primary" loading={saving} onClick={handleSave}>
              保存
            </Button>
          </Space>
        }
      >
        <Form
          form={form}
          layout="vertical"
          preserve={false}
          key={editing ? `edit-${editing.id}` : 'create'}
          initialValues={formInit}
        >
          <Form.Item name="rosterType" label="名单类型" rules={[{ required: true, message: '请选择名单类型' }]}>
            <Select options={ROSTER_TYPES as unknown as { value: string; label: string }[]} placeholder="请选择" />
          </Form.Item>
          <Form.Item name="name" label="姓名" rules={[{ required: true, message: '请输入姓名' }]}>
            <Input maxLength={50} placeholder="请输入姓名" />
          </Form.Item>
          <Space size="middle" wrap>
            <Form.Item name="gender" label="性别">
              <Select options={GENDER_OPTIONS} style={{ width: 100 }} placeholder="请选择" />
            </Form.Item>
            <Form.Item name="studentNo" label="学号">
              <Input maxLength={20} style={{ width: 180 }} placeholder="请输入学号" />
            </Form.Item>
            <Form.Item name="issueNo" label="期数">
              <Input maxLength={20} style={{ width: 120 }} placeholder="如 40" />
            </Form.Item>
          </Space>
          <Form.Item name="className" label="班级">
            <Input maxLength={100} placeholder="如 计科2301" />
          </Form.Item>
          <Form.Item name="branchName" label="所在支部">
            <Input maxLength={100} placeholder="如 信息与智能工程学院学生第一党支部" />
          </Form.Item>
          <Form.Item name="remark" label="备注">
            <Input.TextArea rows={3} maxLength={200} />
          </Form.Item>
        </Form>
      </GlassModal>
    </div>
  )
}
