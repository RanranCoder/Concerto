import { Tag } from 'antd'
import { ACTIVITY_STATUS_LABEL, ACTIVITY_STATUS_COLOR } from '@/api/activityStatus'

/** 党员政治面貌（红系） */
const PARTY_STATUS_MAP: Record<string, { text: string; color: string }> = {
  共青团员: { text: '共青团员', color: '#FA8C16' },
  入党申请人: { text: '入党申请人', color: '#FA8C16' },
  入党积极分子: { text: '入党积极分子', color: '#F5222D' },
  重点发展对象: { text: '重点发展对象', color: '#D4380D' },
  预备党员: { text: '预备党员', color: '#CF1322' },
  正式党员: { text: '正式党员', color: '#DE2910' },
  群众: { text: '群众', color: '#8C8C8C' },
}

/** 活动状态（Task 13 minor 去重：共享常量）。多一层展开保证 StatusTag 的 text/color 结构。 */
const ACTIVITY_STATUS_MAP: Record<string, { text: string; color: string }> = Object.fromEntries(
  Object.keys(ACTIVITY_STATUS_LABEL).map((key) => [
    key,
    { text: ACTIVITY_STATUS_LABEL[key], color: ACTIVITY_STATUS_COLOR[key] ?? '#8C8C8C' },
  ]),
)

const STATUS_MAP: Record<string, { text: string; color: string }> = {
  ...ACTIVITY_STATUS_MAP,
  ...PARTY_STATUS_MAP,
}

interface StatusTagProps {
  status: string
}

export default function StatusTag({ status }: StatusTagProps) {
  const meta = STATUS_MAP[status]
  if (!meta) return <Tag>{status}</Tag>
  return <Tag color={meta.color}>{meta.text}</Tag>
}
