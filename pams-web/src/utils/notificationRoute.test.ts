import { expect, it } from 'vitest'
import { notificationRoute } from './notificationRoute'
it('opens the plan tab of its activity and denies unknown destinations', () => {
  expect(notificationRoute({ entityType: 'PLAN', entityId: 15, activityId: 3 })).toBe('/activities/3?tab=plan')
  expect(notificationRoute({ entityType: 'https://example.com', entityId: 15 })).toBeNull()
  expect(notificationRoute({ entityType: null, entityId: null })).toBeNull()
  expect(notificationRoute({ entityType: 'PENDING_TASKS', entityId: 1 })).toBe('/notifications?view=pending')
  expect(notificationRoute({ entityType: 'SCHEDULE', entityId: 1 })).toBe('/routine/my-schedules')
  expect(notificationRoute({ entityType: 'ANNOUNCEMENT', entityId: 9 })).toBe('/archive/announcements?id=9')
  expect(notificationRoute({ entityType: 'QUALITY', entityId: 1 })).toBe('/archive/credits')
  expect(notificationRoute({ entityType: 'PARTY_STAGE', entityId: 1 })).toBe('/party/members')
  expect(notificationRoute({ entityType: 'SCHEDULE_IMPORT', entityId: 1 })).toBe('/routine/free-schedules')
  expect(notificationRoute({ entityType: 'MAINTENANCE', entityId: 1 })).toBe('/notifications?view=operations')
})
