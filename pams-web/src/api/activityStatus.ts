/**
 * 活动状态常量（唯一来源）：ActivityList / Dashboard / StatusTag 共用。
 * 状态机：ASSIGNED→PLANNING→PLAN_REVIEW→EXECUTING→FINISHED→ARCHIVED
 */
export const ACTIVITY_STATUS_OPTIONS: Array<{ value: string; label: string }> = [
  { value: 'ASSIGNED', label: '已下达' },
  { value: 'PLANNING', label: '排期中' },
  { value: 'PLAN_REVIEW', label: '策划审核' },
  { value: 'EXECUTING', label: '执行中' },
  { value: 'FINISHED', label: '已完成' },
  { value: 'ARCHIVED', label: '已归档' },
]

/** status → 中文 label（下拉选项 / 进度消息共用） */
export const ACTIVITY_STATUS_LABEL: Record<string, string> = Object.fromEntries(
  ACTIVITY_STATUS_OPTIONS.map((o) => [o.value, o.label]),
)

/** status → Tag 红系配色（StatusTag / Dashboard 活动动态共用） */
export const ACTIVITY_STATUS_COLOR: Record<string, string> = {
  ASSIGNED: '#DE2910',
  PLANNING: '#F5222D',
  PLAN_REVIEW: '#FA8C16',
  EXECUTING: '#D4380D',
  FINISHED: '#CF1322',
  ARCHIVED: '#8C8C8C',
}

/** 活动类型枚举（ActivityEdit 独立编辑页复用；与 ActivityDetail/ActivityList 的 TYPE_MAP 键一致） */
export const ACTIVITY_TYPES = [
  'PARTY_LESSON',
  'DATE',
  'PARTY_DAY',
  'COMPETITION',
  'VOLUNTEER',
  'LECTURE',
  'MEETING',
  'OTHER',
] as const
