/**
 * 全站设计 token —— 唯一来源
 * 对应文档：docs/design/PAMS-UI-设计方案-v3-简约高效.md
 *
 * 为什么用 TS 而不是 CSS 常量文件：
 * antd 的 ConfigProvider 需要 JS 值，CSS 需要变量。以前 App.tsx 里手抄 19 个颜色值、
 * tokens.css 里再抄一遍，两边必然漂移（见方案 §1.2）。这里以 TS 为唯一出处，
 * CSS 变量由 buildTokenCss() 生成后注入 <style>，从机制上消除两套值。
 *
 * v6 主色由用户指定为 rgb(22,104,220)，明暗配色见 docs/design/PAMS-UI-设计方案-v6-行业规范修订.md。
 */

import { SPRINGS, springEasing } from './motion'
import { deriveBrandScale, lighten, parseColor, readableOn, toHex, withAlpha } from './color'

export type ThemeMode = 'light' | 'dark'

/** Document formatting values are fixed content colors, independent of the surrounding UI theme. */
export const documentColors = {
  text: '#000000',
  highlight: '#FFF176',
} as const

/** 材质层级 */
export interface SurfaceTokens {
  /** 页面底 */
  page: string
  /** 内容面：卡片 / 表格 / 表单 / 弹窗 */
  canvas: string
  /** 次级面：表头 / 侧边栏 / 只读区 */
  soft: string
  /** hover / 选中底 / disabled */
  softStrong: string
  /** 凹陷面：输入框、搜索框、代码块（比 canvas 更深，表达「可填入」） */
  sunken: string
  /**
   * 侧边栏专用色 —— 必须与主题一起切换（早期版本把侧边栏固定成深底，
   * 导致切浅色主题时侧边栏不变，属于 bug）。
   * 浅色主题 = 浅底 + 深墨字；深色主题 = 深底 + 白字。两套各自独立定义，
   * 不做「一个值两套主题共用」，否则必然在其中一套里不可读。
   */
  siderBg: string
  /** 侧边栏默认文字/图标 */
  siderText: string
  /** 侧边栏次级文字（品牌区部门名、二级菜单项） */
  siderTextDim: string
  siderBorder: string
  /** 选中项底色（浅色主题用浅主色，深色主题用半透白） */
  siderSelected: string
  /** 悬停底色 */
  siderHover: string
  /** 通用容器边界透明，保留边框几何尺寸以避免布局跳动。 */
  border: string
  /** 输入框等控件边，比 border 深一档 */
  borderStrong: string
  shadow1: string
  shadow2: string
}

export interface BaseTokens extends SurfaceTokens {
  /** 用户指定的蓝色聊天气泡，独立于组织品牌色。 */
  userBubbleBg: string
  /** 主按钮实底、选中竖条、Tab 下划线 */
  brand: string
  brandHover: string
  brandActive: string
  /** 浅色底：选中项背景、焦点光环 */
  brandSoft: string
  /** 当前主题下的蓝色文字/图标（深色下 #0034A2 不可读，需提亮） */
  brandText: string
  /** 记号色：只做小面积竖条/数据点，不做表面、不做正文 */
  accent: string
  /** accent 当文字时的可读版本 */
  accentText: string

  success: string
  successBg: string
  successSoft: string
  warning: string
  warningBg: string
  warningSoft: string
  danger: string
  dangerBg: string
  dangerSoft: string
  info: string
  infoBg: string
  infoSoft: string

  textPrimary: string
  textSecondary: string
  textTertiary: string
  textDisabled: string

  radiusSm: string
  radiusMd: string
  radiusLg: string
  radiusXl: string
  /** 品牌落地容器（登录页等大圆角容器）专用，内页 UI 不用 */
  radius2xl: string
  radiusPill: string

  /** v6 色彩层级与业务分类；独立于组件布局。 */
  canvasElevated: string
  focus: string
  onBrand: string
  categoryActivity: string
  categoryActivityBg: string
  categorySchedule: string
  categoryScheduleText: string
  categoryScheduleBg: string
  categoryDocument: string
  categoryDocumentText: string
  categoryDocumentBg: string
  categoryAssistant: string
  categoryAssistantBg: string
  todayBg: string

  easing: string
  dur: string
}

