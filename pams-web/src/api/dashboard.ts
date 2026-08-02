import { get } from './http'

/** 活动按状态计数（6 态，未出现的状态补 0） */
export interface ActivityStats {
  ASSIGNED: number
  PLANNING: number
  PLAN_REVIEW: number
  EXECUTING: number
  FINISHED: number
  ARCHIVED: number
}

export interface DashboardArticleVO {
  id: number
  title: string
  summary: string | null
  articleType: 'PREHEAT' | 'REPORT' | 'VIDEO' | null
  publishTime: string | null
}

export interface DashboardMaterialVO {
  id: number
  name: string
  bizType: string
  createdAt: string | null
}

export interface DashboardAnnouncementVO {
  id: number
  title: string
  publishTime: string | null
  createdAt: string | null
}

/** 我的待办：后端 Task 实体精简（详情在活动甘特页完整展示） */
export interface DashboardTaskVO {
  id: number
  activityId: number
  name: string
  assignee: string | null
  startDate: string | null
  endDate: string | null
  progress: number | null
  status: 'TODO' | 'DOING' | 'DONE' | 'DELAYED'
}

export interface DashboardData {
  activityStats: ActivityStats | null
  /** 本周（周一起）排班条数 */
  weekSchedules: number
  recentArticles: DashboardArticleVO[]
  recentMaterials: DashboardMaterialVO[]
  recentAnnouncements: DashboardAnnouncementVO[]
  myTasks: DashboardTaskVO[]
}

export const getDashboard = () => get<DashboardData>('/dashboard')
