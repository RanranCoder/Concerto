import { Button } from 'antd'
import { useNavigate } from 'react-router-dom'
import type { ActivityDetail } from '@/api/activity'
import type { MaterialVO } from '@/api/material'
import { ACTIVITY_TYPE_MAP } from '@/api/activityStatus'
import GlassCard from '@/components/glass/GlassCard'
import StatusTag from '@/components/glass/StatusTag'
import GanttChart from '@/components/gantt/GanttChart'
import type { GanttTask } from '@/components/gantt/gantt.utils'

export default function ActivityOverview({
  detail,
  materials,
  tasks,
  onTab,
  onEdit,
  canEdit,
}: {
  detail: ActivityDetail
  materials: MaterialVO[]
  tasks: GanttTask[]
  onTab: (tab: string) => void
  onEdit: () => void
  canEdit: boolean
}) {
  const a = detail.activity,
    navigate = useNavigate(),
    done = tasks.filter((t) => (t.progress ?? 0) >= 100).length
  return (
    <div className="activity-overview-v6">
      <div className="activity-stack">
        <GlassCard>
          <header className="activity-panel-head">
            <div>
              <h2>活动信息</h2>
              <p>组织安排与基本资料</p>
            </div>
            {canEdit && (
              <Button type="link" onClick={onEdit}>
                编辑信息
              </Button>
            )}
          </header>
          <dl className="activity-overview-info">
            {[
              ['活动类型', ACTIVITY_TYPE_MAP[a.type] || a.type],
              ['活动主题', a.theme],
              ['组织单位', a.organizer],
              ['负责人', a.leader],
              ['主持人', a.host],
              ['面向对象', a.targetAudience],
              ['开始日期', a.startDate],
              ['结束日期', a.endDate],
              ['活动地点', a.location],
            ].map(([label, value]) => (
              <div key={label}>
                <dt>{label}</dt>
                <dd>{value || '未填写'}</dd>
              </div>
            ))}
            <div className="wide">
              <dt>活动描述</dt>
              <dd>{a.description || '暂无活动描述'}</dd>
            </div>
          </dl>
        </GlassCard>
        <GlassCard>
          <header className="activity-panel-head">
            <div>
              <h2>执行准备</h2>
              <p>按活动流程核对准备事项</p>
            </div>
          </header>
          <ul className="activity-preparation">
            {[
              [
                '活动策划书',
                !detail.plan?.currentHidden && detail.plan?.status === 'APPROVED',
                detail.plan?.currentHidden
                  ? '当前版本仅作者与所选审核人可见，可查阅历史版本'
                  : detail.plan?.latest
                  ? '策划状态：' +
                    {
                      DRAFT: '草稿',
                      PENDING: '待审核',
                      APPROVED: '已通过',
                      REJECTED: '已驳回',
                      WITHDRAWN: '已撤回',
                    }[detail.plan.latest.status]
                  : '尚未创建策划书',
                'plan',
              ],
              [
                '活动议程',
                detail.agendas.length > 0,
                `${detail.agendas.length} 个环节，核对执行顺序与会务安排`,
                'agenda',
              ],
              [
                '座位安排',
                Object.values(detail.seatZones).flat().length > 0,
                '检查就座人员与场地区域',
                'seat',
              ],
              [
                '活动材料',
                materials.length > 0,
                `${materials.length} 份已载入材料，按实际要求核对完整性`,
                'materials',
              ],
            ].map(([label, complete, note, tab]) => (
              <li key={String(tab)}>
                <span className={complete ? 'complete' : ''} aria-hidden>
                  {complete ? '✓' : '!'}
                </span>
                <div>
                  <strong>{label}</strong>
                  <p>{note}</p>
                </div>
                <Button type="link" onClick={() => onTab(String(tab))}>
                  查看
                </Button>
              </li>
            ))}
          </ul>
        </GlassCard>
        <GlassCard>
          <header className="activity-panel-head">
            <div>
              <h2>任务甘特图</h2>
              <p>
                {done} / {tasks.length} 项任务已完成
              </p>
            </div>
            <Button
              type="link"
              onClick={() => navigate(`/activities/${a.id}/gantt`)}
            >
              查看完整任务
            </Button>
          </header>
          <div className="activity-gantt-preview">
            <GanttChart tasks={tasks} onUpdate={() => {}} onEdit={() => {}} />
          </div>
        </GlassCard>
      </div>
      <aside className="activity-stack">
        <GlassCard>
          <header className="activity-panel-head">
            <h2>准备进度</h2>
          </header>
          <dl className="activity-info-list">
            <dt>任务完成</dt>
            <dd>
              {done} / {tasks.length} 项
            </dd>
            <dt>已签到</dt>
            <dd>{detail.signinCount} 人</dd>
            <dt>当前阶段</dt>
            <dd>
              <StatusTag status={a.status} />
            </dd>
            <dt>负责人</dt>
            <dd>{a.leader || '未设置'}</dd>
          </dl>
        </GlassCard>
        {a.status === 'PLAN_REVIEW' && (
          <p className="activity-inline-note">
            策划仍待审核，请先核对活动安排、预算和应急预案。
          </p>
        )}
        <GlassCard>
          <header className="activity-panel-head">
            <h2>工作入口</h2>
          </header>
          <div className="activity-shortcuts">
            {[
              ['signin', '查看签到与应签名单'],
              ['articles', '跟进预热与活动报道'],
              ['materials', '核对活动关联材料'],
            ].map(([tab, text]) => (
              <Button block key={tab} onClick={() => onTab(tab)}>
                {text}
              </Button>
            ))}
          </div>
        </GlassCard>
      </aside>
    </div>
  )
}
