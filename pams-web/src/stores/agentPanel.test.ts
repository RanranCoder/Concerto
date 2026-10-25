import { beforeEach,describe,it,expect,vi } from 'vitest'
import { useAgentPanel } from './agentPanel'
import * as api from '@/api/agent'
import type { AgentArtifactSummary } from '@/api/agent'
vi.mock('@/api/agent',()=>({listAgentArtifacts:vi.fn()}))
const summary=(id:number,version=1):AgentArtifactSummary=>({id,currentVersion:version,title:`成果${id}`,kind:'TEXT',taskId:id,status:'PENDING_CONFIRM',toolName:'draftPlanSections'})
beforeEach(()=>{vi.resetAllMocks();useAgentPanel.getState().closeAll();useAgentPanel.setState({drafts:{}})})
describe('成果工作区阅读状态',()=>{
  it('首次产生可读成果时打开，后续更新不会切换正在阅读的版本',async()=>{
    vi.mocked(api.listAgentArtifacts).mockResolvedValue([summary(1)])
    await useAgentPanel.getState().syncConversation(10)
    expect(useAgentPanel.getState().collapsed).toBe(false)
    expect(useAgentPanel.getState().items[0].selectedVersion).toBe(1)
    vi.mocked(api.listAgentArtifacts).mockResolvedValue([summary(1,2),summary(2)])
    await useAgentPanel.getState().syncConversation(10)
    expect(useAgentPanel.getState().activeId).toBe('v:1')
    expect(useAgentPanel.getState().items[0].selectedVersion).toBe(1)
    expect(useAgentPanel.getState().items[0].artifact?.currentVersion).toBe(2)
  })
  it('会话切换后旧的成果索引不能进入新会话',async()=>{
    let release!:(rows:AgentArtifactSummary[])=>void
    vi.mocked(api.listAgentArtifacts).mockImplementationOnce(()=>new Promise(resolve=>release=resolve)).mockResolvedValueOnce([summary(2)])
    const old=useAgentPanel.getState().syncConversation(10)
    await useAgentPanel.getState().syncConversation(20)
    release([summary(1)]);await old
    expect(useAgentPanel.getState().catalog.map(a=>a.id)).toEqual([2])
    expect(useAgentPanel.getState().conversationId).toBe(20)
  })
  it('收起面板或切换会话时保留未提交修改，显式放弃才清除',()=>{
    useAgentPanel.getState().rememberDraft(1,2,{content:'尚未保存'})
    useAgentPanel.getState().closeAll()
    expect(useAgentPanel.getState().drafts[1]).toEqual({version:2,fields:{content:'尚未保存'}})
    useAgentPanel.getState().discardDraft(1)
    expect(useAgentPanel.getState().drafts[1]).toBeUndefined()
  })
  it('稍后返回的较旧成果版本也不能覆盖已知的新版本',async()=>{
    vi.mocked(api.listAgentArtifacts).mockResolvedValue([summary(1,2)])
    await useAgentPanel.getState().syncConversation(10)
    vi.mocked(api.listAgentArtifacts).mockResolvedValue([summary(1,1)])
    await useAgentPanel.getState().syncConversation(10)
    expect(useAgentPanel.getState().catalog[0].currentVersion).toBe(2)
  })
})
