import { get, post, put, del } from './http'
import type { PageResult } from './types'

export interface NewsVO {
  id: number
  title: string
  subtitle: string
  content: string
  activityId: number | null
  authorId: number | null
  publishDate: string | null
  status: 'DRAFT' | 'PUBLISHED'
  createdAt: string | null
  updatedAt: string | null
}

export interface NewsSave {
  title: string
  subtitle?: string
  content?: string
  activityId?: number | null
}

export const NEWS_STATUS_MAP: Record<string, string> = {
  DRAFT: '草稿',
  PUBLISHED: '已发布',
}

export const NEWS_STATUS_OPTIONS = Object.entries(NEWS_STATUS_MAP).map(([value, label]) => ({ value, label }))

export const listNews = (params: { keyword?: string; page?: number; size?: number }) =>
  get<PageResult<NewsVO>>('/news', params)
export const createNews = (data: NewsSave) => post<number>('/news', data)
export const updateNews = (id: number, data: NewsSave) => put<void>(`/news/${id}`, data)
export const deleteNews = (id: number) => del<void>(`/news/${id}`)
