import { create } from 'zustand'
import { persist } from 'zustand/middleware'

export type ListDensity = 'standard' | 'compact'
export const usePreferencesStore = create<{
  userListDensity: ListDensity
  setUserListDensity: (density: ListDensity) => void
}>()(persist(set => ({
  userListDensity: 'standard',
  setUserListDensity: userListDensity => set({userListDensity}),
}), {name: 'concerto_preferences'}))