export const lightTokens: BaseTokens = {
  userBubbleBg: '#1668DC',
  page: '#F5F5F3',
  canvas: '#FCFCFB',
  soft: '#F8F8F6',
  softStrong: '#F0F0EE',
  sunken: '#F5F5F3',
  siderBg: '#F8F8F6',
  siderText: '#263243',
  siderTextDim: '#5B6777',
  siderBorder: 'transparent',
  siderSelected: '#E6F4FF',
  siderHover: '#F0F0EE',
  border: 'transparent',
  borderStrong: '#7C899B',
  shadow1: '0 2px 8px rgba(38,50,67,.06)',
  shadow2: '0 12px 36px rgba(38,50,67,.12)',

  brand: '#1668DC',
  brandHover: '#094BB5',
  brandActive: '#00328F',
  brandSoft: '#E6F4FF',
  brandText: '#1668DC',
  accent: '#C17B20',
  accentText: '#8C520F',

  success: '#267549',
  successBg: '#ECF6EF',
  successSoft: '#ECF6EF',
  warning: '#8C520F',
  warningBg: '#FFF3E0',
  warningSoft: '#FFF3E0',
  danger: '#BB3344',
  dangerBg: '#FDEEF0',
  dangerSoft: '#FDEEF0',
  info: '#1668DC',
  infoBg: '#E6F4FF',
  infoSoft: '#E6F4FF',

  textPrimary: '#263243',
  textSecondary: '#5B6777',
  textTertiary: '#687587',
  textDisabled: '#A6AFBC',

  radiusSm: '4px',
  radiusMd: '6px',
  radiusLg: '8px',
  radiusXl: '10px',
  radius2xl: '24px',
  radiusPill: '999px',

  easing: 'cubic-bezier(.215,.61,.355,1)',
  dur: '140ms',

  canvasElevated: '#FCFCFB',
  focus: '#1668DC',
  onBrand: '#FFFFFF',
  categoryActivity: '#1668DC',
  categoryActivityBg: '#E6F4FF',
  categorySchedule: '#138375',
  categoryScheduleText: '#0E6D62',
  categoryScheduleBg: '#EDF7F3',
  categoryDocument: '#C17B20',
  categoryDocumentText: '#8C520F',
  categoryDocumentBg: '#FFF6E8',
  categoryAssistant: '#625A8F',
  categoryAssistantBg: '#F1EFF7',
  todayBg: '#F0F7FF',
}

/**
 * 深色主题：按用户指定的中性灰阶以微明度分层。
 * 页面 #0B0B0A → 内容 #151515 → 次级面 #1A1A19 → 悬停面 #20201E。
 * 输入面回落到页面灰阶，容器描边透明；正文使用柔白降低眩光。
 */
export const darkTokens: BaseTokens = {
  userBubbleBg: '#1A3F75',
  page: '#0B0B0A',
  canvas: '#151515',
  soft: '#1A1A19',
  softStrong: '#20201E',
  sunken: '#0B0B0A',
  siderBg: '#151515',
  siderText: '#E6EAF0',
  siderTextDim: '#AAB3C2',
  siderBorder: 'transparent',
  siderSelected: '#172C49',
  siderHover: '#20201E',
  border: 'transparent',
  borderStrong: '#707C8E',
  shadow1: '0 2px 8px rgba(11,11,10,.30)',
  shadow2: '0 12px 36px rgba(11,11,10,.40)',

  brand: '#1668DC',
  brandHover: '#1B70E5',
  brandActive: '#094BB5',
  brandSoft: '#172C49',
  brandText: '#82B5FF',
  accent: '#D5A052',
  accentText: '#EDBC6B',

  success: '#7BC89F',
  successBg: '#1C3228',
  successSoft: '#1C3228',
  warning: '#EDBC6B',
  warningBg: '#3A2D1B',
  warningSoft: '#3A2D1B',
  danger: '#F29AAC',
  dangerBg: '#392229',
  dangerSoft: '#392229',
  info: '#82B5FF',
  infoBg: '#172C49',
  infoSoft: '#172C49',

  textPrimary: '#E6EAF0',
  textSecondary: '#AAB3C2',
  textTertiary: '#919EAF',
  textDisabled: '#647080',

  radiusSm: '4px',
  radiusMd: '6px',
  radiusLg: '8px',
  radiusXl: '10px',
  radius2xl: '24px',
  radiusPill: '999px',

  easing: 'cubic-bezier(.215,.61,.355,1)',
  dur: '140ms',

  canvasElevated: '#1A1A19',
  focus: '#86B9FF',
  onBrand: '#FFFFFF',
  categoryActivity: '#82B5FF',
  categoryActivityBg: '#22354F',
  categorySchedule: '#45B49D',
  categoryScheduleText: '#71CDBC',
  categoryScheduleBg: '#20372F',
  categoryDocument: '#D5A052',
  categoryDocumentText: '#EDBC6B',
  categoryDocumentBg: '#352A1C',
  categoryAssistant: '#C0AFDF',
  categoryAssistantBg: '#2E273C',
  todayBg: '#202D41',
}


