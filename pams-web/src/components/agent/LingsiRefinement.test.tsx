// @vitest-environment jsdom
import { act, cleanup, fireEvent, render } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import LingsiCopyButton from './LingsiCopyButton'
import LingsiMarkdown from './LingsiMarkdown'

const warning = vi.hoisted(() => vi.fn())
vi.mock('antd', () => ({
  App: { useApp: () => ({ message: { warning } }) },
  Tooltip: ({ children }: { children: React.ReactNode }) => children,
}))

afterEach(() => { cleanup(); vi.useRealTimers(); vi.restoreAllMocks(); warning.mockReset() })

describe('copy feedback lifecycle', () => {
  it('repeated copying restarts the clock, then restores the original action', async () => {
    vi.useFakeTimers()
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: vi.fn().mockResolvedValue(undefined) } })
    const view = render(<LingsiCopyButton text="完整正文" />)
    await act(async () => { fireEvent.click(view.getByRole('button', { name: '复制' })) })
    expect(view.getByRole('button', { name: '已复制' })).toBeTruthy()
    act(() => vi.advanceTimersByTime(2000))
    await act(async () => { fireEvent.click(view.getByRole('button', { name: '已复制' })) })
    act(() => vi.advanceTimersByTime(2499))
    expect(view.getByRole('button', { name: '已复制' })).toBeTruthy()
    act(() => vi.advanceTimersByTime(1))
    expect(view.getByRole('button', { name: '复制' })).toBeTruthy()
    expect(navigator.clipboard.writeText).toHaveBeenCalledWith('完整正文')
  })

  it('reports clipboard rejection without showing a success check', async () => {
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: vi.fn().mockRejectedValue(new Error('denied')) } })
    const view = render(<LingsiCopyButton text="正文" />)
    await act(async () => { fireEvent.click(view.getByRole('button')) })
    expect(view.getByRole('button', { name: '复制' })).toBeTruthy()
    expect(warning).toHaveBeenCalledOnce()
  })
})

