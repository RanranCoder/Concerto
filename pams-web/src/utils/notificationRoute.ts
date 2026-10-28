export function notificationRoute(n: { entityType: string | null; entityId: number | null; activityId?: number | null }): string | null {
  if (!n.entityId || !n.entityType) return null
  if (n.activityId) {
    const tab = ({ TASK: 'gantt', PLAN: 'plan', SIGNIN: 'signin', ARTICLE: 'articles' } as Record<string, string>)[n.entityType]
    if (tab) return `/activities/${n.activityId}?tab=${tab}`
  }
  return ({ ACTIVITY: `/activities/${n.entityId}`, ARTICLE: '/content/articles', NEWS: '/content/news',
    ANNOUNCEMENT: '/archive/announcements', SCHEDULE: '/routine/activity-schedules', ATTENDANCE: '/routine/attendance' } as Record<string, string>)[n.entityType] ?? null
}
