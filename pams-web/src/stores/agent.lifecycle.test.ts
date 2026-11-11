import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as api from '@/api/agent'
import { flushStreamDeltas, useAgentStore } from './agent'
import type { AgentChatResponse, AgentMessageVO, AgentTaskVO, AgentRunView } from '@/api/agent'

vi.mock('@/api/agent', () => ({agentChat:vi.fn(),agentAbort:vi.fn(),abortAgentRequest:vi.fn(),getAgentRequest:vi.fn(),getAgentRunEvents:vi.fn(),agentStreamSince:vi.fn(),listConversations:vi.fn(),listMessages:vi.fn(),confirmAgentTask:vi.fn(),getAgentRun:vi.fn()}))
const reply=(id:number,text:string):AgentChatResponse=>({conversationId:id,messageId:id,streamId:`stream-${id}`,reply:text,toolCalls:[],references:[],plan:null,form:null,provider:'stub',degraded:true,durationMs:1,pendingTaskCount:0})
beforeEach(()=>{
  vi.resetAllMocks();useAgentStore.getState().reset()
  vi.mocked(api.listConversations).mockResolvedValue({records:[],total:0,current:1,size:50,pendingTaskCount:0})
})
describe('灵思任务切换与停止',()=>{
  it('刷新未完成的会话后恢复同一个请求，并保留补充指令',async()=>{
    vi.mocked(api.listMessages).mockResolvedValue({records:[{
      role:'USER',content:'查询资料',streamId:'steering-stream',activeRequestId:'steering-request',
      instructions:[{id:'steering-input',text:'只查本月'}],instructionsConsumed:1,
    } as AgentMessageVO],total:1,current:1,size:50})
    vi.mocked(api.getAgentRequest).mockResolvedValue({
      requestId:'steering-request',streamId:'steering-stream',conversationId:1,status:'RUNNING',cancelled:false,
    })
    vi.mocked(api.agentStreamSince).mockResolvedValue({streamId:'steering-stream',since:0,events:[]})
    await useAgentStore.getState().selectConversation(1)
    await vi.waitFor(()=>expect(api.agentStreamSince).toHaveBeenCalledWith('steering-stream',0))
    const state=useAgentStore.getState()
    expect(state.thinking).toBe(true)
    expect(state.activeRequestId).toBe('steering-request')
    expect(state.userTexts).toEqual(['查询资料\n\n补充指令：只查本月'])
    expect(api.agentChat).not.toHaveBeenCalled()
  })
  it('聊天补充信息接续同一任务时退休上一轮表单',async()=>{
    vi.mocked(api.agentChat).mockResolvedValueOnce({...reply(1,'请补充地点'),form:{taskId:77,formKey:'createActivity',title:'补充信息',fields:[{name:'location',label:'地点',type:'text',required:true}],values:{}}})
    await useAgentStore.getState().sendMessage('创建培训班')
    expect(useAgentStore.getState().outcomes[0].form?.taskId).toBe(77)
    const plan={planId:77,summary:'创建培训班',total:1,items:[],skipped:[],detailLines:[],reversible:true,confirmToken:'ct',needsConfirm:true}
    vi.mocked(api.agentChat).mockResolvedValueOnce({...reply(1,'信息已补齐'),plan})
    await useAgentStore.getState().sendMessage('地点在会议室')
    expect(useAgentStore.getState().outcomes[0].form).toBeNull()
    expect(useAgentStore.getState().outcomes[1].plan?.planId).toBe(77)
    expect(api.confirmAgentTask).not.toHaveBeenCalled()
  })
  const runView = (): AgentRunView => ({ id:50,streamId:'stream-1',conversationId:1,status:'WAITING_CONFIRM',nodes:[],actions:[7,8].map(id=>({
    id,runId:50,workItemId:id,revision:1,toolName:'createActivity',status:'PENDING_CONFIRM',summary:`清单${id}`,conversationId:1,
    createdAt:'2026-10-24',confirmedAt:null,finishedAt:null,expired:false,reversible:true,
    plan:{planId:id,summary:`清单${id}`,total:1,items:[],skipped:[],detailLines:[],reversible:true,confirmToken:`token-${id}`,needsConfirm:true},
  })) })
  it.each([true,false])('首次协作更新绑定快照，迟到响应不退回旧界面（含旧 run：%s）',async(hasOldRun)=>{
    let finish!:(value:AgentChatResponse)=>void
    vi.mocked(api.agentChat).mockImplementationOnce(()=>new Promise(resolve=>finish=resolve))
    const pending=useAgentStore.getState().sendMessage('准备协作任务')
    const requestId=useAgentStore.getState().activeRequestId!
    useAgentStore.setState({activeStreamId:'stream-1',currentConversationId:1})
    const run={...runView(),stateVersion:8}
    vi.mocked(api.getAgentRequest).mockResolvedValue({requestId,streamId:'stream-1',conversationId:1,status:'WAITING_CONFIRM',cancelled:false,run})
    useAgentStore.getState().applyTaskDone({type:'AGENT_RUN_UPDATED',runId:50,conversationId:2,streamId:'foreign'})
    expect(api.getAgentRequest).not.toHaveBeenCalled()
    useAgentStore.getState().applyTaskDone({type:'AGENT_RUN_UPDATED',runId:50,conversationId:1,streamId:'stream-1'})
    await vi.waitFor(()=>expect(useAgentStore.getState().outcomes[0].run?.stateVersion).toBe(8))
    vi.mocked(api.getAgentRun).mockResolvedValue(run)
    finish({...reply(1,'请确认'),run:hasOldRun?{...run,stateVersion:2,status:'RUNNING'}:undefined})
    await pending
    expect(useAgentStore.getState().outcomes[0].run?.stateVersion).toBe(8)
    expect(useAgentStore.getState().outcomes[0].executionState).toBe('WAITING_CONFIRM')
  })
  it('协作确认后保留独立分支的清单，并在同一条消息刷新续跑结果',async()=>{
    const run=runView()
    vi.mocked(api.getAgentRun).mockResolvedValue(run)
    vi.mocked(api.agentChat).mockResolvedValue({...reply(1,'请核对两份清单'),run})
    await useAgentStore.getState().sendMessage('两项独立任务')
    const settled={...run,actions:run.actions.map(a=>a.id===7?{...a,status:'SUCCESS',plan:null}:a)}
    vi.mocked(api.getAgentRun).mockResolvedValue(settled)
    vi.mocked(api.confirmAgentTask).mockResolvedValue({...settled.actions[0],executionState:'WAITING_CONFIRM'})
    await useAgentStore.getState().confirmPlan(7,'token-7')
    expect(useAgentStore.getState().outcomes).toHaveLength(1)
    expect(useAgentStore.getState().outcomes[0].run?.actions.map(a=>a.status)).toEqual(['SUCCESS','PENDING_CONFIRM'])
    expect(useAgentStore.getState().outcomes[0].run?.actions[1].plan?.planId).toBe(8)
    const finished={...settled,status:'SUCCESS',actions:settled.actions.map(a=>({...a,status:'SUCCESS',plan:null}))}
    vi.mocked(api.getAgentRun).mockResolvedValue(finished)
    useAgentStore.getState().applyTaskDone({type:'AGENT_TASK_DONE',taskId:8,runId:50,status:'SUCCESS'})
    await vi.waitFor(()=>expect(useAgentStore.getState().outcomes[0].run?.status).toBe('SUCCESS'))
    expect(useAgentStore.getState().outcomes).toHaveLength(1)
  })
  it('恢复历史时保留同一轮的全部协作清单',async()=>{
    const run=runView()
    vi.mocked(api.listMessages).mockResolvedValue({records:[{id:1,role:'ASSISTANT',content:'历史协作',run} as AgentMessageVO],total:1,current:1,size:50})
    await useAgentStore.getState().selectConversation(1)
    expect(useAgentStore.getState().outcomes[0].run?.actions).toHaveLength(2)
    expect(useAgentStore.getState().outcomes[0].run?.actions.every(a=>a.plan?.confirmToken)).toBe(true)
  })
  it('同一任务的迟到快照不能覆盖后返回的新状态',async()=>{
    const run=runView()
    vi.mocked(api.listMessages).mockResolvedValue({records:[{id:1,role:'ASSISTANT',content:'协作',run} as AgentMessageVO],total:1,current:1,size:50})
    await useAgentStore.getState().selectConversation(1)
    let finish!:(value:AgentRunView)=>void
    vi.mocked(api.getAgentRun).mockImplementationOnce(()=>new Promise(resolve=>finish=resolve)).mockResolvedValueOnce({...run,status:'SUCCESS'})
    const old=useAgentStore.getState().refreshRun(50)
    await useAgentStore.getState().refreshRun(50)
    finish(run);await old
    expect(useAgentStore.getState().outcomes[0].run?.status).toBe('SUCCESS')
  })
  it('切换会话后迟到的续跑状态不能污染当前消息',async()=>{
    const run=runView()
    vi.mocked(api.listMessages).mockResolvedValue({records:[{id:1,role:'ASSISTANT',content:'协作',run} as AgentMessageVO],total:1,current:1,size:50})
    await useAgentStore.getState().selectConversation(1)
    let finish!:(value:AgentRunView)=>void
    vi.mocked(api.getAgentRun).mockImplementationOnce(()=>new Promise(resolve=>finish=resolve))
    const pending=useAgentStore.getState().refreshRun(50)
    await useAgentStore.getState().selectConversation(null)
    finish({...run,status:'SUCCESS'});await pending
    expect(useAgentStore.getState().outcomes).toEqual([])
  })
  it('确认的 WS 和 REST 乱序返回保留整体部分完成状态，不重复扣减待确认数量',async()=>{
    const plan={planId:7,summary:'保存草稿',total:1,items:[],skipped:[],detailLines:[],reversible:false,confirmToken:'token',needsConfirm:true}
    vi.mocked(api.listConversations).mockResolvedValue({records:[],total:0,current:1,size:50,pendingTaskCount:3})
    vi.mocked(api.agentChat).mockResolvedValueOnce({...reply(1,'请核对预览'),plan,executionState:'WAITING_CONFIRM',pendingTaskCount:3})
    await useAgentStore.getState().sendMessage('准备方案和稿件')
    vi.mocked(api.listConversations).mockResolvedValue({records:[],total:0,current:1,size:50,pendingTaskCount:2})
    let finish!:(task:AgentTaskVO)=>void
    vi.mocked(api.confirmAgentTask).mockImplementationOnce(()=>new Promise(resolve=>finish=resolve))
    const confirming=useAgentStore.getState().confirmPlan(7,'token')
    useAgentStore.getState().applyTaskDone({taskId:7,status:'SUCCESS',conversationId:1,executionState:'PARTIAL',message:'当前草稿已保存'})
    finish({id:7,toolName:'draftArticleFromActivity',status:'SUCCESS',summary:plan.summary,executionState:'PARTIAL',
      conversationId:1,createdAt:'2026-10-24',confirmedAt:null,finishedAt:null,expired:false,reversible:false,result:{message:'当前草稿已保存'}})
    await confirming
    expect(useAgentStore.getState().outcomes).toHaveLength(1)
    expect(useAgentStore.getState().outcomes[0].executionState).toBe('PARTIAL')
    expect(useAgentStore.getState().outcomes[0].plan).toBeNull()
    expect(useAgentStore.getState().pendingTaskCount).toBe(2)
  })
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
    vi.mocked(api.abortAgentRequest).mockResolvedValue({status:'STOPPING',aborted:true})
    vi.mocked(api.getAgentRequest).mockImplementation(async id=>({requestId:id,streamId:'stopped-stream',conversationId:null,status:'CANCELLED',cancelled:true}))
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

  it('首个事件前可停止，请求确认前保持正在停止',async()=>{
    let finish!:(value:AgentChatResponse)=>void
    vi.mocked(api.agentChat).mockImplementationOnce(()=>new Promise(resolve=>finish=resolve))
    const pending=useAgentStore.getState().sendMessage('首帧前停止')
    const requestId=useAgentStore.getState().activeRequestId!
    vi.mocked(api.abortAgentRequest).mockResolvedValue({status:'STOPPING',aborted:true})
    vi.mocked(api.getAgentRequest).mockResolvedValue({requestId,streamId:null,conversationId:null,status:'STOPPING',cancelled:true})
    await useAgentStore.getState().stop()
    expect(api.abortAgentRequest).toHaveBeenCalledWith(requestId)
    expect(useAgentStore.getState().stopping).toBe(true)
    expect(useAgentStore.getState().outcomes[0].status).toBeUndefined()
    vi.mocked(api.getAgentRequest).mockResolvedValue({requestId,streamId:'closed',conversationId:null,status:'CANCELLED',cancelled:true})
    await useAgentStore.getState().reconcileConnection()
    expect(useAgentStore.getState().stopping).toBe(false)
    expect(useAgentStore.getState().outcomes[0].status).toBe('stopped')
    finish(reply(1,'不应回流'));await pending
    expect(useAgentStore.getState().outcomes[0].reply).not.toContain('不应回流')
  })
  it('首个事件按请求隔离，重复分片只拼一次，任务正文不写最终答复',async()=>{
    let finish!:(value:AgentChatResponse)=>void
    vi.mocked(api.agentChat).mockImplementationOnce(()=>new Promise(resolve=>finish=resolve))
    const pending=useAgentStore.getState().sendMessage('隔离请求')
    const requestId=useAgentStore.getState().activeRequestId!
    const event={streamId:'owned',seq:1,type:'MESSAGE_DELTA' as const,ts:1,payload:{requestId,protocolVersion:2,round:0,text:'正确正文'}}
    useAgentStore.getState().applyStreamEvent({...event,streamId:'foreign',payload:{...event.payload,requestId:'another-window'}})
    useAgentStore.getState().applyStreamEvent(event)
    useAgentStore.getState().applyStreamEvent(event)
    useAgentStore.getState().applyStreamEvent({...event,seq:2,payload:{...event.payload,channel:'task',text:'专家内部草稿'}})
    flushStreamDeltas()
    expect(useAgentStore.getState().outcomes[0].reply).toBe('正确正文')
    finish({...reply(1,'正确正文'),requestId});await pending
  })
  it('较低的服务端版本即使后发请求返回也不能回滚快照',async()=>{
    const run={...runView(),stateVersion:20,status:'SUCCESS'}
    vi.mocked(api.listMessages).mockResolvedValue({records:[{id:1,role:'ASSISTANT',content:'成果',run} as AgentMessageVO],total:1,current:1,size:50})
    await useAgentStore.getState().selectConversation(1)
    vi.mocked(api.getAgentRun).mockResolvedValue({...run,status:'RUNNING',stateVersion:19})
    await useAgentStore.getState().refreshRun(50)
    expect(useAgentStore.getState().outcomes[0].run?.status).toBe('SUCCESS')
    expect(useAgentStore.getState().outcomes[0].run?.stateVersion).toBe(20)
  })
  it('缓存丢失时重连从消息快照恢复完整正文与清单',async()=>{
    let finish!:(value:AgentChatResponse)=>void
    vi.mocked(api.agentChat).mockImplementationOnce(()=>new Promise(resolve=>finish=resolve))
    const pending=useAgentStore.getState().sendMessage('恢复请求')
    const requestId=useAgentStore.getState().activeRequestId!
    vi.mocked(api.getAgentRequest).mockResolvedValue({requestId,streamId:'recovered',conversationId:1,status:'SUCCESS',cancelled:false,
      message:{id:10,role:'ASSISTANT',content:'恢复后的完整结果',streamId:'recovered',plan:runView().actions[0].plan} as AgentMessageVO})
    await useAgentStore.getState().reconcileConnection()
    expect(useAgentStore.getState().outcomes[0].reply).toBe('恢复后的完整结果')
    expect(useAgentStore.getState().outcomes[0].plan?.planId).toBe(7)
    expect(useAgentStore.getState().thinking).toBe(false)
    finish({...reply(1,'恢复后的完整结果'),requestId});await pending
  })
  it('乱序的正文分片按序号复原，已经显示过的分片不会重复',async()=>{
    let finish!:(value:AgentChatResponse)=>void
    vi.mocked(api.agentChat).mockImplementationOnce(()=>new Promise(resolve=>finish=resolve))
    const pending=useAgentStore.getState().sendMessage('乱序分片')
    const requestId=useAgentStore.getState().activeRequestId!
    const event=(seq:number,text:string)=>({streamId:'ordered',seq,type:'MESSAGE_DELTA' as const,ts:seq,payload:{requestId,round:0,text}})
    useAgentStore.getState().applyStreamEvent(event(2,'后半'));flushStreamDeltas()
    useAgentStore.getState().applyStreamEvent(event(1,'前半'));flushStreamDeltas()
    useAgentStore.getState().applyStreamEvent(event(2,'后半'));flushStreamDeltas()
    expect(useAgentStore.getState().outcomes[0].reply).toBe('前半后半')
    finish({...reply(1,'前半后半'),requestId});await pending
  })
})
