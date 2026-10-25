import { describe,it,expect } from 'vitest'
import { artifactDiff } from './artifactDiff'
import { pendingFormGroups } from './pendingForms'
import type { AgentTaskVO } from '@/api/agent'

describe('成果比较与共同补充信息',()=>{
  it('比较保留完整的新旧文本，并明确新增移除',()=>{
    expect(artifactDiff('相同\n旧日期\n结尾','相同\n新日期\n结尾')).toEqual([
      {kind:'same',text:'相同'},{kind:'removed',text:'旧日期'},{kind:'added',text:'新日期'},{kind:'same',text:'结尾'},
    ])
    expect(artifactDiff('未变化','未变化')).toEqual([{kind:'same',text:'未变化'}])
  })
  const task=(id:number,value:string,formKey='date'):AgentTaskVO=>({id,status:'WAITING_INPUT',summary:`任务${id}`,toolName:'tool',conversationId:1,createdAt:'',confirmedAt:null,finishedAt:null,expired:false,reversible:false,
    form:{taskId:id,formKey,title:'补充日期',fields:[{name:'date',label:'日期',type:'date',required:true}],values:{date:value}}})
  it('相同语义且空预填兼容时合并，并保留影响的每个任务',()=>{
    const groups=pendingFormGroups([task(1,''),task(2,'2026-11-20')])
    expect(groups).toHaveLength(1);expect(groups[0].actions.map(a=>a.id)).toEqual([1,2]);expect(groups[0].form.values.date).toBe('2026-11-20')
  })
  it('不合并冲突预填或语义不同的字段',()=>{
    expect(pendingFormGroups([task(1,'2026-11-20'),task(2,'2026-11-21'),task(3,'2026-11-20','another-scope')])).toHaveLength(3)
  })
})
