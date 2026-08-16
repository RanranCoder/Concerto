import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  App, Button, Form, Input, Modal, Popconfirm, Segmented, Select, Space, Table, Tag,
} from 'antd'
import type { TableColumnsType, UploadFile } from 'antd'
import {
  DeleteOutlined, DownloadOutlined, EditOutlined, ExportOutlined, PlusOutlined, ReloadOutlined, UploadOutlined,
} from '@ant-design/icons'
import GlassCard from '@/components/glass/GlassCard'
import GlassModal from '@/components/glass/GlassModal'
import GlassTable from '@/components/glass/GlassTable'
import PageHeader from '@/components/glass/PageHeader'
import { listDepts, type DeptVO } from '@/api/dept'
import {
  archiveSession, batchDeleteMembers, createMember, createMemberSession, deleteMember, deleteMemberSession,
  downloadImportTemplate, downloadMemberExport, getMemberStats, importMembers, listMemberSessions,
  listMembers, setCurrentMemberSession, updateMember, updateMemberSession,
  type MemberImportResult, type MemberSave, type MemberSessionVO, type MemberVO,
  POSITION_OPTIONS, STATUS_OPTIONS, STATUS_COLOR, STATUS_LABELS, POLITICAL_OPTIONS,
} from '@/api/member'

type MemberRecord = MemberVO & { key: number }

interface MemberFormValues extends MemberSave {}

