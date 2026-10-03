// @vitest-environment jsdom
import { describe, it, expect, vi, beforeAll } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { App as AntApp } from 'antd'
import LingsiFormCard from './LingsiFormCard'
import type { AgentInputForm } from '@/api/agent'

// jsdom 没有 matchMedia，antd 的响应式观察器会用（不 stub 会在挂载时抛错）
beforeAll(() => {
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    value: (query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addListener: () => {},
      removeListener: () => {},
      addEventListener: () => {},
      removeEventListener: () => {},
      dispatchEvent: () => false,
    }),
  })
})

/**
 * 表单补全卡回归：**预填值必须真的出现在控件里**。
 *
 * <p>用户反馈：「点提交提示开始日期为必填，但已经填了」。
 * 排查结论（实测，非推测）：
 * <ul>
 *   <li>后端 `CreateActivityTool.parseDate` **只接受严格 yyyy-MM-dd** ——
 *       ISO 带时间 / 2026/10/05 / 2026年10月5日 都会被拒并重新弹表单（已用接口逐个验证）；</li>
 *   <li>首次下发的表单**没有 taskId**（后端只在 submitInput 的返回里补），
 *       所以自动打开与 key 都不能依赖 taskId；</li>
 *   <li>真正的病根：预填值进了 Form 的 store，却**没有进控件** ——
 *       用户看到空的必填日期框，一点提交就被 antd 拦下报「开始日期为必填」。</li>
 * </ul>
 * 下面两条断言分别钉住「预填可见」与「提交值格式正确」。
 */
const FORM: AgentInputForm = {
  // 首次下发就是没有 taskId 的形态
  taskId: undefined as unknown as number,
  formKey: 'createActivity',
  title: '完善活动信息',
  description: '带 * 为必填',
  fields: [
    { name: 'name', label: '活动名称', type: 'text', required: true },
    { name: 'startDate', label: '开始日期', type: 'date', required: true },
    { name: 'endDate', label: '结束日期', type: 'date', required: false },
  ],
  values: { name: '主题党日活动' },
}

function renderCard(extra: Record<string, string>) {
  const onSubmit = vi.fn()
  render(
    <AntApp>
      <LingsiFormCard
        form={{ ...FORM, values: { ...FORM.values, ...extra } }}
        onSubmit={async (v) => {
          onSubmit(v)
          return { status: 'PLAN' }
        }}
      />
    </AntApp>,
  )
  return onSubmit
}

/** 等弹窗内容渲染完（Modal 有开启动画），并给 picker 一拍格式化显示值 */
async function ready() {
  await waitFor(() => {
    expect(document.querySelectorAll('.ant-picker input').length).toBeGreaterThan(0)
  })
  await new Promise((r) => setTimeout(r, 250))
}

/** 所有日期输入框的显示值（DatePicker 的 id 不会透传到内层 input，只能按 class 取） */
const dateValues = () =>
  Array.from(document.querySelectorAll('.ant-picker input')).map((i) => (i as HTMLInputElement).value)

/** 文本类输入框（含名称） */
const textValues = () =>
  Array.from(document.querySelectorAll('.ant-modal input.ant-input')).map((i) => (i as HTMLInputElement).value)

describe('LingsiFormCard 预填与提交', () => {
  it('预填的日期显示在控件里（不能只存在 form store 里）', async () => {
    renderCard({ startDate: '2026-10-05' })
    await ready()
    expect(dateValues().join(' | ')).toContain('2026-10-05')
  })

  it('预填的文本值显示在控件里', async () => {
    renderCard({})
    await ready()
    expect(textValues().join(' | ')).toContain('主题党日活动')
  })

  it('日期提交为严格 yyyy-MM-dd（后端只认这个格式）', async () => {
    const onSubmit = renderCard({ startDate: '2026-10-05' })
    await ready()

    fireEvent.click(screen.getByText('提交并生成清单'))

    await waitFor(() => expect(onSubmit).toHaveBeenCalled())
    const submitted = onSubmit.mock.calls[0][0] as Record<string, string>
    expect(submitted.startDate).toBe('2026-10-05')
    // 必填项有值 → 不该出现这条拦截提示
    expect(screen.queryByText('开始日期为必填')).toBeNull()
  })
})
