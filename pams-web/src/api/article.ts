import { get, post, put, del } from './http'
import type { PageResult } from './types'

export interface ArticleVO {
  id: number
  title: string
  summary: string
  content: string
  coverUrl: string
  activityId: number | null
  articleType: 'PREHEAT' | 'REPORT' | 'VIDEO'
  status: 'DRAFT' | 'PENDING' | 'PUBLISHED' | 'REJECTED'
  authorId: number | null
  reviewerId: number | null
  reviewComment: string
  publishTime: string | null
  createdAt: string | null
  updatedAt: string | null
}

export interface ArticleSave {
  title: string
  summary?: string
  content?: string
  coverUrl?: string
  activityId?: number | null
  articleType?: string
}

export const ARTICLE_STATUS_MAP: Record<string, string> = {
  DRAFT: '草稿',
  PENDING: '待审',
  PUBLISHED: '已发布',
  REJECTED: '被驳回',
}

export const ARTICLE_STATUS_OPTIONS = Object.entries(ARTICLE_STATUS_MAP).map(([value, label]) => ({ value, label }))

export const ARTICLE_TYPE_MAP: Record<string, string> = {
  PREHEAT: '预热',
  REPORT: '报道',
  VIDEO: '宣传视频',
}

export const ARTICLE_TYPE_OPTIONS = Object.entries(ARTICLE_TYPE_MAP).map(([value, label]) => ({ value, label }))

export const listArticles = (params: { status?: string; type?: string; keyword?: string; page?: number; size?: number }) =>
  get<PageResult<ArticleVO>>('/articles', params)
export const createArticle = (data: ArticleSave) => post<number>('/articles', data)
export const updateArticle = (id: number, data: ArticleSave) => put<void>(`/articles/${id}`, data)
export const submitArticle = (id: number) => put<void>(`/articles/${id}/submit`)
export const reviewArticle = (id: number, approved: boolean, comment?: string) =>
  put<void>(`/articles/${id}/review`, { approved, comment })
export const deleteArticle = (id: number) => del<void>(`/articles/${id}`)
