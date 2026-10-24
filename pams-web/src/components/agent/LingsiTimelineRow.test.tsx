// @vitest-environment jsdom
import { cleanup, fireEvent, render } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { TimelineItem } from '@/stores/agent'
import LingsiTimelineRow from './LingsiTimelineRow'
import LingsiProcessZone from './LingsiProcessZone'

afterEach(() => { cleanup(); vi.restoreAllMocks() })
const reasoning = '先核对活动名称与已有资料，再整理筹备事项，确认缺失条件后生成清单。'.repeat(5)
const item: TimelineItem = { key:'reasoning-0',seq:1,kind:'thinking',round:0,
  label:reasoning.slice(0,42)+'…',detail:reasoning,completed:true,ts:1,durationMs:1300 }

describe('推理展开去重', () => {
  it('折叠隐藏全文，展开用中性标题替换摘要，收起恢复摘要', () => {
    const view = render(<LingsiTimelineRow item={item} />)
    const full = view.getByRole('region', {name:'推理全文',hidden:true})
    const collapse = full.closest('.pams-collapse')!
    expect(collapse.getAttribute('aria-hidden')).toBe('true')
    expect(collapse.hasAttribute('inert')).toBe(true)
    const toggle = view.getByRole('button', {name:'展开推理'})
    expect(toggle.getAttribute('aria-controls')).toBe(full.id)
    fireEvent.click(toggle)
    expect(collapse.getAttribute('aria-hidden')).toBe('false')
    expect(collapse.hasAttribute('inert')).toBe(false)
    expect(view.container.querySelector('.lingsi-tl-think-head .lingsi-tl-label:not([hidden])')?.textContent).toBe('推理内容')
    expect(full.textContent).toBe(reasoning)
    expect(view.container.textContent?.split(reasoning).length).toBe(2)
    fireEvent.click(view.getByRole('button', {name:'收起推理'}))
    expect(view.container.querySelector('.lingsi-tl-think-head .lingsi-tl-label')?.textContent).toBe(item.label)
    expect(collapse.getAttribute('data-open')).toBe('false')
  })

  it('被截断的短摘要也能展开，避免低于旧阈值的正文丢失', () => {
    const detail = '核对活动资料并确认筹备事项。'.repeat(5)
    const view = render(<LingsiTimelineRow item={{...item,label:'核对活动资料…',detail}} />)
    fireEvent.click(view.getByRole('button', {name:'展开推理'}))
    expect(view.getByRole('region', {name:'推理全文'}).textContent).toBe(detail)
  })

  it('短推理与编排旁白只有一份文字，没有无意义的展开按钮', () => {
    const view = render(<LingsiTimelineRow item={{...item,label:'核对资料',detail:'核对资料'}} />)
    expect(view.getAllByText('核对资料')).toHaveLength(1)
    expect(view.queryByRole('button')).toBeNull()
    view.rerender(<LingsiTimelineRow item={{...item,label:'正在理解问题',detail:undefined}} />)
    expect(view.getAllByText('正在理解问题')).toHaveLength(1)
    expect(view.queryByRole('button')).toBeNull()
  })

  it('展开期间追加推理不再带回摘要副本', () => {
    const view = render(<LingsiTimelineRow item={item} />)
    fireEvent.click(view.getByRole('button', {name:'展开推理'}))
    view.rerender(<LingsiTimelineRow item={{...item,detail:reasoning+'接着确认材料清单。'}} />)
    expect(view.container.querySelector('.lingsi-tl-think-head .lingsi-tl-label:not([hidden])')?.textContent).toBe('推理内容')
    expect(view.getByRole('region', {name:'推理全文'}).textContent).toBe(reasoning+'接着确认材料清单。')
  })

  it('过程区总耗时仅显示一次', () => {
    const view = render(<LingsiProcessZone items={[item]} active={false} />)
    expect(view.getByRole('button', {name:/已完成/}).textContent?.match(/1\.3s/g)).toHaveLength(1)
  })

  it('整个过程区收起时停止不可见动画，重新打开接续当前进度', () => {
    let now = 100
    vi.spyOn(performance, 'now').mockImplementation(() => now)
    const view = render(<LingsiProcessZone items={[{...item,completed:false}]} active />)
    const head = view.container.querySelector('.lingsi-process-head')!
    expect(view.container.querySelector('.pams-stream-char')).not.toBeNull()
    fireEvent.click(head)
    expect(view.container.querySelector('.pams-stream-char')).toBeNull()
    now = 10000
    fireEvent.click(head)
    expect(view.container.querySelector('.pams-stream-char')).toBeNull()
    expect(view.container.querySelector('.lingsi-tl-label')?.textContent).toBe(item.label)
  })

  it('增量接续同一队列，阶段完成时补齐文字，切换显隐不重播', () => {
    let now = 100
    vi.spyOn(performance, 'now').mockImplementation(() => now)
    const view = render(<LingsiTimelineRow item={{...item,completed:false}} />)
    expect(view.container.querySelector('.lingsi-tl-think-head .pams-stream-char')).not.toBeNull()
    fireEvent.click(view.getByRole('button', {name:'展开推理'}))
    now = 10000
    const next = {...item,completed:false,detail:reasoning+'继续核对。'}
    view.rerender(<LingsiTimelineRow item={next} />)
    const body = view.getByRole('region', {name:'推理全文'})
    expect(body.textContent).toBe(next.detail)
    expect(Array.from(body.querySelectorAll('.pams-stream-char')).map(el=>el.textContent).join('')).toBe('继续核对。')
    fireEvent.click(view.getByRole('button', {name:'收起推理'}))
    fireEvent.click(view.getByRole('button', {name:'展开推理'}))
    expect(Array.from(body.querySelectorAll('.pams-stream-char')).map(el=>el.textContent).join('')).toBe('继续核对。')
    view.rerender(<LingsiTimelineRow item={{...next,completed:true}} />)
    expect(body.querySelector('.pams-stream-char, .pams-stream-pending')).toBeNull()
    now = 11000
    fireEvent.click(view.getByRole('button', {name:'收起推理'}))
    fireEvent.click(view.getByRole('button', {name:'展开推理'}))
    expect(body.querySelector('.pams-stream-char')).toBeNull()
    expect(body.textContent).toBe(next.detail)
  })

  it('收起再展开按原始时间推进，不会把剩余队列从头排一遍', () => {
    let now = 100
    vi.spyOn(performance, 'now').mockImplementation(() => now)
    const view = render(<LingsiTimelineRow item={{...item,completed:false}} />)
    fireEvent.click(view.getByRole('button', {name:'展开推理'}))
    const body = view.getByRole('region', {name:'推理全文'})
    now = 300
    fireEvent.click(view.getByRole('button', {name:'收起推理'}))
    const visibleHead = view.container.querySelector('.lingsi-tl-label:not([hidden])')!
    expect(visibleHead.firstChild?.nodeType).toBe(Node.TEXT_NODE)
    now = 500
    fireEvent.click(view.getByRole('button', {name:'展开推理'}))
    const pending = body.querySelectorAll<HTMLElement>('.pams-stream-char')
    expect(parseFloat(pending[0].style.animationDelay)).toBeLessThan(0)
    expect(body.querySelector('span')?.firstChild?.nodeType).toBe(Node.TEXT_NODE)
  })

  it('工具调用、下一轮思考和结论开始前补齐上一阶段，不延迟真实状态', () => {
    const current = {...item,completed:false}
    const view = render(<LingsiProcessZone items={[current]} active />)
    fireEvent.click(view.getByRole('button', {name:'展开推理'}))
    const body = view.getByRole('region', {name:'推理全文'})
    expect(body.querySelector('.pams-stream-pending')).not.toBeNull()
    const tool: TimelineItem = {key:'tool-1',seq:2,kind:'tool',label:'正在核对资料',completed:false,ts:2}
    view.rerender(<LingsiProcessZone items={[current,tool]} active />)
    expect(body.querySelector('.pams-stream-char, .pams-stream-pending')).toBeNull()
    expect(view.getByText('正在核对资料')).toBeTruthy()
    const next = {...current,key:'reasoning-1',seq:3,round:1}
    view.rerender(<LingsiProcessZone items={[current,{...tool,completed:true},next]} active />)
    const rows = view.container.querySelectorAll('.lingsi-tl-row-think')
    expect(rows[0].querySelector('.pams-stream-char, .pams-stream-pending')).toBeNull()
    expect(rows[1].querySelector('.pams-stream-char')).not.toBeNull()
    view.rerender(<LingsiProcessZone items={[current,{...tool,completed:true},next]} active resultStarted />)
    expect(view.container.querySelector('.pams-stream-char, .pams-stream-pending')).toBeNull()
    expect(view.getByRole('button', {name:'正在生成结果…'})).toBeTruthy()
    expect(view.container.querySelector('.lingsi-tl-think .lingsi-dots')).toBeNull()
  })

  it('历史推理展开直接显示，推理中的 HTML 字符按普通文本呈现', () => {
    const text = '<img src=x onerror="alert(1)">\n核对活动资料。'
    const view = render(<LingsiTimelineRow item={{...item,detail:text}} />)
    fireEvent.click(view.getByRole('button', {name:'展开推理'}))
    const body = view.getByRole('region', {name:'推理全文'})
    expect(body.textContent).toBe(text)
    expect(body.querySelector('img')).toBeNull()
    expect(body.querySelector('.pams-stream-char')).toBeNull()
  })
})
