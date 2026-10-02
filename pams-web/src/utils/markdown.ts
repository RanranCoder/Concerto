import DOMPurify from 'dompurify'

/* ============================================================================
 * 轻量 Markdown 渲染（灵思结果区）
 *
 * 为什么不用 react-markdown：
 * 灵思只要求「段落 / 列表 / 加粗 / 行内代码 / 表格 / 标题」六种，且项目已装 dompurify。
 * 少一个依赖链 = 少一处供应链风险，且能精确控制白名单（与新闻稿 DOMPurify 策略一致）。
 *
 * ⚠️ 安全：所有输出必须过 DOMPurify（服务端目前无 HTML 净化，见设计方案 D10），
 * 这里做前端第二道防线。
 * ========================================================================== */

function escapeHtml(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

/** 行内样式：`code`、**bold**、*italic* */
function inline(raw: string): string {
  let s = escapeHtml(raw)
  s = s.replace(/`([^`]+)`/g, '<code>$1</code>')
  s = s.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
  s = s.replace(/(^|[^*])\*([^*]+)\*/g, '$1<em>$2</em>')
  return s
}

/** 表格行：| a | b | c | */
function isTableRow(line: string): boolean {
  const t = line.trim()
  return t.startsWith('|') && t.endsWith('|') && t.length > 2
}
function isTableSep(line: string): boolean {
  return /^\s*\|[\s:|-]+\|\s*$/.test(line)
}
function splitRow(line: string): string[] {
  return line
    .trim()
    .replace(/^\|/, '')
    .replace(/\|$/, '')
    .split('|')
    .map((c) => c.trim())
}

/**
 * 渲染为 HTML 字符串（已净化）。调用方用 dangerouslySetInnerHTML。
 */
export function renderMarkdown(src: string): string {
  if (!src) return ''
  const lines = src.replace(/\r\n/g, '\n').split('\n')
  const out: string[] = []
  let i = 0

  while (i < lines.length) {
    const line = lines[i]
    const trimmed = line.trim()

    // 空行
    if (!trimmed) {
      i += 1
      continue
    }

    // 表格
    if (isTableRow(line) && i + 1 < lines.length && isTableSep(lines[i + 1])) {
      const header = splitRow(line)
      const rows: string[][] = []
      i += 2
      while (i < lines.length && isTableRow(lines[i])) {
        rows.push(splitRow(lines[i]))
        i += 1
      }
      out.push('<table><thead><tr>')
      for (const h of header) out.push(`<th>${inline(h)}</th>`)
      out.push('</tr></thead><tbody>')
      for (const r of rows) {
        out.push('<tr>')
        for (let c = 0; c < header.length; c += 1) out.push(`<td>${inline(r[c] ?? '')}</td>`)
        out.push('</tr>')
      }
      out.push('</tbody></table>')
      continue
    }

    // 标题
    const h = /^(#{1,4})\s+(.*)$/.exec(trimmed)
    if (h) {
      const level = Math.min(h[1].length + 1, 4) // # → h2（页面内已有 h2 语义）
      out.push(`<h${level}>${inline(h[2])}</h${level}>`)
      i += 1
      continue
    }

    // 引用
    if (trimmed.startsWith('> ')) {
      const buf: string[] = []
      while (i < lines.length && lines[i].trim().startsWith('> ')) {
        buf.push(lines[i].trim().slice(2))
        i += 1
      }
      out.push(`<blockquote>${inline(buf.join(' '))}</blockquote>`)
      continue
    }

    // 无序列表
    if (/^[-*+]\s+/.test(trimmed)) {
      const buf: string[] = []
      while (i < lines.length && /^[-*+]\s+/.test(lines[i].trim())) {
        buf.push(lines[i].trim().replace(/^[-*+]\s+/, ''))
        i += 1
      }
      out.push('<ul>')
      for (const b of buf) out.push(`<li>${inline(b)}</li>`)
      out.push('</ul>')
      continue
    }

    // 有序列表
    if (/^\d+[.)]\s+/.test(trimmed)) {
      const buf: string[] = []
      while (i < lines.length && /^\d+[.)]\s+/.test(lines[i].trim())) {
        buf.push(lines[i].trim().replace(/^\d+[.)]\s+/, ''))
        i += 1
      }
      out.push('<ol>')
      for (const b of buf) out.push(`<li>${inline(b)}</li>`)
      out.push('</ol>')
      continue
    }

    // 段落（连续非空、非结构行合并）
    const buf: string[] = [trimmed]
    i += 1
    while (i < lines.length) {
      const t = lines[i].trim()
      if (
        !t ||
        /^#{1,4}\s+/.test(t) ||
        /^[-*+]\s+/.test(t) ||
        /^\d+[.)]\s+/.test(t) ||
        t.startsWith('> ') ||
        isTableRow(lines[i])
      ) {
        break
      }
      buf.push(t)
      i += 1
    }
    out.push(`<p>${inline(buf.join(' '))}</p>`)
  }

  const html = out.join('')
  return DOMPurify.sanitize(html, {
    ALLOWED_TAGS: [
      'p', 'br', 'strong', 'em', 'code', 'pre',
      'ul', 'ol', 'li', 'blockquote',
      'table', 'thead', 'tbody', 'tr', 'th', 'td',
      'h1', 'h2', 'h3', 'h4',
    ],
    ALLOWED_ATTR: [],
  })
}

/**
 * 剔除写类工具埋在 detailLines 里的 `__args__:{...}` 标记行。
 * 这是「确认时执行的参数」的内部约定，用户不需要看到（见设计方案 §3.3）。
 */
export function stripArgsMarker(lines: string[] | undefined): string[] {
  if (!lines) return []
  return lines.filter((l) => !l.trim().startsWith('__args__:'))
}
