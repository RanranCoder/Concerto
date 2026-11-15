import { ACTIVITY_TYPE_OPTIONS } from '@/api/activityStatus'
import { useOrgStore } from '@/stores/org'
import { useAuthStore } from '@/stores/auth'

const PACK_BY_TYPE: Record<string, string> = {
  PARTY_LESSON: 'party', PARTY_DAY: 'party', DATE: 'youth', YOUTH_LESSON: 'youth', VOLUNTEER: 'volunteer',
}

export function useActivityTypes() {
  const packs = useOrgStore(s => s.enabledPacks)
  const permissions = useAuthStore(s => s.user?.permissions)
  return ACTIVITY_TYPE_OPTIONS.filter(option => {
    const pack = PACK_BY_TYPE[option.value]
    return !pack || (packs.includes(pack) && permissions?.includes(`${pack}:view`))
  })
}
