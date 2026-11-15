import { useState } from 'react'
import { Button, Form, Input, InputNumber, Select, Skeleton } from 'antd'
import { Link, useParams } from 'react-router-dom'
import { get, post, put } from '@/api/http'
import type { PageResult } from '@/api/types'
import { ACTIVITY_STATUS_LABEL } from '@/api/activityStatus'
import { LoadError, Panel, StatusTag } from '@/components/admin/AdminSurface'
import PageHeader from '@/components/glass/PageHeader'
import { useAuthStore } from '@/stores/auth'
import { ChoiceSelect, dateLabel, downloadText, PackDialog, RecordList, usePackQuery } from './PackSurface'

const STATES = { REGISTERED: '已报名', PENDING: '待审核', RETURNED: '已退回', APPROVED: '已确认' }
type State = keyof typeof STATES
type Event = { id: number; activityId: number; name: string; startDate?: string; activityDeleted: number;
  status: string; maxMinutes: number; registered: number; pending: number; approvedMinutes: number }
type Volunteer = { id: number; memberName: string; studentNo: string; signinAt?: string; hasSignin: number;
  serviceMinutes?: number; status: State; decision?: string; reviewedAt?: string; version: number }
type Details = { activity: Pick<Event, 'activityId' | 'name' | 'maxMinutes' | 'activityDeleted'>; records: PageResult<Volunteer> }
type Editor = { type: 'event' } | { type: 'register'; activity: number } | { type: 'submit'; row: Volunteer; maximum: number }
  | { type: 'review'; row: Volunteer; approved: boolean }

const needed = (message: string) => [{ required: true, message }]
const minutes = (value: number) => value < 60 ? `${value} 分钟` : `${Math.floor(value / 60)} 小时${value % 60 ? ` ${value % 60} 分钟` : ''}`

export default function VolunteerWorkspace() {
  const { id } = useParams()
  const permissions = useAuthStore(s => s.user?.permissions) ?? []
  const canManage = permissions.includes('volunteer:manage')
  const canReview = permissions.includes('volunteer:review')
  const canOpenActivity = permissions.includes('activity:view')
  const [page, setPage] = useState(1)
  const [status, setStatus] = useState<State>()
  const [editor, setEditor] = useState<Editor | null>(null)
  const [exportError, setExportError] = useState('')
  const [exporting, setExporting] = useState(false)
  const query = usePackQuery<Details | PageResult<Event>>(id ? `/volunteer/activities/${id}` : '/volunteer/activities', page, status)
  const detail = query.data && 'activity' in query.data ? query.data : undefined

  async function exportApproved() {
    if (exporting || !detail) return
    setExporting(true)
    setExportError('')
    try {
      const rows = await get<Volunteer[]>(`/volunteer/activities/${id}/export`)
      if (!rows.length) { setExportError('此活动暂时没有已确认的服务时长。'); return }
      const cell = (value: string | number | undefined) => {
        let text = String(value ?? '')
        if (/^[\s]*[=+\-@]|^[\t\r\n]/.test(text)) text = `'${text}`
        return `"${text.replaceAll('"', '""')}"`
      }
      downloadText(`志愿服务记录-${id}.csv`, ['活动,姓名,学号,签到时间,核定分钟,审核时间', ...rows.map(row =>
        [detail.activity.name, row.memberName, row.studentNo, dateLabel(row.signinAt), row.serviceMinutes, dateLabel(row.reviewedAt)].map(cell).join(','),
      )].join('\r\n'), 'text/csv')
    } catch { setExportError('服务记录导出失败，请重试。') }
    finally { setExporting(false) }
  }

  return <div className="admin-v6 pack-workspace">
    <PageHeader title={id ? detail?.activity.name ?? '志愿活动' : '志愿服务'}
      extra={id ? <Link to="/volunteer">返回志愿活动</Link> : canManage && <Button type="primary" onClick={() => setEditor({ type: 'event' })}>添加志愿活动</Button>} />
    {id ? query.error ? <LoadError message={query.error} retry={query.reload} /> : !detail ? <Skeleton paragraph={{ rows: 5 }} /> :
      <Panel title="服务记录" description={`每人核定上限 ${minutes(detail.activity.maxMinutes)}${detail.activity.activityDeleted ? ' · 原活动已删除' : ''}`}
        extra={canManage && !detail.activity.activityDeleted && <Button type="primary" onClick={() => setEditor({ type: 'register', activity: Number(id) })}>登记报名</Button>}>
        <div className="pack-filter">
          <Select aria-label="服务记录状态" value={status} allowClear placeholder="全部状态" style={{ minWidth: 128 }}
            onChange={value => { setStatus(value); setPage(1) }} options={Object.entries(STATES).map(([value, label]) => ({ value, label }))} />
          {canOpenActivity && !detail.activity.activityDeleted && <Link to={`/activities/${detail.activity.activityId}?tab=signin`}>打开活动签到</Link>}
          {canReview && <Button loading={exporting} onClick={() => void exportApproved()}>导出已确认记录</Button>}
        </div>
        {exportError && <p className="pack-error" role="alert">{exportError}</p>}
        <RecordList result={detail.records} reload={query.reload} page={page} onPage={setPage} empty={status ? '暂无此状态的记录' : '暂无报名记录'}
          render={row => <><div><h4>{row.memberName}</h4><p>{row.studentNo} · {row.signinAt ? `签到 ${dateLabel(row.signinAt)}` : row.hasSignin ? '已匹配签到' : '未匹配签到'}
            {row.serviceMinutes != null && ` · ${minutes(row.serviceMinutes)}`}</p>
            {row.decision && <p>{row.status === 'PENDING' ? '上次审核' : '审核意见'}：{row.decision}</p>}</div>
            <div className="pack-row-actions"><StatusTag tone={row.status === 'APPROVED' ? 'success' : row.status === 'RETURNED' ? 'warning' : 'neutral'}>{STATES[row.status]}</StatusTag>
              {canManage && !detail.activity.activityDeleted && ['REGISTERED', 'RETURNED'].includes(row.status) && !!row.hasSignin &&
                <Button onClick={() => setEditor({ type: 'submit', row, maximum: detail.activity.maxMinutes })}>{row.status === 'RETURNED' ? '重新核定' : '核定时长'}</Button>}
              {canReview && row.status === 'PENDING' && <><Button onClick={() => setEditor({ type: 'review', row, approved: true })}>确认时长</Button>
                <Button onClick={() => setEditor({ type: 'review', row, approved: false })}>退回</Button></>}
            </div></>} />
      </Panel> : <Panel title="志愿活动" description="选择活动查看报名、待审核记录和已确认时长。">
      <RecordList result={query.data && 'total' in query.data ? query.data : undefined} error={query.error} reload={query.reload}
        page={page} onPage={setPage} empty="暂无志愿活动" render={row => <><div><h4>{row.name}</h4>
          <p>{dateLabel(row.startDate)} · {row.activityDeleted ? '原活动已删除' : ACTIVITY_STATUS_LABEL[row.status] || row.status}</p>
          <p>{row.registered} 人报名 · {row.pending} 条待审核 · 已确认 {minutes(row.approvedMinutes)}</p></div>
          <Link to={`/volunteer/${row.id}`}>管理服务记录</Link></>} />
    </Panel>}
    {editor && <VolunteerEditor editor={editor} onClose={() => setEditor(null)} reload={query.reload} canCreate={permissions.includes('activity:create')} />}
  </div>
}

