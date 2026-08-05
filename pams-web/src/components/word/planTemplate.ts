// 策划书 Word 模板：12 章章节骨架 + 导出 docx + 导入 docx
// 数据模型不变：仍存 PlanFields 7 字段；wangEditor 富文本 HTML 直接落库，导出 docx 时逐元素保真
import DOMPurify from 'dompurify'

export interface PlanFields {
  background: string
  purpose: string
  content: string
  flow: string
  notice: string
  emergency: string
  budget: string
}

/** 只读章节（活动基本信息）的可覆盖值：无值回退活动 meta */
export interface PlanOverrides {
  nameOverride?: string
  themeOverride?: string
  timeOverride?: string
  locationOverride?: string
  organizerOverride?: string
  targetOverride?: string
}

/** 章节顺序条目：label 默认节名，field 对应 7 字段（新增章节为 null），customLabel 用户自定义节名 */
export interface PlanSectionOrderItem {
  label: string
  field: keyof PlanFields | null
  customLabel?: string
  hint?: string
  /** 新增章节（field=null 且非只读 meta 章节）的富文本正文，存 section_order 一并持久化 */
  contentHtml?: string
}

export interface PlanSection {
  label: string
  field: keyof PlanFields | null
  hint?: string
  /** 用户自定义的章节名称（为空时使用label） */
  customLabel?: string
}

/** 12 章模板（以参考策划书「策划书新模板(终)1.docx」为骨架） */
export const PLAN_TEMPLATE_SECTIONS: PlanSection[] = [
  { label: '一、活动名称', field: null, hint: '信息工程学院党建办公室"XXX"活动' },
  { label: '二、活动主题', field: null },
  { label: '三、活动背景', field: 'background' },
  { label: '四、活动目的', field: 'purpose' },
  { label: '五、活动时间', field: null },
  { label: '六、活动地点', field: null },
  { label: '七、活动组织单位', field: null, hint: '信息工程学院党建办公室' },
  { label: '八、活动对象', field: null },
  { label: '九、活动内容', field: 'content' },
  { label: '十、活动注意事项', field: 'notice' },
  { label: '十一、应急预案', field: 'emergency' },
  { label: '十二、经费预算', field: 'budget', hint: '表格：物品/数量/单价(元)/总价(元)' },
]

/** 获取章节显示名称（优先使用自定义名称） */
export function getSectionDisplayName(sec: PlanSection): string {
  return sec.customLabel || sec.label
}

/** 从活动信息生成的策划书 meta（抬头与大标题） */
export interface PlanMeta {
  name?: string
  theme?: string
  orgName?: string
  time?: string
  location?: string
  organizer?: string
  target?: string
  endDate?: string
}

/** 去掉 HTML 标签，导出 docx 时把编辑器产生的富文本还原为纯文本（保留段落换行） */
export function stripHtml(s: string): string {
  return (s || '')
    .replace(/<br\s*\/?>/gi, '\n')
    .replace(/<\/(p|div|li|h[1-6]|tr|table)>/gi, '\n')
    .replace(/<[^>]+>/g, '')
    .replace(/&nbsp;/g, ' ')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&amp;/g, '&')
    .replace(/\r\n/g, '\n')
    .replace(/\n{3,}/g, '\n\n')
}

