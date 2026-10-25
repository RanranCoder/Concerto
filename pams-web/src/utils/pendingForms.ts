import type { AgentTaskVO, AgentInputForm } from '@/api/agent'

export interface PendingFormGroup { actions: AgentTaskVO[]; form: AgentInputForm }
/** Merge only identical field semantics with compatible prefills; conflicting scopes remain separate. */
export function pendingFormGroups(actions:AgentTaskVO[]):PendingFormGroup[] {
  const groups:PendingFormGroup[]=[]
  for(const action of actions) {
    if(action.status!=='WAITING_INPUT' || !action.form)continue
    const form=action.form
    const schema=JSON.stringify([form.formKey,form.fields])
    const target=groups.find(g=>JSON.stringify([g.form.formKey,g.form.fields])===schema && form.fields.every(f=>{
      const a=g.form.values[f.name],b=form.values[f.name]
      return a==null || a==='' || b==null || b==='' || String(a)===String(b)
    }))
    if(!target){groups.push({actions:[action],form:{...form,values:{...form.values}}});continue}
    target.actions.push(action)
    for(const field of form.fields)if(!target.form.values[field.name])target.form.values[field.name]=form.values[field.name]
  }
  return groups
}
