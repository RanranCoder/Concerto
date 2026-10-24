import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { Drawer, Spin, Tooltip } from 'antd'
import { useAgentStore } from '@/stores/agent'
import { useAgentPanel } from '@/stores/agentPanel'
import { useMediaQuery } from '@/hooks/useMediaQuery'
import type { AgentActionPlan, TaskInputResult } from '@/api/agent'
import LingsiConversationList from '@/components/agent/LingsiConversationList'
import LingsiComposer from '@/components/agent/LingsiComposer'
import LingsiEmptyState from '@/components/agent/LingsiEmptyState'
import LingsiMessageBody from '@/components/agent/LingsiMessageBody'
import LingsiUserBubble from '@/components/agent/LingsiUserBubble'
import LingsiScrollToBottom from '@/components/agent/LingsiScrollToBottom'
import LingsiToolDrawer from '@/components/agent/LingsiToolDrawer'
import LingsiEntityPanel from '@/components/agent/LingsiEntityPanel'
import { Menu, PanelRight, Toolbox } from '@/components/agent/LingsiIcons'
import { agentStatusLabels, conversationStatus } from '@/utils/agentTask'
import type { AgentConversationStatus, AgentMode } from '@/api/agent'

/**
 * 灵思主页（灵思设计方案 §5 / §7 + UI 改版方案 v2 §A）。
 *
 * 布局：任务栏（252）+ 对话区（flex，内容 760 居中）+ 实体面板（≤452，可收起）。
 *
 * 关键实现说明：
 * 1. 过程事件走 WebSocket（/user/queue/agent/stream），结果走 chat 接口的同步返回。
 *    两条通道在 store 里汇合：过程区按 streamId 关联到对应轮次，结果区按 messageId 落位。
 * 2. 为了简洁，过程时间轴只在「最新一轮」保留（历史轮次的过程事件不落库，见设计方案 §2.3
 *    agent_message 只存最终内容 + tool_calls 摘要；历史轮次可显示工具摘要行）。
 * 3. 用户提问文案统一由 store 的 userTexts 维护（sendMessage 在同一次 set 里与占位消息一起写入），
 *    页面不再持有本地副本——此前双来源互相竞态，会出现"刚发出的消息不显示、刷新后才出现"。
 * 4. 右栏实体面板的状态放在 `stores/agentPanel`，因为"打开面板"这个动作发生在消息流深处
 *    （产物卡的一行、清单卡的查看全文）—— 用 props 传下去会让每个中间组件都感知面板的存在。
 */
