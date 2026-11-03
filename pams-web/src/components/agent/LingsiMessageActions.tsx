import { useState } from 'react'
import { Tooltip } from 'antd'
import { Reload, ThumbUp } from './LingsiIcons'

import LingsiCopyButton from './LingsiCopyButton'

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
  const [voted, setVoted] = useState<'up' | 'down' | null>(null)

  if (hidden) return null


  return (
    <div className="lingsi-msg-actions">
      <LingsiCopyButton text={text.trim()} size={13.5} />
      <Tooltip title={voted === 'up' ? '已记录：有帮助' : '有帮助'}>
        <button
          type="button"
          className={`lingsi-icon-btn${voted === 'up' ? ' lingsi-icon-btn-on' : ''}`}
          aria-label="有帮助"
          aria-pressed={voted === 'up'}
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
      <span className="lingsi-gen-note">由concerto生成</span>
    </div>
  )
}
