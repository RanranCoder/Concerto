import { useState } from 'react'
import { Button, Form, Input, InputNumber, Select, Skeleton, Tabs } from 'antd'
import { Link, useSearchParams } from 'react-router-dom'
import { get, post, put } from '@/api/http'
import type { PageResult } from '@/api/types'
import { LoadError, Panel, StatusTag } from '@/components/admin/AdminSurface'
import FormSurface from '@/components/form/FormSurface'
import PageHeader from '@/components/glass/PageHeader'
import { useAuthStore } from '@/stores/auth'
import { useOrgStore } from '@/stores/org'
import { ChoiceSelect, dateLabel, downloadText, PackDialog, RecordList, usePackQuery } from './PackSurface'
import YouthCaseHistory from './YouthCaseHistory'

const LIFE = { ASSEMBLY: '团员大会', COMMITTEE: '支委会', GROUP: '团小组会', LESSON: '团课', THEME_DAY: '主题团日' }
const AGENDA_TEMPLATES: Record<keyof typeof LIFE, string> = {
  ASSEMBLY: '核对参会人员\n通报议题\n讨论并记录决议\n明确后续分工',
  COMMITTEE: '回顾支部工作\n讨论本次议题\n确认分工和完成时间',
  GROUP: '交流学习情况\n讨论小组事项\n记录意见与后续安排',
  LESSON: '介绍学习主题\n开展专题学习\n交流讨论并记录要点',
  THEME_DAY: '说明活动主题与安排\n开展学习或实践\n交流心得\n汇总签到与活动材料',
}
const STATES = { PENDING: '待审核', RETURNED: '已退回', APPROVED: '已通过', ARCHIVED: '已归档', RECEIVED: '党务已接收' }
const CASE_NAMES = { EVALUATION: '教育评议', RECOMMENDATION: '推优入党' }
type Kind = keyof typeof CASE_NAMES
type Member = { id: number; memberId: number; memberName: string; studentNo?: string; branchId: number; branchName?: string;
  duty?: string; joinedOn?: string; version: number }
type Life = { id: number; activityId: number; activityName: string; activityDeleted: number; branchId: number; branchName?: string;
  kind: keyof typeof LIFE; agenda: string; minutes?: string; startDate?: string; archivedAt?: string; version: number }
type YouthCase = { id: number; youthMemberId: number; memberName: string; branchName?: string; yearNo: number; kind: Kind;
  submission: string; criteria?: string; status: keyof typeof STATES; decision?: string; reviewedAt?: string; receivedAt?: string; version: number }
type Overview = { branches: { id: number; name: string; memberCount: number }[]; pendingReviews: number; pendingMinutes: number;
  recentLife: { id: number; name: string; kind: keyof typeof LIFE; startDate?: string; archivedAt?: string }[] }
type Editor = { type: 'member'; row?: Member } | { type: 'life'; row?: Life } | { type: 'case'; kind: Kind; row?: YouthCase }
  | { type: 'review'; row: YouthCase; approved: boolean } | { type: 'receive'; row: YouthCase }
  | { type: 'archiveLife'; row: Life } | { type: 'archiveCase'; row: YouthCase }
  | { type: 'history'; row: Member } | { type: 'readLife'; row: Life } | { type: 'readCase'; row: YouthCase }

const needed = (message: string) => [{ required: true, message }]

export default function YouthWorkspace() {
  const [params, setParams] = useSearchParams()
  const tabs = ['overview', 'members', 'life', 'EVALUATION', 'RECOMMENDATION']
  const current = params.get('tab') ?? 'overview'
  const tab = tabs.includes(current) ? current : 'overview'
  const [editor, setEditor] = useState<Editor | null>(null)
  const [revision, setRevision] = useState(0)
  const permissions = useAuthStore(s => s.user?.permissions) ?? []
  const canManage = permissions.includes('youth:manage')
  const canReview = permissions.includes('youth:review')
  const partyEnabled = useOrgStore(s => s.enabledPacks.includes('party'))
  const canReceive = partyEnabled && permissions.includes('party:manage')
  const canOpenActivity = permissions.includes('activity:view')
  const go = (key: string) => setParams(key === 'overview' ? {} : { tab: key })
  const saved = () => setRevision(n => n + 1)
  return <div className="admin-v6 pack-workspace">
    <PageHeader title="团务工作"
      extra={canManage && <Button type="primary" onClick={() => setEditor({ type: 'life' })}>开展组织生活</Button>} />
    <Tabs activeKey={tab} onChange={go} items={tabs.map((key, i) => ({ key, label: ['概览', '团员与支部', '组织生活', '教育评议', '推优入党'][i] }))} />
    {tab === 'overview' ? <YouthOverview key={revision} go={go} /> :
      <YouthList key={tab} tab={tab} revision={revision} onEdit={setEditor} canManage={canManage} canReview={canReview}
        canReceive={canReceive} canOpenActivity={canOpenActivity} />}
    {editor && <YouthEditor editor={editor} close={() => setEditor(null)} saved={saved}
      canCreateActivity={permissions.includes('activity:create')} />}
  </div>
}

