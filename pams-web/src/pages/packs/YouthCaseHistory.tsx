import { Skeleton } from 'antd'
import { LoadError } from '@/components/admin/AdminSurface'
import { dateLabel, usePackQuery } from './PackSurface'

const LABELS: Record<string, string> = { SUBMITTED: '提交审核', APPROVED: '审核通过', RETURNED: '退回补充', ARCHIVED: '归档', RECEIVED: '党务接收' }

export default function YouthCaseHistory({ id }: { id: number }) {
  const history = usePackQuery<{ id: number; action: string; note?: string; actor?: string; occurredAt: string }[]>(`/youth/cases/${id}/history`)
  if (history.error) return <LoadError message={history.error} retry={history.reload} />
  if (!history.data) return <Skeleton title={false} paragraph={{ rows: 2 }} />
  return <section aria-label="办理记录">
    <h3>办理记录</h3>
    <ul className="pack-rows">{history.data.map(event => <li key={event.id}><div>
      <h4>{LABELS[event.action] ?? event.action}</h4>
      <p>{dateLabel(event.occurredAt)}{event.actor && ` · ${event.actor}`}</p>
      {event.note && <p className="pack-note">{event.note}</p>}
    </div></li>)}</ul>
  </section>
}
