import { useEffect, useMemo, useState } from 'react'
import { Button, Empty, Result, Segmented, Skeleton } from 'antd'
import { CalendarOutlined, FileTextOutlined, FolderOpenOutlined, PlusOutlined, RobotOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import dayjs from 'dayjs'
import GlassCard from '@/components/glass/GlassCard'
import PageHeader from '@/components/glass/PageHeader'
import StatusTag from '@/components/glass/StatusTag'
import { useHasPerm } from '@/components/glass/RequireRole'
import { useAuthStore } from '@/stores/auth'
import { getDashboard, type DashboardData } from '@/api/dashboard'
import { listActivities, type ActivityVO } from '@/api/activity'
import { listSchedules, SCHEDULE_TYPE_MAP, WEEKDAY_NAMES, type ScheduleVO } from '@/api/schedule'
import PendingWorkList from '@/components/notification/PendingWorkList'
import { useNotificationStore } from '@/stores/notification'

interface WorkEvent {
  key: string
  title: string
  meta: string
  time: string
  kind: 'activity' | 'schedule' | 'document'
  path: string
}

/** 分页读取已授权活动，避免周历漏掉排在第一页之外的活动。 */
async function calendarActivities(): Promise<ActivityVO[]> {
  const first = await listActivities({ page: 1, size: 100 })
  const rows = [...(first.records ?? [])]
  let page = 2
  while (rows.length < first.total) {
    const next = await listActivities({ page, size: 100 })
    if (!next.records?.length) break
    rows.push(...next.records)
    page += 1
  }
  return rows
}

export default function Dashboard() {
  const notificationRevision = useNotificationStore(s => s.revision)
  const accountToken = useAuthStore(s => s.token)
  const [dataIdentity, setDataIdentity] = useState(accountToken)
  const navigate = useNavigate()
  const hasPerm = useHasPerm()
  const canReadContent = useAuthStore(state => state.user?.permissions?.includes('content:view') ?? false)
  const canReadActivities = hasPerm('activity:view')
  const canReadSchedules = hasPerm('schedule:view')
  const today = dayjs().startOf('day')
  const todayKey = today.format('YYYY-MM-DD')
  const weekday = today.day() === 0 ? 6 : today.day() - 1
  const [range, setRange] = useState(weekday > 4 ? 7 : 5)
  const [selectedDay, setSelectedDay] = useState(weekday)
  const [data, setData] = useState<DashboardData | null>(null)
  const [activities, setActivities] = useState<ActivityVO[]>([])
  const [schedules, setSchedules] = useState<ScheduleVO[]>([])
  const [loading, setLoading] = useState(true)
  const [failed, setFailed] = useState(false)
  const [reload, setReload] = useState(0)

  useEffect(() => {
    let alive = true
    const token = useAuthStore.getState().token
    const refresh = () => {
      if (document.hidden) return
      void getDashboard().then(result => { if (alive && token === useAuthStore.getState().token) {setData(result);setDataIdentity(token)} }).catch(() => {})
    }
    if (notificationRevision > 0) refresh()
    const timer = setInterval(refresh, 60_000)
    return () => { alive = false; clearInterval(timer) }
  }, [notificationRevision, accountToken])

  useEffect(() => {
    let alive = true
    setLoading(true)
    setFailed(false)
    Promise.all([
      getDashboard(),
      canReadActivities ? calendarActivities() : Promise.resolve([] as ActivityVO[]),
      canReadSchedules ? listSchedules({}) : Promise.resolve([] as ScheduleVO[]),
    ]).then(([dashboard, activityRows, scheduleRows]) => {
      if (!alive) return
      setData(dashboard)
      setDataIdentity(accountToken)
      setActivities(activityRows)
      setSchedules(scheduleRows)
    }).catch(() => { if (alive) setFailed(true) }).finally(() => { if (alive) setLoading(false) })
    return () => { alive = false }
  }, [reload, canReadActivities, canReadSchedules, accountToken])

  const tasks = useMemo(() => [...(data?.myTasks ?? [])]
    .filter(task => task.status !== 'DONE')
    .sort((a, b) => Number(b.status === 'DELAYED') - Number(a.status === 'DELAYED') || (a.endDate ?? '9999').localeCompare(b.endDate ?? '9999')), [data])
  const week = useMemo(() => {
    const current = dayjs(todayKey)
    const monday = current.subtract(current.day() === 0 ? 6 : current.day() - 1, 'day')
    return Array.from({ length: 7 }, (_, index) => {
      const date = monday.add(index, 'day')
      const events: WorkEvent[] = []
      activities.forEach(activity => {
        if (!activity.startDate || ['FINISHED', 'ARCHIVED'].includes(activity.status)) return
        const start = dayjs(activity.startDate)
        const end = dayjs(activity.endDate ?? activity.startDate)
        if (!date.isBefore(start, 'day') && !date.isAfter(end, 'day')) events.push({
          key: 'activity-' + activity.id, title: activity.name, meta: activity.location || activity.leader || '地点待确认',
          time: '活动', kind: 'activity', path: '/activities/' + activity.id,
        })
      })
      schedules.forEach(schedule => {
        if (!schedule.scheduleDate || !date.isSame(dayjs(schedule.scheduleDate), 'day') || schedule.status === 'CANCELLED') return
        events.push({ key: 'schedule-' + schedule.id, title: SCHEDULE_TYPE_MAP[schedule.scheduleType] ?? schedule.scheduleType,
          meta: schedule.persons?.map(person => person.personName).join('、') || schedule.location || '人员待安排',
          time: schedule.startTime?.slice(0, 5) || schedule.sessionName || '排班', kind: 'schedule',
          path: schedule.activityId ? '/routine/activity-schedules?activityId=' + schedule.activityId : '/routine/schedules',
        })
      })
      tasks.forEach(task => {
        if (task.endDate && date.isSame(dayjs(task.endDate), 'day')) events.push({
          key: 'task-' + task.id, title: task.name, meta: task.assignee || '任务截止', time: '截止', kind: 'document',
          path: '/activities/' + task.activityId + '/gantt',
        })
      })
      return { date, events }
    })
  }, [todayKey, activities, schedules, tasks])
  const currentEvents = week[weekday]?.events ?? []
  const upcoming = useMemo(() => activities.filter(a => !['FINISHED', 'ARCHIVED'].includes(a.status))
    .sort((a, b) => (a.startDate ?? '9999').localeCompare(b.startDate ?? '9999')).slice(0, 5), [activities])
  const total = data?.activityStats ? Object.values(data.activityStats).reduce((sum, value) => sum + (Number(value) || 0), 0) : activities.length

  if (!loading && failed) return <Result status="error" title="工作台暂时无法加载" subTitle="请检查网络或服务状态后重试。" extra={<Button type="primary" onClick={() => setReload(value => value + 1)}>重试</Button>} />
  if (loading || dataIdentity !== accountToken) return <div aria-busy="true"><PageHeader title="工作台" description="正在加载工作安排…" /><Skeleton active paragraph={{ rows: 12 }} /></div>

  return <div className="workbench-v6">
    <PageHeader title="工作台" description="查看本周安排，跟进活动与待处理事项" extra={hasPerm('activity:create') && <Button aria-label="新增活动" type="primary" icon={<PlusOutlined />} onClick={() => navigate('/activities', { state: { create: true } })}>新增活动</Button>} />
    <section className="workbench-today" aria-label="今日安排">
      <div className="workbench-date"><strong>{today.date()}</strong><span>{today.month() + 1}月 / {WEEKDAY_NAMES[weekday]}</span></div>
      <div className="workbench-today-copy"><h3>今天的安排</h3><p>{currentEvents.length ? currentEvents.length + ' 项安排' : '今天暂无安排'}</p></div>
      <div className="workbench-today-events">{currentEvents.slice(0, 2).map(event => <button key={event.key} onClick={() => navigate(event.path)}><strong>{event.title}</strong><small>{event.time} / {event.meta}</small></button>)}</div>
    </section>
    <section className="business-summary" aria-label="工作摘要">
      {[['活动总数', total, '全部活动'], ['执行中', data?.activityStats?.EXECUTING ?? 0, '持续跟进'], ['本周排班', data?.weekSchedules ?? 0, '本周时段'], ['我的待办', data?.pendingWork?.total ?? tasks.length, '待处理事项']].map(([label, value, note], index) => <div key={label} className={index === 3 ? 'business-metric attention' : 'business-metric'}><span>{label}</span><div><strong>{value}</strong><small>{note}</small></div></div>)}
    </section>
    <div className="workbench-grid"><div>
      <GlassCard className="workbench-calendar"><div className="business-section-head"><div><h3>本周安排</h3><small>{week[0].date.format('YYYY年MM月DD日')} — {week[6].date.format('MM月DD日')}</small></div><Segmented size="small" aria-label="日历范围" options={[{ label: '工作日', value: 5 }, { label: '全周', value: 7 }]} value={range} onChange={value => { const days = Number(value); setRange(days); if (selectedDay >= days) setSelectedDay(0) }} /></div>
        <div className="workbench-legend"><span className="activity">活动</span><span className="schedule">排班</span><span className="document">任务截止</span></div>
        <div className="workbench-mobile-days">{week.slice(0, range).map((day, index) => <button key={index} aria-pressed={selectedDay === index} onClick={() => setSelectedDay(index)}>{WEEKDAY_NAMES[index]}<strong>{day.date.date()}</strong></button>)}</div>
        <div className="workbench-week" style={{ gridTemplateColumns: 'repeat(' + range + ', minmax(0, 1fr))' }}>{week.slice(0, range).map((day, index) => <section key={index} className={'workbench-day' + (day.date.isSame(today, 'day') ? ' today' : '') + (selectedDay === index ? ' selected-day' : '')} aria-label={day.date.format('MM月DD日') + WEEKDAY_NAMES[index]}><div className="workbench-day-head"><span>{index === weekday ? '今天' : WEEKDAY_NAMES[index]}</span><strong>{day.date.date()}</strong></div>{day.events.map(event => <button className={'workbench-event ' + event.kind} key={event.key} onClick={() => navigate(event.path)}><span>{event.kind === 'activity' ? '活动' : (event.kind === 'schedule' ? '排班' : '任务') + ' / ' + event.time}</span><strong>{event.title}</strong><small>{event.meta}</small></button>)}{!day.events.length && <p className="workbench-day-empty">暂无安排</p>}<small className="workbench-day-count">{day.events.length} 项安排</small></section>)}</div>
      </GlassCard>
      {canReadActivities && <GlassCard className="workbench-upcoming"><div className="business-section-head"><h3>近期活动</h3><Button type="link" size="small" onClick={() => navigate('/activities')}>查看全部活动</Button></div>{!upcoming.length ? <Empty description="暂无待推进的活动" /> : upcoming.map(activity => <div key={activity.id} className="workbench-activity"><span className="workbench-activity-icon"><CalendarOutlined /></span><div><strong>{activity.name}</strong><small>{activity.startDate || '日期待定'} / {activity.location || activity.organizer || '地点待确认'}</small></div><StatusTag status={activity.status} /><Button type="link" size="small" onClick={() => navigate('/activities/' + activity.id)}>查看</Button></div>)}</GlassCard>}
    </div><div>
      <GlassCard>{data?.pendingWork ? <PendingWorkList initial={data.pendingWork} /> : <><div className="business-section-head"><h3>待处理</h3><span className="tone-tag tone-warning">{tasks.length} 项</span></div>{!tasks.length ? <Empty description="暂无待处理任务" /> : <div className="workbench-tasks">{tasks.slice(0, 6).map(task => <div key={task.id} className="workbench-task"><div><strong>{task.name}</strong><span className={'tone-tag tone-' + (task.status === 'DELAYED' ? 'danger' : task.status === 'DOING' ? 'processing' : 'warning')}>{task.status === 'DELAYED' ? '已延期' : task.status === 'DOING' ? '执行中' : '待办'}</span></div><small>{task.assignee || '待确认负责人'} / 截止 {task.endDate ? dayjs(task.endDate).format('MM-DD') : '未设置'}</small><Button type="link" size="small" onClick={() => navigate('/activities/' + task.activityId + '?tab=gantt')}>查看任务</Button></div>)}</div>}</>}</GlassCard>
      <GlassCard className="workbench-shortcuts"><div className="business-section-head"><h3>工作入口</h3></div>{hasPerm('material:view') && <button className="workbench-shortcut" onClick={() => navigate('/archive/materials')}><span className="document"><FolderOpenOutlined /></span><div><strong>材料与档案</strong><small>查看策划、议程与名单</small></div></button>}<button className="workbench-shortcut" onClick={() => navigate('/agent')}><span className="assistant"><RobotOutlined /></span><div><strong>concerto</strong><small>根据已有资料准备工作</small></div></button>{hasPerm('notice:view') && <button className="workbench-shortcut" onClick={() => navigate('/archive/announcements')}><span className="activity"><FileTextOutlined /></span><div><strong>通知公告</strong><small>跟进组织发布的工作安排</small></div></button>}</GlassCard>
    </div></div>
    <div className="workbench-resources">{[
      { title: '最新推文', rows: (data?.recentArticles ?? []).map(item => ({ id: item.id, title: item.title, date: item.publishTime })), path: '/content/articles', visible: canReadContent },
      { title: '最新材料', rows: (data?.recentMaterials ?? []).map(item => ({ id: item.id, title: item.name, date: item.createdAt })), path: '/archive/materials', visible: hasPerm('material:view') },
      { title: '最新公告', rows: (data?.recentAnnouncements ?? []).map(item => ({ id: item.id, title: item.title, date: item.publishTime ?? item.createdAt })), path: '/archive/announcements', visible: hasPerm('notice:view') },
    ].filter(section => section.visible).map(section => <GlassCard key={section.title}><div className="business-section-head"><h3>{section.title}</h3><Button type="link" size="small" onClick={() => navigate(section.path)}>查看</Button></div>{section.rows.length ? section.rows.slice(0, 3).map(item => <button key={item.id} className="workbench-resource" onClick={() => navigate(section.path)}><strong>{item.title}</strong><small>{item.date ? dayjs(item.date).format('MM-DD HH:mm') : '日期未设置'}</small></button>) : <p className="workbench-day-empty">暂无内容</p>}</GlassCard>)}</div>
  </div>
}
