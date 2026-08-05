// 轻量 HTML → docx 保真转换器：把 wangEditor 富文本（块级 + 行内 + 图片 + 表格）逐元素转为 docx 节点。
// 浏览器用 DOMParser 解析；node（vitest）环境无 DOMParser 时退化为最小处理（文本抽取）。
// 图片 <img> 需 fetch 二进制（/api/files/... 带 JWT），失败则跳过图片保留文字。
import { stripHtml } from './planTemplate'

type DocxMod = typeof import('docx')

export interface HtmlToDocxContext {
  mod: DocxMod
  /** fetch 图片二进制，走 JWT；可被测试注入 */
  fetchBlob?: (url: string) => Promise<Blob | null>
}

export type DocxChild = InstanceType<DocxMod['Paragraph']> | InstanceType<DocxMod['Table']>

/** 从 style 中提取字号（pt），如 font-size:14pt / 16px */
function styleFontSizePt(style: string | null | undefined): number | undefined {
  const m = /font-size\s*:\s*([\d.]+)\s*(pt|px)/i.exec(style ?? '')
  if (!m) return undefined
  const v = Number(m[1])
  if (!v) return undefined
  return m[2] === 'px' ? Math.round(v * 0.75) : Math.round(v)
}

/** 从 style 中提取颜色（十六进制或 rgb），docx 需要无 # 的十六进制 */
function styleColor(style: string | null | undefined): string | undefined {
  const m = /color\s*:\s*(#[0-9a-fA-F]{3,8}|rgb\(\s*[\d.,\s]+\))/i.exec(style ?? '')
  if (!m) return undefined
  let c = m[1]
  if (c.startsWith('rgb')) {
    const parts = /rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)/i.exec(c)
    if (!parts) return undefined
    c = [Number(parts[1]), Number(parts[2]), Number(parts[3])].map((n) => n.toString(16).padStart(2, '0')).join('')
  }
  return c.replace('#', '')
}

/** 提取 <img> 的 URL（wangEditor 图片为 <img src=... data-href=...>） */
function imageSrc(node: Element): string {
  return node.getAttribute('data-href') || node.getAttribute('src') || ''
}

/**
 * 递归把 HTML 节点转换为 docx 段落/表格/图片。
 * block children 追加到 paragraphs；inline 返回 TextRun[]（可含 ImageRun）。
 */
