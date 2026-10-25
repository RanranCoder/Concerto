import { agentBusinessLink } from '@/utils/agentLinks'
import { useNavigate } from 'react-router-dom'
import type { AgentReference } from '@/api/agent'
import { ArrowRight } from './LingsiIcons'

interface Props {
  references: AgentReference[]
}

/**
 * 引用条（灵思 §7.3）。
 *
 * 为什么一定要有：LLM 会把 id 说错、会把不存在的活动说成存在。可跳转的引用条是
 * 「AI 说了但找不到」的唯一解药 —— 用户点一下就能验证。
 * 无 link 的引用渲染为不可点标签（例如统计口径说明）。
 */
export default function LingsiRefs({ references }: Props) {
  const navigate = useNavigate()
  if (!references?.length) return null

  return (
    <div className="lingsi-refs">
      {references.map((r, i) => {
        const clickable = Boolean(r.link)
        return (
          <span
            className="lingsi-ref"
            key={`${r.type}-${r.id ?? i}`}
            style={clickable ? undefined : { cursor: 'default' }}
            onClick={() => {
              if (clickable) navigate(agentBusinessLink(r.link) as string)
            }}
            role={clickable ? 'button' : undefined}
            tabIndex={clickable ? 0 : undefined}
            onKeyDown={(e) => {
              if (clickable && (e.key === 'Enter' || e.key === ' ')) navigate(agentBusinessLink(r.link) as string)
            }}
          >
            {r.label}
            {clickable && <ArrowRight size={11} />}
          </span>
        )
      })}
    </div>
  )
}
