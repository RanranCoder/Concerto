import FormSurface, { FormSection } from '@/components/form/FormSurface'
import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  App,
  Button,
  Form,
  Input,
  Popconfirm,
  Select,
  Space,
  Switch,
  Tag,
} from 'antd'
import type { TableColumnsType } from 'antd'
import { DeleteOutlined, EditOutlined, PlusOutlined } from '@ant-design/icons'
import GlassCard from '@/components/glass/GlassCard'
import GlassTable from '@/components/glass/GlassTable'
import PageHeader from '@/components/glass/PageHeader'
import { useAuthStore } from '@/stores/auth'
import {
  assignableRoles,
  type ManagedRole,
  previewRoleAssignment,
  applyAuthorizationChange,
} from '@/api/permission'
import { listDepts, type DeptVO } from '@/api/dept'
import {
  createUser,
  deleteUser,
  listUsers,
  updateUser,
  type UserSave,
  type UserVO,
} from '@/api/user'

type UserRecord = UserVO & { key: number }

interface UserFormValues {
  username: string
  password?: string
  realName: string
  studentNo?: string
  phone?: string
  deptId?: number | null
  roleId?: number
  status?: number
}

export default function UserList() {
  const { message, modal } = App.useApp()
  const currentUser = useAuthStore((s) => s.user)
  // 管理身份独立于业务角色。
  const canManageUsers =
    currentUser?.managementCapabilities?.includes('user:manage') ?? false

  const [data, setData] = useState<UserRecord[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(false)
  const [page, setPage] = useState(1)
  const [size, setSize] = useState(10)
  const [keyword, setKeyword] = useState('')
  const [deptFilter, setDeptFilter] = useState<number | undefined>()

  const [depts, setDepts] = useState<DeptVO[]>([])
  const [roles, setRoles] = useState<ManagedRole[]>([])

  const [modalOpen, setModalOpen] = useState(false)
  const [editing, setEditing] = useState<UserVO | null>(null)
  const [saving, setSaving] = useState(false)
  const [form] = Form.useForm<UserFormValues>()

  const deptOptions = useMemo(
    () => depts.map((d) => ({ value: d.id, label: d.name })),
    [depts],
  )
  const roleOptions = useMemo(() => {
    const options = roles.map((r) => ({ value: r.id, label: r.name }))
    if (editing?.roleId && !options.some((r) => r.value === editing.roleId))
      options.push({
        value: editing.roleId,
        label: editing.roleName ?? '当前角色',
      })
    return options
  }, [roles, editing])

  const fetchMeta = useCallback(() => {
    listDepts()
      .then(setDepts)
      .catch(() => {
        /* http 拦截已提示 */
      })
    assignableRoles()
      .then(setRoles)
      .catch(() => {
        /* http 拦截已提示 */
      })
  }, [])

  useEffect(() => {
    fetchMeta()
  }, [fetchMeta])

  const fetchList = useCallback(async () => {
    setLoading(true)
    try {
      const res = await listUsers({
        keyword: keyword || undefined,
        deptId: deptFilter,
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
  }, [keyword, deptFilter, page, size])

  useEffect(() => {
    fetchList()
  }, [fetchList])

  const openCreate = () => {
    setEditing(null)
    setModalOpen(true)
  }

  const openEdit = (record: UserVO) => {
    setEditing(record)
    setModalOpen(true)
  }

  /**
   * 编辑表单回填用 Form initialValues 而非 setFieldsValue：
   * GlassModal destroyOnHidden 会在关闭时卸载字段，打开时才挂载，setFieldsValue 在挂载前调用会丢失
   * （Task 21 NewsList 同款缺陷，此处同步修复）。
   */
  const formInitialValues = useMemo(() => {
    if (!editing) return { status: 1 }
    const roleId = editing.roleId
    return {
      username: editing.username,
      realName: editing.realName,
      studentNo: editing.studentNo || undefined,
      phone: editing.phone || undefined,
      deptId: editing.deptId,
      roleId,
      status: editing.status ?? 1,
    }
  }, [editing, roles])

  const handleSave = async () => {
    const values = await form.validateFields()
    setSaving(true)
    try {
      const payload: UserSave = {
        username: values.username.trim(),
        realName: values.realName.trim(),
        studentNo: values.studentNo?.trim() || null,
        phone: values.phone?.trim() || null,
        deptId: values.deptId ?? null,
        roleId: values.roleId!,
        status: values.status ?? 1,
      }
      if (editing) {
        if (editing.roleId && payload.roleId !== editing.roleId) {
          const preview = await previewRoleAssignment(
            editing.id,
            payload.roleId,
          )
          const confirmed = await new Promise<boolean>((resolve) =>
            modal.confirm({
              title: '确认替换主角色？',
              content: `${editing.realName}：${editing.roleName ?? '原角色'} → ${roles.find((r) => r.id === payload.roleId)?.name ?? payload.roleId}。原角色权限将被替换。`,
              onOk: () => resolve(true),
              onCancel: () => resolve(false),
            }),
          )
          if (!confirmed) return
          await applyAuthorizationChange(preview.id)
        }
        await updateUser(editing.id, payload)
        message.success('用户已更新')
      } else {
        await createUser({
          ...payload,
          password: values.password?.trim() || undefined,
        })
        message.success('用户已创建')
        // 新建后回到第 1 页，保证新用户可见（Task 25 minor）
        setPage(1)
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
      await deleteUser(id)
      message.success('已删除')
      fetchList()
    } catch {
      /* http 拦截已提示 */
    }
  }

  const columns: TableColumnsType<UserRecord> = [
    { title: '用户名', dataIndex: 'username', key: 'username', width: 130 },
    { title: '姓名', dataIndex: 'realName', key: 'realName', width: 110 },
    {
      title: '学号',
      dataIndex: 'studentNo',
      key: 'studentNo',
      width: 130,
      render: (v: string) => v || '-',
    },
    {
      title: '部门',
      dataIndex: 'deptName',
      key: 'deptName',
      width: 130,
      render: (v: string | null) => v || '-',
    },
    {
      title: '角色',
      dataIndex: 'roleName',
      key: 'roleName',
      width: 120,
      render: (v: string | null) => v || '-',
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 90,
      render: (s: number | null) =>
        s === 1 ? (
          <Tag color="success">启用</Tag>
        ) : (
          <Tag color="error">禁用</Tag>
        ),
    },
    {
      title: '操作',
      key: 'action',
      align: 'right',
      width: 210,
      render: (_: unknown, r: UserRecord) => (
        <Space size="small">
          <Button
            type="link"
            size="small"
            icon={<EditOutlined />}
            onClick={() => openEdit(r)}
          >
            编辑
          </Button>
          <Popconfirm
            title="确认删除该用户？"
            onConfirm={() => handleDelete(r.id)}
            okText="删除"
            cancelText="取消"
          >
            <Button
              type="link"
              size="small"
              danger
              icon={<DeleteOutlined />}
              disabled={!canManageUsers || r.id === currentUser?.id}
            >
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
        title="用户管理"
        description="本组织账号管理；主角色与管理员身份分开，角色替换须预览确认"
        extra={
          canManageUsers ? (
            <Space>
              <Button
                type="primary"
                icon={<PlusOutlined />}
                onClick={openCreate}
              >
                新增用户
              </Button>
            </Space>
          ) : null
        }
      />

      <GlassCard style={{ padding: 16, marginBottom: 16 }}>
        <Space wrap>
          <Input.Search
            placeholder="用户名 / 姓名"
            allowClear
            style={{ width: 240 }}
            onSearch={(v) => {
              setPage(1)
              setKeyword(v)
            }}
          />
          <Select
            placeholder="部门筛选"
            allowClear
            options={deptOptions}
            style={{ width: 180 }}
            value={deptFilter}
            onChange={(v) => {
              setPage(1)
              setDeptFilter(v ?? undefined)
            }}
          />
        </Space>
      </GlassCard>

      <GlassTable<UserRecord>
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

      {/* 新增 / 编辑用户 */}
      <FormSurface
        mode="drawer"
        description="填写账号资料并分配所属部门与业务角色。"
        title={editing ? '编辑用户' : '新增用户'}
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
          initialValues={formInitialValues}
        >
          <FormSection title="账号资料">
            <Form.Item
              name="username"
              label="用户名"
              rules={[
                { required: true, message: '请输入用户名' },
                { pattern: /^[a-zA-Z0-9_]+$/, message: '仅字母数字下划线' },
              ]}
            >
              <Input
                maxLength={50}
                placeholder="登录账号"
                disabled={!!editing}
              />
            </Form.Item>
            <Form.Item
              name="realName"
              label="姓名"
              rules={[{ required: true, message: '请输入姓名' }]}
            >
              <Input maxLength={50} placeholder="真实姓名" />
            </Form.Item>
            <Form.Item name="studentNo" label="学号">
              <Input
                maxLength={20}
                style={{ width: 160 }}
                placeholder="学生学号"
              />
            </Form.Item>
            <Form.Item name="phone" label="电话">
              <Input
                maxLength={20}
                style={{ width: 160 }}
                placeholder="联系电话"
              />
            </Form.Item>
          </FormSection>
          <FormSection title="角色与所属部门">
            <Form.Item
              name="roleId"
              label="角色"
              rules={[{ required: true, message: '请选择角色' }]}
            >
              <Select options={roleOptions} placeholder="请选择角色" />
            </Form.Item>
            <Form.Item name="deptId" label="部门">
              <Select
                options={deptOptions}
                allowClear
                placeholder="独立选择所属部门"
              />
            </Form.Item>
          </FormSection>
          <FormSection title="登录与状态">
            {!editing && (
              <Form.Item
                name="password"
                label="密码"
                rules={[
                  {
                    required: true,
                    min: 8,
                    message: '请输入至少 8 位初始密码',
                  },
                ]}
              >
                <Input.Password
                  maxLength={100}
                  placeholder="至少 8 位初始密码"
                  autoComplete="new-password"
                />
              </Form.Item>
            )}
            <Form.Item
              name="status"
              label="状态"
              valuePropName="checked"
              getValueFromEvent={(checked: boolean) => (checked ? 1 : 0)}
              getValueProps={(v?: number) => ({ checked: v === 1 })}
            >
              <Switch checkedChildren="启用" unCheckedChildren="禁用" />
            </Form.Item>
          </FormSection>
        </Form>
      </FormSurface>
    </div>
  )
}
