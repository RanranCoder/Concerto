import { Typography } from 'antd'
import GlassCard from '@/components/glass/GlassCard'

export default function Dashboard() {
  return (
    <GlassCard style={{ padding: 24 }}>
      <Typography.Title level={4} style={{ margin: 0 }}>
        仪表盘
      </Typography.Title>
      <Typography.Paragraph style={{ marginTop: 12, color: 'var(--color-text-secondary)' }}>
        数据概览与待办汇总，Task 28 实现。
      </Typography.Paragraph>
    </GlassCard>
  )
}
