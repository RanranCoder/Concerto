import FormSurface, { FormSection } from '@/components/form/FormSurface'
import { useCallback, useEffect, useState } from 'react'
import { RecordsNav, RecordsName } from '@/components/records/RecordsLayout'
import {
  App,
  Button,
  Form,
  Input,
  Popconfirm,
  Select,
  Space,
  DatePicker,
  InputNumber,
} from 'antd'
import {
  DeleteOutlined,
  EditOutlined,
  EyeOutlined,
  PlusOutlined,
  RetweetOutlined,
} from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import dayjs, { type Dayjs } from 'dayjs'
import GlassCard from '@/components/glass/GlassCard'
import GlassTable from '@/components/glass/GlassTable'
import PageHeader from '@/components/glass/PageHeader'
import StatusTag from '@/components/glass/StatusTag'
import { useAuthStore } from '@/stores/auth'
import {
  listPartyMembers,
  createPartyMember,
  updatePartyMember,
  deletePartyMember,
  changeStage,
  PARTY_STAGES,
  GENDER_OPTIONS,
  POLITICAL_OPTIONS,
  type PartyMemberVO,
} from '@/api/party'

type PartyMemberRecord = PartyMemberVO & { key: number }

interface MemberFormValues {
  name: string
  gender?: string
  nation?: string
  className?: string
  branchName?: string
  politicalStatus?: string
  studentNo?: string
  idCard?: string
  birthDate?: Dayjs
  nativePlace?: string
  education?: string
  phone?: string
  homeAddress?: string
  remark?: string
}

interface StageFormValues {
  stage: string
  issueNo?: number
  startDate?: Dayjs
  endDate?: Dayjs
  remark?: string
}

const STATUS_OPTIONS = PARTY_STAGES.map((s) => ({
  value: s.label,
  label: s.label,
}))

