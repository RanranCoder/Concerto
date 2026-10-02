import { createBrowserRouter, Navigate, Outlet } from 'react-router-dom'
import { lazy, useEffect } from 'react'
import type { ReactNode } from 'react'
import { useAuthStore } from '@/stores/auth'
import { useOrgStore } from '@/stores/org'
import { meApi } from '@/api/auth'
import MainLayout from '@/layouts/MainLayout'
import RequireRole, { LEADER_ROLES, ADMIN_ROLES, RequirePerm } from '@/components/glass/RequireRole'

const Login = lazy(() => import('@/pages/Login'))
const SigninScan = lazy(() => import('@/pages/signin/SigninScan'))
const Dashboard = lazy(() => import('@/pages/Dashboard'))
const Forbidden = lazy(() => import('@/pages/Forbidden'))
const Activities = lazy(() => import('@/pages/activity/ActivityList'))
const KnowledgeBase = lazy(() => import('@/pages/knowledge/KnowledgeBase'))

// 以下页面在后续 Task 逐一实现，路由先全量配好，未实现前用占位组件保证可访问不白屏
const ActivityDetail = lazy(() => import('@/pages/activity/ActivityDetail'))
const ActivityEdit = lazy(() => import('@/pages/activity/ActivityEdit'))
const Gantt = lazy(() => import('@/pages/activity/Gantt'))
const Schedules = lazy(() => import('@/pages/routine/ScheduleList'))
const MySchedules = lazy(() => import('@/pages/routine/MyScheduleList'))
const Attendance = lazy(() => import('@/pages/routine/AttendanceList'))
const ActivitySchedules = lazy(() => import('@/pages/routine/ActivityScheduleList'))
const FreeSchedules = lazy(() => import('@/pages/routine/FreeScheduleList'))
const CourseSchedule = lazy(() => import('@/pages/routine/CourseSchedule'))
const PartyMembers = lazy(() => import('@/pages/party/PartyMemberList'))
const PartyMemberDetail = lazy(() => import('@/pages/party/PartyMemberDetail'))
const PartyRosters = lazy(() => import('@/pages/party/PartyRosterList'))
const Articles = lazy(() => import('@/pages/content/ArticleList'))
const News = lazy(() => import('@/pages/content/NewsList'))
const Materials = lazy(() => import('@/pages/archive/MaterialList'))
const Templates = lazy(() => import('@/pages/archive/TemplateList'))
const Credits = lazy(() => import('@/pages/archive/CreditList'))
const Announcements = lazy(() => import('@/pages/archive/AnnouncementList'))
const GroupChats = lazy(() => import('@/pages/archive/GroupChatList'))
const Users = lazy(() => import('@/pages/admin/UserList'))
const PermissionManage = lazy(() => import('@/pages/admin/PermissionManage'))
const Settings = lazy(() => import('@/pages/admin/Settings'))
const NotificationList = lazy(() => import('@/pages/notification/NotificationList'))
const MemberList = lazy(() => import('@/pages/member/MemberList'))
const MemberDetail = lazy(() => import('@/pages/member/MemberDetail'))
const AgentHome = lazy(() => import('@/pages/agent/AgentHome'))
const OrgRegister = lazy(() => import('@/pages/org/OrgRegister'))
const OrgStructure = lazy(() => import('@/pages/org/OrgStructure'))

export function RequireAuth({ children }: { children: ReactNode }) {
  const token = useAuthStore((s) => s.token)
  const refreshUser = useAuthStore((s) => s.refreshUser)
  // 启动/刷新页面时用 JWT 换取最新 user+permissions，使权限管理页的变更刷新即生效（无需重新登录）
  useEffect(() => {
    if (token) {
      meApi().then((u) => refreshUser(u)).catch(() => { /* 401 由 http 拦截器登出 */ })
      // 拉取当前组织（品牌/主题色），驱动 App.tsx 的 colorPrimary
      useOrgStore.getState().load()
    }
  }, [token, refreshUser])
  if (!token) return <Navigate to="/login" replace />
  return <>{children}</>
}

export const router = createBrowserRouter([
  { path: '/login', element: <Login /> },
  // 注册组织：免登录公开访问（与 /login 同级，位于 MainLayout / RequireAuth 之外）
  { path: '/register', element: <OrgRegister /> },
  // 扫码签到落地页：免登录公开访问（与 /login 同级，位于 MainLayout / RequireAuth 之外）
  { path: '/signin/:token', element: <SigninScan /> },
  {
    path: '/',
    element: (
      <RequireAuth>
        <MainLayout />
      </RequireAuth>
    ),
    children: [
      { index: true, element: <Dashboard /> },

      // 灵思（Agent）：对话页对所有登录用户开放。
      // 注意：这里不加 RequirePerm —— 权限控制在**工具层**（D3 三层防护）：
      // 每个工具声明 requiredPermissions，ToolRegistry.dispatch 派发前断言，
      // 且 /api/agent/tools 只返回当前用户可见的工具。所以干事进来只会看到自己有权做的事。
      { path: '/agent', element: <AgentHome /> },

      // 活动管理（干事可看，删除/改状态后端已限制部长及以上）
      { path: '/activities', element: <Activities /> },
      { path: '/activities/:id', element: <ActivityDetail /> },
      { path: '/activities/:id/edit', element: <ActivityEdit /> },
      { path: '/activities/:id/gantt', element: <Gantt /> },

      // 排班考勤
      { path: '/routine/schedules', element: <Schedules /> },
      { path: '/routine/my-schedules', element: <MySchedules /> },
      { path: '/routine/attendance', element: <Attendance /> },
      { path: '/routine/activity-schedules', element: <ActivitySchedules /> },
      { path: '/routine/course-schedule', element: <CourseSchedule /> },
      { path: '/routine/free-schedules', element: <FreeSchedules /> },

      // 成员管理（敏感名单，仅拥有 member:view 权限码的角色可见）
      {
        path: '/members',
        element: <RequirePerm codes={['member:view']}><Outlet /></RequirePerm>,
        children: [
          { path: '', element: <MemberList /> },
          { path: ':id', element: <MemberDetail /> },
        ],
      },

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
      { path: '/archive/group-chats', element: <GroupChats /> },

      // 通知中心
      { path: '/notifications', element: <NotificationList /> },

      // 知识库（V27）：灵思「对话」档位能检索到的全部资料。
      // kb:read 种子给了全部角色 —— 知识问答本身人人可用，
      // 能看到哪些内容由各知识源的可见范围决定，不由这个码决定（与 /agent 同一立场）。
      {
        path: '/knowledge',
        element: <RequirePerm codes={['kb:read']}><KnowledgeBase /></RequirePerm>,
      },

      // 组织架构：ADMIN（主任 / 指导老师）可编辑本组织架构树
      {
        path: '/org/structure',
        element: <RequireRole roles={ADMIN_ROLES}><OrgStructure /></RequireRole>,
      },

      // 用户管理：拥有 user:view 权限码可访问（默认主任/指导老师）
      { path: '/admin/users', element: <RequirePerm codes={['user:view']}><Users /></RequirePerm> },
      // 权限管理：拥有 user:permission 权限码可访问（默认仅指导老师，可由指导老师在权限管理页开放给主任）
      { path: '/admin/permissions', element: <RequirePerm codes={['user:permission']}><PermissionManage /></RequirePerm> },
      // 系统设置：仅主任/指导老师可访问
      { path: '/admin/settings', element: <RequireRole roles={ADMIN_ROLES}><Settings /></RequireRole> },

      { path: '/403', element: <Forbidden /> },
      { path: '*', element: <Navigate to="/" replace /> },
    ],
  },
])
