import { createBrowserRouter, Navigate } from 'react-router-dom'
import { lazy } from 'react'
import type { ReactNode } from 'react'
import { useAuthStore } from '@/stores/auth'
import MainLayout from '@/layouts/MainLayout'

const Login = lazy(() => import('@/pages/Login'))
const Dashboard = lazy(() => import('@/pages/Dashboard'))
const Activities = lazy(() => import('@/pages/activity/ActivityList'))

// 以下页面在后续 Task 逐一实现，路由先全量配好，未实现前用占位组件保证可访问不白屏
const ActivityDetail = lazy(() => import('@/pages/activity/ActivityDetail'))
const Gantt = lazy(() => import('@/pages/activity/Gantt'))
const Schedules = lazy(() => import('@/pages/routine/ScheduleList'))
const Attendance = lazy(() => import('@/pages/routine/AttendanceList'))
const FreeSchedules = lazy(() => import('@/pages/routine/FreeScheduleList'))
const PartyMembers = lazy(() => import('@/pages/party/PartyMemberList'))
const PartyMemberDetail = lazy(() => import('@/pages/party/PartyMemberDetail'))
const PartyRosters = lazy(() => import('@/pages/party/PartyRosterList'))
const Articles = lazy(() => import('@/pages/content/ArticleList'))
const Materials = lazy(() => import('@/pages/archive/MaterialList'))
const Templates = lazy(() => import('@/pages/archive/TemplateList'))
const Credits = lazy(() => import('@/pages/archive/CreditList'))
const Announcements = lazy(() => import('@/pages/archive/AnnouncementList'))
const Users = lazy(() => import('@/pages/admin/UserList'))

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

      // 活动管理
      { path: '/activities', element: <Activities /> },
      { path: '/activities/:id', element: <ActivityDetail /> },
      { path: '/activities/:id/gantt', element: <Gantt /> },

      // 排班考勤
      { path: '/routine/schedules', element: <Schedules /> },
      { path: '/routine/attendance', element: <Attendance /> },
      { path: '/routine/free-schedules', element: <FreeSchedules /> },

      // 党务台账
      { path: '/party/members', element: <PartyMembers /> },
      { path: '/party/members/:id', element: <PartyMemberDetail /> },
      { path: '/party/rosters', element: <PartyRosters /> },

      // 内容宣传
      { path: '/content/articles', element: <Articles /> },

      // 档案资产
      { path: '/archive/materials', element: <Materials /> },
      { path: '/archive/templates', element: <Templates /> },
      { path: '/archive/credits', element: <Credits /> },
      { path: '/archive/announcements', element: <Announcements /> },

      // 用户管理
      { path: '/admin/users', element: <Users /> },

      { path: '*', element: <Navigate to="/" replace /> },
    ],
  },
])
