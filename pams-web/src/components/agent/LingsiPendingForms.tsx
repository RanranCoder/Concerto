import { useMemo, useState } from 'react'
import type { AgentTaskVO, TaskInputResult } from '@/api/agent'
import { pendingFormGroups, type PendingFormGroup } from '@/utils/pendingForms'
import LingsiFormCard from './LingsiFormCard'

export default function LingsiPendingForms({actions,readonly,busy,onSubmit}:{actions:AgentTaskVO[];readonly?:boolean;busy?:boolean;onSubmit:(id:number,values:Record<string,string>)=>Promise<TaskInputResult>}) {
  const groups=useMemo(()=>pendingFormGroups(actions),[actions])
  const [held,setHeld]=useState<PendingFormGroup[] | null>(null)
  const [drafts,setDrafts]=useState<Record<number,Record<string,string>>>({})
  const [error,setError]=useState('')
  return <>{error && <p role="alert">{error}</p>}{(held ?? groups).map(group=>{
    const form={...group.form,values:{...group.form.values,...drafts[group.actions[0].id]}}
    return <div key={group.actions.map(a=>a.id).join('-')}>
      {group.actions.length>1 && <p>这份信息将用于：{group.actions.map(a=>a.summary).join('、')}</p>}
      <LingsiFormCard form={form} inline readonly={readonly} busy={busy || held!=null} onSubmit={async values=>{
        setHeld(groups);setError('');setDrafts(s=>({...s,...Object.fromEntries(group.actions.map(a=>[a.id,values]))}))
        const failures:string[]=[];let result:TaskInputResult={status:'ERROR'}
        try {
          for(const action of group.actions) {
            try {result=await onSubmit(action.id,values);if(result.status==='ERROR')failures.push(`${action.summary}：${result.error ?? '请核对填写内容'}`)}
            catch {failures.push(`${action.summary}：提交未完成`)}
          }
          if(failures.length){const message=failures.join('；');setError(message);return{status:'ERROR',error:message}}
          return result
        } finally {setHeld(null)}
      }}/>
    </div>
  })}</>
}
