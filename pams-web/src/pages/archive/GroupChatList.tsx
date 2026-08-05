import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  Button,
  Card,
  Empty,
  Form,
  Input,
  message,
  Modal,
  Popconfirm,
  Radio,
  Select,
  Space,
  Tag,
  Tooltip,
} from 'antd'
import {
  AppstoreOutlined,
  DeleteOutlined,
  EditOutlined,
  InboxOutlined,
  PlusOutlined,
  SearchOutlined,
  UnorderedListOutlined,
  QrcodeOutlined,
} from '@ant-design/icons'
import PageHeader from '@/components/glass/PageHeader'
import GlassCard from '@/components/glass/GlassCard'
import GlassModal from '@/components/glass/GlassModal'
import {
  archiveGroupChat,
  createChatCategory,
  createGroupChat,
  deleteChatCategory,
  deleteGroupChat,
  listChatCategories,
  listGroupChats,
  renameChatCategory,
  updateGroupChat,
  type GroupChatCategoryVO,
  type GroupChatSave,
  type GroupChatVO,
} from '@/api/chat'
import { listActivities, type ActivityVO } from '@/api/activity'
import { listUsers, type UserVO } from '@/api/user'
import { listDepts, type DeptVO } from '@/api/dept'
import { uploadFile } from '@/api/file'

const STATUS_MAP: Record<string, { label: string; color: string }> = {
  ACTIVE: { label: '活跃', color: 'green' },
  DISSOLVED: { label: '已解散', color: 'red' },
  ARCHIVED: { label: '已归档', color: 'default' },
}

interface ChatFormValues {
  name: string
  categoryId?: number
  activityId?: number
  departments?: string[]
  ownerId?: number
  qrCodeUrl?: string
  remark?: string
  status?: 'ACTIVE' | 'DISSOLVED' | 'ARCHIVED'
}

