/**
 * 全站设计 token —— 唯一来源
 * 对应文档：docs/PAMS-UI-设计方案-v3-简约高效.md
 *
 * 为什么用 TS 而不是 CSS 常量文件：
 * antd 的 ConfigProvider 需要 JS 值，CSS 需要变量。以前 App.tsx 里手抄 19 个颜色值、
 * tokens.css 里再抄一遍，两边必然漂移（见方案 §1.2）。这里以 TS 为唯一出处，
 * CSS 变量由 buildTokenCss() 生成后注入 <style>，从机制上消除两套值。
 *
 * 主色取自 https://www.gzccc.edu.cn/ 的 css25/index.css：#0034A2（出现 82 次）。
 */

import { deriveBrandScale, lighten, parseColor, readableOn, withAlpha } from './color'

export type ThemeMode = 'light' | 'dark'

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
  border: string
  /** 输入框等控件边，比 border 深一档 */
  borderStrong: string
  shadow1: string
  shadow2: string
}

export interface BaseTokens extends SurfaceTokens {
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
  radiusPill: string

  easing: string
  dur: string
}

export const lightTokens: BaseTokens = {
  page: '#F5F6F8',
  canvas: '#FFFFFF',
  soft: '#F2F4F8',
  softStrong: '#E7EAF2',
  sunken: '#EDEFF4',
  siderBg: '#F2F4F8',
  siderText: '#1B2438',
  siderTextDim: '#6D747F',
  siderBorder: '#E2E7F0',
  siderSelected: 'rgba(0,52,162,.10)',
  siderHover: '#E7EAF2',
  border: '#E2E7F0',
  borderStrong: '#D3DAE6',
  shadow1: '0 2px 8px rgba(16,24,40,.06)',
  shadow2: '0 8px 24px rgba(16,24,40,.10)',

  brand: '#0034A2',
  brandHover: '#0045D6',
  brandActive: '#002B85',
  brandSoft: '#E7EDFB',
  brandText: '#0034A2',
  accent: '#FFA811',
  accentText: '#9A5B00',

  success: '#1F7A3D',
  successBg: '#EDF7EF',
  successSoft: 'rgba(31,122,61,.10)',
  warning: '#9A5B00',
  warningBg: '#FDF5E6',
  warningSoft: 'rgba(154,91,0,.10)',
  danger: '#C0271A',
  dangerBg: '#FDECEA',
  dangerSoft: 'rgba(192,39,26,.10)',
  info: '#0E7490',
  infoBg: '#E6F4F7',
  infoSoft: 'rgba(14,116,144,.10)',

  textPrimary: '#1B2438',
  textSecondary: '#5A6478',
  textTertiary: '#7A8496',
  textDisabled: '#A8B0BB',

  radiusSm: '4px',
  radiusMd: '6px',
  radiusLg: '8px',
  radiusXl: '10px',
  radiusPill: '999px',

  easing: 'cubic-bezier(.22,1,.36,1)',
  dur: '140ms',
}

/**
 * 深色主题 —— 中性灰阶，无蓝调。
 *
 * 依据（2026-09 调研）：
 *  ① 底色不用纯黑、也不带色相：参考站（bilibili 深色）实测为 #17181A / #242628，
 *     是纯中性灰；带蓝调的底会把整屏染成「蓝色遮罩」，长时间阅读发闷。
 *  ② 层级靠逐级提亮而非阴影：Material/Uxcel 的深色建议是每级提亮 4–6%。
 *     本主题：page #17181A → canvas #1E2024 → soft #24262A → softStrong #2C2F34。
 *  ③ 凹陷控件比容器更深：输入框/搜索框用 softStrong 反向压暗到 #121315，
 *     与参考站搜索框（#0D0E0F）同思路。
 *  ④ 正文不用纯白 #FFF：用 #E6E8EA，降低眩光（参考站正文 #E7E9EB）。
 */
export const darkTokens: BaseTokens = {
  page: '#17181A',
  canvas: '#1E2024',
  soft: '#24262A',
  softStrong: '#2C2F34',
  sunken: '#121315',
  siderBg: '#1F2226',
  siderText: '#FFFFFF',
  siderTextDim: 'rgba(255,255,255,.58)',
  siderBorder: 'rgba(255,255,255,.08)',
  siderSelected: 'rgba(255,255,255,.10)',
  siderHover: 'rgba(255,255,255,.06)',
  border: '#33363B',
  borderStrong: '#41454B',
  shadow1: '0 2px 8px rgba(0,0,0,.40)',
  shadow2: '0 8px 24px rgba(0,0,0,.50)',

  brand: '#2563EB',
  brandHover: '#3B7BF0',
  brandActive: '#1D4FD8',
  brandSoft: 'rgba(59,123,240,.16)',
  brandText: '#7FA6F0',
  accent: '#FFA811',
  accentText: '#E0A854',

  success: '#5FBF7A',
  successBg: '#16241A',
  successSoft: 'rgba(95,191,122,.14)',
  warning: '#E0A854',
  warningBg: '#2A2314',
  warningSoft: 'rgba(224,168,84,.14)',
  danger: '#F0735C',
  dangerBg: '#2A1A17',
  dangerSoft: 'rgba(240,115,92,.14)',
  info: '#38BDF8',
  infoBg: '#12242B',
  infoSoft: 'rgba(56,189,248,.14)',

  textPrimary: '#E6E8EC',
  textSecondary: '#A2AAB6',
  textTertiary: '#8A93A3',
  textDisabled: '#5A626E',

  radiusSm: '4px',
  radiusMd: '6px',
  radiusLg: '8px',
  radiusXl: '10px',
  radiusPill: '999px',

  easing: 'cubic-bezier(.22,1,.36,1)',
  dur: '140ms',
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
    brandHover: hover,
    brandActive: active,
    brandSoft: isDark ? withAlpha(brand, 0.18) : lighten(brand, 0.88),
    // 深色主题下主色往往过暗，当文字用要提亮到可读
    brandText: isDark ? readableOn(brand, base.canvas, 4.5) : brand,
  }
}

/** 合并「模式默认 token」与「组织主色派生 token」。 */
export function resolveTokens(mode: ThemeMode, primary?: string): BaseTokens {
  const base = tokensFor(mode)
  if (!primary || !parseColor(primary)) return base
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
  radiusPill: '--r-pill',
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

/* 深色主题：中性灰阶、无蓝调；层级逐级提亮约 4–6%；凹陷控件（输入框）比容器更深 */
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
