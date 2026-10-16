import { ACTIVITY_STATUS_LABEL, ACTIVITY_STATUS_TONE } from '@/api/activityStatus'

/**
 * 状态标签色调：浅底 + 同色文字 + 无边框（设计方案 §6.2）。
 * 一律用 CSS 变量取值，明暗主题与组织主色都能自动跟随；
 * 页面里不要再写实底彩色标签（太重，且主题切换会失真）。
 */
export type StatusTone = 'neutral' | 'brand' | 'processing' | 'success' | 'warning' | 'danger'

/** 党员政治面貌 → 色调 */
const PARTY_STATUS_MAP: Record<string, { text: string; tone: StatusTone }> = {
  共青团员: { text: '共青团员', tone: 'warning' },
  入党申请人: { text: '入党申请人', tone: 'warning' },
  入党积极分子: { text: '入党积极分子', tone: 'processing' },
  重点发展对象: { text: '重点发展对象', tone: 'processing' },
  预备党员: { text: '预备党员', tone: 'brand' },
  正式党员: { text: '正式党员', tone: 'brand' },
  群众: { text: '群众', tone: 'neutral' },
}

/** 活动状态（唯一来源在 api/activityStatus.ts）+ 党务状态 */
const STATUS_MAP: Record<string, { text: string; tone: StatusTone }> = {
  DRAFT: { text: '草稿', tone: 'neutral' },
  PENDING: { text: '待审', tone: 'warning' },
  APPROVED: { text: '待发布', tone: 'brand' },
  PUBLISHED: { text: '已发布', tone: 'success' },
  REJECTED: { text: '被驳回', tone: 'danger' },
  ...Object.fromEntries(
    Object.keys(ACTIVITY_STATUS_LABEL).map((key) => [
      key,
      { text: ACTIVITY_STATUS_LABEL[key], tone: (ACTIVITY_STATUS_TONE[key] ?? 'neutral') as StatusTone },
    ]),
  ),
  ...PARTY_STATUS_MAP,
}

interface StatusTagProps {
  status: string
}

export default function StatusTag({ status }: StatusTagProps) {
  const meta = STATUS_MAP[status]
  if (!meta) return <span className="tone-tag tone-neutral">{status}</span>
  return <span className={`tone-tag tone-${meta.tone}`}>{meta.text}</span>
}
