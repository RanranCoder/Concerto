import { describe, expect, it } from 'vitest'
import { readBenchmarkMetrics } from './benchmarkMetrics'

describe('evaluation metadata compatibility', () => {
  it('accepts legacy rows and rejects JSON primitives without breaking the table', () => {
    for (const raw of [undefined, null, '', 'null', '5', '"text"', '[]', '{broken']) expect(readBenchmarkMetrics(raw)).toEqual({})
  })
  it('retains incomplete and unknown values without fabricating zero cost', () => {
    const value = readBenchmarkMetrics('{"cost":null,"usageComplete":false,"reportedCalls":1,"reportedInputTokens":0}')
    expect(value).toEqual({ cost: null, usageComplete: false, reportedCalls: 1, reportedInputTokens: 0 })
    expect(value).not.toHaveProperty('qualityVerdict')
  })
})
