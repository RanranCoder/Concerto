import { get, post, put, del, http } from './http'
import type { PageResult } from './types'
import type { AxiosResponse } from 'axios'

export interface MemberVO {
  id: number
  sessionId: number
  sessionName: string | null
  deptId: number | null
  deptName: string | null
  position: string
  positionLabel: string
  name: string
  gender: string | null
  studentNo: string | null
  className: string | null
  phone: string | null
  politicalStatus: string | null
  status: string
  statusLabel: string
  remark: string | null
  createdAt: string
  updatedAt: string
}

export interface MemberSessionVO {
  id: number
  name: string
  isCurrent: number
  sortOrder: number
  remark: string | null
}

export interface MemberSave {
  sessionId: number
  deptId?: number | null
  position: string
  name: string
  gender?: string | null
  studentNo?: string | null
  className?: string | null
  phone?: string | null
  politicalStatus?: string | null
  status?: string
  remark?: string | null
}

export interface MemberImportFailure { row: number; name: string; reason: string }
export interface MemberImportResult { total: number; success: number; skipped: number; failed: MemberImportFailure[] }
export interface NameCount { name: string; count: number }
export interface MemberStats { total: number; byDept: NameCount[]; byPosition: NameCount[]; byStatus: NameCount[] }
export interface MemberCredit { id: number; project: string; credit: number; basis: string | null; remark: string | null; createdAt: string }
export interface MemberDetail {
  member: MemberVO
  scheduleCount: number
  attendanceCount: number
  totalCredit: number
  credits: MemberCredit[]
}
export interface UnregisteredMember { id: number; name: string; studentNo: string; deptName: string; positionLabel: string }
export interface AccountImportResult { created: number; skipped: number }

/** 职位/状态枚举映射（与后端 MemberEnums 一致） */
export const POSITION_LABELS: Record<string, string> = {
  DIRECTOR: '主任', SUB_DIRECTOR: '副主任', DEPT_HEAD: '部长', SUB_DEPT_HEAD: '副部长', STAFF: '干事',
}
export const STATUS_LABELS: Record<string, string> = {
  ACTIVE: '在职', ALUMNI: '往届', RESIGNED: '退部', EXPELLED: '开除', LEFT: '离职',
}
export const STATUS_COLOR: Record<string, string> = {
  ACTIVE: 'success', ALUMNI: 'blue', RESIGNED: 'orange', EXPELLED: 'red', LEFT: 'default',
}
export const POSITION_OPTIONS = Object.entries(POSITION_LABELS).map(([value, label]) => ({ value, label }))
export const STATUS_OPTIONS = Object.entries(STATUS_LABELS).map(([value, label]) => ({ value, label }))
export const POLITICAL_OPTIONS = ['共青团员', '中共预备党员', '中共党员', '群众', '无党派人士'].map((v) => ({ value: v, label: v }))

export const listMembers = (params: {
  sessionId?: number; deptId?: number; position?: string; status?: string; keyword?: string; page?: number; size?: number
}) => get<PageResult<MemberVO>>('/members', params)
export const getMember = (id: number) => get<MemberDetail>(`/members/${id}`)
export const createMember = (data: MemberSave) => post<number>('/members', data)
export const updateMember = (id: number, data: MemberSave) => put<void>(`/members/${id}`, data)
export const deleteMember = (id: number) => del<void>(`/members/${id}`)
export const batchDeleteMembers = (ids: number[]) => post<void>('/members/batch-delete', { ids })
export const importMembers = (formData: FormData) => post<MemberImportResult>('/members/import', formData)
export const getMemberStats = (sessionId?: number) => get<MemberStats>('/members/stats', { sessionId })
export const archiveSession = (sessionId: number) => post<{ count: number }>(`/members/${sessionId}/archive`)
export const listUnregisteredMembers = (sessionId?: number) =>
  get<UnregisteredMember[]>('/members/unregistered', { sessionId })
export const importAccounts = (data: { sessionId?: number; memberIds: number[]; roleCodes?: Record<number, string> }) =>
  post<AccountImportResult>('/members/import-accounts', data)

/** blob 下载（模板 / 导出） */
export const downloadMemberXlsx = (url: string, params?: Record<string, unknown>) =>
  http.get(url, { params, responseType: 'blob' }) as unknown as Promise<AxiosResponse<Blob>>
export const downloadImportTemplate = () => downloadMemberXlsx('/members/import/template')
export const downloadMemberExport = (params?: {
  sessionId?: number; deptId?: number; position?: string; status?: string; keyword?: string
}) => downloadMemberXlsx('/members/export', params)

export const listMemberSessions = () => get<MemberSessionVO[]>('/member-sessions')
export const createMemberSession = (data: { name: string; isCurrent?: number; sortOrder?: number; remark?: string }) =>
  post<number>('/member-sessions', data)
export const updateMemberSession = (id: number, data: { name: string; isCurrent?: number; sortOrder?: number; remark?: string }) =>
  put<void>(`/member-sessions/${id}`, data)
export const deleteMemberSession = (id: number) => del<void>(`/member-sessions/${id}`)
export const setCurrentMemberSession = (id: number) => post<void>(`/member-sessions/${id}/set-current`)
