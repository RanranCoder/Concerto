import { Space } from 'antd'
import type { ReactNode } from 'react'

interface PageHeaderProps {
  title: string
  description?: string
  extra?: ReactNode
}

export default function PageHeader({ title, description, extra }: PageHeaderProps) {
  return (
    <div className="glass-card page-header" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: 20, marginBottom: 16, flexWrap: 'wrap', gap: 12 }}>
      <div>
        <h2 style={{ margin: 0, fontSize: 20, fontWeight: 600, color: 'var(--color-text)' }}>{title}</h2>
        {description && <p style={{ margin: '4px 0 0', color: 'var(--color-text-secondary)' }}>{description}</p>}
      </div>
      {extra && <Space>{extra}</Space>}
    </div>
  )
}
