import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as api from '@/api/agent'
import { useAgentStore } from './agent'
import type { AgentChatResponse, AgentMessageVO } from '@/api/agent'

vi.mock('@/api/agent', () => ({agentChat:vi.fn(),agentAbort:vi.fn(),listConversations:vi.fn(),listMessages:vi.fn()}))
const reply=(id:number,text:string):AgentChatResponse=>({conversationId:id,messageId:id,streamId:`stream-${id}`,reply:text,toolCalls:[],references:[],plan:null,form:null,provider:'stub',degraded:true,durationMs:1,pendingTaskCount:0})
beforeEach(()=>{
  vi.clearAllMocks();useAgentStore.getState().reset()
  vi.mocked(api.listConversations).mockResolvedValue({records:[],total:0,current:1,size:50,pendingTaskCount:0})
})
describe('灵思任务切换与停止',()=>{
  it('迟到的历史请求不能覆盖后来选中的任务',async()=>{
    let finishFirst!:(value:Awaited<ReturnType<typeof api.listMessages>>)=>void
    vi.mocked(api.listMessages).mockImplementationOnce(()=>new Promise(resolve=>finishFirst=resolve))
      .mockResolvedValueOnce({records:[{role:'ASSISTANT',content:'第二项任务',id:2} as AgentMessageVO],total:1,current:1,size:50})
    const first=useAgentStore.getState().selectConversation(1)
    await useAgentStore.getState().selectConversation(2)
    finishFirst({records:[{role:'ASSISTANT',content:'迟到的第一项',id:1} as AgentMessageVO],total:1,current:1,size:50})
    await first
    expect(useAgentStore.getState().currentConversationId).toBe(2)
    expect(useAgentStore.getState().outcomes[0].reply).toBe('第二项任务')
  })
  it('停止后迟到的响应和事件不污染新任务',async()=>{
    let finishOld!:(value:AgentChatResponse)=>void
    vi.mocked(api.agentChat).mockImplementationOnce(()=>new Promise(resolve=>finishOld=resolve)).mockResolvedValueOnce(reply(2,'新任务的结果'))
    vi.mocked(api.agentAbort).mockResolvedValue({streamId:'stopped-stream',aborted:true})
    const old=useAgentStore.getState().sendMessage('旧任务')
    useAgentStore.setState({activeStreamId:'stopped-stream'})
    await useAgentStore.getState().stop()
    expect(useAgentStore.getState().outcomes[0].status).toBe('stopped')
    await useAgentStore.getState().selectConversation(null)
    await useAgentStore.getState().sendMessage('新任务')
    finishOld(reply(1,'迟到的旧结果'));await old
    useAgentStore.getState().applyStreamEvent({streamId:'stopped-stream',seq:1,type:'THINKING',ts:1,payload:{note:'旧事件'}})
    expect(useAgentStore.getState().currentConversationId).toBe(2)
    expect(useAgentStore.getState().outcomes[0].reply).toBe('新任务的结果')
    expect(useAgentStore.getState().timeline).toHaveLength(0)
  })
  it('请求失败不能被标成已完成',async()=>{
    vi.mocked(api.agentChat).mockRejectedValue(new Error('offline'))
    await useAgentStore.getState().sendMessage('测试任务')
    expect(useAgentStore.getState().outcomes[0].status).toBe('failed')
    expect(useAgentStore.getState().thinking).toBe(false)
  })
})
