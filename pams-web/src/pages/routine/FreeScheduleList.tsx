import { useEffect, useState } from 'react'
import { Button, Empty, Select, Space, Spin } from 'antd'
import { ReloadOutlined } from '@ant-design/icons'
import GlassCard from '@/components/glass/GlassCard'
import PageHeader from '@/components/glass/PageHeader'
import { getGeneratedNoClassSchedule, type NoClassScheduleGeneratedVO } from '@/api/courseSchedule'
import { listDepts, type DeptVO } from '@/api/dept'
import { useAuthStore } from '@/stores/auth'

const SEMESTERS = ['2025-2026-2', '2026-2027-1', '2025-2026-1', '2024-2025-2']
const WEEKDAYS = ['周一', '周二', '周三', '周四', '周五']

export default function FreeScheduleList() {
  const user = useAuthStore((s) => s.user)
  const [depts, setDepts] = useState<DeptVO[]>([])
  const [deptId, setDeptId] = useState<number | undefined>(user?.deptId ?? undefined)
  const [semester, setSemester] = useState('2025-2026-2')
  const [data, setData] = useState<NoClassScheduleGeneratedVO | null>(null)
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    listDepts()
      .then((res) => setDepts(res ?? []))
      .catch(() => { /* 拦截已提示 */ })
  }, [])

  const load = () => {
    setLoading(true)
    setData(null)
    getGeneratedNoClassSchedule(deptId, semester)
      .then((res) => setData(res ?? null))
      .catch(() => setData(null))
      .finally(() => setLoading(false))
  }

  useEffect(() => { load() /* eslint-disable-line react-hooks/exhaustive-deps */ }, [deptId, semester])

  // 窗口重新聚焦时自动重新拉取：在别的标签页生成后切回本页也能看到最新数据
  useEffect(() => {
    const onFocus = () => load()
    window.addEventListener('focus', onFocus)
    return () => window.removeEventListener('focus', onFocus)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  return (
    <div>
      <PageHeader title="无课表" description="批量导入课表自动生成的无课表，按部门+学期查看" />
      <GlassCard style={{ padding: 16, marginBottom: 16 }}>
        <Space wrap>
          <Select
            placeholder="部门"
            style={{ width: 180 }}
            options={depts.map((d) => ({ value: d.id, label: d.name }))}
            value={deptId}
            onChange={setDeptId}
          />
          <Select
            style={{ width: 180 }}
            options={SEMESTERS.map((s) => ({ value: s, label: s }))}
            value={semester}
            onChange={setSemester}
          />
          <Button icon={<ReloadOutlined />} onClick={load}>刷新</Button>
          {data && <span style={{ color: 'var(--color-text-secondary)', fontSize: 12 }}>生成时间：{data.createdAt?.replace('T', ' ')}</span>}
        </Space>
      </GlassCard>
      <GlassCard style={{ padding: 16 }}>
        <Spin spinning={loading}>
          {!data ? (
            <Empty description="该部门+学期还没有生成的无课表，请到「无课表制作 → 批量导入」生成" />
          ) : (
            <div style={{ overflow: 'auto' }}>
              <table style={{ borderCollapse: 'collapse', fontSize: 13, minWidth: 720 }}>
                <thead>
                  <tr>
                    <th style={{ padding: '6px 10px', border: '1px solid var(--color-border)', background: 'var(--color-bg-2)' }}>节次</th>
                    {WEEKDAYS.map((d) => (
                      <th key={d} style={{ padding: '6px 10px', border: '1px solid var(--color-border)', background: 'var(--color-bg-2)' }}>{d}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {data.rows.map((row) => (
                    <tr key={row.period}>
                      <td style={{ padding: '6px 10px', border: '1px solid var(--color-border)', whiteSpace: 'nowrap', color: 'var(--color-text-secondary)' }}>{row.label}</td>
                      {[1, 2, 3, 4, 5].map((day) => (
                        <td key={day} style={{ padding: '6px 10px', border: '1px solid var(--color-border)', verticalAlign: 'top', minWidth: 140 }}>
                          {(row.days[String(day)] ?? []).map((c, idx) => (
                            <div key={`${row.period}-${day}-${idx}`}>{c.name}（{c.freeWeeks}）</div>
                          ))}
                        </td>
                      ))}
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
