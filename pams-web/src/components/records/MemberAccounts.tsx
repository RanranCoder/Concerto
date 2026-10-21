import MemberAccountImport from './MemberAccountImport'
import { Button, Space } from 'antd'
import { Link } from 'react-router-dom'
import { useAuthStore } from '@/stores/auth'

export default function MemberAccounts({ sessionId }: { sessionId?: number }) {
  const canManage = useAuthStore(s => s.user?.managementCapabilities?.includes('user:manage') ?? false)
  return <section className="glass-card records-panel"><h3>成员登录账号</h3><p className="records-note">从花名册批量创建账号，逐人确认角色与权限，再下载个人设密链接交付给对应成员。成员设置密码后即可登录。</p><Space wrap><MemberAccountImport sessionId={sessionId} />{canManage && <Link to="/admin/users"><Button>前往用户管理</Button></Link>}</Space></section>
}