function YouthOverview({ go }: { go: (key: string) => void }) {
  const query = usePackQuery<Overview>('/youth/overview')
  if (query.error) return <LoadError message={query.error} retry={query.reload} />
  if (!query.data) return <Skeleton paragraph={{ rows: 5 }} />
  const { branches, pendingReviews, pendingMinutes, recentLife } = query.data
  return <div className="pack-overview">
    <div>
      <Panel title="待办事项"><ul className="pack-rows">
        <li><div><h4>评议与推优待审核</h4><p>{pendingReviews} 条</p></div><div className="pack-row-actions">
          <Button onClick={() => go('EVALUATION')}>查看评议</Button><Button onClick={() => go('RECOMMENDATION')}>查看推优</Button>
        </div></li>
        <li><div><h4>组织生活待归档</h4><p>{pendingMinutes} 场</p></div><Button onClick={() => go('life')}>查看记录</Button></li>
      </ul></Panel>
      <Panel title="近期组织生活">{recentLife.length ? <ul className="pack-rows">{recentLife.map(row => <li key={row.id}>
        <div><h4>{row.name}</h4><p>{LIFE[row.kind]} · {dateLabel(row.startDate)}</p></div>
        <StatusTag tone={row.archivedAt ? 'success' : 'neutral'}>{row.archivedAt ? '已归档' : '待归档'}</StatusTag>
      </li>)}</ul> : <p>暂无组织生活记录。</p>}</Panel>
    </div>
    <Panel title="支部工作情况" extra={<Button type="link" onClick={() => go('members')}>团员与支部</Button>}>
      {branches.length ? <ul className="pack-rows">{branches.map(branch => <li key={branch.id}><strong>{branch.name}</strong><span>{branch.memberCount} 名团员</span></li>)}</ul>
        : <p>关联团员和所属支部后，即可查看支部人数。</p>}
    </Panel>
  </div>
}

