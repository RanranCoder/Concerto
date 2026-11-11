// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { AgentRunView } from '@/api/agent'
import LingsiRunOverview from './LingsiRunOverview'

afterEach(cleanup)
const run: AgentRunView = {
  id: 1, streamId: 'run', conversationId: 1, status: 'WAITING_CONFIRM', actions: [],
  nodes: [
    { id: 1, workItemId: 11, agent: 'retrieval', task: '检索历史策划', status: 'SUCCESS', dependsOn: [], revision: 1, attempt: 1 },
    { id: 2, workItemId: 12, agent: 'writing', task: '撰写活动报道', status: 'WAITING_CONFIRM', dependsOn: [1], revision: 1, attempt: 1 },
    { id: 3, workItemId: 13, agent: 'reviewer', task: '核对报道事实', status: 'QUEUED', dependsOn: [2], revision: 1, attempt: 0 },
  ],
}
describe('collaboration overview', () => {
  it('keeps the live shell and user collapse when the first durable snapshot arrives', () => {
    const view = render(<LingsiRunOverview active items={[{key:'a',seq:1,ts:1,kind:'agent',agent:'writing',label:'文书任务',completed:false}]} />)
    const shell = screen.getByRole('region', { name: '协作概览' })
    expect(screen.getByText('已收到 1 项执行记录')).toBeTruthy()
    expect(screen.queryByText(/项任务/)).toBeNull()
    expect(screen.queryByRole('button', { name:/查看任务详情/ })).toBeNull()
    fireEvent.click(screen.getByRole('button', { name:/协作进展/ }))
    view.rerender(<LingsiRunOverview run={run} onInspect={() => {}} />)
    expect(screen.getByRole('region', { name:'协作概览' })).toBe(shell)
    expect(screen.getByRole('button', { name:/协作进展/ }).getAttribute('aria-expanded')).toBe('false')
    expect(screen.getByText('1/3')).toBeTruthy()
  })
  it('counts only successful tasks and names the actual upstream dependency', () => {
    const inspect = vi.fn()
    render(<LingsiRunOverview run={run} onInspect={inspect} />)
    expect(screen.getByText('1/3')).toBeTruthy()
    expect(screen.getByText('等待前置任务：撰写活动报道')).toBeTruthy()
    expect(screen.getByText('前置任务已完成：检索历史策划')).toBeTruthy()
    expect(screen.getByText('等待上游')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: /查看待处理事项/ }))
    expect(inspect).toHaveBeenCalledOnce()
  })
  it('preserves explicit collapse across live updates', () => {
    const view = render(<LingsiRunOverview run={run} onInspect={() => {}} />)
    fireEvent.click(screen.getByRole('button', { name: /协作进展/ }))
    view.rerender(<LingsiRunOverview run={{ ...run, status: 'RUNNING' }} onInspect={() => {}} />)
    expect(screen.getByRole('button', { name: /协作进展/ }).getAttribute('aria-expanded')).toBe('false')
  })
  it('keeps failed and partial runs expanded and completed runs compact', () => {
    const view = render(<LingsiRunOverview run={{ ...run, status: 'FAILED' }} onInspect={() => {}} />)
    expect(screen.getByRole('button', { name: /协作进展/ }).getAttribute('aria-expanded')).toBe('true')
    view.rerender(<LingsiRunOverview run={{ ...run, status: 'SUCCESS' }} onInspect={() => {}} />)
    expect(screen.getByRole('button', { name: /协作进展/ }).getAttribute('aria-expanded')).toBe('false')
  })
})
