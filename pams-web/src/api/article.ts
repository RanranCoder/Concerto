import { get, post, put, del } from './http'
import type { PageResult } from './types'

export interface ArticleVO {
  id: number
  title: string
  summary: string
  content: string
  coverUrl: string
  activityId: number | null
  activityName: string
  articleType: 'PREHEAT' | 'REPORT' | 'VIDEO'
  status: 'DRAFT' | 'PENDING' | 'APPROVED' | 'PUBLISHED' | 'REJECTED'
  authorId: number | null
  reviewerId: number | null
  reviewComment: string
  imageUrls: string[]
  deadline: string | null
  wxUrl: string | null
  readCount: number
  likeCount: number
  publishTime: string | null
  createdAt: string | null
  updatedAt: string | null
}

export interface ArticleSave {
  title: string
  summary?: string
  content?: string
  coverUrl?: string
  activityId: number
  authorId?: number
  deadline?: string
  imageUrls?: string[]
  articleType?: string
}

export const ARTICLE_STATUS_MAP: Record<string, string> = {
  DRAFT: '草稿',
  PENDING: '待审',
  APPROVED: '待发布',
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

export const listArticles = (params: {
  status?: string
  type?: string
  keyword?: string
  activityId?: number
  page?: number
  size?: number
}) => get<PageResult<ArticleVO>>('/articles', params)
export const createArticle = (data: ArticleSave) => post<number>('/articles', data)
export const updateArticle = (id: number, data: ArticleSave) => put<void>(`/articles/${id}`, data)
export const submitArticle = (id: number) => put<void>(`/articles/${id}/submit`)
export const reviewArticle = (id: number, approved: boolean, comment?: string) =>
  put<void>(`/articles/${id}/review`, { approved, comment })
export const publishArticle = (id: number, data: { wxUrl: string }) =>
  put<void>(`/articles/${id}/publish`, data)
export const updateArticleStats = (id: number, data: { readCount: number; likeCount: number }) =>
  put<void>(`/articles/${id}/stats`, data)
export const deleteArticle = (id: number) => del<void>(`/articles/${id}`)
