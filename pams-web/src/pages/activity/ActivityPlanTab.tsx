import { lazy, Suspense, useEffect, useMemo, useRef, useState } from 'react'
import { App, Button, Empty, Input, Space, Spin, Checkbox, Select } from 'antd'
import {
  DownloadOutlined,
  EditOutlined,
  UploadOutlined,
} from '@ant-design/icons'
import {
  createPlan,
  fetchPlanDocument,
  getPlan,
  planWorkspace,
  planReviewers,
  revisePlan,
  type PlanReviewerCandidate,
  reviewPlan,
  submitPlan,
  updatePlan,
  type PlanSave,
} from '@/api/plan'
import { fetchFileBlob, uploadFile } from '@/api/file'
import { listTemplates, type TemplateVO } from '@/api/template'
import type { ActivityDetail, ActivityPlanVO } from '@/api/activity'
import { useAuthStore } from '@/stores/auth'
import { useHasPerm } from '@/components/glass/RequireRole'
import FormSurface from '@/components/form/FormSurface'
import type { SuperDocDocumentHandle } from '@/components/word/SuperDocDocument'
import PlanInspector from './PlanInspector'
import {
  parseSectionOrder,
  planToDocx,
  type PlanFields,
  type PlanOverrides,
} from '@/components/word/legacyPlanMigration'

const SuperDocDocument = lazy(() => import('@/components/word/SuperDocDocument'))

const labels = {
  DRAFT: '草稿',
  PENDING: '待审核',
  APPROVED: '已通过',
  REJECTED: '已驳回',
  WITHDRAWN: '已撤回',
}
const fromPlan = (plan: ActivityPlanVO | null): PlanFields => ({
  background: plan?.background ?? '',
  purpose: plan?.purpose ?? '',
  content: plan?.content ?? '',
  flow: plan?.flow ?? '',
  notice: plan?.notice ?? '',
  emergency: plan?.emergency ?? '',
  budget: plan?.budget ?? '',
})
const fromOverrides = (plan: ActivityPlanVO | null): PlanOverrides => ({
  nameOverride: plan?.nameOverride ?? undefined,
  themeOverride: plan?.themeOverride ?? undefined,
  timeOverride: plan?.timeOverride ?? undefined,
  locationOverride: plan?.locationOverride ?? undefined,
  organizerOverride: plan?.organizerOverride ?? undefined,
  targetOverride: plan?.targetOverride ?? undefined,
})

