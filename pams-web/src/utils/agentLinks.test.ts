import { describe, expect, it } from 'vitest'
import { agentBusinessLink } from './agentLinks'
describe('Agent business navigation', () => {
  it.each([
    ['/activity/4', '/activities/4'], ['/activity/4/tasks', '/activities/4/gantt'],
    ['/activity/4/seat', '/activities/4?tab=seat'], ['/activity/4/signin', '/activities/4?tab=signin'],
    ['/member?id=5', '/members/5'], ['/party/member?id=5', '/party/members/5'],
    ['/content/article?id=7', '/content/articles?id=7'], ['/archive/material?id=7', '/archive/materials?id=7'],
    ['/archive/credit?activityId=4', '/archive/credits?activityId=4'],
    ['/archive/announcement?id=7', '/archive/announcements?id=7'], ['/notification', '/notifications'],
    ['/activities/4?tab=plan', '/activities/4?tab=plan'],
  ])('maps %s to its real route', (input, output) => expect(agentBusinessLink(input)).toBe(output))
  it('keeps absent navigation absent', () => expect(agentBusinessLink(null)).toBeNull())
})
