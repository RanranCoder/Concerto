import type { CSSProperties, ReactNode } from 'react'

interface GlassCardProps {
  children: ReactNode
  className?: string
  style?: CSSProperties
}

export default function GlassCard({ children, className, style }: GlassCardProps) {
  return (
    <div className={`glass-card${className ? ` ${className}` : ''}`} style={style}>
      {children}
    </div>
  )
}
