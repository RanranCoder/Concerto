import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as api from '@/api/agent'
import { flushStreamDeltas, useAgentStore } from './agent'
import type { AgentChatResponse, AgentMessageVO } from '@/api/agent'

vi.mock('@/api/agent', () => ({agentChat:vi.fn(),agentAbort:vi.fn(),listConversations:vi.fn(),listMessages:vi.fn()}))
const reply=(id:number,text:string):AgentChatResponse=>({conversationId:id,messageId:id,streamId:`stream-${id}`,reply:text,toolCalls:[],references:[],plan:null,form:null,provider:'stub',degraded:true,durationMs:1,pendingTaskCount:0})
beforeEach(()=>{
  vi.clearAllMocks();useAgentStore.getState().reset()
  vi.mocked(api.listConversations).mockResolvedValue({records:[],total:0,current:1,size:50,pendingTaskCount:0})
})
describe('灵思任务切换与停止',()=>{
  it('首个正文分片先结束当前推理，下一轮仍可独立开始且不逐字重建时间轴',async()=>{
    let finish!:(value:AgentChatResponse)=>void
    vi.mocked(api.agentChat).mockImplementationOnce(()=>new Promise(resolve=>finish=resolve))
    const pending=useAgentStore.getState().sendMessage('测试阶段节奏')
    useAgentStore.setState({activeStreamId:'paced'})
    const apply=useAgentStore.getState().applyStreamEvent
    apply({streamId:'paced',seq:1,type:'THINKING_DELTA',ts:1,payload:{round:0,text:'先核对资料。'}})
    expect(useAgentStore.getState().timeline.at(-1)?.completed).toBe(false)
    apply({streamId:'paced',seq:2,type:'MESSAGE_DELTA',ts:2,payload:{round:0,text:'已核对。'}})
    expect(useAgentStore.getState().timeline.at(-1)?.completed).toBe(true)
    flushStreamDeltas()
    expect(useAgentStore.getState().outcomes[0].reply).toBe('已核对。')
    const sealed=useAgentStore.getState().timeline
    apply({streamId:'paced',seq:3,type:'MESSAGE_DELTA',ts:3,payload:{round:0,text:'继续整理。'}})
    flushStreamDeltas()
    expect(useAgentStore.getState().timeline).toBe(sealed)
    apply({streamId:'paced',seq:4,type:'THINKING_DELTA',ts:4,payload:{round:1,text:'下一轮整理清单。'}})
    expect(useAgentStore.getState().timeline.at(-1)?.completed).toBe(false)
    apply({streamId:'paced',seq:5,type:'MESSAGE_DELTA',ts:5,payload:{round:1,text:'最终结论。'}})
    expect(useAgentStore.getState().timeline.every(item=>item.completed)).toBe(true)
    flushStreamDeltas()
    expect(useAgentStore.getState().outcomes[0].reply).toBe('最终结论。')
    finish({...reply(1,'最终结论。'),streamId:'paced'});await pending
  })
  it('切换正在生成的会话会解除输入锁，旧请求不能回写',async()=>{
    let finish!:(value:AgentChatResponse)=>void
    vi.mocked(api.agentChat).mockImplementationOnce(()=>new Promise(resolve=>finish=resolve))
    const pending=useAgentStore.getState().sendMessage('旧任务')
    useAgentStore.setState({activeStreamId:'old'})
    await useAgentStore.getState().selectConversation(null)
    expect(useAgentStore.getState().thinking).toBe(false)
    expect(useAgentStore.getState().activeStreamId).toBeNull()
    finish(reply(1,'旧结果'));await pending
    expect(useAgentStore.getState().outcomes).toEqual([])
  })
  it('其他流的字符不能清空或追加到当前回复，完成后忽略迟到字符',async()=>{
    let finish!:(value:AgentChatResponse)=>void
    vi.mocked(api.agentChat).mockImplementationOnce(()=>new Promise(resolve=>finish=resolve))
    const pending=useAgentStore.getState().sendMessage('当前问题')
    useAgentStore.setState({activeStreamId:'current'})
    const apply=useAgentStore.getState().applyStreamEvent
    apply({streamId:'other',seq:1,type:'MESSAGE_DELTA',ts:1,payload:{round:0,text:'不该出现'}})
    flushStreamDeltas()
    expect(useAgentStore.getState().outcomes[0].reply).toBe('')
    apply({streamId:'current',seq:2,type:'MESSAGE_DONE',ts:2,payload:{reply:'权威正文'}})
    expect(useAgentStore.getState().outcomes[0].streaming).toBe(false)
    apply({streamId:'current',seq:3,type:'MESSAGE_DELTA',ts:3,payload:{round:1000,text:'迟到'}})
    flushStreamDeltas()
    expect(useAgentStore.getState().outcomes[0].reply).toBe('权威正文')
    finish({...reply(1,'完整结果'),streamId:'current'});await pending
    expect(useAgentStore.getState().outcomes[0].reply).toBe('完整结果')
  })
  it('登出重置后迟到响应不能恢复旧账号会话',async()=>{
    let finish!:(value:AgentChatResponse)=>void
    vi.mocked(api.agentChat).mockImplementationOnce(()=>new Promise(resolve=>finish=resolve))
    const pending=useAgentStore.getState().sendMessage('旧账号问题')
    useAgentStore.getState().reset()
    finish(reply(1,'旧账号结果'));await pending
    expect(useAgentStore.getState().currentConversationId).toBeNull()
    expect(useAgentStore.getState().outcomes).toEqual([])
  })
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
