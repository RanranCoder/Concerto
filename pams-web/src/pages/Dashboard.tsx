import { useEffect, useMemo, useState } from 'react'
import { Button, Empty, Result, Skeleton, Tooltip, Typography } from 'antd'
import {
  BellOutlined,
  CalendarOutlined,
  CheckCircleOutlined,
  ClockCircleOutlined,
  FileTextOutlined,
  FolderOpenOutlined,
  FundOutlined,
  PlayCircleOutlined,
  ScheduleOutlined,
} from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import dayjs from 'dayjs'
import GlassCard from '@/components/glass/GlassCard'
import Masonry from '@/components/glass/Masonry'
import PageHeader from '@/components/glass/PageHeader'
import StatusTag from '@/components/glass/StatusTag'
import { getDashboard, type DashboardData } from '@/api/dashboard'
import { listActivities, type ActivityVO } from '@/api/activity'
import { listSchedules, SCHEDULE_TYPE_MAP, WEEKDAY_NAMES, type ScheduleVO } from '@/api/schedule'
import { MATERIAL_BIZ_TYPE_MAP } from '@/api/material'
import { ARTICLE_TYPE_MAP } from '@/api/article'
import { useAuthStore } from '@/stores/auth'

/** 统计卡图标配色：统一用语义 token，明暗主题与组织主色自动跟随 */
const CARD_ACCENT: Record<string, string> = {
  red: 'var(--brand)',
  orange: 'var(--warn)',
  green: 'var(--ok)',
  blue: 'var(--info)',
}

const fmt = (t?: string | null) => (t ? dayjs(t).format('MM-DD HH:mm') : '—')