/** 将旧默认院校蓝迁移到新版；其他组织自定义颜色保留。 */
export function normalizePrimaryColor(primary?: string | null): string {
  const parsed = primary ? parseColor(primary) : null
  if (!parsed) return lightTokens.brand
  const hex = toHex(parsed)
  return hex === '#0034A2' ? lightTokens.brand : hex
}

export const tokensFor = (mode: ThemeMode): BaseTokens => (mode === 'dark' ? darkTokens : lightTokens)

/**
 * 组织自定义主色时，用它派生同一套 brand* token。
 * 这样「统一 UI」不会砍掉多组织主题能力：中性色/语义色全站一致，
 * 只有主色跟着组织走，且 hover/active/浅底/深色可读文字都自动配套。
 */
export function brandOverrides(primary: string, mode: ThemeMode): Partial<BaseTokens> {
  const base = tokensFor(mode)
  const isDark = mode === 'dark'
  const { brand, hover, active } = deriveBrandScale(primary, isDark)
  return {
    brand,
    brandHover: readableOn(hover, base.onBrand, 4.5),
    brandActive: readableOn(active, base.onBrand, 4.5),
    brandSoft: isDark ? withAlpha(brand, 0.18) : lighten(brand, 0.88),
    // 深色主题下主色往往过暗，当文字用要提亮到可读
    brandText: readableOn(brand, isDark ? base.canvas : lighten(brand, 0.88), 4.5),
    focus: readableOn(brand, base.canvas, 3),
  }
}

/** 合并「模式默认 token」与「组织主色派生 token」。 */
export function resolveTokens(mode: ThemeMode, primary?: string): BaseTokens {
  const base = tokensFor(mode)
  if (!primary || !parseColor(primary)) return base
  if (toHex(parseColor(primary)!) === lightTokens.brand) return base
  return { ...base, ...brandOverrides(primary, mode) }
}

/** 字号刻度（10 种 → 7 档） */
export const FONT_SCALE = {
  /** 密集场景的极小字：座位格编号、品牌区部门名、图表刻度 */
  micro: '11px',
  caption: '12px',
  body: '13px',
  bodyLg: '14px',
  title: '16px',
  h2: '20px',
  stat: '24px',
} as const

/**
 * 展示字号：刻意超出正文刻度的少数场景（图标、Hero 数字、空状态大标题）。
 * 单独成档是为了「不滥用」——只有这些用途允许超过 24px，新增前先确认是否真需要。
 */
export const DISPLAY_SCALE = {
  /** 强调符号（如签到成功的 ✓） */
  pageTitle: '26px',
  symbol: '22px',
  /** 卡片级大数字 */
  statXl: '28px',
  /** 页面级大数字（403 状态码、签到成功标题） */
  display: '30px',
  /** Hero 标题（403 主标题） */
  hero: '40px',
  /** 水印/装饰图标 */
  watermark: '64px',
} as const

/** 间距台阶（4px 基准） */
export const SPACE_SCALE = ['4px', '8px', '12px', '16px', '20px', '24px', '32px'] as const

export const FONT_FAMILY =
  "'PingFang SC','Microsoft YaHei','HarmonyOS Sans SC','Noto Sans SC',sans-serif"

/* ============================================================
   CSS 变量名映射 —— 这里的 key 决定生成的变量名，测试会校验无遗漏
   ============================================================ */
