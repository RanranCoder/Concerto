import { get, post, put, del } from './http'
import type { PageResult } from './types'

// ===================== 常量 =====================

/** 党员发展阶段（与后端 PartyStageType 一致，字符串存储） */
export const PARTY_STAGES = [
  { value: 'APPLICANT', label: '入党申请人' },
  { value: 'ACTIVE', label: '入党积极分子' },
  { value: 'DEVELOPMENT', label: '重点发展对象' },
  { value: 'PROBATIONARY', label: '预备党员' },
  { value: 'FULL', label: '正式党员' },
] as const

export const STAGE_LABEL_MAP: Record<string, string> = Object.fromEntries(
  PARTY_STAGES.map((s) => [s.value, s.label]),
)

/** 政治面貌红系配色（与 liquid glass 红系一致） */
export const STAGE_COLOR_MAP: Record<string, string> = {
  入党申请人: '#FA8C16',
  入党积极分子: '#F5222D',
  重点发展对象: '#D4380D',
  预备党员: '#CF1322',
  正式党员: '#DE2910',
}

/** 名单类型：推优/通过/汇总/发展对象/转移 */
export const ROSTER_TYPES = [
  { value: 'RECOMMEND', label: '推优' },
  { value: 'PASSED', label: '通过' },
  { value: 'SUMMARY', label: '汇总' },
  { value: 'DEVELOPMENT', label: '发展对象' },
  { value: 'TRANSFER', label: '转移' },
] as const

export const ROSTER_TYPE_MAP: Record<string, string> = Object.fromEntries(
  ROSTER_TYPES.map((t) => [t.value, t.label]),
)

export const GENDER_OPTIONS = [
  { value: '男', label: '男' },
  { value: '女', label: '女' },
]

export const POLITICAL_OPTIONS = [
  { value: '共青团员', label: '共青团员' },
  { value: '入党申请人', label: '入党申请人' },
  { value: '入党积极分子', label: '入党积极分子' },
  { value: '重点发展对象', label: '重点发展对象' },
  { value: '预备党员', label: '预备党员' },
  { value: '正式党员', label: '正式党员' },
  { value: '群众', label: '群众' },
]

// ===================== 成员 =====================

/** 成员 VO。部长及以上才返回 idCard / phone / homeAddress，干事（STAFF）响应不含这三项。 */
export interface PartyMemberVO {
  id: number
  name: string
  gender: string
  nation: string
  idCard?: string
  birthDate: string | null
  nativePlace: string
  education: string
  phone?: string
  homeAddress?: string
  className: string
  college: string
  branchName: string
  politicalStatus: string
  studentNo: string
  remark: string
  createdAt: string | null
  updatedAt: string | null
}

export interface PartyMemberSave {
  name: string
  gender?: string | null
  nation?: string | null
  idCard?: string | null
  birthDate?: string | null
  nativePlace?: string | null
  education?: string | null
  phone?: string | null
  homeAddress?: string | null
  className?: string | null
  college?: string | null
  branchName?: string | null
  politicalStatus?: string | null
  studentNo?: string | null
  remark?: string | null
}

/** 流转历史记录（party_stage） */
export interface PartyStageVO {
  id: number
  memberId: number
  stage: string
  issueNo: string | null
  status: string | null
  startDate: string | null
  endDate: string | null
  remark: string | null
  createdAt: string | null
}

// ===================== 名单 =====================

export interface PartyRosterVO {
  id: number
  rosterType: string
  issueNo: string | null
  name: string
  gender: string | null
  studentNo: string | null
  className: string | null
  branchName: string | null
  remark: string | null
  createdAt: string | null
}

export interface PartyRosterSave {
  rosterType: string
  issueNo?: string | null
  name: string
  gender?: string | null
  studentNo?: string | null
  className?: string | null
  branchName?: string | null
  remark?: string | null
}

// ===================== 函调 =====================

export interface PartyInvestigationVO {
  id: number
  memberId: number
  fatherName: string | null
  fatherBranch: string | null
  fatherBranchAddr: string | null
  motherName: string | null
  motherBranch: string | null
  motherBranchAddr: string | null
  relativeName: string | null
  relativeBranch: string | null
  relativeBranchAddr: string | null
  createdAt: string | null
}

export interface PartyInvestigationSave {
  memberId: number
  fatherName?: string | null
  fatherBranch?: string | null
  fatherBranchAddr?: string | null
  motherName?: string | null
  motherBranch?: string | null
  motherBranchAddr?: string | null
  relativeName?: string | null
  relativeBranch?: string | null
  relativeBranchAddr?: string | null
}

// ===================== 登记 =====================

