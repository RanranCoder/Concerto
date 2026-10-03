import { Button } from 'antd'
import { useNavigate } from 'react-router-dom'
import { ArrowLeftOutlined, LockOutlined } from '@ant-design/icons'
import GlassCard from '@/components/glass/GlassCard'

export default function Forbidden() {
  const navigate = useNavigate()
  return (
    <div
      style={{
        minHeight: '70vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
      }}
    >
      <GlassCard style={{ padding: '40px 48px', textAlign: 'center', maxWidth: 420 }}>
        <div
          style={{
            width: 64,
            height: 64,
            margin: '0 auto 16px',
            borderRadius: '50%',
            background: 'var(--brand)',
            color: '#fff',
            fontSize: 'var(--fs-display)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
          }}
        >
          <LockOutlined />
        </div>
        <div style={{ fontSize: 'var(--fs-hero)', fontWeight: 700, color: 'var(--text-primary)', lineHeight: 1.2 }}>
          403
        </div>
        <div style={{ fontSize: 16, color: 'var(--text-primary)', margin: '8px 0 4px' }}>无权访问</div>
        <div style={{ fontSize: 14, color: 'var(--text-secondary)', marginBottom: 24 }}>
          您的当前角色没有访问该页面的权限，请联系指导老师或主任开通。
        </div>
        <Button type="primary" icon={<ArrowLeftOutlined />} onClick={() => navigate('/')}>
          返回首页
        </Button>
      </GlassCard>
    </div>
  )
}
