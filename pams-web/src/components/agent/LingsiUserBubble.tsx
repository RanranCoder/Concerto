import { Tooltip } from 'antd'
import type { AgentChatRef } from '@/api/agent'
import { Pencil } from './LingsiIcons'

import LingsiCopyButton from './LingsiCopyButton'

interface Props {
  text: string
  /** 本条消息实际附带的上下文引用（UI v2 `+`）：只作展示，不可点 —— 真正可交互的入口在右栏与产物卡 */
  attachedRefs?: AgentChatRef[]
  /** 编辑并重新发送：把原文放回输入框（**不自动发送**，用户改完自己回车） */
  onEdit?: (text: string) => void
}

/** 用户消息：右对齐蓝色气泡，保留纯文本换行；操作置于气泡下方。 */
export default function LingsiUserBubble({ text, attachedRefs, onEdit }: Props) {
  return (
    <div className="lingsi-user-quote">
      <div className="lingsi-user-bubble">
      {attachedRefs && attachedRefs.length > 0 && (
        <div className="lingsi-quote-refs">
          {attachedRefs.map((r) => (
            <span className="lingsi-quote-ref" key={`${r.type}-${r.id}`}>
              {r.label || `${r.type} ${r.id}`}
            </span>
          ))}
        </div>
      )}
      <div className="lingsi-user-quote-text">{text}</div>
      </div>
      <div className="lingsi-user-quote-actions">
        <LingsiCopyButton text={text} size={13} />
        {onEdit && (
          <Tooltip title="编辑并重新发送">
            <button type="button" className="lingsi-icon-btn" aria-label="编辑并重新发送" onClick={() => onEdit(text)}>
              <Pencil size={13} />
            </button>
          </Tooltip>
        )}
      </div>
    </div>
  )
}
