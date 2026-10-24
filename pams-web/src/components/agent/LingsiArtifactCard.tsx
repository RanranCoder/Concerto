import { useState } from 'react'
import type { AgentArtifact } from '@/api/agent'
import type { DeltaChip } from '@/stores/agent'
import { useAgentPanel } from '@/stores/agentPanel'
import { ARTIFACT_FALLBACK_COLOR, ARTIFACT_TYPE_COLORS } from '@/styles/categoryColors'
import { Caret, Check, OpenExternal } from './LingsiIcons'

interface Props {
  artifacts: AgentArtifact[]
  /** 卡片头的变更量标签（由 store 从 CommitResult.detail 派生） */
  deltas?: DeltaChip[]
  /** 默认展开阈值：产物多于这个数量时默认折叠 */
  openThreshold?: number
}

/** 实体类型 → 中文名（tab / 副行文案用） */
const TYPE_LABEL: Record<string, string> = {
  activity: '活动',
  member: '成员',
  article: '推文',
  seatLayout: '座位表',
  announcement: '公告',
  notification: '通知',
  plan: '策划书',
  entity: '内容',
}

/**
 * 实体类型 → 识别色块（首字 + 底色）。
 *
 * 色值集中在 styles/categoryColors.ts：这是**分类 identity 色**（只承担「分辨类型」），
 * 不是语义色，所以刻意用固定 hex 而非 var(--brand) 这类变量 ——
 * ① CSS 变量是 token 级替换，`var(--x)55` 不会合成 8 位 hex（项目已踩过）；
 * ② 跟着主题/组织主色变会让不同类型之间更难分辨。
 */
function glyph(a: AgentArtifact): { text: string; bg: string } {
  const label = TYPE_LABEL[a.entityType] ?? '产物'
  return {
    text: label.slice(0, 1),
    bg: ARTIFACT_TYPE_COLORS[a.entityType] ?? ARTIFACT_FALLBACK_COLOR,
  }
}

/**
 * 产物卡（UI v2 §C）—— 对应 Trae Work 的「N 个文件已更改」。
 *
 * <p>替代原来的单行绿字回执（`.lingsi-notice`）。为什么值得做成卡片：
 * 用户确认一份批量操作后，最要紧的两个问题是「到底成没成」和「改了哪几样东西」。
 * 一行文案只能回答前者；后者一旦缺位，用户就必须自己去活动页翻一遍才能确认。
 *
 * <p>另一条约束：**变更量是卡片级属性，不是行级属性**。
 * 后端 `CommitResult.detail` 给出的是"本次共写了 41 条"，把它摊到每行产物上
 * （3 个产物各 +41）就是在撒谎，所以 41 只出现在卡片头。
 */
export default function LingsiArtifactCard({ artifacts, deltas, openThreshold = 6 }: Props) {
  const openArtifact = useAgentPanel((s) => s.openArtifact)
  const [open, setOpen] = useState(artifacts.length <= openThreshold)

  if (!artifacts?.length) return null

  return (
    <div className={`lingsi-artifact${open ? ' lingsi-artifact-open' : ''}`}>
      <div
        className="lingsi-artifact-head"
        role="button"
        tabIndex={0}
        aria-expanded={open}
        onClick={() => setOpen((v) => !v)}
        onKeyDown={(e) => {
          if (e.key === 'Enter' || e.key === ' ') setOpen((v) => !v)
        }}
      >
        <span className="lingsi-artifact-ic">
          <Check size={12} />
        </span>
        <span className="lingsi-artifact-title">{artifacts.length} 项变更已产生</span>
        {deltas && deltas.length > 0 && (
          <span className="lingsi-artifact-deltas">
            {deltas.map((d) => (
              <span key={d.label} className={`lingsi-delta lingsi-delta-${d.tone}`}>
                {d.label}
              </span>
            ))}
          </span>
        )}
        <Caret size={13} open={open} />
      </div>

      <div className="pams-collapse" data-open={open} aria-hidden={!open} {...(!open ? { inert: '' } : {})}>
        <div className="pams-collapse-inner">
        <div className="lingsi-artifact-rows">
          {artifacts.map((a, i) => {
            const g = glyph(a)
            return (
              <div
                className="lingsi-artifact-row"
                key={`${a.entityType}-${a.entityId ?? a.name}-${i}`}
                role="button"
                tabIndex={0}
                onClick={() => openArtifact(a)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' || e.key === ' ') openArtifact(a)
                }}
              >
                <span className="lingsi-artifact-glyph" style={{ background: g.bg }}>
                  {g.text}
                </span>
                <span className="lingsi-artifact-name">{a.name}</span>
                <span className="lingsi-artifact-sub">
                  {TYPE_LABEL[a.entityType] ?? a.entityType}
                  {a.entityId != null && ` · ${a.entityId}`}
                </span>
                <span className="lingsi-artifact-act">
                  <OpenExternal size={13} />
                </span>
              </div>
            )
          })}
        </div>
        </div>
      </div>
    </div>
  )
}