export default function MemberList() {
  const { message, modal } = App.useApp()
  const [sessions, setSessions] = useState<MemberSessionVO[]>([])
  const [sessionId, setSessionId] = useState<number | undefined>()
  const [data, setData] = useState<MemberRecord[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(false)
  const [page, setPage] = useState(1)
  const [size, setSize] = useState(10)
  const [keyword, setKeyword] = useState('')
  const [deptFilter, setDeptFilter] = useState<number | undefined>()
  const [positionFilter, setPositionFilter] = useState<string | undefined>()
  const [statusFilter, setStatusFilter] = useState<string | undefined>()
  const [stats, setStats] = useState<{ total: number; byDept: { name: string; count: number }[] } | null>(null)

  const [depts, setDepts] = useState<DeptVO[]>([])
  const [modalOpen, setModalOpen] = useState(false)
  const [editing, setEditing] = useState<MemberVO | null>(null)
  const [saving, setSaving] = useState(false)
  const [form] = Form.useForm<MemberFormValues>()

  const [sessionModalOpen, setSessionModalOpen] = useState(false)
  const [editingSession, setEditingSession] = useState<MemberSessionVO | null>(null)
  const [sessionForm] = Form.useForm<{ name: string; remark?: string }>()

  const [importing, setImporting] = useState(false)
  const [importFileList, setImportFileList] = useState<UploadFile[]>([])
  const [importResult, setImportResult] = useState<MemberImportResult | null>(null)
  const [selectedRowKeys, setSelectedRowKeys] = useState<React.Key[]>([])

  const currentSession = useMemo(
    () => sessions.find((s) => s.id === sessionId) ?? sessions.find((s) => s.isCurrent === 1),
    [sessions, sessionId],
  )
  const activeSessionId = sessionId ?? currentSession?.id

  const fetchSessions = useCallback(async () => {
    const list = await listMemberSessions()
    setSessions(list)
    if (sessionId == null) {
      const cur = list.find((s) => s.isCurrent === 1) ?? list[0]
      setSessionId(cur?.id)
    }
  }, [sessionId])

  const fetchStats = useCallback(async () => {
    if (activeSessionId == null) return
    const s = await getMemberStats(activeSessionId)
    setStats({ total: s.total, byDept: s.byDept })
  }, [activeSessionId])

  const fetchList = useCallback(async () => {
    setLoading(true)
    try {
      const res = await listMembers({
        sessionId: activeSessionId, deptId: deptFilter, position: positionFilter,
        status: statusFilter, keyword: keyword || undefined, page, size,
      })
      setData((res.records ?? []).map((r) => ({ ...r, key: r.id })))
      setTotal(res.total)
    } catch { /* http 拦截已提示 */ } finally { setLoading(false) }
  }, [activeSessionId, deptFilter, positionFilter, statusFilter, keyword, page, size])

  useEffect(() => { fetchSessions() }, [fetchSessions])
  useEffect(() => { fetchList() }, [fetchList])
  useEffect(() => { fetchStats() }, [fetchStats])
  useEffect(() => { setDepts; listDepts().then(setDepts).catch(() => {}) }, [])

  const openCreate = () => { setEditing(null); setModalOpen(true) }
  const openEdit = (r: MemberVO) => { setEditing(r); setModalOpen(true) }
  const formInitialValues = useMemo(() => {
    if (!editing) return { sessionId: activeSessionId, status: 'ACTIVE' }
    return {
      sessionId: editing.sessionId, deptId: editing.deptId, position: editing.position, name: editing.name,
      gender: editing.gender, studentNo: editing.studentNo, className: editing.className,
      phone: editing.phone, politicalStatus: editing.politicalStatus, status: editing.status, remark: editing.remark,
    }
  }, [editing, activeSessionId])

  const handleSave = async () => {
    const v = await form.validateFields()
    setSaving(true)
    try {
      const payload: MemberSave = {
        sessionId: v.sessionId ?? activeSessionId!, position: v.position!, name: v.name.trim(),
        deptId: v.deptId ?? null, gender: v.gender ?? null, studentNo: v.studentNo?.trim() || null,
        className: v.className?.trim() || null, phone: v.phone?.trim() || null,
        politicalStatus: v.politicalStatus ?? null, status: v.status ?? 'ACTIVE', remark: v.remark ?? null,
      }
      if (editing) { await updateMember(editing.id, payload); message.success('成员已更新') }
      else { await createMember(payload); message.success('成员已新增') }
      setModalOpen(false); fetchList(); fetchStats()
    } catch { /* 已提示 */ } finally { setSaving(false) }
  }

  const handleDelete = async (id: number) => {
    await deleteMember(id); message.success('已删除'); fetchList(); fetchStats()
  }

  const handleBatchDelete = async () => {
    if (!selectedRowKeys.length) return
    modal.confirm({
      title: `确认删除选中的 ${selectedRowKeys.length} 名成员？`,
      onOk: async () => {
        await batchDeleteMembers(selectedRowKeys.map(Number))
        message.success('已删除'); setSelectedRowKeys([]); fetchList(); fetchStats()
      },
    })
  }

  const handleImport = async () => {
    if (!importFileList.length || activeSessionId == null) return
    setImporting(true)
    try {
      const fd = new FormData()
      fd.append('sessionId', String(activeSessionId))
      fd.append('file', importFileList[0].originFileObj as Blob, importFileList[0].name)
      const r = await importMembers(fd)
      setImportResult(r)
      setImportFileList([])
      fetchList(); fetchStats()
    } catch { /* 已提示 */ } finally { setImporting(false) }
  }

  const handleArchive = () => {
    if (activeSessionId == null) return
    modal.confirm({
      title: `确认换届归档「${currentSession?.name ?? ''}」？`,
      content: '该届全部「在职」成员将被批量置为「往届」。',
      okText: '归档',
      onOk: async () => {
        const r = await archiveSession(activeSessionId)
        message.success(`已归档 ${r.count} 人`)
        fetchList(); fetchStats()
      },
    })
  }

  const handleExport = async () => {
    const res = await downloadMemberExport({ sessionId: activeSessionId, deptId: deptFilter, position: positionFilter, status: statusFilter, keyword: keyword || undefined })
    const url = URL.createObjectURL(res.data)
    const a = document.createElement('a')
    a.href = url; a.download = `成员花名册_${currentSession?.name ?? ''}.xlsx`; a.click()
    URL.revokeObjectURL(url)
  }

  const handleSessionSave = async () => {
    const v = await sessionForm.validateFields()
    if (editingSession) { await updateMemberSession(editingSession.id, { ...v, sortOrder: editingSession.sortOrder }); message.success('届别已更新') }
    else { await createMemberSession({ ...v, isCurrent: sessions.length === 0 ? 1 : 0 }); message.success('届别已新增') }
    setSessionModalOpen(false); fetchSessions()
  }

  const columns: TableColumnsType<MemberRecord> = [
    { title: '姓名', dataIndex: 'name', key: 'name', width: 110 },
    { title: '部门', dataIndex: 'deptName', key: 'deptName', width: 120, render: (v: string | null) => v ?? '主任室' },
    { title: '职位', dataIndex: 'positionLabel', key: 'positionLabel', width: 100 },
    { title: '性别', dataIndex: 'gender', key: 'gender', width: 60, render: (v: string | null) => v || '-' },
    { title: '班级', dataIndex: 'className', key: 'className', width: 160, render: (v: string | null) => v || '-' },
    { title: '学号', dataIndex: 'studentNo', key: 'studentNo', width: 140, render: (v: string | null) => v || '-' },
    { title: '联系方式', dataIndex: 'phone', key: 'phone', width: 130, render: (v: string | null) => v || '-' },
    { title: '政治面貌', dataIndex: 'politicalStatus', key: 'politicalStatus', width: 100, render: (v: string | null) => v || '-' },
    {
      title: '状态', dataIndex: 'status', key: 'status', width: 90,
      render: (s: string) => <Tag color={STATUS_COLOR[s]}>{STATUS_LABELS[s]}</Tag>,
    },
    {
      title: '操作', key: 'action', width: 150,
      render: (_: unknown, r: MemberRecord) => (
        <Space size="small">
          <Button type="link" size="small" onClick={() => { window.location.href = `/members/${r.id}` }}>详情</Button>
          <Button type="link" size="small" icon={<EditOutlined />} onClick={() => openEdit(r)}>编辑</Button>
          <Popconfirm title="确认删除该成员？" onConfirm={() => handleDelete(r.id)} okText="删除" cancelText="取消">
            <Button type="link" size="small" danger icon={<DeleteOutlined />}>删除</Button>
          </Popconfirm>
        </Space>
      ),
    },
  ]

  return (
    <div>
      <PageHeader
        title="成员管理"
        description="党建办公室干部干事花名册（仅干部可见），支持 Excel 导入 / 手动添加 / 换届归档"
        extra={
          <Space wrap>
            <Button icon={<PlusOutlined />} onClick={openCreate}>新增成员</Button>
            <Button icon={<UploadOutlined />} loading={importing} onClick={() => document.getElementById('member-import')?.click()}>
              导入 Excel
            </Button>
            <Button icon={<DownloadOutlined />} onClick={() => downloadImportTemplate().then((res) => {
              const url = URL.createObjectURL(res.data); const a = document.createElement('a')
              a.href = url; a.download = '成员导入模板.xlsx'; a.click(); URL.revokeObjectURL(url)
            })}>下载模板</Button>
            <Button icon={<ExportOutlined />} onClick={handleExport}>导出</Button>
            <Button icon={<ReloadOutlined />} onClick={handleArchive}>换届归档</Button>
          </Space>
        }
      />

      <GlassCard style={{ padding: 12, marginBottom: 12 }}>
        <Space wrap style={{ width: '100%', justifyContent: 'space-between' }}>
          <Segmented
            options={sessions.map((s) => ({ label: s.isCurrent === 1 ? `${s.name}（当前）` : s.name, value: s.id }))}
            value={activeSessionId}
            onChange={(v) => { setSessionId(v as number); setPage(1) }}
          />
          <Button size="small" type="link" onClick={() => { setEditingSession(null); sessionForm.resetFields(); setSessionModalOpen(true) }}>
            届别管理
          </Button>
        </Space>
        {stats && (
          <Space wrap style={{ marginTop: 8 }}>
            <Tag color="blue">总人数 {stats.total}</Tag>
            {stats.byDept.map((d) => <Tag key={d.name}>{d.name} {d.count}</Tag>)}
          </Space>
        )}
      </GlassCard>

      <GlassCard style={{ padding: 16, marginBottom: 16 }}>
        <Space wrap>
          <Input.Search placeholder="姓名 / 学号 / 手机号" allowClear style={{ width: 220 }}
            onSearch={(v) => { setPage(1); setKeyword(v) }} />
          <Select placeholder="部门" allowClear options={depts.map((d) => ({ value: d.id, label: d.name }))}
            style={{ width: 140 }} value={deptFilter} onChange={(v) => { setPage(1); setDeptFilter(v) }} />
          <Select placeholder="职位" allowClear options={POSITION_OPTIONS} style={{ width: 120 }}
            value={positionFilter} onChange={(v) => { setPage(1); setPositionFilter(v) }} />
          <Select placeholder="状态" allowClear options={STATUS_OPTIONS} style={{ width: 120 }}
            value={statusFilter} onChange={(v) => { setPage(1); setStatusFilter(v) }} />
          {selectedRowKeys.length > 0 && (
            <Button danger onClick={handleBatchDelete}>批量删除（{selectedRowKeys.length}）</Button>
          )}
        </Space>
      </GlassCard>

      <GlassTable<MemberRecord>
        columns={columns} dataSource={data} rowKey="id" loading={loading}
        rowSelection={{ selectedRowKeys, onChange: setSelectedRowKeys }}
        pagination={{ current: page, pageSize: size, total, showSizeChanger: true,
          showTotal: (t) => `共 ${t} 条`, onChange: (p, s) => { setPage(p); setSize(s) } }}
      />

      <input id="member-import" type="file" accept=".xlsx,.xls" hidden
        onChange={(e) => {
          const f = e.target.files?.[0]
          if (f) { setImportFileList([{ uid: '-1', name: f.name, originFileObj: f } as UploadFile]) ; handleImport() }
          e.target.value = ''
        }} />

      {/* 新增 / 编辑成员 */}
      <GlassModal
        title={editing ? '编辑成员' : '新增成员'} open={modalOpen} onCancel={() => setModalOpen(false)}
        footer={<Space><Button onClick={() => setModalOpen(false)}>取消</Button>
          <Button type="primary" loading={saving} onClick={handleSave}>保存</Button></Space>}>
        <Form form={form} layout="vertical" preserve={false} initialValues={formInitialValues}>
          <Form.Item name="sessionId" label="届别" rules={[{ required: true, message: '请选择届别' }]}>
            <Select options={sessions.map((s) => ({ value: s.id, label: s.name }))} disabled={!!editing} />
          </Form.Item>
          <Form.Item name="name" label="姓名" rules={[{ required: true, message: '请输入姓名' }]}>
            <Input maxLength={50} />
          </Form.Item>
          <Space size="middle" wrap>
            <Form.Item name="position" label="职位" rules={[{ required: true, message: '请选择职位' }]}>
              <Select options={POSITION_OPTIONS} style={{ width: 160 }} />
            </Form.Item>
            <Form.Item name="deptId" label="部门">
              <Select allowClear placeholder="主任/副主任可留空（主任室）"
                options={depts.map((d) => ({ value: d.id, label: d.name }))} style={{ width: 180 }} />
            </Form.Item>
            <Form.Item name="gender" label="性别">
              <Select allowClear options={[{ value: '男', label: '男' }, { value: '女', label: '女' }]} style={{ width: 90 }} />
            </Form.Item>
          </Space>
          <Space size="middle" wrap>
            <Form.Item name="studentNo" label="学号">
              <Input maxLength={30} style={{ width: 200 }} />
            </Form.Item>
            <Form.Item name="className" label="班级">
              <Input maxLength={100} style={{ width: 200 }} />
            </Form.Item>
            <Form.Item name="phone" label="联系方式">
              <Input maxLength={20} style={{ width: 160 }} />
            </Form.Item>
          </Space>
          <Space size="middle" wrap>
            <Form.Item name="politicalStatus" label="政治面貌">
              <Select allowClear options={POLITICAL_OPTIONS} style={{ width: 160 }} />
            </Form.Item>
            <Form.Item name="status" label="状态">
              <Select options={STATUS_OPTIONS} style={{ width: 140 }} />
            </Form.Item>
          </Space>
          <Form.Item name="remark" label="备注">
            <Input.TextArea maxLength={255} rows={2} />
          </Form.Item>
        </Form>
      </GlassModal>

      {/* 届别管理 */}
      <GlassModal
        title="届别管理" open={sessionModalOpen} onCancel={() => setSessionModalOpen(false)}
        footer={<Space>
          <Button onClick={() => setSessionModalOpen(false)}>关闭</Button>
          <Button type="primary" onClick={handleSessionSave}>保存</Button>
        </Space>}>
        <Form form={sessionForm} layout="vertical" preserve={false}>
          <Form.Item name="name" label="届别名称" rules={[{ required: true, message: '请输入届名' }]}>
            <Input maxLength={50} placeholder="如：第十届" />
          </Form.Item>
          <Form.Item name="remark" label="备注"><Input maxLength={255} /></Form.Item>
        </Form>
        <Table
          size="small" rowKey="id" pagination={false}
          dataSource={sessions}
          columns={[
            { title: '届别', dataIndex: 'name' },
            { title: '当前', dataIndex: 'isCurrent', width: 70, render: (v: number) => (v === 1 ? <Tag color="green">当前</Tag> : '-') },
            { title: '操作', key: 'op', width: 180,
              render: (_: unknown, s: MemberSessionVO) => (
                <Space size="small">
                  {s.isCurrent !== 1 && (
                    <Button type="link" size="small" onClick={async () => { await setCurrentMemberSession(s.id); message.success('已设为当前届'); fetchSessions() }}>
                      设为当前
                    </Button>
                  )}
                  <Button type="link" size="small" onClick={() => { setEditingSession(s); sessionForm.setFieldsValue({ name: s.name, remark: s.remark ?? undefined }); setSessionModalOpen(true) }}>编辑</Button>
                  <Popconfirm title="删除该届别？" onConfirm={async () => { await deleteMemberSession(s.id); message.success('已删除'); fetchSessions() }} okText="删除" cancelText="取消">
                    <Button type="link" size="small" danger>删除</Button>
                  </Popconfirm>
                </Space>
              ) },
          ]}
        />
      </GlassModal>

      {/* 导入结果报告 */}
      <Modal
        title="导入结果" open={!!importResult} onCancel={() => setImportResult(null)} footer={null}
        width={560}>
        {importResult && (
          <div>
            <Space wrap style={{ marginBottom: 12 }}>
              <Tag color="blue">共 {importResult.total} 行</Tag>
              <Tag color="success">成功 {importResult.success}</Tag>
              {importResult.failed.length > 0 && <Tag color="error">失败 {importResult.failed.length}</Tag>}
            </Space>
            {importResult.failed.length > 0 ? (
              <Table
                size="small" rowKey="row" pagination={false} dataSource={importResult.failed}
                columns={[
                  { title: '行号', dataIndex: 'row', width: 80 },
                  { title: '姓名', dataIndex: 'name', width: 120 },
                  { title: '原因', dataIndex: 'reason' },
                ]} />
            ) : <div style={{ color: '#999' }}>全部导入成功。</div>}
          </div>
        )}
      </Modal>
    </div>
  )
}
