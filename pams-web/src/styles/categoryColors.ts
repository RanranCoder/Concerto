/**
 * 分类identity色板 —— 唯一来源
 *
 * 与 tokens.ts 的区别（重要）：
 *   tokens.ts    语义色：主色、成功/警告/危险、灰阶层级 —— 随明暗主题与组织主色变化
 *   本文件        分类色：座位区、材料类型、产物类型、协作者角色 —— 用于「区分身份」，
 *                 不表达状态，因此刻意使用固定色值（走主题会让 A 区和 B 区换主题后难分辨）
 *
 * 这些值以前散落在 SeatLayoutEditor / MaterialList / LingsiArtifactCard / 协作视图 等
 * 文件里各写一份，同一个「活动」色在不同页面可能不一致；集中在这里保证一致。
 */

/** 分类色：固定 hex，不随主题变化 */
export type CategoryColor = string

/** 座位区配色（座位表编辑器 / 座位图 / Excel 导入共用） */
export const SEAT_ZONE_COLORS: Record<string, CategoryColor> = {
  guest: '#f5222d',
  party: '#fa541c',
  activist: '#faad14',
  develop: '#52c41a',
  probationary: '#13c2c2',
  full: '#1677ff',
  staff: '#722ed1',
  media: '#eb2f96',
  audience: '#8c8c8c',
  invited: '#597ef7',
}

/** 座位区默认色（未指定颜色时） */
export const SEAT_DEFAULT_COLOR: CategoryColor = SEAT_ZONE_COLORS.full

/** 座位区默认色兜底（Excel 导入时给未识别类型） */
export const SEAT_UNKNOWN_COLOR: CategoryColor = '#8C9AAB'

/**
 * 座位表「过道」斜纹填充。
 * 这里保留写实色：它由两个灰阶交替构成斜纹，用 CSS 变量拼不出来
 * （repeating-linear-gradient 的端点不能是 var() 套 var() 再要求明暗两套对比）。
 */
export const SEAT_AISLE_HATCH =
  'repeating-linear-gradient(45deg, #e5e7eb, #e5e7eb 4px, #d1d5db 4px, #d1d5db 8px)'

/** 材料业务类型配色 */
export const MATERIAL_TYPE_COLORS: Record<string, CategoryColor> = {
  PLAN: '#3f6fbf',
  SUMMARY: '#4f7a52',
  SIGNIN: '#a9701a',
  PHOTO: '#7f77dd',
  OTHER: '#6b7280',
}

/** 灵思产物类型配色 */
export const ARTIFACT_TYPE_COLORS: Record<string, CategoryColor> = {
  activity: '#c8703f',
  member: '#3f6fbf',
  article: '#3f6fbf',
  seatLayout: '#6b7a8f',
  announcement: '#a9701a',
  notification: '#7f77dd',
  plan: '#4f7a52',
}

/** 产物类型兜底色 */
export const ARTIFACT_FALLBACK_COLOR: CategoryColor = '#6b7280'

/**
 * 多 Agent 协作者身份色。
 * 注意：协调者用主色（它是「当前主控」，语义上属于主色），其余为身份分类色。
 */
export const AGENT_ROLE_COLORS: Record<string, CategoryColor> = {
  coordinator: 'var(--brand)',
  retrieval: '#4a6fa5',
  planning: '#5a7a5a',
  writing: '#8a6d3b',
  scheduling: '#5a6a7a',
  data: '#6a6a6a',
  reviewer: '#7a4a5a',
}

/** 协作者兜底色 */
export const AGENT_FALLBACK_COLOR: CategoryColor = '#6a6a6a'

/** 协作者头像用的单字简称（协作内联进灵思后，过程区行与团队面板共用） */
export const AGENT_SHORT: Record<string, string> = {
  coordinator: '协',
  retrieval: '检',
  planning: '策',
  writing: '文',
  scheduling: '排',
  data: '事',
  reviewer: '审',
  generalist: '通',
}

/** 协作者身份色（带兜底） */
export const agentColorOf = (name: string): CategoryColor =>
  AGENT_ROLE_COLORS[name] ?? AGENT_FALLBACK_COLOR

/** 协作者单字简称（带兜底：取展示名首字） */
export const agentShortOf = (name: string, fallback = ''): string =>
  AGENT_SHORT[name] ?? (fallback || name).slice(0, 1)
