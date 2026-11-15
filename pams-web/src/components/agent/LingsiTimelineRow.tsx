import { useId, useState } from 'react'
import { EditOutlined, FileTextOutlined } from '@ant-design/icons'
import type { TimelineItem } from '@/stores/agent'
import { agentColorOf, agentShortOf } from '@/styles/categoryColors'
import { formatDuration } from '@/utils/thinking'
import { useStreamReveal } from '@/hooks/useStreamReveal'
import { Thinking, Tool, Check, Cross, Plan, Aborted, Sparkles } from './LingsiIcons'

interface Props {
  item: TimelineItem
  visible?: boolean
  settled?: boolean
}

/** 长推理、换行或已被标题裁剪的推理需要展开；完整短句直接展示。 */
const EXPAND_THRESHOLD = 120

const TOOL_STATUS: Record<string, string> = {
  FORM: '待补充信息', WAITING_INPUT: '待补充信息', PENDING_CONFIRM: '待确认', SUCCESS: '已执行', PARTIAL: '部分完成',
  FAILED: '失败', DENIED: '未获授权', TIMEOUT: '超时', CANCELLED: '已取消', SKIPPED: '已跳过',
  NEEDS_RECONCILE: '结果待核对',
}

function icon(item: TimelineItem) {
  switch (item.kind) {
    case 'thinking':
      return <Thinking />
    case 'tool':
      // FORM：等用户在弹窗里补全参数（D2.5），琥珀色笔形提示，不算成功也不算失败
      if (item.completed && (item.status === 'FORM' || item.status === 'WAITING_INPUT')) return <EditOutlined />
      if (item.status === 'PENDING_CONFIRM') return <Plan />
      if (item.status === 'CANCELLED' || item.status === 'SKIPPED') return <Aborted />
      return item.completed ? (toolFailed(item) ? <Cross /> : <Check />) : <Tool />
    case 'plan':
      return <Plan />
    case 'error':
      return <Cross />
    case 'aborted':
      return <Aborted />
    case 'collab':
      return <Sparkles />
    case 'agent':
      return item.completed ? <Check /> : <Tool />
    case 'review':
      return item.verdict === 'PASS' ? <Check /> : <Cross />
    default:
      return <Tool />
  }
}

function toolFailed(item: TimelineItem): boolean {
  return Boolean(item.error) || ['FAILED', 'DENIED', 'TIMEOUT'].includes(item.status ?? '')
}

/** 协作者头像：单字 + 身份色（与团队面板、消息身份标识同一套色） */
function AgentAvatar({ name, label }: { name: string; label: string }) {
  return (
    <span className="lingsi-tl-agent-av" style={{ background: agentColorOf(name) }}>
      {agentShortOf(name, label)}
    </span>
  )
}

/**
 * 思维链行：折叠只显示标题（推理摘要首句 / 编排旁白一句），推理可展开读全文。
 *
 * <p>两种行共用这一个组件，靠**有没有 `detail`** 区分（与 {@code groupTimeline} 同一判据）：
 * <ul>
 *   <li>推理摘要（V35）：折叠只显示摘要，展开后用中性标题与完整原文替换摘要</li>
 *   <li>编排旁白（V36）：「正在理解你的问题…」这类后端自述，只有一句标题，没有可展开的内容</li>
 * </ul>
 * 不分成两个组件是刻意的：它们的折叠态是同一行结构（图标 + 标题 + 耗时），
 * 拆开就会出现"旁白与推理的行高/缩进不一致"这种只有肉眼能发现的差异。
 */
function ThinkingRow({ item, visible = true, settled = false }: Props) {
  const [open, setOpen] = useState(false)
  const bodyId = useId()
  const text = (item.detail ?? '').trim()
  const expandable = Boolean(text) && (
    text.length > EXPAND_THRESHOLD || text.includes('\n') || text !== item.label.trim()
  )
  const expanded = expandable && open
  const duration = formatDuration(item.durationMs)
  const streaming = Boolean(text) && !item.completed
  const headline = expandable ? item.label : text || item.label
  const finished = settled || item.completed
  const headlineRef = useStreamReveal(headline, streaming, { plainText: true, visible: visible && !expanded, settled: finished })
  const contentRef = useStreamReveal(text, streaming, { plainText: true, visible: visible && expanded, settled: finished })

  return (
    <div className="lingsi-tl-row lingsi-tl-row-think">
      <span className="lingsi-tl-icon">{icon(item)}</span>
      <div className="lingsi-tl-think">
        <div className="lingsi-tl-think-head" data-expandable={expandable}>
          <span className="lingsi-tl-label" ref={headlineRef} hidden={expanded} />
          {expanded && <span className="lingsi-tl-label">推理摘要</span>}
          {expandable && (
            <button
              type="button"
              className="lingsi-tl-think-toggle"
              onClick={() => setOpen((v) => !v)}
              aria-expanded={expanded}
              aria-controls={bodyId}
            >
              {expanded ? '收起摘要' : '展开摘要'}
            </button>
          )}
          {duration && <span className="lingsi-tl-meta">{duration}</span>}
        </div>
        {expandable && (
          <div className="pams-collapse" data-open={expanded} aria-hidden={!expanded} {...(!expanded ? { inert: '' } : {})}>
            <div className="pams-collapse-inner">
              <div className="lingsi-tl-think-body" id={bodyId} role="region" aria-label="推理摘要"><span ref={contentRef} /></div>
            </div>
          </div>
        )}
      </div>
    </div>
  )
}

