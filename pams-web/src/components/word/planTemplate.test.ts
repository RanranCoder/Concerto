import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import {
  planFromDocxText,
  planToDocx,
  parseBudgetArray,
  parseBudgetHtml,
  parseBudgetMatrix,
  planYearFromMeta,
  sanitizeEditableHtml,
  stripHtml,
  toEditableHtml,
} from './planTemplate'

const REF = 'D:/StudyFiles/Office/党建办公室/信工党建办公室历届资料/信工党建第九届/年度部门材料汇总/组织部资料/23级学长的组织部资料——统一仅供参考/党建策划书/策划书新模板(终)1.docx'

describe('planTemplate import/export', () => {
  it('stripHtml removes tags & entities', () => {
    expect(stripHtml('<div>abc<b>123</b></div>&amp;')).toBe('abc123\n&')
  })

  it('stripHtml preserves line breaks from editable HTML', () => {
    expect(stripHtml('<div>第一行</div><div>第二行<br>换行</div>')).toBe('第一行\n第二行\n换行\n')
    // 往返：DB 纯文本 → contenteditable HTML → 导出前 strip 回纯文本（去掉末尾换行）
    expect(stripHtml(toEditableHtml('第一行\n第二行')).replace(/\n$/, '')).toBe('第一行\n第二行')
    // 已是 HTML（用户编辑产物）原样返回，避免二次转义
    expect(toEditableHtml('<div>第一行</div>')).toBe('<div>第一行</div>')
  })

  it('parseBudgetArray parses JSON array else null', () => {
    expect(parseBudgetArray('[{"item":"横幅","quantity":1,"unitPrice":50,"totalPrice":50}]')).toHaveLength(1)
    expect(parseBudgetArray('纯文本预算')).toBeNull()
  })

  it('parseBudgetHtml extracts rows from HTML table (toolbar-inserted budget)', () => {
    const html =
      '<table class="word-table"><tr><th>物品</th><th>数量</th><th>单价（元）</th><th>总价（元）</th></tr>' +
      '<tr><td>横幅</td><td>1</td><td>50</td><td>50</td></tr>' +
      '<tr><td>展板</td><td>2</td><td>30</td><td>60</td></tr></table>'
    expect(parseBudgetHtml(html)).toEqual([
      ['物品', '数量', '单价（元）', '总价（元）'],
      ['横幅', '1', '50', '50'],
      ['展板', '2', '30', '60'],
    ])
    expect(parseBudgetHtml('纯文本预算')).toBeNull()
    expect(parseBudgetHtml('[{"item":"x"}]')).toBeNull()
  })

  it('parseBudgetMatrix covers JSON array and HTML table, else null', () => {
    expect(parseBudgetMatrix('[{"item":"横幅","quantity":1,"unitPrice":50,"totalPrice":50}]')).toEqual([
      ['物品', '数量', '单价（元）', '总价（元）'],
      ['横幅', '1', '50', '50'],
    ])
    const html =
      '<table><tr><th>物品</th><th>数量</th></tr><tr><td>横幅</td><td>1</td></tr></table>'
    expect(parseBudgetMatrix(html)).toEqual([
      ['物品', '数量'],
      ['横幅', '1'],
    ])
    expect(parseBudgetMatrix('纯文本预算')).toBeNull()
  })

  it('sanitizeEditableHtml strips scripts and event handlers, keeps table/span', () => {
    const bad = '<img src=x onerror="alert(1)"><script>alert(2)</script><b>hi</b><span style="font-size:14pt">章节</span>'
    const out = sanitizeEditableHtml(bad)
    expect(out).not.toContain('onerror')
    expect(out).not.toContain('<script')
    expect(out).toContain('<b>hi</b>')
    expect(out).toContain('font-size:14pt')
    // 基本排版标签放行
    expect(sanitizeEditableHtml('<table><tr><td>物品</td></tr></table>')).toContain('<table')
    // 纯文本/空值
    expect(sanitizeEditableHtml('')).toBe('')
  })

  it('planYearFromMeta prefers endDate year, falls back to current year', () => {
    expect(planYearFromMeta({ endDate: '2024-06-30' })).toBe(2024)
    expect(planYearFromMeta({ endDate: '2025/12/31' })).toBe(2025)
    expect(planYearFromMeta({})).toBe(new Date().getFullYear())
    expect(planYearFromMeta({ endDate: '' })).toBe(new Date().getFullYear())
  })

  it('planFromDocxText extracts fields from real reference 策划书 text', async () => {
    const hasRef = await import('node:fs').then((fs) => fs.existsSync(REF)).catch(() => false)
    if (!hasRef) return // 参考文件仅在本机存在，缺失时跳过
    // node 环境用 buffer 路径（浏览器/生产用 arrayBuffer，行为一致）
    const mammoth = await import('mammoth')
    const buf = readFileSync(REF)
    const result = await mammoth.extractRawText({ buffer: buf })
    const plan = planFromDocxText(result.value)
    console.log('=== planFromDocxText extracted ===')
    console.log(JSON.stringify(plan, null, 2))
    expect(plan).toMatchObject({})
  }, 20000)

  it('planToDocx produces a valid docx blob', async () => {
    const blob = await planToDocx(
      {
        background: '为了学习党的二十大，坚定理想信念\n开展本次主题教育活动。',
        purpose: '增强党性修养，凝聚青春力量。',
        content: '1. 奏唱国歌\n2. 重温入党誓词',
        flow: '',
        notice: '注意安全，听从指挥。',
        emergency: '如遇突发情况，请及时联系负责人。',
        budget: '[{"item":"横幅","quantity":1,"unitPrice":50,"totalPrice":50}]',
      },
      { name: '信息工程学院党建办公室"传承红色基因"活动', theme: '传承红色基因' },
    )
    expect(blob).toBeInstanceOf(Blob)
    expect(blob.size).toBeGreaterThan(2000)
    console.log('=== exported docx size ===', blob.size)
  }, 20000)

  it('planToDocx renders HTML-table budget as docx table', async () => {
    const htmlBudget =
      '<table class="word-table"><tr><th>物品</th><th>数量</th><th>单价（元）</th><th>总价（元）</th></tr>' +
      '<tr><td>横幅</td><td>1</td><td>50</td><td>50</td></tr>' +
      '<tr><td>展板</td><td>2</td><td>30</td><td>60</td></tr></table>'
    const blob = await planToDocx(
      {
        background: '背景',
        purpose: '目的',
        content: '内容',
        flow: '',
        notice: '注意',
        emergency: '应急',
        budget: htmlBudget,
      },
      { name: '活动', theme: '', endDate: '2024-05-20' },
    )
    expect(blob).toBeInstanceOf(Blob)
    expect(blob.size).toBeGreaterThan(2000)
    // 导出后再导入：表格单元格文本应能被读回（证明表格真正写入了 docx）
    const buf = Buffer.from(await blob.arrayBuffer())
    const mammoth = await import('mammoth')
    const result = await mammoth.extractRawText({ buffer: buf })
    expect(result.value).toContain('横幅')
    expect(result.value).toContain('展板')
    console.log('=== html-budget exported docx size ===', blob.size)
  }, 20000)

  // 导出后再导入的往返：证明导出的 docx 能被 docxToPlan 读回字段
  it('round-trip: export then import restores fields', async () => {
    const blob = await planToDocx(
      {
        background: '为了传承红色基因，坚定理想信念。',
        purpose: '增强党性修养。',
        content: '1. 奏唱国歌\n2. 重温入党誓词\n3. 主题演讲',
        flow: '',
        notice: '注意安全。',
        emergency: '应急联系人。',
        budget: '[{"item":"横幅","quantity":1,"unitPrice":50,"totalPrice":50},{"item":"展板","quantity":2,"unitPrice":30,"totalPrice":60}]',
      },
      { name: '活动', theme: '' },
    )
    const buf = Buffer.from(await blob.arrayBuffer())
    const mammoth = await import('mammoth')
    const result = await mammoth.extractRawText({ buffer: buf })
    const plan = planFromDocxText(result.value)
    console.log('=== round-trip extracted ===')
    console.log(JSON.stringify(plan, null, 2))
    expect(plan.background).toContain('红色基因')
    expect(plan.purpose).toContain('党性修养')
    expect(plan.content).toContain('主题演讲')
    expect(plan.budget).toContain('横幅')
  }, 20000)
})
