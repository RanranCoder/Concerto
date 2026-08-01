// pams-web/src/components/gantt/gantt.utils.test.ts
import { describe, it, expect } from 'vitest'
import { dayRange, buildDeps } from './gantt.utils'

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
})