/** 纯文本 → 可编辑 HTML：转义并保留换行（DB 中的 \n 首次进入 contenteditable 需要 <br>） */
export function textToHtml(s: string): string {
  return (s || '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/\r\n/g, '\n')
    .replace(/\n/g, '<br>')
}

/** 进入 contenteditable 前的归一化：已是 HTML（用户编辑产物）原样返回，纯文本则转义换行 */
export function toEditableHtml(s: string): string {
  if (/<[a-zA-Z][\s\S]*>/i.test(s || '')) return sanitizeEditableHtml(s || '')
  return textToHtml(s)
}

/** XSS 防护：进入 contenteditable / 导出 docx 前把富文本净化。
 * 允许基本排版标签（p/div/br/b/strong/table/tr/td/th/ul/ol/li/span…），
 * 但禁止脚本类标签与一切事件属性，避免「字段编辑」粘贴的恶意 HTML 在他人打开编辑时执行。
 * 注意：事件属性（onerror 等）本就不在 DOMPurify 默认 ALLOWED_ATTR 内，会被直接剥离；
 * FORBID_ATTR 的 'on*' 仅为显式意图声明（DOMPurify 精确匹配，不改动时无害）。
 * 不禁用 style 属性，编辑器「字号」功能依赖 span[style=font-size] 保留。
 * vitest（node 环境）无 window，DOMPurify 降级为工厂函数无 .sanitize，捕获后走最小兜底净化。 */
export function sanitizeEditableHtml(html: string): string {
  const input = html || ''
  if (!input) return ''
  try {
    return DOMPurify.sanitize(input, {
      FORBID_TAGS: ['style', 'script', 'iframe', 'svg', 'form', 'object', 'embed', 'link', 'meta', 'base', 'math'],
      FORBID_ATTR: ['on*'],
    })
  } catch {
    // node 测试环境：DOMPurify 是工厂函数，退化为最小兜底净化（浏览器由上面真实净化兜底）
    return input
      .replace(/<\s*(script|style|iframe|object|embed|link|meta|base|form|svg|math)(?:\s[^>]*)?>[\s\S]*?<\s*\/\s*\1\s*>/gi, '')
      .replace(/<\s*\/?\s*(script|style|iframe|object|embed|link|meta|base|form|svg|math)(?:\s[^>]*)?>/gi, '')
      .replace(/\son[a-z]+\s*=\s*(?:"[^"]*"|'[^']*'|[^\s>]+)/gi, '')
  }
}

/** 判定一段预算字符串是否为 HTML 表格（编辑器工具条插入的 <table> 富文本） */
export function isBudgetHtmlTable(s: string | null | undefined): boolean {
  return typeof s === 'string' && /<table[\s>]/i.test(s.trim())
}

/** 从预算 HTML 表格提取行列文本为二维数组（首行为表头；colspan/rowspan 简化为同列文本，全空行剔除）
 * 轻量正则实现，不依赖 DOMParser，便于单测。返回 null 表示不是可解析的 HTML 表格 */
export function parseBudgetHtml(s: string | null | undefined): string[][] | null {
  if (!isBudgetHtmlTable(s)) return null
  const rows: string[][] = []
  const rowRe = /<tr[^>]*>([\s\S]*?)<\/tr\s*>/gi
  let rm: RegExpExecArray | null
  while ((rm = rowRe.exec(s as string)) !== null) {
    const cells = Array.from((rm[1] || '').matchAll(/<t[dh][^>]*>([\s\S]*?)<\/t[dh]\s*>/gi)).map((cm) => stripHtml(cm[1]).trim())
    if (cells.some((c) => c.trim() !== '')) rows.push(cells)
  }
  return rows.length ? rows : null
}

/** 把编辑器产物（可能是 HTML 表格 / JSON 数组 / 纯文本）解析为渲染用二维数组，取不到则 null */
export function parseBudgetMatrix(s: string | null | undefined): string[][] | null {
  const arr = parseBudgetArray(s)
  if (arr) return [['物品', '数量', '单价（元）', '总价（元）']].concat(
    arr.map((item) => [String(item?.item ?? ''), String(item?.quantity ?? ''), String(item?.unitPrice ?? ''), String(item?.totalPrice ?? '')]),
  )
  const htmlMatrix = parseBudgetHtml(s)
  if (htmlMatrix) return htmlMatrix
  return null
}

/** 落款年份：优先取活动 endDate 年份，拿不到再回退当前年（endDate 为 "YYYY-MM-DD" 或 "YYYY/MM/DD" 形式） */
export function planYearFromMeta(meta?: PlanMeta): number {
  const m = /(?:^|\D)(20\d{2})/.exec((meta?.endDate ?? '').slice(0, 10))
  return m ? Number(m[1]) : new Date().getFullYear()
}

/** 固定章节（不映射字段）从活动 meta 取展示值 */
export function sectionMetaValue(sec: PlanSection, meta?: PlanMeta): string {
  if (!meta) return ''
  switch (sec.label) {
    case '一、活动名称':
      return meta.name ?? ''
    case '二、活动主题':
      return meta.theme ?? ''
    case '五、活动时间':
      return meta.time ?? ''
    case '六、活动地点':
      return meta.location ?? ''
    case '七、活动组织单位':
      return meta.organizer ?? ''
    case '八、活动对象':
      return meta.target ?? ''
    default:
      return ''
  }
}

/** 只读章节的可覆盖展示值：override 非空优先，否则回退 meta（预览/导出用） */
export function sectionOverrideValue(
  label: string,
  overrides: PlanOverrides | undefined,
  meta?: PlanMeta,
): string {
  const o = overrides ?? {}
  const metaVal = meta ? sectionMetaValue({ label, field: null } as PlanSection, meta) : ''
  switch (label) {
    case '一、活动名称':
      return o.nameOverride?.trim() || metaVal
    case '二、活动主题':
      return o.themeOverride?.trim() || metaVal
    case '五、活动时间':
      return o.timeOverride?.trim() || metaVal
    case '六、活动地点':
      return o.locationOverride?.trim() || metaVal
    case '七、活动组织单位':
      return o.organizerOverride?.trim() || metaVal
    case '八、活动对象':
      return o.targetOverride?.trim() || metaVal
    default:
      return ''
  }
}

/** 默认模板 → 章节顺序 JSON 数组（字段章节 + 只读章节） */
export function defaultSectionOrder(): PlanSectionOrderItem[] {
  return PLAN_TEMPLATE_SECTIONS.map((s) => ({
    label: s.label,
    field: s.field,
    hint: s.hint,
  }))
}

/** 组装整篇策划书预览 HTML（抬头 + 各章节 override||meta 只读值/富文本正文），供 PagedWordPreview 使用 */
export function buildPlanPreviewHtml(
  plan: PlanFields,
  overrides: PlanOverrides | undefined,
  meta?: PlanMeta,
  sectionOrder?: PlanSectionOrderItem[],
): string {
  const orgName = meta?.orgName || '信息工程学院党建办公室'
  const title = meta?.name || '活动策划书'
  const theme = meta?.theme || ''
  const sections = sectionOrder && sectionOrder.length > 0 ? sectionOrder : defaultSectionOrder()

  const head = [
    `<div class="word-header-center">${escapeHtml(orgName)}</div>`,
    `<div class="word-header-title">${escapeHtml(title)}</div>`,
    theme ? `<div class="word-header-sub">（主题：${escapeHtml(theme)}）</div>` : '',
  ].join('')

  const body = sections
    .map((sec) => {
      const label = escapeHtml(sec.customLabel || sec.label)
      let content = ''
      if (sec.field) {
        content = plan[sec.field] ?? ''
      } else if (isCustomSection(sec)) {
        content = sec.contentHtml ?? ''
      } else {
        const fixed = sectionOverrideValue(sec.label, overrides, meta).trim()
        if (fixed) content = `<div>${escapeHtml(fixed)}</div>`
      }
      if (!content.trim()) return ''
      return `<div class="word-sec"><div class="word-sec-label">${label}</div><div class="word-sec-body">${content}</div></div>`
    })
    .join('')

  return `${head}${body}`
}

/** 解析后端 section_order JSON；非数组/损坏则回退默认模板 */
export function parseSectionOrder(raw: string | null | undefined): PlanSectionOrderItem[] {
  if (raw) {
    try {
      const v = JSON.parse(raw)
      if (Array.isArray(v) && v.length > 0) {
        return v.map((it: Partial<PlanSectionOrderItem>) => ({
          label: typeof it?.label === 'string' ? it.label : '新章节',
          field: (it?.field as keyof PlanFields | null) ?? null,
          customLabel: typeof it?.customLabel === 'string' ? it.customLabel : undefined,
          hint: typeof it?.hint === 'string' ? it.hint : undefined,
          contentHtml: typeof it?.contentHtml === 'string' ? it.contentHtml : undefined,
        }))
      }
    } catch {
      /* 损坏 JSON 回退默认 */
    }
  }
  return defaultSectionOrder()
}

/** 只读活动信息章节 label 集合 */
const META_SECTION_LABELS = new Set(['一、活动名称', '二、活动主题', '五、活动时间', '六、活动地点', '七、活动组织单位', '八、活动对象'])

export function isMetaSectionLabel(label: string): boolean {
  return META_SECTION_LABELS.has(label)
}

/** 判断是否为用户新增的自定义章节（正文走富文本，存 section_order.contentHtml） */
export function isCustomSection(sec: PlanSectionOrderItem): boolean {
  return !sec.field && !isMetaSectionLabel(sec.label)
}

/** 章节顺序 → 存储 JSON 字符串 */
export function serializeSectionOrder(items: PlanSectionOrderItem[]): string {
  return JSON.stringify(items)
}

/** 预算字符串可能为 JSON 数组：[{item,quantity,unitPrice,totalPrice}]，否则视为纯文本 */
export function parseBudgetArray(s: string | null | undefined): Array<Record<string, unknown>> | null {
  if (!s) return null
  const t = s.trim()
  if (!t.startsWith('[')) return null
  try {
    const v = JSON.parse(t)
    return Array.isArray(v) ? (v as Array<Record<string, unknown>>) : null
  } catch {
    return null
  }
}

/** 规范标题：将 "三、活动背景" / "三." / "3." 等形式的章节标题统一为 PLAN_TEMPLATE_SECTIONS 的 label */
function normalizeSectionTitle(raw: string): string | null {
  const t = (raw || '').replace(/[ \t ]+/g, '').trim()
  if (!t) return null
  const noC = t.replace(/^(第?[一二三四五六七八九十\d]+)([、\.．:：])\s*/, '').trim()
  for (const s of PLAN_TEMPLATE_SECTIONS) {
    if (noC === s.label.replace(/^(第?[一二三四五六七八九十\d]+[、\.．:：])/, '') && noC.length >= 2) return s.label
  }
  // 宽松匹配：长度 >4 的正文段落不回退（避免把正文内容误当章节）
  return null
}

/** 把段落按章节标题切分，返回各章节归集的文本（用于 Word 导入的字段映射） */
function splitSections(lines: string[]): { label: string; body: string }[] {
  const result: { label: string; body: string }[] = []
  let current: { label: string; body: string } | null = null

  for (const line of lines) {
    const title = normalizeSectionTitle(line)
    if (title) {
      current = { label: title, body: '' }
      result.push(current)
    } else if (current) {
      const t = (line || '').trim()
      if (t) current.body = current.body ? `${current.body}\n${t}` : t
    }
    // 章节标题之前的内容（抬头/标题）忽略
  }
  return result
}

/** 导出：把 7 字段 + 覆盖值组装成标准策划书 docx（docx 库动态 import）。
 * 正文为 wangEditor 富文本 HTML → htmlToDocx 逐元素保真（加粗/列表/表格/图片/颜色）。
 * 只读章节用 override || meta。章节顺序取自 sectionOrder（缺省默认模板）。 */
export async function planToDocx(
  plan: PlanFields,
  meta?: PlanMeta,
  overrides?: PlanOverrides,
  sectionOrder?: PlanSectionOrderItem[],
): Promise<Blob> {
  const mod = await import('docx')
  const { Document, Packer, Paragraph, Table, TextRun, AlignmentType } = mod
  const { htmlToDocx } = await import('./htmlToDocx')

  const orgName = meta?.orgName || '信息工程学院党建办公室'
  const title = meta?.name || '活动策划书'
  const theme = meta?.theme || ''
  const ctx = { mod }

  type DocChild = InstanceType<typeof Paragraph> | InstanceType<typeof Table>
  const children: DocChild[] = []

  // 抬头：机构名 22pt 居中
  children.push(
    new Paragraph({
      alignment: AlignmentType.CENTER,
      spacing: { before: 120, after: 40 },
      children: [new TextRun({ text: orgName, font: '宋体', size: 44, bold: true })],
    }),
  )
  // 大标题 22pt 居中
  children.push(
    new Paragraph({
      alignment: AlignmentType.CENTER,
      spacing: { after: 200 },
      children: [new TextRun({ text: title, font: '宋体', size: 44, bold: true })],
    }),
  )
  if (theme) {
    children.push(
      new Paragraph({
        alignment: AlignmentType.CENTER,
        spacing: { after: 200 },
        children: [new TextRun({ text: `（主题：${theme}）`, font: '宋体', size: 24 })],
      }),
    )
  }

  const sections = sectionOrder && sectionOrder.length > 0 ? sectionOrder : defaultSectionOrder()

  for (const sec of sections) {
    const label = sec.customLabel || sec.label
    // 字段章节：富文本 HTML 保真；自定义章节：section_order.contentHtml；只读章节：override || meta
    let html = ''
    if (sec.field) {
      html = plan[sec.field] ?? ''
    } else if (isCustomSection(sec)) {
      html = sec.contentHtml ?? ''
    } else {
      const fixedVal = sectionOverrideValue(sec.label, overrides, meta).trim()
      html = fixedVal ? `<div>${escapeHtml(fixedVal)}</div>` : ''
    }
    if (!html.trim()) continue

    children.push(
      new Paragraph({
        spacing: { before: 160, after: 80 },
        children: [new TextRun({ text: label, font: '宋体', size: 28, bold: true })],
      }),
    )

    // 预算：JSON 数组 → 表格；HTML 表格 → htmlToDocx 内建 Table 转换（fallback 纯文本）
    if (sec.field === 'budget' && !isBudgetHtmlTable(plan.budget)) {
      const budgetRows = parseBudgetMatrix(plan.budget)
      if (budgetRows) {
        const border = { style: mod.BorderStyle.SINGLE, size: 4, color: '000000' }
        const table = new mod.Table({
          width: { size: 100, type: mod.WidthType.PERCENTAGE },
          rows: budgetRows.map((cells, ri) =>
            new mod.TableRow({
              tableHeader: ri === 0,
              children: cells.map((c) =>
                new mod.TableCell({
                  borders: { top: border, bottom: border, left: border, right: border },
                  verticalAlign: 'center',
                  children: [
                    new Paragraph({
                      alignment: AlignmentType.CENTER,
                      spacing: { before: 40, after: 40 },
                      children: [new TextRun({ text: c, font: '宋体', size: 24, bold: ri === 0 })],
                    }),
                  ],
                }),
              ),
            }),
          ),
        })
        children.push(table)
        continue
      }
    }

    const body = await htmlToDocx(html, ctx)
    children.push(...body)
  }

  // 落款：右对齐；年份取活动 endDate 年份，拿不到再回退当前年
  children.push(
    new Paragraph({
      alignment: AlignmentType.RIGHT,
      spacing: { before: 240 },
      children: [new TextRun({ text: orgName, font: '宋体', size: 24 })],
    }),
  )
  const year = planYearFromMeta(meta)
  children.push(
    new Paragraph({
      alignment: AlignmentType.RIGHT,
      spacing: { after: 120 },
      children: [new TextRun({ text: `${year}年`, font: '宋体', size: 24 })],
    }),
  )

  const doc = new Document({
    sections: [{ children }],
  })
  return Packer.toBlob(doc)
}

/** HTML 转义（只读章节纯文本 → 富文本段落） */
function escapeHtml(s: string): string {
  return (s || '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
}

/** 把 mammoth 提取出的纯文本按章节标题切分，粗粒度填充字段 */
export function planFromDocxText(text: string): Partial<PlanFields> {
  const sections = splitSections((text || '').split('\n'))
  const out: Partial<PlanFields> = {}
  for (const sec of PLAN_TEMPLATE_SECTIONS) {
    if (!sec.field) continue
    const hit = sections.find((s) => s.label === sec.label)
    if (hit && hit.body.trim()) out[sec.field] = hit.body.trim()
  }
  return out
}

/** 把 mammoth HTML 按章节标题切分，返回 {label, bodyHtml}[]（正文保留 HTML 结构） */
export function splitSectionsHtml(html: string): { label: string; bodyHtml: string }[] {
  const result: { label: string; bodyHtml: string }[] = []
  let current: { label: string; bodyHtml: string } | null = null

  // node（vitest）环境无 document：退化为纯文本切分
  if (typeof document === 'undefined') {
    return splitSections((stripHtml(html) || '').split('\n')).map((s) => ({ label: s.label, bodyHtml: escapeHtml(s.body) }))
  }

  const fragment = document.createElement('div')
  fragment.innerHTML = html || ''
  const children = Array.from(fragment.childNodes)

  for (const node of children) {
    const text = (node.textContent ?? '').replace(/[ \t ]+/g, '').trim()
    if (!text) continue
    const tag = (node as Element).tagName?.toLowerCase() ?? ''
    const title = tag === 'h1' || tag === 'h2' || tag === 'h3' || tag === 'h4' ? normalizeSectionTitle(text) : null
    if (title) {
      current = { label: title, bodyHtml: '' }
      result.push(current)
    } else if (current) {
      const h = node instanceof Element ? node.outerHTML : escapeHtml(node.textContent ?? '')
      current.bodyHtml = current.bodyHtml ? `${current.bodyHtml}${h}` : h
    }
  }
  return result
}

/** 把 mammoth 提取的 HTML 按章节标题粗粒度填充字段（正文保留 HTML，budget 表格也保留） */
export function planFromDocxHtml(html: string): Partial<PlanFields> {
  const sections = splitSectionsHtml(html || '')
  const out: Partial<PlanFields> = {}
  for (const sec of PLAN_TEMPLATE_SECTIONS) {
    if (!sec.field) continue
    const hit = sections.find((s) => s.label === sec.label)
    if (hit && hit.bodyHtml.trim()) out[sec.field] = hit.bodyHtml.trim()
  }
  return out
}

/** 导入：mammoth convertToHtml 保留富文本结构，按章节标题切分回填各字段 */
export async function docxToPlan(file: File): Promise<Partial<PlanFields>> {
  const mammoth = await import('mammoth')
  const arrayBuffer = await file.arrayBuffer()
  const result = await mammoth.convertToHtml({ arrayBuffer })
  return planFromDocxHtml(result.value || '')
}

/** 导出议程表：docx 编号列表（标题「活动议程表」居中 + 每步编号项，docx 库动态 import 分包） */
export async function agendaToDocx(
  agendas: Array<{ stepNo: number; title: string; remark?: string | null }>,
): Promise<Blob> {
  const { Document, Packer, Paragraph, TextRun, AlignmentType } = await import('docx')
  const children = [
    new Paragraph({
      alignment: AlignmentType.CENTER,
      spacing: { after: 200 },
      children: [new TextRun({ text: '活动议程表', font: '宋体', bold: true, size: 28 })],
    }),
    ...agendas.map(
      (a) =>
        new Paragraph({
          spacing: { after: 60 },
          children: [new TextRun({ text: `${a.stepNo}. ${a.title}${a.remark ? '　' + a.remark : ''}`, font: '宋体', size: 24 })],
        }),
    ),
  ]
  const doc = new Document({ sections: [{ children }] })
  return Packer.toBlob(doc)
}
