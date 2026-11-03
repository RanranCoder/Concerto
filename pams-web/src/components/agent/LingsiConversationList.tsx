import { useState } from 'react'
import { Input, Popconfirm, Spin } from 'antd'
import type { AgentConversationStatus, AgentConversationVO } from '@/api/agent'
import { agentStatusLabels, conversationStatus, isCompleted, taskDateGroup } from '@/utils/agentTask'
import { Book, Close, Plus, Search, Sparkles } from './LingsiIcons'
import { useAuthStore } from '@/stores/auth'
import { useOrgStore } from '@/stores/org'

interface Props {
  list: AgentConversationVO[]
  loading: boolean
  currentId: number | null
  pendingTaskCount: number
  currentStatus?: AgentConversationStatus
  busy?: boolean
  onNew: () => void
  onSelect: (id: number) => void
  onDelete: (id: number) => void
  onOpenTools: () => void
  onNavigate?: () => void
}
export default function LingsiConversationList({list, loading, currentId, currentStatus, busy, pendingTaskCount, onNew, onSelect, onDelete, onOpenTools, onNavigate}: Props) {
  const [query, setQuery] = useState('')
  const [filter, setFilter] = useState('all')
  const user = useAuthStore(s => s.user)
  const orgName = useOrgStore(s => s.orgName)
  const getStatus = (c: AgentConversationVO) => c.id === currentId && currentStatus ? currentStatus : conversationStatus(c)
  const filtered = list.filter(c => (c.title || '未命名任务').toLowerCase().includes(query.trim().toLowerCase())
    && (filter === 'all' || (filter === 'pending' ? getStatus(c) === 'PENDING_CONFIRM' : isCompleted(getStatus(c)))))
  const navigate = (id?: number) => { if (busy) return; id == null ? onNew() : onSelect(id); onNavigate?.() }
  return <aside className="lingsi-sider" aria-label="concerto任务列表">
    <div className="lingsi-sider-head"><span className="lingsi-sider-title"><span className="lingsi-v6-ai"><Sparkles size={18}/></span>concerto</span><small>Agent</small></div>
    <button type="button" className="lingsi-new-task" disabled={busy} onClick={() => navigate()}><Plus size={15}/>新建任务</button>
    <Input className="lingsi-sider-search" aria-label="搜索任务" placeholder="搜索任务" prefix={<Search size={14}/>} value={query} allowClear onChange={e => setQuery(e.target.value)}/>
    <div className="lingsi-task-filters" role="group" aria-label="任务状态筛选">
      {([['all', '全部'], ['pending', '待确认'], ['completed', '已完成']] as const).map(([key, text]) => <button type="button" key={key} aria-pressed={filter===key} onClick={() => setFilter(key)}>{text}{key==='pending' && pendingTaskCount>0 ? ` ${pendingTaskCount}` : ''}</button>)}
    </div>
    <div className="lingsi-sider-list">
      {loading ? <div className="lingsi-sider-loading"><Spin size="small"/></div> : filtered.length === 0 ? <div className="lingsi-task-empty">{list.length === 0 ? '还没有任务，从新建任务开始。' : '没有匹配的任务。'}{list.length > 0 && <button type="button" onClick={() => { setQuery(''); setFilter('all') }}>重置筛选</button>}</div> : ['今天', '昨天', '更早'].map(group => {
        const rows = filtered.filter(c => taskDateGroup(c.updatedAt) === group)
        return rows.length ? <div key={group}><div className="lingsi-conv-group">{group}</div>{rows.map(c => {
          const status = getStatus(c)
          return <div className={`lingsi-conv${c.id===currentId?' lingsi-conv-active':''}`} key={c.id}>
            <button type="button" className="lingsi-conv-select" disabled={busy} aria-current={c.id===currentId?'page':undefined} onClick={() => navigate(c.id)}>
              <span className="lingsi-conv-title" title={c.title}>{c.title || '未命名任务'}</span>
              <span className="lingsi-conv-sub"><span className={`lingsi-task-dot status-${status}`}/>{agentStatusLabels[status]}<time dateTime={c.updatedAt}>{taskDateGroup(c.updatedAt)==='更早'?new Date(c.updatedAt).toLocaleDateString('zh-CN',{month:'2-digit',day:'2-digit'}):new Date(c.updatedAt).toLocaleTimeString('zh-CN', {hour:'2-digit', minute:'2-digit'})}</time></span>
            </button>
            <Popconfirm title="删除这个任务？" description="会话记录会被移除，已执行的操作不受影响。" onConfirm={() => onDelete(c.id)} okText="删除" cancelText="取消" disabled={busy}>
              <button type="button" className="lingsi-conv-del lingsi-icon-btn" disabled={busy} aria-label={`删除任务：${c.title}`}><Close size={12}/></button>
            </Popconfirm>
          </div>
        })}</div> : null
      })}
    </div>
    <footer className="lingsi-task-footer"><button type="button" onClick={onOpenTools}><Book size={15}/>能力与资料范围</button><small>当前组织：{orgName || '当前组织'}<br/>{user?.deptName || '当前部门'} / {user?.realName || user?.username}</small></footer>
  </aside>
}
