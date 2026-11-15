export const EXTENSION_PACKS = [
  {
    code: 'party',
    entry: '/party/members',
    workspace: '党务台账',
    name: '党建工作包',
    description: '维护党员档案、发展阶段与党务记录。',
    features: ['党员发展台账', '党务名册', '组织关系与函调记录'],
    available: true,
  },
  {
    code: 'youth',
    entry: '/youth',
    workspace: '团务工作',
    name: '团建工作包',
    description: '管理团员与团支部，开展组织生活和推优入党。',
    features: ['团员与支部', '组织生活', '教育评议', '推优入党'],
    available: true,
  },
  {
    code: 'volunteer',
    entry: '/volunteer',
    workspace: '志愿服务',
    name: '志愿服务包',
    description: '从活动签到核定服务时长，审核与导出服务记录。',
    features: ['志愿活动', '服务时长核定', '服务记录审核与导出'],
    available: true,
  },
] as const

export type ExtensionPack = typeof EXTENSION_PACKS[number]
export const EXTENSIONS_PATH = '/admin/settings?section=extensions'

export function parsePacks(json: string | null): string[] {
  try {
    const codes: unknown = JSON.parse(json ?? '[]')
    if (!Array.isArray(codes)) return []
    return EXTENSION_PACKS.filter(pack => pack.available && codes.includes(pack.code)).map(pack => pack.code)
  } catch {
    return []
  }
}
