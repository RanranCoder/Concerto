import { Navigate } from 'react-router-dom'
import { useAuthStore } from '@/stores/auth'

/**
 * 路由级角色守卫：当前用户 roleCode 不在 roles 白名单时跳转 /403。
 * 部长及以上统一白名单（后端 @PreAuthorize 同口径）。
 */
export const LEADER_ROLES = [
  'TEACHER',
  'DIRECTOR',
  'ORG_LEADER',
  'SECRETARY_LEADER',
  'MEDIA_LEADER',
  'TECH_LEADER',
]

/** 用户管理：按简报"主任+额外显示用户管理"，前端仅主任/指导老师可访问；后端 @PreAuthorize 保持部长及以上。 */
export const ADMIN_ROLES = ['TEACHER', 'DIRECTOR']

export default function RequireRole({ roles, children }: { roles: string[]; children: React.ReactNode }) {
  const role = useAuthStore((s) => s.user?.roleCode)
  if (!role || !roles.includes(role)) return <Navigate to="/403" replace />
  return <>{children}</>
}

/** 权限码守卫：当前用户未拥有任一指定权限码时跳转 /403。配合后端 hasAuthority 链路。 */
export function RequirePerm({ codes, children }: { codes: string[]; children: React.ReactNode }) {
  const perms = useAuthStore((s) => s.user?.permissions) ?? []
  if (!codes.some((c) => perms.includes(c))) return <Navigate to="/403" replace />
  return <>{children}</>
}

/** 读取当前用户权限码集合，返回 hasPerm(code) 判定函数（供菜单显隐）。 */
export function useHasPerm() {
  const perms = useAuthStore((s) => s.user?.permissions) ?? []
  return (code: string) => perms.includes(code)
}
