import { useEffect, useMemo, useState } from 'react'
import { Button, Empty, Space, Spin, Tag, Tooltip, Typography } from 'antd'
import {
  BellOutlined,
  CalendarOutlined,
  CheckCircleOutlined,
  ClockCircleOutlined,
  FileTextOutlined,
  FolderOpenOutlined,
  PlayCircleOutlined,
  ScheduleOutlined,
  TrophyOutlined,
} from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import dayjs from 'dayjs'
import GlassCard from '@/components/glass/GlassCard'
import Masonry from '@/components/glass/Masonry'
import PageHeader from '@/components/glass/PageHeader'
import { getDashboard, type DashboardData } from '@/api/dashboard'
import { listActivities, type ActivityVO } from '@/api/activity'
import { listSchedules, SCHEDULE_TYPE_MAP, WEEKDAY_NAMES, type ScheduleVO } from '@/api/schedule'
import { MATERIAL_BIZ_TYPE_MAP } from '@/api/material'
import { ARTICLE_TYPE_MAP } from '@/api/article'
import { ACTIVITY_STATUS_COLOR, ACTIVITY_STATUS_LABEL } from '@/api/activityStatus'
import { useAuthStore } from '@/stores/auth'

/** 统计卡配色（图标底色圆角块） */
const CARD_ACCENT: Record<string, string> = {
  red: 'var(--color-red)',
  orange: '#FA8C16',
  green: '#52A052',
  blue: '#2F6FB0',
}

const fmt = (t?: string | null) => (t ? dayjs(t).format('MM-DD HH:mm') : '—')

