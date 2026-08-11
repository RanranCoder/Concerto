import { useEffect, useMemo, useState } from 'react'
import { App, Button, DatePicker, Form, Input, Popconfirm, Select, Slider, Space, Switch } from 'antd'
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons'
import { useNavigate, useParams } from 'react-router-dom'
import dayjs from 'dayjs'
import PageHeader from '@/components/glass/PageHeader'
import GlassCard from '@/components/glass/GlassCard'
import GlassModal from '@/components/glass/GlassModal'
import GanttChart from '@/components/gantt/GanttChart'
import type { GanttTask } from '@/components/gantt/gantt.utils'
import { listTasks, createTask, updateTask, deleteTask, toGanttTask, type TaskSave } from '@/api/task'
import { listDepts, type DeptVO } from '@/api/dept'

export default function Gantt() {
  const { message } = App.useApp()
  const { id } = useParams()
  const activityId = Number(id)
  const navigate = useNavigate()

  const [tasks, setTasks] = useState<GanttTask[]>([])
  const [depts, setDepts] = useState<DeptVO[]>([])
  const [open, setOpen] = useState(false)
  const [editing, setEditing] = useState<GanttTask | null>(null)
  const [saving, setSaving] = useState(false)
  const [formInit, setFormInit] = useState<Record<string, unknown>>()
  const [form] = Form.useForm()

  // 部门列表：后端 Task 只返回 deptId，需映射 deptName 供甘特图着色（Task 12 minor）
  const [deptsLoaded, setDeptsLoaded] = useState(false)
  useEffect(() => {
    listDepts()
      .then((rows) => {
        setDepts(rows ?? [])
        setDeptsLoaded(true)
      })
      .catch(() => setDeptsLoaded(true))
  }, [])

  const deptNameById = useMemo(() => {
    const m = new Map<number, string>()
    depts.forEach((d) => m.set(d.id, d.name))
    return m
  }, [depts])

  const deptOptions = depts.map((d) => ({ value: d.id, label: d.name }))

  const fetchTasks = async () => {
    if (!activityId) return
    try {
      const rows = await listTasks(activityId)
      setTasks(rows.map((t) => toGanttTask(t, t.deptId != null ? deptNameById.get(t.deptId) : undefined)))
    } catch {
      /* http 拦截已提示 */
    }
  }

  // B11 fix: 等 depts 加载完再拉取任务，避免首次 deptNameById 为空导致任务条无颜色的闪烁
  useEffect(() => {
    if (deptsLoaded) fetchTasks()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activityId, deptsLoaded, deptNameById])

  const openCreate = () => {
    setEditing(null)
    // GlassModal destroyOnHidden 关闭即卸载，回填用 initialValues（挂载时生效）
    setFormInit({ isMilestone: false, progress: 0 })
    setOpen(true)
  }

  const openEdit = (t: GanttTask) => {
    setEditing(t)
    setFormInit({
      name: t.name,
      deptId: t.deptName ? depts.find((d) => d.name === t.deptName)?.id : undefined,
      assignee: t.assignee,
      range: t.startDate && t.endDate ? [dayjs(t.startDate), dayjs(t.endDate)] : undefined,
      dependsOn: t.dependsOn,
      isMilestone: t.isMilestone,
      progress: t.progress,
    })
    setOpen(true)
  }

  const handleSave = async () => {
    const values = await form.validateFields()
    if (!activityId) return
    setSaving(true)
    try {
      const [start, end] = values.range ?? [null, null]
      const payload: TaskSave = {
        activityId,
        name: values.name,
        deptId: values.deptId ?? null,
        assignee: values.assignee ?? null,
        startDate: start ? start.format('YYYY-MM-DD') : null,
        endDate: end ? end.format('YYYY-MM-DD') : null,
        dependsOn: values.dependsOn ?? null,
        isMilestone: values.isMilestone ? 1 : 0,
        progress: values.progress ?? 0,
      }
      if (editing) {
        await updateTask(editing.id, payload)
        message.success('任务已保存')
      } else {
        await createTask(payload)
        message.success('任务已创建')
      }
      setOpen(false)
      fetchTasks()
    } catch {
      /* 校验失败或 http 拦截已提示 */
    } finally {
      setSaving(false)
    }
  }

  const handleDelete = async (t: GanttTask) => {
    try {
      await deleteTask(t.id)
      message.success('任务已删除')
      fetchTasks()
    } catch {
      /* http 拦截已提示 */
    }
  }

  /** 甘特图组件内更新（进度/日期/名称）→ 落库 updateTask + 局部刷新 */
  const handleUpdate = async (t: GanttTask) => {
    try {
      await updateTask(t.id, {
        activityId,
        name: t.name,
        deptId: depts.find((d) => d.name === t.deptName)?.id ?? null,
        assignee: t.assignee ?? null,
        startDate: t.startDate,
        endDate: t.endDate,
        dependsOn: t.dependsOn,
        isMilestone: t.isMilestone ? 1 : 0,
        progress: t.progress ?? 0,
      })
      fetchTasks()
    } catch {
      /* http 拦截已提示 */
    }
  }

  return (
    <div>
      <PageHeader
        title="任务甘特图"
        description="主任分解活动任务，查看依赖关系与进度"
        extra={
          <>
            <Button icon={<PlusOutlined />} type="primary" onClick={openCreate}>
              新增任务
            </Button>
            <Button onClick={() => navigate(`/activities/${activityId}`)}>返回详情</Button>
          </>
        }
      />

      <GlassCard style={{ padding: 16 }}>
        <GanttChart tasks={tasks} onUpdate={handleUpdate} onEdit={openEdit} />
      </GlassCard>

      <GlassModal
        title={editing ? '编辑任务' : '新增任务'}
        open={open}
        onCancel={() => setOpen(false)}
        footer={
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <div>
              {editing && (
                <Popconfirm title="确认删除该任务？" onConfirm={() => { setOpen(false); handleDelete(editing) }} okText="删除" cancelText="取消">
                  <Button danger icon={<DeleteOutlined />}>删除</Button>
                </Popconfirm>
              )}
            </div>
            <Space>
              <Button onClick={() => setOpen(false)}>取消</Button>
              <Button type="primary" loading={saving} onClick={handleSave}>
                保存
              </Button>
            </Space>
          </div>
        }
      >
        <Form
          form={form}
          layout="vertical"
          preserve={false}
          key={editing ? `task-edit-${editing.id}` : 'task-create'}
          initialValues={formInit}
        >
          <Form.Item name="name" label="任务名称" rules={[{ required: true, message: '请输入任务名称' }]}>
            <Input maxLength={100} />
          </Form.Item>
          <Form.Item name="deptId" label="负责部门">
            <Select options={deptOptions} placeholder="选择负责部门" allowClear />
          </Form.Item>
          <Form.Item name="assignee" label="负责人">
            <Input maxLength={50} placeholder="负责人姓名" />
          </Form.Item>
          <Form.Item name="range" label="起止日期" rules={[{ required: true, message: '请选择起止日期' }]}>
            <DatePicker.RangePicker style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="dependsOn" label="前置任务">
            <Select
              placeholder="选择前置任务"
              allowClear
              options={tasks
                .filter((t) => t.id !== editing?.id)
                .map((t) => ({ value: t.id, label: t.name }))}
            />
          </Form.Item>
          <Form.Item name="isMilestone" label="里程碑" valuePropName="checked">
            <Switch />
          </Form.Item>
          <Form.Item name="progress" label="进度" >
            <Slider
              min={0}
              max={100}
              marks={{ 0: '0%', 50: '50%', 100: '100%' }}
            />
          </Form.Item>
        </Form>
      </GlassModal>
    </div>
  )
}
