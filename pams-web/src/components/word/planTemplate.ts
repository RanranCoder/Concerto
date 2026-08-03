// 策划书 Word 模板：12 章章节骨架 + 导出 docx + 导入 docx（粗粒度）
// 数据模型不变：仍存 PlanFields 7 字段，Word 只是编辑/展示形态

export interface PlanFields {
  background: string
  purpose: string
  content: string
  flow: string
  notice: string
  emergency: string
  budget: string
}

export interface PlanSection {
  label: string
  field: keyof PlanFields | null
  hint?: string
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

/** 从活动信息生成的策划书 meta（抬头与大标题） */
export interface PlanMeta {
  name?: string
  theme?: string
  orgName?: string
  time?: string
  location?: string
  organizer?: string
  target?: string
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
  if (/<[a-zA-Z][\s\S]*>/i.test(s || '')) return s || ''
  return textToHtml(s)
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

/** 导出：把 7 字段组装成标准策划书 docx（docx 库动态 import） */
export async function planToDocx(plan: PlanFields, meta?: PlanMeta): Promise<Blob> {
  const { Document, Packer, Paragraph, Table, TableRow, TableCell, TextRun, AlignmentType, WidthType, BorderStyle } = await import('docx')

  const orgName = meta?.orgName || '信息工程学院党建办公室'
  const title = meta?.name || '活动策划书'
  const theme = meta?.theme || ''
  const budgetArr = parseBudgetArray(plan.budget)

  const border = { style: BorderStyle.SINGLE, size: 4, color: '000000' }
  const cellBorders = {
    top: border,
    bottom: border,
    left: border,
    right: border,
  }

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

  for (const sec of PLAN_TEMPLATE_SECTIONS) {
    const fixedVal = sectionMetaValue(sec, meta).trim()
    const fieldVal = sec.field ? stripHtml(plan[sec.field] ?? '').trim() : ''
    const value = fieldVal || fixedVal
    if (!value) continue

    children.push(
      new Paragraph({
        spacing: { before: 160, after: 80 },
        children: [new TextRun({ text: sec.label, font: '宋体', size: 28, bold: true })],
      }),
    )

    // 预算：JSON 数组 → 4 列表格
    if (sec.field === 'budget' && budgetArr) {
      const header = ['物品', '数量', '单价（元）', '总价（元）']
      const rows = [header].concat(
        budgetArr.map((item) => [
          String(item?.item ?? ''),
          String(item?.quantity ?? ''),
          String(item?.unitPrice ?? ''),
          String(item?.totalPrice ?? ''),
        ]),
      )
      const table = new Table({
        width: { size: 100, type: WidthType.PERCENTAGE },
        rows: rows.map((cells, ri) =>
          new TableRow({
            tableHeader: ri === 0,
            children: cells.map((c) =>
              new TableCell({
                borders: cellBorders,
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

    // 普通字段：多行正文，每行一段 12pt
    for (const line of String(value).split('\n')) {
      const t = line.replace(/\s+$/, '')
      if (!t.trim()) {
        children.push(new Paragraph({ children: [] }))
        continue
      }
      children.push(
        new Paragraph({
          spacing: { after: 60 },
          indent: { firstLine: 480 },
          children: [new TextRun({ text: t, font: '宋体', size: 24 })],
        }),
      )
    }
  }

  // 落款：右对齐
  children.push(
    new Paragraph({
      alignment: AlignmentType.RIGHT,
      spacing: { before: 240 },
      children: [new TextRun({ text: orgName, font: '宋体', size: 24 })],
    }),
  )
  const year = new Date().getFullYear()
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

/** 导入：mammoth 动态 import 提取文本，按章节标题粗粒度填充字段 */
export async function docxToPlan(file: File): Promise<Partial<PlanFields>> {
  const mammoth = await import('mammoth')
  const arrayBuffer = await file.arrayBuffer()
  const result = await mammoth.extractRawText({ arrayBuffer })
  return planFromDocxText(result.value || '')
}
