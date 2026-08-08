import { describe, it, expect } from 'vitest'
import { columnCountFor, distributeToColumns } from './masonry.utils'

describe('masonry.utils', () => {
  it('columnCountFor never returns 0', () => {
    expect(columnCountFor(0, 340, 16)).toBe(1)
    expect(columnCountFor(300, 340, 16)).toBe(1)
  })

  it('columnCountFor scales with width', () => {
    expect(columnCountFor(1200, 340, 16)).toBe(3)
    expect(columnCountFor(800, 340, 16)).toBe(2)
    expect(columnCountFor(500, 340, 16)).toBe(1)
  })

  it('distributeToColumns puts tall items first then fills other columns', () => {
    expect(distributeToColumns([400, 100, 100], 2, 0)).toEqual([0, 1, 1])
  })

  it('distributeToColumns balances alternating items', () => {
    expect(distributeToColumns([200, 200, 200, 200], 2, 0)).toEqual([0, 1, 0, 1])
  })

  it('distributeToColumns counts gap toward column height', () => {
    expect(distributeToColumns([300, 100, 100], 2, 16)).toEqual([0, 1, 1])
  })
})
