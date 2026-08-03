import { PLAN_TEMPLATE_SECTIONS, parseBudgetArray, sectionMetaValue, stripHtml, type PlanFields, type PlanMeta } from './planTemplate'

interface WordPreviewProps {
  plan: PlanFields
  meta?: PlanMeta
}

/** A4 纸张只读渲染 7 字段（按 12 章顺序，标题 14pt 加粗 + 正文 12pt；budget JSON → 表格） */
export default function WordPreview({ plan, meta }: WordPreviewProps) {
  const budgetArr = parseBudgetArray(plan.budget)

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
              {sec.field === 'budget' && budgetArr ? (
                <table className="word-table">
                  <thead>
                    <tr>
                      <th>物品</th>
                      <th>数量</th>
                      <th>单价（元）</th>
                      <th>总价（元）</th>
                    </tr>
                  </thead>
                  <tbody>
                    {budgetArr.map((item, ri) => (
                      <tr key={ri}>
                        <td>{String(item?.item ?? '')}</td>
                        <td>{String(item?.quantity ?? '')}</td>
                        <td>{String(item?.unitPrice ?? '')}</td>
                        <td>{String(item?.totalPrice ?? '')}</td>
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
          <div>{new Date().getFullYear()}年</div>
        </div>
      </div>
    </div>
  )
}
