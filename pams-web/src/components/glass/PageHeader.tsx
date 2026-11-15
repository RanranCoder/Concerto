import { InfoCircleOutlined } from '@ant-design/icons'
import { Button, Space, Tooltip } from 'antd'
import type { ReactNode } from 'react'

interface PageHeaderProps {
  title: string
  description?: ReactNode
  help?: { label: string; text: string }
  extra?: ReactNode
}

/**
 * 页面标题、可选的业务元数据和页面操作。
 */
export default function PageHeader({ title, description, help, extra }: PageHeaderProps) {
  return (
    <header className="page-header">
      <div className="page-header-main">
        <div className="page-header-title-row">
          <h1 className="page-header-title">{title}</h1>
          {help && (
            <Tooltip title={help.text} placement="bottom" trigger={['hover', 'focus', 'click']}>
              <Button type="text" icon={<InfoCircleOutlined aria-hidden="true" />} aria-label={help.label} />
            </Tooltip>
          )}
        </div>
        {description && <p className="page-header-desc">{description}</p>}
      </div>
      {extra && <Space wrap>{extra}</Space>}
    </header>
  )
}