function YouthList({ tab, revision, onEdit, canManage, canReview, canReceive, canOpenActivity }: {
  tab: string; revision: number; onEdit: (editor: Editor) => void; canManage: boolean; canReview: boolean; canReceive: boolean; canOpenActivity: boolean
}) {
  const [page, setPage] = useState(1)
  const [exportError, setExportError] = useState('')
  const kind = tab as Kind
  const endpoint = tab === 'members' || tab === 'life' ? `/youth/${tab}` : `/youth/cases?kind=${kind}`
  const query = usePackQuery<PageResult<Member | Life | YouthCase>>(endpoint, page, undefined, revision)
  const title = tab === 'members' ? '团员档案' : tab === 'life' ? '组织生活记录' : CASE_NAMES[kind]
  const add = tab === 'members' ? '关联成员' : tab === 'life' ? '开展组织生活' : kind === 'EVALUATION' ? '提交评议' : '发起推优'
  async function exportCase(id: number) {
    setExportError('')
    try {
      const row = await get<YouthCase>(`/youth/cases/${id}/export`)
      downloadText(`推优材料-${row.id}.txt`, [
        '推优入党材料', `${row.memberName}　${row.branchName ?? '未标注支部'}　${row.yearNo} 年`,
        `状态　${STATES[row.status]}`, '', row.submission, '', `审核意见　${row.decision ?? '—'}`, `审核时间　${dateLabel(row.reviewedAt)}`,
      ].join('\r\n'))
    } catch { setExportError('推优材料导出失败，请刷新后重试。') }
  }
  return <Panel title={title} extra={canManage && tab !== 'life' && <Button type="primary" onClick={() => onEdit(
    tab === 'members' ? { type: 'member' } : { type: 'case', kind },
  )}>{add}</Button>}>
    {exportError && <p className="pack-error" role="alert">{exportError}</p>}
    <RecordList result={query.data} error={query.error} reload={query.reload} page={page} onPage={setPage}
      empty={`暂无${title}`} render={row => {
        if ('memberId' in row) return <><div><h4>{row.memberName}</h4><p>{[row.branchName, row.duty, row.studentNo].filter(Boolean).join(' · ')}</p></div>
          <div className="pack-row-actions"><Button onClick={() => onEdit({ type: 'history', row })}>关系变更</Button>
            {canManage && <Button onClick={() => onEdit({ type: 'member', row })}>编辑档案</Button>}</div></>
        if ('activityId' in row) return <><div><h4>{row.activityName}</h4><p>{[LIFE[row.kind], row.branchName, dateLabel(row.startDate)].filter(Boolean).join(' · ')}</p></div>
          <div className="pack-row-actions"><StatusTag tone={row.archivedAt ? 'success' : 'neutral'}>{row.archivedAt ? '已归档' : row.minutes ? '待归档' : '待补纪要'}</StatusTag>
            {canOpenActivity && !row.activityDeleted && <Link to={`/activities/${row.activityId}`}>活动与材料</Link>}
            <Button onClick={() => onEdit({ type: 'readLife', row })}>查看</Button>
            {canManage && !row.archivedAt && <Button onClick={() => onEdit({ type: 'life', row })}>编辑</Button>}
            {canManage && !row.archivedAt && row.minutes && <Button onClick={() => onEdit({ type: 'archiveLife', row })}>归档</Button>}</div></>
        return <><div><h4>{row.memberName}</h4><p>{row.yearNo} 年 · {row.branchName || '支部未标注'}</p></div>
          <div className="pack-row-actions"><StatusTag tone={row.status === 'RETURNED' ? 'warning' : row.status === 'PENDING' ? 'neutral' : 'success'}>{STATES[row.status]}</StatusTag>
            <Button onClick={() => onEdit({ type: 'readCase', row })}>查看材料</Button>
            {row.status === 'PENDING' && canReview && <><Button onClick={() => onEdit({ type: 'review', row, approved: true })}>通过</Button>
              <Button onClick={() => onEdit({ type: 'review', row, approved: false })}>退回</Button></>}
            {row.status === 'RETURNED' && canManage && <Button onClick={() => onEdit({ type: 'case', kind, row })}>修改并重提</Button>}
            {row.status === 'APPROVED' && kind === 'EVALUATION' && canReview && <Button onClick={() => onEdit({ type: 'archiveCase', row })}>年度归档</Button>}
            {row.status === 'APPROVED' && kind === 'RECOMMENDATION' && canReceive && <Button onClick={() => onEdit({ type: 'receive', row })}>党务接收</Button>}
            {['APPROVED', 'RECEIVED'].includes(row.status) && kind === 'RECOMMENDATION' && canReview && <Button onClick={() => void exportCase(row.id)}>导出材料</Button>}
          </div></>
      }} />
  </Panel>
}

function YouthEditor({ editor, close, saved, canCreateActivity }: {
  editor: Editor; close: () => void; saved: () => void; canCreateActivity: boolean
}) {
  if (editor.type === 'history') return <BranchHistory row={editor.row} close={close} />
  if (editor.type === 'readLife' || editor.type === 'readCase') {
    const life = editor.type === 'readLife' ? editor.row : null
    const record = editor.type === 'readCase' ? editor.row : null
    return <FormSurface open mode="drawer" title={life?.activityName ?? record?.memberName} onCancel={close}>
      {life && <div className="pack-reading"><div><strong>议题</strong><p className="pack-note">{life.agenda}</p></div>
        <div><strong>纪要</strong><p className="pack-note">{life.minutes || '尚未补录'}</p></div></div>}
      {record && <div className="pack-reading">{record.criteria && <div><strong>评议依据</strong><p className="pack-note">{record.criteria}</p></div>}
        <div><strong>提交材料</strong><p className="pack-note">{record.submission}</p></div>
        <div><strong>审核意见</strong><p className="pack-note">{record.decision || '尚未审核'}</p></div>
        {record.receivedAt && <div><strong>接收时间</strong><p>{dateLabel(record.receivedAt)}</p></div>}
        <YouthCaseHistory id={record.id} /></div>}
    </FormSurface>
  }
  return <YouthForm editor={editor} close={close} saved={saved} canCreateActivity={canCreateActivity} />
}

