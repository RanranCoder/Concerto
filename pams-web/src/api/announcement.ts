import { get, post, put, del } from './http'

export interface AnnouncementVO {
  id: number
  title: string
  content: string
  publisherId: number | null
  publishTime: string | null
  createdAt: string
  important: boolean
  requireConfirmation: boolean
  confirmationVersion: number
}

export interface AnnouncementSave {
  title: string
  content: string
  publishTime?: string | null
  important?: boolean
  requireConfirmation?: boolean
  audienceAll?: boolean
  audienceUserIds?: number[]
  audienceDeptIds?: number[]
  importantCorrection?: boolean
}

export const listAnnouncements = () => get<AnnouncementVO[]>('/announcements')
export const createAnnouncement = (data: AnnouncementSave) => post<AnnouncementVO>('/announcements', data)
export const updateAnnouncement = (id: number, data: AnnouncementSave) => put<void>(`/announcements/${id}`, data)
export const deleteAnnouncement = (id: number) => del<void>(`/announcements/${id}`)
/** 当前用户已读公告 id 列表（已读状态落库，跨设备一致） */
export const listAnnouncementReadIds = () => get<number[]>('/announcements/read-ids')
/** 标记公告已读（幂等） */
export const markAnnouncementRead = (id: number) => post<void>(`/announcements/${id}/read`)
export const announcementConfirmation = (id: number) => get<{ version: number; confirmed_at: string | null }[]>(`/announcements/${id}/confirmation`)
export const confirmAnnouncement = (id: number, version: number) => post<void>(`/announcements/${id}/confirm`, { version })
