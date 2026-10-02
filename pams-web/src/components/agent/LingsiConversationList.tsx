import { useMemo, useState } from 'react'
import { Empty, Input, Popconfirm, Spin, Tooltip } from 'antd'
import type { AgentConversationVO } from '@/api/agent'
import { Close, Search, Sparkles } from './LingsiIcons'

interface Props {
  list: AgentConversationVO[]
  loading: boolean
  currentId: number | null
  pendingTaskCount: number
  onNew: () => void
  onSelect: (id: number) => void
  onDelete: (id: number) => void
  /** 窄屏抽屉模式：选中/新建后收起抽屉（宽屏为 no-op） */
  onNavigate?: () => void
}

/** 相对时间：1 分钟内「刚刚」、1 小时内「N 分钟前」、今天「HH:mm」、昨天、7 天内「N 天前」、更早「MM-DD」 */
function relTime(iso: string): string {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  const p = (n: number) => String(n).padStart(2, '0')
  const min = Math.floor((Date.now() - d.getTime()) / 60000)
  if (min < 1) return '刚刚'
  if (min < 60) return `${min} 分钟前`

  const today = new Date()
  today.setHours(0, 0, 0, 0)
  if (d.getTime() >= today.getTime()) return `${p(d.getHours())}:${p(d.getMinutes())}`

  const yest = new Date(today)
  yest.setDate(yest.getDate() - 1)
  if (d.getTime() >= yest.getTime()) return '昨天'

  const days = Math.floor((Date.now() - d.getTime()) / 86_400_000)
  if (days < 7) return `${days} 天前`
  return `${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

function isToday(iso: string): boolean {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return false
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  return d.getTime() >= today.getTime()
}

/**
 * 任务栏（灵思 §5.1 / UI v2 §F）。
 *
 * <p><b>把"会话"读成"任务"</b>，三处改动都服务这一件事：
 * <ol>
 *   <li>「新建任务」是一个**整宽按钮置顶**，不再是标题右上角一个孤零零的 `+`。</li>
 *   <li>按时间分组（今天 / 更早）并给组名配计数。灵思是长任务工具、不是高频聊天，
 *       绝大多数会话落在"今天"，所以只分两段 —— 四段分组在真实数据下会有三个空标题。</li>
 *   <li>会话项两行：标题 + 相对时间。选中态是**白底 + 左侧红色竖条**——
 *       这条在原设计方案 §3.3 里写了，但一直没实现（只有红底），
 *       导致选中态和 hover 态在视觉上几乎分不出来。</li>
 * </ol>
 *
 * <p>搜索是**纯本地过滤**：列表一次只拉 50 条，为一个前缀过滤再打一次接口不值当，
 * 且本地过滤是即时的（网络过滤会有可感知的延迟与竞态）。
 *
 * <p>待确认任务在标题旁挂徽标 —— 写操作门禁是异步的，用户可能切走页面，
 * 回到灵思时必须一眼看到「还有一件事等我确认」。
 */
export default function LingsiConversationList({
  list,
  loading,
  currentId,
  pendingTaskCount,
  onNew,
  onSelect,
  onDelete,
  onNavigate,
}: Props) {
  const [q, setQ] = useState('')

  const keyword = q.trim().toLowerCase()
  const filtered = useMemo(
    () =>
      keyword
        ? list.filter((c) => (c.title || '未命名任务').toLowerCase().includes(keyword))
        : list,
    [list, keyword],
  )

  const { today, earlier } = useMemo(() => {
    const t: AgentConversationVO[] = []
    const e: AgentConversationVO[] = []
    for (const c of filtered) (isToday(c.updatedAt) ? t : e).push(c)
    return { today: t, earlier: e }
  }, [filtered])

  const pick = (id: number) => {
    onSelect(id)
    onNavigate?.()
  }

  const create = () => {
    onNew()
    onNavigate?.()
  }

  const renderItem = (c: AgentConversationVO) => (
    <div
      className={`lingsi-conv${c.id === currentId ? ' lingsi-conv-active' : ''}`}
      key={c.id}
      onClick={() => pick(c.id)}
      role="button"
      tabIndex={0}
      onKeyDown={(e) => {
        if (e.key === 'Enter') pick(c.id)
      }}
    >
      <div className="lingsi-conv-title" title={c.title}>
        {c.title || '未命名任务'}
      </div>
      <div className="lingsi-conv-sub">
        <span>{relTime(c.updatedAt)}</span>
        {c.pendingTaskCount > 0 && (
          <Tooltip title={`${c.pendingTaskCount} 项操作待你确认`}>
            <span className="lingsi-conv-pending">{c.pendingTaskCount}</span>
          </Tooltip>
        )}
      </div>
      <span className="lingsi-conv-del" onClick={(e) => e.stopPropagation()}>
        <Popconfirm
          title="删除这个任务？"
          description="会话记录会被移除，已执行的操作不受影响。"
          onConfirm={() => onDelete(c.id)}
          okText="删除"
          cancelText="取消"
        >
          <button type="button" className="lingsi-icon-btn" aria-label="删除">
            <Close size={12} />
          </button>
        </Popconfirm>
      </span>
    </div>
  )

  const group = (title: string, items: AgentConversationVO[]) =>
    items.length > 0 && (
      <div key={title}>
        <div className="lingsi-conv-group">
          {title}
          <span className="lingsi-conv-group-count">{items.length}</span>
        </div>
        {items.map(renderItem)}
      </div>
    )

  return (
    <aside className="lingsi-sider glass-card">
      <div className="lingsi-sider-head">
        <span className="lingsi-sider-title">
          <span className="lingsi-brand-mark">
            <Sparkles size={13} />
          </span>
          灵思
        </span>
        <span className="lingsi-sider-sub">党务助手</span>
      </div>

      <button type="button" className="lingsi-new-task" onClick={create}>
        <svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round">
          <path d="M8 3.5v9M3.5 8h9" />
        </svg>
        新建任务
      </button>

      {list.length > 5 && (
        <Input
          className="lingsi-sider-search"
          size="small"
          allowClear
          value={q}
          onChange={(e) => setQ(e.target.value)}
          placeholder="搜索任务"
          prefix={<Search size={13} />}
          variant="filled"
        />
      )}

      {pendingTaskCount > 0 && (
        <div className="lingsi-sider-pending">{pendingTaskCount} 项操作等你确认</div>
      )}

      <div className="lingsi-sider-list">
        {loading ? (
          <div className="lingsi-sider-loading">
            <Spin size="small" />
          </div>
        ) : filtered.length === 0 ? (
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description={keyword ? '没有匹配的任务' : '还没有任务'}
            style={{ marginTop: 24 }}
          />
        ) : (
          <>
            {group('今天', today)}
            {group('更早', earlier)}
          </>
        )}
      </div>
    </aside>
  )
}