export default function AgentHome() {
  const conversations = useAgentStore((s) => s.conversations)
  const conversationsLoading = useAgentStore((s) => s.conversationsLoading)
  const currentConversationId = useAgentStore((s) => s.currentConversationId)
  const outcomes = useAgentStore((s) => s.outcomes)
  const messagesLoading = useAgentStore((s) => s.messagesLoading)
  const userTexts = useAgentStore((s) => s.userTexts)
  const timeline = useAgentStore((s) => s.timeline)
  const thinking = useAgentStore((s) => s.thinking)
  const activeStreamId = useAgentStore((s) => s.activeStreamId)
  const pendingTaskCount = useAgentStore((s) => s.pendingTaskCount)
  const mode = useAgentStore((s) => s.mode)
  const setMode = useAgentStore((s) => s.setMode)
  const autoCollab = useAgentStore((s) => s.autoCollab)
  const setAutoCollab = useAgentStore((s) => s.setAutoCollab)
  const pendingRefs = useAgentStore((s) => s.pendingRefs)
  const addRef = useAgentStore((s) => s.addRef)
  const removeRef = useAgentStore((s) => s.removeRef)
  const userRefs = useAgentStore((s) => s.userRefs)

  const fetchConversations = useAgentStore((s) => s.fetchConversations)
  const selectConversation = useAgentStore((s) => s.selectConversation)
  const removeConversation = useAgentStore((s) => s.removeConversation)
  const sendMessage = useAgentStore((s) => s.sendMessage)
  const stop = useAgentStore((s) => s.stop)
  const confirmPlan = useAgentStore((s) => s.confirmPlan)
  const cancelPlan = useAgentStore((s) => s.cancelPlan)
  const submitForm = useAgentStore((s) => s.submitForm)

  const panelItems = useAgentPanel((s) => s.items)
  const panelCollapsed = useAgentPanel((s) => s.collapsed)
  const togglePanel = useAgentPanel((s) => s.toggle)

  const narrow = useMediaQuery('(max-width: 1250px)')
  /** <900px：任务栏放不下了，改走抽屉（对齐原设计方案 §3.2 的窄屏降级） */
  const isPhone = useMediaQuery('(max-width: 960px)')

  const [toolsOpen, setToolsOpen] = useState(false)
  const [siderOpen, setSiderOpen] = useState(false)
  const [planBusy, setPlanBusy] = useState(false)
  const [formBusy, setFormBusy] = useState(false)
  /** 「编辑并重新发送」的草稿（nonce 保证同一句话被编辑两次都会生效） */
  const [seed, setSeed] = useState<{ text: string; nonce: number } | null>(null)

  const scrollRef = useRef<HTMLDivElement>(null)
  const [anchoredTurn, setAnchoredTurn] = useState<number | null>(null)
  const alignedTurn = useRef<number | null>(null)

  // A short new turn still needs enough room below it to reach the top of the
  // viewport. Measure the scroll container only when its size changes.
  useLayoutEffect(() => {
    const el = scrollRef.current
    if (!el) return
    const measure = () => {
      const style = getComputedStyle(el)
      el.style.setProperty('--lingsi-turn-min-height', `${Math.max(0, el.clientHeight - parseFloat(style.paddingTop) - parseFloat(style.paddingBottom))}px`)
    }
    measure()
    const observer = new ResizeObserver(measure)
    observer.observe(el)
    return () => observer.disconnect()
  }, [])

  useEffect(() => {
    void fetchConversations()
    return () => {
      // 离开页面清掉过程态，避免下次进入看到上一轮的残影
      useAgentStore.setState({ timeline: [], thinking: false })
      // 产物面板也一并收起：下次进来不该先看到上个任务的面板
      useAgentPanel.getState().closeAll()
      useAgentPanel.getState().setCollapsed(true)
    }
  }, [fetchConversations])

  const conversation = conversations.find(c => c.id === currentConversationId)
  const lastOutcome = outcomes.at(-1)
  const currentStatus: AgentConversationStatus = thinking ? 'RUNNING'
    : outcomes.some(o => o.form) ? 'WAITING_INPUT'
    : outcomes.some(o => o.plan) ? 'PENDING_CONFIRM'
    : lastOutcome?.notice?.level === 'failed' ? 'FAILED'
    : lastOutcome?.notice?.level === 'partial' ? 'PARTIAL'
    : lastOutcome?.notice?.level === 'info' ? 'CANCELLED'
    : lastOutcome?.status === 'stopped' ? 'STOPPED'
    : lastOutcome?.status === 'failed' ? 'FAILED'
    : lastOutcome?.timeline.some(t => t.kind === 'error') ? 'FAILED'
    : outcomes.length > 0 ? 'COMPLETED'
    : conversation ? conversationStatus(conversation) : 'READY'

  // 用户提问与助手回复按下标一一对齐（store 在 sendMessage 的同一次 set 里同时写入两者）。
  // 结果块/异步完成追加的消息没有用户文案，userTexts 侧补空串占位，因此下标恒等。
  const rows = useMemo(
    () =>
      outcomes.map((_, i) => ({
        user: userTexts[i] || null,
        outcomeIdx: i,
      })),
    [outcomes, userTexts],
  )

  // 自动滚到底（流式过程中持续跟随）—— 但只在用户本来就在底部附近时才跟随。
  // 字符流会高频触发本 effect，无条件置底会把"往上翻看过程"的用户一次次拽回来。
  useEffect(() => {
    const el = scrollRef.current
    // Once a send is anchored, new process/reply chunks must not pull the
    // question back down or interrupt the user's manual reading position.
    if (!el || anchoredTurn !== null) return
    const nearBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 120
    if (nearBottom) el.scrollTop = el.scrollHeight
  }, [rows.length, outcomes, timeline.length, anchoredTurn])

  useLayoutEffect(() => {
    const el = scrollRef.current
    if (anchoredTurn === null) { alignedTurn.current = null; return }
    if (!el || alignedTurn.current === anchoredTurn) return
    const turn = el.querySelector<HTMLElement>('[data-scroll-anchor="true"]')
    if (!turn) return
    const align = () => {
      const style = getComputedStyle(el)
      el.style.setProperty('--lingsi-turn-min-height', `${Math.max(0, el.clientHeight - parseFloat(style.paddingTop) - parseFloat(style.paddingBottom))}px`)
      const top = el.scrollTop + turn.getBoundingClientRect().top - el.getBoundingClientRect().top - parseFloat(style.paddingTop)
      el.scrollTo({top, behavior:window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth'})
    }
    align()
    // The composer resets its textarea height after sending. Recheck once in
    // the next frame so the enlarged phone viewport cannot clamp the target.
    const frame = requestAnimationFrame(() => { align(); alignedTurn.current = anchoredTurn })
    return () => cancelAnimationFrame(frame)
  }, [anchoredTurn, rows.length])

  const handleSend = useCallback(
    (text: string) => {
      const state = useAgentStore.getState()
      if (state.thinking || !text.trim()) return
      setAnchoredTurn(state.outcomes.length)
      void sendMessage(text)
    },
    [sendMessage],
  )

  const handleSelect = useCallback(
    (id: number) => {
      setAnchoredTurn(null)
      useAgentPanel.getState().closeAll()
      setSeed({ text:'', nonce:Date.now() })
      void selectConversation(id)
    },
    [selectConversation],
  )

  const handleNew = useCallback(() => {
    setAnchoredTurn(null)
    useAgentPanel.getState().closeAll()
    setSeed({ text:'', nonce:Date.now() })
    void selectConversation(null)
  }, [selectConversation])

  const handleDelete = useCallback(
    async (id: number) => {
      try {
        await removeConversation(id)
      } catch {
        /* http 拦截器已提示 */
      }
    },
    [removeConversation],
  )

  const doConfirm = useCallback(
    async (plan: AgentActionPlan) => {
      setPlanBusy(true)
      try {
        // 令牌随卡传入：历史恢复的确认卡同样可确认（不再依赖全局 pendingPlan）
        // 不弹提示：执行结果由 store 原位写进该条消息的结果块（confirmPlan / applyTaskDone）
        await confirmPlan(plan.planId, plan.confirmToken)
      } catch {
        /* http 拦截器已提示 */
      } finally {
        setPlanBusy(false)
      }
    },
    [confirmPlan],
  )

  const doCancel = useCallback(
    async (plan: AgentActionPlan) => {
      setPlanBusy(true)
      try {
        // 取消结果同样原位写入结果块，不弹提示
        await cancelPlan(plan.planId)
      } catch {
        /* http 拦截器已提示 */
      } finally {
        setPlanBusy(false)
      }
    },
    [cancelPlan],
  )

  const doSubmitForm = useCallback(
    async (taskId: number, values: Record<string, string>): Promise<TaskInputResult> => {
      setFormBusy(true)
      try {
        return await submitForm(taskId, values)
      } catch {
        // http 拦截器已提示；返回 ERROR 让弹窗保留（用户改完可重提）
        return { status: 'ERROR', error: '提交失败，请稍后重试' }
      } finally {
        setFormBusy(false)
      }
    },
    [submitForm],
  )

  /** 「编辑并重新发送」：把原文放回输入框，不自动发送 */
  const handleEdit = useCallback((text: string) => {
    setSeed({ text, nonce: Date.now() })
  }, [])

  const handleStarter = (text: string, target?: AgentMode) => {
    if (target) void setMode(target)
    setSeed({ text, nonce: Date.now() })
  }

  const isEmpty = rows.length === 0

  /** 模型档位展示：取最近一轮的 provider */
  const lastProvider = useMemo(() => {
    for (let i = outcomes.length - 1; i >= 0; i -= 1) {
      if (outcomes[i].provider) return outcomes[i]
    }
    return null
  }, [outcomes])

  const showPanelToggle = panelItems.length > 0

  /**
   * 任务栏节点：宽屏做常驻栏，窄屏（<900px）装进抽屉。
   * 同一个节点两处用 —— 分屏/抽屉只是**容器**差异，列表逻辑必须完全一致。
   */
  const siderNode = (
    <LingsiConversationList
      list={conversations}
      loading={conversationsLoading}
      currentId={currentConversationId}
      pendingTaskCount={pendingTaskCount}
      currentStatus={currentStatus}
      busy={thinking || messagesLoading}
      onNew={handleNew}
      onSelect={handleSelect}
      onDelete={handleDelete}
      onOpenTools={() => setToolsOpen(true)}
      onNavigate={() => setSiderOpen(false)}
    />
  )

  return (
    <div className={`lingsi-page lingsi-v6${narrow ? ' lingsi-page-narrow' : ''}`}>
      {isPhone ? (
        <Drawer
          className="lingsi-sider-drawer lingsi-v6-task-drawer"
          open={siderOpen}
          onClose={() => setSiderOpen(false)}
          placement="left"
          width={288}
          title="任务列表"
          styles={{ body: { padding: 0 } }}
        >
          {siderNode}
        </Drawer>
      ) : (
        siderNode
      )}

      <div className="lingsi-main">
        <div className="lingsi-main-head">
          {isPhone && (
            <Tooltip title="任务列表">
              <button type="button" className="lingsi-icon-btn" aria-label="打开任务列表" onClick={() => setSiderOpen(true)}>
                <Menu size={16} />
              </button>
            </Tooltip>
          )}
          <div className="lingsi-main-title"><h1>{conversation?.title || (currentConversationId ? userTexts[0]?.slice(0,22) || '当前任务' : '新任务')}</h1><small>{currentConversationId ? '当前组织资料与关联活动' : '组织工作，从一句话开始'}</small></div>
          <span className="lingsi-main-head-actions">
            {currentConversationId && <button type="button" className={`lingsi-status-chip status-${currentStatus}`} onClick={() => scrollRef.current?.querySelector('.lingsi-plan, .lingsi-form')?.scrollIntoView({ behavior:'smooth', block:'center' })}>{agentStatusLabels[currentStatus]}</button>}
            {showPanelToggle && (
              <Tooltip title={panelCollapsed ? '展开产物面板' : '收起产物面板'}>
                <button
                  type="button"
                  className={`lingsi-icon-btn${panelCollapsed ? '' : ' lingsi-icon-btn-on'}`}
                  onClick={togglePanel}
                  aria-label={panelCollapsed ? '展开工作成果' : '收起工作成果'}
                  aria-expanded={!panelCollapsed}
                >
                  <PanelRight size={15} /><span>工作成果</span>
                </button>
              </Tooltip>
            )}
            <button type="button" className="lingsi-icon-btn" aria-label="能力与资料范围" title="能力与资料范围" onClick={() => setToolsOpen(true)}><Toolbox size={16}/></button>
          </span>
        </div>

        <div className="lingsi-scroll" ref={scrollRef}>
          <div className="lingsi-stream-inner">
            {messagesLoading ? (
              <div className="lingsi-stream-loading">
                <Spin />
              </div>
            ) : isEmpty ? (
              <LingsiEmptyState onPick={handleStarter} onOpenTools={() => setToolsOpen(true)} mode={mode} recent={conversations} onSelect={handleSelect}/>
            ) : (
              rows.map((r, i) => {
                const msg = r.outcomeIdx >= 0 ? outcomes[r.outcomeIdx] : undefined
                const isLast = i === rows.length - 1
                return (
                  <div key={`turn-${i}`} className="lingsi-turn-wrap" data-scroll-anchor={i === anchoredTurn ? 'true' : undefined}>
                    {r.user && (
                      <LingsiUserBubble text={r.user} attachedRefs={userRefs[i]} onEdit={handleEdit} />
                    )}
                    {msg && (
                      <LingsiMessageBody
                        msg={msg}
                        // 最后一轮：流式进行中/刚结束用实时时间轴；其余轮次用各自持久化派生的时间轴（V26）
                        timeline={isLast && timeline.length > 0 ? timeline : msg.timeline}
                        active={isLast && (thinking || msg.streaming)}
                        planBusy={planBusy}
                        formBusy={formBusy}
                        readonly={mode !== 'standard'}
                        // 重新生成仅最后一条：历史重生成会产生分叉（一期不做）
                        onRegenerate={isLast && r.user ? () => handleSend(r.user as string) : undefined}
                        onConfirmPlan={doConfirm}
                        onCancelPlan={doCancel}
                        onSubmitForm={doSubmitForm}
                      />
                    )}
                  </div>
                )
              })
            )}
          </div>
        </div>

        <div className="lingsi-composer-dock">
        <LingsiScrollToBottom scrollRef={scrollRef} generating={thinking || Boolean(lastOutcome?.streaming)} />
        <LingsiComposer
          busy={thinking}
          canStop={Boolean(activeStreamId)}
          disabled={messagesLoading}
          mode={mode}
          onModeChange={(m) => void setMode(m)}
          autoCollab={autoCollab}
          onAutoCollabChange={setAutoCollab}
          provider={lastProvider?.provider}
          degraded={lastProvider?.degraded}
          pendingRefs={pendingRefs}
          onAddRef={addRef}
          onRemoveRef={removeRef}
          seed={seed}
          onSend={handleSend}
          onStop={() => void stop()}
          onOpenTools={() => setToolsOpen(true)}
        />
        </div>
      </div>

      <LingsiEntityPanel />

      <LingsiToolDrawer open={toolsOpen} onClose={() => setToolsOpen(false)} />
    </div>
  )
}
