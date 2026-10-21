// @vitest-environment jsdom
import { describe, it, expect } from 'vitest'
import type { ActivityPlanVO } from '@/api/activity'
import { comparePlans, comparisonText } from './planComparison'
const plan = (fields: Partial<ActivityPlanVO>) =>
  ({ id: 1, activityId: 1, version: 1, ...fields }) as ActivityPlanVO
describe('版本章节对比', () => {
  it('区分新增、删除和修改的章节，保留前后文本', () => {
    const changes = comparePlans(
      plan({ purpose: '<p>旧目标</p>', budget: '<p>预算</p>' }),
      plan({ purpose: '<p>新目标</p>', notice: '<p>新提醒</p>' }),
    )
    expect(changes.map((c) => c.key)).toEqual(['purpose', 'notice', 'budget'])
    expect(changes[0]).toMatchObject({ before: '旧目标', after: '新目标' })
    expect(changes[2].after).toBe('')
  })
  it('正文相同但格式变化时明确标记', () => {
    expect(
      comparePlans(
        plan({ purpose: '<p>目标</p>' }),
        plan({ purpose: '<p><strong>目标</strong></p>' }),
      )[0].formatOnly,
    ).toBe(true)
  })
  it('同时比较元数据、自定义章节和顺序', () => {
    const changes = comparePlans(
      plan({
        nameOverride: '旧名称',
        sectionOrder: JSON.stringify([
          { label: '补充说明', contentHtml: '<p>原安排</p>' },
        ]),
      }),
      plan({
        nameOverride: '新名称',
        sectionOrder: JSON.stringify([
          { label: '补充说明', contentHtml: '<p>新安排</p>' },
        ]),
      }),
    )
    expect(changes.map((c) => c.label)).toContain('活动名称')
    expect(changes.find((c) => c.label === '补充说明')).toMatchObject({
      before: '原安排',
      after: '新安排',
    })
  })
  it('仅改变审核状态不会伪造章节差异', () => {
    expect(
      comparePlans(plan({ status: 'DRAFT' }), plan({ status: 'APPROVED' })),
    ).toEqual([])
  })
  it('以纯文本展示表格、图片，忽略脚本与事件属性', () => {
    expect(
      comparisonText(
        '<script>alert(1)</script><p onclick="evil()">安全正文</p><img src="private" alt="会场图">',
      ),
    ).toBe('安全正文\n[图片：会场图]')
  })
})
