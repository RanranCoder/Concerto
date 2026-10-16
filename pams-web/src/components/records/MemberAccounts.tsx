import { Button } from 'antd'
import { Link } from 'react-router-dom'
import { useAuthStore } from '@/stores/auth'

export default function MemberAccounts(_props: { sessionId?: number }) {
  const canManage = useAuthStore(s => s.user?.managementCapabilities?.includes('user:manage') ?? false)
  return <section className="glass-card records-panel"><h3>成员登录账号</h3><p className="records-note">批量开户将在凭证交付和逐项授权预览接入后开放。目前可在用户管理中逐个创建账号，选择已发布角色并设置初始密码。</p>{canManage && <Link to="/admin/users"><Button type="primary">前往用户管理</Button></Link>}</section>
}
