import { Space } from 'antd'
import type { ReactNode } from 'react'

interface PageHeaderProps {
  title: string
  description?: ReactNode
  extra?: ReactNode
}

/**
 * 页面标题区。统一为「无容器」形态：标题 + 描述 + 右侧操作，
 * 靠下边框与间距与内容分区，不再套一层卡片（设计方案 §6 B/C/D 模式）。
 * 样式在 global.css 的 .page-header 中，避免内联值与 token 脱节。
 */
export default function PageHeader({ title, description, extra }: PageHeaderProps) {
  return (
    <div className="page-header">
      <div className="page-header-main">
        <h2 className="page-header-title">{title}</h2>
        {description && <p className="page-header-desc">{description}</p>}
      </div>
      {extra && <Space wrap>{extra}</Space>}
    </div>
  )
}
