import { useEffect, useId, useRef, useState } from 'react'
import type { TimelineItem } from '@/stores/agent'
import {
  groupTimeline,
  hasTeamPanel,
  summarizeTimeline,
} from '@/utils/thinking'
import { Caret, Thinking } from './LingsiIcons'
import LingsiTimelineRow from './LingsiTimelineRow'

interface Props {
  items: TimelineItem[]
  /** 该轮仍在进行（未收到 MESSAGE_DONE / ERROR / ABORTED） */
  active?: boolean
  resultStarted?: boolean
  teamOverview?: boolean
}

/** 结束后延迟自动收起的时长：一轮刚结束时正是用户最想看步骤的时刻 */
const COLLAPSE_DELAY_MS = 1200

/** 段小标题：旁白段是"编排"，推理段按出现顺序编号，其余是"执行" */
function groupTitles(groups: ReturnType<typeof groupTimeline>): string[] {
  let reasoning = 0
  return groups.map((group) => {
    if (group.kind === 'narration') return '编排'
    if (group.kind === 'reasoning') {
      reasoning += 1
      return `第 ${reasoning} 段推理`
    }
    return '执行'
  })
}

/**
 * 过程区（灵思 §7.1 双形态）—— 思维链 + 执行步骤的完整时间轴。
 *
 * 编排规则（§3.5.3 规则 5）：
 * 1. 过程区永远在结果区「上方」，且最早到达 —— THINKING 事件先行，保证 TTFE P95 < 800ms 有东西可看
 * 2. 进行中：默认展开、实时追加，头部显示「正在推理 · <当段标题>」
 * 3. 结束后：**延迟 {@link COLLAPSE_DELAY_MS}ms** 再折叠为一行摘要（推理段数 / 能力数 / 总耗时），点击可回看
 * 4. 有失败项时：不自动折叠（用户需要立刻看到哪里错了）
 * 5. 一次都没有过程事件时不渲染（纯闲聊不值得占位）；但**进行中**要占位一行，
 *    否则首字延迟窗口里用户面对的是一片空白
 * 6. 推理/旁白按段渲染（见 groupTimeline）：一次回答里模型可能想多轮，
 *    平铺成一条长列表就看不出"哪几步属于同一次思考"
 * 7. **去重**：团队面板同时渲染时，把 agent/collab 行让给面板（见 hasTeamPanel）——
 *    同一屏里"检索专家"出现两遍、协作理由出现两遍，是纯噪声
 */
export default function LingsiProcessZone({ items, active, resultStarted = false, teamOverview = false }: Props) {
  // 用户手动干预过就不再自动折叠（尊重用户操作），新一轮开始时复位
  const [manual, setManual] = useState<boolean | null>(null)
  const [autoOpen, setAutoOpen] = useState(Boolean(active))
  const bodyId = useId()
  const hasError = items.some((i) => i.kind === 'error' || i.error)
  // 去重判据与团队面板共用（utils/thinking.ts），两处必然一致
  const teamPanel = teamOverview || hasTeamPanel(items)

  const wasActive = useRef(Boolean(active))
  useEffect(() => {
    const startedNow = Boolean(active) && !wasActive.current
    wasActive.current = Boolean(active)
    if (active) {
      setAutoOpen(true)
      // 新一轮开始 → 清掉上一轮的手动覆盖，否则"上次点过收起"会永久生效
      if (startedNow) setManual(null)
      return
    }
    if (hasError) return
    const timer = window.setTimeout(() => setAutoOpen(false), COLLAPSE_DELAY_MS)
    return () => window.clearTimeout(timer)
  }, [active, hasError])

  const open = manual ?? (hasError || autoOpen)

  if (!items.length) {
    if (!active) return null
    return (
      <div className="lingsi-process">
        <span className="lingsi-process-head lingsi-process-head-live" aria-live="polite">
          <span className="lingsi-process-slot" aria-hidden="true">
            <Thinking size={13} />
          </span>
          <span className="lingsi-process-slot" aria-hidden="true" />
          <span>{resultStarted ? '正在生成结果…' : '正在理解你的问题…'}</span>
        </span>
      </div>
    )
  }

  // 让位给团队面板的行：只影响**渲染**（摘要/分段都按可见行算，避免摘要里的数字与看到的行不符）
  const visible = teamPanel ? items.filter((i) => i.kind !== 'agent' && i.kind !== 'collab') : items
  const groups = groupTimeline(visible)
  const titles = groupTitles(groups)
  const summary = (active && resultStarted ? '正在生成结果…' : summarizeTimeline(visible, active))
    .replaceAll(' · ', ' / ').replace(/^已完成/, teamOverview ? '已记录' : '已完成')

  return (
    <div className="lingsi-process">
      <button
        type="button"
        className={`lingsi-process-head${active ? ' lingsi-process-head-live' : ''}`}
        onClick={() => setManual(!open)}
        aria-expanded={open}
        aria-controls={bodyId}
      >
        {/* 固定槽位：进行中放脉冲图标、结束后留空 —— 图标出现/消失不再推动文字左右跳 */}
        <span className="lingsi-process-slot" aria-hidden="true">
          {active ? <Thinking size={13} /> : null}
        </span>
        <Caret size={13} open={open} />
        <span><span className="lingsi-process-label">执行过程</span> {summary}</span>
      </button>

      <div className="pams-collapse" data-open={open} aria-hidden={!open} {...(!open ? { inert: '' } : {})}>
        <div className="pams-collapse-inner">
        <div className="lingsi-process-body" id={bodyId} role="group" aria-label="推理摘要与执行步骤">
          {teamPanel && <div className="lingsi-tl-hint">专家分工见上方协作概览</div>}
          {groups.map((group, index) => (
            <div className="lingsi-tl-group" key={group.key}>
              {groups.length > 1 && <div className="lingsi-tl-group-head">{titles[index]}</div>}
              {group.items.map((item) => (
                <LingsiTimelineRow key={item.key} item={item} visible={open}
                  settled={!active || resultStarted || item !== items.at(-1)} />
              ))}
            </div>
          ))}
        </div>
        </div>
      </div>
    </div>
  )
}
