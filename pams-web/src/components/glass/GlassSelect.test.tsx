// @vitest-environment jsdom
import { describe, it, expect, vi } from 'vitest'
import { useState } from 'react'
import { render, screen, fireEvent } from '@testing-library/react'
import GlassSelect, { type GlassSelectOption } from './GlassSelect'

/**
 * 自建下拉选框回归：全站不再出现浏览器原生 select 的观感，
 * 这里固化「无原生元素 + 展开/收起 + 键盘交互 + 弹层挂在 body」这几条，
 * 防止后续有人图省事换回 <select>。
 */

type Mode = 'standard' | 'readonly' | 'kb'

const MODE_OPTIONS: GlassSelectOption<Mode>[] = [
  { value: 'standard', label: '标准模式' },
  { value: 'readonly', label: '只读模式' },
  { value: 'kb', label: '资料问答' },
]

function Demo({
  initial = 'standard',
  disabled,
  onChange,
}: {
  initial?: Mode
  disabled?: boolean
  onChange?: (m: Mode) => void
}) {
  const [mode, setMode] = useState<Mode>(initial)
  return (
    <GlassSelect
      ariaLabel="运行模式"
      value={mode}
      options={MODE_OPTIONS}
      disabled={disabled}
      onChange={(m) => {
        setMode(m)
        onChange?.(m)
      }}
    />
  )
}

describe('GlassSelect 自建下拉选框', () => {
  it('收起态只显示当前值，不带任何原生 select 元素', () => {
    render(<Demo />)
    const trigger = screen.getByRole('combobox', { name: '运行模式' })
    expect(trigger.tagName).toBe('BUTTON')
    expect(trigger.getAttribute('aria-expanded')).toBe('false')
    expect(trigger.textContent).toContain('标准模式')
    expect(document.querySelector('select')).toBeNull()
    expect(screen.queryByRole('option')).toBeNull()
  })

  it('展开后选项列表挂在 body 上（portal），并带 listbox 语义', () => {
    render(<Demo />)
    fireEvent.click(screen.getByRole('combobox', { name: '运行模式' }))
    const listbox = screen.getByRole('listbox', { name: '运行模式' })
    expect(listbox.closest('body')).toBe(document.body)
    expect(screen.getAllByRole('option').map((o) => o.textContent)).toEqual([
      '标准模式',
      '只读模式',
      '资料问答',
    ])
    expect(screen.getByRole('option', { name: '标准模式' }).getAttribute('aria-selected')).toBe('true')
  })

  it('点击选项回报新值并收起，触发器文案随之更新', () => {
    const onChange = vi.fn()
    render(<Demo onChange={onChange} />)
    fireEvent.click(screen.getByRole('combobox', { name: '运行模式' }))
    fireEvent.click(screen.getByRole('option', { name: '资料问答' }))

    expect(onChange).toHaveBeenCalledWith('kb')
    expect(screen.getByRole('combobox', { name: '运行模式' }).textContent).toContain('资料问答')
    expect(screen.queryByRole('option')).toBeNull()
  })

  it('键盘：方向键在触发器上移动高亮，Enter 选中', () => {
    const onChange = vi.fn()
    render(<Demo initial="readonly" onChange={onChange} />)
    const trigger = screen.getByRole('combobox', { name: '运行模式' })

    fireEvent.keyDown(trigger, { key: 'ArrowDown' })
    expect(trigger.getAttribute('aria-expanded')).toBe('true')
    // 展开时高亮落在当前值上：键盘用户第一步就已经在正确位置
    expect(screen.getByRole('option', { name: '只读模式' }).getAttribute('data-active')).toBe('true')

    fireEvent.keyDown(trigger, { key: 'ArrowDown' })
    const next = screen.getByRole('option', { name: '资料问答' })
    expect(next.getAttribute('data-active')).toBe('true')
    expect(trigger.getAttribute('aria-activedescendant')).toBe(next.id)

    fireEvent.keyDown(trigger, { key: 'Enter' })
    expect(onChange).toHaveBeenCalledTimes(1)
    expect(onChange).toHaveBeenCalledWith('kb')
    expect(screen.queryByRole('option')).toBeNull()
  })

  it('Esc 与点击外部关闭都不改值', () => {
    const onChange = vi.fn()
    render(<Demo initial="kb" onChange={onChange} />)
    const trigger = screen.getByRole('combobox', { name: '运行模式' })

    fireEvent.keyDown(trigger, { key: 'ArrowDown' })
    fireEvent.keyDown(trigger, { key: 'Escape' })
    expect(screen.queryByRole('option')).toBeNull()

    fireEvent.click(trigger)
    fireEvent.mouseDown(document.body)
    expect(screen.queryByRole('option')).toBeNull()

    expect(onChange).not.toHaveBeenCalled()
    expect(trigger.textContent).toContain('资料问答')
  })

  it('disabled 时点击不展开', () => {
    render(<Demo disabled />)
    fireEvent.click(screen.getByRole('combobox', { name: '运行模式' }))
    expect(screen.queryByRole('option')).toBeNull()
  })
})
