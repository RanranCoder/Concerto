// @vitest-environment jsdom
import { afterEach, describe, expect, it } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import LingsiTeamPanel from './LingsiTeamPanel'
import type { TimelineItem } from '@/stores/agent'
afterEach(cleanup)
const task = (key: string, status: string): TimelineItem => ({
  key, seq: Number(key), kind: 'agent', agent: 'writing', label: '文书任务', status, completed: true, ts: 1,
})
describe('collaboration execution states', () => {
  it('keeps waiting actions visible and does not call them completed', () => {
    render(<LingsiTeamPanel items={[task('1', 'WAITING_CONFIRM')]} />)
    expect(screen.getByText('待确认')).toBeTruthy()
    expect(screen.getByRole('button').getAttribute('aria-expanded')).toBe('true')
    expect(screen.queryByText('已完成')).toBeNull()
  })
  it('does not hide repeated attempts from the same specialist', () => {
    render(<LingsiTeamPanel active items={[task('1', 'PARTIAL'), task('2', 'FAILED')]} />)
    expect(screen.getAllByText('文书任务')).toHaveLength(2)
    expect(screen.getByText('失败')).toBeTruthy()
    expect(screen.queryByText('已完成')).toBeNull()
  })
})
