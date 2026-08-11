import { useCallback, useEffect, useState } from 'react'
import { App, Button, DatePicker, Form, Input, Popconfirm, Select, Space } from 'antd'
import { DeleteOutlined, EditOutlined, PlusOutlined } from '@ant-design/icons'
import dayjs, { type Dayjs } from 'dayjs'
import GlassModal from '@/components/glass/GlassModal'
import GlassTable from '@/components/glass/GlassTable'
import {
  listInvestigations,
  createInvestigation,
  updateInvestigation,
  deleteInvestigation,
  listRegisters,
  createRegister,
  updateRegister,
  deleteRegister,
  listTransfers,
  createTransfer,
  updateTransfer,
  deleteTransfer,
  GENDER_OPTIONS,
  type PartyInvestigationVO,
  type PartyRegisterVO,
  type PartyTransferVO,
} from '@/api/party'

/** 通用小工具：空值转 - */
const dash = (v: unknown) => (v == null || (typeof v === 'string' && !v.trim()) ? '-' : String(v))

// ==================== 函调 Tab ====================

interface InvFormValues {
  fatherName?: string
  fatherBranch?: string
  fatherBranchAddr?: string
  motherName?: string
  motherBranch?: string
  motherBranchAddr?: string
  relativeName?: string
  relativeBranch?: string
  relativeBranchAddr?: string
}

