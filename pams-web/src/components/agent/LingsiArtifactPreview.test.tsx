// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, render, screen } from '@testing-library/react'
import LingsiArtifactPreview from './LingsiArtifactPreview'
import { getAgentArtifact, type AgentArtifactDetail, type AgentArtifactSummary } from '@/api/agent'
import { useAgentPanel } from '@/stores/agentPanel'
vi.mock('@/api/agent', () => ({ getAgentArtifact: vi.fn(), editAgentArtifact: vi.fn(), listAgentArtifacts: vi.fn() }))
vi.mock('./LingsiMarkdown', () => ({ default: ({ text }: { text: string }) => <p>{text}</p> }))
const artifact = (status = 'PENDING_CONFIRM', version = 1, taskVersion = 0): AgentArtifactSummary => ({ id: 1, title: '送审预览', kind: 'TEXT', currentVersion: version, taskId: version, taskVersion, status, toolName: 'prepareArticleSubmission' })
const detail = (value: AgentArtifactSummary, selected = value.currentVersion): AgentArtifactDetail => ({ artifact: value, versions: [{ version: selected, origin: 'AGENT', createdAt: '', taskId: selected }], version: selected, origin: 'AGENT', preview: '确认后提交审核', fields: { content: '正文' }, plan: {}, reviewStatus: 'UNVERIFIED', editable: value.status === 'PENDING_CONFIRM', status: value.status, taskId: selected, artifacts: [], sources: [], sourceNotice: '', issues: [] })
beforeEach(() => { vi.resetAllMocks(); useAgentPanel.getState().closeAll(); useAgentPanel.setState({ drafts: {} }) })
afterEach(cleanup)
describe('成果动作状态', () => {
  it('成功回执到达后，在详情请求完成前撤去旧待提交状态和编辑入口', async () => {
    vi.mocked(getAgentArtifact).mockResolvedValueOnce(detail(artifact()))
    const view = render(<LingsiArtifactPreview artifact={artifact()} onClose={() => {}} />)
    await screen.findByRole('button', { name: '修改正文' })
    let release!: (value: AgentArtifactDetail) => void
    vi.mocked(getAgentArtifact).mockImplementationOnce(() => new Promise(resolve => { release = resolve }))
    view.rerender(<LingsiArtifactPreview artifact={artifact('SUCCESS', 1, 2)} onClose={() => {}} />)
    expect(screen.queryByText('预览，尚未写入')).toBeNull()
    expect(screen.queryByRole('button', { name: '修改正文' })).toBeNull()
    expect(screen.getAllByText('已执行')).toHaveLength(2)
    await act(async () => { release(detail(artifact('SUCCESS', 1, 2))) })
  })
  it('最新版本成功不改变正在阅读的旧版本已取消事实', async () => {
    const previous = detail(artifact('CANCELLED', 2, 1), 1)
    vi.mocked(getAgentArtifact).mockResolvedValue(previous)
    render(<LingsiArtifactPreview artifact={artifact('SUCCESS', 2, 3)} initialVersion={1} onClose={() => {}} />)
    expect(await screen.findAllByText('已失效，未执行')).toHaveLength(2)
    expect(screen.queryByText('已执行')).toBeNull()
  })
})
