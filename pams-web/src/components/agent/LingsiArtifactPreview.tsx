import { useEffect, useRef, useState } from 'react'
import { getAgentArtifact, editAgentArtifact, type AgentArtifactDetail, type AgentArtifactSummary } from '@/api/agent'
import { useAgentStore } from '@/stores/agent'
import { useAgentPanel } from '@/stores/agentPanel'
import { agentBusinessLink } from '@/utils/agentLinks'
import { artifactDiff } from '@/utils/artifactDiff'
import LingsiMarkdown from './LingsiMarkdown'
import LingsiCopyButton from './LingsiCopyButton'
import { Close } from './LingsiIcons'

const fieldNames:Record<string,string>={documentBody:'策划书全文',title:'标题',content:'正文',background:'活动背景',purpose:'活动目的',notice:'注意事项',emergency:'应急预案',budget:'经费预算',flow:'活动流程'}
const statusNames:Record<string,string>={PENDING_CONFIRM:'预览，尚未写入',REVIEW_REQUIRED:'需修订',SUCCESS:'已执行',PARTIAL:'部分完成',FAILED:'执行失败',CANCELLED:'已失效，未执行',RUNNING:'正在提交'}
export default function LingsiArtifactPreview({artifact,initialVersion,onClose}:{artifact:AgentArtifactSummary;initialVersion?:number;onClose:()=>void}) {
  const cached=useAgentPanel.getState().drafts[artifact.id]
  const [selected,setSelected]=useState(cached?.version ?? initialVersion ?? artifact.currentVersion)
  const [detail,setDetail]=useState<AgentArtifactDetail | null>(null)
  const [previous,setPrevious]=useState<AgentArtifactDetail | null>(null)
  const [tab,setTab]=useState('content')
  const [editing,setEditing]=useState(Boolean(cached))
  const [fields,setFields]=useState<Record<string,string>>(cached?.fields ?? {})
  const [pendingVersion,setPendingVersion]=useState<number | null>(null)
  const [error,setError]=useState('')
  const [loading,setLoading]=useState(false)
  const [reload,setReload]=useState(0)
  const [saving,setSaving]=useState(false)
  const request=useRef(0)
  const acceptedSave=useRef<number | null>(null)
  const mode=useAgentStore(s=>s.mode)
  const catalog=useAgentPanel(s=>s.catalog)
  const editorRef=useRef<HTMLTextAreaElement>(null)
  const changeVersion=(version:number)=>{if(editing){setPendingVersion(version);return}setSelected(version)}
  useEffect(()=>{if(initialVersion===acceptedSave.current){acceptedSave.current=null;return}if(initialVersion!=null && initialVersion!==selected){if(editing)setPendingVersion(initialVersion);else setSelected(initialVersion)}},[initialVersion])
  useEffect(()=>{
    const sequence=++request.current;setLoading(true);setError('');setDetail(value=>value?.version===selected?value:null)
    getAgentArtifact(artifact.id,selected).then(value=>{if(sequence===request.current){setDetail(value);if(!editing)setFields(value.fields)}})
      .catch(()=>{if(sequence===request.current){setError('无法加载成果，请刷新重试。');setDetail(null)}})
      .finally(()=>{if(sequence===request.current)setLoading(false)})
    return ()=>{request.current++}
  },[artifact.id,selected,artifact.status,artifact.taskId,artifact.taskVersion,artifact.currentVersion,reload]) // Keep a reader on the selected version when a new version arrives.
  useEffect(()=>{
    let stale=false;setPrevious(null)
    if(tab==='changes' && selected>1)getAgentArtifact(artifact.id,selected-1).then(v=>{if(!stale)setPrevious(v)}).catch(()=>{if(!stale)setError('前一版本未能加载，请重试。')})
    return ()=>{stale=true}
  },[artifact.id,selected,tab])
  useEffect(()=>{if(editing)editorRef.current?.focus()},[editing])
  const update=(key:string,value:string)=>{
    const next={...fields,[key]:value};setFields(next);useAgentPanel.getState().rememberDraft(artifact.id,selected,next)
  }
  const save=async()=>{
    if(!detail)return
    setSaving(true);setError('')
    const sequence=request.current
    try {
      const result=await editAgentArtifact(artifact.id,selected,fields,mode)
      useAgentPanel.getState().discardDraft(artifact.id)
      if(sequence!==request.current)return
      useAgentStore.getState().applyArtifactEdit(detail.taskId,result)
      acceptedSave.current=result.detail.version
      setPendingVersion(null)
      useAgentPanel.getState().openVersion(result.detail.artifact)
      setDetail(result.detail);setSelected(result.detail.version);setEditing(false);setFields(result.detail.fields)
      if(useAgentPanel.getState().conversationId!=null)void useAgentPanel.getState().syncConversation(useAgentPanel.getState().conversationId!)
    } catch {if(sequence===request.current)setError('保存未完成，修改已保留。若已有新版本，请先对比再重新修改。')}
    finally {setSaving(false)}
  }
  const current=detail?.version===artifact.currentVersion
  // A newer server catalog receipt must supersede a cached pending detail while it reloads.
  // Historical selected versions keep their own action state, even if the latest version succeeded.
  const catalogAuthoritative=selected===artifact.currentVersion && (!detail || artifact.taskVersion!=null && artifact.taskVersion>=(detail.artifact.taskVersion ?? 0))
  const lifecycle=catalogAuthoritative?artifact.status:detail?.status
  const editable=Boolean(detail?.editable && current && !loading && ['PENDING_CONFIRM','REVIEW_REQUIRED'].includes(lifecycle ?? ''))
  const status=lifecycle==='SUCCESS' && ['draftPlanSections','draftArticleFromActivity'].includes(artifact.toolName)?'已保存业务草稿'
    : lifecycle==='SUCCESS' && artifact.toolName==='publishAnnouncement'?'已发布':statusNames[lifecycle ?? ''] ?? ''
  const tabs=[['content','内容预览'],['changes','本次变更'],['sources','参考资料']]
  return <div className="lingsi-v6-panel-content">
    <header className="lingsi-preview-header"><h2>{artifact.title}</h2><button type="button" className="lingsi-icon-btn" aria-label="关闭工作成果预览" onClick={onClose}><Close size={16}/></button></header>
    {catalog.length>1 && <label className="lingsi-artifact-picker">工作成果<select aria-label="工作成果" value={artifact.id} onChange={e=>{
      const next=catalog.find(a=>a.id===Number(e.target.value));if(next)useAgentPanel.getState().openVersion(next)
    }}>{catalog.map(a=><option key={a.id} value={a.id}>{a.title}（第 {a.currentVersion} 版）</option>)}</select></label>}
    <div className="lingsi-artifact-version-bar">
      <label>成果版本 <select aria-label="成果版本" value={selected} disabled={saving} onChange={e=>changeVersion(Number(e.target.value))}>
        {[...new Set([artifact.currentVersion,...detail?.versions.map(v=>v.version) ?? [selected]])].sort((a,b)=>b-a).map(v=><option key={v} value={v}>第 {v} 版{v===artifact.currentVersion?'（最新）':''}</option>)}
      </select></label><span>{status}</span>
      {selected<artifact.currentVersion && <button type="button" onClick={()=>changeVersion(artifact.currentVersion)}>查看第 {artifact.currentVersion} 版更新</button>}
    </div>
    <div className="lingsi-preview-tabs" role="tablist" aria-label="成果内容">{tabs.map(([key,label],i)=><button key={key} type="button" role="tab" id={`artifact-${artifact.id}-${key}`} aria-selected={tab===key} tabIndex={tab===key?0:-1} aria-controls={`artifact-body-${artifact.id}`} onClick={()=>setTab(key)} onKeyDown={e=>{
      const next=e.key==='ArrowRight'?(i+1)%3:e.key==='ArrowLeft'?(i+2)%3:e.key==='Home'?0:e.key==='End'?2:-1
      if(next<0)return;e.preventDefault();setTab(tabs[next][0]);document.getElementById(`artifact-${artifact.id}-${tabs[next][0]}`)?.focus()
    }}>{label}</button>)}</div>
    <div className="lingsi-preview-scroll" id={`artifact-body-${artifact.id}`} role="tabpanel" aria-labelledby={`artifact-${artifact.id}-${tab}`}>
      {loading && <p role="status">正在加载成果…</p>}
      {error && <p role="alert">{error}{!detail && <button type="button" onClick={()=>setReload(v=>v+1)}>重新加载成果</button>}</p>}
      {pendingVersion!=null && <div className="lingsi-unsaved-prompt"><p>有未保存的修改。</p><button type="button" onClick={()=>{useAgentPanel.getState().discardDraft(artifact.id);setEditing(false);setSelected(pendingVersion);setPendingVersion(null)}}>放弃修改并切换</button><button type="button" onClick={()=>setPendingVersion(null)}>继续编辑</button></div>}
      {detail && tab==='content' && <>
        {!current && <p className="lingsi-preview-empty">正在查看历史版本，确认入口仅对应最新有效版本。</p>}
        {editing ? <form onSubmit={e=>{e.preventDefault();void save()}}>
          <p>修改暂存在当前页面，保存后生成新预览，仍需确认才会写入业务系统。</p>
          {Object.entries(fields).map(([key,value],i)=><label className="lingsi-draft-field" key={key}>{fieldNames[key] ?? key}<textarea ref={i===0?editorRef:undefined} value={value} disabled={saving} maxLength={key==='title'?200:24000} onChange={e=>update(key,e.target.value)} /></label>)}
          <div className="lingsi-draft-actions"><button type="button" disabled={saving} onClick={()=>{useAgentPanel.getState().discardDraft(artifact.id);setFields(detail.fields);setEditing(false)}}>放弃修改</button><button type="submit" disabled={saving || mode!=='standard' || !editable}>{saving?'正在复核…':'保存新版本并复核'}</button></div>
        </form> : <>
          {detail.preview && <><div className="lingsi-preview-document-meta"><span>第 {selected} 版</span><LingsiCopyButton text={detail.preview} label="复制预览全文"/></div><article className="lingsi-preview-document"><LingsiMarkdown text={detail.preview}/></article></>}
          {!detail.preview && <table className="lingsi-artifact-table"><thead><tr><th>对象</th><th>变更内容</th></tr></thead><tbody>{detail.plan.items?.map((row,i)=><tr key={i}><th>{row.label}</th><td>{row.sub}</td></tr>)}</tbody></table>}
          {editable && <button type="button" disabled={mode!=='standard'} onClick={()=>{setFields(detail.fields);setEditing(true);useAgentPanel.getState().rememberDraft(artifact.id,selected,detail.fields)}}>修改正文</button>}
        </>}
        {detail.issues?.length>0 && <section className="lingsi-artifact-issues" aria-label="本版审查问题"><h3>需核对的问题</h3>{detail.issues.map(i=><div key={i.id}><p>{i.location}</p><blockquote>{i.quote}</blockquote><p>{i.reason}</p></div>)}</section>}
        {detail.reviewStatus==='UNVERIFIED' && <p className="lingsi-preview-empty">本版尚未完成自动核验，请人工核对。</p>}
      </>}
      {detail && tab==='changes' && <>
        {selected>1 ? previous ? <><h3>与第 {selected-1} 版对比</h3>{artifactDiff(previous.preview ?? '',detail.preview ?? '').map((row,i)=><div key={i} className={`lingsi-version-diff lingsi-version-diff-${row.kind}`}><span>{row.kind==='added'?'新增':row.kind==='removed'?'移除':'保留'}</span><pre>{row.text}</pre></div>)}</> : <p>正在加载前一版本…</p> : <p>这是首个成果版本。</p>}
        <h3>业务变更范围</h3><ul>{detail.plan.items?.map((row,i)=><li key={i}>{row.label}：{row.sub}</li>)}</ul>
        {!!detail.plan.skipped?.length && <section className="lingsi-preserved-skips"><strong>未执行与待补事项</strong>{detail.plan.skipped.map((row,i)=><p key={i}>{row.label}：{row.reason}</p>)}</section>}
      </>}
      {detail && tab==='sources' && <><p>{detail.sourceNotice}</p>{detail.sources.map((source,i)=><section key={i}><h3>{source.label}</h3>{source.text && <p>{source.text}</p>}{source.artifactId!=null && <button type="button" onClick={()=>{
        getAgentArtifact(source.artifactId!,source.version).then(value=>useAgentPanel.getState().openVersion(value.artifact,source.version)).catch(()=>setError('来源暂不可访问。'))
      }}>{source.title}，第 {source.version} 版</button>}</section>)}</>}
      {detail?.artifacts?.map((a,i)=>a.path?<a className="lingsi-business-link" key={i} href={agentBusinessLink(a.path)!}>打开{a.name}</a>:null)}
    </div>
    <footer className="lingsi-preview-footer"><span>{editing?'有未保存的修改':status || '请核对内容和变更范围'}</span><button type="button" onClick={onClose}>回到对话</button></footer>
  </div>
}
