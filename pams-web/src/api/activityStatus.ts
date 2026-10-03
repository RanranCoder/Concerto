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

/**
 * status → 色调名（StatusTag 用；取值见 components/glass/StatusTag.tsx 的 StatusTone）。
 * 状态语义映射：已下达=待办 / 排期中=进行中 / 策划审核=警示 / 执行中=定位 / 已完成=成功 / 已归档=中性。
 */
export const ACTIVITY_STATUS_TONE: Record<string, string> = {
  ASSIGNED: 'warning',
  PLANNING: 'processing',
  PLAN_REVIEW: 'warning',
  EXECUTING: 'brand',
  FINISHED: 'success',
  ARCHIVED: 'neutral',
}

/**
 * @deprecated 实底彩色标签已废弃（设计方案 §6.2：统一改为浅底 tone 标签）。
 * 仅保留给尚未迁移的图表/图例（Dashboard 活动动态）使用。新代码请用 ACTIVITY_STATUS_TONE。
 */
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

/** 活动类型 → 中文 label（ActivityEdit 下拉 / ActivityDetail 展示 / ActivityList 列表共用，唯一来源） */
export const ACTIVITY_TYPE_MAP: Record<string, string> = {
  PARTY_LESSON: '党课',
  DATE: '主题团日',
  PARTY_DAY: '主题党日',
  COMPETITION: '竞赛',
  VOLUNTEER: '志愿服务',
  LECTURE: '讲座',
  MEETING: '会议',
  OTHER: '其他',
}

/** 活动类型下拉选项（value=枚举，label=中文） */
export const ACTIVITY_TYPE_OPTIONS = Object.entries(ACTIVITY_TYPE_MAP).map(([value, label]) => ({ value, label }))
