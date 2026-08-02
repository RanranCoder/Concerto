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
