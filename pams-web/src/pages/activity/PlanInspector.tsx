import { useMemo } from 'react'
import { Button, Empty, Input, Space, Tabs } from 'antd'
import type { ActivityDetail, ActivityPlanVO } from '@/api/activity'
import {
  BODY_SECTIONS,
  comparePlans,
  comparisonText,
  PLAN_LABELS,
  PLAN_TONES,
} from './planComparison'

interface Props {
  workspace: ActivityDetail['plan']
  shown: ActivityPlanVO | null
  editing: boolean
  busy: boolean
  activeTab: string
  onTab: (key: string) => void
  onSelect: (id: number) => void
  canReview: boolean
  comment: string
  onComment: (value: string) => void
  onReview: (approved: boolean) => void
}
const time = (value: string | null | undefined) =>
  value?.replace('T', ' ').slice(5, 16).replace('-', '/') || '时间未记录'

export default function PlanInspector({
  workspace,
  shown,
  editing,
  busy,
  activeTab,
  onTab,
  onSelect,
  canReview,
  comment,
  onComment,
  onReview,
}: Props) {
  const current =
    workspace &&
    !workspace.currentHidden &&
    workspace.latest?.id === workspace.currentId
      ? workspace.latest
      : null
  const changes = useMemo(
    () =>
      shown && current && shown.id !== current.id
        ? comparePlans(shown, current)
        : null,
    [shown, current],
  )
  const reviews = shown?.reviewers ?? []
  const opinions = reviews.filter((r) => r.comment?.trim())
  const filled = shown
    ? BODY_SECTIONS.filter(([key]) => comparisonText(shown[key])).length
    : 0
  const history = (
    <>
      <header className="plan-inspector-intro">
        <h3>
          {workspace?.currentHidden ? '可查看的历史版本' : '每次提交，都有记录'}
        </h3>
        <p>
          {workspace?.currentHidden
            ? '当前版本仅向作者及本轮选定审核人开放。'
            : '正文原地保存；修订时保留上一版本。'}
        </p>
      </header>
      {workspace?.versions?.length ? (
        <ol className="plan-version-timeline">
          {workspace.versions.map((v) => (
            <li key={v.id} className={v.id === shown?.id ? 'selected' : ''}>
              <button
                type="button"
                disabled={editing || busy}
                aria-pressed={v.id === shown?.id}
                onClick={() => onSelect(v.id)}
              >
                <span className="plan-version-dot" />
                <div className="plan-version-title">
                  <strong>
                    v{v.version}
                    {v.id === workspace.currentId ? ' · 当前版本' : ''}
                  </strong>
                  <span className={`tone-tag tone-${PLAN_TONES[v.status]}`}>
                    {PLAN_LABELS[v.status]}
                  </span>
                </div>
                <p>
                  {v.note ||
                    (v.id === workspace.currentId
                      ? '当前工作版本'
                      : v.status === 'APPROVED'
                        ? '审核通过的定稿。'
                        : '策划书版本记录。')}
                </p>
                <small>
                  {time(v.updatedAt)} ·{' '}
                  {v.ownerName ||
                    (v.ownerId ? `作者 #${v.ownerId}` : '未记录作者')}
                </small>
              </button>
            </li>
          ))}
        </ol>
      ) : (
        <Empty
          image={Empty.PRESENTED_IMAGE_SIMPLE}
          description="保存策划书后，版本记录会显示在这里"
        />
      )}
      {changes && current && shown ? (
        <section className="plan-version-comparison" aria-label="版本章节对比">
          <h3>与当前 v{current.version} 对比</h3>
          <p>{changes.length} 个章节有变化</p>
          {editing && <p>对比已保存的正文，保存后更新。</p>}
          {changes.length ? (
            changes.map((change) => (
              <section key={change.key} className="plan-section-diff">
                <h4>{change.label}</h4>
                {change.formatOnly && <small>正文文字一致，格式有调整</small>}
                <div className="plan-diff-before">
                  <small>v{shown.version}</small>
                  <p>{change.before || '尚未填写'}</p>
                </div>
                <div className="plan-diff-after">
                  <small>当前 v{current.version}</small>
                  <p>{change.after || '尚未填写'}</p>
                </div>
              </section>
            ))
          ) : (
            <p>正文内容一致，审核状态或记录发生了变化。</p>
          )}
        </section>
      ) : (
        <p className="plan-inspector-help">
          {workspace?.currentHidden
            ? '您无权查看当前版本，因此不会展示与当前版的对比。'
            : workspace?.versions && workspace.versions.length > 1
              ? '选择历史版本，查看与当前版的章节差异。'
              : '修订新版本后，可在这里对比章节变化。'}
        </p>
      )}
    </>
  )
  const review = (
    <>
      <header className="plan-inspector-intro">
        <h3>
          {shown?.id === workspace?.currentId
            ? '提交前检查'
            : `v${shown?.version ?? '—'} 审核记录`}
        </h3>
        <p>检查结构完整性，内容由审核人确认。</p>
      </header>
      <ul className="plan-inspector-checks">
        <li>
          <span>策划章节</span>
          <strong>{filled} / 7</strong>
        </li>
        <li>
          <span>本轮审核人</span>
          <strong>
            {reviews.length ? `${reviews.length} 位` : '尚未选择'}
          </strong>
        </li>
      </ul>
      <div className="plan-inspector-opinions">
        <h3>
          审核意见 <span>{opinions.length}</span>
        </h3>
        {opinions.length ? (
          opinions.map((r) => (
            <article key={r.userId}>
              <header>
                <strong>{r.name}</strong>
                <span>{r.approved ? '已通过' : '已退回'}</span>
              </header>
              <small>{time(r.reviewedAt)}</small>
              <p>{r.comment}</p>
            </article>
          ))
        ) : (
          <p className="plan-inspector-help">
            暂无审核意见。审核人提交意见后，将随版本保留。
          </p>
        )}
      </div>
      {!!reviews.length && (
        <section className="plan-inspector-progress">
          <h3>本轮审核人</h3>
          <p>全部通过后完成审核</p>
          {reviews.map((r) => (
            <div key={r.userId}>
              <span>{r.name}</span>
              <b>
                {r.approved === null
                  ? '待审核'
                  : r.approved
                    ? '已通过'
                    : '已退回'}
              </b>
            </div>
          ))}
        </section>
      )}
      {canReview && (
        <section className="plan-inspector-decision">
          <label htmlFor="plan-review-comment">填写审核意见</label>
          <Input.TextArea
            id="plan-review-comment"
            rows={4}
            maxLength={1000}
            value={comment}
            disabled={busy}
            onChange={(e) => onComment(e.target.value)}
            placeholder="退回时请说明需要修改的内容"
          />
          <Space wrap>
            <Button danger loading={busy} onClick={() => onReview(false)}>
              退回修改
            </Button>
            <Button
              type="primary"
              loading={busy}
              onClick={() => onReview(true)}
            >
              通过审核
            </Button>
          </Space>
        </section>
      )}
    </>
  )
  return (
    <aside className="plan-inspector" aria-label="策划书审核与版本侧栏">
      <Tabs
        activeKey={workspace?.currentHidden ? 'history' : activeTab}
        onChange={onTab}
        items={[
          ...(!workspace?.currentHidden
            ? [
                {
                  key: 'review',
                  label: (
                    <>
                      审核意见{' '}
                      <span className="plan-inspector-count">
                        {opinions.length}
                      </span>
                    </>
                  ),
                  children: review,
                },
              ]
            : []),
          { key: 'history', label: '版本记录', children: history },
        ]}
      />
    </aside>
  )
}
