/**
 * 颜色工具 —— 用于从一个主色派生整套可用色阶。
 *
 * 为什么需要：组织可以在 theme_json 里配置自己的主色（stores/org.ts），
 * 而悬停/按下/浅底/深色主题下的文字色都必须跟着主色走，否则会出现
 * 「主色是绿、hover 还是蓝」这类不一致。这里把派生规则集中一处。
 */

export interface Rgb {
  r: number
  g: number
  b: number
}

const HEX3 = /^#?([0-9a-f])([0-9a-f])([0-9a-f])$/i
const HEX6 = /^#?([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})$/i
const RGB_FN = /^rgba?\(\s*([\d.]+)\s*,\s*([\d.]+)\s*,\s*([\d.]+)/i

/** 解析 #RGB / #RRGGBB / rgb() / rgba()；失败返回 null。 */
export function parseColor(input: string): Rgb | null {
  const s = (input ?? '').trim()
  if (!s) return null

  const m6 = s.match(HEX6)
  if (m6) {
    return {
      r: Number.parseInt(m6[1], 16),
      g: Number.parseInt(m6[2], 16),
      b: Number.parseInt(m6[3], 16),
    }
  }

  const m3 = s.match(HEX3)
  if (m3) {
    return {
      r: Number.parseInt(m3[1] + m3[1], 16),
      g: Number.parseInt(m3[2] + m3[2], 16),
      b: Number.parseInt(m3[3] + m3[3], 16),
    }
  }

  const mFn = s.match(RGB_FN)
  if (mFn) {
    const clamp = (n: number) => Math.max(0, Math.min(255, Math.round(n)))
    return { r: clamp(+mFn[1]), g: clamp(+mFn[2]), b: clamp(+mFn[3]) }
  }

  return null
}

const clamp255 = (n: number) => Math.max(0, Math.min(255, Math.round(n)))

export function toHex({ r, g, b }: Rgb): string {
  return '#' + [r, g, b].map((v) => clamp255(v).toString(16).padStart(2, '0')).join('').toUpperCase()
}

/** 朝白色混合：amount=0 原色，amount=1 纯白。 */
export function lighten(color: string, amount: number): string {
  const c = parseColor(color)
  if (!c) return color
  const t = Math.max(0, Math.min(1, amount))
  return toHex({
    r: c.r + (255 - c.r) * t,
    g: c.g + (255 - c.g) * t,
    b: c.b + (255 - c.b) * t,
  })
}

/** 朝黑色混合：amount=0 原色，amount=1 纯黑。 */
export function darken(color: string, amount: number): string {
  const c = parseColor(color)
  if (!c) return color
  const t = Math.max(0, Math.min(1, amount))
  return toHex({ r: c.r * (1 - t), g: c.g * (1 - t), b: c.b * (1 - t) })
}

/** 带透明度的 rgba 字符串（用于浅底、焦点光环）。 */
export function withAlpha(color: string, alpha: number): string {
  const c = parseColor(color)
  if (!c) return color
  const a = Math.max(0, Math.min(1, alpha))
  return `rgba(${c.r},${c.g},${c.b},${a})`
}

/** WCAG 2.1 相对亮度。 */
export function relativeLuminance(color: string): number {
  const c = parseColor(color)
  if (!c) return 0
  const f = (v: number) => {
    const s = v / 255
    return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4)
  }
  return 0.2126 * f(c.r) + 0.7152 * f(c.g) + 0.0722 * f(c.b)
}

/** WCAG 对比度（1–21）。 */
export function contrastRatio(a: string, b: string): number {
  const l1 = relativeLuminance(a)
  const l2 = relativeLuminance(b)
  const hi = Math.max(l1, l2)
  const lo = Math.min(l1, l2)
  return (hi + 0.05) / (lo + 0.05)
}

/** 深色主题下当文字用的提亮色：至少向白色混到可读，同时保留色相。 */
export function readableOn(color: string, bg: string, minRatio = 4.5): string {
  const c = parseColor(color)
  if (!c) return color
  // 背景偏暗则往白提，偏亮则往黑压
  const goLighter = relativeLuminance(bg) < 0.5
  for (let t = 0; t <= 0.85; t += 0.05) {
    const candidate = goLighter ? lighten(color, t) : darken(color, t)
    if (contrastRatio(candidate, bg) >= minRatio) return candidate
  }
  return goLighter ? lighten(color, 0.85) : darken(color, 0.85)
}

/**
 * 从主色派生按钮/选中态需要的色阶。
 * dark 模式下 hover 要「更亮」、active 要「稍亮」；light 模式下相反。
 */
export function deriveBrandScale(primary: string, isDark: boolean) {
  return {
    brand: primary,
    hover: isDark ? lighten(primary, 0.14) : darken(primary, 0.12),
    active: isDark ? lighten(primary, 0.26) : darken(primary, 0.26),
  }
}
