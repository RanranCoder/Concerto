import { createBrowserRouter, Navigate, Outlet } from 'react-router-dom'
import { lazy } from 'react'
import type { ReactNode } from 'react'
import { useAuthStore } from '@/stores/auth'
import MainLayout from '@/layouts/MainLayout'
import RequireRole, { LEADER_ROLES, ADMIN_ROLES } from '@/components/glass/RequireRole'

const Login = lazy(() => import('@/pages/Login'))
const Dashboard = lazy(() => import('@/pages/Dashboard'))
const Forbidden = lazy(() => import('@/pages/Forbidden'))
const Activities = lazy(() => import('@/pages/activity/ActivityList'))

// 以下页面在后续 Task 逐一实现，路由先全量配好，未实现前用占位组件保证可访问不白屏
const ActivityDetail = lazy(() => import('@/pages/activity/ActivityDetail'))
const ActivityEdit = lazy(() => import('@/pages/activity/ActivityEdit'))
const Gantt = lazy(() => import('@/pages/activity/Gantt'))
const Schedules = lazy(() => import('@/pages/routine/ScheduleList'))
const Attendance = lazy(() => import('@/pages/routine/AttendanceList'))
const FreeSchedules = lazy(() => import('@/pages/routine/FreeScheduleList'))
const PartyMembers = lazy(() => import('@/pages/party/PartyMemberList'))
const PartyMemberDetail = lazy(() => import('@/pages/party/PartyMemberDetail'))
const PartyRosters = lazy(() => import('@/pages/party/PartyRosterList'))
const Articles = lazy(() => import('@/pages/content/ArticleList'))
const News = lazy(() => import('@/pages/content/NewsList'))
const Materials = lazy(() => import('@/pages/archive/MaterialList'))
const Templates = lazy(() => import('@/pages/archive/TemplateList'))
const Credits = lazy(() => import('@/pages/archive/CreditList'))
const Announcements = lazy(() => import('@/pages/archive/AnnouncementList'))
const Users = lazy(() => import('@/pages/admin/UserList'))
const Settings = lazy(() => import('@/pages/admin/Settings'))

export function RequireAuth({ children }: { children: ReactNode }) {
  const token = useAuthStore((s) => s.token)
  if (!token) return <Navigate to="/login" replace />
  return <>{children}</>
}

export const router = createBrowserRouter([
  { path: '/login', element: <Login /> },
  {
    path: '/',
    element: (
      <RequireAuth>
        <MainLayout />
      </RequireAuth>
    ),
    children: [
      { index: true, element: <Dashboard /> },

      // 活动管理（干事可看，删除/改状态后端已限制部长及以上）
      { path: '/activities', element: <Activities /> },
      { path: '/activities/:id', element: <ActivityDetail /> },
      { path: '/activities/:id/edit', element: <ActivityEdit /> },
      { path: '/activities/:id/gantt', element: <Gantt /> },

      // 排班考勤
      { path: '/routine/schedules', element: <Schedules /> },
      { path: '/routine/attendance', element: <Attendance /> },
      { path: '/routine/free-schedules', element: <FreeSchedules /> },

      // 党务台账（敏感，部长及以上；干事读已脱敏，仍隐藏入口）
      {
        path: '/party',
        element: <RequireRole roles={LEADER_ROLES}><Outlet /></RequireRole>,
        children: [
          { path: 'members', element: <PartyMembers /> },
          { path: 'members/:id', element: <PartyMemberDetail /> },
          { path: 'rosters', element: <PartyRosters /> },
        ],
      },

      // 内容宣传（推文干事可看；新闻稿入口部长及以上显示）
      { path: '/content/articles', element: <Articles /> },
      { path: '/content/news', element: <News /> },

      // 档案资产
      { path: '/archive/materials', element: <Materials /> },
      { path: '/archive/templates', element: <Templates /> },
      { path: '/archive/credits', element: <Credits /> },
      { path: '/archive/announcements', element: <Announcements /> },

      // 用户管理：简报"主任+额外显示用户管理"，前端仅主任/指导老师可访问
      { path: '/admin/users', element: <RequireRole roles={ADMIN_ROLES}><Users /></RequireRole> },
      // 系统设置：仅主任/指导老师可访问
      { path: '/admin/settings', element: <RequireRole roles={ADMIN_ROLES}><Settings /></RequireRole> },

      { path: '/403', element: <Forbidden /> },
      { path: '*', element: <Navigate to="/" replace /> },
    ],
  },
])
