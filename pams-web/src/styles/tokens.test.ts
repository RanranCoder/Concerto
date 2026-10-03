import { describe, it, expect } from 'vitest'
import { readFileSync, readdirSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  buildTokenCss,
  CSS_VAR_MAP,
  lightTokens,
  darkTokens,
  resolveTokens,
  type BaseTokens,
} from '@/styles/tokens'
import { contrastRatio } from '@/styles/color'

// 直接读磁盘文件：?raw 在 vitest 的 node 环境下拿到空串，不可靠
const tokenCss = readFileSync(fileURLToPath(new URL('./tokens.css', import.meta.url)), 'utf8')

/**
 * 防止「一套值抄两份」重新出现（设计方案 §1.2 / §4.1）：
 * tokens.css 必须与 tokens.ts 逐项一致，谁都不能单独改。
 */
function blockVars(css: string, selector: string): Record<string, string> {
  // 抓取指定选择器后面的第一个 { ... } 块
  const idx = css.indexOf(selector)
  if (idx < 0) throw new Error(`选择器未找到: ${selector}`)
  const open = css.indexOf('{', idx)
  const close = css.indexOf('}', open)
  const body = css.slice(open + 1, close)
  const vars: Record<string, string> = {}
  for (const line of body.split(';')) {
    const m = line.match(/(--[\w-]+)\s*:\s*(.+)/)
    if (m) vars[m[1]] = m[2].trim()
  }
  return vars
}

function expectTokensMatchCss(css: string, selector: string, t: BaseTokens) {
  const vars = blockVars(css, selector)
  for (const [key, varName] of Object.entries(CSS_VAR_MAP)) {
    const expected = t[key as keyof BaseTokens]
    expect(vars[varName], `${varName}（${key}）`)!.toBe(expected)
  }
}

describe('token 单一来源一致性', () => {
  it('tokens.css 的浅色块与 lightTokens 逐项一致', () => {
    expectTokensMatchCss(tokenCss, ":root, :root[data-theme='light']", lightTokens)
  })

  it("tokens.css 的深色块与 darkTokens 逐项一致", () => {
    expectTokensMatchCss(tokenCss, ":root[data-theme='dark']", darkTokens)
  })

  it('磁盘上的 tokens.css 就是 buildTokenCss() 的产物（无手改痕迹）', () => {
    // 逐行比对，避免块级比对漏掉块外的改动（如新增了手写规则）
    const normalize = (s: string) => s.replace(/\r\n/g, '\n').trim()
    expect(normalize(tokenCss)).toBe(normalize(buildTokenCss()))
  })

  it('两套主题定义了完全相同的变量集合（不会漏掉某个 token）', () => {
    const light = Object.keys(blockVars(tokenCss, ":root, :root[data-theme='light']")).sort()
    const dark = Object.keys(blockVars(tokenCss, ":root[data-theme='dark']")).sort()
    expect(dark).toEqual(light)
    expect(light.length).toBe(Object.keys(CSS_VAR_MAP).length)
  })
})

/**
 * 组织可在 theme_json 配置自己的主色（stores/org.ts），
 * 这些用例保证「统一 UI」没有砍掉多组织主题能力。
 */
describe('组织自定义主色派生', () => {
  const GREEN = '#1E7A46'

  it('不传主色时与默认 token 完全一致', () => {
    expect(resolveTokens('light')).toEqual(lightTokens)
    expect(resolveTokens('dark')).toEqual(darkTokens)
  })

  it('传入主色只改 brand 系列，中性色与语义色不变', () => {
    const t = resolveTokens('light', GREEN)
    expect(t.brand).toBe(GREEN)
    expect(t.page).toBe(lightTokens.page)
    expect(t.textPrimary).toBe(lightTokens.textPrimary)
    expect(t.danger).toBe(lightTokens.danger)
    expect(t.success).toBe(lightTokens.success)
  })

  it('hover / active 由主色派生，且互不相同', () => {
    const t = resolveTokens('light', GREEN)
    const distinct = new Set([t.brand, t.brandHover, t.brandActive])
    expect(distinct.size).toBe(3)
  })

  it('浅色主题下主色文字在其浅底上达到 AA（≥4.5:1）', () => {
    for (const primary of ['#0034A2', GREEN, '#7A3BB5', '#B02A2A']) {
      const t = resolveTokens('light', primary)
      expect(contrastRatio(t.brandText, t.brandSoft), `${primary} on ${t.brandSoft}`).toBeGreaterThanOrEqual(4.5)
    }
  })

  it('深色主题下主按钮白字达到 AA（≥4.5:1）', () => {
    for (const primary of ['#0034A2', GREEN, '#7A3BB5']) {
      const t = resolveTokens('dark', primary)
      expect(contrastRatio('#FFFFFF', t.brand), `white on ${t.brand}`).toBeGreaterThanOrEqual(4.5)
    }
  })

  it('非法主色直接回退默认，不产生破坏性输出', () => {
    expect(resolveTokens('light', 'not-a-color')).toEqual(lightTokens)
    expect(resolveTokens('light', '')).toEqual(lightTokens)
  })

  it('buildTokenCss(primary) 输出合法的 CSS 变量覆盖', () => {
    const css = buildTokenCss(GREEN)
    const light = blockVars(css, ":root, :root[data-theme='light']")
    expect(light['--brand']).toBe(GREEN)
    // 其余变量仍齐全，不会因为覆盖而少生成
    expect(Object.keys(light).length).toBe(Object.keys(CSS_VAR_MAP).length)
  })
})

