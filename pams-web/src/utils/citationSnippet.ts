import type { AgentCitation } from '@/api/agent'

const labels: Record<string, string> = {
  step: '环节', detail: '内容', time: '时间', item: '项目', quantity: '数量',
  unitPrice: '单价', totalPrice: '合计', unit: '单位', remark: '备注',
}

/** Legacy citation snapshots can contain a JSON array truncated mid-object.
 * Recover complete rows only; never guess the missing part of a source. */
export function citationSnippet(citation: Pick<AgentCitation, 'sourceType' | 'snippet'>): string {
  const text = citation.snippet || ''
  if (citation.sourceType !== 'activity_plan' || !text.trim().startsWith('[')) return text
  let rows: unknown[] = []
  let truncated = false
  try {
    const value: unknown = JSON.parse(text)
    if (Array.isArray(value)) rows = value
  } catch {
    let start = -1, depth = 0, quoted = false, escaped = false
    for (let i = 0; i < text.length; i++) {
      const char = text[i]
      if (quoted) {
        if (escaped) escaped = false
        else if (char === '\\') escaped = true
        else if (char === '"') quoted = false
      } else if (char === '"') quoted = true
      else if (char === '{') { if (depth++ === 0) start = i }
      else if (char === '}' && depth > 0 && --depth === 0) {
        try { rows.push(JSON.parse(text.slice(start, i + 1))) } catch { /* keep valid rows only */ }
      }
    }
    truncated = true
  }
  const formatted = rows.flatMap((row, index) => {
    if (!row || typeof row !== 'object' || Array.isArray(row)) return []
    const entries = Object.entries(row).filter(([, v]) => v != null && typeof v !== 'object')
    if (!entries.some(([k]) => k === 'step' || k === 'item')) return []
    return [`${index + 1}. ${entries.map(([k, v]) => `${labels[k] || k}：${String(v)}`).join('；')}`]
  })
  return formatted.length ? formatted.join('\n') + (truncated ? '\n…（片段已截断，请打开原文查看完整内容）' : '') : text
}
