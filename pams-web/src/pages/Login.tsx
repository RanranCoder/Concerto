import { App, Form, Input, Button, Typography } from 'antd'
import { LockOutlined, UserOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import { useAuthStore } from '@/stores/auth'
import { loginApi } from '@/api/auth'

export default function Login() {
  const { message } = App.useApp()
  const navigate = useNavigate()
  const setLogin = useAuthStore((s) => s.setLogin)

  const onFinish = async (values: { username: string; password: string }) => {
    try {
      const data = await loginApi(values)
      setLogin(data)
      message.success('登录成功')
      navigate('/', { replace: true })
    } catch { /* http 拦截已提示 */ }
  }

  return (
    <div className="login-page">
      <div className="glass-card login-card">
        <Typography.Title level={3} style={{ textAlign: 'center', color: 'var(--color-text)' }}>
          党务管理系统
        </Typography.Title>
        <Typography.Paragraph style={{ textAlign: 'center', color: 'var(--color-text-secondary)' }}>
          信息与智能工程学院党建办公室
        </Typography.Paragraph>
        <Form onFinish={onFinish} size="large">
          <Form.Item name="username" rules={[{ required: true, message: '请输入用户名' }]}>
            <Input prefix={<UserOutlined />} placeholder="用户名" />
          </Form.Item>
          <Form.Item name="password" rules={[{ required: true, message: '请输入密码' }]}>
            <Input.Password prefix={<LockOutlined />} placeholder="密码" />
          </Form.Item>
          <Form.Item>
            <Button type="primary" htmlType="submit" block>登 录</Button>
          </Form.Item>
        </Form>
      </div>
    </div>
  )
}
