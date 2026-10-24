import { describe, expect, it } from 'vitest'
import { citationSnippet } from './citationSnippet'

describe('legacy structured plan citations', () => {
  it('formats flow and budget without losing zero values', () => {
    expect(citationSnippet({sourceType:'activity_plan', snippet:'[{"step":"签到","detail":"领取资料"}]'})).toBe('1. 环节：签到；内容：领取资料')
    expect(citationSnippet({sourceType:'activity_plan', snippet:'[{"item":"资料","quantity":0,"unitPrice":1.5,"totalPrice":0}]'})).toContain('数量：0；单价：1.5；合计：0')
  })
  it('recovers complete rows from a truncated snapshot without inventing the remainder', () => {
    const text = citationSnippet({sourceType:'activity_plan', snippet:'[{"item":"资料","quantity":2},{"item":"证书…'})
    expect(text).toContain('项目：资料；数量：2')
    expect(text).toContain('片段已截断')
    expect(text).not.toContain('项目：证书')
  })
  it('leaves prose, other source types and unrecognised data untouched', () => {
    for (const snippet of ['正常正文', '[1,2]', '[{"other":"值"}]']) expect(citationSnippet({sourceType:'activity_plan', snippet})).toBe(snippet)
    expect(citationSnippet({sourceType:'manual', snippet:'[{"item":"JSON示例"}]'})).toBe('[{"item":"JSON示例"}]')
  })
})
