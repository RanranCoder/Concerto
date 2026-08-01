import { useEffect, useMemo, useState } from 'react'
import { Button, Form, Input, message, Popconfirm, Select, Space } from 'antd'
import { DeleteOutlined, EditOutlined, PlusOutlined } from '@ant-design/icons'
import GlassCard from '@/components/glass/GlassCard'
import GlassModal from '@/components/glass/GlassModal'
import GlassTable from '@/components/glass/GlassTable'
import PageHeader from '@/components/glass/PageHeader'
import {
  listFreeSchedules,
  createFreeSchedule,
  updateFreeSchedule,
  deleteFreeSchedule,
  type FreeScheduleSave,
  type FreeScheduleVO,
} from '@/api/freeSchedule'
import { listDepts, type DeptVO } from '@/api/dept'
import { useAuthStore } from '@/stores/auth'

interface DeptRow {
  key: number
  deptId: number
  deptName: string
  freeWeeksText: string
}

interface PersonRow extends FreeScheduleVO {
  key: number
}

interface FormValues {
  personName: string
  className?: string
  deptId?: number
  freeWeeks?: string
  note?: string
}

/** 部门无课表汇总文本：空闲周次并集 */
function summarize(deptFreeList: FreeScheduleVO[]): string {
  const weeks = new Set<number>()
  deptFreeList.forEach((f) => {
    try {
      const parsed = f.freeWeeks ? JSON.parse(f.freeWeeks) : null
      if (Array.isArray(parsed)) {
        parsed.forEach((w) => {
          if (typeof w === 'number') weeks.add(w)
        })
      } else if (parsed && typeof parsed === 'object' && typeof parsed.start === 'number' && typeof parsed.end === 'number') {
        for (let w = parsed.start; w <= parsed.end; w++) weeks.add(w)
      }
    } catch {
      /* 忽略无法解析的 freeWeeks */
    }
  })
  const sorted = Array.from(weeks).sort((a, b) => a - b)
  return sorted.length > 0 ? `空闲周：${sorted.join('、')}` : '无空闲周数据'
}

