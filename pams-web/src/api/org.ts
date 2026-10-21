import { get, post, put, del } from './http'

export interface OrgInfo {
  id: number
  name: string
  type: string
  shortName: string | null
  logoUrl: string | null
  themeJson: string | null
  enabledPacks: string | null
  terminologyJson: string | null
}

export interface OrgUnitNode {
  id: number
  parentId: number | null
  name: string
  unitType: string
  code: string | null
  level: number | null
  path: string | null
  leaderUserId: number | null
  sortOrder: number | null
  children: OrgUnitNode[]
}

export interface OrgRegisterPayload {
  org: { name: string; type: string; shortName?: string; themeColor?: string }
  admin: { username: string; password: string; realName?: string; phone?: string }
  units: OrgUnitInput[]
  enabledPacks: string[]
}

export interface OrgUnitInput {
  name: string
  unitType?: string
  leaderUserId?: number | null
  children?: OrgUnitInput[]
}

export interface UnitSavePayload {
  name?: string
  unitType?: string
  parentId?: number | null
  leaderUserId?: number | null
  sortOrder?: number
}

export interface OrgInvitation {
  id: number
  orgId: number
  code: string
  roleCode: string
  orgUnitId: number | null
  expireAt: string | null
}

/** Complete minimal account directory, scoped to the managed organization. */
export interface OrgAccount {
  id: number
  username: string
  realName: string | null
  deptId: number | null
  roleName: string | null
  status: number | null
}

export interface OrgStructureData {
  units: OrgUnitNode[]
  accounts: OrgAccount[]
}

export const getOrgStructure = () => get<OrgStructureData>('/orgs/current/structure')

/** 注册组织（免登录） */
export const registerOrg = (data: OrgRegisterPayload) =>
  post<{ orgId: number; orgName: string; adminUsername: string }>('/orgs/register', data)

/** 组织公开列表项（免登录，仅非敏感字段） */
export interface PublicOrg {
  id: number
  name: string
  type: string
  shortName: string | null
  logoUrl: string | null
  themeJson: string | null
}

/** 组织公开列表（免登录）：登录页组织选择与品牌 */
export const listPublicOrgs = () => get<PublicOrg[]>('/orgs/public')

export const getCurrentOrg = () => get<OrgInfo>('/orgs/current')

export const updateOrg = (data: {
  name?: string
  shortName?: string
  logoUrl?: string
  themeColor?: string
  enabledPacks?: string[]
}) => put<void>('/orgs/current', data)

/** 架构树 */
export const listUnits = () => get<OrgUnitNode[]>('/orgs/current/units')

export const createUnit = (data: UnitSavePayload) => post<number>('/orgs/current/units', data)

export const updateUnit = (id: number, data: UnitSavePayload) =>
  put<void>(`/orgs/current/units/${id}`, data)

export const deleteUnit = (id: number) => del<void>(`/orgs/current/units/${id}`)

export const createInvitation = (data: { roleCode?: string; orgUnitId?: number; ttlHours?: number }) =>
  post<OrgInvitation>('/orgs/current/invitations', data)
