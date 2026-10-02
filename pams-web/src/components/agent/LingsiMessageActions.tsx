import { useState } from 'react'
import { App, Tooltip } from 'antd'
import { Copy, Reload, ThumbUp } from './LingsiIcons'

interface Props {
  /** 本条回复的正文（复制用；不含卡片内容） */
  text: string
  /**
   * 再问一次（图标沿用「重新生成」的语义位）。
   *
   * ⚠️ 它与 Trae 的「重新生成」**不是同一件事**，文案必须如实：
   * Trae 是替换上一条回答；灵思后端没有"重生成"接口，这里是**以相同问题新增一轮**。
   * 更关键的是只对最后一条开放 —— 历史消息再问一次会在会话末尾追加一轮，
   * 而用户以为自己动的是中间那条，这种"操作对象与视觉对象不一致"是必须避免的。
   */
  onRegenerate?: () => void
  /** 流式进行中不显示动作行（内容还没定稿，复制到的是半截） */
  hidden?: boolean
}

/**
 * 消息动作行 + 免责声明（UI v2 §G）。
 *
 * 常驻低对比（opacity .5，hover 消息时提到 1）——动作不该抢内容的注意力，
 * 但也不能藏到"要用时找不到"。这一条直接照搬 Trae Work。
 *
 * `由灵思生成` **不参与 hover 显隐**：它是合规声明，必须恒可见。
 */
export default function LingsiMessageActions({ text, onRegenerate, hidden }: Props) {
  const { message } = App.useApp()
  const [voted, setVoted] = useState<'up' | 'down' | null>(null)

  if (hidden) return null

  const copy = async () => {
    const body = (text ?? '').trim()
    if (!body) return
    try {
      if (navigator.clipboard?.writeText) {
        await navigator.clipboard.writeText(body)
      } else {
        // 非安全上下文（局域网 http 访问）没有 clipboard API —— 退回旧方案，
        // 否则用户会看到"复制没反应"而且不知道为什么
        const ta = document.createElement('textarea')
        ta.value = body
        ta.style.position = 'fixed'
        ta.style.opacity = '0'
        document.body.appendChild(ta)
        ta.select()
        document.execCommand('copy')
        document.body.removeChild(ta)
      }
      message.success('已复制')
    } catch {
      message.warning('复制失败，请手动选择文本')
    }
  }

  return (
    <div className="lingsi-msg-actions">
      <Tooltip title="复制">
        <button type="button" className="lingsi-icon-btn" onClick={() => void copy()}>
          <Copy size={13.5} />
        </button>
      </Tooltip>
      <Tooltip title={voted === 'up' ? '已记录：有帮助' : '有帮助'}>
        <button
          type="button"
          className={`lingsi-icon-btn${voted === 'up' ? ' lingsi-icon-btn-on' : ''}`}
          onClick={() => setVoted((v) => (v === 'up' ? null : 'up'))}
        >
          <ThumbUp size={13.5} />
        </button>
      </Tooltip>
      {onRegenerate && (
        <Tooltip title="再问一次（以相同问题新增一轮对话）">
          <button type="button" className="lingsi-icon-btn" onClick={onRegenerate}>
            <Reload size={13.5} />
          </button>
        </Tooltip>
      )}
      <span className="lingsi-gen-note">由灵思生成</span>
    </div>
  )
}
