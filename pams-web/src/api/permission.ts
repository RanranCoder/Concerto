import { get, put, post } from './http'

// ==================== F07 权限管理 ====================

export interface PermissionNode {
  id: number
  code: string
  name: string
  module: string
}

export interface PermissionModuleNode {
  module: string
  children: PermissionNode[]
}

export interface RolePermissionVO {
  role: string
  permissions: string[]
}

export interface RolePermissionsResult {
  roles: RolePermissionVO[]
  tree: PermissionModuleNode[]
}

export const getRolePermissions = () => get<RolePermissionsResult>('/permissions')
export const saveRolePermissions = (role: string, permissionCodes: string[]) =>
  put<void>(`/permissions/roles/${role}`, permissionCodes)
export const restoreDefaultPermissions = () => post<void>('/permissions/restore-default')

// ==================== F07 密码修改 ====================

export const changePassword = (oldPassword: string, newPassword: string) =>
  post<void>('/users/change-password', { oldPassword, newPassword })
