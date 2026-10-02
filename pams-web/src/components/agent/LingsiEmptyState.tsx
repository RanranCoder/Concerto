import { Button } from 'antd'
import { Sparkles, Tool } from './LingsiIcons'
import type { AgentMode } from '@/api/agent'

interface Props {
  onPick: (text: string) => void
  onOpenTools: () => void
  /** 当前档位：对话档位要换一组示例，否则用户会以为它也能办事 */
  mode?: AgentMode
}

/**
 * 空态欢迎区（灵思 §5）。
 *
 * 提示卡不是装饰：新用户不知道灵思能做什么，也不该从零猜「怎么问」。
 * 四条覆盖 23 个工具中最有代表性的四类意图（查数据 / 查进度 / 生成草案 / 批量操作）。
 *
 * V27：对话档位换一组示例。**这一步不能省** —— 办事型的示例
 * （"谁还没签到""批量加素拓分"）在知识问答里必然得到"资料里没有提到"，
 * 用户会直接判定功能没用。示例要承担"教会用户这个档位能问什么"的职责。
 */
const SUGGESTIONS: { label: string; desc: string; text: string }[] = [
  {
    label: '看看最近的活动',
    desc: '汇总进行中与即将开始的活动、报名与签到情况',
    text: '帮我汇总一下最近的活动情况，哪些还在进行中，签到率怎么样？',
  },
  {
    label: '谁还没签到',
    desc: '查某次活动的已签/未签名单，可直接导出思路',
    text: '最近一次活动还有哪些人没签到？',
  },
  {
    label: '我的待办有什么',
    desc: '汇总待我审批、待我确认、将到期的事项',
    text: '我目前有哪些待办事项需要处理？',
  },
  {
    label: '批量加素拓分',
    desc: '按活动签到情况一键生成加分清单，确认后落库',
    text: '帮我把最近一次活动的参与人员批量加素拓分，先给我看清单。',
  },
]

/** 对话档位的示例：覆盖四类问法（制度 / 流程 / 先例 / 条款） */
const KB_SUGGESTIONS: { label: string; desc: string; text: string }[] = [
  {
    label: '发展党员要走哪些流程',
    desc: '制度型问题：从资料里找规定并给出出处',
    text: '发展党员分几个阶段？每个阶段要交什么材料？',
  },
  {
    label: '办活动要走什么审批',
    desc: '流程型问题：把散落的规定串成步骤',
    text: '办一次主题党日活动要走哪些审批流程？',
  },
  {
    label: '上次活动是怎么策划的',
    desc: '先例型问题：从历史策划书里找做法',
    text: '上次主题党日活动的策划书里，活动流程是怎么安排的？',
  },
  {
    label: '党费收缴标准',
    desc: '条款型问题：引用原文片段，可点开核对',
    text: '党费的收缴标准是怎么规定的？',
  },
]

export default function LingsiEmptyState({ onPick, onOpenTools, mode }: Props) {
  const kb = mode === 'kb'
  const list = kb ? KB_SUGGESTIONS : SUGGESTIONS

  return (
    <div className="lingsi-hero">
      <div className="lingsi-hero-avatar">
        <Sparkles size={26} />
      </div>
      <h3 className="lingsi-hero-title">你好，我是灵思</h3>
      <p className="lingsi-hero-sub">
        {kb
          ? '当前是对话模式：我只在制度、公告、稿件、策划书这些资料里找答案，并给出出处。不会执行任何操作，也不会改动数据。'
          : '党建工作台的智能助手。可以帮你查数据、算排班、草拟文稿；涉及改动系统数据的操作，我会先给你一份清单，你确认后我才执行。'}
      </p>

      <div className="lingsi-suggest-grid">
        {list.map((s) => (
          <div className="lingsi-suggest" key={s.label} onClick={() => onPick(s.text)} role="button" tabIndex={0}>
            <div className="lingsi-suggest-label">{s.label}</div>
            <div className="lingsi-suggest-desc">{s.desc}</div>
          </div>
        ))}
      </div>

      <div style={{ marginTop: 18 }}>
        <Button size="small" icon={<Tool />} onClick={onOpenTools}>
          {kb ? '查看能检索哪些资料' : '查看我现在会做什么'}
        </Button>
      </div>
    </div>
  )
}
