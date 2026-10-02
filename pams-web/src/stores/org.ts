import { create } from 'zustand'
import { getCurrentOrg } from '@/api/org'

/** 默认主题色（党建红）——组织未配置主题时回退。 */
export const DEFAULT_PRIMARY_COLOR = '#DE2910'

interface OrgState {
  orgName: string | null
  primaryColor: string
  loaded: boolean
  /** 拉取当前组织信息（品牌/主题）。失败保持默认，不阻塞。 */
  load: () => Promise<void>
  reset: () => void
}

function parsePrimaryColor(themeJson: string | null): string {
  if (!themeJson) return DEFAULT_PRIMARY_COLOR
  try {
    const obj = JSON.parse(themeJson) as { primaryColor?: unknown }
    return typeof obj.primaryColor === 'string' && obj.primaryColor.trim()
      ? obj.primaryColor.trim()
      : DEFAULT_PRIMARY_COLOR
  } catch {
    return DEFAULT_PRIMARY_COLOR
  }
}

export const useOrgStore = create<OrgState>((set) => ({
  orgName: null,
  primaryColor: DEFAULT_PRIMARY_COLOR,
  loaded: false,
  load: async () => {
    try {
      const org = await getCurrentOrg()
      set({ orgName: org.name, primaryColor: parsePrimaryColor(org.themeJson), loaded: true })
    } catch {
      /* 未登录或接口失败：保持默认主题 */
    }
  },
  reset: () => set({ orgName: null, primaryColor: DEFAULT_PRIMARY_COLOR, loaded: false }),
}))
