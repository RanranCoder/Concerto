import type { ReactNode } from 'react'

export default function RoutineLayout({ children }: { children: ReactNode }) {
  return <div className="routine-v6">{children}</div>
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
