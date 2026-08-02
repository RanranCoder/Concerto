import { get, post, put, del } from './http'
import type { PageResult } from './types'

export interface MaterialVO {
  id: number
  name: string
  /** 业务类型：PLAN / SIGNIN / SCHEDULE / ATTENDANCE / NEWS / ARTICLE / PHOTO / PPT / INVOICE / ROSTER / OTHER */
  bizType: string
  activityId: number | null
  deptId: number | null
  uploaderId: number | null
  tag: string
  description: string
  fileId: number | null
  createdAt: string
}

export interface MaterialSave {
  name: string
  bizType: string
  activityId?: number | null
  deptId?: number | null
  tag?: string | null
  description?: string | null
  fileId?: number | null
}

/** 材料归档树节点（后端 MaterialService.tree 原样返回） */
export interface MaterialTreeActivityNode {
  activityId: number | null
  bizTypes: { bizType: string; materials: MaterialVO[] }[]
}

export const MATERIAL_BIZ_TYPES = [
  'PLAN',
  'SIGNIN',
  'SCHEDULE',
  'ATTENDANCE',
  'NEWS',
  'ARTICLE',
  'PHOTO',
  'PPT',
  'INVOICE',
  'ROSTER',
  'OTHER',
] as const

export const MATERIAL_BIZ_TYPE_MAP: Record<string, string> = {
  PLAN: '策划书',
  SIGNIN: '签到表',
  SCHEDULE: '排班表',
  ATTENDANCE: '考勤表',
  NEWS: '新闻稿',
  ARTICLE: '推文',
  PHOTO: '照片',
  PPT: 'PPT',
  INVOICE: '发票',
  ROSTER: '名单',
  OTHER: '其他',
}

export const MATERIAL_BIZ_TYPE_OPTIONS = MATERIAL_BIZ_TYPES.map((v) => ({ value: v, label: MATERIAL_BIZ_TYPE_MAP[v] ?? v }))

export const listMaterials = (params: { keyword?: string; bizType?: string; activityId?: number; deptId?: number; page?: number; size?: number }) =>
  get<PageResult<MaterialVO>>('/materials', params)
export const getMaterialTree = (activityId?: number) => get<MaterialTreeActivityNode[]>('/materials/tree', { activityId })
export const createMaterial = (data: MaterialSave) => post<MaterialVO>('/materials', data)
export const updateMaterial = (id: number, data: MaterialSave) => put<void>(`/materials/${id}`, data)
export const deleteMaterial = (id: number) => del<void>(`/materials/${id}`)