export default function Dashboard() {
  const navigate = useNavigate()
  const user = useAuthStore((s) => s.user)

  const [dash, setDash] = useState<DashboardData | null>(null)
  const [activities, setActivities] = useState<ActivityVO[]>([])
  const [schedules, setSchedules] = useState<ScheduleVO[]>([])
  const [loading, setLoading] = useState(true)
  const [cleared, setCleared] = useState(false)

  const today = dayjs()
  const todayWeekday = today.day() === 0 ? 7 : today.day() // 周一=1 … 周日=7

  useEffect(() => {
    let alive = true
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
        /* http 拦截已提示 */
      } finally {
        if (alive) setLoading(false)
      }
    })()
    return () => {
      alive = false
    }
  }, [])

  const stats = useMemo(() => {
    const s = dash?.activityStats
    const total = s
      ? Object.values(s).reduce((sum, v) => sum + (Number(v) || 0), 0)
      : activities.length
    const executing = s?.EXECUTING ?? 0
    return [
      { key: 'total', label: '活动总数', value: total, accent: 'red', icon: <TrophyOutlined /> },
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

  if (loading) {
    return (
      <div style={{ display: 'grid', placeItems: 'center', padding: 120 }}>
        <Spin size="large" />
      </div>
    )
  }

  return (
    <div>
      <PageHeader
        title={`你好，${user?.realName ?? ''}`}
        description={`${today.format('YYYY年MM月DD日')} ${WEEKDAY_NAMES[todayWeekday - 1]} · 党务管理系统首页`}
      />

      {/* 顶部 4 个统计卡 */}
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))',
          gap: 16,
          marginBottom: 16,
        }}
      >
        {stats.map((s) => (
          <GlassCard key={s.key} style={{ padding: 20, display: 'flex', alignItems: 'center', gap: 16 }}>
            <div
              style={{
                width: 48,
                height: 48,
                borderRadius: 14,
                display: 'grid',
                placeItems: 'center',
                color: '#fff',
                fontSize: 22,
                background: `linear-gradient(135deg, ${CARD_ACCENT[s.accent]}, ${CARD_ACCENT[s.accent]}cc)`,
                boxShadow: `0 8px 20px ${CARD_ACCENT[s.accent]}44`,
              }}
            >
              {s.icon}
            </div>
            <div>
              <div style={{ fontSize: 28, fontWeight: 700, color: 'var(--color-text)', lineHeight: 1.2 }}>{s.value}</div>
              <div style={{ fontSize: 13, color: 'var(--color-text-secondary)' }}>{s.label}</div>
            </div>
          </GlassCard>
        ))}
      </div>

      <Masonry gap={16} minColWidth={300}>
        <GlassCard style={{ padding: 20 }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
              <Typography.Title level={5} style={{ margin: 0 }}>
                <ScheduleOutlined style={{ color: 'var(--color-red)', marginRight: 8 }} />
                本周排班
              </Typography.Title>
              <Button type="link" onClick={() => navigate('/routine/schedules')}>
                管理排班
              </Button>
            </div>
            {weekGrid.length === 0 ? (
              <Empty description="本周暂无排班" style={{ padding: 24 }} />
            ) : (
              <div style={{ overflowX: 'auto' }}>
                <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
                  <thead>
                    <tr>
                      <th
                        style={{
                          padding: '6px 8px',
                          textAlign: 'left',
                          borderBottom: '1px solid var(--color-border)',
                          color: 'var(--color-text-secondary)',
                          whiteSpace: 'nowrap',
                        }}
                      >
                        节次 / 时间段
                      </th>
                      {WEEKDAY_NAMES.map((name, idx) => {
                        const isToday = todayWeekday === idx + 1
                        return (
                          <th
                            key={name}
                            style={{
                              padding: '6px 8px',
                              textAlign: 'center',
                              borderBottom: '1px solid var(--color-border)',
                              color: 'var(--color-text-secondary)',
                              background: isToday ? 'var(--color-red-soft)' : 'transparent',
                              borderRadius: isToday ? 6 : 0,
                              whiteSpace: 'nowrap',
                            }}
                          >
                            {name}
                            {isToday && <span style={{ color: 'var(--color-red)', marginLeft: 4 }}>今</span>}
                          </th>
                        )
                      })}
                    </tr>
                  </thead>
                  <tbody>
                    {weekGrid.map((row) => (
                      <tr key={row.sessionName}>
                        <td
                          style={{
                            padding: '6px 8px',
                            fontWeight: 500,
                            verticalAlign: 'top',
                            borderBottom: '1px solid var(--color-border)',
                            whiteSpace: 'nowrap',
                            color: 'var(--color-text)',
                          }}
                        >
                          {row.sessionName}
                        </td>
                        {row.cells.map((list, idx) => {
                          const isToday = todayWeekday === idx + 1
                          return (
                            <td
                              key={`${row.sessionName}-${idx}`}
                              style={{
                                padding: '6px 8px',
                                verticalAlign: 'top',
                                borderBottom: '1px solid var(--color-border)',
                                minWidth: 96,
                                background: isToday ? 'var(--color-red-soft)' : 'transparent',
                              }}
                            >
                              {list.map((s) => {
                                const primary = (s.persons ?? [])
                                  .filter((p) => p.isPrimary !== 0)
                                  .map((p) => p.personName)
                                const deputy = (s.persons ?? [])
                                  .filter((p) => p.isPrimary === 0)
                                  .map((p) => p.personName)
                                return (
                                  <div key={s.id} style={{ marginBottom: 6 }}>
                                    <div style={{ fontSize: 11, color: 'var(--color-red)' }}>
                                      {SCHEDULE_TYPE_MAP[s.scheduleType] ?? s.scheduleType}
                                    </div>
                                    <div style={{ color: 'var(--color-text)', lineHeight: 1.3 }}>
                                      {primary.join('、') || deputy.join('、') || '—'}
                                    </div>
                                    {deputy.length > 0 && primary.length > 0 && (
                                      <div style={{ fontSize: 11, color: 'var(--color-text-secondary)' }}>
                                        {deputy.join('、')}
                                      </div>
                                    )}
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

          <GlassCard style={{ padding: 20 }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
              <Typography.Title level={5} style={{ margin: 0 }}>
                <CalendarOutlined style={{ color: 'var(--color-red)', marginRight: 8 }} />
                活动动态
              </Typography.Title>
              <Space size={4}>
                {!cleared && (
                  <Button type="link" size="small" onClick={() => setCleared(true)}>
                    清除
                  </Button>
                )}
                <Button type="link" onClick={() => navigate('/activities')}>
                  全部活动
                </Button>
              </Space>
            </div>
            {cleared || upcomingActivities.length === 0 ? (
              <Empty description={cleared ? '已清除活动动态' : '暂无进行中的活动'} style={{ padding: 24 }} />
            ) : (
              <div className="hide-scrollbar" style={{ display: 'grid', gap: 8, maxHeight: 194, overflowY: 'auto', paddingRight: 6 }}>
                {upcomingActivities.map((a) => {
                  return (
                    <div
                      key={a.id}
                      onClick={() => navigate(`/activities/${a.id}`)}
                      style={{
                        display: 'flex',
                        alignItems: 'center',
                        gap: 12,
                        padding: '10px 12px',
                        borderRadius: 10,
                        border: '1px solid var(--surface-border)',
                        background: 'var(--surface)',
                        cursor: 'pointer',
                        transition: 'border-color 0.2s var(--easing)',
                      }}
                      className="dash-activity-item"
                    >
                      <div style={{ minWidth: 52, textAlign: 'center' }}>
                        <div style={{ fontSize: 16, fontWeight: 700, color: 'var(--color-red)' }}>
                          {a.startDate ? dayjs(a.startDate).format('MM-DD') : '—'}
                        </div>
                        <div style={{ fontSize: 11, color: 'var(--color-text-secondary)' }}>
                          {a.startDate ? dayjs(a.startDate).format('ddd') : '待定'}
                        </div>
                      </div>
                      <div style={{ flex: 1, minWidth: 0 }}>
                        <div style={{ color: 'var(--color-text)', fontWeight: 500 }} className="ellipsis">
                          {a.name}
                        </div>
                        <div style={{ fontSize: 12, color: 'var(--color-text-secondary)' }} className="ellipsis">
                          {a.location || a.organizer || a.theme || '—'}
                        </div>
                      </div>
                      <Tag color={ACTIVITY_STATUS_COLOR[a.status] ?? '#8C8C8C'} style={{ marginInlineEnd: 0 }}>
                        {ACTIVITY_STATUS_LABEL[a.status] ?? a.status}
                      </Tag>
                    </div>
                  )
                })}
              </div>
            )}
          </GlassCard>

          {/* 我的待办 */}
          <GlassCard style={{ padding: 20 }}>
            <Typography.Title level={5} style={{ margin: 0, marginBottom: 12 }}>
              <CheckCircleOutlined style={{ color: 'var(--color-red)', marginRight: 8 }} />
              我的待办（{dash?.myTasks?.length ?? 0}）
            </Typography.Title>
            {!dash?.myTasks || dash.myTasks.length === 0 ? (
              <Empty description="暂无指派给你的任务" style={{ padding: 24 }} />
            ) : (
              <div style={{ display: 'grid', gap: 8 }}>
                {dash.myTasks.map((t) => (
                  <div
                    key={t.id}
                    style={{
                      display: 'flex',
                      alignItems: 'center',
                      gap: 12,
                      padding: '10px 12px',
                      borderRadius: 10,
                      border: '1px solid var(--surface-border)',
                      background: 'var(--surface)',
                    }}
                  >
                    <div style={{ flex: 1, minWidth: 0 }}>
                      <div className="ellipsis" style={{ color: 'var(--color-text)', fontWeight: 500 }}>
                        {t.name}
                      </div>
                      <div style={{ fontSize: 12, color: 'var(--color-text-secondary)' }}>
                        截止 {t.endDate ? dayjs(t.endDate).format('MM-DD') : '—'} · 进度 {t.progress ?? 0}%
                      </div>
                    </div>
                    <Tooltip title={t.status}>
                      <Tag color={t.status === 'DONE' ? 'green' : t.status === 'DELAYED' ? 'red' : 'gold'}>
                        {{ TODO: '待办', DOING: '进行中', DONE: '完成', DELAYED: '延期' }[t.status]}
                      </Tag>
                    </Tooltip>
                  </div>
                ))}
              </div>
            )}
          </GlassCard>
        <GlassCard style={{ padding: 20 }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
              <Typography.Title level={5} style={{ margin: 0 }}>
                <FileTextOutlined style={{ color: 'var(--color-red)', marginRight: 8 }} />
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
                        <Tag
                          style={{ marginLeft: 6, fontSize: 10, lineHeight: '16px' }}
                          color="blue"
                        >
                          {ARTICLE_TYPE_MAP[a.articleType] ?? a.articleType}
                        </Tag>
                      )}
                    </div>
                    <div style={{ fontSize: 12, color: 'var(--color-text-secondary)' }}>
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
                <FolderOpenOutlined style={{ color: 'var(--color-red)', marginRight: 8 }} />
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
                        <Tag
                          style={{ marginLeft: 6, fontSize: 10, lineHeight: '16px' }}
                          color="geekblue"
                        >
                          {MATERIAL_BIZ_TYPE_MAP[m.bizType] ?? m.bizType}
                        </Tag>
                      )}
                    </div>
                    <div style={{ fontSize: 12, color: 'var(--color-text-secondary)' }}>{fmt(m.createdAt)}</div>
                  </div>
                ))}
              </div>
            )}
          </GlassCard>

          <GlassCard style={{ padding: 20 }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
              <Typography.Title level={5} style={{ margin: 0 }}>
                <BellOutlined style={{ color: 'var(--color-red)', marginRight: 8 }} />
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
                    <div style={{ fontSize: 12, color: 'var(--color-text-secondary)' }}>
                      {fmt(an.publishTime ?? an.createdAt)}
                    </div>
                  </div>
                ))}
              </div>
            )}
          </GlassCard>
      </Masonry>

      <style>{`
        .dash-activity-item:hover { border-color: rgba(222,41,16,0.4); }
        .dash-side-link {
          display: block;
          padding: 8px 10px;
          border-radius: 8px;
          border: 1px solid var(--surface-border);
          background: var(--surface);
          cursor: pointer;
          transition: border-color 0.2s var(--easing);
        }
        .dash-side-link:hover { border-color: rgba(222,41,16,0.4); }
        .dash-side-title { color: var(--color-text); font-size: 13px; }
        .ellipsis {
          overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
        }
      `}</style>
    </div>
  )
}
