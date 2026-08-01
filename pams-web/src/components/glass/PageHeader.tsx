import { Typography, Space } from 'antd'
import type { ReactNode } from 'react'

interface PageHeaderProps {
  title: string
  description?: string
  extra?: ReactNode
}

export default function PageHeader({ title, description, extra }: PageHeaderProps) {
  return (
    <div className="page-header" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16, flexWrap: 'wrap', gap: 12 }}>
      <div>
        <Typography.Title level={4} style={{ margin: 0, color: 'var(--color-text)' }}>
          {title}
        </Typography.Title>
        {description && (
          <Typography.Paragraph type="secondary" style={{ margin: '4px 0 0', color: 'var(--color-text-secondary)' }}>
            {description}
          </Typography.Paragraph>
        )}
      </div>
      {extra && <Space>{extra}</Space>}
    </div>
  )
}