function BranchHistory({ row, close }: { row: Member; close: () => void }) {
  const query = usePackQuery<{ changedAt: string; fromBranch?: string; toBranch?: string; actor?: string }[]>(`/youth/members/${row.id}/history`)
  return <FormSurface open mode="drawer" title={`${row.memberName}的支部关系`} onCancel={close}>
    {query.error ? <LoadError message={query.error} retry={query.reload} /> : !query.data ? <Skeleton /> :
      <ul className="pack-rows">{query.data.map((change, i) => <li key={i}><div><h4>{change.fromBranch ? `${change.fromBranch} → ` : '首次登记 · '}{change.toBranch || '原组织节点'}</h4>
        <p>{dateLabel(change.changedAt)}{change.actor && ` · ${change.actor}`}</p></div></li>)}</ul>}
  </FormSurface>
}

function YouthForm({ editor, close, saved, canCreateActivity }: {
  editor: Exclude<Editor, { type: 'history' | 'readLife' | 'readCase' }>; close: () => void; saved: () => void; canCreateActivity: boolean
}) {
  const [form] = Form.useForm()
  const agenda = Form.useWatch('agenda', form)
  const lifeKind: keyof typeof LIFE = Form.useWatch('kind', form) ?? 'THEME_DAY'
  const [newActivity, setNewActivity] = useState(false)
  let title: string
  let fields: React.ReactNode
  let initial: Record<string, unknown> = {}
  let action = '保存'
  async function save() {
    const values = await form.validateFields()
    switch (editor.type) {
      case 'member':
        if (editor.row) await put(`/youth/members/${editor.row.id}`, { ...values, memberId: editor.row.memberId, version: editor.row.version })
        else await post('/youth/members', values)
        break
      case 'life':
        if (editor.row) await put(`/youth/life/${editor.row.id}`, { ...values, version: editor.row.version })
        else await post('/youth/life', newActivity ? { ...values, activityId: undefined } : { ...values, date: undefined, name: undefined })
        break
      case 'case': await post('/youth/cases', { ...values, kind: editor.kind,
        ...(editor.row ? { youthMemberId: editor.row.youthMemberId, year: editor.row.yearNo, version: editor.row.version } : {}) }); break
      case 'review': await put(`/youth/cases/${editor.row.id}/review`, { ...values, version: editor.row.version, approved: editor.approved }); break
      case 'receive': await put(`/youth/cases/${editor.row.id}/receive`, { ...values, version: editor.row.version }); break
      case 'archiveLife': await put(`/youth/life/${editor.row.id}/archive`, { version: editor.row.version }); break
      case 'archiveCase': await put(`/youth/cases/${editor.row.id}/archive`, { version: editor.row.version }); break
    }
    saved()
  }
  switch (editor.type) {
    case 'member': {
      const row = editor.row
      title = row ? `编辑${row.memberName}的团员档案` : '关联成员'
      initial = row ? { branchId: row.branchId, joinedOn: row.joinedOn, duty: row.duty } : {}
      fields = <>{!row && <Form.Item name="memberId" label="现有成员" rules={needed('请选择成员')}>
        <ChoiceSelect url="/youth/choices/members" placeholder="按姓名或学号查询" />
      </Form.Item>}
        <Form.Item name="branchId" label="所属支部" extra="选择组织架构中对应的节点。" rules={needed('请选择支部')}>
          <ChoiceSelect url="/youth/choices/branches" initial={row && { id: row.branchId, name: row.branchName ?? `节点 ${row.branchId}` }} placeholder="查询组织节点" />
        </Form.Item>
        <Form.Item name="duty" label="团内职务"><Input maxLength={60} /></Form.Item>
        <Form.Item name="joinedOn" label="入团日期"><Input type="date" /></Form.Item></>
      break
    }
    case 'life': {
      const row = editor.row
      title = row ? '编辑组织生活' : '开展组织生活'
      initial = row ? { branchId: row.branchId, kind: row.kind, agenda: row.agenda, minutes: row.minutes } : { kind: 'THEME_DAY' }
      fields = <>{!row && <>
        {canCreateActivity && <Form.Item label="活动来源"><Select aria-label="活动来源" value={newActivity ? 'new' : 'existing'}
          options={[{ value: 'existing', label: '关联已有活动' }, { value: 'new', label: '创建新活动' }]}
          onChange={value => setNewActivity(value === 'new')} /></Form.Item>}
        {newActivity ? <><Form.Item name="name" label="活动名称" preserve={false} rules={needed('请填写活动名称')}><Input maxLength={100} /></Form.Item>
          <Form.Item name="date" label="活动日期" preserve={false} rules={needed('请选择日期')}><Input type="date" /></Form.Item></> :
          <Form.Item name="activityId" label="已有活动" preserve={false} rules={needed('请选择活动')}><ChoiceSelect url="/youth/choices/activities" placeholder="查询活动名称" /></Form.Item>}
      </>}
        <Form.Item name="branchId" label="所属支部" rules={needed('请选择支部')}><ChoiceSelect url="/youth/choices/branches"
          initial={row && { id: row.branchId, name: row.branchName ?? `节点 ${row.branchId}` }} placeholder="查询组织节点" /></Form.Item>
        <Form.Item name="kind" label="组织生活类型" rules={needed('请选择类型')}><Select options={Object.entries(LIFE).map(([value, label]) => ({ value, label }))} /></Form.Item>
        {!row && !agenda && <Button onClick={() => form.setFieldValue('agenda', AGENDA_TEMPLATES[lifeKind])}>使用议题模板</Button>}
        <Form.Item name="agenda" label="议题" rules={needed('请填写议题')}><Input.TextArea rows={3} maxLength={4000} /></Form.Item>
        <Form.Item name="minutes" label="纪要" extra="签到和附件沿用关联活动，可在活动详情中维护。"><Input.TextArea rows={4} maxLength={8000} /></Form.Item></>
      break
    }
    case 'case':
      title = editor.row ? `重提${CASE_NAMES[editor.kind]}` : `提交${CASE_NAMES[editor.kind]}`
      action = '提交审核'
      initial = { year: new Date().getFullYear(), submission: editor.row?.submission, criteria: editor.row?.criteria }
      fields = <>{editor.row ? <p>{editor.row.memberName} · {editor.row.yearNo} 年</p> : <>
        <Form.Item name="youthMemberId" label="团员" rules={needed('请选择团员')}><ChoiceSelect url="/youth/choices/youth-members" placeholder="按姓名或学号查询" /></Form.Item>
        <Form.Item name="year" label="年度" rules={needed('请填写年度')}><InputNumber min={2000} max={2100} precision={0} /></Form.Item></>}
        {editor.kind === 'EVALUATION' && <Form.Item name="criteria" label="本次评议依据"><Input.TextArea rows={3} maxLength={4000} /></Form.Item>}
        <Form.Item name="submission" label={editor.kind === 'RECOMMENDATION' ? '资格核对、评议与推荐意见' : '参与情况与评议材料'} rules={needed('请填写提交材料')}>
          <Input.TextArea rows={7} maxLength={8000} />
        </Form.Item></>
      break
    case 'review':
      title = `${editor.approved ? '通过' : '退回'}${CASE_NAMES[editor.row.kind]}`
      action = editor.approved ? '确认通过' : '确认退回'
      fields = <><p>{editor.row.memberName} · {editor.row.yearNo} 年</p><p className="pack-note">{editor.row.submission}</p>
        {editor.row.criteria && <p className="pack-note">评议依据：{editor.row.criteria}</p>}
        <Form.Item name="decision" label="审核意见" rules={needed('请填写审核意见')}><Input.TextArea rows={4} maxLength={4000} /></Form.Item></>
      break
    case 'receive':
      title = '接收推优材料'
      action = '确认接收'
      fields = <><p>{editor.row.memberName} · {editor.row.yearNo} 年</p><p className="pack-note">{editor.row.submission}</p>
        <Form.Item name="partyMemberId" label="关联党务档案" rules={needed('请选择接收档案')}>
          <ChoiceSelect url={`/youth/cases/${editor.row.id}/party-matches`} placeholder="选择对应的党务档案" />
        </Form.Item></>
      break
    default:
      title = editor.type === 'archiveLife' ? '归档组织生活' : '归档年度评议'
      action = '确认归档'
      fields = <p>归档后可在本工作区查看已确认的记录。</p>
  }
  return <PackDialog title={title} action={action} onClose={close} onSave={save}>
    <Form form={form} layout="vertical" initialValues={initial}>{fields}</Form>
  </PackDialog>
}