export default function Dashboard() {
  const navigate = useNavigate()
  const user = useAuthStore((s) => s.user)

  const [dash, setDash] = useState<DashboardData | null>(null)
  const [activities, setActivities] = useState<ActivityVO[]>([])
  const [schedules, setSchedules] = useState<ScheduleVO[]>([])
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState(false)
  const [reloadKey, setReloadKey] = useState(0)

  const today = dayjs()
  const todayWeekday = today.day() === 0 ? 7 : today.day() // 周一=1 … 周日=7

  useEffect(() => {
    let alive = true
    setLoading(true)
    setLoadError(false)
    ;(async () => {
      try {
        const [d, acts, scheds] = await Promise.all([
          getDashboard(),
          listActivities({ page: 1, size: 20 }),
          listSchedules({}),
        ])
        if (!alive) return
        setDash(d)
        setActivities(acts.records ?? [])
        setSchedules(scheds ?? [])
      } catch {
        if (alive) setLoadError(true)
      } finally {
        if (alive) setLoading(false)
      }
    })()
    return () => {
      alive = false
    }
  }, [reloadKey])

  const stats = useMemo(() => {
    const s = dash?.activityStats
    const total = s
      ? Object.values(s).reduce((sum, v) => sum + (Number(v) || 0), 0)
      : activities.length
    const executing = s?.EXECUTING ?? 0
    return [
      { key: 'total', label: '活动总数', value: total, accent: 'red', icon: <FundOutlined /> },
      { key: 'executing', label: '进行中活动', value: executing, accent: 'orange', icon: <PlayCircleOutlined /> },
      { key: 'week', label: '本周排班', value: dash?.weekSchedules ?? 0, accent: 'blue', icon: <ScheduleOutlined /> },
      { key: 'todo', label: '我的待办', value: dash?.myTasks?.length ?? 0, accent: 'green', icon: <ClockCircleOutlined /> },
    ]
  }, [dash, activities])

  /** 本周排班：按 scheduleDate 是否落在本周过滤，再按 weekday × sessionName 组装紧凑网格 */
  const weekGrid = useMemo(() => {
    // ISO 周边界（周一起）：dayjs.day() 周日=0，减回周一
    const monday = today.subtract(today.day() === 0 ? 6 : today.day() - 1, 'day')
    const sunday = monday.add(6, 'day')
    const inWeek = (schedules ?? []).filter((s) => {
      if (!s.scheduleDate) return false
      const d = dayjs(s.scheduleDate)
      // 用 day 粒度比较：monday/sunday 带当前时刻（如 14:20），scheduleDate 为 0 点，直接比较会误判周一当天
      return !d.isBefore(monday, 'day') && !d.isAfter(sunday, 'day')
    })
    const cells = new Map<string, ScheduleVO[]>()
    inWeek.forEach((s) => {
      const key = `${s.weekday ?? 1}-${s.sessionName ?? ''}`
      const arr = cells.get(key) ?? []
      arr.push(s)
      cells.set(key, arr)
    })
    const rowKeys = Array.from(new Set(inWeek.map((s) => s.sessionName ?? '')))
    return rowKeys.map((sessionName) => ({
      sessionName,
      cells: WEEKDAY_NAMES.map((_, idx) => cells.get(`${idx + 1}-${sessionName}`) ?? []),
    }))
  }, [schedules, today])

  /** 进行中/已下达/策划阶段的活动，按开始日期升序（无日期垫底） */
  const upcomingActivities = useMemo(() => {
    const watch = new Set(['ASSIGNED', 'PLANNING', 'PLAN_REVIEW', 'EXECUTING'])
    return activities
      .filter((a) => watch.has(a.status))
      .sort((a, b) => {
        if (!a.startDate && !b.startDate) return 0
        if (!a.startDate) return 1
        if (!b.startDate) return -1
        return a.startDate.localeCompare(b.startDate)
      })
      .slice(0, 8)
  }, [activities])

  const prioritizedTasks = useMemo(() => {
    const priority: Record<string, number> = { DELAYED: 0, TODO: 1, DOING: 2, DONE: 3 }
    return [...(dash?.myTasks ?? [])]
      .sort((a, b) => {
        const statusDiff = (priority[a.status] ?? 4) - (priority[b.status] ?? 4)
        if (statusDiff !== 0) return statusDiff
        return (a.endDate ?? '9999-12-31').localeCompare(b.endDate ?? '9999-12-31')
      })
      .slice(0, 5)
  }, [dash?.myTasks])

  if (loading) {
    return (
      <div aria-busy="true" aria-label="正在加载仪表盘">
        <PageHeader title={`你好，${user?.realName ?? ''}`} description="正在加载今日工作信息…" />
        <div className="dashboard-stats-grid">
          {Array.from({ length: 4 }, (_, i) => (
            <GlassCard key={i} style={{ padding: 20 }}><Skeleton active title={false} paragraph={{ rows: 2, width: ['65%', '35%'] }} /></GlassCard>
          ))}
        </div>
        <section className="dashboard-work-grid" aria-hidden="true">
          {[0, 1].map((i) => <GlassCard key={i} style={{ padding: 20, minHeight: 260 }}><Skeleton active paragraph={{ rows: 5 }} /></GlassCard>)}
        </section>
        <GlassCard style={{ padding: 20, marginTop: 16, minHeight: 240 }}><Skeleton active paragraph={{ rows: 6 }} /></GlassCard>
      </div>
    )
  }

  if (loadError) {
    return (
      <Result
        status="error"
        title="仪表盘暂时无法加载"
        subTitle="请检查网络或服务状态，然后重试。"
        extra={<Button type="primary" onClick={() => setReloadKey((key) => key + 1)}>重试</Button>}
      />
    )
  }

  return (
    <div>
      <PageHeader
        title={`你好，${user?.realName ?? ''}`}
        description={`${today.format('YYYY年MM月DD日')} ${WEEKDAY_NAMES[todayWeekday - 1]} · 党务管理系统首页`}
      />

      {/* 顶部 4 个统计卡 */}
      <div className="dashboard-stats-grid">
        {stats.map((s) => (
          <GlassCard
            key={s.key}
            className="stat-card"
            style={{ padding: 20, display: 'flex', alignItems: 'center', gap: 16, overflow: 'hidden' }}
          >
            {/* 角落水印图标：同色系超大低透明，增强卡片层次 */}
            <span
              style={{
                position: 'absolute',
                right: -4,
                bottom: -10,
                fontSize: 'var(--fs-watermark)',
                lineHeight: 1,
                color: CARD_ACCENT[s.accent],
                opacity: 0.08,
                pointerEvents: 'none',
              }}
            >
              {s.icon}
            </span>
            <div
              style={{
                width: 36,
                height: 36,
                borderRadius: 'var(--r-md)',
                display: 'grid',
                placeItems: 'center',
                color: '#fff',
                fontSize: 16,
                flexShrink: 0,
                background: CARD_ACCENT[s.accent],
              }}
            >
              {s.icon}
            </div>
            <div style={{ minWidth: 0 }}>
              <div
                style={{
                  fontSize: 'var(--fs-statXl)',
                  fontWeight: 700,
                  lineHeight: 1.15,
                  color: 'var(--text-primary)',
                  fontVariantNumeric: 'tabular-nums',
                }}
              >
                {s.value}
              </div>
              <div style={{ fontSize: 14, color: 'var(--text-secondary)', marginTop: 2 }}>{s.label}</div>
            </div>
          </GlassCard>
        ))}
      </div>

      <section className="dashboard-work-section" aria-label="今日工作重点">
        <div className="dashboard-section-heading">
          <Typography.Title level={4} style={{ margin: 0 }}>今日工作重点</Typography.Title>
          <span>优先处理待办任务，并关注近期活动</span>
        </div>
        <div className="dashboard-work-grid">
          <GlassCard style={{ padding: 20 }}>
            <div className="dashboard-card-heading">
              <Typography.Title level={5} style={{ margin: 0 }}>
                <CheckCircleOutlined style={{ color: 'var(--brand)', marginRight: 8 }} />
                我的待办（{dash?.myTasks?.length ?? 0}）
              </Typography.Title>
              <Button type="link" size="small" onClick={() => navigate('/activities')}>活动列表</Button>
            </div>
            {prioritizedTasks.length === 0 ? (
              <Empty description="暂无指派给你的任务" style={{ padding: 24 }} />
            ) : (
              <div className="dashboard-task-list">
                {prioritizedTasks.map((task) => (
                  <div key={task.id} className="dashboard-task-item">
                    <div className="dashboard-task-copy">
                      <div className="dashboard-item-title">{task.name}</div>
                      <div className="dashboard-item-meta">
                        截止 {task.endDate ? dayjs(task.endDate).format('MM-DD') : '未设置'} · 进度 {task.progress ?? 0}%
                      </div>
                    </div>
                    <Tooltip title={task.status}>
                      <span className={`tone-tag tone-${task.status === 'DONE' ? 'success' : task.status === 'DELAYED' ? 'danger' : task.status === 'DOING' ? 'processing' : 'warning'}`}>
                        {{ TODO: '待办', DOING: '进行中', DONE: '完成', DELAYED: '延期' }[task.status]}
                      </span>
                    </Tooltip>
                    <Button type="text" size="small" onClick={() => navigate(`/activities/${task.activityId}/gantt`)}>
                      查看
                    </Button>
                  </div>
                ))}
              </div>
            )}
          </GlassCard>

          <GlassCard style={{ padding: 20 }}>
            <div className="dashboard-card-heading">
              <Typography.Title level={5} style={{ margin: 0 }}>
                <CalendarOutlined style={{ color: 'var(--brand)', marginRight: 8 }} />
                近期活动
              </Typography.Title>
              <Button type="link" size="small" onClick={() => navigate('/activities')}>全部活动</Button>
            </div>
            {upcomingActivities.length === 0 ? (
              <Empty description="暂无进行中的活动" style={{ padding: 24 }} />
            ) : (
              <div className="dashboard-activity-list">
                {upcomingActivities.slice(0, 5).map((activity) => (
                  <button
                    key={activity.id}
                    type="button"
                    className="dashboard-activity-item"
                    onClick={() => navigate(`/activities/${activity.id}`)}
                  >
                    <span className="dashboard-activity-date">
                      <strong>{activity.startDate ? dayjs(activity.startDate).format('MM-DD') : '—'}</strong>
                      <small>{activity.startDate ? dayjs(activity.startDate).format('ddd') : '待定'}</small>
                    </span>
                    <span className="dashboard-activity-copy">
                      <span className="dashboard-item-title">{activity.name}</span>
                      <span className="dashboard-item-meta">{activity.location || activity.organizer || activity.theme || '未填写地点'}</span>
                    </span>
                    <StatusTag status={activity.status} />
                  </button>
                ))}
              </div>
            )}
          </GlassCard>
        </div>

        <GlassCard style={{ padding: 20, marginTop: 16 }}>
          <div className="dashboard-card-heading">
            <Typography.Title level={5} style={{ margin: 0 }}>
              <ScheduleOutlined style={{ color: 'var(--brand)', marginRight: 8 }} />
              本周排班
            </Typography.Title>
            <Button type="link" onClick={() => navigate('/routine/schedules')}>管理排班</Button>
          </div>
          {weekGrid.length === 0 ? (
            <Empty description="本周暂无排班" style={{ padding: 24 }} />
          ) : (
            <div style={{ overflowX: 'auto' }}>
              <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 14 }}>
                <thead>
                  <tr>
                    <th style={{ padding: '6px 8px', textAlign: 'left', borderBottom: '1px solid var(--border)', color: 'var(--text-secondary)', whiteSpace: 'nowrap' }}>
                      节次 / 时间段
                    </th>
                    {WEEKDAY_NAMES.map((name, idx) => {
                      const isToday = todayWeekday === idx + 1
                      return (
                        <th key={name} style={{ padding: '6px 8px', textAlign: 'center', borderBottom: '1px solid var(--border)', color: 'var(--text-secondary)', background: isToday ? 'var(--brand-soft)' : 'transparent', borderRadius: isToday ? 6 : 0, whiteSpace: 'nowrap' }}>
                          {name}
                          {isToday && <span style={{ color: 'var(--brand)', marginLeft: 4 }}>今</span>}
                        </th>
                      )
                    })}
                  </tr>
                </thead>
                <tbody>
                  {weekGrid.map((row) => (
                    <tr key={row.sessionName}>
                      <td style={{ padding: '6px 8px', fontWeight: 500, verticalAlign: 'top', borderBottom: '1px solid var(--border)', whiteSpace: 'nowrap', color: 'var(--text-primary)' }}>
                        {row.sessionName}
                      </td>
                      {row.cells.map((list, idx) => {
                        const isToday = todayWeekday === idx + 1
                        return (
                          <td key={`${row.sessionName}-${idx}`} style={{ padding: '6px 8px', verticalAlign: 'top', borderBottom: '1px solid var(--border)', minWidth: 96, background: isToday ? 'var(--brand-soft)' : 'transparent' }}>
                            {list.map((schedule) => {
                              const primary = (schedule.persons ?? []).filter((person) => person.isPrimary !== 0).map((person) => person.personName)
                              const deputy = (schedule.persons ?? []).filter((person) => person.isPrimary === 0).map((person) => person.personName)
                              return (
                                <div key={schedule.id} style={{ marginBottom: 6 }}>
                                  <div style={{ fontSize: 13, color: 'var(--brand)' }}>{SCHEDULE_TYPE_MAP[schedule.scheduleType] ?? schedule.scheduleType}</div>
                                  <div style={{ color: 'var(--text-primary)', lineHeight: 1.3 }}>{primary.join('、') || deputy.join('、') || '—'}</div>
                                  {deputy.length > 0 && primary.length > 0 && <div style={{ fontSize: 13, color: 'var(--text-secondary)' }}>{deputy.join('、')}</div>}
                                </div>
                              )
                            })}
                          </td>
                        )
                      })}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </GlassCard>
      </section>

      <Masonry gap={16} minColWidth={300}>
        <GlassCard style={{ padding: 20 }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
              <Typography.Title level={5} style={{ margin: 0 }}>
                <FileTextOutlined style={{ color: 'var(--brand)', marginRight: 8 }} />
                最新推文
              </Typography.Title>
              <Button type="link" onClick={() => navigate('/content/articles')}>
                更多
              </Button>
            </div>
            {(dash?.recentArticles ?? []).length === 0 ? (
              <Empty description="暂无已发布推文" style={{ padding: 20 }} />
            ) : (
              <div style={{ display: 'grid', gap: 8 }}>
                {(dash?.recentArticles ?? []).map((a) => (
                  <div key={a.id} onClick={() => navigate('/content/articles')} className="dash-side-link">
                    <div className="dash-side-title ellipsis">
                      {a.title}
                      {a.articleType && (
                        <span className="tone-tag tone-processing" style={{ marginLeft: 6 }}>
                          {ARTICLE_TYPE_MAP[a.articleType] ?? a.articleType}
                        </span>
                      )}
                    </div>
                    <div style={{ fontSize: 13, color: 'var(--text-secondary)' }}>
                      {fmt(a.publishTime)}
                    </div>
                  </div>
                ))}
              </div>
            )}
          </GlassCard>

          <GlassCard style={{ padding: 20 }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
              <Typography.Title level={5} style={{ margin: 0 }}>
                <FolderOpenOutlined style={{ color: 'var(--brand)', marginRight: 8 }} />
                最新材料
              </Typography.Title>
              <Button type="link" onClick={() => navigate('/archive/materials')}>
                更多
              </Button>
            </div>
            {(dash?.recentMaterials ?? []).length === 0 ? (
              <Empty description="暂无归档材料" style={{ padding: 20 }} />
            ) : (
              <div style={{ display: 'grid', gap: 8 }}>
                {(dash?.recentMaterials ?? []).map((m) => (
                  <div key={m.id} onClick={() => navigate('/archive/materials')} className="dash-side-link">
                    <div className="dash-side-title ellipsis">
                      {m.name}
                      {m.bizType && (
                        <span className="tone-tag tone-neutral" style={{ marginLeft: 6 }}>
                          {MATERIAL_BIZ_TYPE_MAP[m.bizType] ?? m.bizType}
                        </span>
                      )}
                    </div>
                    <div style={{ fontSize: 13, color: 'var(--text-secondary)' }}>{fmt(m.createdAt)}</div>
                  </div>
                ))}
              </div>
            )}
          </GlassCard>

          <GlassCard style={{ padding: 20 }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
              <Typography.Title level={5} style={{ margin: 0 }}>
                <BellOutlined style={{ color: 'var(--brand)', marginRight: 8 }} />
                最新公告
              </Typography.Title>
              <Button type="link" onClick={() => navigate('/archive/announcements')}>
                更多
              </Button>
            </div>
            {(dash?.recentAnnouncements ?? []).length === 0 ? (
              <Empty description="暂无公告" style={{ padding: 20 }} />
            ) : (
              <div style={{ display: 'grid', gap: 8 }}>
                {(dash?.recentAnnouncements ?? []).map((an) => (
                  <div key={an.id} onClick={() => navigate('/archive/announcements')} className="dash-side-link">
                    <div className="dash-side-title ellipsis">{an.title}</div>
                    <div style={{ fontSize: 13, color: 'var(--text-secondary)' }}>
                      {fmt(an.publishTime ?? an.createdAt)}
                    </div>
                  </div>
                ))}
              </div>
            )}
          </GlassCard>
      </Masonry>

      <style>{`
        .dash-activity-item:hover { border-color: var(--brand-hover); }
        .dash-side-link {
          display: block;
          padding: 8px 10px;
          border-radius: 8px;
          border: 1px solid var(--border);
          background: var(--canvas);
          cursor: pointer;
          transition: border-color 0.2s var(--ease);
        }
        .dash-side-link:hover { border-color: var(--brand-hover); }
        .dash-side-title { color: var(--text-primary); font-size: 13px; }
        .ellipsis {
          overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
        }
      `}</style>
    </div>
  )
}
