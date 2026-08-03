import { useEffect, useState } from 'react'
import { Button, DatePicker, Form, Input, Select, Space, Spin, message } from 'antd'
import { ArrowLeftOutlined } from '@ant-design/icons'
import { useNavigate, useParams } from 'react-router-dom'
import dayjs from 'dayjs'
import PageHeader from '@/components/glass/PageHeader'
import GlassCard from '@/components/glass/GlassCard'
import { getActivity, updateActivity } from '@/api/activity'
import { ACTIVITY_TYPE_OPTIONS } from '@/api/activityStatus'

/** getActivity 返回 detail 视图，含 targetAudience/description（列表 VO 未声明） */
type ActivityEditVO = {
  id: number
  name: string
  theme: string
  type: string
  status: string
  startDate: string | null
  endDate: string | null
  location: string
  organizer: string
  host: string
  leader: string
  createdAt: string
  targetAudience?: string
  description?: string
}

export default function ActivityEdit() {
  const { id } = useParams()
  const activityId = Number(id)
  const navigate = useNavigate()
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)
  const [form] = Form.useForm()

  useEffect(() => {
    if (!activityId) return
    setLoading(true)
    getActivity(activityId)
      .then((a) => {
        const vo = a as ActivityEditVO
        form.setFieldsValue({
          name: vo.name,
          theme: vo.theme ?? undefined,
          type: vo.type ?? 'OTHER',
          range: vo.startDate ? [dayjs(vo.startDate), vo.endDate ? dayjs(vo.endDate) : undefined] : undefined,
          location: vo.location ?? undefined,
          organizer: vo.organizer ?? undefined,
          targetAudience: vo.targetAudience ?? undefined,
          host: vo.host ?? undefined,
          leader: vo.leader ?? undefined,
          description: vo.description ?? undefined,
        })
      })
      .catch(() => {})
      .finally(() => setLoading(false))
  }, [activityId, form])

  const handleSave = async () => {
    const values = await form.validateFields()
    setSaving(true)
    try {
      const payload = {
        name: values.name,
        theme: values.theme || null,
        type: values.type ?? 'OTHER',
        startDate: values.range?.[0]?.format('YYYY-MM-DD') ?? null,
        endDate: values.range?.[1]?.format('YYYY-MM-DD') ?? null,
        location: values.location || null,
        organizer: values.organizer || null,
        targetAudience: values.targetAudience || null,
        host: values.host || null,
        leader: values.leader || null,
        description: values.description || null,
      }
      await updateActivity(activityId, payload)
      message.success('活动已保存')
      navigate(`/activities/${activityId}`, { replace: true })
    } catch {
      /* 校验失败或 http 拦截已提示 */
    } finally {
      setSaving(false)
    }
  }

  return (
    <div>
      <PageHeader
        title="编辑活动"
        description="修改活动基本信息"
        extra={<Button icon={<ArrowLeftOutlined />} onClick={() => navigate(`/activities/${activityId}`)}>返回详情</Button>}
      />
      <Spin spinning={loading}>
        <GlassCard style={{ padding: 24, maxWidth: 720 }}>
          <Form form={form} layout="vertical">
            <Form.Item name="name" label="活动名称" rules={[{ required: true, message: '请输入活动名称' }]}>
              <Input maxLength={100} />
            </Form.Item>
            <Form.Item name="theme" label="活动主题"><Input maxLength={200} /></Form.Item>
            <Form.Item name="type" label="类型">
              <Select options={ACTIVITY_TYPE_OPTIONS} />
            </Form.Item>
            <Form.Item name="range" label="时间范围"><DatePicker.RangePicker style={{ width: '100%' }} /></Form.Item>
            <Form.Item name="location" label="地点"><Input maxLength={100} /></Form.Item>
            <Form.Item name="organizer" label="组织单位"><Input maxLength={100} /></Form.Item>
            <Form.Item name="targetAudience" label="面向对象"><Input maxLength={200} /></Form.Item>
            <Form.Item name="host" label="主持人"><Input maxLength={50} /></Form.Item>
            <Form.Item name="leader" label="负责人"><Input maxLength={50} /></Form.Item>
            <Form.Item name="description" label="活动描述"><Input.TextArea rows={4} /></Form.Item>
            <Space>
              <Button onClick={() => navigate(`/activities/${activityId}`)}>取消</Button>
              <Button type="primary" loading={saving} onClick={handleSave}>保存</Button>
            </Space>
          </Form>
        </GlassCard>
      </Spin>
    </div>
  )
}