export default function ActivityPlanTab({
  activityId,
  activity,
  plan,
  onChanged,
}: {
  activityId: number
  activity: ActivityDetail['activity']
  plan: ActivityDetail['plan']
  onChanged: () => void
}) {
  const { message, modal } = App.useApp(),
    hasPerm = useHasPerm()
  const userId = useAuthStore((s) => s.user?.id)
  const authorName = useAuthStore((s) => s.user?.realName || '策划书作者')
  const [workspace, setWorkspace] = useState(plan)
  const [selected, setSelected] = useState<ActivityPlanVO | null>(null)
  const [versionBusy, setVersionBusy] = useState(false)
  const latest = selected ?? workspace?.latest ?? null
  const isCurrent = !!latest && latest.id === workspace?.currentId
  const [submitOpen, setSubmitOpen] = useState(false)
  const [candidates, setCandidates] = useState<PlanReviewerCandidate[]>([])
  const [reviewerIds, setReviewerIds] = useState<number[]>([])
  const [candidateBusy, setCandidateBusy] = useState(false)
  const [candidateError, setCandidateError] = useState(false)
  const [search, setSearch] = useState('')
  useEffect(() => {
    setWorkspace(plan)
  }, [plan])
  useEffect(() => {
    setSelected(null)
    setEditor(false)
    setSubmitOpen(false)
  }, [activityId])
  useEffect(() => {
    const id = Number(new URLSearchParams(window.location.search).get('planId'))
    if (!id || !workspace?.versions?.some((v) => v.id === id)) return
    let cancelled = false
    setVersionBusy(true)
    getPlan(id)
      .then((doc) => {
        if (!cancelled) setSelected(doc)
      })
      .catch(() => {})
      .finally(() => {
        if (!cancelled) setVersionBusy(false)
      })
    return () => {
      cancelled = true
    }
  }, [activityId])
  const refresh = async () => {
    const next = await planWorkspace(activityId)
    setWorkspace(next)
    setSelected(null)
    onChanged()
  }
  const pickVersion = async (id: number) => {
    setInspectorTab('history')
    setVersionBusy(true)
    setSelected(null)
    setBlob(null)
    try {
      setSelected(await getPlan(id))
    } finally {
      setVersionBusy(false)
    }
  }
  const chooseReviewers = async () => {
    setSubmitOpen(true)
    setReviewerIds([])
    setSearch('')
    setCandidateBusy(true)
    setCandidateError(false)
    try {
      setCandidates(await planReviewers())
    } catch {
      setCandidates([])
      setCandidateError(true)
    } finally {
      setCandidateBusy(false)
    }
  }
  const revision = async () => {
    if (!latest) return
    setSaving(true)
    try {
      await revisePlan(latest.id)
      await refresh()
      message.success('已保留原版并创建新草稿，请重新选择审核人')
    } finally {
      setSaving(false)
    }
  }
  const [editor, setEditor] = useState(false),
    [dirty, setDirty] = useState(false),
    [saving, setSaving] = useState(false)
  const [editBlob, setEditBlob] = useState<Blob | null>(null)
  const [documentReady, setDocumentReady] = useState(false)
  const documentEditor = useRef<SuperDocDocumentHandle>(null)
  const [blob, setBlob] = useState<Blob | null>(null),
    [previewBusy, setPreviewBusy] = useState(false),
    [previewError, setPreviewError] = useState(false)
  const [comment, setComment] = useState('')
  const [inspectorTab, setInspectorTab] = useState(() =>
    new URLSearchParams(window.location.search).has('planId')
      ? 'history'
      : 'review',
  )
  const inspectorRegion = useRef<HTMLDivElement>(null)
  const [templates, setTemplates] = useState<TemplateVO[]>([]),
    [templateOpen, setTemplateOpen] = useState(false),
    [importing, setImporting] = useState(false)
  const file = useRef<HTMLInputElement>(null)
  const inlineEditor = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (editor) inlineEditor.current?.scrollIntoView?.({ block: 'start' })
  }, [editor])
  const meta = useMemo(
    () => ({
      name: latest?.nameOverride ?? activity.name,
      theme: latest?.themeOverride ?? activity.theme,
      orgName: latest?.organizerOverride ?? activity.organizer,
      time:
        latest?.timeOverride ??
        [
          activity.startDate,
          activity.endDate !== activity.startDate ? activity.endDate : null,
        ]
          .filter(Boolean)
          .join(' ~ '),
      location: latest?.locationOverride ?? activity.location,
      organizer: latest?.organizerOverride ?? activity.organizer,
      target: latest?.targetOverride ?? activity.targetAudience,
      endDate: latest
        ? latest.timeOverride || latest.createdAt
        : (activity.endDate ?? undefined),
    }),
    [activity, latest],
  )
  useEffect(() => {
    let cancelled = false
    setBlob(null)
    setPreviewBusy(true)
    setPreviewError(false)
    const prepare = latest?.documentFileId
      ? fetchPlanDocument(latest.id)
      : planToDocx(fromPlan(latest), meta, fromOverrides(latest), parseSectionOrder(latest?.sectionOrder))
    prepare
      .then((data) => {
        if (!cancelled) setBlob(data)
      })
      .catch(() => {
        if (!cancelled) setPreviewError(true)
      })
      .finally(() => {
        if (!cancelled) setPreviewBusy(false)
      })
    return () => {
      cancelled = true
    }
  }, [latest, meta])
  const openEditor = () => {
    if (!blob || previewBusy || versionBusy) return
    setEditBlob(blob)
    setDocumentReady(false)
    setDirty(false)
    setEditor(true)
  }
  const closeEditor = () => {
    if (saving) return
    if (document.fullscreenElement) {
      void document.exitFullscreen().then(closeEditor).catch(() => message.warning('请先退出全屏，再取消编辑。'))
      return
    }
    if (dirty)
      modal.confirm({
        title: '放弃未保存的策划内容？',
        okText: '放弃修改',
        cancelText: '继续编辑',
        onOk: () => setEditor(false),
      })
    else setEditor(false)
  }
  const save = async () => {
    if (!documentReady || !documentEditor.current || saving) return
    setSaving(true)
    try {
      const nativeDocx = await documentEditor.current.exportDocx()
      const uploaded = await uploadFile(new File([nativeDocx], '策划书.docx', {
        type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
      }), 'PLAN')
      const data: PlanSave = {
        activityId,
        expectedUpdatedAt: latest?.updatedAt,
        expectedRevision: latest?.revision,
        documentFileId: uploaded.id,
        ...fromOverrides(latest),
      }
      if (!latest) await createPlan(data)
      else await updatePlan(latest.id, data)
      await refresh()
      setEditor(false)
      setEditBlob(null)
      setDirty(false)
      message.success('策划书已保存')
    } catch {
      message.error('保存未完成，您的修改仍保留在编辑器中，请重试')
    } finally { setSaving(false) }
  }
  const importContent = async (selected: File) => {
    setImporting(true)
    try {
      if (!selected.name.toLowerCase().endsWith('.docx')) throw new Error('invalid docx')
      const { validateDocx } = await import('@/components/word/nativeDocx')
      await validateDocx(selected)
      setEditBlob(selected)
      setDocumentReady(false)
      setDirty(true)
      setEditor(true)
      setTemplateOpen(false)
      message.success('已导入为未保存的策划内容，请核对后保存')
    } catch {
      message.error('导入失败，请使用有效的 .docx 文件')
    } finally {
      setImporting(false)
    }
  }
  const exportDoc = async () => {
    if (!blob || versionBusy || previewBusy) return
    const url = URL.createObjectURL(blob),
      anchor = document.createElement('a')
    anchor.href = url
    anchor.download = `${activity.name}-策划书.docx`
    anchor.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  }
  const submit = async () => {
    if (!latest) return
    setSaving(true)
    try {
      await submitPlan(latest.id, reviewerIds)
      setSubmitOpen(false)
      await refresh()
      message.success('已提交审核')
    } catch {
      /* shared error */
    } finally {
      setSaving(false)
    }
  }
  const approve = async (approved: boolean) => {
    if (!latest) return
    if (!approved && !comment.trim()) {
      message.warning('驳回时请填写修改意见')
      return
    }
    setSaving(true)
    try {
      await reviewPlan(latest.id, approved, comment || undefined)
      setComment('')
      await refresh()
      message.success(
        approved
          ? '已记录您的通过意见，全部审核人通过后完成审核'
          : '已退回，请作者修订后重新提交',
      )
    } catch {
      /* shared error */
    } finally {
      setSaving(false)
    }
  }
  const canEdit =
    !!workspace?.canEdit &&
    (!latest || (isCurrent && latest.status === 'DRAFT'))
  const canRevise =
    !!workspace?.canEdit && isCurrent && latest?.status !== 'DRAFT'
  const canReview =
    isCurrent &&
    hasPerm('plan:review') &&
    latest?.status === 'PENDING' &&
    latest.reviewers?.some((r) => r.userId === userId && r.approved === null)
  return (
    <section className="activity-plan-surface">
      <header className="activity-panel-head">
        <div>
          <h2>活动策划书</h2>
          <p>一份文档，持续完善 · 提交时指定审核人</p>
        </div>
        <Space wrap>
          {latest && (
            <Button
              disabled={editor}
              onClick={() => {
                setInspectorTab('history')
                inspectorRegion.current?.scrollIntoView({ block: 'nearest' })
              }}
            >
              审核与版本
            </Button>
          )}
          {canEdit && !editor && (
            <>
              <Button
                icon={<UploadOutlined />}
                loading={importing}
                onClick={() => file.current?.click()}
              >
                导入 Word
              </Button>
              <Button
                onClick={() => {
                  setTemplateOpen(true)
                  listTemplates({ category: 'PLAN' })
                    .then(setTemplates)
                    .catch(() => {})
                }}
              >
                从模板库导入
              </Button>
            </>
          )}
          <Button
            icon={<DownloadOutlined />}
            disabled={!blob || previewBusy || editor || versionBusy}
            onClick={exportDoc}
          >
            下载 Word
          </Button>
          {canRevise && !editor && (
            <Button
              disabled={saving}
              onClick={() => {
                if (latest?.status === 'PENDING')
                  modal.confirm({
                    title: '撤回审核并修订？',
                    content:
                      '原版和审核记录将保留为历史版本，生成仅作者可见的新草稿。',
                    okText: '撤回并修订',
                    onOk: revision,
                  })
                else void revision()
              }}
            >
              {latest?.status === 'PENDING' ? '撤回并修订' : '修订新版本'}
            </Button>
          )}
          {canEdit && !editor && (
            <Button type="primary" icon={<EditOutlined />} onClick={openEditor} disabled={previewBusy || !blob || versionBusy}>
              {latest ? '编辑策划' : '创建策划书'}
            </Button>
          )}
        </Space>
      </header>
      {workspace?.currentHidden && (
        <p className="activity-plan-visibility" role="status">
          当前版本仅作者与所选审核人可见。您可以阅读下方已保存的历史版本。
        </p>
      )}
      {!!workspace?.versions?.length && (
        <div className="activity-plan-versionbar">
          <span>版本</span>
          <Select
            aria-label="选择策划书版本"
            value={latest?.id}
            disabled={editor || saving || versionBusy}
            onChange={(id) => {
              void pickVersion(id).catch(() => {})
            }}
            style={{ minWidth: 200 }}
            options={workspace.versions.map((v) => ({
              value: v.id,
              label: `v${v.version} · ${labels[v.status]}${v.id === workspace.currentId ? ' · 当前版本' : ' · 历史只读'}`,
            }))}
          />
          <span>
            {isCurrent
              ? '当前版本 · 仅作者与所选审核人可见'
              : '历史版本 · 仅供查阅'}
          </span>
        </div>
      )}
      {latest && (
        <div className="activity-plan-review">
          <span
            className={`tone-tag tone-${{ DRAFT: 'neutral', PENDING: 'warning', APPROVED: 'success', REJECTED: 'danger', WITHDRAWN: 'neutral' }[latest.status]}`}
          >
            {labels[latest.status]}
          </span>{' '}
          <span>
            {labels[latest.status]} / v{latest.version ?? 1} / 更新于{' '}
            {latest.updatedAt?.replace('T', ' ').slice(0, 16)}
          </span>
          <Space>
            {hasPerm('plan:submit') && isCurrent && latest?.status === 'DRAFT' && latest.ownerId === userId && !editor && (
              <Button type="primary" onClick={chooseReviewers} loading={saving}>
                提交审核
              </Button>
            )}
            {canReview && !editor && (
              <Button
                type="primary"
                onClick={() => {
                  setComment('')
                  setInspectorTab('review')
                  inspectorRegion.current?.scrollIntoView({ block: 'nearest' })
                }}
              >
                审核策划书
              </Button>
            )}
          </Space>
        </div>
      )}
      <div className="plan-document-workspace">
        <div className="plan-document-main">
          {editor ? (
            <div
              ref={inlineEditor}
              className="activity-plan-inline-editor"
              aria-label="文档内编辑"
            >
              <div className="activity-plan-edit-actions">
                <div>
                  <strong>正在编辑策划书</strong>
                  <span>{dirty ? '有未保存的修改' : '编辑后点击保存'}</span>
                </div>
                <Space>
                  <Button onClick={closeEditor} disabled={saving}>
                    取消编辑
                  </Button>
                  <Button loading={saving} disabled={!documentReady} type="primary" onClick={() => void save()}>
                    保存策划书
                  </Button>
                </Space>
              </div>
              {editBlob && <Suspense fallback={<div className="activity-plan-loading">正在加载文档编辑器…</div>}>
                <SuperDocDocument ref={documentEditor} document={editBlob} editing disabled={saving}
                  author={authorName}
                  onReady={setDocumentReady} onChange={() => setDirty(true)} />
              </Suspense>}

            </div>
          ) : versionBusy ? (
            <div className="activity-plan-loading">
              <Spin tip="正在切换版本" />
            </div>
          ) : previewError ? (
            <p role="alert" className="activity-inline-note error">
              策划书加载失败，请重新载入活动后重试。
            </p>
          ) : latest ? (
            previewBusy || !blob ? <div className="activity-plan-loading"><Spin /></div> :
              <Suspense fallback={<div className="activity-plan-loading">正在加载文档预览…</div>}>
                <SuperDocDocument key={`${latest.id}-${latest.updatedAt}`} document={blob} editing={false}
                  author="策划书读者" />
              </Suspense>
          ) : (
            <Empty
              description={
                workspace?.currentHidden
                  ? '您尚无可查看的历史版本'
                  : '还没有策划书，创建后可按 A4 文档阅读与审核。'
              }
            />
          )}
        </div>
        <div className="plan-inspector-column" ref={inspectorRegion}>
          <PlanInspector
            workspace={workspace}
            shown={latest}
            editing={editor}
            busy={saving || versionBusy}
            activeTab={inspectorTab}
            onTab={setInspectorTab}
            onSelect={(id) => {
              void pickVersion(id).catch(() => {})
            }}
            canReview={!!canReview && !editor}
            comment={comment}
            onComment={setComment}
            onReview={(approved) => {
              void approve(approved)
            }}
          />
        </div>
      </div>
      <FormSurface
        mode="dialog"
        title="选择审核人"
        open={submitOpen}
        onCancel={() => !saving && setSubmitOpen(false)}
        description="本次提交仅作者与所选审核人可查看；其他用户只能查看历史版本。全部所选审核人通过后，审核完成。"
        width={600}
        footer={
          <Space>
            <Button disabled={saving} onClick={() => setSubmitOpen(false)}>
              取消
            </Button>
            <Button
              type="primary"
              loading={saving}
              disabled={!reviewerIds.length || candidateBusy}
              onClick={submit}
            >
              提交给 {reviewerIds.length} 位审核人
            </Button>
          </Space>
        }
      >
        <Input
          aria-label="搜索审核人"
          placeholder="搜索姓名、部门或角色"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
        />
        <Spin spinning={candidateBusy}>
          <div className="activity-plan-reviewer-list">
            {candidateError ? (
              <p role="alert">审核人加载失败，请关闭后重新打开。</p>
            ) : !candidateBusy && !candidates.length ? (
              <Empty description="暂无可用审核人，请先为组织成员配置活动审核权限" />
            ) : (
              candidates
                .filter((c) =>
                  `${c.name} ${c.deptName} ${c.roleName}`.includes(search),
                )
                .map((c) => (
                  <Checkbox
                    key={c.id}
                    checked={reviewerIds.includes(c.id)}
                    onChange={(e) =>
                      setReviewerIds((ids) =>
                        e.target.checked
                          ? [...ids, c.id]
                          : ids.filter((id) => id !== c.id),
                      )
                    }
                  >
                    <strong>{c.name}</strong>
                    <span>
                      {[c.deptName, c.roleName].filter(Boolean).join(' · ')}
                    </span>
                  </Checkbox>
                ))
            )}
          </div>
        </Spin>
      </FormSurface>
      <input
        ref={file}
        type="file"
        accept=".docx"
        hidden
        onChange={(e) => {
          const f = e.target.files?.[0]
          e.target.value = ''
          if (f) void importContent(f)
        }}
      />
      <FormSurface
        mode="drawer"
        title="选择策划书模板"
        open={templateOpen}
        onCancel={() => setTemplateOpen(false)}
        footer={<Button onClick={() => setTemplateOpen(false)}>关闭</Button>}
      >
        <Spin spinning={importing}>
          {templates.length ? (
            templates.map((t) => (
              <div className="activity-template-row" key={t.id}>
                <span>{t.name}</span>
                <Button
                  disabled={!t.fileId}
                  onClick={async () => {
                    if (t.fileId) {
                      try {
                        const data = await fetchFileBlob(t.fileId)
                        await importContent(
                          new File([data], t.originFilename || 'template.docx'),
                        )
                      } catch {
                        /* shared error */
                      }
                    }
                  }}
                >
                  使用模板
                </Button>
              </div>
            ))
          ) : (
            <Empty description="暂无策划书模板" />
          )}
        </Spin>
      </FormSurface>
    </section>
  )
}