export default function GroupChatList() {
  const [chats, setChats] = useState<GroupChatVO[]>([])
  const [categories, setCategories] = useState<GroupChatCategoryVO[]>([])
  const [keyword, setKeyword] = useState('')
  const [view, setView] = useState<'card' | 'list'>('card')
  const [filterCategory, setFilterCategory] = useState<number>()
  const [filterStatus, setFilterStatus] = useState<string>()
  const [filterDept, setFilterDept] = useState<string>()
  const [activities, setActivities] = useState<ActivityVO[]>([])
  const [users, setUsers] = useState<UserVO[]>([])
  const [depts, setDepts] = useState<DeptVO[]>([])
  const [modalOpen, setModalOpen] = useState(false)
  const [editing, setEditing] = useState<GroupChatVO | null>(null)
  const [saving, setSaving] = useState(false)
  const [qrUrl, setQrUrl] = useState<string | null>(null)
  const [form] = Form.useForm<ChatFormValues>()

  // 分类管理
  const [categoryOpen, setCategoryOpen] = useState(false)
  const [newCategory, setNewCategory] = useState('')

  const fetchAll = useCallback(async () => {
    try {
      const [cs, cats] = await Promise.all([
        listGroupChats({ keyword: keyword || undefined, categoryId: filterCategory, status: filterStatus, department: filterDept }),
        listChatCategories(),
      ])
      setChats(cs ?? [])
      setCategories(cats ?? [])
    } catch {
      /* http 拦截已提示 */
    }
  }, [keyword, filterCategory, filterStatus, filterDept])

  useEffect(() => {
    fetchAll()
  }, [fetchAll])

  useEffect(() => {
    listActivities({ size: 100 })
      .then((res) => setActivities(res.records ?? []))
      .catch(() => {
        /* 无权限留空 */
      })
    listUsers({ size: 1000 })
      .then((res) => setUsers(res.records ?? []))
      .catch(() => {
        /* 无权限留空 */
      })
    listDepts()
      .then(setDepts)
      .catch(() => {
        /* 无权限留空 */
      })
  }, [])

  const categoryNameOf = (id: number | null) =>
    id == null ? '未分类' : categories.find((c) => c.id === id)?.name ?? `#${id}`
  const ownerNameOf = (id: number | null) =>
    id == null ? '-' : users.find((u) => u.id === id)?.realName ?? `#${id}`

  const openCreate = () => {
    setEditing(null)
    setQrUrl(null)
    form.resetFields()
    setModalOpen(true)
  }

  const openEdit = (c: GroupChatVO) => {
    setEditing(c)
    setQrUrl(c.qrCodeUrl || null)
    form.resetFields()
    setModalOpen(true)
    // GlassModal destroyOnHidden：回填用 initialValues，挂载时生效
    setTimeout(() => {
      form.setFieldsValue({
        name: c.name,
        categoryId: c.categoryId ?? undefined,
        activityId: c.activityId ?? undefined,
        departments: c.departments,
        ownerId: c.ownerId ?? undefined,
        qrCodeUrl: c.qrCodeUrl,
        remark: c.remark,
        status: c.status,
      })
    }, 0)
  }

  const handleQrUpload = async (file: File) => {
    try {
      const rec = await uploadFile(file, 'OTHER')
      setQrUrl(`/uploads/${rec.path}`)
      form.setFieldsValue({ qrCodeUrl: `/uploads/${rec.path}` })
      message.success('二维码已上传')
    } catch {
      /* http 拦截已提示 */
    }
  }

  const handleSave = async () => {
    const values = await form.validateFields()
    setSaving(true)
    try {
      const payload: GroupChatSave = {
        name: values.name.trim(),
        categoryId: values.categoryId ?? null,
        activityId: values.activityId ?? null,
        departments: values.departments ?? [],
        ownerId: values.ownerId ?? null,
        qrCodeUrl: qrUrl ?? undefined,
        remark: values.remark?.trim() || undefined,
        status: values.status ?? 'ACTIVE',
      }
      if (editing) {
        await updateGroupChat(editing.id, payload)
        message.success('群聊已更新')
      } else {
        await createGroupChat(payload)
        message.success('群聊已创建')
      }
      setModalOpen(false)
      fetchAll()
    } catch {
      /* http 拦截已提示 */
    } finally {
      setSaving(false)
    }
  }

  const handleArchive = async (id: number) => {
    try {
      await archiveGroupChat(id)
      message.success('已归档')
      fetchAll()
    } catch {
      /* http 拦截已提示 */
    }
  }

  const handleDelete = async (id: number) => {
    try {
      await deleteGroupChat(id)
      message.success('已删除')
      fetchAll()
    } catch {
      /* http 拦截已提示 */
    }
  }

  const addCategory = async () => {
    if (!newCategory.trim()) {
      message.warning('请输入分类名称')
      return
    }
    try {
      await createChatCategory(newCategory.trim())
      message.success('分类已创建')
      setNewCategory('')
      fetchAll()
    } catch {
      /* http 拦截已提示 */
    }
  }

  const handleRenameCategory = async (id: number, name: string) => {
    const next = window.prompt('重命名分类', name)
    if (!next?.trim()) return
    try {
      await renameChatCategory(id, next.trim())
      message.success('已重命名')
      fetchAll()
    } catch {
      /* http 拦截已提示 */
    }
  }

  const handleDeleteCategory = async (id: number) => {
    try {
      await deleteChatCategory(id)
      message.success('分类已删除')
      fetchAll()
    } catch {
      /* http 拦截已提示 */
    }
  }

  const filtered = useMemo(() => chats, [chats])

  const renderCard = (c: GroupChatVO) => {
    const st = STATUS_MAP[c.status] ?? { label: c.status, color: 'default' }
    return (
      <Card
        key={c.id}
        size="small"
        style={{ width: 300, marginBottom: 12 }}
        title={
          <Space>
            <span style={{ fontWeight: 600 }}>{c.name}</span>
            <Tag color={st.color}>{st.label}</Tag>
          </Space>
        }
        extra={
          <Space size={0}>
            <Button type="link" size="small" icon={<EditOutlined />} onClick={() => openEdit(c)} />
            {c.status !== 'ARCHIVED' && (
              <Button type="link" size="small" icon={<InboxOutlined />} onClick={() => handleArchive(c.id)} title="归档" />
            )}
            <Popconfirm title="确认删除该群聊？" onConfirm={() => handleDelete(c.id)} okText="删除" cancelText="取消">
              <Button type="link" size="small" danger icon={<DeleteOutlined />} />
            </Popconfirm>
          </Space>
        }
      >
        <div style={{ display: 'flex', flexDirection: 'column', gap: 6, fontSize: 13 }}>
          <div>
            <Tag color="blue">{categoryNameOf(c.categoryId)}</Tag>
            {c.departments?.map((d) => (
              <Tag key={d}>{d}</Tag>
            ))}
          </div>
          <div style={{ color: 'var(--color-text-secondary)' }}>
            负责人：{ownerNameOf(c.ownerId)}
          </div>
          {c.activityName && <div style={{ color: 'var(--color-text-secondary)' }}>关联活动：{c.activityName}</div>}
          {c.remark && <div style={{ color: 'var(--color-text-secondary)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>备注：{c.remark}</div>}
          {c.qrCodeUrl && (
            <Tooltip title="点击查看群二维码">
              <a href={c.qrCodeUrl} target="_blank" rel="noreferrer">
                <QrcodeOutlined /> 群二维码
              </a>
            </Tooltip>
          )}
        </div>
      </Card>
    )
  }

  const columns = [
    { title: '群聊名称', dataIndex: 'name', key: 'name', ellipsis: true },
    {
      title: '分类',
      key: 'category',
      render: (_: unknown, r: GroupChatVO) => <Tag color="blue">{categoryNameOf(r.categoryId)}</Tag>,
    },
    {
      title: '关联部门',
      key: 'departments',
      render: (_: unknown, r: GroupChatVO) => r.departments?.map((d) => <Tag key={d}>{d}</Tag>) ?? '-',
    },
    { title: '负责人', key: 'owner', width: 100, render: (_: unknown, r: GroupChatVO) => ownerNameOf(r.ownerId) },
    {
      title: '状态',
      key: 'status',
      width: 90,
      render: (_: unknown, r: GroupChatVO) => {
        const st = STATUS_MAP[r.status] ?? { label: r.status, color: 'default' }
        return <Tag color={st.color}>{st.label}</Tag>
      },
    },
    {
      title: '操作',
      key: 'action',
      width: 160,
      render: (_: unknown, r: GroupChatVO) => (
        <Space size="small" wrap>
          <Button type="link" size="small" icon={<EditOutlined />} onClick={() => openEdit(r)}>
            编辑
          </Button>
          {r.status !== 'ARCHIVED' && (
            <Button type="link" size="small" icon={<InboxOutlined />} onClick={() => handleArchive(r.id)}>
              归档
            </Button>
          )}
          <Popconfirm title="确认删除该群聊？" onConfirm={() => handleDelete(r.id)} okText="删除" cancelText="取消">
            <Button type="link" size="small" danger icon={<DeleteOutlined />}>
              删除
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ]

  return (
    <div>
      <PageHeader
        title="群聊管理"
        description="统一管理党务工作微信群：分类、关联活动/部门、负责人与二维码"
        extra={
          <Space>
            <Button icon={<AppstoreOutlined />} onClick={() => setCategoryOpen(true)}>
              分类管理
            </Button>
            <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
              新增群聊
            </Button>
          </Space>
        }
      />

      <GlassCard style={{ padding: 16, marginBottom: 16 }}>
        <Space wrap>
          <Input.Search
            placeholder="搜索群聊名称"
            allowClear
            style={{ width: 200 }}
            prefix={<SearchOutlined />}
            onSearch={(v) => setKeyword(v)}
          />
          <Select
            placeholder="按分类筛选"
            allowClear
            style={{ width: 140 }}
            value={filterCategory}
            onChange={setFilterCategory}
            options={categories.map((c) => ({ value: c.id, label: c.name }))}
          />
          <Select
            placeholder="按状态筛选"
            allowClear
            style={{ width: 120 }}
            value={filterStatus}
            onChange={setFilterStatus}
            options={Object.entries(STATUS_MAP).map(([v, m]) => ({ value: v, label: m.label }))}
          />
          <Select
            placeholder="按部门筛选"
            allowClear
            style={{ width: 130 }}
            value={filterDept}
            onChange={setFilterDept}
            options={depts.map((d) => ({ value: d.name, label: d.name }))}
          />
          <Radio.Group value={view} onChange={(e) => setView(e.target.value)}>
            <Radio.Button value="card">
              <AppstoreOutlined /> 卡片
            </Radio.Button>
            <Radio.Button value="list">
              <UnorderedListOutlined /> 列表
            </Radio.Button>
          </Radio.Group>
        </Space>
      </GlassCard>

      {view === 'card' ? (
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 12 }}>
          {filtered.length === 0 ? (
            <Empty style={{ margin: '40px auto' }} description="暂无群聊" />
          ) : (
            filtered.map(renderCard)
          )}
        </div>
      ) : (
        <GlassCard style={{ padding: 16 }}>
          {filtered.length === 0 ? (
            <Empty description="暂无群聊" />
          ) : (
            <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
              <thead>
                <tr style={{ textAlign: 'left', color: 'var(--color-text-secondary)' }}>
                  {columns.map((c) => (
                    <th key={c.key} style={{ padding: '8px 10px', borderBottom: '1px solid var(--color-border)' }}>
                      {c.title}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {filtered.map((r) => (
                  <tr key={r.id}>
                    {columns.map((c) => {
                      // 渲染列：优先 render，否则取字段值
                      let cell: React.ReactNode
                      if (c.render) {
                        cell = c.render(undefined, r)
                      } else if (c.dataIndex) {
                        cell = (r as unknown as Record<string, unknown>)[c.dataIndex] as React.ReactNode
                      } else {
                        cell = null
                      }
                      return (
                        <td key={c.key} style={{ padding: '8px 10px', borderBottom: '1px solid var(--color-border)' }}>
                          {cell}
                        </td>
                      )
                    })}
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </GlassCard>
      )}

      {/* 新增/编辑弹窗 */}
      <GlassModal
        title={editing ? '编辑群聊' : '新增群聊'}
        open={modalOpen}
        onCancel={() => setModalOpen(false)}
        width={560}
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
          key={editing ? `chat-edit-${editing.id}` : 'chat-create'}
          initialValues={{ status: 'ACTIVE', departments: [] }}
        >
          <Form.Item name="name" label="群聊名称" rules={[{ required: true, message: '请输入群聊名称' }]}>
            <Input maxLength={50} placeholder="如 第四十期培训班活动群" />
          </Form.Item>
          <Space size={12} style={{ display: 'flex' }}>
            <Form.Item name="categoryId" label="分类" rules={[{ required: true, message: '请选择分类' }]} style={{ flex: 1 }}>
              <Select
                placeholder="选择分类"
                options={categories.map((c) => ({ value: c.id, label: c.name }))}
              />
            </Form.Item>
            <Form.Item name="status" label="状态" style={{ flex: 1 }}>
              <Select
                options={Object.entries(STATUS_MAP).map(([v, m]) => ({ value: v, label: m.label }))}
              />
            </Form.Item>
          </Space>
          <Form.Item name="activityId" label="关联活动">
            <Select
              allowClear
              showSearch
              optionFilterProp="label"
              placeholder="选择活动（可选）"
              options={activities.map((a) => ({ value: a.id, label: a.name }))}
            />
          </Form.Item>
          <Form.Item name="departments" label="关联部门">
            <Select
              mode="multiple"
              placeholder="选择部门（可多选）"
              options={depts.map((d) => ({ value: d.name, label: d.name }))}
            />
          </Form.Item>
          <Form.Item name="ownerId" label="群主/负责人" rules={[{ required: true, message: '请选择负责人' }]}>
            <Select
              showSearch
              optionFilterProp="label"
              placeholder="选择系统内用户"
              options={users.map((u) => ({ value: u.id, label: `${u.realName}（${u.deptName ?? u.roleName}）` }))}
            />
          </Form.Item>
          <Form.Item name="qrCodeUrl" label="群二维码">
            <div>
              <label
                style={{
                  display: 'inline-flex',
                  alignItems: 'center',
                  gap: 6,
                  padding: '8px 16px',
                  border: '1px dashed var(--color-primary)',
                  borderRadius: 8,
                  color: 'var(--color-primary)',
                  cursor: 'pointer',
                  fontSize: 13,
                }}
              >
                <QrcodeOutlined /> {qrUrl ? '重新上传' : '上传二维码图片（可选）'}
                <input
                  type="file"
                  accept="image/*"
                  style={{ display: 'none' }}
                  onChange={(e) => {
                    const f = e.target.files?.[0]
                    if (f) handleQrUpload(f)
                    e.target.value = ''
                  }}
                />
              </label>
              {qrUrl && (
                <span style={{ marginLeft: 8, fontSize: 12, color: 'var(--color-text-secondary)' }}>
                  已上传
                </span>
              )}
            </div>
          </Form.Item>
          <Form.Item name="remark" label="备注">
            <Input.TextArea rows={2} maxLength={200} placeholder="备注信息（可选）" />
          </Form.Item>
        </Form>
      </GlassModal>

      {/* 分类管理弹窗 */}
      <Modal
        title="分类管理"
        open={categoryOpen}
        onCancel={() => setCategoryOpen(false)}
        footer={<Button onClick={() => setCategoryOpen(false)}>关闭</Button>}
      >
        <Space style={{ marginBottom: 12, width: '100%' }}>
          <Input
            value={newCategory}
            onChange={(e) => setNewCategory(e.target.value)}
            placeholder="新分类名称，如 活动群"
            onPressEnter={addCategory}
          />
          <Button type="primary" onClick={addCategory}>
            新增
          </Button>
        </Space>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          {categories.map((c) => (
            <div key={c.id} style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '6px 10px', border: '1px solid var(--color-border)', borderRadius: 8 }}>
              <span style={{ flex: 1 }}>{c.name}</span>
              <Button type="link" size="small" icon={<EditOutlined />} onClick={() => handleRenameCategory(c.id, c.name)} />
              <Popconfirm title="删除该分类？" onConfirm={() => handleDeleteCategory(c.id)} okText="删除" cancelText="取消">
                <Button type="link" size="small" danger icon={<DeleteOutlined />} />
              </Popconfirm>
            </div>
          ))}
          {categories.length === 0 && <Empty description="暂无分类" image={Empty.PRESENTED_IMAGE_SIMPLE} />}
        </div>
      </Modal>
    </div>
  )
}
