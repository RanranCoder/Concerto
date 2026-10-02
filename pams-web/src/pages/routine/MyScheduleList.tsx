import { useEffect, useMemo, useState, type CSSProperties } from 'react'
import { Empty, Select, Space, Spin, Tag } from 'antd'
import dayjs from 'dayjs'
import GlassCard from '@/components/glass/GlassCard'
import PageHeader from '@/components/glass/PageHeader'
import { listMySchedules, SCHEDULE_TYPE_MAP, WEEKDAY_NAMES, type ScheduleVO } from '@/api/schedule'
import { listActivities, type ActivityVO } from '@/api/activity'

/** 节次列：第1-2 / 第3-4 / 第5-6 / 第7-8 节 */
const PERIOD_ROWS: Array<{ key: string; label: string; range: [number, number] }> = [
  { key: 'p1', label: '第1-2节', range: [1, 2] },
  { key: 'p3', label: '第3-4节', range: [3, 4] },
  { key: 'p5', label: '第5-6节', range: [5, 6] },
  { key: 'p7', label: '第7-8节', range: [7, 8] },
]

function periodRowIndex(sessionName?: string | null): number {
  const m = sessionName?.match(/(\d+)/)
  if (!m) return -1
  const p = Number(m[1])
  return PERIOD_ROWS.findIndex((r) => p >= r.range[0] && p <= r.range[1])
}

function effWeekday(s: ScheduleVO): number | undefined {
  if (s.weekday != null) return s.weekday
  if (s.scheduleDate) return new Date(s.scheduleDate).getDay() || 7
  return undefined
}

const thStyle: CSSProperties = {
  padding: 10,
  textAlign: 'center',
  border: '1px solid var(--color-border)',
  background: 'var(--color-bg-2)',
  fontWeight: 600,
  color: 'var(--color-text-secondary)',
}
const tdStyle: CSSProperties = {
  padding: 10,
  verticalAlign: 'top',
  border: '1px solid var(--color-border)',
  minWidth: 150,
  minHeight: 44,
}

export default function MyScheduleList() {
  const [list, setList] = useState<ScheduleVO[]>([])
  const [loading, setLoading] = useState(false)
  const [activityId, setActivityId] = useState<number | undefined>()
  const [activities, setActivities] = useState<ActivityVO[]>([])

  useEffect(() => {
    listActivities({ page: 1, size: 200 }).then((r) => setActivities(r?.records ?? [])).catch(() => {})
    setLoading(true)
    listMySchedules()
      .then((res) => setList(res ?? []))
      .catch(() => {})
      .finally(() => setLoading(false))
  }, [])

  const filtered = useMemo(
    () => list.filter((s) => !activityId || s.activityId === activityId),
    [list, activityId],
  )

  /** key=`{weekNo}-{rowIdx}` -> 该格排班 */
  const map = useMemo(() => {
    const m = new Map<string, ScheduleVO[]>()
    filtered.forEach((s) => {
      const row = periodRowIndex(s.sessionName)
      if (row < 0) return
      const key = `${s.weekNo ?? 0}-${row}`
      const arr = m.get(key) ?? []
      arr.push(s)
      m.set(key, arr)
    })
    return m
  }, [filtered])

  const weeks = useMemo(
    () => Array.from(new Set(filtered.map((s) => s.weekNo ?? 0))).filter((w) => w > 0).sort((a, b) => a - b),
    [filtered],
  )

  return (
    <div>
      <PageHeader title="我的排班" description="当前账号被排到的值班/活动班次（行为周次，列为节次）" />
      <GlassCard style={{ padding: 16, marginBottom: 16 }}>
        <Space wrap>
          <span style={{ color: 'var(--color-text-secondary)' }}>活动名称</span>
          <Select
            placeholder="全部活动"
            allowClear
            showSearch
            optionFilterProp="label"
            style={{ width: 220 }}
            options={activities.map((a) => ({ value: a.id, label: a.name }))}
            value={activityId}
            onChange={setActivityId}
          />
          <span style={{ color: 'var(--color-text-secondary)', fontSize: 12 }}>共被排 {filtered.length} 个班次</span>
        </Space>
      </GlassCard>
      <GlassCard style={{ padding: 16 }}>
        <Spin spinning={loading}>
          {weeks.length === 0 && !loading ? (
            <Empty description="当前账号还没有被排到班次" />
          ) : (
            <div style={{ overflowX: 'auto' }}>
              <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                <thead>
                  <tr>
                    <th style={{ ...thStyle, width: 80 }}>周次</th>
                    {PERIOD_ROWS.map((p) => (
                      <th key={p.key} style={thStyle}>
                        {p.label}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {weeks.map((w) => (
                    <tr key={w}>
                      <td style={{ ...tdStyle, width: 80, textAlign: 'center', fontWeight: 600 }}>第{w}周</td>
                      {PERIOD_ROWS.map((p, idx) => {
                        const shifts = map.get(`${w}-${idx}`) ?? []
                        return (
                          <td key={p.key} style={tdStyle}>
                            {shifts.length === 0 ? (
                              <span style={{ color: 'var(--color-text-tertiary)' }}>—</span>
                            ) : (
                              shifts.map((s) => (
                                <div key={s.id} style={{ paddingBottom: 6, marginBottom: 6, borderBottom: '1px dashed var(--color-border)' }}>
                                  <Space size={4} wrap>
                                    <span style={{ fontWeight: 500 }}>
                                      {effWeekday(s) != null ? WEEKDAY_NAMES[effWeekday(s)! - 1] : ''}
                                    </span>
                                    <Tag color="red">{SCHEDULE_TYPE_MAP[s.scheduleType] ?? s.scheduleType}</Tag>
                                  </Space>
                                  {s.sessionName && (
                                    <div style={{ fontSize: 12, color: 'var(--color-text-secondary)' }}>{s.sessionName}</div>
                                  )}
                                  {s.location && (
                                    <div style={{ fontSize: 12, color: 'var(--color-text-secondary)' }}>{s.location}</div>
                                  )}
                                  {s.scheduleDate && (
                                    <div style={{ fontSize: 11, color: 'var(--color-text-secondary)' }}>
                                      {dayjs(s.scheduleDate).format('YYYY-MM-DD')}
                                    </div>
                                  )}
                                </div>
                              ))
                            )}
                          </td>
                        )
                      })}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Spin>
      </GlassCard>
    </div>
  )
}