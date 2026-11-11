import { useRef } from 'react'
import type { AgentActionPlan, TaskInputResult } from '@/api/agent'
import type { OutcomeMessage, TimelineItem } from '@/stores/agent'
import { useAgentPanel } from '@/stores/agentPanel'
import { citationSnippet } from '@/utils/citationSnippet'
import LingsiAvatar from './LingsiAvatar'
import LingsiMarkdown from './LingsiMarkdown'
import LingsiProcessZone from './LingsiProcessZone'
import LingsiMetaBar from './LingsiMetaBar'
import LingsiRefs from './LingsiRefs'
import LingsiActionCard from './LingsiActionCard'
import LingsiFormCard from './LingsiFormCard'
import LingsiArtifactCard from './LingsiArtifactCard'
import LingsiSources from './LingsiSources'
import LingsiMessageActions from './LingsiMessageActions'
import LingsiWorkItems from './LingsiWorkItems'
import LingsiRunOverview from './LingsiRunOverview'

interface Props {
  msg: OutcomeMessage
  /** 该轮的完整时间轴（按 streamId 归属；历史消息没有过程事件 → 传空数组） */
  timeline: TimelineItem[]
  /** 该轮是否仍在进行 */
  active?: boolean
  planBusy?: boolean
  formBusy?: boolean
  /** 只读模式：清单卡不可确认（后端也会拦，这里是第一道） */
  readonly?: boolean
  /** 仅最后一条给「重新生成」入口（历史消息重生成会产生分叉，一期不做） */
  onRegenerate?: () => void
  onConfirmPlan: (plan: AgentActionPlan) => void
  onCancelPlan: (plan: AgentActionPlan) => void
  onSubmitForm: (taskId: number, values: Record<string, string>) => Promise<TaskInputResult>
}

/**
 * 灵思消息体 —— 过程区 + 结果区两段结构（灵思 §7.1 + UI v2 §B2）。
 *
 * <p>顺序不可颠倒：过程区在上、结果区在下。这条约束直接来自「让等待可见」的设计目标 ——
 * 用户先看到「我在做什么」，再看到「我做出了什么」。
 *
 * <p><b>UI v2 的核心改动：AI 消息不再是"气泡"，而是文档流。</b>
 * 原来结果区外面套着 `.lingsi-bubble`（1px 边框 + 底色），而清单卡自己也有边框 ——
 * 两层同色描边叠在一起，就是设计语言里最典型的"卡片套卡片"噪声。
 * 去掉容器装饰后，**卡片本身成为页面上唯一的容器层级**，层级关系反而更清楚：
 * 正文与卡片平级，都直接落在对话流上，靠间距和边框区分，不靠嵌套。
 */
