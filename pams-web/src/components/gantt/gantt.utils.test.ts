// pams-web/src/components/gantt/gantt.utils.test.ts
import { describe, it, expect } from 'vitest'
import { dayRange, buildDeps, isScheduled, isOverdue, hasDependencyConflict, taskStatus, wouldCycle } from './gantt.utils'

describe('gantt.utils', () => {
  it('dayRange returns inclusive length', () => {
    expect(dayRange('2026-03-01', '2026-03-04')).toBe(4)
  })

  it('buildDeps maps dependsOn to edges', () => {
    const tasks = [
      { id: 1, name: 'a', dependsOn: null },
      { id: 2, name: 'b', dependsOn: 1 },
    ] as Array<{ id: number; name: string; dependsOn: number | null }>
    const edges = buildDeps(tasks)
    expect(edges).toEqual([{ from: 1, to: 2 }])
  })

  it('keeps missing and incomplete dates out of the calendar', () => {
    expect(isScheduled({ startDate: null, endDate: null })).toBe(false)
    expect(isScheduled({ startDate: '2026-10-20', endDate: null })).toBe(false)
    expect(isScheduled({ startDate: '2026-10-21', endDate: '2026-10-20' })).toBe(false)
  })

  it('does not mark completed or unplanned tasks overdue', () => {
    expect(isOverdue({ startDate: '2026-10-10', endDate: '2026-10-20', progress: 100 }, '2026-10-21')).toBe(false)
    expect(isOverdue({ startDate: null, endDate: null, progress: 0 }, '2026-10-21')).toBe(false)
    expect(isOverdue({ startDate: '2026-10-10', endDate: '2026-10-20', progress: 75 }, '2026-10-21')).toBe(true)
  })

  it('treats same-day handover as a conflict and next-day start as valid', () => {
    const parent = { id: 1, startDate: '2026-10-20', endDate: '2026-10-21', dependsOn: null }
    const child = { id: 2, startDate: '2026-10-21', endDate: '2026-10-22', dependsOn: 1 }
    expect(hasDependencyConflict(child, [parent, child])).toBe(true)
    expect(hasDependencyConflict({ ...child, startDate: '2026-10-22' }, [parent, child])).toBe(false)
  })

  it('detects indirect cycles without hanging on existing malformed chains', () => {
    const tasks = [{ id: 1, dependsOn: null }, { id: 2, dependsOn: 1 }, { id: 3, dependsOn: 2 }]
    expect(wouldCycle(tasks, 1, 3)).toBe(true)
    expect(wouldCycle(tasks, 3, 1)).toBe(false)
    expect(wouldCycle([{ id: 4, dependsOn: 5 }, { id: 5, dependsOn: 4 }], undefined, 4)).toBe(true)
  })

  it('uses task progress and real dates for display states', () => {
    expect(taskStatus({ startDate: '2026-10-22', endDate: '2026-10-23', progress: 0 }, '2026-10-21')).toBe('待开始')
    expect(taskStatus({ startDate: null, endDate: null, progress: 30 }, '2026-10-21')).toBe('未排期')
  })
})
