import { create } from 'zustand'
import { getCurrentOrg } from '@/api/org'
import { lightTokens, normalizePrimaryColor } from '@/styles/tokens'

/**
 * 默认主题色 —— 组织未配置 theme_json 时回退。
 * 直接取 token 里的 brand，避免「默认主色」在 org.ts 和 tokens.ts 各写一份。
 */
export const DEFAULT_PRIMARY_COLOR = lightTokens.brand

/** 已知领域包（A2）：组织未显式配置时视为全部启用（兼容默认组织）。 */
export const ALL_PACKS = ['party', 'volunteer', 'club']

interface OrgState {
  orgName: string | null
  primaryColor: string
  /** 启用的领域包；未配置 = ALL_PACKS */
  enabledPacks: string[]
  /** 术语表：key → 展示名 */
  terminology: Record<string, string>
  loaded: boolean
  load: () => Promise<void>
  reset: () => void
}

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

function parsePacks(json: string | null): string[] {
  if (!json || !json.trim()) return ALL_PACKS
  try {
    const a = JSON.parse(json) as unknown
    return Array.isArray(a) ? a.map(String) : ALL_PACKS
  } catch {
    return ALL_PACKS
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
  enabledPacks: ALL_PACKS,
  terminology: {},
  loaded: false,
  load: async () => {
    try {
      const org = await getCurrentOrg()
      set({
        orgName: org.name,
        primaryColor: parsePrimaryColor(org.themeJson),
        enabledPacks: parsePacks(org.enabledPacks),
        terminology: parseTerminology(org.terminologyJson),
        loaded: true,
      })
    } catch {
      /* 未登录或接口失败：保持默认 */
    }
  },
  reset: () => set({
    orgName: null,
    primaryColor: DEFAULT_PRIMARY_COLOR,
    enabledPacks: ALL_PACKS,
    terminology: {},
    loaded: false,
  }),
}))

/** 领域包是否启用（组件内用 selector 取 enabledPacks 后调用）。 */
export function isPackEnabled(packs: string[], pack: string): boolean {
  return packs.includes(pack)
}

/** 术语：优先组织自定义，回退默认文案。 */
export function term(terminology: Record<string, string>, key: string, fallback: string): string {
  return terminology[key] ?? fallback
}
