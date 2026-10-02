import { EditOutlined } from '@ant-design/icons'
import type { TimelineItem } from '@/stores/agent'
import { Thinking, Tool, Check, Cross, Plan, Aborted } from './LingsiIcons'

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
    default:
      return <Tool />
  }
}

/**
 * 过程区的一行。
 *
 * 展示规则（灵思 §7.1）：
 * - 思考中：三点脉冲，不用 spinner（避免每行都在转，视觉噪声大）
 * - 工具执行中：显示「正在…」，完成后补耗时
 * - 失败：错误文案直接跟在后面（不折叠，否则用户不知道为什么没结果）
 */
export default function LingsiTimelineRow({ item }: Props) {
  const running = !item.completed
  const failed = Boolean(item.error)
  const awaitingInput = item.completed && item.status === 'FORM'

  return (
    <div className="lingsi-tl-row">
      <span
        className="lingsi-tl-icon"
        style={failed ? { color: 'var(--color-red)' } : awaitingInput ? { color: '#faad14' } : undefined}
      >
        {icon(item)}
      </span>
      <span className={`lingsi-tl-label${running || failed ? ' lingsi-tl-label-active' : ''}`}>
        {item.label}
        {running && item.kind === 'tool' && (
          <span style={{ marginLeft: 6, display: 'inline-flex', gap: 3, verticalAlign: 'middle' }}>
            <span className="lingsi-dot" />
            <span className="lingsi-dot" />
            <span className="lingsi-dot" />
          </span>
        )}
        {failed && <span style={{ color: 'var(--color-red)' }}> · {item.error}</span>}
        {awaitingInput && <span style={{ color: '#faad14' }}> · 等待填写表单</span>}
      </span>
      {item.completed && !failed && !awaitingInput && typeof item.durationMs === 'number' && item.durationMs > 0 && (
        <span className="lingsi-tl-meta">{item.durationMs}ms</span>
      )}
    </div>
  )
}
