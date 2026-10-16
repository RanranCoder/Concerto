import type { NoClassScheduleRowVO } from '@/api/courseSchedule'
import { freeInWeek, WEEK_DAYS } from '@/utils/routine'

export default function FreeScheduleGrid({
  rows,
  week,
}: {
  rows: NoClassScheduleRowVO[]
  week?: number
}) {
  return (
    <div className="routine-table-scroll">
      <table className="routine-week-table routine-free-table">
        <thead>
          <tr>
            <th scope="col">节次 / 时间</th>
            {WEEK_DAYS.slice(0, 5).map((d) => (
              <th key={d} scope="col">
                {d}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.period}>
              <th scope="row">
                {r.label}
                <small>{r.halfDay}</small>
              </th>
              {[1, 2, 3, 4, 5].map((d) => {
                const cells = (r.days[String(d)] ?? []).filter(
                  (c) => week === undefined || freeInWeek(c.freeWeeks, week),
                )
                return (
                  <td key={d}>
                    {cells.map((c, i) => (
                      <div
                        className="routine-free-person"
                        key={`${c.name}-${i}`}
                      >
                        <span>{c.name}</span>
                        <small>
                          {week === undefined
                            ? c.freeWeeks || '未标注周次'
                            : `第 ${week} 周`}
                        </small>
                      </div>
                    ))}
                    {!cells.length && <span className="routine-muted">—</span>}
                  </td>
                )
              })}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