export function InvestigationPanel({ memberId }: { memberId: number }) {
  const { message } = App.useApp()
  const [list, setList] = useState<PartyInvestigationVO[]>([])
  const [loading, setLoading] = useState(false)
  const [modalOpen, setModalOpen] = useState(false)
  const [editing, setEditing] = useState<PartyInvestigationVO | null>(null)
  const [formInit, setFormInit] = useState<Partial<InvFormValues>>()
  const [saving, setSaving] = useState(false)
  const [form] = Form.useForm<InvFormValues>()

  const fetchList = useCallback(async () => {
    setLoading(true)
    try {
      setList(await listInvestigations(memberId))
    } catch {
      /* http 拦截已提示 */
    } finally {
      setLoading(false)
    }
  }, [memberId])

  useEffect(() => {
    fetchList()
  }, [fetchList])

  const openCreate = () => {
    setEditing(null)
    setFormInit(undefined)
    setModalOpen(true)
  }

  const openEdit = (r: PartyInvestigationVO) => {
    setEditing(r)
    // GlassModal destroyOnHidden 关闭即卸载，回填用 initialValues（挂载时生效）
    setFormInit({
      fatherName: r.fatherName ?? undefined,
      fatherBranch: r.fatherBranch ?? undefined,
      fatherBranchAddr: r.fatherBranchAddr ?? undefined,
      motherName: r.motherName ?? undefined,
      motherBranch: r.motherBranch ?? undefined,
      motherBranchAddr: r.motherBranchAddr ?? undefined,
      relativeName: r.relativeName ?? undefined,
      relativeBranch: r.relativeBranch ?? undefined,
      relativeBranchAddr: r.relativeBranchAddr ?? undefined,
    })
    setModalOpen(true)
  }

  const handleSave = async () => {
    const values = await form.validateFields()
    setSaving(true)
    try {
      const payload = {
        memberId,
        fatherName: values.fatherName || null,
        fatherBranch: values.fatherBranch || null,
        fatherBranchAddr: values.fatherBranchAddr || null,
        motherName: values.motherName || null,
        motherBranch: values.motherBranch || null,
        motherBranchAddr: values.motherBranchAddr || null,
        relativeName: values.relativeName || null,
        relativeBranch: values.relativeBranch || null,
        relativeBranchAddr: values.relativeBranchAddr || null,
      }
      if (editing) {
        await updateInvestigation(editing.id, payload)
        message.success('函调已更新')
      } else {
        await createInvestigation(payload)
        message.success('函调已新增')
      }
      setModalOpen(false)
      fetchList()
    } catch {
      /* 校验失败或 http 拦截已提示 */
    } finally {
      setSaving(false)
    }
  }

  const handleDelete = async (id: number) => {
    try {
      await deleteInvestigation(id)
      message.success('已删除')
      fetchList()
    } catch {
      /* http 拦截已提示 */
    }
  }

  return (
    <div>
      <Space style={{ marginBottom: 12 }}>
        <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
          新增函调
        </Button>
      </Space>
      <GlassTable<PartyInvestigationVO>
        columns={[
          { title: '关系人', dataIndex: 'person', key: 'person', render: (_: unknown, r) => `${dash(r.fatherName)} 父` },
          { title: '所在党支部', dataIndex: 'branch', key: 'branch', render: (_: unknown, r) => dash(r.fatherBranch) },
          { title: '党支部地址', dataIndex: 'addr', key: 'addr', render: (_: unknown, r) => dash(r.fatherBranchAddr) },
          {
            title: '操作',
            key: 'action',
            width: 140,
            render: (_: unknown, r: PartyInvestigationVO) => (
              <Space size="small">
                <Button type="link" size="small" icon={<EditOutlined />} onClick={() => openEdit(r)}>
                  编辑
                </Button>
                <Popconfirm title="确认删除该函调？" onConfirm={() => handleDelete(r.id)} okText="删除" cancelText="取消">
                  <Button type="link" size="small" danger icon={<DeleteOutlined />}>
                    删除
                  </Button>
                </Popconfirm>
              </Space>
            ),
          },
        ]}
        dataSource={list.map((r) => ({ ...r, key: r.id }))}
        rowKey="id"
        loading={loading}
        pagination={false}
      />

      <GlassModal
        title={editing ? '编辑函调' : '新增函调'}
        open={modalOpen}
        onCancel={() => setModalOpen(false)}
        footer={
          <Space>
            <Button onClick={() => setModalOpen(false)}>取消</Button>
            <Button type="primary" loading={saving} onClick={handleSave}>
              保存
            </Button>
          </Space>
        }
      >
        <Form
          form={form}
          layout="vertical"
          preserve={false}
          key={editing ? `inv-edit-${editing.id}` : 'inv-create'}
          initialValues={formInit}
        >
          <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--color-red)', marginBottom: 8 }}>父亲</div>
          <Space size="middle" wrap style={{ marginBottom: 8 }}>
            <Form.Item name="fatherName" label="姓名">
              <Input maxLength={50} style={{ width: 140 }} />
            </Form.Item>
            <Form.Item name="fatherBranch" label="所在党支部">
              <Input maxLength={100} style={{ width: 220 }} />
            </Form.Item>
          </Space>
          <Form.Item name="fatherBranchAddr" label="党支部地址">
            <Input maxLength={255} placeholder="函调寄送地址" />
          </Form.Item>
          <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--color-red)', marginBottom: 8 }}>母亲</div>
          <Space size="middle" wrap style={{ marginBottom: 8 }}>
            <Form.Item name="motherName" label="姓名">
              <Input maxLength={50} style={{ width: 140 }} />
            </Form.Item>
            <Form.Item name="motherBranch" label="所在党支部">
              <Input maxLength={100} style={{ width: 220 }} />
            </Form.Item>
          </Space>
          <Form.Item name="motherBranchAddr" label="党支部地址">
            <Input maxLength={255} placeholder="函调寄送地址" />
          </Form.Item>
          <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--color-red)', marginBottom: 8 }}>其他社会关系人</div>
          <Space size="middle" wrap style={{ marginBottom: 8 }}>
            <Form.Item name="relativeName" label="姓名">
              <Input maxLength={50} style={{ width: 140 }} />
            </Form.Item>
            <Form.Item name="relativeBranch" label="所在党支部">
              <Input maxLength={100} style={{ width: 220 }} />
            </Form.Item>
          </Space>
          <Form.Item name="relativeBranchAddr" label="党支部地址">
            <Input maxLength={255} placeholder="函调寄送地址" />
          </Form.Item>
        </Form>
      </GlassModal>
    </div>
  )
}

