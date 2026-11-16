import { useState } from 'react'
import type { AgentConversationVO, AgentMode } from '@/api/agent'
import { useAuthStore } from '@/stores/auth'
import { useOrgStore } from '@/stores/org'
import { agentStatusLabels, conversationStatus } from '@/utils/agentTask'
import LingsiAvatar from './LingsiAvatar'
import { ArrowRight, Book, Pencil, Plan, Search } from './LingsiIcons'
import './LingsiEmptyState.css'

interface Starter {
  title: string
  description: string
  prompt: string
  mode?: AgentMode
}

interface Props {
  onPick: (text: string, mode?: AgentMode) => void
  mode: AgentMode
  recent: AgentConversationVO[]
  onSelect: (id: number) => void
}

const archiveStarter: Starter = {
  title: '查活动归档要求',
  description: '材料清单、提交时限和制度出处',
  prompt: '根据本组织可查的资料，列出活动结束后需要归档的材料、提交时限和负责角色，逐项注明出处。资料未明确的要求请标注。',
  mode: 'kb',
}

const knowledgeStarters: Starter[] = [
  archiveStarter,
  {
    title: '查活动审批流程',
    description: '申请、审核和所需材料',
    prompt: '根据本组织可查的制度，梳理活动从申请到获批的流程，列出各环节的办理角色、所需材料，并注明出处。',
  },
  {
    title: '参考往期策划书',
    description: '找可复用的结构、议程和分工',
    prompt: '从可查的往期活动策划书中找出可参考的文档，比较它们的章节、议程和分工，并标明对应来源。',
  },
]

