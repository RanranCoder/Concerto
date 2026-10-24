import { useEffect, useState } from 'react'
import { Drawer } from 'antd'
import { useNavigate } from 'react-router-dom'
import { useAgentPanel, type PanelItem } from '@/stores/agentPanel'
import { useAgentStore } from '@/stores/agent'
import { useMediaQuery } from '@/hooks/useMediaQuery'
import { stripArgsMarker } from '@/utils/markdown'
import { citationSnippet } from '@/utils/citationSnippet'
import LingsiMarkdown from './LingsiMarkdown'
import LingsiSources from './LingsiSources'
import { Close } from './LingsiIcons'

import LingsiCopyButton from './LingsiCopyButton'

export default function LingsiEntityPanel() {
  const {items, activeId, collapsed, activate, close, setCollapsed} = useAgentPanel()
  const narrow = useMediaQuery('(max-width: 1250px)')
  const [tab, setTab] = useState('content')
  const active = items.find(it => it.id === activeId) ?? items.at(-1)
  const outcomes = useAgentStore(s => s.outcomes)
  const result = outcomes.find(o => o.notice?.taskId === active?.plan?.planId)?.notice
  const navigate = useNavigate()
  useEffect(() => setTab('content'), [activeId])
  if (!active || collapsed) return null
  const tabs = [['content','内容预览'],['changes','本次变更'],['sources','参考资料']]
  const showBusinessPage = (item: PanelItem) => { if (item.path) { setCollapsed(true); navigate(item.path) } }
  const content = <div className="lingsi-v6-panel-content">
    <header className="lingsi-preview-header"><div><h2>{active.title}</h2><small>与对话对照核对</small></div><button type="button" className="lingsi-icon-btn" aria-label="关闭工作成果预览" onClick={() => setCollapsed(true)}><Close size={16}/></button></header>
    {items.length > 1 && <div className="lingsi-preview-items">{items.map(it => <div key={it.id} className={active.id===it.id?'active':''}><button type="button" onClick={() => activate(it.id)} aria-pressed={active.id===it.id}>{it.title}</button><button type="button" aria-label={`关闭预览：${it.title}`} onClick={() => close(it.id)}><Close size={11}/></button></div>)}</div>}
    <div className="lingsi-preview-tabs" role="tablist" aria-label="成果内容">{tabs.map(([key,title],index) => <button type="button" key={key} id={`lingsi-preview-tab-${key}`} role="tab" aria-selected={tab===key} aria-controls="lingsi-preview-content" tabIndex={tab===key?0:-1} onClick={() => setTab(key)} onKeyDown={e => {
      let next = index
      if(e.key==='ArrowRight') next=(index+1)%3; else if(e.key==='ArrowLeft') next=(index+2)%3; else if(e.key==='Home') next=0; else if(e.key==='End') next=2; else return
      e.preventDefault();setTab(tabs[next][0]);document.getElementById(`lingsi-preview-tab-${tabs[next][0]}`)?.focus()
    }}>{title}</button>)}</div>
    <div className="lingsi-preview-scroll" id="lingsi-preview-content" role="tabpanel" aria-labelledby={`lingsi-preview-tab-${tab}`} tabIndex={0}>
      {tab==='sources' ? active.source || active.citations?.length ? <LingsiSources citations={active.citations?.length?active.citations:[active.source!]}/> : <div className="lingsi-preview-empty">本次结果没有返回资料出处。请根据业务资料核对内容。</div> : tab==='changes' ? active.plan ? <><h3>{active.plan.summary}</h3><p>{result?.text || `待确认 / ${active.plan.total} 项`}</p><ul className="lingsi-preview-change-list">{active.plan.items.map((it,i) => <li key={i}><strong>{it.label}</strong><span>{it.sub}</span></li>)}</ul><p>{stripArgsMarker(active.plan.detailLines).join('\n')}</p>{active.plan.skipped.length>0 && <div className="lingsi-preserved-skips"><strong>未执行与待补事项</strong>{active.plan.skipped.map((s,i) => <p key={i}>{s.label}：{s.reason}</p>)}</div>}<p className="lingsi-preview-empty">确认入口保留在对话中。</p></> : <div className="lingsi-preview-empty">{active.kind==='artifact'?'已产生业务变更，完整详情请打开业务页面查看。':'这是一份资料片段，没有待执行变更。'}</div> : active.kind==='text' ? <>
        <div className="lingsi-preview-document-meta"><span>{active.source ? '资料片段' : result ? '已处理' : '待核对内容'}</span><LingsiCopyButton key={active.id} text={active.text || ''} label="复制预览全文" /></div>
        {active.source && <div className="lingsi-preview-empty">{active.source.sourceTitle}{active.source.path?` / ${active.source.path}`:''}{active.source.asOf?` / 数据截至 ${active.source.asOf}`:''}</div>}
        <article className="lingsi-preview-document"><LingsiMarkdown text={active.source ? citationSnippet({ ...active.source, snippet: active.text || '' }) : active.text || ''}/></article>
        {active.source?.link && <a href={active.source.link}>打开资料原文</a>}
      </> : <div className="lingsi-preview-artifact"><h2>{active.name}</h2><p>该成果已写入业务系统。请在对应页面查看完整内容与状态。</p>{active.path ? <button type="button" className="lingsi-business-link" onClick={() => showBusinessPage(active)}>打开完整页面</button> : <p>没有可跳转的业务页面。</p>}</div>}
    </div>
    <footer className="lingsi-preview-footer"><span>{active.source?'引用资料请核对适用范围':result?.text || (active.kind==='artifact'?'已产生业务成果':'尚未执行 / 请在对话中确认')}</span><button type="button" onClick={() => setCollapsed(true)}>回到对话</button></footer>
  </div>
  return narrow ? <Drawer className="lingsi-v6-preview-drawer" open={!collapsed} onClose={() => setCollapsed(true)} placement="right" width="min(450px, 100vw)" closable={false} styles={{body:{padding:0}}}>{content}</Drawer> : <aside className="lingsi-panel lingsi-v6-panel" aria-label="工作成果预览">{content}</aside>
}