async function walkNode(
  node: Node,
  ctx: HtmlToDocxContext,
  paragraphs: DocxChild[],
  parentRunStyle: { bold?: boolean; italics?: boolean; underline?: boolean; strike?: boolean; color?: string; size?: number },
): Promise<{ runs: Array<InstanceType<DocxMod['TextRun']> | InstanceType<DocxMod['ImageRun']> | InstanceType<DocxMod['ExternalHyperlink']>> }> {
  const { mod } = ctx
  const { TextRun } = mod

  if (node.nodeType === 3) {
    const text = node.textContent ?? ''
    if (!text) return { runs: [] }
    const opt: Record<string, unknown> = { text }
    if (parentRunStyle.bold) opt.bold = true
    if (parentRunStyle.italics) opt.italics = true
    if (parentRunStyle.underline) opt.underline = { type: 'single' }
    if (parentRunStyle.strike) opt.strike = true
    if (parentRunStyle.color) opt.color = parentRunStyle.color
    if (parentRunStyle.size) opt.size = parentRunStyle.size
    return { runs: [new TextRun(opt)] }
  }

  if (node.nodeType !== 1) return { runs: [] }
  const el = node as Element
  const tag = el.tagName.toLowerCase()

  // 图片：独立处理（fetch 二进制 → ImageRun，失败跳过保留文字 alt）
  if (tag === 'img') {
    const src = imageSrc(el)
    if (!src) return { runs: [] }
    try {
      const blob = ctx.fetchBlob ? await ctx.fetchBlob(src) : await defaultFetchBlob(src)
      if (!blob) return { runs: [] }
      const data = new Uint8Array(await blob.arrayBuffer())
      const type = blob.type.includes('png') ? 'png' : blob.type.includes('gif') ? 'gif' : blob.type.includes('bmp') ? 'bmp' : 'jpg'
      const w = el.getAttribute('width') ? Number(el.getAttribute('width')) : 400
      const h = el.getAttribute('height') ? Number(el.getAttribute('height')) : 300
      return {
        runs: [
          new mod.ImageRun({
            type,
            data,
            transformation: { width: w || 400, height: h || 300 },
          }),
        ],
      }
    } catch {
      return { runs: [] }
    }
  }

  // 链接：包一层 ExternalHyperlink（行内）
  if (tag === 'a') {
    const href = el.getAttribute('href') || ''
    const inner = await walkNodeChildren(el, ctx, paragraphs, parentRunStyle)
    if (!href || inner.runs.length === 0) return inner
    return { runs: [new mod.ExternalHyperlink({ link: href, children: inner.runs })] }
  }

  // 行内样式标签
  if (['b', 'strong', 'i', 'em', 'u', 's', 'del', 'strike', 'sub', 'sup', 'span', 'font'].includes(tag)) {
    const nextStyle = { ...parentRunStyle }
    if (tag === 'b' || tag === 'strong') nextStyle.bold = true
    if (tag === 'i' || tag === 'em') nextStyle.italics = true
    if (tag === 'u') nextStyle.underline = true
    if (tag === 's' || tag === 'del' || tag === 'strike') nextStyle.strike = true
    if (tag === 'span' || tag === 'font') {
      const style = el.getAttribute('style')
      const color = styleColor(style)
      if (color) nextStyle.color = color
      const size = styleFontSizePt(style)
      if (size) nextStyle.size = size
      if (tag === 'font') {
        const faceColor = el.getAttribute('color')
        if (faceColor) nextStyle.color = faceColor.replace('#', '')
      }
    }
    return walkNodeChildren(el, ctx, paragraphs, nextStyle)
  }

  // 块级：把行内 runs 收束成段落
  if (['p', 'div', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'li', 'blockquote', 'br', 'ul', 'ol'].includes(tag)) {
    // <br> 是段落内换行
    if (tag === 'br') {
      return { runs: [new TextRun({ text: '', break: 1 })] }
    }
    // ul/ol 是列表容器：仅递归子项（li 各自成段），不额外产生段落
    if (tag === 'ul' || tag === 'ol') {
      await walkNodeChildren(el, ctx, paragraphs, parentRunStyle)
      return { runs: [] }
    }
    const { runs } = await walkNodeChildren(el, ctx, paragraphs, parentRunStyle)
    if (runs.length === 0 && !el.textContent?.trim()) return { runs }
    const h = tag.match(/^h([1-6])$/)
    const headingSize = h ? 28 + (6 - Number(h[1])) * 4 : undefined
    const paraChildren = headingSize
      ? runs.map((r) => ('text' in (r as unknown as Record<string, unknown>)
          ? new TextRun({ ...(r as unknown as Record<string, unknown>), size: headingSize, bold: true })
          : r))
      : runs
    paragraphs.push(
      new mod.Paragraph({
        spacing: headingSize ? { before: 120, after: 80 } : { after: 60 },
        indent: headingSize ? undefined : { firstLine: 480 },
        children: paraChildren,
      }),
    )
    return { runs: [] }
  }

  // 表格
  if (tag === 'table') {
    await appendTable(el, ctx, paragraphs)
    return { runs: [] }
  }

  // 其他（td/th/thead/tbody/tr 等）直接递归内容，由上层块级收束
  return walkNodeChildren(el, ctx, paragraphs, parentRunStyle)
}

async function walkNodeChildren(
  el: Element,
  ctx: HtmlToDocxContext,
  paragraphs: DocxChild[],
  parentRunStyle: { bold?: boolean; italics?: boolean; underline?: boolean; strike?: boolean; color?: string; size?: number },
): Promise<{ runs: Array<InstanceType<DocxMod['TextRun']> | InstanceType<DocxMod['ImageRun']> | InstanceType<DocxMod['ExternalHyperlink']>> }> {
  const allRuns: Array<InstanceType<DocxMod['TextRun']> | InstanceType<DocxMod['ImageRun']> | InstanceType<DocxMod['ExternalHyperlink']>> = []
  for (const child of Array.from(el.childNodes)) {
    const r = await walkNode(child, ctx, paragraphs, parentRunStyle)
    allRuns.push(...r.runs)
  }
  return { runs: allRuns }
}

