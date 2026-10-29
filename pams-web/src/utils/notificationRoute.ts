import { useAuthStore } from '@/stores/auth'

export function notificationRoute(n: { entityType: string | null; entityId: number | null; activityId?: number | null }): string | null {
  if (!n.entityId || !n.entityType) return null
  if (n.entityType === 'MAINTENANCE') return '/notifications?view=operations'
  if (n.entityType === 'ACCOUNT_IMPORT') return useAuthStore.getState().user?.permissions?.includes('member:view') ? '/members' : null
  if (['QUALITY', 'QUALITY_DELETE'].includes(n.entityType)) return '/archive/credits'
  if (n.entityType === 'MEMBER_IMPORT') return '/members'
  if (n.entityType === 'SCHEDULE_IMPORT') return '/routine/free-schedules'
  if (['PARTY_STAGE', 'PARTY_RECORD', 'PARTY_LETTER', 'PARTY_IMPORT'].includes(n.entityType)) return '/party/members'
  if (n.entityType === 'ANNOUNCEMENT') return `/archive/announcements?id=${n.entityId}`
  if (n.entityType === 'PENDING_TASKS') return '/notifications?view=pending'
  if (n.entityType === 'SCHEDULE' && !useAuthStore.getState().user?.permissions?.includes('schedule:view')) return '/routine/my-schedules'
  if (n.activityId) {
    const tab = ({ TASK: 'gantt', PLAN: 'plan', SIGNIN: 'signin', ARTICLE: 'articles' } as Record<string, string>)[n.entityType]
    if (tab) return `/activities/${n.activityId}?tab=${tab}`
  }
  return ({ ACTIVITY: `/activities/${n.entityId}`, ARTICLE: '/content/articles', NEWS: '/content/news',
    ANNOUNCEMENT: '/archive/announcements', SCHEDULE: '/routine/activity-schedules', ATTENDANCE: '/routine/attendance' } as Record<string, string>)[n.entityType] ?? null
}
