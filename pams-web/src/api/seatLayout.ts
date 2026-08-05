import { get, post, put, del } from './http'

// ==================== F01 座位表布局 ====================

export interface SeatLayoutVO {
  id: number
  activityId: number | null
  name: string
  rows: number
  cols: number
  aisleCols: string
  aisleWidthRatio: number
  seatData: string
  colorLabels: string
  isTemplate: boolean
  templateCategory: string
  createdBy: number | null
  createdAt: string
  updatedAt: string
}

export interface SeatLayoutSave {
  activityId?: number | null
  name: string
  rows: number
  cols: number
  aisleCols?: string
  aisleWidthRatio?: number
  seatData?: string
  colorLabels?: string
  asTemplate?: boolean
  templateCategory?: string
}

/** 格子状态 */
export interface SeatCell {
  row: number
  col: number
  /** seat（座位）/ aisle（过道）/ empty（空） */
  type: 'seat' | 'aisle' | 'empty'
  color?: string
  label?: string
  /** 选座状态：EMPTY / SELECTED / LOCKED */
  state?: string
  personName?: string
}

/** 自定义配色项 */
export interface ColorLabel {
  color: string
  label: string
}

export const getActivitySeatLayout = (activityId: number) =>
  get<SeatLayoutVO | null>('/seat-layouts/activity', { activityId })
export const listActivitySeatLayouts = (activityId: number) =>
  get<SeatLayoutVO[]>('/seat-layouts/activity/all', { activityId })
export const listSeatTemplates = () => get<SeatLayoutVO[]>('/seat-layouts/templates')
export const createSeatLayout = (data: SeatLayoutSave) => post<number>('/seat-layouts', data)
export const updateSeatLayout = (id: number, data: SeatLayoutSave) => put<void>(`/seat-layouts/${id}`, data)
export const saveSeatLayoutAsTemplate = (id: number, category?: string) =>
  post<number>(`/seat-layouts/${id}/save-as-template`, undefined, { params: { category } })
export const createSeatLayoutFromTemplate = (templateId: number, activityId: number, name?: string) =>
  post<number>('/seat-layouts/from-template', undefined, { params: { templateId, activityId, name } })
export const deleteSeatTemplate = (id: number) => del<void>(`/seat-layouts/templates/${id}`)
