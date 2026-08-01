import { useEffect, useMemo, useState } from 'react'
import { InputNumber, message, Select, Space, Table } from 'antd'
import GlassCard from '@/components/glass/GlassCard'
import GlassTable from '@/components/glass/GlassTable'
import PageHeader from '@/components/glass/PageHeader'
import {
  listSchedules,
  SCHEDULE_TYPE_OPTIONS,
  WEEKDAY_NAMES,
  type ScheduleVO,
} from '@/api/schedule'
import {
  listAttendances,
  createAttendance,
  deleteAttendance,
  summaryAttendance,
  ATTENDANCE_STATUS_MAP,
  type AttendanceSummaryVO,
  type AttendanceVO,
} from '@/api/attendance'

interface ScheduleRow {
  key: number
  scheduleId: number
  displayName: string
  personNames: string[]
}

export default function AttendanceList() {
  const [weekNo, setWeekNo] = useState<number | undefined>()
  const [summaryType, setSummaryType] = useState<string | undefined>()
  const [schedules, setSchedules] = useState<ScheduleVO[]>([])
  const [attendanceMap, setAttendanceMap] = useState<Record<string, AttendanceVO[]>>({})
  const [summary, setSummary] = useState<AttendanceSummaryVO[]>([])
  const [loading, setLoading] = useState(false)

  // 该周排班（登记考勤的数据源），并回显该周已登记的考勤状态（同人取最新一条）
  useEffect(() => {
    setLoading(true)
    listSchedules({ weekNo })
      .then((res) => setSchedules(res ?? []))
      .catch(() => {
        /* http 拦截已提示 */
      })
      .finally(() => setLoading(false))
    if (weekNo) {
      listAttendances({ weekNo })
        .then((res) => {
          const map: Record<string, AttendanceVO[]> = {}
          ;(res ?? []).forEach((a) => {
            map[`${a.scheduleId}-${a.personName}`] = [a]
          })
          setAttendanceMap(map)
        })
        .catch(() => {
          /* http 拦截已提示 */
        })
    } else {
      setAttendanceMap({})
    }
  }, [weekNo])

  // 汇总表：weekNo 必传（按所属排班周次匹配），type 可选
  const refreshSummary = () => {
    summaryAttendance({ weekNo, type: summaryType })
      .then((res) => setSummary(res ?? []))
      .catch(() => {
        /* http 拦截已提示 */
      })
  }

  useEffect(() => {
    refreshSummary()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [weekNo, summaryType])

  const scheduleRows: ScheduleRow[] = useMemo(() => {
    return schedules.map((s) => {
      const names = (s.persons ?? []).map((p) => p.personName)
      const weekdayLabel = WEEKDAY_NAMES[(s.weekday ?? 1) - 1] ?? ''
      return {
        key: s.id,
        scheduleId: s.id,
        displayName: `${weekdayLabel} ${s.sessionName ?? ''} ${s.location ?? ''}`.trim(),
        personNames: names,
      }
    })
  }, [schedules])

  const handleStatusChange = async (scheduleId: number, personName: string, status: string) => {
    const mapKey = `${scheduleId}-${personName}`
    const existing = attendanceMap[mapKey]?.[0]
    try {
      if (existing && existing.status === status) return
      if (existing) {
        // 后端无更新接口，删除旧记录后重建
        await deleteAttendance(existing.id)
      }
      const created = await createAttendance({ scheduleId, personName, status })
      setAttendanceMap((prev) => ({ ...prev, [mapKey]: [created] }))
      message.success(`${personName}：${ATTENDANCE_STATUS_MAP[status]}`)
      refreshSummary()
    } catch {
      /* http 拦截已提示 */
    }
  }

  const summaryColumns = [
    { title: '姓名', dataIndex: 'personName', key: 'personName' },
    { title: '应到', dataIndex: 'shouldAttend', key: 'shouldAttend', width: 80, align: 'center' as const },
    { title: '实到', dataIndex: 'present', key: 'present', width: 80, align: 'center' as const },
    { title: '请假', dataIndex: 'leave', key: 'leave', width: 80, align: 'center' as const },
    { title: '缺勤', dataIndex: 'absent', key: 'absent', width: 80, align: 'center' as const },
    { title: '次数', dataIndex: 'count', key: 'count', width: 80, align: 'center' as const },
  ]

  return (
    <div>
      <PageHeader title="考勤管理" description="按周次登记排班考勤，汇总应到/实到/请假/缺勤" />

      <GlassCard style={{ padding: 16, marginBottom: 16 }}>
        <Space wrap>
          <span style={{ color: 'var(--color-text-secondary)' }}>考勤周次</span>
          <InputNumber
            min={1}
            max={30}
            style={{ width: 120 }}
            placeholder="如 1"
            value={weekNo}
            onChange={(v) => setWeekNo(v ?? undefined)}
          />
        </Space>
      </GlassCard>

      <GlassTable<ScheduleRow>
        columns={[
          { title: '排班', dataIndex: 'displayName', key: 'displayName' },
          {
            title: '人员',
            key: 'persons',
            render: (_: unknown, r: ScheduleRow) =>
              r.personNames.length === 0 ? (
                <span style={{ color: 'var(--color-text-secondary)' }}>无人员</span>
              ) : (
                <Space size="small" wrap>
                  {r.personNames.map((name) => {
                    const existing = attendanceMap[`${r.scheduleId}-${name}`]?.[0]
                    return (
                      <Select
                        key={`${r.scheduleId}-${name}`}
                        value={existing?.status}
                        style={{ width: 110 }}
                        placeholder={`${name} 未登记`}
                        options={[
                          { value: 'PRESENT', label: `${name} 出勤` },
                          { value: 'LEAVE', label: `${name} 请假` },
                          { value: 'ABSENT', label: `${name} 缺勤` },
                        ]}
                        onChange={(v) => handleStatusChange(r.scheduleId, name, v)}
                      />
                    )
                  })}
                </Space>
              ),
          },
        ]}
        dataSource={scheduleRows}
        loading={loading}
        pagination={false}
        locale={{ emptyText: weekNo ? '该周暂无排班' : '请先选择考勤周次' }}
      />

      <GlassCard style={{ padding: 16, marginTop: 16 }}>
        <Space wrap style={{ marginBottom: 16 }}>
          <Select
            placeholder="汇总类型（可选）"
            allowClear
            options={SCHEDULE_TYPE_OPTIONS}
            style={{ width: 180 }}
            value={summaryType}
            onChange={setSummaryType}
          />
          <span style={{ color: 'var(--color-text-secondary)', fontSize: 12 }}>
            汇总按人员所属排班周次匹配，未关联排班的考勤不计入
          </span>
        </Space>
        <Table<AttendanceSummaryVO>
          size="middle"
          columns={summaryColumns}
          dataSource={summary.map((r) => ({ ...r, key: r.personName }))}
          pagination={false}
          rowKey="personName"
          locale={{ emptyText: weekNo ? '暂无考勤数据' : '请先选择考勤周次' }}
        />
      </GlassCard>
    </div>
  )
}
