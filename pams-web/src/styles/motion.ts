/** Unit-mass spring responses, sampled for CSS linear() (no animation dependency). */
export const SPRINGS = {
  drag: { stiffness: 1200, damping: 80 },
  feedback: { stiffness: 380, damping: 18 },
} as const

export function springProgress(seconds: number, stiffness: number, damping: number): number {
  const a = damping / 2
  const discriminant = a * a - stiffness
  if (discriminant > 0) {
    const b = Math.sqrt(discriminant)
    const r1 = -a + b
    const r2 = -a - b
    return 1 + (r2 * Math.exp(r1 * seconds) - r1 * Math.exp(r2 * seconds)) / (r1 - r2)
  }
  if (discriminant === 0) return 1 - (1 + a * seconds) * Math.exp(-a * seconds)
  const b = Math.sqrt(-discriminant)
  return 1 - Math.exp(-a * seconds) * (Math.cos(b * seconds) + a / b * Math.sin(b * seconds))
}

export function springEasing(spring: { stiffness: number; damping: number }): string {
  const samples = Array.from({ length: 61 }, (_, i) =>
    i === 60 ? '1' : springProgress(i / 100, spring.stiffness, spring.damping).toFixed(5),
  )
  return `linear(${samples.join(', ')})`
}
