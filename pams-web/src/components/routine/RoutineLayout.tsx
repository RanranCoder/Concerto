import type { ReactNode } from 'react'
import { NavLink } from 'react-router-dom'
import { useAuthStore } from '@/stores/auth'

export default function RoutineLayout({ children }: { children: ReactNode }) {
  const permissions = useAuthStore((s) => s.user?.permissions) ?? []
  const pages = [
    ...(permissions.includes('course:self:manage') || permissions.includes('course:manage') ? [['/routine/course-schedule','课表制作']] : []),
    ...(permissions.includes('schedule:view') ? [['/routine/schedules', '排班'], ['/routine/activity-schedules', '活动排班']] : []),
    ...(permissions.includes('schedule:self:view') ? [['/routine/my-schedules', '我的排班']] : []),

    ...(permissions.includes('attendance:view') || permissions.includes('attendance:self:view') ? [['/routine/attendance', '考勤']] : []),
    ...(permissions.includes('schedule:free_table')
      ? [

          ['/routine/free-schedules', '无课表'],
        ]
      : []),
  ]
  return (
    <div className="routine-v6">
      <nav className="routine-module-nav" aria-label="排班考勤页面">
        {pages.map(([to, name]) => (
          <NavLink key={to} to={to}>
            {name}
          </NavLink>
        ))}
      </nav>
      {children}
    </div>
  )
}

export function RoutineSummary({
  items,
}: {
  items: Array<{
    label: string
    value: number
    note?: string
    attention?: boolean
  }>
}) {
  return (
    <section className="business-summary" aria-label="当前工作摘要">
      {items.map((m) => (
        <div
          className={`business-metric${m.attention ? ' attention' : ''}`}
          key={m.label}
        >
          <span>{m.label}</span>
          <div>
            <strong>{m.value}</strong>
            <small>{m.note}</small>
          </div>
        </div>
      ))}
    </section>
  )
}

export function RoutineNote({
  children,
  warning = false,
}: {
  children: ReactNode
  warning?: boolean
}) {
  return (
    <div className={`routine-note${warning ? ' warning' : ''}`}>{children}</div>
  )
}

const STATUS: Record<string, [string, string]> = {
  DRAFT: ['草稿', 'warning'],
  PUBLISHED: ['已发布', 'brand'],
  ENDED: ['已结束', 'neutral'],
  CANCELLED: ['已取消', 'neutral'],
  PENDING: ['待签到', 'neutral'],
  NORMAL: ['正常', 'success'],
  LATE: ['迟到', 'warning'],
  ABSENT: ['缺勤', 'danger'],
  LEAVE: ['请假', 'brand'],
  EARLY_LEAVE: ['早退', 'warning'],
  MAKEUP: ['补签', 'processing'],
  CANCELED: ['取消', 'neutral'],
}
export function RoutineStatus({ status }: { status?: string | null }) {
  const [label, tone] = STATUS[status ?? ''] ?? [status ?? '未设置', 'neutral']
  return <span className={`tone-tag tone-${tone}`}>{label}</span>
}
