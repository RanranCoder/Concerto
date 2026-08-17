import { App, Form, Input, Button } from 'antd'
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
      <div className="login-bg" aria-hidden="true" />
      <div className="login-brand">
        <h1 className="login-brand-title">党建办公室</h1>
        <p className="login-brand-slogan">
          不忘初心<span>|</span>砥砺前行<span>|</span>逐梦党建<span>|</span>与我同行
        </p>
      </div>

      <div className="login-card">
        <img className="login-card-logo" src="/hello-kitty.png" alt="" />
        <h2 className="login-card-title">欢迎回来</h2>
        <p className="login-card-subtitle">登录继续你的工作</p>
        <Form onFinish={onFinish} size="large">
          <Form.Item name="username" rules={[{ required: true, message: '请输入用户名' }]}>
            <Input prefix={<UserOutlined />} placeholder="用户名" />
          </Form.Item>
          <Form.Item name="password" rules={[{ required: true, message: '请输入密码' }]}>
            <Input.Password prefix={<LockOutlined />} placeholder="密码" />
          </Form.Item>
          <Form.Item>
            <Button className="login-submit" type="primary" htmlType="submit" block>登 录</Button>
          </Form.Item>
        </Form>
      </div>
    </div>
  )
}
