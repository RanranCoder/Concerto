import { useMemo } from 'react'
import { useStreamReveal } from '@/hooks/useStreamReveal'
import { renderMarkdown } from '@/utils/markdown'

interface Props {
  text: string
  /** 流式未完时在末尾追加光标 */
  streaming?: boolean
  /**
   * 依据条数。>0 时把正文里的 `[n]` 渲染成可点角标。
   *
   * 只接受"条数"而不是 citations 数组：这个组件只负责**把编号变成可点的东西**，
   * 点开之后展示什么由调用方决定。传整个数组会让它意外承担展示职责，
   * 也会让 markdown 这个纯渲染组件依赖上业务数据结构。
   */
  citeMax?: number
  citationIndices?: number[]
  /** 点击 `[n]` 角标（n 从 1 起）。不传则角标不可点，仍保留视觉强调 */
  onCite?: (index: number) => void
}

/** 把 `[n]` 包成可点角标；`<code>` / `<pre>` 内的内容跳过（代码里的 [1] 不是引用） */
function markCitations(html: string, allowed: Set<number>): string {
  if (!html || !allowed.size) return html
  return html
    .split(/(<code[\s\S]*?<\/code>|<pre[\s\S]*?<\/pre>)/g)
    .map((seg) => {
      if (seg.startsWith('<code') || seg.startsWith('<pre')) return seg
      return seg.replace(/\[(\d{1,3})\]/g, (whole, d: string) => {
        const n = Number(d)
        // 越界编号原样保留：后端已把它当作"模型编造"丢弃，这里不必伪装成有效引用
        return allowed.has(n)
          ? `<span class="lingsi-cite" data-idx="${n}" role="button" tabindex="0">${n}</span>`
          : whole
      })
    })
    .join('')
}

/**
 * 结果区正文。支持轻量 Markdown（段落/列表/加粗/行内代码/表格/标题）。
 * 渲染结果已过 DOMPurify，见 utils/markdown.ts。
 *
 * V27：正文里的 `[n]` 会被渲染成可点角标，点击后由调用方打开对应依据 ——
 * 让"引用可核验"从"卡里有列表"变成"读到哪里就能点哪里"。
 * 注意这里必须用**事件委托**：内容是 innerHTML，挂不了 React 事件。
 */
export default function LingsiMarkdown({ text, streaming, citeMax = 0, citationIndices, onCite }: Props) {
  const citationKey = citationIndices?.join(',') ?? Array.from({ length: citeMax }, (_, i) => i + 1).join(',')
  const html = useMemo(() => markCitations(renderMarkdown(text), new Set(citationKey ?
    citationKey.split(',').map(Number) : [])), [text, citationKey])
  const contentRef = useStreamReveal(html, streaming, { sourceText: text })
  if (!text && !streaming) return null
  return (
    <div
      className="lingsi-reply"
      onClick={(e) => {
        if (!onCite) return
        const el = (e.target as HTMLElement).closest('.lingsi-cite') as HTMLElement | null
        if (el?.dataset.idx) onCite(Number(el.dataset.idx))
      }}
      onKeyDown={(e) => {
        if (!onCite || (e.key !== 'Enter' && e.key !== ' ')) return
        const el = (e.target as HTMLElement).closest('.lingsi-cite') as HTMLElement | null
        if (el?.dataset.idx) {
          e.preventDefault()
          onCite(Number(el.dataset.idx))
        }
      }}
    >
      <span ref={contentRef} />
      {streaming && <span className="lingsi-cursor" />}
    </div>
  )
}
