// @vitest-environment jsdom
// 组件级验证：编辑标题 → 保存 → 重载后标题是否保留（模拟 PlanRichEditor）
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, fireEvent, cleanup, act } from '@testing-library/react'
import React from 'react'

// 模拟 wangEditor 封装：可控 contentEditable，value 变化时同步内容，input 时回调
vi.mock('./PlanRichEditor', () => ({
  default: function MockRichEditor(props: { value: string; onChange: (html: string) => void; placeholder?: string }) {
    const divRef = React.useRef<HTMLDivElement>(null)
    React.useEffect(() => {
      if (divRef.current && divRef.current.innerHTML !== props.value) {
        divRef.current.innerHTML = props.value
      }
    })
    return (
      <div
        data-testid="mock-editor"
        ref={divRef}
        contentEditable
        suppressContentEditableWarning
        onInput={(e) => props.onChange((e.currentTarget as HTMLDivElement).innerHTML)}
      />
    )
  },
}))

import WordEditor from './WordEditor'
import type { PlanFields, PlanOverrides, PlanSectionOrderItem } from './planTemplate'

beforeEach(() => cleanup())

const EMPTY: PlanFields = { background: '', purpose: '', content: '', flow: '', notice: '', emergency: '', budget: '' }

function renderEditor(opts: { initial?: PlanFields; overrides?: PlanOverrides; order?: PlanSectionOrderItem[] } = {}) {
  const calls: Array<{ fields: PlanFields; overrides: PlanOverrides; order: PlanSectionOrderItem[] }> = []
  const rec = { fields: opts.initial ?? EMPTY, overrides: opts.overrides ?? {}, order: opts.order ?? [] }
  const Wrapper = () => {
    const [fields, setFields] = React.useState<PlanFields>(opts.initial ?? EMPTY)
    const [overrides, setOverrides] = React.useState<PlanOverrides | null>(opts.overrides ?? null)
    const [order, setOrder] = React.useState<PlanSectionOrderItem[] | null>(opts.order ?? null)
    return (
      <WordEditor
        value={fields}
        onChange={(f) => {
          rec.fields = f
          setFields(f)
        }}
        overrides={overrides ?? undefined}
        onOverridesChange={(o) => {
          rec.overrides = o
          setOverrides(o)
        }}
        sectionOrder={order?.length ? order : undefined}
        onSectionOrderChange={(o) => {
          rec.order = o
          setOrder(o)
        }}
      />
    )
  }
  const view = render(<Wrapper />)
  return {
    view,
    read: () => {
      calls.push({ fields: rec.fields, overrides: rec.overrides, order: rec.order })
      return calls[calls.length - 1]
    },
  }
}

describe('WordEditor 章节标题回写', () => {
  it('编辑已知章节标题 → 上抛的 sectionOrder 保留自定义标题', async () => {
    const { view, read } = renderEditor()
    const editor = await view.findByTestId('mock-editor')
    // 把「四、活动目的」标题改成「四、活动目标」并输入正文
    act(() => {
      editor.innerHTML = editor.innerHTML.replace('<h2>四、活动目的</h2><p><br></p>', '<h2>四、活动目标</h2><p>提升能力</p>')
      fireEvent.input(editor)
    })
    const last = read()
    const renamed = last.order.find((o) => o.label === '四、活动目标')
    expect(renamed).toBeTruthy()
    expect(renamed!.contentHtml).toContain('提升能力')
    expect(renamed!.field).toBeNull()
  })

  it('保存后重载（用上抛的 order 重建）→ 标题仍保留', async () => {
    const { view, read } = renderEditor()
    const editor = await view.findByTestId('mock-editor')
    act(() => {
      editor.innerHTML = editor.innerHTML.replace('<h2>四、活动目的</h2><p><br></p>', '<h2>四、活动目标</h2><p>提升能力</p>')
      fireEvent.input(editor)
    })
    const last = read()
    cleanup()
    // 模拟保存后重新挂载：把上抛的 fields/overrides/order 作为 props
    const { view: view2 } = renderEditor({ initial: last.fields, overrides: last.overrides, order: last.order })
    const editor2 = await view2.findByTestId('mock-editor')
    expect(editor2.innerHTML).toContain('<h2>四、活动目标</h2>')
    expect(editor2.innerHTML).toContain('提升能力')
  })
})
