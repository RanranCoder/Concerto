import type { AgentActionPlan, TaskInputResult } from '@/api/agent'
import type { OutcomeMessage, TimelineItem } from '@/stores/agent'
import { useAgentPanel } from '@/stores/agentPanel'
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
  const openText = useAgentPanel((s) => s.openText)
  const citations = msg.citations ?? []

  /** 点击正文里的 `[n]`：把对应依据的片段打开在右栏，不打断阅读 */
  const onCite = (index: number) => {
    const c = citations.find((x) => x.index === index)
    if (!c || c.revoked) return   // 已不可见的引用不展示内容
    openText(`cite-${c.sourceType}-${c.sourceId}-${index}`,
      `${c.sourceTitle}${c.path ? ` · ${c.path}` : ''}`, c.snippet)
  }
  // 产物：优先取消息级（历史水合与确认回写都写在这里），仅在没有时回退到结果块上挂的
  const artifacts = msg.artifacts?.length ? msg.artifacts : (msg.notice?.artifacts ?? [])
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
      <LingsiAvatar size={24} />

      <div className="lingsi-turn-body">
        {/* ---------- 过程区（§7.1 上半段） ---------- */}
        {(timeline.length > 0 || active) && <LingsiProcessZone items={timeline} active={active} />}

        {/* ---------- 结果区（§7.1 下半段）：文档流，无容器 ---------- */}
        {msg.reply ? (
          <LingsiMarkdown
            text={msg.reply}
            streaming={msg.streaming}
            citeMax={citations.length}
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

        {hasPlan && msg.plan && (
          <LingsiActionCard
            plan={msg.plan}
            busy={planBusy}
            readonly={readonly}
            onConfirm={() => onConfirmPlan(msg.plan as AgentActionPlan)}
            onCancel={() => onCancelPlan(msg.plan as AgentActionPlan)}
          />
        )}

        {/* 表单补全卡（D2.5）：与清单卡互斥——提交表单后原位变为清单卡 */}
        {!hasPlan && msg.form && (
          <LingsiFormCard
            form={msg.form}
            busy={formBusy}
            onSubmit={(values) => onSubmitForm(msg.form!.taskId, values)}
          />
        )}

        {/* 执行结果行（用户反馈：任务完成不弹通知，结果落在对话框里） */}
        {msg.notice && (
          <div className={`lingsi-notice lingsi-notice-${msg.notice.level}`}>{msg.notice.text}</div>
        )}

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
