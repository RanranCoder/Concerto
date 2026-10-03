import { create } from 'zustand'
import { persist } from 'zustand/middleware'
import type { ThemeMode } from '@/styles/tokens'

export type { ThemeMode }

interface ThemeState {
  mode: ThemeMode
  toggle: () => void
  setMode: (m: ThemeMode) => void
}

const prefersDark = () =>
  typeof window !== 'undefined' && window.matchMedia('(prefers-color-scheme: dark)').matches

export const useThemeStore = create<ThemeState>()(
  persist(
    (set, get) => ({
      mode: prefersDark() ? 'dark' : 'light',
      setMode: (mode) => {
        document.documentElement.dataset.theme = mode
        set({ mode })
      },
      toggle: () => get().setMode(get().mode === 'dark' ? 'light' : 'dark'),
    }),
    { name: 'pams_theme' },
  ),
)
