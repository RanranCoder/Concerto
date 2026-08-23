import { create } from 'zustand'
import { persist } from 'zustand/middleware'
import type { LoginResponse } from '@/api/auth'

interface AuthState {
  token: string | null
  user: LoginResponse['user'] | null
  setLogin: (data: LoginResponse) => void
  refreshUser: (user: LoginResponse['user']) => void
  logout: () => void
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      token: null,
      user: null,
      setLogin: (data) => set({ token: data.token, user: data.user }),
      refreshUser: (user) => set({ user }),
      logout: () => set({ token: null, user: null }),
    }),
    {
      name: 'pams_token',
      version: 1,
      // RBAC 升级后老缓存的 user 没有 permissions 字段 → 补空数组避免菜单塌缩
      migrate: (state) => {
        if (!state) return state
        const s = state as { user?: LoginResponse['user'] | null }
        return { ...s, user: s.user ? { ...s.user, permissions: s.user.permissions ?? [] } : null }
      },
    },
  ),
)