function VolunteerEditor({ editor, onClose, reload, canCreate }: {
  editor: Editor; onClose: () => void; reload: () => void; canCreate: boolean
}) {
  const [form] = Form.useForm()
  const [create, setCreate] = useState(false)
  async function save() {
    const values = await form.validateFields()
    switch (editor.type) {
      case 'event': await post('/volunteer/activities', create ? { ...values, activityId: undefined } : { ...values, name: undefined, date: undefined }); break
      case 'register': await post(`/volunteer/activities/${editor.activity}/registrations`, values); break
      case 'submit': await put(`/volunteer/records/${editor.row.id}/submit`, { ...values, version: editor.row.version }); break
      case 'review': await put(`/volunteer/records/${editor.row.id}/review`, { ...values, version: editor.row.version, approved: editor.approved }); break
    }
    reload()
  }
  const title = editor.type === 'event' ? '添加志愿活动' : editor.type === 'register' ? '登记志愿者报名' :
    editor.type === 'submit' ? '核定服务时长' : editor.approved ? '确认服务时长' : '退回服务记录'
  const action = editor.type === 'submit' ? '提交审核' : editor.type === 'review' ? (editor.approved ? '确认时长' : '确认退回') : '保存'
  const initialValues = editor.type === 'submit' ? { minutes: editor.row.serviceMinutes } : undefined
  return <PackDialog title={title} action={action} onClose={onClose} onSave={save}>
    <Form form={form} layout="vertical" initialValues={initialValues}>
      {editor.type === 'event' && <>
        {canCreate && <Form.Item label="活动来源"><Select aria-label="活动来源" value={create ? 'new' : 'existing'}
          options={[{ value: 'existing', label: '关联已有活动' }, { value: 'new', label: '创建新活动' }]}
          onChange={value => setCreate(value === 'new')} /></Form.Item>}
        {create ? <><Form.Item name="name" label="活动名称" preserve={false} rules={needed('请填写活动名称')}><Input maxLength={100} /></Form.Item>
          <Form.Item name="date" label="活动日期" preserve={false} rules={needed('请选择日期')}><Input type="date" /></Form.Item></> :
          <Form.Item name="activityId" label="已有活动" preserve={false} rules={needed('请选择活动')}><ChoiceSelect url="/volunteer/choices/activities" placeholder="查询活动名称" /></Form.Item>}
        <Form.Item name="maxMinutes" label="每人时长上限（分钟）" rules={needed('请填写时长上限')}><InputNumber min={1} max={10080} precision={0} /></Form.Item>
      </>}
      {editor.type === 'register' && <Form.Item name="memberId" label="志愿者" extra="报名学号用于核对同一活动的签到。" rules={needed('请选择志愿者')}>
        <ChoiceSelect url="/volunteer/choices/members" placeholder="按姓名或学号查询" />
      </Form.Item>}
      {editor.type === 'submit' && <><p>{editor.row.memberName} · {editor.row.studentNo}</p>
        <Form.Item name="minutes" label="核定时长（分钟）" extra={`按实际服务填写，上限 ${editor.maximum} 分钟。`}
          rules={needed('请填写服务时长')}><InputNumber min={1} max={editor.maximum} precision={0} /></Form.Item></>}
      {editor.type === 'review' && <><p>{editor.row.memberName} · {minutes(editor.row.serviceMinutes ?? 0)} · 签到 {dateLabel(editor.row.signinAt)}</p>
        <Form.Item name="decision" label={editor.approved ? '审核意见（选填）' : '退回原因'} rules={editor.approved ? undefined : needed('请填写退回原因')}>
          <Input.TextArea rows={4} maxLength={4000} />
        </Form.Item></>}
    </Form>
  </PackDialog>
}
