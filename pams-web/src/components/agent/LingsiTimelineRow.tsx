import { EditOutlined } from '@ant-design/icons'
import type { TimelineItem } from '@/stores/agent'
import { agentColorOf, agentShortOf } from '@/styles/categoryColors'
import { Thinking, Tool, Check, Cross, Plan, Aborted, Sparkles } from './LingsiIcons'

interface Props {
  item: TimelineItem
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
 * 过程区的一行。
 *
 * 展示规则（灵思 §7.1）：
 * - 思考中：三点脉冲，不用 spinner（避免每行都在转，视觉噪声大）
 * - 工具执行中：显示「正在…」，完成后补耗时
 * - 失败：错误文案直接跟在后面（不折叠，否则用户不知道为什么没结果）
 *
 * 协作内聚后新增三类行（kind = collab / agent / review）：
 * - `collab`：本次为何升级为协作（可选说明），带模式标签
 * - `agent` ：某个专家 Agent 的进行/完成，左侧用身份色头像替代通用图标
 * - `review`：审查结论，直接内联一张结论块（PASS / REVISE + 理由）
 * 这三类只在标准模式被后端判定升级为协作时出现，单 Agent 轮次不会有。
 */
export default function LingsiTimelineRow({ item }: Props) {
  const running = !item.completed
  const failed = Boolean(item.error)
  const awaitingInput = item.completed && item.status === 'FORM'

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

  return (
    <div className="lingsi-tl-row">
      <span
        className="lingsi-tl-icon"
        style={failed ? { color: 'var(--brand)' } : awaitingInput ? { color: 'var(--warn)' } : undefined}
      >
        {agentName ? <AgentAvatar name={agentName} label={item.label} /> : icon(item)}
      </span>
      <span className={`lingsi-tl-label${running || failed ? ' lingsi-tl-label-active' : ''}`}>
        {item.label}
        {item.kind === 'collab' && item.status && (
          <span className="lingsi-tl-mode">{item.status}</span>
        )}
        {running && (item.kind === 'tool' || item.kind === 'agent') && (
          <span style={{ marginLeft: 6, display: 'inline-flex', gap: 3, verticalAlign: 'middle' }}>
            <span className="lingsi-dot" />
            <span className="lingsi-dot" />
            <span className="lingsi-dot" />
          </span>
        )}
        {failed && <span style={{ color: 'var(--brand)' }}> · {item.error}</span>}
        {awaitingInput && <span style={{ color: 'var(--warn)' }}> · 等待填写表单</span>}
      </span>
      {item.completed && !failed && !awaitingInput && typeof item.durationMs === 'number' && item.durationMs > 0 && (
        <span className="lingsi-tl-meta">{item.durationMs}ms</span>
      )}
    </div>
  )
}
