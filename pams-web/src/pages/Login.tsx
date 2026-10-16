import { useEffect, useMemo, useRef, useState } from 'react'
import { Form, Input, Button } from 'antd'
import { isAxiosError } from 'axios'
import { LockOutlined, UserOutlined, ApartmentOutlined, CheckOutlined } from '@ant-design/icons'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { useAuthStore } from '@/stores/auth'
import { loginApi } from '@/api/auth'
import { listPublicOrgs, type PublicOrg } from '@/api/org'
import { DEFAULT_PRIMARY_COLOR } from '@/stores/org'
import { normalizePrimaryColor } from '@/styles/tokens'
import GlassSelect from '@/components/glass/GlassSelect'
import AuthLayout from '@/components/auth/AuthLayout'

const ORG_KEY = 'pams.login.orgId'

function orgPrimaryColor(themeJson: string | null): string {
  try {
    const primary = JSON.parse(themeJson || '{}').primaryColor
    return typeof primary === 'string' ? normalizePrimaryColor(primary) : DEFAULT_PRIMARY_COLOR
  } catch { return DEFAULT_PRIMARY_COLOR }
}

export default function Login() {
  const navigate = useNavigate()
  const location = useLocation()
  const created = location.state as { adminUsername?: string; orgId?: number; orgName?: string } | null
  const setLogin = useAuthStore((s) => s.setLogin)
  const [orgs, setOrgs] = useState<PublicOrg[]>([])
  const [orgStatus, setOrgStatus] = useState<'loading' | 'ready' | 'error'>('loading')
  const [orgId, setOrgId] = useState<number | null>(() => {
    const raw = Number(localStorage.getItem(ORG_KEY))
    return created?.orgId ?? (Number.isFinite(raw) && raw > 0 ? raw : null)
  })
  const [loading, setLoading] = useState(false)
  const busy = useRef(false)
  const [error, setError] = useState('')

  const loadOrgs = () => {
    setOrgStatus('loading')
    listPublicOrgs().then((data) => { setOrgs(data); setOrgStatus('ready') })
      .catch(() => setOrgStatus('error'))
  }
  useEffect(loadOrgs, [])

  const selected = useMemo(() => orgs.find((o) => o.id === orgId) ?? orgs[0] ?? null, [orgs, orgId])
  const brand = selected ? orgPrimaryColor(selected.themeJson) : DEFAULT_PRIMARY_COLOR
  const orgLabel = selected ? selected.shortName || selected.name : '你的组织工作台'

  const onFinish = async (values: { username: string; password: string }) => {
    if (busy.current) return
    busy.current = true
    setLoading(true)
    setError('')
    try {
      const data = await loginApi({ ...values, username: values.username.trim() })
      setLogin(data)
      navigate('/', { replace: true })
    } catch (err) {
      setError(isAxiosError(err)
        ? err.response?.data?.message || '暂时无法登录，请检查网络后重试'
        : err instanceof Error ? err.message : '登录失败，请检查账号和密码后重试')
    } finally { busy.current = false; setLoading(false) }
  }

  return (
    <AuthLayout brand={brand} aside={<>
      <div className="welcome-copy"><h1>把每一份热爱，<br />汇成共同的成果。</h1><p>从一次活动到一届传承，<br />让组织里的协作，在这里有序发生。</p></div>
      <div className="collaboration-map" aria-hidden="true">
        <div className="map-root"><ApartmentOutlined /><span>{orgLabel}</span></div>
        <div className="map-branches"><span>组织与成员</span><span>活动与协作</span><span>资料与传承</span></div>
        <div className="map-task"><span className="map-check"><CheckOutlined /></span><div><strong>每一件事，都有着落</strong><p>连接成员，让进展清晰可见</p></div><span className="map-avatars"><i>协</i><i>作</i><i>力</i></span></div>
      </div>
      <p className="welcome-note">众智协奏，事事闭环。</p>
    </>}>
      <div className="login-form-wrap">
        <div className="onboarding-title"><h2>欢迎回来</h2><p>登录协奏，继续你的组织工作。</p></div>
        {created?.orgName && <div className="onboarding-notice" role="status">「{created.orgName}」已创建，使用管理员账号登录即可开始。</div>}
        {orgStatus === 'loading' && <p className="onboarding-hint" role="status">正在获取组织信息…</p>}
        {orgStatus === 'error' && <div className="onboarding-hint">组织信息暂时未加载，仍可使用账号登录。<button type="button" onClick={loadOrgs}>重新加载</button></div>}
        {selected && <div className="login-org">
          <span className="login-org-mark" aria-hidden="true">{selected.logoUrl ? <img src={selected.logoUrl} alt="" /> : orgLabel.charAt(0)}</span>
          <div><span className="login-org-label">组织展示</span>{orgs.length > 1 ? <GlassSelect className="login-org-select" ariaLabel="组织展示" value={selected.id}
            options={orgs.map((o) => ({ value: o.id, label: o.shortName || o.name }))}
            onChange={(id) => { setOrgId(id); localStorage.setItem(ORG_KEY, String(id)) }} /> : <strong>{orgLabel}</strong>}</div>
        </div>}
        <p className="onboarding-hint login-account-hint">账号由组织管理员分配，登录后自动进入所属组织。</p>
        <Form name="login" layout="vertical" requiredMark={false} onFinish={onFinish} initialValues={{ username: created?.adminUsername }} disabled={loading}>
          <Form.Item label="用户名" name="username" rules={[{ required: true, whitespace: true, message: '请输入用户名' }]}>
            <Input prefix={<UserOutlined />} placeholder="请输入用户名 / 学号" autoComplete="username" autoFocus={!created?.adminUsername} />
          </Form.Item>
          <Form.Item label="密码" name="password" rules={[{ required: true, message: '请输入密码' }]}>
            <Input.Password prefix={<LockOutlined />} placeholder="请输入密码" autoComplete="current-password" autoFocus={!!created?.adminUsername} />
          </Form.Item>
          {error && <div className="onboarding-error" role="alert">{error}</div>}
          <Button type="primary" htmlType="submit" block loading={loading} className="onboarding-primary">{loading ? '正在登录…' : '登录工作台'}</Button>
        </Form>
        <p className="login-help">忘记账号或密码？请联系组织管理员。</p>
        <div className="login-create"><div><strong>让团队在这里相聚</strong><p>为你的学生组织创建专属工作台</p></div><Link to="/register">创建组织</Link></div>
      </div>
    </AuthLayout>
  )
}
