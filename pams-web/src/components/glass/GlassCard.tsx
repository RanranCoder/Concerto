import type { CSSProperties, ReactNode } from 'react'

interface GlassCardProps {
  children: ReactNode
  className?: string
  style?: CSSProperties
}

/**
 * 全站通用容器（原 GlassCard）。
 *
 * 命名保留 Glass 是历史原因：约 30 个页面在引用它。视觉已从液态玻璃改为
 * 「canvas 实底 + 1px 描边 + 无阴影」，层级靠描边和间距表达（设计方案 §3.1 / §7）。
 * 卡片一律不投影：密集列表页里阴影会互相叠加成灰雾。
 */
export default function GlassCard({ children, className, style }: GlassCardProps) {
  return (
    <div className={`glass-card${className ? ` ${className}` : ''}`} style={style}>
      {children}
    </div>
  )
}
