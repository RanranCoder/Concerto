import { create } from 'zustand'
import { getCurrentOrg } from '@/api/org'
import { lightTokens, normalizePrimaryColor } from '@/styles/tokens'
import { useAuthStore } from '@/stores/auth'
import { parsePacks } from '@/utils/extensionPacks'

/**
 * 默认主题色 —— 组织未配置 theme_json 时回退。
 * 直接取 token 里的 brand，避免「默认主色」在 org.ts 和 tokens.ts 各写一份。
 */
export const DEFAULT_PRIMARY_COLOR = lightTokens.brand

interface OrgState {
  orgName: string | null
  primaryColor: string
  enabledPacks: string[]
  /** 术语表：key → 展示名 */
  terminology: Record<string, string>
  loaded: boolean
  loadFailed: boolean
  load: () => Promise<void>
  applyPacks: (packs: string[]) => void
  reset: () => void
}

let requestVersion = 0

function parsePrimaryColor(themeJson: string | null): string {
  if (!themeJson) return DEFAULT_PRIMARY_COLOR
  try {
    const obj = JSON.parse(themeJson) as { primaryColor?: unknown }
    return typeof obj.primaryColor === 'string' && obj.primaryColor.trim()
      ? normalizePrimaryColor(obj.primaryColor)
      : DEFAULT_PRIMARY_COLOR
  } catch {
    return DEFAULT_PRIMARY_COLOR
  }
}

function parseTerminology(json: string | null): Record<string, string> {
  if (!json || !json.trim()) return {}
  try {
    const o = JSON.parse(json) as Record<string, unknown>
    const out: Record<string, string> = {}
    Object.entries(o).forEach(([k, v]) => { if (typeof v === 'string') out[k] = v })
    return out
  } catch {
    return {}
  }
}

export const useOrgStore = create<OrgState>((set) => ({
  orgName: null,
  primaryColor: DEFAULT_PRIMARY_COLOR,
  enabledPacks: [],
  terminology: {},
  loaded: false,
  loadFailed: false,
  load: async () => {
    const version = ++requestVersion
    const token = useAuthStore.getState().token
    const current = () => version === requestVersion && token === useAuthStore.getState().token
    try {
      const org = await getCurrentOrg()
      if (!current()) return
      set({
        orgName: org.name,
        primaryColor: parsePrimaryColor(org.themeJson),
        enabledPacks: parsePacks(org.enabledPacks),
        terminology: parseTerminology(org.terminologyJson),
        loaded: true,
        loadFailed: false,
      })
    } catch {
      if (current()) set({ loaded: false, loadFailed: true, enabledPacks: [] })
    }
  },
  applyPacks: (enabledPacks) => {
    requestVersion++
    set({ enabledPacks, loaded: true, loadFailed: false })
  },
  reset: () => {
    requestVersion++
    set({ orgName: null, primaryColor: DEFAULT_PRIMARY_COLOR, enabledPacks: [], terminology: {}, loaded: false, loadFailed: false })
  },
}))

useAuthStore.subscribe((state, previous) => {
  if (state.token !== previous.token) useOrgStore.getState().reset()
})

/** 领域包是否启用（组件内用 selector 取 enabledPacks 后调用）。 */
export function isPackEnabled(packs: string[], pack: string): boolean {
  return packs.includes(pack)
}

/** 术语：优先组织自定义，回退默认文案。 */
export function term(terminology: Record<string, string>, key: string, fallback: string): string {
  return terminology[key] ?? fallback
}
