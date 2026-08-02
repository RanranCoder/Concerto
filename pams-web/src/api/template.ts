import { get, post, put, del } from './http'

export interface TemplateVO {
  id: number
  name: string
  /** 分类：PLAN / SEAT / AGENDA / SIGNIN / NAMEPLATE / LOGO / EMBER / NEWS */
  category: string
  description: string | null
  fileId: number | null
  createdBy: number | null
  createdAt: string
}

export interface TemplateSave {
  name: string
  category: string
  description?: string | null
  fileId?: number | null
}

export const TEMPLATE_CATEGORIES = [
  'PLAN',
  'SEAT',
  'AGENDA',
  'SIGNIN',
  'NAMEPLATE',
  'LOGO',
  'EMBER',
  'NEWS',
] as const

export const TEMPLATE_CATEGORY_MAP: Record<string, string> = {
  PLAN: '策划书',
  SEAT: '座位表',
  AGENDA: '议程表',
  SIGNIN: '签到表',
  NAMEPLATE: '水牌',
  LOGO: 'LOGO',
  EMBER: '党徽',
  NEWS: '新闻稿',
}

export const TEMPLATE_CATEGORY_OPTIONS = TEMPLATE_CATEGORIES.map((v) => ({ value: v, label: TEMPLATE_CATEGORY_MAP[v] ?? v }))

export const listTemplates = (params: { category?: string } = {}) => get<TemplateVO[]>('/templates', params)
export const createTemplate = (data: TemplateSave) => post<TemplateVO>('/templates', data)
export const updateTemplate = (id: number, data: TemplateSave) => put<void>(`/templates/${id}`, data)
export const deleteTemplate = (id: number) => del<void>(`/templates/${id}`)