describe('streamed Markdown', () => {
  it('keeps long and repeated incoming chunks within a bounded lag, then finishes promptly', () => {
    vi.useFakeTimers()
    let now = 100
    vi.spyOn(performance, 'now').mockImplementation(()=>now)
    let text = '持续输出，不能积压成几十秒的动画。'.repeat(120)
    const view = render(<LingsiMarkdown text={text} streaming />)
    for(let i=0;i<15;i++) {
      now += 60
      text += '下一段内容。'.repeat(12)
      view.rerender(<LingsiMarkdown text={text} streaming />)
      act(()=>vi.advanceTimersByTime(60))
      expect(view.container.querySelectorAll('.pams-stream-char').length).toBeLessThanOrEqual(12)
    }
    // No more input: even a long backlog must finish within 320+90ms.
    now += 420
    act(()=>vi.advanceTimersByTime(32))
    expect(view.container.querySelector('.pams-stream-char, .pams-stream-pending')).toBeNull()
    now += 10
    text += '结尾'.repeat(200)
    view.rerender(<LingsiMarkdown text={text} streaming />)
    view.rerender(<LingsiMarkdown text={text} />)
    now += 200
    act(()=>vi.advanceTimersByTime(32))
    expect(view.container.querySelector('.pams-stream-char, .pams-stream-pending')).toBeNull()
    expect(view.container.textContent).toBe(text)
  })

  it('bounds animated nodes across long formatted replies and preserves settled blocks', () => {
    let now = 100
    vi.spyOn(performance, 'now').mockImplementation(() => now)
    const view = render(<LingsiMarkdown text="已经显示的正文。" />)
    const paragraph = view.container.querySelector('p')
    const text = '已经显示的正文。\n\n'+ ('**核对资料**与筹备事项[3]。\n\n'.repeat(80))
    view.rerender(<LingsiMarkdown text={text} streaming citationIndices={[3]} />)
    expect(view.container.querySelector('p')).toBe(paragraph)
    expect(view.container.querySelectorAll('.pams-stream-char').length).toBeLessThanOrEqual(12)
    const cite = view.container.querySelector('.lingsi-cite')!
    expect(cite.classList.contains('pams-stream-pending')).toBe(true)
    expect(cite.getAttribute('tabindex')).toBe('-1')
    expect(cite.querySelector('.pams-stream-char')).toBeNull()
    now = 100000
    view.rerender(<LingsiMarkdown text={text} citationIndices={[3]} />)
    expect(view.container.querySelectorAll('.pams-stream-char, .pams-stream-pending')).toHaveLength(0)
    expect(cite.getAttribute('tabindex')).toBe('0')
    expect(view.container.querySelector('p')).toBe(paragraph)
  })

  it('uses one reveal queue across incoming chunks, sentences and paragraphs', () => {
    vi.useFakeTimers()
    let now = 100
    vi.spyOn(performance, 'now').mockImplementation(() => now)
    const prefix = '甲'.repeat(12)
    const view = render(<LingsiMarkdown text={prefix} streaming />)
    now = 120
    view.rerender(<LingsiMarkdown text={prefix+'\n\n戊己'} streaming />)
    expect(view.container.querySelector('p:last-of-type .pams-stream-pending')?.textContent).toBe('戊己')
    now = 140
    view.rerender(<LingsiMarkdown text={prefix+'\n\n戊己'} />)
    expect(view.container.querySelector('p:last-of-type .pams-stream-char')).toBeNull()
    now = 200
    act(() => vi.advanceTimersByTime(32))
    const letters = Array.from(view.container.querySelectorAll<HTMLElement>('p:last-of-type .pams-stream-char'))
    expect(letters.map(el=>el.textContent).join('')).toBe('戊己')
    expect(parseFloat(letters[0].style.animationDelay)).toBeLessThanOrEqual(0)
    now = 400
    act(() => vi.advanceTimersByTime(32))
    expect(view.container.querySelector('.pams-stream-pending')).toBeNull()
    expect(view.container.querySelectorAll('.pams-stream-char')).toHaveLength(0)
  })

  it('Markdown closing delimiters do not replay already revealed words', () => {
    let now = 100
    vi.spyOn(performance, 'now').mockImplementation(() => now)
    const view = render(<LingsiMarkdown text="甲**乙丙" streaming />)
    now = 10000
    view.rerender(<LingsiMarkdown text="甲**乙丙**丁" streaming />)
    expect(view.container.querySelector('strong')?.textContent).toBe('乙丙')
    expect(Array.from(view.container.querySelectorAll('.pams-stream-char')).map(el=>el.textContent).join('')).toBe('丁')
  })

  it('finished characters merge into ordinary text and cancel the frame clock on unmount', () => {
    vi.useFakeTimers()
    let now = 100
    vi.spyOn(performance, 'now').mockImplementation(() => now)
    const view = render(<LingsiMarkdown text="甲" streaming />)
    expect(view.container.querySelector('.pams-stream-char')).not.toBeNull()
    now = 200
    act(() => vi.advanceTimersByTime(32))
    expect(view.container.querySelector('.pams-stream-char')).toBeNull()
    expect(view.container.querySelector('[data-pams-run]')?.firstChild?.nodeType).toBe(Node.TEXT_NODE)
    view.rerender(<LingsiMarkdown text={'甲'+ '继续'.repeat(1000)} streaming />)
    view.unmount()
    expect(vi.getTimerCount()).toBe(0)
  })
  it('adding final citation metadata never replays text after the marker', () => {
    let now = 100
    vi.spyOn(performance, 'now').mockImplementation(() => now)
    const text = '依据[3]办理，后续文本保持稳定。'
    const view = render(<LingsiMarkdown text={text} streaming />)
    now = 10000
    view.rerender(<LingsiMarkdown text={text} citationIndices={[3]} />)
    expect(view.container.querySelector('.lingsi-cite')?.textContent).toBe('3')
    expect(view.container.querySelectorAll('.pams-stream-char')).toHaveLength(0)
    expect(view.container.textContent).toBe('依据3办理，后续文本保持稳定。')
  })

  it('animates the entire citation box and allows sparse indices only', () => {
    const view = render(<LingsiMarkdown text="甲[3]乙[1]" streaming citationIndices={[3]} />)
    const cite = view.container.querySelector('.lingsi-cite')!
    expect(cite.classList.contains('pams-stream-char')).toBe(true)
    expect(cite.querySelector('.pams-stream-char')).toBeNull()
    expect(view.container.querySelectorAll('.lingsi-cite')).toHaveLength(1)
    expect(view.container.textContent).toContain('乙[1]')
  })
  it('keeps past reveal deadlines, grapheme clusters, links and citations across chunks', () => {
    let now = 100
    vi.spyOn(performance, 'now').mockImplementation(() => now)
    const onCite = vi.fn()
    const view = render(<LingsiMarkdown text="甲" streaming citeMax={1} onCite={onCite} />)
    const first = view.container.querySelector('.pams-stream-char')
    expect(view.container.querySelector<HTMLElement>('.pams-stream-char')?.style.animationDelay).toBe('0ms')
    now = 140
    view.rerender(<LingsiMarkdown text="甲乙👨‍👩‍👧‍👦 [1]" streaming citeMax={1} onCite={onCite} />)
    const chars = view.container.querySelectorAll<HTMLElement>('.pams-stream-char')
    expect(chars[0]).toBe(first)
    expect(chars[0].style.animationDelay).toBe('0ms') // existing animation keeps its original clock
    expect(chars[1].style.animationDelay).toBe('0ms')
    expect(chars[2].textContent?.startsWith('👨‍👩‍👧‍👦')).toBe(true)
    fireEvent.click(view.container.querySelector('.lingsi-cite')!)
    expect(onCite).toHaveBeenCalledWith(1)
    now = 1000
    view.rerender(<LingsiMarkdown text="甲乙👨‍👩‍👧‍👦 [1]" citeMax={1} onCite={onCite} />)
    expect(view.container.querySelectorAll('.pams-stream-char')).toHaveLength(0)
    expect(view.container.textContent).toContain('甲乙👨‍👩‍👧‍👦')
  })

  it('renders history immediately and preserves sanitized formatting', () => {
    const view = render(<LingsiMarkdown text={'**原文**\n\n<script>alert(1)</script>'} />)
    expect(view.container.querySelectorAll('.pams-stream-char')).toHaveLength(0)
    expect(view.container.querySelector('script')).toBeNull()
    expect(view.container.querySelector('strong')?.textContent).toBe('原文')
  })

  it('respects reduced motion even while streaming', () => {
    Object.defineProperty(window, 'matchMedia', { configurable: true, value: vi.fn(() => ({ matches: true })) })
    const view = render(<LingsiMarkdown text="立即显示正文" streaming />)
    expect(view.container.textContent).toContain('立即显示正文')
    expect(view.container.querySelectorAll('.pams-stream-char')).toHaveLength(0)
  })

  it('enabling reduced motion mid-reply reveals the queued suffix immediately', () => {
    vi.useFakeTimers()
    const motion = {matches:false}
    Object.defineProperty(window, 'matchMedia', { configurable:true, value:vi.fn(()=>motion) })
    const view = render(<LingsiMarkdown text={'整段连续输出。'.repeat(80)} streaming />)
    expect(view.container.querySelector('.pams-stream-pending')).not.toBeNull()
    motion.matches = true
    act(()=>vi.advanceTimersByTime(32))
    expect(view.container.querySelectorAll('.pams-stream-char, .pams-stream-pending')).toHaveLength(0)
    expect(vi.getTimerCount()).toBe(0)
  })
})
