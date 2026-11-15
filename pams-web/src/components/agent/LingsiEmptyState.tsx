import { CalendarOutlined, ClockCircleOutlined, FileTextOutlined, TeamOutlined, AppstoreOutlined } from '@ant-design/icons'
import { useAuthStore } from '@/stores/auth'
import { useOrgStore } from '@/stores/org'
import type { AgentConversationVO, AgentMode } from '@/api/agent'
import { ArrowRight, Book } from './LingsiIcons'
import { LINGSI_AVATAR_SRC } from './LingsiAvatar'

interface Props {
  onPick: (text: string, mode?: AgentMode) => void
  mode?: AgentMode
  recent?: AgentConversationVO[]
  onSelect?: (id: number) => void
}
export default function LingsiEmptyState({onPick, mode='standard', recent=[], onSelect}: Props) {
  const user = useAuthStore(s => s.user)
  const has = (code: string) => user?.permissions?.includes(code)
  const packs = useOrgStore(s => s.enabledPacks)
  const writable = mode === 'standard'
  const kb = mode === 'kb'
  const leader = has('article:create')
  const canDraft = writable && has('plan:edit')
  const partyView = packs.includes('party') && has('party:view')
  const entries = kb ? [
    {title:'归档要求', desc:'查找活动材料清单，回答附带出处', text:'活动结束后需要归档哪些材料？请列出要求和出处。', icon:<Book size={18}/>, tone:'ai', target:undefined},
    {title:'审批流程', desc:'从组织制度中核对活动办理流程', text:'办一次活动要经过哪些审批？请提供资料依据。', icon:<FileTextOutlined/>, tone:'document', target:undefined},
    {title:'历史策划', desc:'参考已有活动的策划结构与准备事项', text:'历史活动策划书通常包含哪些内容？请给出资料依据。', icon:<CalendarOutlined/>, tone:'', target:undefined},
  ] : [
    {title:'活动筹备', desc:canDraft?'参考历史策划，准备初稿与会务清单':'查询活动进展与准备情况', text:canDraft?'帮我为活动准备策划初稿和会务清单，缺少的事实保留待补，请先让我核对。':'帮我汇总近期活动的进展和准备情况。', icon:<CalendarOutlined/>, tone:'', visible:has('activity:view'), target:undefined},
    {title:'排班考勤', desc:writable&&has('schedule:generate')?'结合无课表匹配人员，列明空缺':has('attendance:self:view')?'查询共同空闲时间与考勤情况':'查询本部门的共同空闲时间', text:writable&&has('schedule:generate')?'根据本部门无课表为活动安排值班，先给我看候选人员和空缺清单。':has('attendance:self:view')?'帮我查询本部门的共同空闲时间和近期考勤情况。':'帮我查询本部门的共同空闲时间。', icon:<ClockCircleOutlined/>, tone:'duty', visible:has('schedule:view'), target:undefined},
    {title:'内容宣传', desc:writable&&leader?'结合活动事实起草文稿，未知信息标注待补':leader?'检索相关推文与活动材料':'检索当前可查看的活动材料', text:writable&&leader?'根据活动资料帮我起草一篇活动报道，缺少的事实标为待补，先给我核对。':leader?'帮我检索近期活动的推文与相关材料。':'帮我检索近期活动的相关材料。', icon:<FileTextOutlined/>, tone:'document', visible:has('material:view')||leader, target:undefined},
    {title:'资料问答', desc:'从制度、公告和历史文书中查找依据', text:'活动结束后需要归档哪些材料？请列出要求和出处。', icon:<Book size={18}/>, tone:'ai', visible:has('kb:read'), target:'kb' as AgentMode},
    {title:partyView?'党务与成员':'成员资料', desc:partyView?'汇总可查看的发展阶段与成员情况':'查询当前可查看的成员资料', text:partyView?'汇总当前可查看的成员发展阶段，我想先了解整体情况。':'从成员名册资料中汇总当前可查看的成员情况。', icon:<TeamOutlined/>, tone:'', visible:partyView||has('kb:read'), target:partyView?undefined:'kb' as AgentMode},
    {title:'日常事务', desc:'查看活动任务与待办，明确下一步', text:'帮我汇总当前待处理的活动任务，按截止时间列出准备事项。', icon:<AppstoreOutlined/>, tone:'', visible:true, target:undefined},
    {title:'团务待办', desc:'查看评议、推优与组织生活待办', text:'帮我汇总当前组织的团务待办。', icon:<TeamOutlined/>, tone:'', visible:packs.includes('youth') && has('youth:view'), target:undefined},
    {title:'志愿服务', desc:'查看报名、待审核与已确认时长', text:'帮我汇总当前组织的志愿服务活动、报名人次和已确认时长。', icon:<ClockCircleOutlined/>, tone:'', visible:packs.includes('volunteer') && has('volunteer:view'), target:undefined},
  ].filter(e => e.visible)
  return <div className="lingsi-hero">
    <div className="lingsi-welcome-greeting">
      <img src={LINGSI_AVATAR_SRC} className="lingsi-welcome-avatar" alt="" />
      <div>
        <h2>你好，我是concerto</h2>
        <p>{kb ? '回答仅引用你有权查看的资料。' : writable ? '变更系统数据前会先请你确认。' : '只查询业务数据，不执行写入。'}</p>
      </div>
    </div>
    <div className="lingsi-starters">{entries.map(e => <button type="button" key={e.title} className="lingsi-starter" onClick={() => onPick(e.text,e.target)}><span className={`lingsi-category ${e.tone}`}>{e.icon}</span><span><strong>{e.title}</strong><small>{e.desc}</small></span><ArrowRight size={14}/></button>)}</div>
    {recent.length > 0 && (
      <div className="lingsi-welcome-recents">
        <span>继续最近的任务</span>
        {recent.slice(0, 2).map(c => (
          <button type="button" key={c.id} onClick={() => onSelect?.(c.id)}>{c.title}</button>
        ))}
      </div>
    )}
  </div>
}
