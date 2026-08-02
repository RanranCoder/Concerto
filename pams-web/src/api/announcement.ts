import { get, post, put, del } from './http'

export interface AnnouncementVO {
  id: number
  title: string
  content: string
  publisherId: number | null
  publishTime: string | null
  createdAt: string
}

export interface AnnouncementSave {
  title: string
  content: string
  publishTime?: string | null
}

export const listAnnouncements = () => get<AnnouncementVO[]>('/announcements')
export const createAnnouncement = (data: AnnouncementSave) => post<AnnouncementVO>('/announcements', data)
export const updateAnnouncement = (id: number, data: AnnouncementSave) => put<void>(`/announcements/${id}`, data)
export const deleteAnnouncement = (id: number) => del<void>(`/announcements/${id}`)
