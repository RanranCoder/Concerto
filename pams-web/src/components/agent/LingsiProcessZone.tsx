import { useState } from 'react'
import type { TimelineItem } from '@/stores/agent'
import { Caret, Thinking } from './LingsiIcons'
import LingsiTimelineRow from './LingsiTimelineRow'

interface Props {
  items: TimelineItem[]
  /** 该轮仍在进行（未收到 MESSAGE_DONE / ERROR / ABORTED） */
  active?: boolean
}

/**
 * 过程区（灵思 §7.1 双形态）。
 *
 * 编排规则（§3.5.3 规则 5）：
 * 1. 过程区永远在结果区「上方」，且最早到达 —— THINKING 事件先行，保证 TTFE P95 < 800ms 有东西可看
 * 2. 进行中：默认展开、实时追加，头部显示「正在思考…」
 * 3. 结束后：默认自动折叠为一行摘要（含工具数与总耗时），点击可回看
 * 4. 有失败项时：不自动折叠（用户需要立刻看到哪里错了）
 * 5. 一次都没有过程事件时不渲染（纯闲聊不值得占位）
 */
export default function LingsiProcessZone({ items, active }: Props) {
  // 用户手动干预过就不再自动折叠（尊重用户操作）
  const [manual, setManual] = useState<boolean | null>(null)

  if (!items.length) return null

  const hasError = items.some((i) => i.kind === 'error' || i.error)
  const toolCount = items.filter((i) => i.kind === 'tool').length
  const totalMs = items.reduce((s, i) => s + (i.durationMs ?? 0), 0)

  const autoOpen = Boolean(active) || hasError
  const open = manual ?? autoOpen

  const summary = (() => {
    if (active) {
      const last = items[items.length - 1]
      return last?.kind === 'thinking' ? '正在思考…' : last?.label ?? '正在处理…'
    }
    if (hasError) return '过程中出现异常'
    const parts: string[] = []
    if (toolCount) parts.push(`调用 ${toolCount} 项能力`)
    if (totalMs) parts.push(`耗时 ${(totalMs / 1000).toFixed(1)}s`)
    return parts.length ? `已完成 · ${parts.join(' · ')}` : '已完成'
  })()

  return (
    <div className="lingsi-process">
      <span
        className={`lingsi-process-head${active ? ' lingsi-process-head-live' : ''}`}
        onClick={() => setManual(!open)}
        role="button"
        tabIndex={0}
        onKeyDown={(e) => {
          if (e.key === 'Enter' || e.key === ' ') setManual(!open)
        }}
      >
        {active && <Thinking size={13} />}
        <Caret size={13} open={open} />
        <span>{summary}</span>
      </span>

      {open && (
        <div className="lingsi-process-body">
          {items.map((it) => (
            <LingsiTimelineRow key={it.key} item={it} />
          ))}
        </div>
      )}
    </div>
  )
}
