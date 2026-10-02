import type { AgentCitation } from '@/api/agent'
import { useAgentPanel } from '@/stores/agentPanel'
import { ArrowRight, OpenExternal, Warn } from './LingsiIcons'

interface Props {
  citations: AgentCitation[]
}

/** 快照类来源：必须显式声明"这是某时刻的数字"，不能让人误读成当前值 */
const SNAPSHOT_SOURCE = 'snapshot'

/** 出处类型的展示名。未知类型原样回退，不隐藏 —— 让新源接进来就能看到 */
const SOURCE_LABEL: Record<string, string> = {
  manual: '制度',
  announcement: '公告',
  article: '稿件',
  activity_plan: '策划书',
  snapshot: '数据快照',
}

const sourceLabel = (t: string) => SOURCE_LABEL[t] ?? t

/**
 * 依据卡（V27 对话档位）。
 *
 * <p><b>它存在的前提是"引用由服务端回填"</b>：模型只输出 `[n]` 编号，
 * 出处元数据全部来自检索结果。所以这里渲染的每一条都是**真实存在过的检索命中**，
 * 而不是模型声称的出处 —— 这是知识库可信度的落脚点，也是为什么这张卡不能省。
 *
 * <h3>三种状态必须区分清楚</h3>
 * <ol>
 *   <li><b>正常</b>：可点开看片段、可跳原文页</li>
 *   <li><b>快照类</b>：额外显示「数据截至 YYYY-MM-DD」并换用虚线样式 ——
 *       它表示"某时刻的统计"而非"当前值"。不区分会让用户拿过期数字做决策，
 *       比不答更糟（方案 R16）</li>
 *   <li><b>已不可见</b>（`revoked`）：用户权限被收回后重看历史对话。
 *       服务端已清空片段，这里只说明"已不可见"而**不显示任何内容** ——
 *       这是权限收紧必须能撤回历史的体现（方案 G8）。见到它不要当 bug 报。</li>
 * </ol>
 *
 * <h3>视觉约束</h3>
 * 不引入新的红色（红色只保留在新建图标 / 选中竖条 / 发送按钮 / 不可逆警示 4 处）。
 * 快照用中性虚线、已不可见用灰阶 —— 都不与"危险"语义混淆。
 */
export default function LingsiSources({ citations }: Props) {
  const openText = useAgentPanel((s) => s.openText)

  if (!citations || citations.length === 0) return null

  return (
    <section className="lingsi-sources" aria-label={`答案依据，共 ${citations.length} 条`}>
      <div className="lingsi-sources-head">
        依据
        <span className="lingsi-sources-count">{citations.length}</span>
      </div>

      <ol className="lingsi-sources-list">
        {citations.map((c) => {
          const isSnapshot = c.sourceType === SNAPSHOT_SOURCE
          const key = `${c.sourceType}-${c.sourceId}-${c.index}`

          if (c.revoked) {
            return (
              <li className="lingsi-source lingsi-source-revoked" key={key}>
                <span className="lingsi-source-idx">{c.index}</span>
                <div className="lingsi-source-body">
                  <div className="lingsi-source-title">{c.sourceTitle || '已不可见的依据'}</div>
                  <div className="lingsi-source-revoked-note">
                    <Warn size={12} />
                    该引用已不可见（你的访问权限可能已变更）
                  </div>
                </div>
              </li>
            )
          }

          return (
            <li
              className={`lingsi-source${isSnapshot ? ' lingsi-source-snapshot' : ''}`}
              key={key}
            >
              <span className="lingsi-source-idx">{c.index}</span>
              <div className="lingsi-source-body">
                <div className="lingsi-source-title">
                  <span className="lingsi-source-kind">{sourceLabel(c.sourceType)}</span>
                  <span className="lingsi-source-name">{c.sourceTitle}</span>
                </div>

                <div className="lingsi-source-meta">
                  {c.path && <span className="lingsi-source-path">{c.path}</span>}
                  {isSnapshot && (
                    <span className="lingsi-source-asof">
                      数据截至 {c.asOf || '未知时间'}
                    </span>
                  )}
                </div>

                {c.snippet && <p className="lingsi-source-snippet">{c.snippet}</p>}

                <div className="lingsi-source-actions">
                  {c.snippet && (
                    <button
                      type="button"
                      className="lingsi-source-link"
                      onClick={() =>
                        openText(
                          `cite-${key}`,
                          `${sourceLabel(c.sourceType)} · ${c.sourceTitle}`,
                          c.snippet,
                        )
                      }
                    >
                      查看片段
                      <ArrowRight size={12} />
                    </button>
                  )}
                  {c.link && (
                    <a className="lingsi-source-link" href={c.link}>
                      打开原文
                      <OpenExternal size={12} />
                    </a>
                  )}
                </div>
              </div>
            </li>
          )
        })}
      </ol>
    </section>
  )
}