export default function LingsiEmptyState({ onPick, mode, recent, onSelect }: Props) {
  const [category, setCategory] = useState('queries')
  const permissions = useAuthStore(s => s.user?.permissions)
  const packs = useOrgStore(s => s.enabledPacks)
  const has = (...codes: string[]) => codes.every(code => permissions?.includes(code))
  const canSeeActivity = has('activity:view')
  const canReadPlans = canSeeActivity && has('plan:view')
  const canReadKnowledge = has('kb:read')
  const canSeePending = ['task:view', 'plan:review', 'content:view', 'schedule:view', 'schedule:self:view', 'notice:view']
    .some(code => has(code))
  const kb = mode === 'kb'

  const queries: Starter[] = [
    ...(canSeePending ? [{
      title: '查看我的待办',
      description: '待处理任务、审批等工作台事项',
      prompt: '汇总工作台上我当前可办理的事项，按类型分组，标出已逾期和有截止时间的条目，并附上可用的办理入口。',
    }] : []),
    ...(canSeeActivity ? [{
      title: '查看近期活动',
      description: '未来两周的活动安排和当前状态',
      prompt: '列出未来两周我有权查看的活动，按开始时间排序，标出活动状态、地点和详情入口。缺失的信息请标注。',
    }] : []),
    ...(has('task:view') ? [{
      title: '核对活动任务进度',
      description: '查看负责人、截止时间和未完成项',
      prompt: '查看指定活动的任务进度，列出未完成任务、负责人、截止时间和当前进度，优先标出逾期事项。若未指定活动，请先问我。',
    }] : []),
    {
      title: '查看未读通知',
      description: '未读总数和最新未读消息',
      prompt: '查一下我的站内未读通知，告诉我未读总数，并列出最新 5 条的标题和收到时间。',
    },
  ]
  const drafts: Starter[] = [
    ...(canSeeActivity && has('plan:edit') ? [{
      title: '起草活动策划书',
      description: '用活动信息整理议程、分工与准备事项',
      prompt: '根据指定活动的信息起草策划书，包含活动目标、议程、人员分工和会前准备。未确定的信息标为待补。若未指定活动，请先问我。先展示策划初稿，等我核对后再保存。',
    }] : []),
    ...(canReadPlans && has('plan:edit') ? [{
      title: '修订 Word 策划书',
      description: '按原文定位段落，预览修改前后差异',
      prompt: '帮我修订已有的 Word 策划书。请先确认活动、策划书版本和具体修改要求，再读取原文定位相关段落，展示修改前后的差异，等我确认后再保存修订稿。',
    }] : []),
    ...(canSeeActivity && has('schedule:generate') ? [{
      title: '预排活动值班',
      description: '按无课表匹配人员，列出缺员班次',
      prompt: '根据无课表为指定活动准备排班预览。缺少活动、日期、时段、参与部门或每班人数时先问我。列出拟排班次和缺员情况，等我确认后再保存草稿。',
    }] : []),
    ...(canSeeActivity && has('article:create') ? [{
      title: '起草活动报道',
      description: '基于活动事实准备可审阅的稿件',
      prompt: '根据指定活动的已核实信息起草一篇报道，包含标题、导语和正文，未知信息标为待补。若未指定活动，请先问我。先给我预览稿件，等我确认后再保存。',
    }] : []),
  ]
  const references: Starter[] = [
    ...(canReadPlans ? [{
      title: '核对策划书审批状态',
      description: '查看指定版本的状态和可见审核意见',
      prompt: '请核对指定活动的策划书审批状态，列出相应版本及可见的审核意见。若未指定活动或版本有歧义，请先问我。',
    }] : []),
    ...(has('material:view') ? [{
      title: '检索活动材料',
      description: '按活动名称查找材料和文件入口',
      prompt: '检索指定活动相关的材料，列出可查看的文件名、类别和可用入口。若未指定活动，请先问我。',
    }] : []),
  ]
  const extensions: Starter[] = [
    ...(packs.includes('party') && has('party:view') ? [{
      title: '查询党员发展进度',
      description: '按发展阶段查看可见记录',
      prompt: '帮我查看当前有权读取的党员发展记录，按发展阶段整理，并注明查询范围。',
    }] : []),
    ...(packs.includes('youth') && has('youth:view') ? [{
      title: '查看团务待办',
      description: '统计待审核和待归档事项',
      prompt: '查看本组织团务待办，分别列出评议和推优待审核总数、组织生活待归档场次，并附上团务工作入口。',
    }] : []),
    ...(packs.includes('volunteer') && has('volunteer:view') ? [{
      title: '汇总志愿服务',
      description: '活动、报名、审核与已确认时长',
      prompt: '汇总当前组织的志愿服务活动数、报名人次、待审核记录数和已确认服务时长，并附上志愿服务入口。',
    }] : []),
  ]

  const groups = [
    { id: 'queries', label: '业务查询', icon: Search, entries: queries },
    { id: 'drafts', label: '草稿与预览', icon: Pencil, entries: mode === 'standard' ? drafts : [] },
    { id: 'references', label: '资料与文档', icon: Book, entries: [
      ...references,
      ...(canReadKnowledge ? [{ ...archiveStarter, description: '切换到资料问答，查材料清单与出处' }] : []),
    ] },
    { id: 'extensions', label: '组织工作包', icon: Plan, entries: extensions },
  ].filter(group => group.entries.length > 0)
  const activeGroup = groups.find(group => group.id === category) ?? groups[0]
  const entries = kb ? (canReadKnowledge ? knowledgeStarters : []) : activeGroup.entries

  return (
    <div className="lingsi-welcome">
      <div className="lingsi-welcome-heading">
        <LingsiAvatar size={48} />
        <div>
          <h2>Concerto 工作台</h2>
          <p>{kb ? '从组织资料中查找答案和出处。' : '查待办、了解活动进展，或准备下一项工作。'}</p>
        </div>
      </div>
      <section className="lingsi-welcome-board" aria-labelledby="lingsi-template-title">
        <div className="lingsi-welcome-board-head">
          <h3 id="lingsi-template-title">{kb ? '资料提问模板' : '任务模板'}</h3>
          <span>点击填入输入框，发送前可编辑</span>
        </div>
        {!kb && groups.length > 1 && (
          <div className="lingsi-starter-categories" role="group" aria-label="模板分类">
            {groups.map(({ id, label, icon: Icon }) => (
              <button type="button" key={id} aria-pressed={id === activeGroup.id} onClick={() => setCategory(id)}>
                <span className="lingsi-category-icon"><Icon size={16} /></span>{label}
              </button>
            ))}
          </div>
        )}
        {entries.length > 0 ? (
          <ul className="lingsi-starters" aria-label={kb ? '资料问答' : activeGroup.label}>
            {entries.map(entry => (
              <li key={entry.title}>
                <button type="button" className="lingsi-starter" onClick={() => onPick(entry.prompt, entry.mode)}>
                  <span className="lingsi-starter-copy">
                    <strong>{entry.title}</strong>
                    <small>{entry.description}</small>
                  </span>
                  <Pencil size={15} />
                </button>
              </li>
            ))}
          </ul>
        ) : (
          <p className="lingsi-starter-empty">当前账号暂无资料问答权限。可切换运行模式处理其他任务。</p>
        )}
      </section>
      {recent.length > 0 && (
        <section className="lingsi-welcome-recents" aria-labelledby="lingsi-recents-title">
          <h3 id="lingsi-recents-title">最近任务</h3>
          <ul>
            {recent.slice(0, 2).map(item => {
              const status = conversationStatus(item)
              return (
                <li key={item.id}>
                  <button type="button" onClick={() => onSelect(item.id)}>
                    <span className="lingsi-recent-title">{item.title}</span>
                    <span className={`lingsi-task-dot status-${status}`} aria-hidden="true" />
                    <span className="lingsi-recent-status">{agentStatusLabels[status]}</span>
                    <ArrowRight size={14} />
                  </button>
                </li>
              )
            })}
          </ul>
        </section>
      )}
    </div>
  )
}
