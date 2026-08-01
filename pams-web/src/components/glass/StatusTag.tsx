import { Tag } from 'antd'

const STATUS_MAP: Record<string, { text: string; color: string }> = {
  ASSIGNED: { text: '已下达', color: '#DE2910' },
  PLANNING: { text: '排期中', color: '#F5222D' },
  PLAN_REVIEW: { text: '策划审核', color: '#FA8C16' },
  EXECUTING: { text: '执行中', color: '#D4380D' },
  FINISHED: { text: '已完成', color: '#CF1322' },
  ARCHIVED: { text: '已归档', color: '#8C8C8C' },
}

interface StatusTagProps {
  status: string
}

export default function StatusTag({ status }: StatusTagProps) {
  const meta = STATUS_MAP[status]
  if (!meta) return <Tag>{status}</Tag>
  return <Tag color={meta.color}>{meta.text}</Tag>
}
