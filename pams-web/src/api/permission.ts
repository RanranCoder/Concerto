import { get, post, put, del } from './http'
export interface Grant {
  permission: string
  scope: 'ORG'
  packVersion?: number
}
export interface CatalogPermission {
  id: number
  code: string
  name: string
  module: string
  supportedScopes: string[]
  packVersion?: number
  grantable: boolean
  reason: string
  reasonCode?: string
  groupName?: string
  description?: string
  dependencies?: string[]
  risk?: boolean
}
export interface ManagedRole {
  id: number
  code: string
  name: string
  description?: string
  status: 'DRAFT' | 'ACTIVE' | 'DISABLED' | 'ARCHIVED'
  version: number
  userCount?: number
  permissionCount?: number
  draftRevisionId?: number
  updatedAt?: string
  updatedByName?: string
  sourceKind?: string
}
export interface RoleDetail {
  role: {
    id: number
    name: string
    status: ManagedRole['status']
    config_version: number
  }
  published: Grant[]
  draft: { id: number; version: number; grants: string } | null
  users: { id: number; realName: string; username: string }[]
  history: { id: number; grants: string; publishedAt: string }[]
  impact?: {
    userCount: number
    activeUserCount: number
    positions: { id: number; name: string }[]
    invitations: number
  }
  actions?: { canEdit: boolean; canGrant: boolean; reason: string }
  migrationReview?: { status: string }[]
}
export interface ChangePreview {
  id: string
  action: string
  targetId: number
  details: {
    reason?: string
    before?: Grant[]
    grants?: Grant[]
    oldRoleId?: number
    roleId?: number
    impact?: RoleDetail['impact']
    active?: boolean
    grantable?: string[]
    expiresAt?: string
    delegationBefore?: {
      active: boolean
      grantable: string[]
      expiresAt?: string
    }
  }
  expiresInSeconds: number
  expiresAt?: string
}
export const managedRoles = () => get<ManagedRole[]>('/roles')
export const roleTemplates = () =>
  get<{ code: string; name: string }[]>('/roles/templates')
export const updateRoleMetadata = (
  id: number,
  version: number,
  name: string,
  description?: string,
) => put<void>(`/roles/${id}`, { version, name, description })
export const roleCatalog = () =>
  get<CatalogPermission[]>('/permissions/catalog')
export const roleDetail = (id: number) => get<RoleDetail>(`/roles/${id}`)
export const createRole = (
  name: string,
  description?: string,
  sourceRoleId?: number,
  templateCodes?: string[],
) => post<number>('/roles', { name, description, sourceRoleId, templateCodes })
export const saveRoleDraft = (
  id: number,
  draftVersion: number,
  grants: Grant[],
  before: Grant[],
) =>
  put<number>(`/roles/${id}/draft`, {
    draftVersion,
    permissionChanges: [
      ...before
        .filter(
          (old) => !grants.some((next) => next.permission === old.permission),
        )
        .map((g) => ({ operation: 'REVOKE', permission: g.permission })),
      ...grants
        .filter(
          (next) =>
            JSON.stringify(
              before.find((old) => old.permission === next.permission),
            ) !== JSON.stringify(next),
        )
        .map((g) => ({
          operation: 'SET',
          permission: g.permission,
          scope: g.scope,
        })),
    ],
  })
export const previewRole = (
  id: number,
  action: string,
  version: number,
  reason?: string,
) =>
  post<ChangePreview>(`/roles/${id}/change-previews`, {
    action,
    version,
    reason,
  })
export const applyAuthorizationChange = (id: string) =>
  post<{ status: string }>(`/orgs/current/authorization-changes/${id}/apply`)
export const previewRoleAssignment = (
  userId: number,
  roleId: number,
  reason?: string,
) =>
  post<ChangePreview>('/users/role-change-previews', { userId, roleId, reason })
export const assignableRoles = () => get<ManagedRole[]>('/roles/assignable')
export const getAdministrator = (id: number) =>
  get<{
    active: boolean
    grantable: string[]
    expiresAt?: string
    version: number
  }>(`/orgs/current/role-delegations/${id}`)
