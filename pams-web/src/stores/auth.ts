import { create } from 'zustand'
import { persist } from 'zustand/middleware'
import type { LoginResponse } from '@/api/auth'

interface AuthState {
  token: string | null
  user: LoginResponse['user'] | null
  setLogin: (data: LoginResponse) => void
  logout: () => void
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      token: null,
      user: null,
      setLogin: (data) => set({ token: data.token, user: data.user }),
      logout: () => set({ token: null, user: null }),
    }),
    { name: 'pams_token' },
  ),
)