/**
 * 防止「引用了不存在的 CSS 变量」——这类 bug 不报错、不崩，只是样式静默失效。
 * 本轮统一时就踩到过：--easing / --radius-md / --radius-lg 在改名后成了死引用。
 */
describe('CSS 变量引用有效性', () => {
  const SRC = fileURLToPath(new URL('..', import.meta.url))

  function walk(dir: string, out: string[] = []): string[] {
    for (const e of readdirSync(dir, { withFileTypes: true })) {
      if (e.name === 'node_modules' || e.name === 'dist') continue
      const p = path.join(dir, e.name)
      if (e.isDirectory()) walk(p, out)
      else if (/\.(ts|tsx|css)$/.test(e.name) && !/\.test\./.test(e.name)) out.push(p)
    }
    return out
  }

  const sources = walk(SRC).map((f) => ({
    path: path.relative(SRC, f),
    // 去掉注释再检查：注释里举例说明 var(--x) 不算真实引用
    text: readFileSync(f, 'utf8')
      .replace(/\/\*[\s\S]*?\*\//g, '')
      .replace(/\/\/[^\n]*/g, ''),
  }))

  /** 全局 token 变量 + 源文件里就地声明的局部变量（如 .at-page { --at-text: ... }） */
  const defined = new Set<string>(Object.values(CSS_VAR_MAP))
  for (const { text } of sources) {
    for (const m of text.matchAll(/(--[\w-]+)\s*:\s*[^;{}]+[;}]/g)) defined.add(m[1])
  }

  it('所有 var(--x) 引用的变量都有定义', () => {
    const dead: string[] = []
    for (const { path: file, text } of sources) {
      for (const m of text.matchAll(/var\((--[\w-]+)/g)) {
        if (!defined.has(m[1])) dead.push(`${file}: var(${m[1]})`)
      }
    }
    expect(dead, `以下变量被引用但未定义：\n${dead.join('\n')}`).toEqual([])
  })

  it('不再引用已废弃的旧变量名', () => {
    const legacy = [
      '--color-text', '--color-red', '--color-bg-page',
      '--surface', '--surface-strong', '--surface-border', '--surface-shadow',
      '--easing', '--radius-lg', '--radius-md', '--radius-sm', '--radius-xl',
    ]
    const hits: string[] = []
    for (const { path: file, text } of sources) {
      for (const name of legacy) {
        // 只查「引用」；注释里对历史的说明不算
        if (text.includes(`var(${name})`)) hits.push(`${file}: var(${name})`)
      }
    }
    expect(hits, `旧的变量名仍在被引用：\n${hits.join('\n')}`).toEqual([])
  })
})

/**
 * 字号与内联颜色的「不许回潮」约束（设计方案 §4.2 / §12.5）。
 * 统一前全站有 10 种字号（含 10.5/11.5/12.5 这类小数号）与 83 处内联硬编码颜色，
 * 这两条测试防止再次散开。
 */
describe('字号与内联颜色约束', () => {
  const SRC = fileURLToPath(new URL('..', import.meta.url))

  function walk(dir: string, out: string[] = []): string[] {
    for (const e of readdirSync(dir, { withFileTypes: true })) {
      if (e.name === 'node_modules' || e.name === 'dist') continue
      const p = path.join(dir, e.name)
      if (e.isDirectory()) walk(p, out)
      else if (/\.(ts|tsx|css)$/.test(e.name) && !/\.test\./.test(e.name)) out.push(p)
    }
    return out
  }

  const files = walk(SRC).map((f) => ({
    path: path.relative(SRC, f),
    text: readFileSync(f, 'utf8')
      .replace(/\/\*[\s\S]*?\*\//g, '')
      .replace(/\/\/[^\n]*/g, ''),
  }))

  /** 允许的字号刻度（pt 为 Word 纸张专用，单独排除） */
  const ALLOWED_FONT_SIZES = new Set([
    'var(--fs-micro)', 'var(--fs-caption)', 'var(--fs-body)', 'var(--fs-body-lg)',
    'var(--fs-title)', 'var(--fs-h2)', 'var(--fs-stat)',
    'var(--fs-symbol)', 'var(--fs-statXl)', 'var(--fs-display)',
    'var(--fs-hero)', 'var(--fs-watermark)',
    // global.css 里 Word 纸张的局部变量（白纸黑字，刻意不随主题）
    'var(--wp-ink)', 'var(--wp-ink-soft)', 'var(--wp-line)', 'var(--wp-th-bg)',
  ])

  const stripComments = (css: string) => css.replace(/\/\*[\s\S]*?\*\//g, '')

  it('CSS 里不再出现刻度外的字号', () => {
    const bad: string[] = []
    for (const { path: file, text } of files.filter((f) => f.path.endsWith('.css'))) {
      // 停到 ; 或 } 或行尾：紧凑写法（如 `{ font-size: x }`）没有分号
      for (const m of stripComments(text).matchAll(/font-size:\s*([^;}]+)/g)) {
        const v = m[1].trim()
        if (ALLOWED_FONT_SIZES.has(v)) continue
        if (v.endsWith('pt') || v.startsWith('clamp(')) continue // Word 纸张 / 登录页 Hero
        bad.push(`${file}: font-size: ${v}`)
      }
    }
    expect(bad, `以下字号不在刻度内：\n${bad.join('\n')}`).toEqual([])
  })

  it('tsx 里的 fontSize 只用刻度值（或按尺寸动态计算）', () => {
    const allowedNumeric = new Set(['13', '14', '16'])
    const bad: string[] = []
    for (const { path: file, text } of files.filter((f) => f.path.endsWith('.tsx'))) {
      // 取到 , 或 } 或行尾为止，避免把后续表达式一起吃进来
      for (const m of text.matchAll(/fontSize:\s*([^,}\n]+)/g)) {
        const v = m[1].trim().replace(/^['"]|['"]$/g, '')
        if (ALLOWED_FONT_SIZES.has(v) || allowedNumeric.has(v)) continue
        // 按单元格尺寸动态计算是合理的（座位格字号随缩放变化）
        if (/^(Math\.|size|\w+Size|\w+\s*[*/+-])/.test(v)) continue
        bad.push(`${file}: fontSize: ${v}`)
      }
    }
    expect(bad, `以下 fontSize 不在刻度内：\n${bad.join('\n')}`).toEqual([])
  })

  /**
   * 内联硬编码颜色只允许出现在这些「刻意」场景：
   *  - tokens.ts / categoryColors.ts：颜色定义处（本来就是值的出处）
   *  - SigninQR：二维码必须纯黑纯白才能被扫
   *  - SeatLayoutEditor：打印窗口是独立文档，拿不到页面 CSS 变量
   *  - OrgRegister：表单 placeholder 里的示例文案
   *  - api/activityStatus.ts、api/party.ts：色值是「数据」不是样式（图表/后端往返）
   */
  const COLOR_ALLOW = new Set([
    'styles/tokens.ts',
    'styles/categoryColors.ts',
    'styles/color.ts',
    'components/signin/SigninQR.tsx',
    'components/seat/SeatLayoutEditor.tsx',
    'pages/org/OrgRegister.tsx',
    'api/activityStatus.ts',
    'api/party.ts',
  ])

  it('tsx/ts 里除刻意场景外没有内联硬编码颜色（CSS 由 CSS 层自管）', () => {
    const bad: string[] = []
    for (const { path: file, text } of files) {
      if (!/\.(tsx|ts)$/.test(file)) continue
      // path.relative 在 Windows 上给反斜杠，统一成正斜杠再比对
      const norm = file.split(path.sep).join('/')
      if (COLOR_ALLOW.has(norm)) continue
      for (const m of text.matchAll(/(?<![\w-])#[0-9a-fA-F]{6}\b/g)) {
        bad.push(`${file}: ${m[0]}`)
      }
    }
    expect(bad, `以下内联颜色应改为 token：\n${bad.join('\n')}`).toEqual([])
  })

  it('CSS 圆角只用刻度（4/6/8/10 + 胶囊/圆形）', () => {
    const allowed = new Set([
      'var(--r-sm)', 'var(--r-md)', 'var(--r-lg)', 'var(--r-xl)',
      'var(--r-pill)', '50%', '0', '0 !important',
      // 角落不对称的情形（如聊天气泡尾巴、选中竖条只圆右侧）
      '0 2px 2px 0',
      '0 var(--r-sm) var(--r-sm) 0',
      'var(--r-lg) var(--r-lg) var(--r-sm) var(--r-lg)',
    ])
    const bad: string[] = []
    for (const { path: file, text } of files.filter((f) => f.path.endsWith('.css'))) {
      for (const m of stripComments(text).matchAll(/border-radius:\s*([^;}]+)/g)) {
        const v = m[1].trim()
        if (!allowed.has(v)) bad.push(`${file}: border-radius: ${v}`)
      }
    }
    expect(bad, `以下圆角不在刻度内：\n${bad.join('\n')}`).toEqual([])
  })
})