export default function PartyMemberList() {
  const { message } = App.useApp()
  const navigate = useNavigate()
  const user = useAuthStore((s) => s.user)
  const isMinisterOrAbove = (user?.permissions ?? []).includes('party:manage')

  const [data, setData] = useState<PartyMemberRecord[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(false)
  const [page, setPage] = useState(1)
  const [size, setSize] = useState(10)
  const [keyword, setKeyword] = useState('')
  const [stage, setStage] = useState<string | undefined>()
  const [stageCounts, setStageCounts] = useState<Record<string, number> | null>(
    null,
  )

  const [modalOpen, setModalOpen] = useState(false)
  const [editing, setEditing] = useState<PartyMemberVO | null>(null)
  const [formInit, setFormInit] = useState<Partial<MemberFormValues>>()
  const [saving, setSaving] = useState(false)
  const [form] = Form.useForm<MemberFormValues>()

  const [stageOpen, setStageOpen] = useState(false)
  const [stageTarget, setStageTarget] = useState<PartyMemberVO | null>(null)
  const [stageSaving, setStageSaving] = useState(false)
  const [stageForm] = Form.useForm<StageFormValues>()

  const fetchList = useCallback(async () => {
    setLoading(true)
    try {
      const res = await listPartyMembers({
        keyword: keyword || undefined,
        stage,
        page,
        size,
      })
      setData((res.records ?? []).map((r) => ({ ...r, key: r.id })))
      setTotal(res.total)
    } catch {
      /* http 拦截已提示 */
    } finally {
      setLoading(false)
    }
  }, [keyword, stage, page, size])

  useEffect(() => {
    let live = true
    Promise.all(
      PARTY_STAGES.map((s) =>
        listPartyMembers({ stage: s.label, page: 1, size: 1 }).then(
          (r) => [s.value, r.total] as const,
        ),
      ),
    )
      .then((entries) => {
        if (live) setStageCounts(Object.fromEntries(entries))
      })
      .catch(() => {})
    return () => {
      live = false
    }
  }, [data])

  useEffect(() => {
    fetchList()
  }, [fetchList])

  const openCreate = () => {
    setEditing(null)
    // GlassModal destroyOnHidden 关闭即卸载，回填用 initialValues（挂载时生效）
    setFormInit({ gender: '男' })
    setModalOpen(true)
  }

  const openEdit = (record: PartyMemberVO) => {
    setEditing(record)
    setFormInit({
      name: record.name,
      gender: record.gender || undefined,
      nation: record.nation || undefined,
      className: record.className || undefined,
      branchName: record.branchName || undefined,
      politicalStatus: record.politicalStatus || undefined,
      studentNo: record.studentNo || undefined,
      idCard: record.idCard || undefined,
      birthDate: record.birthDate ? dayjs(record.birthDate) : undefined,
      nativePlace: record.nativePlace || undefined,
      education: record.education || undefined,
      phone: record.phone || undefined,
      homeAddress: record.homeAddress || undefined,
      remark: record.remark || undefined,
    })
    setModalOpen(true)
  }

  const handleSave = async () => {
    const values = await form.validateFields()
    setSaving(true)
    try {
      const payload = {
        name: values.name,
        gender: values.gender || null,
        nation: values.nation || null,
        className: values.className || null,
        branchName: values.branchName || null,
        politicalStatus: values.politicalStatus || null,
        studentNo: values.studentNo || null,
        idCard: isMinisterOrAbove ? values.idCard || null : undefined,
        birthDate: values.birthDate
          ? values.birthDate.format('YYYY-MM-DD')
          : null,
        nativePlace: values.nativePlace || null,
        education: values.education || null,
        phone: isMinisterOrAbove ? values.phone || null : undefined,
        homeAddress: isMinisterOrAbove ? values.homeAddress || null : undefined,
        remark: values.remark || null,
      }
      if (editing) {
        await updatePartyMember(editing.id, payload)
        message.success('成员已更新')
      } else {
        await createPartyMember(payload)
        message.success('成员已创建')
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
      await deletePartyMember(id)
      message.success('已删除')
      fetchList()
    } catch {
      /* http 拦截已提示 */
    }
  }

  const openStage = (record: PartyMemberVO) => {
    setStageTarget(record)
    stageForm.resetFields()
    setStageOpen(true)
  }

  const handleStage = async () => {
    if (!stageTarget) return
    const values = await stageForm.validateFields()
    setStageSaving(true)
    try {
      await changeStage(stageTarget.id, {
        stage: values.stage,
        issueNo: values.issueNo != null ? String(values.issueNo) : undefined,
        startDate: values.startDate
          ? values.startDate.format('YYYY-MM-DD')
          : undefined,
        endDate: values.endDate
          ? values.endDate.format('YYYY-MM-DD')
          : undefined,
        remark: values.remark || undefined,
      })
      message.success(
        `已流转为「${PARTY_STAGES.find((s) => s.value === values.stage)?.label ?? values.stage}」`,
      )
      setStageOpen(false)
      fetchList()
    } catch {
      /* 校验失败或 http 拦截已提示 */
    } finally {
      setStageSaving(false)
    }
  }

  const columns = [
    {
      title: '人员',
      key: 'name',
      render: (_: unknown, r: PartyMemberRecord) => (
        <RecordsName name={r.name} meta={r.studentNo || '未填写学号'} avatar />
      ),
    },
    {
      title: '班级',
      dataIndex: 'className',
      key: 'className',
      render: (v: string) => v || '-',
    },
    {
      title: '所在党支部',
      dataIndex: 'branchName',
      key: 'branch',
      render: (v: string) => v || '未填写',
    },
    {
      title: '身份',
      dataIndex: 'politicalStatus',
      key: 'politicalStatus',
      render: (v: string) => (v ? <StatusTag status={v} /> : '-'),
    },
    {
      title: '期数',
      dataIndex: 'issueNo',
      key: 'issueNo',
      render: () => '-',
    },
    // 电话为敏感字段：仅部长及以上（roleLevel >= 3）显示
    ...(isMinisterOrAbove
      ? [
          {
            title: '电话',
            dataIndex: 'phone',
            key: 'phone',
            render: (v: string) => v || '-',
          },
        ]
      : []),
    {
      title: '操作',
      key: 'action',
      align: 'right' as const,
      width: 260,
      render: (_: unknown, r: PartyMemberRecord) => (
        <Space size="small">
          <Button
            type="link"
            size="small"
            icon={<EyeOutlined />}
            onClick={() => navigate(`/party/members/${r.id}`)}
          >
            详情
          </Button>
          {isMinisterOrAbove && (
            <Button
              type="link"
              size="small"
              icon={<RetweetOutlined />}
              onClick={() => openStage(r)}
            >
              流转
            </Button>
          )}
          {isMinisterOrAbove && (
            <Button
              type="link"
              size="small"
              icon={<EditOutlined />}
              onClick={() => openEdit(r)}
            >
              编辑
            </Button>
          )}
          {isMinisterOrAbove && (
            <Popconfirm
              title="确认删除该成员？"
              onConfirm={() => handleDelete(r.id)}
              okText="删除"
              cancelText="取消"
            >
              <Button type="link" size="small" danger icon={<DeleteOutlined />}>
                删除
              </Button>
            </Popconfirm>
          )}
        </Space>
      ),
    },
  ]

  if (modalOpen)
    return (
      <FormSurface
        mode="workspace"
        description="填写身份资料、组织信息与联系方式。"
        title={editing ? '编辑成员' : '新增成员'}
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
          key={editing ? `edit-${editing.id}` : 'create'}
          initialValues={formInit}
        >
          <FormSection title="基本资料">
            <Form.Item
              name="name"
              label="姓名"
              rules={[{ required: true, message: '请输入姓名' }]}
            >
              <Input maxLength={50} placeholder="请输入姓名" />
            </Form.Item>
            <Form.Item name="gender" label="性别">
              <Select
                options={GENDER_OPTIONS}
                style={{ width: 100 }}
                placeholder="请选择"
              />
            </Form.Item>
            <Form.Item name="nation" label="民族">
              <Input
                maxLength={30}
                style={{ width: 140 }}
                placeholder="如 汉族"
              />
            </Form.Item>
            <Form.Item name="className" label="班级">
              <Input maxLength={100} placeholder="如 计科2301" />
            </Form.Item>
            <Form.Item name="studentNo" label="学号">
              <Input maxLength={20} placeholder="请输入学号" />
            </Form.Item>
            <Form.Item name="birthDate" label="出生日期">
              <DatePicker placeholder="选择日期" />
            </Form.Item>
            <Form.Item name="education" label="学历">
              <Input
                maxLength={50}
                style={{ width: 120 }}
                placeholder="如 本科在读"
              />
            </Form.Item>
            <Form.Item name="nativePlace" label="籍贯">
              <Input maxLength={100} placeholder="如 广东广州" />
            </Form.Item>
          </FormSection>
          <FormSection title="组织信息">
            <Form.Item
              className="form-field-wide"
              name="branchName"
              label="所在党支部"
            >
              <Input
                maxLength={100}
                placeholder="如 信息与智能工程学院学生第一党支部"
              />
            </Form.Item>
            <Form.Item name="politicalStatus" label="政治面貌">
              <Select options={POLITICAL_OPTIONS} placeholder="请选择" />
            </Form.Item>
          </FormSection>
          <FormSection title="联系资料与备注">
            {isMinisterOrAbove && (
              <>
                <Form.Item name="idCard" label="身份证号">
                  <Input
                    maxLength={18}
                    placeholder="敏感字段，仅部长及以上可见"
                  />
                </Form.Item>
                <Form.Item name="phone" label="联系电话">
                  <Input
                    maxLength={20}
                    placeholder="敏感字段，仅部长及以上可见"
                  />
                </Form.Item>
                <Form.Item name="homeAddress" label="家庭地址">
                  <Input
                    maxLength={255}
                    placeholder="敏感字段，仅部长及以上可见"
                  />
                </Form.Item>
              </>
            )}
            <Form.Item name="remark" label="备注">
              <Input.TextArea rows={3} maxLength={1000} />
            </Form.Item>
          </FormSection>
        </Form>
      </FormSurface>
    )

  return (
    <div className="records-page">
      <PageHeader
        title="党务台账"
        description="围绕个人发展历程维护台账，集中查阅名册与专项记录。"
        extra={
          isMinisterOrAbove && (
            <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
              新增成员
            </Button>
          )
        }
      />

      <RecordsNav section="party" />
      <div className="records-stage-filters" aria-label="按发展阶段筛选">
        {PARTY_STAGES.map((s) => (
          <button
            key={s.value}
            type="button"
            aria-pressed={stage === s.label}
            onClick={() => {
              setStage(stage === s.label ? undefined : s.label)
              setPage(1)
            }}
          >
            {s.label}
            <b>{stageCounts?.[s.value] ?? '—'}</b>
          </button>
        ))}
      </div>
      <GlassCard className="records-toolbar">
        <Space wrap>
          <Input.Search
            placeholder="姓名 / 学号 / 班级"
            allowClear
            style={{ width: 240 }}
            onSearch={(v) => {
              setPage(1)
              setKeyword(v)
            }}
          />
          <Select
            placeholder="身份筛选"
            allowClear
            options={STATUS_OPTIONS}
            style={{ width: 180 }}
            value={stage}
            onChange={(v) => {
              setPage(1)
              setStage(v ?? undefined)
            }}
          />
        </Space>
      </GlassCard>

      <GlassTable<PartyMemberRecord>
        scroll={{ x: 1000 }}
        columns={columns}
        dataSource={data}
        rowKey="id"
        loading={loading}
        pagination={{
          current: page,
          pageSize: size,
          total,
          onChange: (p, s) => {
            setPage(p)
            setSize(s)
          },
          showSizeChanger: true,
          showTotal: (t) => `共 ${t} 条`,
        }}
      />

      {/* 新增 / 编辑成员 */}

      {/* 阶段流转 */}
      <FormSurface
        mode="dialog"
        title={`阶段流转 — ${stageTarget?.name ?? ''}`}
        open={stageOpen}
        onCancel={() => setStageOpen(false)}
        footer={
          <Space>
            <Button onClick={() => setStageOpen(false)}>取消</Button>
            <Button type="primary" loading={stageSaving} onClick={handleStage}>
              确认流转
            </Button>
          </Space>
        }
      >
        <Form form={stageForm} layout="vertical" preserve={false}>
          <Form.Item
            name="stage"
            label="流转至"
            rules={[{ required: true, message: '请选择阶段' }]}
          >
            <Select
              options={
                PARTY_STAGES as unknown as { value: string; label: string }[]
              }
              placeholder="请选择阶段"
            />
          </Form.Item>
          <Space size="middle" wrap>
            <Form.Item name="issueNo" label="期数">
              <InputNumber
                min={1}
                max={99}
                style={{ width: 100 }}
                placeholder="如 40"
              />
            </Form.Item>
            <Form.Item name="startDate" label="开始日期">
              <DatePicker placeholder="选择日期" />
            </Form.Item>
            <Form.Item name="endDate" label="结束日期">
              <DatePicker placeholder="选择日期" />
            </Form.Item>
          </Space>
          <Form.Item name="remark" label="备注">
            <Input.TextArea rows={3} maxLength={200} />
          </Form.Item>
        </Form>
      </FormSurface>
    </div>
  )
}
