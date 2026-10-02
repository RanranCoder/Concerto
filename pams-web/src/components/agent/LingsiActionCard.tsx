import { useState } from 'react'
import { Button, Drawer, Tooltip } from 'antd'
import type { AgentActionPlan } from '@/api/agent'
import { stripArgsMarker } from '@/utils/markdown'
import { useAgentPanel } from '@/stores/agentPanel'
import { useNarrow } from '@/hooks/useMediaQuery'
import LingsiMarkdown from './LingsiMarkdown'
import { Lock, Warn } from './LingsiIcons'

interface Props {
  plan: AgentActionPlan
  busy?: boolean
  /** 只读模式：清单仍展示（它是真实存在的待办），但不可确认 */
  readonly?: boolean
  onConfirm: () => void
  onCancel: () => void
}

const ITEM_LIMIT = 12

/**
 * 拟执行清单卡（灵思 §4）。
 *
 * <p>硬约束（一条都没放松）：
 * <ol>
 *   <li>只依赖 AgentActionPlan 结构 —— 新增写类工具前端零改动（D11）</li>
 *   <li>跳过项（skipped）必现且不进折叠（§4.3）——
 *       「确认时说 50 人、执行后只加 38 人」是本卡要杜绝的事</li>
 *   <li>不可逆操作必须显式警示（reversible=false）</li>
 *   <li>明细详情默认折叠（长列表会淹没决策），但可一键展开</li>
 * </ol>
 *
 * <p>UI v2 的两处改动：
 * <ul>
 *   <li>「查看全文」从 720px Drawer 遮罩改为**右侧面板分屏**（宽屏下），
 *       让"一边看清单一边通读正文"成为可能 —— 这正是"建议通读后再确认"落地的前提；
 *       窄屏没有分屏空间，退回 Drawer（行为分叉，不是样式分叉）。</li>
 *   <li>只读模式下**不隐藏清单**、只禁用确认：清单是后端持久化的真实任务，
 *       藏起来又冒出来会让人以为任务丢了。</li>
 * </ul>
 */
export default function LingsiActionCard({ plan, busy, readonly, onConfirm, onCancel }: Props) {
  const [detailOpen, setDetailOpen] = useState(false)
  const [previewOpen, setPreviewOpen] = useState(false)
  const openText = useAgentPanel((s) => s.openText)
  const narrow = useNarrow()

  const detailLines = stripArgsMarker(plan.detailLines)
  const shown = plan.items.slice(0, ITEM_LIMIT)
  const rest = plan.items.length - shown.length
  /** 内容类工具（策划书/推文）会带正文预览：确认前应能通读全文 */
  const preview = (plan.preview ?? '').trim()

  const viewFullText = () => {
    if (narrow) {
      setPreviewOpen(true)
      return
    }
    // 分屏：对话不被遮挡，用户可以对照清单核正文
    openText(`plan-${plan.planId}`, plan.summary || '内容预览', preview)
  }

  return (
    <div className="lingsi-plan">
      <div className="lingsi-plan-head">
        <Warn size={14} />
        <span className="lingsi-plan-title">即将执行的操作</span>
        <span className="lingsi-plan-summary">
          {plan.summary}
          {plan.total > 0 && ` · 共 ${plan.total} 项`}
        </span>
      </div>

      {/* ---- 内容预览入口（§3.9.1）：策划书/推文这类长文，确认前先通读 ---- */}
      {preview.length > 0 && (
        <div className="lingsi-plan-sec">
          <Button size="small" type="link" style={{ padding: 0 }} onClick={viewFullText}>
            查看全文（约 {preview.length} 字）
          </Button>
          <span className="lingsi-plan-more" style={{ marginLeft: 8 }}>
            这是即将写入的内容，建议通读后再确认
          </span>
        </div>
      )}

      {/* ---- 跳过项：必现、不可折叠（§4.3） ---- */}
      {plan.skipped?.length > 0 && (
        <div className="lingsi-plan-sec">
          <div className="lingsi-plan-sec-title">
            将跳过 {plan.skipped.length} 项（请在确认前核对）
          </div>
          {plan.skipped.map((s, i) => (
            <div className="lingsi-skip" key={`${s.label}-${i}`}>
              <span className="lingsi-skip-dot">•</span>
              <span>
                <span className="lingsi-skip-label">{s.label}</span>
                {s.sub && <span className="lingsi-skip-reason"> · {s.sub}</span>}
                {s.reason && <span className="lingsi-skip-reason"> —— {s.reason}</span>}
              </span>
            </div>
          ))}
        </div>
      )}

      {/* ---- 将执行项 ---- */}
      {shown.length > 0 && (
        <div className="lingsi-plan-sec">
          <div className="lingsi-plan-sec-title">将执行 {plan.items.length} 项</div>
          {shown.map((it, i) => (
            <div className="lingsi-plan-item" key={it.id ?? `idx-${i}`}>
              <span className="lingsi-plan-item-no">{i + 1}.</span>
              <span>
                <span className="lingsi-plan-item-label">{it.label}</span>
                {it.sub && <span className="lingsi-plan-item-sub"> · {it.sub}</span>}
              </span>
            </div>
          ))}
          {rest > 0 && <div className="lingsi-plan-more">…另有 {rest} 项</div>}
        </div>
      )}

      {/* ---- 明细（默认折叠） ---- */}
      {detailLines.length > 0 && (
        <>
          <div className="lingsi-plan-more" onClick={() => setDetailOpen((v) => !v)} role="button" tabIndex={0}>
            {detailOpen ? '收起明细 ▲' : `查看明细 ${detailLines.length} 行 ▼`}
          </div>
          {detailOpen && <div className="lingsi-plan-detail">{detailLines.join('\n')}</div>}
        </>
      )}

      <div className="lingsi-plan-foot">
        {readonly ? (
          <span className="lingsi-plan-warn">
            <Lock size={13} />
            当前是只读模式，不会执行写操作；切到「标准模式」后可继续确认
          </span>
        ) : plan.reversible === false ? (
          <span className="lingsi-plan-warn">
            <Warn size={13} />
            此操作不可撤销，请确认无误后再执行
          </span>
        ) : (
          <span className="lingsi-plan-summary">确认后立即执行，可随时在会话中查看执行结果</span>
        )}
        <span className="lingsi-plan-acts">
          {preview.length > 0 && (
            <Button size="small" onClick={viewFullText} disabled={busy}>
              查看全文
            </Button>
          )}
          <Button size="small" onClick={onCancel} disabled={busy}>
            取消
          </Button>
          <Tooltip
            title={
              readonly
                ? '只读模式下不执行写操作'
                : plan.reversible === false
                  ? '不可逆操作，请确认'
                  : ''
            }
          >
            <Button size="small" type="primary" loading={busy} disabled={readonly} onClick={onConfirm}>
              确认执行
            </Button>
          </Tooltip>
        </span>
      </div>

      {/* 窄屏降级：没有分屏空间时仍用抽屉（行为分叉而非样式分叉） */}
      <Drawer
        open={previewOpen}
        onClose={() => setPreviewOpen(false)}
        title={plan.summary || '内容预览'}
        width={720}
        destroyOnHidden
        styles={{ body: { fontSize: 14, lineHeight: 1.8 } }}
      >
        <LingsiMarkdown text={preview} />
      </Drawer>
    </div>
  )
}
