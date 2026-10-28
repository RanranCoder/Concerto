import { forwardRef, useEffect, useImperativeHandle, useState } from 'react'
import {
  DatePicker,
  Button,
  Form,
  Input,
  Select,
  Slider,
  Switch,
  type FormInstance,
} from 'antd'
import type { Dayjs } from 'dayjs'
import type { DeptVO } from '@/api/dept'
import type { TaskVO } from '@/api/task'
import { listUserOptions, type UserOptionVO } from '@/api/user'
import { dateLabel, wouldCycle } from '@/components/gantt/gantt.utils'

export interface TaskEditorValues {
  name: string
  deptId?: number
  assignee?: string
  assigneeUserId?: number
  startDate?: Dayjs
  endDate?: Dayjs
  dependsOn?: number
  isMilestone: boolean
  progress: number
  description?: string
  priority?: number
}

interface Props {
  initial?: TaskEditorValues
  disabled: boolean
  tasks: TaskVO[]
  depts: DeptVO[]
  selectedId?: number
  onDirty: () => void
  onFinish: () => void
}

/** A keyed editor owns its Form instance; an old drawer cannot clear another task's values. */
export default forwardRef<FormInstance<TaskEditorValues>, Props>(
  function TaskEditorForm(
    { initial, disabled, tasks, depts, selectedId, onDirty, onFinish },
    ref,
  ) {
    const [form] = Form.useForm<TaskEditorValues>()
    const milestone = Form.useWatch('isMilestone', form)
    const departmentId = Form.useWatch('deptId', form) as number | undefined
    const [retry, setRetry] = useState(0)
    const [members, setMembers] = useState<{
      departmentId?: number
      users: UserOptionVO[]
      status: 'loading' | 'ready' | 'error'
    }>({ users: [], status: 'loading' })
    useEffect(() => {
      if (departmentId == null) return
      let cancelled = false
      setMembers({ departmentId, users: [], status: 'loading' })
      listUserOptions({ deptId: departmentId })
        .then((users) => {
          if (!cancelled)
            setMembers({
              departmentId,
              users: users.filter(
                (user) =>
                  user.deptId === departmentId && !!user.realName?.trim(),
              ),
              status: 'ready',
            })
        })
        .catch(() => {
          if (!cancelled)
            setMembers({ departmentId, users: [], status: 'error' })
        })
      return () => {
        cancelled = true
      }
    }, [departmentId, retry])
    const currentMembers =
      members.departmentId === departmentId ? members.users : []
    const loadingMembers =
      departmentId != null &&
      (members.departmentId !== departmentId || members.status === 'loading')
    const failedMembers =
      departmentId != null &&
      members.departmentId === departmentId &&
      members.status === 'error'
    const names = [...new Set(currentMembers.map((user) => user.realName))]
    const legacyAssignee =
      departmentId === initial?.deptId &&
      !!initial?.assignee &&
      !names.includes(initial.assignee)
    const unboundLegacy = departmentId === initial?.deptId && !!initial?.assignee && initial.assigneeUserId == null
    useImperativeHandle(ref, () => form, [form])
    return (
      <Form<TaskEditorValues>
        form={form}
        layout="vertical"
        initialValues={initial}
        disabled={disabled}
        onValuesChange={onDirty}
        onFinish={onFinish}
      >
        <div className="task-edit-fields">
          <Form.Item
            className="wide"
            name="name"
            label="任务名称"
            rules={[
              {
                required: true,
                whitespace: true,
                message: '请填写任务名称',
              },
            ]}
          >
            <Input maxLength={100} placeholder="例如：核对参会名单" />
          </Form.Item>
          <Form.Item name="deptId" label="负责部门">
            <Select
              allowClear
              onChange={() => {
                form.setFieldValue('assignee', undefined)
                form.setFieldValue('assigneeUserId', undefined)
                form.validateFields(['assignee']).catch(() => {})
              }}
              placeholder="待分配"
              options={[
                ...depts.map((d) => ({ value: d.id, label: d.name })),
                ...(initial?.deptId != null &&
                !depts.some((d) => d.id === initial.deptId)
                  ? [
                      {
                        value: initial.deptId,
                        label: `原部门（${initial.deptId}）`,
                      },
                    ]
                  : []),
              ]}
            />
          </Form.Item>
          <Form.Item
            name="assigneeUserId"
            label="负责人"
            dependencies={['deptId']}
            extra={
              failedMembers ? (
                <span role="alert">
                  部门成员加载失败。
                  <Button
                    type="link"
                    size="small"
                    disabled={disabled}
                    onClick={() => setRetry((value) => value + 1)}
                  >
                    重新加载
                  </Button>
                </span>
              ) : unboundLegacy ? (
                `历史负责人：${initial?.assignee}，尚未绑定账号，可保留原记录或重新选择。`
              ) : !loadingMembers && legacyAssignee && !failedMembers ? (
                '原负责人不在当前部门可选成员中，可保留原记录或重新选择。'
              ) : undefined
            }
            rules={[
              {
                validator: async (_, value?: number) => {
                  if (
                    !value ||
                    (value === initial?.assigneeUserId &&
                      departmentId === initial?.deptId)
                  )
                    return
                  if (departmentId == null) throw new Error('请先选择负责部门')
                  if (loadingMembers)
                    throw new Error('正在加载部门成员，请稍后再保存')
                  if (failedMembers)
                    throw new Error('部门成员加载失败，请重新加载')
                  if (!currentMembers.some(user => user.id === value))
                    throw new Error('请选择当前部门的成员')
                },
              },
            ]}
          >
            <Select
              onChange={(id?: number) => form.setFieldValue('assignee', currentMembers.find(user => user.id === id)?.realName)}
              showSearch
              allowClear
              optionFilterProp="label"
              aria-label="负责人"
              disabled={
                disabled ||
                departmentId == null ||
                loadingMembers ||
                failedMembers
              }
              loading={loadingMembers}
              placeholder={
                departmentId == null
                  ? '先选择负责部门'
                  : loadingMembers
                    ? '正在加载部门成员'
                    : '输入姓名搜索或选择成员'
              }
              notFoundContent={
                loadingMembers ? '正在加载部门成员…' : '没有匹配的部门成员'
              }
              options={[
                ...currentMembers.map(user => ({ value: user.id, label: `${user.realName}（${user.id}）` })),
                ...(legacyAssignee && initial?.assigneeUserId
                  ? [
                      {
                        value: initial!.assigneeUserId!,
                        label: `${initial!.assignee}（原负责人）`,
                        disabled: true,
                      },
                    ]
                  : []),
              ]}
            />
          </Form.Item>
          <Form.Item name="assignee" hidden><Input /></Form.Item>
          <Form.Item
            className="wide task-milestone-field"
            name="isMilestone"
            label="设为里程碑（单日关键节点）"
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>
          <Form.Item
            name="startDate"
            label={milestone ? '里程碑日期' : '开始日期'}
          >
            <DatePicker style={{ width: '100%' }} />
          </Form.Item>
          {!milestone && (
            <Form.Item name="endDate" label="结束日期">
              <DatePicker style={{ width: '100%' }} />
            </Form.Item>
          )}
          <p className="task-field-hint wide">
            {milestone
              ? '里程碑只需选择一个日期。'
              : '日期可暂不填写；填写时需同时选择开始和结束日期。'}
          </p>
          <Form.Item className="wide" name="dependsOn" label="前置任务">
            <Select
              allowClear
              placeholder="无前置任务"
              showSearch
              optionFilterProp="label"
              options={tasks
                .filter((t) => t.id !== selectedId)
                .map((t) => ({
                  value: t.id,
                  label: `${t.name}（${dateLabel(t.endDate)}）`,
                  disabled: wouldCycle(tasks, selectedId, t.id),
                }))}
            />
          </Form.Item>
          <Form.Item className="wide" name="progress" label="当前进度">
            <Slider
              min={0}
              max={100}
              marks={{ 0: '0%', 50: '50%', 100: '100%' }}
            />
          </Form.Item>
          <Form.Item className="wide" name="description" label="任务说明">
            <Input.TextArea
              rows={4}
              maxLength={2000}
              placeholder="说明交付内容、准备要求等"
            />
          </Form.Item>
        </div>
        <Form.Item name="priority" hidden>
          <Input type="number" />
        </Form.Item>
        <div className="task-context">
          <h3>日期与依赖</h3>
          <p>
            前置完成后的次日开始。改期前会展示后续任务影响，由你选择仅保存当前任务或同时顺延。
          </p>
        </div>
      </Form>
    )
  },
)
