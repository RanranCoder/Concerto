import { PLAN_TEMPLATE_SECTIONS, parseBudgetMatrix, planYearFromMeta, sectionMetaValue, stripHtml, type PlanFields, type PlanMeta } from './planTemplate'

interface WordPreviewProps {
  plan: PlanFields
  meta?: PlanMeta
}

/** A4 纸张只读渲染 7 字段（按 12 章顺序，标题 14pt 加粗 + 正文 12pt；budget JSON/HTML 表格 → 表格） */
export default function WordPreview({ plan, meta }: WordPreviewProps) {
  const budgetRows = parseBudgetMatrix(plan.budget)
  const year = planYearFromMeta(meta)

  return (
    <div className="word-paper">
      <div className="word-paper-padding">
        <div className="word-header-center">{meta?.orgName || '信息工程学院党建办公室'}</div>
        <div className="word-header-title">{meta?.name || '活动策划书'}</div>
        {meta?.theme ? <div className="word-header-sub">（主题：{meta.theme}）</div> : null}

        {PLAN_TEMPLATE_SECTIONS.map((sec, i) => {
          const fixedVal = sec.field ? '' : sectionMetaValue(sec, meta)
          const raw = sec.field ? (plan[sec.field] ?? '') : ''
          const val = sec.field ? stripHtml(raw).trim() : fixedVal.trim()
          if (!val) return null
          return (
            <div key={i} className="word-sec">
              <div className="word-sec-label">{sec.label}</div>
              {sec.field === 'budget' && budgetRows ? (
                <table className="word-table">
                  <thead>
                    <tr>
                      {budgetRows[0]?.map((h, hi) => (
                        <th key={hi}>{h}</th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {budgetRows.slice(1).map((cells, ri) => (
                      <tr key={ri}>
                        {cells.map((c, ci) => (
                          <td key={ci}>{c}</td>
                        ))}
                      </tr>
                    ))}
                  </tbody>
                </table>
              ) : (
                <div className="word-sec-body word-sec-static" style={{ whiteSpace: 'pre-wrap' }}>
                  {val}
                </div>
              )}
            </div>
          )
        })}

        <div className="word-sign" style={{ textAlign: 'right', marginTop: 32 }}>
          <div>{meta?.orgName || '信息工程学院党建办公室'}</div>
          <div>{year}年</div>
        </div>
      </div>
    </div>
  )
}