export default function FreeScheduleList() {
  const user = useAuthStore((s) => s.user)
  const isMinisterOrAbove = (user?.roleLevel ?? 0) >= 3

  const [depts, setDepts] = useState<DeptVO[]>([])
  const [deptFilter, setDeptFilter] = useState<number | undefined>()
  const [list, setList] = useState<FreeScheduleVO[]>([])
  const [loading, setLoading] = useState(false)
  const [modalOpen, setModalOpen] = useState(false)
  const [editing, setEditing] = useState<FreeScheduleVO | null>(null)
  const [saving, setSaving] = useState(false)
  const [form] = Form.useForm<FormValues>()

  useEffect(() => {
    listDepts()
      .then((res) => setDepts(res ?? []))
      .catch(() => {
        /* http 拦截已提示 */
      })
  }, [])

  useEffect(() => {
    setLoading(true)
    listFreeSchedules({ deptId: deptFilter })
      .then((res) => setList(res ?? []))
      .catch(() => {
        /* http 拦截已提示 */
      })
      .finally(() => setLoading(false))
  }, [deptFilter])

  const deptRows: DeptRow[] = useMemo(() => {
    const grouped = new Map<number, FreeScheduleVO[]>()
    list.forEach((f) => {
      const key = f.deptId ?? 0
      if (!grouped.has(key)) grouped.set(key, [])
      grouped.get(key)!.push(f)
    })
    // 无课表记录可能来自任意部门（含未分配的 0）
    return Array.from(grouped.entries())
      .map(([deptId, items]) => {
        const dept = depts.find((d) => d.id === deptId)
        return {
          key: deptId,
          deptId,
          deptName: dept?.name ?? (deptId === 0 ? '未分配' : `部门#${deptId}`),
          freeWeeksText: summarize(items),
        }
      })
      .sort((a, b) => a.deptName.localeCompare(b.deptName, 'zh'))
  }, [list, depts])

  const expandedPersonRows = (deptId: number): PersonRow[] =>
    list
      .filter((f) => (f.deptId ?? 0) === deptId)
      .map((f) => ({ ...f, key: f.id }))

  const openCreate = () => {
    setEditing(null)
    form.resetFields()
    setModalOpen(true)
  }

  const openEdit = (record: FreeScheduleVO) => {
    setEditing(record)
    form.setFieldsValue({
      personName: record.personName,
      className: record.className ?? undefined,
      deptId: record.deptId ?? undefined,
      freeWeeks: record.freeWeeks ?? undefined,
      note: record.note ?? undefined,
    })
    setModalOpen(true)
  }

  const handleSave = async () => {
    const values = await form.validateFields()
    setSaving(true)
    try {
      const payload: FreeScheduleSave = {
        personName: values.personName,
        className: values.className,
        deptId: values.deptId ?? editing?.deptId,
        freeWeeks: values.freeWeeks,
        note: values.note,
      }
      if (editing) {
        await updateFreeSchedule(editing.id, payload)
        message.success('保存成功')
      } else {
        await createFreeSchedule(payload)
        message.success('创建成功')
      }
      setModalOpen(false)
      refresh()
    } catch {
      /* 校验失败或 http 拦截已提示 */
    } finally {
      setSaving(false)
    }
  }

  const handleDelete = async (id: number) => {
    try {
      await deleteFreeSchedule(id)
      message.success('已删除')
      refresh()
    } catch {
      /* http 拦截已提示 */
    }
  }

  const refresh = () => {
    listFreeSchedules({ deptId: deptFilter })
      .then((res) => setList(res ?? []))
      .catch(() => {
        /* http 拦截已提示 */
      })
  }

  return (
    <div>
      <PageHeader
        title="无课表"
        description="各部门人员空闲周次，便于排班参考"
        extra={
          isMinisterOrAbove && (
            <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
              新增无课表
            </Button>
          )
        }
      />

      <GlassCard style={{ padding: 16, marginBottom: 16 }}>
        <Space wrap>
          <Select
            placeholder="部门筛选"
            allowClear
            options={depts.map((d) => ({ value: d.id, label: d.name }))}
            style={{ width: 160 }}
            value={deptFilter}
            onChange={setDeptFilter}
          />
          <span style={{ color: 'var(--color-text-secondary)', fontSize: 12 }}>
            文秘部 / 组织部 / 新媒体中心 / 青年科技部，展开查看人员与空闲周次
          </span>
        </Space>
      </GlassCard>

      <GlassTable<DeptRow>
        columns={[
          { title: '部门', dataIndex: 'deptName', key: 'deptName' },
          { title: '空闲周次', dataIndex: 'freeWeeksText', key: 'freeWeeksText' },
        ]}
        dataSource={deptRows}
        loading={loading}
        pagination={false}
        locale={{ emptyText: '暂无无课表数据' }}
        expandable={{
          expandedRowRender: (record) => {
            const persons = expandedPersonRows(record.deptId)
            return (
              <GlassTable<PersonRow>
                columns={[
                  { title: '姓名', dataIndex: 'personName', key: 'personName' },
                  { title: '班级', dataIndex: 'className', key: 'className', render: (v: string) => v || '-' },
                  {
                    title: '空闲周次',
                    key: 'freeWeeks',
                    render: (_: unknown, r: PersonRow) => r.freeWeeks || '-',
                  },
                  {
                    title: '操作',
                    key: 'action',
                    render: (_: unknown, r: PersonRow) => (
                      <Space size="small">
                        {isMinisterOrAbove && (
                          <Button type="link" size="small" icon={<EditOutlined />} onClick={() => openEdit(r)}>
                            编辑
                          </Button>
                        )}
                        {isMinisterOrAbove && (
                          <Popconfirm
                            title="确认删除该无课表记录？"
                            onConfirm={() => handleDelete(r.id)}
                            okText="删除"
                            cancelText="取消"
                          >
                            <Button type="link" size="small" danger icon={<DeleteOutlined />}>
                              删除
                            </Button>
                          </Popconfirm>
                        )}
                      </Space>
                    ),
                  },
                ]}
                dataSource={persons}
                pagination={false}
                rowKey="key"
              />
            )
          },
        }}
      />

      <GlassModal
        title={editing ? '编辑无课表' : '新增无课表'}
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
        <Form form={form} layout="vertical" preserve={false}>
          <Form.Item
            name="personName"
            label="姓名"
            rules={[{ required: true, message: '请输入姓名' }]}
          >
            <Input maxLength={50} placeholder="人员姓名" />
          </Form.Item>
          <Form.Item name="className" label="班级">
            <Input maxLength={100} placeholder="如 计科2401" />
          </Form.Item>
          <Form.Item name="deptId" label="所属部门">
            <Select
              allowClear
              placeholder="选择部门（可选）"
              options={depts.map((d) => ({ value: d.id, label: d.name }))}
            />
          </Form.Item>
          <Form.Item name="freeWeeks" label="空闲周次" rules={[{ required: true, message: '请输入空闲周次' }]}>
            <Input placeholder='JSON 数组，如 [1,3,5] 或 {"start":1,"end":18}' />
          </Form.Item>
          <Form.Item name="note" label="备注">
            <Input maxLength={200} />
          </Form.Item>
        </Form>
      </GlassModal>
    </div>
  )
}
