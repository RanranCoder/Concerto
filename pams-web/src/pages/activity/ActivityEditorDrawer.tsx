import { useEffect, useState } from 'react'
import { App, Button, DatePicker, Form, Input, Select, Space, Spin } from 'antd'
import dayjs, { type Dayjs } from 'dayjs'
import FormSurface, { FormSection } from '@/components/form/FormSurface'
import {
  createActivity,
  getActivity,
  updateActivity,
  type ActivitySave,
} from '@/api/activity'
import { ACTIVITY_TYPE_OPTIONS } from '@/api/activityStatus'

interface Values extends Omit<ActivitySave, 'startDate' | 'endDate'> {
  range?: [Dayjs, Dayjs]
}
export default function ActivityEditorDrawer({
  open,
  activityId,
  onClose,
  onSaved,
}: {
  open: boolean
  activityId?: number
  onClose: () => void
  onSaved: () => void
}) {
  const { message, modal } = App.useApp(),
    [form] = Form.useForm<Values>()
  const [initial, setInitial] = useState<Values>(),
    [loading, setLoading] = useState(false),
    [saving, setSaving] = useState(false),
    [error, setError] = useState(false)
  const [locked, setLocked] = useState(false)
  useEffect(() => {
    if (!open) return
    let cancelled = false
    setInitial(undefined)
    setError(false)
    setLocked(false)
    if (!activityId) {
      setLoading(false)
      setInitial({ name: '', type: 'OTHER' })
      return
    }
    setLoading(true)
    getActivity(activityId)
      .then((a) => {
        if (!cancelled) {
          setLocked(['FINISHED', 'ARCHIVED'].includes(a.status))
          setInitial({
            ...a,
            range: a.startDate
              ? [dayjs(a.startDate), dayjs(a.endDate ?? a.startDate)]
              : undefined,
          })
        }
      })
      .catch(() => {
        if (!cancelled) setError(true)
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [activityId, open])
  const close = () => {
    if (saving) return
    if (form.isFieldsTouched())
      modal.confirm({
        title: '放弃未保存的修改？',
        content: '关闭后，本次修改不会保存。',
        okText: '放弃修改',
        cancelText: '继续编辑',
        onOk: onClose,
      })
    else onClose()
  }
  const save = async () => {
    let values: Values
    try {
      values = await form.validateFields()
    } catch {
      return
    }
    setSaving(true)
    try {
      const { range, ...fields } = values
      const data: ActivitySave = {
        ...fields,
        startDate: range?.[0]?.format('YYYY-MM-DD') ?? null,
        endDate: range?.[1]?.format('YYYY-MM-DD') ?? null,
      }
      if (activityId) await updateActivity(activityId, data)
      else await createActivity(data)
      message.success(activityId ? '活动已保存' : '活动已创建')
      onSaved()
      onClose()
    } catch {
      /* Request errors retain all entered values. */
    } finally {
      setSaving(false)
    }
  }
  return (
    <FormSurface
      mode="drawer"
      className="activity-form-drawer"
      title={activityId ? '编辑活动' : '新建活动'}
      description="明确活动安排、组织分工与参与范围。"
      open={open}
      onCancel={close}
      footer={
        <Space>
          <Button onClick={close} disabled={saving}>
            取消
          </Button>
          <Button
            type="primary"
            onClick={save}
            loading={saving}
            disabled={loading || error || locked || !initial}
          >
            保存活动
          </Button>
        </Space>
      }
    >
      <Spin spinning={loading}>
        {locked && (
          <p className="activity-inline-note">
            活动已结束或归档，基本信息仅供查看。
          </p>
        )}
        {error ? (
          <p role="alert">活动信息读取失败，请关闭后重试。</p>
        ) : (
          initial && (
            <Form
              key={`${activityId ?? 'new'}-${open}`}
              form={form}
              layout="vertical"
              initialValues={initial}
              preserve={false}
              disabled={locked}
            >
              <FormSection title="活动基本信息">
                <Form.Item
                  className="form-field-wide"
                  name="name"
                  label="活动名称"
                  rules={[
                    {
                      required: true,
                      whitespace: true,
                      message: '请输入活动名称',
                    },
                  ]}
                >
                  <Input maxLength={100} />
                </Form.Item>
                <Form.Item name="theme" label="活动主题">
                  <Input maxLength={200} />
                </Form.Item>
                <Form.Item name="type" label="活动类型">
                  <Select options={ACTIVITY_TYPE_OPTIONS} />
                </Form.Item>
              </FormSection>
              <FormSection title="时间与地点">
                <Form.Item name="range" label="起止日期">
                  <DatePicker.RangePicker style={{ width: '100%' }} />
                </Form.Item>
                <Form.Item name="location" label="活动地点">
                  <Input maxLength={100} />
                </Form.Item>
              </FormSection>
              <FormSection title="组织与人员">
                <Form.Item name="organizer" label="组织单位">
                  <Input maxLength={100} />
                </Form.Item>
                <Form.Item name="leader" label="负责人">
                  <Input maxLength={50} />
                </Form.Item>
                <Form.Item name="host" label="主持人">
                  <Input maxLength={50} />
                </Form.Item>
                <Form.Item name="targetAudience" label="面向对象">
                  <Input maxLength={200} />
                </Form.Item>
              </FormSection>
              <FormSection title="活动说明">
                <Form.Item
                  className="form-field-wide"
                  name="description"
                  label="活动描述"
                >
                  <Input.TextArea rows={5} maxLength={1000} />
                </Form.Item>
              </FormSection>
            </Form>
          )
        )}
      </Spin>
    </FormSurface>
  )
}
