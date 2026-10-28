import { useEffect, useState } from 'react'
import { App, Button, Select, Segmented, Spin } from 'antd'
import { useNavigate } from 'react-router-dom'
import dayjs from 'dayjs'
import GlassCard from '@/components/glass/GlassCard'
import PageHeader from '@/components/glass/PageHeader'
import RoutineLayout, {
  RoutineSummary,
} from '@/components/routine/RoutineLayout'
import {
  ScheduleDetail,
  ScheduleGrid,
  ScheduleTable,
  shiftTitle,
  WeekControls,
} from '@/components/routine/ScheduleViews'
import { post } from '@/api/http'
import { listMySchedules, type ScheduleVO } from '@/api/schedule'
import { listActivities, type ActivityVO } from '@/api/activity'
import { getScheduleConfigs, type ScheduleConfigVO } from '@/api/courseSchedule'
import { useAuthStore } from '@/stores/auth'
import {
  FALLBACK_PERIODS,
  monday,
  inCalendarWeek,
  weekday,
  periodFor,
  timeRange,
} from '@/utils/routine'

export default function MyScheduleList() {
  const { message } = App.useApp()
  const [checking, setChecking] = useState(false)
  const checkIn = async (shift: ScheduleVO) => {
    setChecking(true)
    try { await post('/attendances', {scheduleId:shift.id,personName:user?.realName || user?.username,status:'NORMAL'}); message.success('本人打卡已记录') } catch {} finally { setChecking(false) }
  }
  const navigate = useNavigate(),
    user = useAuthStore((s) => s.user)
  const [rows, setRows] = useState<ScheduleVO[]>([]),
    [activities, setActivities] = useState<ActivityVO[]>([]),
    [periods, setPeriods] = useState<ScheduleConfigVO[]>(FALLBACK_PERIODS)
  const [loading, setLoading] = useState(false),
    [start, setStart] = useState(monday),
    [mode, setMode] = useState('week'),
    [activityId, setActivityId] = useState<number>(),
    [detail, setDetail] = useState<ScheduleVO | null>(null)
  useEffect(() => {
    setLoading(true)
    listMySchedules()
      .then((r) => setRows(r ?? []))
      .catch(() => {})
      .finally(() => setLoading(false))
    listActivities({ page: 1, size: 200 })
      .then((r) => setActivities(r.records ?? []))
      .catch(() => {})
    getScheduleConfigs()
      .then((r) => {
        if (r.length) setPeriods(r)
      })
      .catch(() => {})
  }, [])
  const filtered = rows.filter(
    (s) => !activityId || s.activityId === activityId,
  )
  const inferredWeek =
    filtered.find((s) => s.scheduleDate && inCalendarWeek(s, start) && s.weekNo)
      ?.weekNo ?? undefined
  const weekRows = filtered.filter((s) =>
      inCalendarWeek(s, start, inferredWeek),
    ),
    unplaced = weekRows.filter((s) => !weekday(s) || periodFor(s, periods) < 0)
  const next = filtered
    .filter(
      (s) =>
        s.status !== 'CANCELLED' &&
        s.status !== 'ENDED' &&
        s.scheduleDate &&
        dayjs(
          `${s.scheduleDate}T${s.endTime || periods[periodFor(s, periods)]?.endTime || '23:59:59'}`,
        ).isAfter(dayjs()),
    )
    .sort((a, b) =>
      `${a.scheduleDate} ${a.startTime ?? periods[periodFor(a, periods)]?.startTime ?? ''}`.localeCompare(
        `${b.scheduleDate} ${b.startTime ?? periods[periodFor(b, periods)]?.startTime ?? ''}`,
      ),
    )[0]
  const own = (s: ScheduleVO) =>
    s.persons.find(
      (p) =>
        p.userId === user?.id,
    )
  return (
    <RoutineLayout>
      <PageHeader
        title="我的排班"
        description={`${user?.realName ?? ''} · ${user?.deptName ?? '当前组织'}，查看自己的值班与活动安排。`}
        extra={
          <Button disabled={!user?.permissions.includes('attendance:self:view')} onClick={() => navigate('/routine/attendance?scope=mine')}>
            查看我的考勤
          </Button>
        }
      />
      {next && (
        <section className="routine-next-shift">
          <div className="routine-next-date">
            {dayjs(next.scheduleDate).format('DD')}
            <small>{dayjs(next.scheduleDate).format('MM 月 ddd')}</small>
          </div>
          <div>
            <small>下一班</small>
            <h3>{shiftTitle(next, activities)}</h3>
            <p>
              {timeRange(next, periods)}　{next.location || '地点未设置'}
            </p>
            <div className="routine-meta">
              <span>
                {own(next)?.isPrimary === 1 ? '你是主班' : '你是副班'}
              </span>
              <span>
                同行：
                {next.persons
                  .filter((p) => p !== own(next))
                  .map((p) => p.personName)
                  .join('、') || '无'}
              </span>
            </div>
          </div>
          <div><Button onClick={() => setDetail(next)}>查看安排</Button>{user?.permissions.includes('schedule:check') && next.status === 'PUBLISHED' && <Button type="primary" loading={checking} onClick={() => void checkIn(next)}>本人打卡</Button>}</div>
        </section>
      )}
      <RoutineSummary
        items={[
          { label: '本周安排', value: weekRows.length, note: '班次' },
          {
            label: '主班任务',
            value: weekRows.filter((s) => own(s)?.isPrimary === 1).length,
            note: '班次',
          },
          {
            label: '活动安排',
            value: weekRows.filter((s) => s.scheduleType === 'ACTIVITY').length,
            note: '班次',
          },
          {
            label: '例行事务',
            value: weekRows.filter((s) => s.scheduleType !== 'ACTIVITY').length,
            note: '班次',
          },
        ]}
      />
      <GlassCard>
        <WeekControls
          start={start}
          onChange={setStart}
          extra={
            <Segmented
              value={mode}
              onChange={(v) => setMode(String(v))}
              options={[
                { value: 'week', label: '周视图' },
                { value: 'list', label: '全部安排' },
              ]}
            />
          }
        />
        <div className="routine-toolbar">
          <label className="routine-filter wide">
            关联活动
            <Select
              aria-label="关联活动"
              allowClear
              showSearch
              optionFilterProp="label"
              placeholder="全部安排"
              value={activityId}
              onChange={setActivityId}
              options={activities.map((a) => ({ value: a.id, label: a.name }))}
            />
          </label>
          <span className="routine-muted">只显示当前账号的安排</span>
        </div>
        <div className="routine-caption">
          <div className="routine-legend">
            <span>例行事务</span>
            <span className="activity">活动排班</span>
          </div>
          <span>左右滑动查看整周</span>
        </div>
        <Spin spinning={loading}>
          {mode === 'week' ? (
            <>
              <ScheduleGrid
                rows={weekRows}
                periods={periods}
                start={start}
                activities={activities}
                onOpen={setDetail}
                sevenDays
              />
              {unplaced.length > 0 && (
                <ScheduleTable
                  rows={unplaced}
                  activities={activities}
                  onOpen={setDetail}
                />
              )}
            </>
          ) : (
            <ScheduleTable
              rows={filtered}
              activities={activities}
              onOpen={setDetail}
            />
          )}
        </Spin>
        <div className="routine-table-foot">
          <span>共 {filtered.length} 个个人班次</span>
          {mode === 'week' && (
            <Button type="link" onClick={() => setMode('list')}>
              查看全部安排（含未设置日期的班次）
            </Button>
          )}
        </div>
      </GlassCard>
      <ScheduleDetail
        shift={detail}
        activities={activities}
        onClose={() => setDetail(null)}
      />
    </RoutineLayout>
  )
}
