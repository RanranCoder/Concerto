import { useId, useState } from 'react'
import { EditOutlined } from '@ant-design/icons'
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

/** 进行中的三点脉冲（共用同一套动画；不放 spinner，避免每行都在转） */
function PendingDots() {
  return (
    <span className="lingsi-dots" aria-hidden="true">
      <span className="lingsi-dot" />
      <span className="lingsi-dot" />
      <span className="lingsi-dot" />
    </span>
  )
}

/** 入参 JSON 美化；解析失败就原样显示（后端理论上给 JSON，但不能因此白屏） */
function prettyArgs(raw: string): string {
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    return raw
  }
}

function icon(item: TimelineItem) {
  switch (item.kind) {
    case 'thinking':
      return <Thinking />
    case 'tool':
      // FORM：等用户在弹窗里补全参数（D2.5），琥珀色笔形提示，不算成功也不算失败
      if (item.completed && item.status === 'FORM') return <EditOutlined />
      return item.completed ? (item.error ? <Cross /> : <Check />) : <Tool />
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

/** 协作者头像：单字 + 身份色（与团队面板、消息身份标识同一套色） */
function AgentAvatar({ name, label }: { name: string; label: string }) {
  return (
    <span className="lingsi-tl-agent-av" style={{ background: agentColorOf(name) }}>
      {agentShortOf(name, label)}
    </span>
  )
}

/**
 * 思维链行：折叠只显示标题（模型推理首句 / 编排旁白一句），推理可展开读全文。
 *
 * <p>两种行共用这一个组件，靠**有没有 `detail`** 区分（与 {@code groupTimeline} 同一判据）：
 * <ul>
 *   <li>模型推理（V35）：折叠只显示摘要，展开后用中性标题与完整原文替换摘要</li>
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
          {expanded && <span className="lingsi-tl-label">推理内容</span>}
          {!finished && <PendingDots />}
          {expandable && (
            <button
              type="button"
              className="lingsi-tl-think-toggle"
              onClick={() => setOpen((v) => !v)}
              aria-expanded={expanded}
              aria-controls={bodyId}
            >
              {expanded ? '收起推理' : '展开推理'}
            </button>
          )}
          {duration && <span className="lingsi-tl-meta">{duration}</span>}
        </div>
        {expandable && (
          <div className="pams-collapse" data-open={expanded} aria-hidden={!expanded} {...(!expanded ? { inert: '' } : {})}>
            <div className="pams-collapse-inner">
              <div className="lingsi-tl-think-body" id={bodyId} role="region" aria-label="推理全文"><span ref={contentRef} /></div>
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
 * - 思考中：三点脉冲，不用 spinner（避免每行都在转，视觉噪声大）
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
  // 入参下钻的展开态：必须在所有 return 之前声明（hooks 不能放在条件分支后）
  const [argsOpen, setArgsOpen] = useState(false)

  const running = !item.completed
  const failed = Boolean(item.error)
  const awaitingInput = item.completed && item.status === 'FORM'

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
  // V36：只有后端下发了入参（normal 级工具）才有下钻入口；null/空串都视为不可下钻。
  // 结果（result）按设计不落库（agent_tool_call 不含结果内容），所以这里只展示入参
  const argsRaw = (item.argsJson ?? '').trim()
  const canDrill = argsRaw.length > 0

  return (
    <div className={`lingsi-tl-item${failed ? ' lingsi-tl-row-failed' : ''}`}>
      <div className="lingsi-tl-row">
        <span className="lingsi-tl-icon">
          {agentName ? <AgentAvatar name={agentName} label={item.label} /> : icon(item)}
        </span>
        <span
          className={`lingsi-tl-label${running || failed ? ' lingsi-tl-label-active' : ''}${
            failed || awaitingInput ? '' : ' lingsi-tl-label-clamp'
          }`}
        >
          {item.label}
          {item.kind === 'collab' && item.status && <span className="lingsi-tl-mode">{item.status}</span>}
          {running && (item.kind === 'tool' || item.kind === 'agent') && <PendingDots />}
          {failed && <span className="lingsi-tl-error"> · {item.error}</span>}
          {awaitingInput && <span className="lingsi-tl-warn"> · 等待填写表单</span>}
          {canDrill && (
            <button
              type="button"
              className="lingsi-tl-args-toggle"
              onClick={() => setArgsOpen((v) => !v)}
              aria-expanded={argsOpen}
            >
              {argsOpen ? '收起入参' : '查看入参'}
            </button>
          )}
        </span>
        {item.completed && !failed && !awaitingInput && duration && (
          <span className="lingsi-tl-meta">{duration}</span>
        )}
      </div>
      {canDrill && argsOpen && (
        <pre className="lingsi-tl-args" aria-label={`${item.label} 的调用入参`}>
          {prettyArgs(argsRaw)}
        </pre>
      )}
    </div>
  )
}