export default function LingsiMessageBody({
  msg,
  timeline,
  active,
  planBusy,
  formBusy,
  readonly,
  onRegenerate,
  onConfirmPlan,
  onCancelPlan,
  onSubmitForm,
}: Props) {
  const hasPlan = Boolean(msg.plan)
  const workItemsRef = useRef<HTMLDivElement>(null)
  const inspectWorkItems = () => {
    const target = workItemsRef.current
    if (!target) return
    const details = target.querySelector('details')
    if (details) details.open = true
    target.scrollIntoView({ block: 'nearest', behavior: 'auto' })
    target.focus({ preventScroll: true })
  }
  const openText = useAgentPanel((s) => s.openText)
  const citations = msg.citations ?? []

  /** 点击正文里的 `[n]`：把对应依据的片段打开在右栏，不打断阅读 */
  const onCite = (index: number) => {
    const c = citations.find((x) => x.index === index)
    if (!c || c.revoked) return   // 已不可见的引用不展示内容
    openText(`cite-${c.sourceType}-${c.sourceId}-${index}`,
      `${c.sourceTitle}${c.path ? ` / ${c.path}` : ''}`, citationSnippet(c), { source:c, citations })
  }
  // 产物：优先取消息级（历史水合与确认回写都写在这里），仅在没有时回退到结果块上挂的
  const artifacts = msg.artifacts?.length ? msg.artifacts : (msg.notice?.artifacts ?? [])
  // An earlier round may already have emitted text. A new live reasoning round
  // owns the process label until its own first answer chunk seals it.
  const hasLiveReasoning = timeline.some(item => item.kind === 'thinking' && item.detail && !item.completed)
  const hasBody =
    Boolean(msg.reply) ||
    msg.references?.length > 0 ||
    msg.citations?.length > 0 ||
    hasPlan ||
    Boolean(msg.form) ||
    Boolean(msg.notice) ||
    artifacts.length > 0

  return (
    <div className="lingsi-turn">
      <div className="lingsi-turn-body">
        <div className="lingsi-answer-heading"><LingsiAvatar size={30}/><span>concerto</span><small>{active ? '正在处理' : '内容与事实需核对'}</small></div>
        {/*
          协作团队面板：仅在标准模式被判定升级为协作时才有内容（单 Agent 轮次整块不渲染）。
          位置在过程区之上 —— 它是「这轮派了哪些专家」的汇总层，
          过程区是「具体每一步做了什么」的明细层，先汇总后明细。
        */}
        <LingsiRunOverview run={msg.run} items={timeline} active={active} onInspect={inspectWorkItems} />

        {/* ---------- 过程区（§7.1 上半段） ---------- */}
        {(timeline.length > 0 || active) && <LingsiProcessZone items={timeline} active={active}
          teamOverview={Boolean(msg.run)} resultStarted={!hasLiveReasoning && Boolean(msg.reply || hasPlan || msg.form || msg.notice || artifacts.length)} />}

        {/* ---------- 结果区（§7.1 下半段）：文档流，无容器 ---------- */}
        {msg.reply && (msg.run || timeline.length > 0) && <div className="lingsi-result-label">{msg.streaming ? '正在生成回复' : '回复内容'}</div>}
        {msg.reply ? (
          <LingsiMarkdown
            text={msg.reply}
            streaming={msg.streaming}
            citationIndices={citations.filter(c => !c.revoked).map(c => c.index)}
            onCite={onCite}
          />
        ) : msg.streaming ? (
          <div className="lingsi-reply lingsi-reply-pending">
            正在整理结果…
            <span className="lingsi-cursor" />
          </div>
        ) : null}

        {msg.references?.length > 0 && <LingsiRefs references={msg.references} />}

        {/*
          依据卡（V27 对话档位）：紧贴答案下方，在一切"操作性卡片"之前。
          位置是刻意的 —— 用户读完答案，第一件想做的事是核对出处；
          把它排在清单卡/产物卡之后，会变成"先看到动作、后看到理由"。
        */}
        {msg.citations && msg.citations.length > 0 && <LingsiSources citations={msg.citations} />}

        {msg.run && <div ref={workItemsRef} tabIndex={-1} className="lingsi-work-target" aria-label="任务详情与待处理事项"><LingsiWorkItems run={msg.run} readonly={readonly} busy={planBusy || formBusy} onConfirm={onConfirmPlan} onCancel={onCancelPlan} onSubmit={onSubmitForm} /></div>}
        {!msg.run && hasPlan && msg.plan && (
          <LingsiActionCard
            plan={msg.plan}
            toolName={msg.toolCalls?.filter(t=>t.write).at(-1)?.tool}
            busy={planBusy}
            readonly={readonly}
            citations={citations}
            onConfirm={() => onConfirmPlan(msg.plan as AgentActionPlan)}
            onCancel={() => onCancelPlan(msg.plan as AgentActionPlan)}
          />
        )}

        {/* 表单补全卡（D2.5）：与清单卡互斥——提交表单后原位变为清单卡 */}
        {!msg.run && !hasPlan && msg.form && (
          <LingsiFormCard
            form={msg.form}
            inline
            readonly={readonly}
            busy={formBusy}
            onSubmit={(values) => onSubmitForm(msg.form!.taskId, values)}
          />
        )}

        {/* 执行结果行（用户反馈：任务完成不弹通知，结果落在对话框里） */}
        {msg.notice && (
          <div className={`lingsi-notice lingsi-notice-${msg.notice.level}`}>{msg.notice.text}</div>
        )}
        {msg.notice?.skipped && msg.notice.skipped.length > 0 && <div className="lingsi-preserved-skips"><strong>未执行与待补事项</strong>{msg.notice.skipped.map((s,i) => <p key={i}>{s.label}{s.sub ? ` / ${s.sub}` : ''}：{s.reason}</p>)}</div>}

        {/* 产物卡（UI v2 §C）：notice 回答"成没成"，这里回答"改了哪几样" */}
        {artifacts.length > 0 && (
          <LingsiArtifactCard artifacts={artifacts} deltas={msg.notice?.deltas} />
        )}

        {hasBody && !msg.streaming && !msg.notice && (
          <LingsiMetaBar
            provider={msg.provider}
            degraded={msg.degraded}
            durationMs={msg.durationMs}
            toolCalls={msg.toolCalls}
          />
        )}

        {hasBody && (
          <LingsiMessageActions text={msg.reply} hidden={msg.streaming} onRegenerate={onRegenerate} />
        )}
      </div>
    </div>
  )
}
