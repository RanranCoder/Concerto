// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { planFromDocxHtml, planToDocx } from './planTemplate'

describe('activity Word document import/export', () => {
  it('exports recognized headings and all content including the separate flow field', async () => {
    const blob = await planToDocx(
      {
        background: '<p>原始背景</p>',
        purpose: '<p>活动目的</p>',
        content: '<p>完整活动内容</p>',
        flow: '<p>签到、学习、讨论、总结</p>',
        notice: '<p>注意事项</p>',
        emergency: '<p>应急预案</p>',
        budget: '<table><tr><td>资料印制</td><td>120元</td></tr></table>',
      },
      { name: 'Word 往返校验', endDate: '2026-10-20' },
    )
    const mammoth = await import('mammoth')
    const html = (
      await mammoth.convertToHtml({
        buffer: Buffer.from(await blob.arrayBuffer()),
      })
    ).value
    expect(html).toContain('<h2>')
    const restored = planFromDocxHtml(html)
    expect(restored.background).toContain('原始背景')
    expect(restored.purpose).toContain('活动目的')
    expect(restored.content).toContain('完整活动内容')
    expect(restored.flow).toContain('签到、学习、讨论、总结')
    expect(restored.notice).toContain('注意事项')
    expect(restored.emergency).toContain('应急预案')
    expect(restored.budget).toContain('<table>')
    expect(restored.budget).toContain('120元')
  })

  it('also recognizes exact section labels formatted as ordinary Word paragraphs', () => {
    const restored = planFromDocxHtml(
      '<p><strong>三、活动背景</strong></p><p>普通段落样式的背景</p><p>九、活动内容</p><p>活动正文</p><p><strong>活动流程</strong></p><p>先签到再学习</p>',
    )
    expect(restored.background).toContain('普通段落样式的背景')
    expect(restored.content).toContain('活动正文')
    expect(restored.flow).toContain('先签到再学习')
  })
})