export interface PartyRegisterVO {
  id: number
  memberId: number
  college: string | null
  branch: string | null
  className: string | null
  name: string | null
  gender: string | null
  birthDate: string | null
  nativePlace: string | null
  nation: string | null
  idCard: string | null
  phone: string | null
  homeAddress: string | null
  applyDate: string | null
  education: string | null
  talkPerson: string | null
  conditionNote: string | null
  remark: string | null
  createdAt: string | null
}

export interface PartyRegisterSave {
  memberId: number
  college?: string | null
  branch?: string | null
  className?: string | null
  name?: string | null
  gender?: string | null
  birthDate?: string | null
  nativePlace?: string | null
  nation?: string | null
  idCard?: string | null
  phone?: string | null
  homeAddress?: string | null
  applyDate?: string | null
  education?: string | null
  talkPerson?: string | null
  conditionNote?: string | null
  remark?: string | null
}

// ===================== 转移 =====================

export interface PartyTransferVO {
  id: number
  memberId: number
  className: string | null
  name: string | null
  gender: string | null
  nation: string | null
  /** 1 预备 / 0 正式 */
  isProbationary: number | null
  idCard: string | null
  receiveOrg: string | null
  phone: string | null
  wechat: string | null
  /** 1 线上 / 0 线下 */
  isOnline: number | null
  signDate: string | null
  remark: string | null
  createdAt: string | null
}

export interface PartyTransferSave {
  memberId: number
  className?: string | null
  name?: string | null
  gender?: string | null
  nation?: string | null
  isProbationary?: number | null
  idCard?: string | null
  receiveOrg?: string | null
  phone?: string | null
  wechat?: string | null
  isOnline?: number | null
  signDate?: string | null
  remark?: string | null
}

// ===================== API =====================

/** 成员分页。干事（STAFF）响应不返回 idCard/phone/homeAddress，前端按 roleLevel 控制敏感列/表单。 */
export const listPartyMembers = (params: { keyword?: string; stage?: string; page?: number; size?: number }) =>
  get<PageResult<PartyMemberVO>>('/party/members', params)

/** 成员详情（部长及以上返回敏感字段） */
export const getPartyMember = (id: number) => get<PartyMemberVO>(`/party/members/${id}`)

export const createPartyMember = (data: PartyMemberSave) => post<number>('/party/members', data)
export const updatePartyMember = (id: number, data: PartyMemberSave) => put<void>(`/party/members/${id}`, data)
export const deletePartyMember = (id: number) => del<void>(`/party/members/${id}`)

/** 阶段流转：追加 party_stage 记录并更新政治面貌为对应中文身份。 */
export const changeStage = (
  id: number,
  data: { stage: string; issueNo?: string; startDate?: string; endDate?: string; remark?: string },
) => put<void>(`/party/members/${id}/stage`, data)

/** 流转历史。注意：挂在成员控制器下（Task 18 接口契约），路径为 /api/party/members/stages 而非 /api/party/stages。 */
export const listStages = (memberId: number) => get<PartyStageVO[]>('/party/members/stages', { memberId })

/** 名单（推优/通过/汇总/发展对象/转移） */
export const listRosters = (params: { type?: string; issueNo?: string }) =>
  get<PartyRosterVO[]>('/party/rosters', params)
export const createRoster = (data: PartyRosterSave) => post<number>('/party/rosters', data)
export const updateRoster = (id: number, data: PartyRosterSave) => put<void>(`/party/rosters/${id}`, data)
export const deleteRoster = (id: number) => del<void>(`/party/rosters/${id}`)

/** 函调记录 */
export const listInvestigations = (memberId: number) =>
  get<PartyInvestigationVO[]>('/party/investigations', { memberId })
export const createInvestigation = (data: PartyInvestigationSave) =>
  post<number>('/party/investigations', data)
export const updateInvestigation = (id: number, data: PartyInvestigationSave) =>
  put<void>(`/party/investigations/${id}`, data)
export const deleteInvestigation = (id: number) => del<void>(`/party/investigations/${id}`)

/** 登记记录 */
export const listRegisters = (memberId: number) => get<PartyRegisterVO[]>('/party/registers', { memberId })
export const createRegister = (data: PartyRegisterSave) => post<number>('/party/registers', data)
export const updateRegister = (id: number, data: PartyRegisterSave) =>
  put<void>(`/party/registers/${id}`, data)
export const deleteRegister = (id: number) => del<void>(`/party/registers/${id}`)

/** 转移记录 */
export const listTransfers = (memberId: number) => get<PartyTransferVO[]>('/party/transfers', { memberId })
export const createTransfer = (data: PartyTransferSave) => post<number>('/party/transfers', data)
export const updateTransfer = (id: number, data: PartyTransferSave) =>
  put<void>(`/party/transfers/${id}`, data)
export const deleteTransfer = (id: number) => del<void>(`/party/transfers/${id}`)
