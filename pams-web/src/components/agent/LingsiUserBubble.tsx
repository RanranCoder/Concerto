import { App, Tooltip } from 'antd'
import type { AgentChatRef } from '@/api/agent'
import { Copy, Pencil } from './LingsiIcons'

interface Props {
  text: string
  /** 本条消息实际附带的上下文引用（UI v2 `+`）：只作展示，不可点 —— 真正可交互的入口在右栏与产物卡 */
  attachedRefs?: AgentChatRef[]
  /** 编辑并重新发送：把原文放回输入框（**不自动发送**，用户改完自己回车） */
  onEdit?: (text: string) => void
}

/**
 * 用户提问（UI v2 §B1）。
 *
 * <p><b>从"右对齐红底气泡"改成"左对齐引用块"</b>，三条理由：
 * <ol>
 *   <li>灵思的输入多为长指令（"帮我把第四十期培训班的开班仪式任务都生成出来，名单以最新导入的为准"），
 *       76% 宽的气泡会折成三四行且起点参差，读起来累；引用块满宽、左对齐，行首整齐。</li>
 *   <li>品牌红是这个灰阶体系里<b>对比度最高</b>的颜色。用在每一条用户消息上，
 *       等于把"强调"用成了"常态"，选中竖条与不可逆警示就都不显眼了。</li>
 *   <li>与 AI 的文档流同栏对齐，整条会话读起来像<b>工作记录</b>而不是聊天记录 ——
 *       这和"左栏是任务不是会话"是同一个心智模型的两面。</li>
 * </ol>
 *
 * <p>hover 才露出「复制 / 编辑并重新发送」：这两个动作只在回看时才有用。
 * 保留换行，不做 Markdown 渲染（用户输入按纯文本对待）。
 */
export default function LingsiUserBubble({ text, attachedRefs, onEdit }: Props) {
  const { message } = App.useApp()

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(text)
      message.success('已复制')
    } catch {
      message.warning('复制失败，请手动选择文本')
    }
  }

  return (
    <div className="lingsi-user-quote">
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
      <div className="lingsi-user-quote-actions">
        <Tooltip title="复制">
          <button type="button" className="lingsi-icon-btn" onClick={() => void copy()}>
            <Copy size={13} />
          </button>
        </Tooltip>
        {onEdit && (
          <Tooltip title="编辑并重新发送">
            <button type="button" className="lingsi-icon-btn" onClick={() => onEdit(text)}>
              <Pencil size={13} />
            </button>
          </Tooltip>
        )}
      </div>
    </div>
  )
}
