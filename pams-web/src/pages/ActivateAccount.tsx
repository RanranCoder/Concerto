import { useRef, useState } from 'react'
import { Button, Form, Input } from 'antd'
import axios, { isAxiosError } from 'axios'
import { Link, useLocation } from 'react-router-dom'
import AuthLayout from '@/components/auth/AuthLayout'

export default function ActivateAccount() {
  const location = useLocation()
  const token = new URLSearchParams(location.hash.slice(1)).get('token') ?? ''
  return <AccountSetupForm key={token} token={token} />
}

function AccountSetupForm({ token }: { token: string }) {
  const location = useLocation()
  const [done, setDone] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const running = useRef(false)
  const valid = /^[A-Za-z0-9_-]{43}$/.test(token)

  const submit = async ({ password }: { password: string }) => {
    if (running.current || !valid) return
    running.current = true; setBusy(true); setError('')
    try {
      // Public token exchange uses no stored session: another signed-in account is irrelevant.
      await axios.post('/api/auth/account-setup', { token, password }, { timeout: 30000 })
      setDone(true)
      window.history.replaceState(null, '', location.pathname)
    } catch (err) {
      setError(isAxiosError(err) ? err.response?.data?.message || '暂时无法设置密码，请重试。' : '暂时无法设置密码，请重试。')
    } finally { running.current = false; setBusy(false) }
  }

  return <AuthLayout aside={<div className="welcome-copy"><h1>设置你的密码，<br />加入共同的协作。</h1><p>组织已为你创建账号。<br />完成设置后，使用学号和新密码登录。</p></div>}>
    <div className="login-form-wrap">
      <div className="onboarding-title"><h2>{done ? '账号已激活' : '设置登录密码'}</h2><p>{done ? '使用管理员提供的用户名和刚设置的密码登录。' : '设密链接仅能使用一次，有效期为 48 小时。'}</p></div>
      {done ? <Link to="/login"><Button type="primary" block>前往登录</Button></Link> : !valid ? <>
        <div role="alert" className="onboarding-error">设密链接不完整，请使用管理员交付的完整链接。</div>
        <Link to="/login">返回登录</Link>
      </> : <Form layout="vertical" onFinish={submit} disabled={busy}>
        <Form.Item name="password" label="登录密码" rules={[
          { required: true, min: 8, whitespace: true, message: '请输入至少 8 位密码' },
          { validator: (_, value: string) => !value || new TextEncoder().encode(value).length <= 72
            ? Promise.resolve() : Promise.reject(new Error('密码 UTF-8 编码不能超过 72 字节')) },
        ]}><Input.Password autoComplete="new-password" placeholder="至少 8 位" /></Form.Item>
        <Form.Item name="confirmation" label="确认密码" dependencies={['password']} rules={[
          { required: true, message: '请再次输入密码' },
          ({ getFieldValue }) => ({ validator: (_, value) => !value || getFieldValue('password') === value
            ? Promise.resolve() : Promise.reject(new Error('两次输入的密码不一致')) }),
        ]}><Input.Password autoComplete="new-password" placeholder="再次输入密码" /></Form.Item>
        {error && <div role="alert" className="onboarding-error">{error}</div>}
        <Button type="primary" htmlType="submit" block loading={busy}>设置密码并激活</Button>
        <p className="onboarding-hint">链接过期或已被替换时，请联系开户管理员重新发放。</p>
      </Form>}
    </div>
  </AuthLayout>
}
