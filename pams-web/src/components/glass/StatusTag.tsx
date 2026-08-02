import { Tag } from 'antd'

const STATUS_MAP: Record<string, { text: string; color: string }> = {
  ASSIGNED: { text: '已下达', color: '#DE2910' },
  PLANNING: { text: '排期中', color: '#F5222D' },
  PLAN_REVIEW: { text: '策划审核', color: '#FA8C16' },
  EXECUTING: { text: '执行中', color: '#D4380D' },
  FINISHED: { text: '已完成', color: '#CF1322' },
  ARCHIVED: { text: '已归档', color: '#8C8C8C' },
  // 党员政治面貌（红系）
  共青团员: { text: '共青团员', color: '#FA8C16' },
  入党申请人: { text: '入党申请人', color: '#FA8C16' },
  入党积极分子: { text: '入党积极分子', color: '#F5222D' },
  重点发展对象: { text: '重点发展对象', color: '#D4380D' },
  预备党员: { text: '预备党员', color: '#CF1322' },
  正式党员: { text: '正式党员', color: '#DE2910' },
  群众: { text: '群众', color: '#8C8C8C' },
}

interface StatusTagProps {
  status: string
}

export default function StatusTag({ status }: StatusTagProps) {
  const meta = STATUS_MAP[status]
  if (!meta) return <Tag>{status}</Tag>
  return <Tag color={meta.color}>{meta.text}</Tag>
}