async function appendTable(el: Element, ctx: HtmlToDocxContext, paragraphs: DocxChild[]): Promise<void> {
  const { mod } = ctx
  const rows: { cells: string[]; isHeader: boolean }[] = []
  // 手动遍历 tr（兼容 xmldom：无 querySelectorAll），thead/tbody 需递归
  const collectTrs = (n: Node): Element[] => {
    const out: Element[] = []
    for (const c of Array.from(n.childNodes)) {
      if (c.nodeType !== 1) continue
      const tag = (c as Element).tagName.toLowerCase()
      if (tag === 'tr') out.push(c as Element)
      else if (tag === 'thead' || tag === 'tbody' || tag === 'tfoot') out.push(...collectTrs(c))
    }
    return out
  }
  const trs = collectTrs(el)
  for (const tr of trs) {
    const trEl = tr as Element
    const cells = Array.from(trEl.childNodes)
      .filter((n) => n.nodeType === 1 && /^t[dh]$/i.test((n as Element).tagName))
      .map((c) => cellText(c as Element))
    if (cells.some((c) => c.trim())) {
      rows.push({ cells, isHeader: trEl.firstChild?.nodeName?.toLowerCase() === 'th' })
    }
  }
  if (rows.length === 0) return
  const border = { style: mod.BorderStyle.SINGLE, size: 4, color: '000000' }
  const table = new mod.Table({
    width: { size: 100, type: mod.WidthType.PERCENTAGE },
    rows: rows.map((row, ri) =>
      new mod.TableRow({
        tableHeader: row.isHeader || ri === 0,
        children: row.cells.map((c) =>
          new mod.TableCell({
            borders: { top: border, bottom: border, left: border, right: border },
            verticalAlign: 'center',
            children: [
              new mod.Paragraph({
                alignment: mod.AlignmentType.CENTER,
                spacing: { before: 40, after: 40 },
                children: [new mod.TextRun({ text: c, font: '宋体', size: 24, bold: row.isHeader || ri === 0 })],
              }),
            ],
          }),
        ),
      }),
    ),
  })
  paragraphs.push(table)
}

/** 单元格文本：优先 innerHTML（浏览器），xmldom 无 innerHTML 时回退 textContent */
function cellText(el: Element): string {
  const html = (el as unknown as { innerHTML?: string }).innerHTML
  if (typeof html === 'string' && html) return stripHtmlForDocx(html)
  return (el.textContent ?? '').trim()
}

function stripHtmlForDocx(s: string): string {
  return (s || '')
    .replace(/<br\s*\/?>/gi, ' ')
    .replace(/<[^>]+>/g, '')
    .replace(/&nbsp;/g, ' ')
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .trim()
}

/** 默认图片二进制拉取：/api/files/{id}/download 走 axios(JWT)，其他 URL 原样 fetch */
async function defaultFetchBlob(url: string): Promise<Blob | null> {
  const m = /\/api\/files\/(\d+)\/download/.exec(url)
  if (m) {
    try {
      const { http } = await import('@/api/http')
      const res = (await http.get(`/files/${Number(m[1])}/download`, { responseType: 'blob' })) as unknown as { data: Blob }
      return res.data
    } catch {
      return null
    }
  }
  if (url.startsWith('http') || url.startsWith('/uploads/')) {
    const res = await fetch(url)
    if (!res.ok) return null
    return res.blob()
  }
  return null
}

/**
 * 把一段富文本 HTML 解析为 docx 段落/表格数组。
 * 空/纯文本输入退化为文本段。node 环境（无 DOMParser）退化为 stripHtml 纯文本段。
 */
export async function htmlToDocx(html: string, ctx: HtmlToDocxContext): Promise<DocxChild[]> {
  const { mod } = ctx
  const paragraphs: DocxChild[] = []
  const input = (html || '').trim()
  if (!input) return paragraphs

  const domParser = typeof DOMParser !== 'undefined' ? new DOMParser() : null
  if (!domParser) {
    for (const line of stripHtml(input).split('\n')) {
      if (line.trim()) {
        paragraphs.push(
          new mod.Paragraph({
            spacing: { after: 60 },
            indent: { firstLine: 480 },
            children: [new mod.TextRun({ text: line, font: '宋体', size: 24 })],
          }),
        )
      }
    }
    return paragraphs
  }

  // 包一层根节点：浏览器 text/html 产生 body>div；node xmldom（XML 解析器）把 div 当作 documentElement
  const doc = domParser.parseFromString(`<div>${input}</div>`, 'text/html')
  const root = doc.body ?? doc.documentElement
  const container = root.nodeName.toLowerCase() === 'div' ? root : root.firstChild ?? root
  for (const child of Array.from(container.childNodes)) {
    await walkNode(child, ctx, paragraphs, {})
  }
  return paragraphs
}