/**
 * 过程区的一行。
 *
 * 展示规则（灵思 §7.1）：
 * - 工具执行中：显示「正在…」，完成后补耗时
 * - 失败：**用语义色 --danger**（此前用主色 --brand 表示失败，与"蓝色只做记号"的原则冲突）
 * - 耗时一律走 formatDuration：此前摘要用秒、行内用毫秒原文，同一个数字两种写法
 * - 自由文本（label / error）限高两行，但**带错误或等待补全的行不限高** ——
 *   失败原因被折叠掉，比排版难看严重得多
 *
 * 协作内聚后新增三类行（kind = collab / agent / review）：
 * - `collab`：本次为何升级为协作（可选说明），带模式标签
 * - `agent` ：某个专家 Agent 的进行/完成，左侧用身份色头像替代通用图标
 * - `review`：审查结论，直接内联一张结论块（PASS / REVISE + 理由）
 * 这三类只在标准模式被后端判定升级为协作时出现，单 Agent 轮次不会有。
 * （其中 agent/collab 行在团队面板同时渲染时由 LingsiProcessZone 让位，避免同一屏出现两遍）
 */
export default function LingsiTimelineRow({ item, visible = true, settled }: Props) {
  const [targetsOpen, setTargetsOpen] = useState(false)
  const targetsId = useId()

  const running = !item.completed
  const failed = toolFailed(item)
  const statusLabel = item.kind === 'tool' && item.status ? TOOL_STATUS[item.status] : undefined

  // 思维链行单独渲染：它是唯一"内容比标题长"的行类型（可展开读原文）
  if (item.kind === 'thinking' && (item.detail || item.label)) {
    return <ThinkingRow item={item} visible={visible} settled={settled} />
  }

  // 审查结论单独特化：它承载的是「能不能过」，值得一块可读的结论区
  if (item.kind === 'review') {
    const pass = item.verdict === 'PASS'
    return (
      <div className="lingsi-tl-row lingsi-tl-row-review">
        <span className="lingsi-tl-icon" style={{ color: pass ? 'var(--ok)' : 'var(--warn)' }}>
          {icon(item)}
        </span>
        <div className={`lingsi-review ${pass ? 'lingsi-review-pass' : ''}`}>
          <div className="lingsi-review-head">
            <AgentAvatar name="reviewer" label="审" />
            <b>审查</b>
            <span className={`tone-tag ${pass ? 'tone-success' : 'tone-warning'}`}>
              {pass ? 'PASS · 通过' : 'REVISE · 需修订'}
            </span>
          </div>
          {item.reason && <div className="lingsi-review-body">{item.reason}</div>}
        </div>
      </div>
    )
  }

  const agentName = item.kind === 'agent' ? item.agent ?? '' : ''
  const duration = formatDuration(item.durationMs)
  const targets = item.targets ?? []
  const more = targets.length - 3

  return (
    <div className={`lingsi-tl-item${failed ? ' lingsi-tl-row-failed' : ''}`}>
      <div className="lingsi-tl-row">
        <span className="lingsi-tl-icon">
          {agentName ? <AgentAvatar name={agentName} label={item.label} /> : icon(item)}
        </span>
        <span
          className={`lingsi-tl-label${running || failed ? ' lingsi-tl-label-active' : ''}${
            failed ? '' : ' lingsi-tl-label-clamp'
          }`}
        >
          {item.label}
          {item.kind === 'collab' && item.status && <span className="lingsi-tl-mode">{item.status}</span>}
          {statusLabel && <span className="lingsi-tl-tool-status" data-status={item.status}>{statusLabel}</span>}
          {item.error && <span className="lingsi-tl-error"> {item.error}</span>}
        </span>
        {item.completed && duration && (
          <span className="lingsi-tl-meta">{duration}</span>
        )}
      </div>
      {(item.resultSummary || targets.length > 0) && (
        <div className="lingsi-tl-result">
          {item.resultSummary && <p>{item.resultSummary}</p>}
          {targets.length > 0 && (
            <ul id={targetsId} aria-label={`${item.label}的相关对象`}>
              {(targetsOpen ? targets : targets.slice(0, 3)).map(name => (
                <li key={name}><FileTextOutlined aria-hidden="true" /><span>{name}</span></li>
              ))}
            </ul>
          )}
          {more > 0 && (
            <button type="button" className="lingsi-tl-target-toggle" aria-expanded={targetsOpen} aria-controls={targetsId}
              onClick={() => setTargetsOpen(open => !open)}>
              {targetsOpen ? '收起' : `查看其余 ${more} 项`}
            </button>
          )}
        </div>
      )}
    </div>
  )
}