// ==================== 登记 Tab ====================

interface RegFormValues {
  name?: string
  gender?: string
  nation?: string
  birthDate?: Dayjs
  nativePlace?: string
  college?: string
  branch?: string
  className?: string
  education?: string
  idCard?: string
  phone?: string
  homeAddress?: string
  applyDate?: Dayjs
  talkPerson?: string
  conditionNote?: string
  remark?: string
}

export function RegisterPanel({ memberId }: { memberId: number }) {
  const { message } = App.useApp()
  const [list, setList] = useState<PartyRegisterVO[]>([])
  const [loading, setLoading] = useState(false)
  const [modalOpen, setModalOpen] = useState(false)
  const [editing, setEditing] = useState<PartyRegisterVO | null>(null)
  const [formInit, setFormInit] = useState<Partial<RegFormValues>>()
  const [saving, setSaving] = useState(false)
  const [form] = Form.useForm<RegFormValues>()

  const fetchList = useCallback(async () => {
    setLoading(true)
    try {
      setList(await listRegisters(memberId))
    } catch {
      /* http 拦截已提示 */
    } finally {
      setLoading(false)
    }
  }, [memberId])

  useEffect(() => {
    fetchList()
  }, [fetchList])

  const openCreate = () => {
    setEditing(null)
    setFormInit(undefined)
    setModalOpen(true)
  }

  const openEdit = (r: PartyRegisterVO) => {
    setEditing(r)
    // GlassModal destroyOnHidden 关闭即卸载，回填用 initialValues（挂载时生效）
    setFormInit({
      name: r.name ?? undefined,
      gender: r.gender ?? undefined,
      nation: r.nation ?? undefined,
      birthDate: r.birthDate ? dayjs(r.birthDate) : undefined,
      nativePlace: r.nativePlace ?? undefined,
      college: r.college ?? undefined,
      branch: r.branch ?? undefined,
      className: r.className ?? undefined,
      education: r.education ?? undefined,
      idCard: r.idCard ?? undefined,
      phone: r.phone ?? undefined,
      homeAddress: r.homeAddress ?? undefined,
      applyDate: r.applyDate ? dayjs(r.applyDate) : undefined,
      talkPerson: r.talkPerson ?? undefined,
      conditionNote: r.conditionNote ?? undefined,
      remark: r.remark ?? undefined,
    })
    setModalOpen(true)
  }

  const handleSave = async () => {
    const values = await form.validateFields()
    setSaving(true)
    try {
      const payload = {
        memberId,
        name: values.name || null,
        gender: values.gender || null,
        nation: values.nation || null,
        birthDate: values.birthDate ? values.birthDate.format('YYYY-MM-DD') : null,
        nativePlace: values.nativePlace || null,
        college: values.college || null,
        branch: values.branch || null,
        className: values.className || null,
        education: values.education || null,
        idCard: values.idCard || null,
        phone: values.phone || null,
        homeAddress: values.homeAddress || null,
        applyDate: values.applyDate ? values.applyDate.format('YYYY-MM-DD') : null,
        talkPerson: values.talkPerson || null,
        conditionNote: values.conditionNote || null,
        remark: values.remark || null,
      }
      if (editing) {
        await updateRegister(editing.id, payload)
        message.success('登记已更新')
      } else {
        await createRegister(payload)
        message.success('登记已新增')
      }
      setModalOpen(false)
      fetchList()
    } catch {
      /* 校验失败或 http 拦截已提示 */
    } finally {
      setSaving(false)
    }
  }

  const handleDelete = async (id: number) => {
    try {
      await deleteRegister(id)
      message.success('已删除')
      fetchList()
    } catch {
      /* http 拦截已提示 */
    }
  }

  return (
    <div>
      <Space style={{ marginBottom: 12 }}>
        <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
          新增登记
        </Button>
      </Space>
      <GlassTable<PartyRegisterVO>
        columns={[
          { title: '姓名', dataIndex: 'name', key: 'name', render: (v: string) => dash(v) },
          { title: '班级', dataIndex: 'className', key: 'className', render: (v: string) => dash(v) },
          { title: '申请书时间', dataIndex: 'applyDate', key: 'applyDate', render: (v: string) => dash(v) },
          { title: '谈话人', dataIndex: 'talkPerson', key: 'talkPerson', render: (v: string) => dash(v) },
          {
            title: '操作',
            key: 'action',
            width: 140,
            render: (_: unknown, r: PartyRegisterVO) => (
              <Space size="small">
                <Button type="link" size="small" icon={<EditOutlined />} onClick={() => openEdit(r)}>
                  编辑
                </Button>
                <Popconfirm title="确认删除该登记？" onConfirm={() => handleDelete(r.id)} okText="删除" cancelText="取消">
                  <Button type="link" size="small" danger icon={<DeleteOutlined />}>
                    删除
                  </Button>
                </Popconfirm>
              </Space>
            ),
          },
        ]}
        dataSource={list.map((r) => ({ ...r, key: r.id }))}
        rowKey="id"
        loading={loading}
        pagination={false}
      />

      <GlassModal
        title={editing ? '编辑登记' : '新增登记'}
        open={modalOpen}
        onCancel={() => setModalOpen(false)}
        width={720}
        footer={
          <Space>
            <Button onClick={() => setModalOpen(false)}>取消</Button>
            <Button type="primary" loading={saving} onClick={handleSave}>
              保存
            </Button>
          </Space>
        }
      >
        <Form
          form={form}
          layout="vertical"
          preserve={false}
          key={editing ? `reg-edit-${editing.id}` : 'reg-create'}
          initialValues={formInit}
        >
          <Space size="middle" wrap>
            <Form.Item name="name" label="姓名">
              <Input maxLength={50} style={{ width: 140 }} />
            </Form.Item>
            <Form.Item name="gender" label="性别">
              <Select options={GENDER_OPTIONS} style={{ width: 100 }} />
            </Form.Item>
            <Form.Item name="nation" label="民族">
              <Input maxLength={30} style={{ width: 120 }} />
            </Form.Item>
            <Form.Item name="birthDate" label="出生日期">
              <DatePicker placeholder="选择日期" />
            </Form.Item>
          </Space>
          <Space size="middle" wrap>
            <Form.Item name="nativePlace" label="籍贯">
              <Input maxLength={100} style={{ width: 160 }} />
            </Form.Item>
            <Form.Item name="college" label="学院">
              <Input maxLength={100} style={{ width: 200 }} placeholder="信息与智能工程学院" />
            </Form.Item>
            <Form.Item name="education" label="学历">
              <Input maxLength={50} style={{ width: 120 }} placeholder="本科在读" />
            </Form.Item>
          </Space>
          <Space size="middle" wrap>
            <Form.Item name="branch" label="所在党支部">
              <Input maxLength={100} style={{ width: 240 }} />
            </Form.Item>
            <Form.Item name="className" label="班级">
              <Input maxLength={100} style={{ width: 160 }} />
            </Form.Item>
          </Space>
          <Space size="middle" wrap>
            <Form.Item name="idCard" label="身份证号">
              <Input maxLength={18} style={{ width: 200 }} />
            </Form.Item>
            <Form.Item name="phone" label="电话">
              <Input maxLength={20} style={{ width: 160 }} />
            </Form.Item>
            <Form.Item name="applyDate" label="申请书时间">
              <DatePicker placeholder="选择日期" />
            </Form.Item>
          </Space>
          <Form.Item name="homeAddress" label="家庭地址">
            <Input maxLength={255} />
          </Form.Item>
          <Form.Item name="talkPerson" label="谈话人">
            <Input maxLength={50} placeholder="负责谈话的党组织联系人" />
          </Form.Item>
          <Form.Item name="conditionNote" label="条件情况">
            <Input.TextArea rows={3} placeholder="对申请人主要条件情况的说明" />
          </Form.Item>
          <Form.Item name="remark" label="备注">
            <Input.TextArea rows={2} />
          </Form.Item>
        </Form>
      </GlassModal>
    </div>
  )
}