export interface GovernanceLog {
  id: number
  actorId: number
  action: string
  targetId: number
  createdAt: string
}
export const governanceLogs = () =>
  get<GovernanceLog[]>('/orgs/current/audit-logs')
export interface EffectivePermissions {
  businessPermissions: string[]
  managementCapabilities: string[]
  roleId: number
  managementSource: 'OWNER' | 'DELEGATED' | 'NONE'
  roleName?: string
  configured?: { permission: string; effective: boolean; reason: string }[]
}
export const effectivePermissions = (id: number) =>
  get<EffectivePermissions>(`/users/${id}/effective-permissions`)
export const changePassword = (oldPassword: string, newPassword: string) =>
  post<void>('/users/change-password', { oldPassword, newPassword })

export const authorizationChange = (id: string) =>
  get<{ status: string }>(`/orgs/current/authorization-changes/${id}`)
export const previewAdministrator = (
  id: number,
  active: boolean,
  grantable: string[],
  expiresAt?: string,
  version = 0,
  reason?: string,
) =>
  post<ChangePreview>(`/orgs/current/role-delegations/${id}/change-previews`, {
    active,
    grantable,
    expiresAt,
    version,
    reason,
  })
export interface MigrationReview {
  role_id: number
  roleName?: string
  rolename?: string
  status: string
  before_json: string
  after_json: string
  review_required: number
}
export const migrationReviews = () =>
  get<MigrationReview[]>('/permissions/migration-reviews')
export interface DelegationInfo {
  active: boolean
  status?: string
  version: number
  expiresAt?: string
  updatedAt?: string
  updatedByName?: string
}
export interface ManagementSummary {
  ownDelegation?: DelegationInfo
  isOwner: boolean
  capabilities: string[]
  grantable: string[]
  administrators: {
    id: number
    realName: string
    status: string
    expiresAt?: string
    version?: number
    updatedAt?: string
    updatedByName?: string
  }[]
}
export const managementSummary = () =>
  get<ManagementSummary>('/orgs/current/management-summary')
export interface AuditEntry extends GovernanceLog {
  outcome?: string
  reason?: string
  changeId?: string
  actorName?: string
  actorname?: string
  targetName?: string
  targetname?: string
  details: string
}
export const auditPage = (
  page: number,
  size: number,
  keyword: string,
  action: string,
  from = '',
  to = '',
) =>
  get<{ items: AuditEntry[]; total: number }>('/orgs/current/audit-log-page', {
    page,
    size,
    keyword,
    action,
    from,
    to,
  })

export interface RoleMember {
  id: number
  realName: string
  username: string
  status: number
  deptName?: string
  managementSource: 'OWNER' | 'DELEGATED' | 'NONE'
}
export interface RoleHistory {
  id: number
  grants: string
  publishedAt: string
  actorName?: string
  reason?: string
  catalogVersion?: number
}
export interface RoleReference {
  id: number
  kind: 'USER' | 'POSITION' | 'INVITATION'
  name: string
  detail: string
  blocking: number
}
export const roleMembers = (id: number, page = 1) =>
  get<{ items: RoleMember[]; total: number }>(`/roles/${id}/members`, {
    page,
    size: 20,
  })
export const roleHistory = (id: number, page = 1) =>
  get<{ items: RoleHistory[]; total: number }>(`/roles/${id}/history`, {
    page,
    size: 20,
  })
export const roleReferences = (id: number, page = 1) =>
  get<{ items: RoleReference[]; total: number; blockingTotal: number }>(
    `/roles/${id}/references`,
    {
      page,
      size: 20,
    },
  )
export const managedRolePage = (
  page: number,
  keyword: string,
  status: string,
  drafts: boolean,
) =>
  get<{ items: ManagedRole[]; total: number }>('/roles/page', {
    page,
    size: 20,
    keyword,
    status,
    drafts,
  })
export const discardRoleDraft = (
  id: number,
  version: number,
  draftVersion: number,
) => del<void>(`/roles/${id}/draft`, { params: { version, draftVersion } })
export const deleteUnpublishedRole = (
  id: number,
  version: number,
  draftVersion: number,
) => del<void>(`/roles/${id}`, { params: { version, draftVersion } })
