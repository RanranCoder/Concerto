import { expect, it } from 'vitest'
import { notificationRoute } from './notificationRoute'
it('opens the plan tab of its activity and denies unknown destinations', () => {
  expect(notificationRoute({ entityType: 'PLAN', entityId: 15, activityId: 3 })).toBe('/activities/3?tab=plan')
  expect(notificationRoute({ entityType: 'https://example.com', entityId: 15 })).toBeNull()
  expect(notificationRoute({ entityType: null, entityId: null })).toBeNull()
})