export const CSS_VAR_MAP: Record<keyof BaseTokens, string> = {
  userBubbleBg: '--user-bubble-bg',
  page: '--page',
  canvas: '--canvas',
  soft: '--soft',
  softStrong: '--soft-strong',
  sunken: '--sunken',
  siderBg: '--sider-bg',
  siderText: '--sider-text',
  siderTextDim: '--sider-text-dim',
  siderBorder: '--sider-border',
  siderSelected: '--sider-selected',
  siderHover: '--sider-hover',
  border: '--border',
  borderStrong: '--border-strong',
  shadow1: '--shadow-1',
  shadow2: '--shadow-2',
  brand: '--brand',
  brandHover: '--brand-hover',
  brandActive: '--brand-active',
  brandSoft: '--brand-soft',
  brandText: '--brand-text',
  accent: '--accent',
  accentText: '--accent-text',
  success: '--ok',
  successBg: '--ok-bg',
  successSoft: '--ok-soft',
  warning: '--warn',
  warningBg: '--warn-bg',
  warningSoft: '--warn-soft',
  danger: '--danger',
  dangerBg: '--danger-bg',
  dangerSoft: '--danger-soft',
  info: '--info',
  infoBg: '--info-bg',
  infoSoft: '--info-soft',
  textPrimary: '--text-primary',
  textSecondary: '--text-secondary',
  textTertiary: '--text-tertiary',
  textDisabled: '--text-disabled',
  radiusSm: '--r-sm',
  radiusMd: '--r-md',
  radiusLg: '--r-lg',
  radiusXl: '--r-xl',
  radius2xl: '--r-2xl',
  radiusPill: '--r-pill',
  canvasElevated: '--canvas-elevated',
  focus: '--focus',
  onBrand: '--on-brand',
  categoryActivity: '--category-activity',
  categoryActivityBg: '--category-activity-bg',
  categorySchedule: '--category-schedule',
  categoryScheduleText: '--category-schedule-text',
  categoryScheduleBg: '--category-schedule-bg',
  categoryDocument: '--category-document',
  categoryDocumentText: '--category-document-text',
  categoryDocumentBg: '--category-document-bg',
  categoryAssistant: '--category-assistant',
  categoryAssistantBg: '--category-assistant-bg',
  todayBg: '--today-bg',
  easing: '--ease',
  dur: '--dur',
}

const declarations = (t: BaseTokens): string =>
  (Object.keys(CSS_VAR_MAP) as Array<keyof BaseTokens>)
    .map((k) => `  ${CSS_VAR_MAP[k]}: ${t[k]};`)
    .join('\n')

const fontDeclarations = (): string =>
  [
    `  --font-family: ${FONT_FAMILY};`,
    `  --ease-drag: ${springEasing(SPRINGS.drag)};`,
    `  --ease-feedback: ${springEasing(SPRINGS.feedback)};`,
    ...Object.entries(FONT_SCALE).map(([k, v]) => `  --fs-${k === 'bodyLg' ? 'body-lg' : k}: ${v};`),
    ...Object.entries(DISPLAY_SCALE).map(([k, v]) => `  --fs-${k}: ${v};`),
    ...SPACE_SCALE.map((v, i) => `  --sp-${i}: ${v};`),
  ].join('\n')

/**
 * 生成 tokens.css 的内容。
 * 单独导出是为了让单元测试能在 node 环境里把它和 CSS 文件比对，
 * 防止有人直接改 CSS 造成两套值（方案 §4.1 的一致性断言）。
 */
export function buildTokenCss(primary?: string): string {
  return `/* 由 src/styles/tokens.ts 生成，请勿手改 —— 改 tokens.ts 后重新生成 */
:root {
${fontDeclarations()}
}

:root, :root[data-theme='light'] {
${declarations(resolveTokens('light', primary))}
}

/* 深色主题：中性灰阶、无蓝调；微明度分层，无容器描边；凹陷控件（输入框）比容器更深 */
:root[data-theme='dark'] {
${declarations(resolveTokens('dark', primary))}
}
`
}

/** 把 token 变量注入文档，供 CSS 使用。在 main.tsx 渲染前调用一次；组织主色变化时再调用刷新。 */
export function installTokenCss(primary?: string, doc: Document = document): void {
  const id = 'pams-tokens'
  let el = doc.getElementById(id) as HTMLStyleElement | null
  if (!el) {
    el = doc.createElement('style')
    el.id = id
    doc.head.appendChild(el)
  }
  el.textContent = buildTokenCss(primary)
}
