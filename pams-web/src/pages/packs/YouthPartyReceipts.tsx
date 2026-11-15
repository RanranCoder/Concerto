import { Skeleton } from 'antd'
import { LoadError } from '@/components/admin/AdminSurface'
import { dateLabel, usePackQuery } from './PackSurface'
import YouthCaseHistory from './YouthCaseHistory'

export default function YouthPartyReceipts({ memberId }: { memberId: number }) {
  const query = usePackQuery<{ id: number; yearNo: number; branchName?: string; submission: string;
    decision?: string; receivedAt: string }[]>(`/youth/party-receipts/${memberId}`)
  if (query.error) return <LoadError message={query.error} retry={query.reload} />
  if (!query.data) return <Skeleton title={false} paragraph={{ rows: 2 }} />
  if (!query.data.length) return <p>暂无已接收的推优材料。</p>
  return <ul className="pack-rows">{query.data.map(receipt => <li key={receipt.id}>
    <details><summary>{receipt.yearNo} 年推优材料 · {receipt.branchName || '支部未标注'} · {dateLabel(receipt.receivedAt)} 接收</summary>
      <p className="pack-note">{receipt.submission}</p><p>审核意见：{receipt.decision || '—'}</p>
      <YouthCaseHistory id={receipt.id} />
    </details>
  </li>)}</ul>
}
