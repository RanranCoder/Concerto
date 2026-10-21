import type { ActivityPlanVO } from '@/api/activity'

export const PLAN_LABELS = {
  DRAFT: '草稿',
  PENDING: '待审核',
  APPROVED: '已通过',
  REJECTED: '已驳回',
  WITHDRAWN: '已撤回',
}
export const PLAN_TONES = {
  DRAFT: 'neutral',
  PENDING: 'warning',
  APPROVED: 'success',
  REJECTED: 'danger',
  WITHDRAWN: 'neutral',
}
export const BODY_SECTIONS = [
  ['background', '活动背景'],
  ['purpose', '活动目的'],
  ['content', '活动内容'],
  ['flow', '活动流程'],
  ['notice', '活动注意事项'],
  ['emergency', '应急预案'],
  ['budget', '经费预算'],
] as const
const META_SECTIONS = [
  ['nameOverride', '活动名称'],
  ['themeOverride', '活动主题'],
  ['timeOverride', '活动时间'],
  ['locationOverride', '活动地点'],
  ['organizerOverride', '活动组织单位'],
  ['targetOverride', '活动对象'],
] as const

/** Detached parsing only; diff content is rendered as React text, never as HTML. */
export function comparisonText(value: string | null | undefined) {
  const doc = new DOMParser().parseFromString(value ?? '', 'text/html')
  doc.querySelectorAll('script,style,iframe,object').forEach((n) => n.remove())
  doc
    .querySelectorAll('img')
    .forEach((n) =>
      n.replaceWith(doc.createTextNode(`[图片${n.alt ? `：${n.alt}` : ''}]`)),
    )
  doc
    .querySelectorAll('p,li,tr,h1,h2,h3,h4,br')
    .forEach((n) => n.append(doc.createTextNode('\n')))
  return (doc.body.textContent ?? '')
    .replace(/\u200b|\ufeff/g, '')
    .replace(/[ \t]+/g, ' ')
    .replace(/\n\s*\n/g, '\n')
    .trim()
}
interface Section {
  key: string
  label: string
  html: string
}
function sections(plan: ActivityPlanVO): Section[] {
  const rows: Section[] = [...META_SECTIONS, ...BODY_SECTIONS].map(
    ([key, label]) => ({ key, label, html: plan[key] ?? '' }),
  )
  try {
    const order: unknown = JSON.parse(plan.sectionOrder || '[]')
    if (Array.isArray(order) && order.length) {
      const titles: string[] = []
      for (const item of order) {
        if (!item || typeof item.label !== 'string') continue
        const label =
          typeof item.customLabel === 'string' && item.customLabel
            ? item.customLabel
            : item.label
        titles.push(label)
        if (item.field) {
          const section = rows.find((r) => r.key === item.field)
          if (section) section.label = label
        } else if (typeof item.contentHtml === 'string')
          rows.push({
            key: `custom:${item.label}`,
            label,
            html: item.contentHtml,
          })
      }
      rows.push({
        key: 'order',
        label: '章节顺序与标题',
        html: titles.join('\n'),
      })
    }
  } catch {
    /* Legacy documents may not have a serialized outline. */
  }
  return rows
}
export function comparePlans(before: ActivityPlanVO, after: ActivityPlanVO) {
  const old = new Map(sections(before).map((r) => [r.key, r]))
  const next = new Map(sections(after).map((r) => [r.key, r]))
  return [...new Set([...old.keys(), ...next.keys()])].flatMap((key) => {
    const a = old.get(key),
      b = next.get(key)
    const from = comparisonText(a?.html),
      to = comparisonText(b?.html)
    const formatOnly =
      from === to && (a?.html ?? '').trim() !== (b?.html ?? '').trim()
    if (from === to && !formatOnly && a?.label === b?.label) return []
    return [
      { key, label: b?.label ?? a!.label, before: from, after: to, formatOnly },
    ]
  })
}