// ==================== 转移 Tab ====================

interface TraFormValues {
  name?: string
  gender?: string
  nation?: string
  className?: string
  isProbationary?: number
  idCard?: string
  receiveOrg?: string
  phone?: string
  wechat?: string
  isOnline?: number
  signDate?: Dayjs
  remark?: string
}

export function TransferPanel({ memberId }: { memberId: number }) {
  const { message } = App.useApp()
  const [list, setList] = useState<PartyTransferVO[]>([])
  const [loading, setLoading] = useState(false)
  const [modalOpen, setModalOpen] = useState(false)
  const [editing, setEditing] = useState<PartyTransferVO | null>(null)
  const [formInit, setFormInit] = useState<Partial<TraFormValues>>()
  const [saving, setSaving] = useState(false)
  const [form] = Form.useForm<TraFormValues>()

  const fetchList = useCallback(async () => {
    setLoading(true)
    try {
      setList(await listTransfers(memberId))
    } catch {
      /* http 拦截已提示 */
    } finally {
      setLoading(false)
    }
  }, [memberId])

  useEffect(() => {
    fetchList()
  }, [fetchList])

  const openCreate = () => {
    setEditing(null)
    setFormInit({ isOnline: 1 })
    setModalOpen(true)
  }

  const openEdit = (r: PartyTransferVO) => {
    setEditing(r)
    // GlassModal destroyOnHidden 关闭即卸载，回填用 initialValues（挂载时生效）
    setFormInit({
      name: r.name ?? undefined,
      gender: r.gender ?? undefined,
      nation: r.nation ?? undefined,
      className: r.className ?? undefined,
      isProbationary: r.isProbationary ?? undefined,
      idCard: r.idCard ?? undefined,
      receiveOrg: r.receiveOrg ?? undefined,
      phone: r.phone ?? undefined,
      wechat: r.wechat ?? undefined,
      isOnline: r.isOnline ?? 1,
      signDate: r.signDate ? dayjs(r.signDate) : undefined,
      remark: r.remark ?? undefined,
    })
    setModalOpen(true)
  }

  const handleSave = async () => {
    const values = await form.validateFields()
    setSaving(true)
    try {
      const payload = {
        memberId,
        name: values.name || null,
        gender: values.gender || null,
        nation: values.nation || null,
        className: values.className || null,
        isProbationary: values.isProbationary ?? null,
        idCard: values.idCard || null,
        receiveOrg: values.receiveOrg || null,
        phone: values.phone || null,
        wechat: values.wechat || null,
        isOnline: values.isOnline ?? null,
        signDate: values.signDate ? values.signDate.format('YYYY-MM-DD') : null,
        remark: values.remark || null,
      }
      if (editing) {
        await updateTransfer(editing.id, payload)
        message.success('转移已更新')
      } else {
        await createTransfer(payload)
        message.success('转移已新增')
      }
      setModalOpen(false)
      fetchList()
    } catch {
      /* 校验失败或 http 拦截已提示 */
    } finally {
      setSaving(false)
    }
  }

  const handleDelete = async (id: number) => {
    try {
      await deleteTransfer(id)
      message.success('已删除')
      fetchList()
    } catch {
      /* http 拦截已提示 */
    }
  }

  return (
    <div>
      <Space style={{ marginBottom: 12 }}>
        <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
          新增转移
        </Button>
      </Space>
      <GlassTable<PartyTransferVO>
        columns={[
          { title: '姓名', dataIndex: 'name', key: 'name', render: (v: string) => dash(v) },
          { title: '接收组织', dataIndex: 'receiveOrg', key: 'receiveOrg', render: (v: string) => dash(v) },
          { title: '身份', dataIndex: 'isProbationary', key: 'isProbationary', render: (v: number | null) => (v == null ? '-' : v === 1 ? '预备党员' : '正式党员') },
          { title: '方式', dataIndex: 'isOnline', key: 'isOnline', render: (v: number | null) => (v == null ? '-' : v === 1 ? '线上' : '线下') },
          { title: '签署日期', dataIndex: 'signDate', key: 'signDate', render: (v: string) => dash(v) },
          {
            title: '操作',
            key: 'action',
            width: 140,
            render: (_: unknown, r: PartyTransferVO) => (
              <Space size="small">
                <Button type="link" size="small" icon={<EditOutlined />} onClick={() => openEdit(r)}>
                  编辑
                </Button>
                <Popconfirm title="确认删除该转移？" onConfirm={() => handleDelete(r.id)} okText="删除" cancelText="取消">
                  <Button type="link" size="small" danger icon={<DeleteOutlined />}>
                    删除
                  </Button>
                </Popconfirm>
              </Space>
            ),
          },
        ]}
        dataSource={list.map((r) => ({ ...r, key: r.id }))}
        rowKey="id"
        loading={loading}
        pagination={false}
      />

      <GlassModal
        title={editing ? '编辑转移' : '新增转移'}
        open={modalOpen}
        onCancel={() => setModalOpen(false)}
        footer={
          <Space>
            <Button onClick={() => setModalOpen(false)}>取消</Button>
            <Button type="primary" loading={saving} onClick={handleSave}>
              保存
            </Button>
          </Space>
        }
      >
        <Form
          form={form}
          layout="vertical"
          preserve={false}
          key={editing ? `tra-edit-${editing.id}` : 'tra-create'}
          initialValues={formInit}
        >
          <Space size="middle" wrap>
            <Form.Item name="name" label="姓名">
              <Input maxLength={50} style={{ width: 140 }} />
            </Form.Item>
            <Form.Item name="gender" label="性别">
              <Select options={GENDER_OPTIONS} style={{ width: 100 }} />
            </Form.Item>
            <Form.Item name="nation" label="民族">
              <Input maxLength={30} style={{ width: 120 }} />
            </Form.Item>
            <Form.Item name="className" label="班级">
              <Input maxLength={100} style={{ width: 160 }} />
            </Form.Item>
          </Space>
          <Space size="middle" wrap>
            <Form.Item name="isProbationary" label="转移身份">
              <Select
                style={{ width: 140 }}
                options={[
                  { value: 1, label: '预备党员' },
                  { value: 0, label: '正式党员' },
                ]}
              />
            </Form.Item>
            <Form.Item name="isOnline" label="发起方式">
              <Select
                style={{ width: 140 }}
                options={[
                  { value: 1, label: '线上' },
                  { value: 0, label: '线下' },
                ]}
              />
            </Form.Item>
            <Form.Item name="signDate" label="签署日期">
              <DatePicker placeholder="选择日期" />
            </Form.Item>
          </Space>
          <Form.Item name="receiveOrg" label="接收组织">
            <Input maxLength={200} placeholder="接收组织关系的党组织名称" />
          </Form.Item>
          <Space size="middle" wrap>
            <Form.Item name="idCard" label="身份证号">
              <Input maxLength={18} style={{ width: 200 }} />
            </Form.Item>
            <Form.Item name="phone" label="电话">
              <Input maxLength={20} style={{ width: 160 }} />
            </Form.Item>
            <Form.Item name="wechat" label="微信">
              <Input maxLength={50} style={{ width: 160 }} />
            </Form.Item>
          </Space>
          <Form.Item name="remark" label="备注">
            <Input.TextArea rows={2} />
          </Form.Item>
        </Form>
      </GlassModal>
    </div>
  )
}

// ==================== 导出 ====================

export interface RecordPanelProps {
  memberId: number
}

export function RecordPanels({ memberId }: RecordPanelProps) {
  return (
    <div>
      <InvestigationPanel memberId={memberId} />
      <RegisterPanel memberId={memberId} />
      <TransferPanel memberId={memberId} />
    </div>
  )
}
