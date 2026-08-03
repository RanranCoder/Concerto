import { useState } from 'react'
import { Button, Form, Input, Typography, message } from 'antd'
import { useParams } from 'react-router-dom'
import { scanSignin } from '@/api/signin'

interface ScanFormValues {
  name: string
  studentNo?: string
}

/**
 * 免登录扫码签到落地页（/signin/:token）。
 * 复用 login-page / login-card 的毛玻璃样式；扫码链接为未登录的普通成员/同学打开，
 * 填姓名（必填）与学号（选填）即可签到。无效/过期 token 由后端返回业务错误，
 * http 拦截层统一 message 提示（本页无需额外错误态）。
 */
export default function SigninScan() {
  const { token } = useParams()
  const [done, setDone] = useState(false)
  const [saving, setSaving] = useState(false)
  const [form] = Form.useForm()

  const handleSubmit = async (values: ScanFormValues) => {
    if (!token) return
    setSaving(true)
    try {
      await scanSignin({ token, name: values.name, studentNo: values.studentNo || undefined })
      setDone(true)
      message.success('签到成功')
    } catch {
      /* http 拦截已提示（无效/过期码） */
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="login-page">
      <div className="glass-card login-card">
        <Typography.Title level={3} style={{ textAlign: 'center', color: 'var(--color-text)' }}>
          活动签到
        </Typography.Title>
        <Typography.Paragraph style={{ textAlign: 'center', color: 'var(--color-text-secondary)' }}>
          扫码签到 · 信息与智能工程学院党建办公室
        </Typography.Paragraph>
        {done ? (
          <div style={{ textAlign: 'center' }}>
            <div style={{ fontSize: 22, color: 'var(--color-red)', marginBottom: 8 }}>✓ 签到成功</div>
            <Button onClick={() => window.location.reload()}>继续</Button>
          </div>
        ) : (
          <Form form={form} layout="vertical" onFinish={handleSubmit}>
            <Form.Item name="name" label="姓名" rules={[{ required: true, message: '请输入姓名' }]}>
              <Input maxLength={50} placeholder="请输入姓名" />
            </Form.Item>
            <Form.Item name="studentNo" label="学号">
              <Input maxLength={20} placeholder="学号（选填）" />
            </Form.Item>
            <Button type="primary" htmlType="submit" loading={saving} block>
              签到
            </Button>
          </Form>
        )}
      </div>
    </div>
  )
}
