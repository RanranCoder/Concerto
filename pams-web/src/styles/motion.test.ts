import { describe, expect, it } from 'vitest'
import { SPRINGS, springProgress } from './motion'

describe('motion personality', () => {
  it('the drag spring converges without overshoot or reverse travel', () => {
    let previous = 0
    for (let i = 0; i <= 100; i++) {
      const value = springProgress(i / 100, SPRINGS.drag.stiffness, SPRINGS.drag.damping)
      expect(value).toBeGreaterThanOrEqual(previous)
      expect(value).toBeLessThanOrEqual(1)
      previous = value
    }
    expect(previous).toBeCloseTo(1, 5)
  })
  it('confirmation has a small overshoot and settles', () => {
    const samples = Array.from({ length: 101 }, (_, i) => springProgress(i / 100, SPRINGS.feedback.stiffness, SPRINGS.feedback.damping))
    expect(Math.max(...samples)).toBeGreaterThan(1)
    expect(Math.max(...samples)).toBeLessThan(1.25)
    expect(samples.at(-1)).toBeCloseTo(1, 3)
  })
})
