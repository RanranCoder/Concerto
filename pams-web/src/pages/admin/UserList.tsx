import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Button, Form, Input, message, Popconfirm, Select, Space, Switch, Tag, Typography } from 'antd'
import type { TableColumnsType } from 'antd'
import { DeleteOutlined, EditOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import GlassCard from '@/components/glass/GlassCard'
import GlassModal from '@/components/glass/GlassModal'
import GlassTable from '@/components/glass/GlassTable'
import PageHeader from '@/components/glass/PageHeader'
import { useAuthStore } from '@/stores/auth'
import { listDepts, type DeptVO } from '@/api/dept'
import {
  createUser,
  deleteUser,
  listRoles,
  listUsers,
  resetPassword,
  updateUser,
  type RoleVO,
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

/** 部长角色 code → 自动绑定部门（可手动覆盖）。部门名与 DataSeeder 种子一致。 */
const LEADER_DEPT_MAP: Record<string, string> = {
  ORG_LEADER: '组织部',
  SECRETARY_LEADER: '文秘部',
  MEDIA_LEADER: '新媒体中心',
  TECH_LEADER: '青年科技部',
}

export default function UserList() {
  const currentUser = useAuthStore((s) => s.user)
  // 用户管理页仅部长及以上可见（roleLevel >= 3）；本页本就被菜单隐藏，此处兜底隐藏操作
  const isMinisterOrAbove = (currentUser?.roleLevel ?? 0) >= 3

  const [data, setData] = useState<UserRecord[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(false)
  const [page, setPage] = useState(1)
  const [size, setSize] = useState(10)
  const [keyword, setKeyword] = useState('')
  const [deptFilter, setDeptFilter] = useState<number | undefined>()

  const [depts, setDepts] = useState<DeptVO[]>([])
  const [roles, setRoles] = useState<RoleVO[]>([])

  const [modalOpen, setModalOpen] = useState(false)
  const [editing, setEditing] = useState<UserVO | null>(null)
  const [saving, setSaving] = useState(false)
  const [form] = Form.useForm<UserFormValues>()

  // 当前选中角色的 code（联动判断用）
  const roleId = Form.useWatch('roleId', form)
  const selectedRole = useMemo(() => roles.find((r) => r.id === roleId), [roles, roleId])
  // 上一次角色选择（联动需要"上一部长角色"才能判断部门是否被手动覆盖过）
  const lastRoleIdRef = useRef<number | undefined>()

  const deptOptions = useMemo(() => depts.map((d) => ({ value: d.id, label: d.name })), [depts])
  const roleOptions = useMemo(
    () => roles.map((r) => ({ value: r.id, label: r.name })),
    [roles],
  )

  const fetchMeta = useCallback(() => {
    listDepts().then(setDepts).catch(() => {
      /* http 拦截已提示 */
    })
    listRoles().then(setRoles).catch(() => {
      /* http 拦截已提示 */
    })
  }, [])

  useEffect(() => {
    fetchMeta()
  }, [fetchMeta])

  const fetchList = useCallback(async () => {
    setLoading(true)
    try {
      const res = await listUsers({ keyword: keyword || undefined, deptId: deptFilter, page, size })
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
    lastRoleIdRef.current = undefined
    setModalOpen(true)
  }

  const openEdit = (record: UserVO) => {
    setEditing(record)
    lastRoleIdRef.current = roles.find((r) => r.code === record.roleCode)?.id
    setModalOpen(true)
  }

  /**
   * 编辑表单回填用 Form initialValues 而非 setFieldsValue：
   * GlassModal destroyOnClose 会在关闭时卸载字段，打开时才挂载，setFieldsValue 在挂载前调用会丢失
   * （Task 21 NewsList 同款缺陷，此处同步修复）。
   */
  const formInitialValues = useMemo(() => {
    if (!editing) return { status: 1 }
    const roleId = roles.find((r) => r.code === editing.roleCode)?.id
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

  /**
   * 角色-部门联动：选中部长角色（组织/文秘/新媒体/青年科技部长）自动绑定对应部门，可手动覆盖。
   * 只有当前部门为空、或当前部门是上一个部长角色的自动绑定部门（说明未经手动修改）时才跟随新角色，
   * 用户手动改过的部门不会被角色切换覆盖。
   */
  const handleRoleChange = (nextRoleId?: number) => {
    if (nextRoleId == null) return
    const prevRoleId = lastRoleIdRef.current
    lastRoleIdRef.current = nextRoleId
    const nextRole = roles.find((r) => r.id === nextRoleId)
    if (!nextRole) return
    const prevRole = roles.find((r) => r.id === prevRoleId)
    const prevAutoDeptName = prevRole ? LEADER_DEPT_MAP[prevRole.code] : undefined
    const nextAutoDeptName = LEADER_DEPT_MAP[nextRole.code]
    if (!nextAutoDeptName) return
    const dept = depts.find((d) => d.name === nextAutoDeptName)
    if (!dept) return
    const cur = form.getFieldValue('deptId')
    const curDeptName = depts.find((d) => d.id === cur)?.name
    // 部门为空，或当前部门正是上一部长角色的自动绑定部门 → 跟随新角色绑定
    if (cur == null || (prevAutoDeptName && curDeptName === prevAutoDeptName)) {
      form.setFieldsValue({ deptId: dept.id })
    }
  }

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
        await updateUser(editing.id, payload)
        message.success('用户已更新')
      } else {
        await createUser({ ...payload, password: values.password?.trim() || undefined })
        message.success('用户已创建')
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

  const handleResetPassword = async (id: number) => {
    try {
      await resetPassword(id)
      message.success('密码已重置为 123456')
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
    { title: '部门', dataIndex: 'deptName', key: 'deptName', width: 130, render: (v: string | null) => v || '-' },
    { title: '角色', dataIndex: 'roleName', key: 'roleName', width: 120, render: (v: string | null) => v || '-' },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 90,
      render: (s: number | null) => (s === 1 ? <Tag color="success">启用</Tag> : <Tag color="error">禁用</Tag>),
    },
    {
      title: '操作',
      key: 'action',
      width: 210,
      render: (_: unknown, r: UserRecord) => (
        <Space size="small">
          <Button type="link" size="small" icon={<EditOutlined />} onClick={() => openEdit(r)}>
            编辑
          </Button>
          <Popconfirm title="重置密码？" description="密码将被重置为 123456" onConfirm={() => handleResetPassword(r.id)} okText="重置" cancelText="取消">
            <Button type="link" size="small" icon={<ReloadOutlined />}>
              重置密码
            </Button>
          </Popconfirm>
          <Popconfirm title="确认删除该用户？" onConfirm={() => handleDelete(r.id)} okText="删除" cancelText="取消">
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
        title="用户管理"
        description="党建办公室成员账号管理（部长及以上），新增 / 编辑 / 重置密码 / 删除"
        extra={
          isMinisterOrAbove ? (
            <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
              新增用户
            </Button>
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
      <GlassModal
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
        <Form form={form} layout="vertical" preserve={false} initialValues={formInitialValues}>
          <Form.Item
            name="username"
            label="用户名"
            rules={[
              { required: true, message: '请输入用户名' },
              { pattern: /^[a-zA-Z0-9_]+$/, message: '仅字母数字下划线' },
            ]}
          >
            <Input maxLength={50} placeholder="登录账号" disabled={!!editing} />
          </Form.Item>
          <Form.Item
            name="realName"
            label="姓名"
            rules={[{ required: true, message: '请输入姓名' }]}
          >
            <Input maxLength={50} placeholder="真实姓名" />
          </Form.Item>
          <Space size="middle" wrap>
            <Form.Item name="studentNo" label="学号">
              <Input maxLength={20} style={{ width: 160 }} placeholder="学生学号" />
            </Form.Item>
            <Form.Item name="phone" label="电话">
              <Input maxLength={20} style={{ width: 160 }} placeholder="联系电话" />
            </Form.Item>
          </Space>
          <Form.Item
            name="roleId"
            label="角色"
            rules={[{ required: true, message: '请选择角色' }]}
            extra={
              selectedRole && LEADER_DEPT_MAP[selectedRole.code] ? (
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  已自动绑定部门：{LEADER_DEPT_MAP[selectedRole.code]}（可手动修改）
                </Typography.Text>
              ) : null
            }
          >
            <Select options={roleOptions} placeholder="请选择角色" onChange={handleRoleChange} />
          </Form.Item>
          <Form.Item name="deptId" label="部门">
            <Select options={deptOptions} allowClear placeholder={selectedRole && LEADER_DEPT_MAP[selectedRole.code] ? `默认 ${LEADER_DEPT_MAP[selectedRole.code]}` : '选部长角色可自动绑定'} />
          </Form.Item>
          {!editing && (
            <Form.Item
              name="password"
              label="密码"
              extra="留空则默认 123456"
            >
              <Input.Password maxLength={100} placeholder="默认 123456" autoComplete="new-password" />
            </Form.Item>
          )}
          <Form.Item name="status" label="状态" valuePropName="checked" getValueFromEvent={(checked: boolean) => (checked ? 1 : 0)} getValueProps={(v?: number) => ({ checked: v === 1 })}>
            <Switch checkedChildren="启用" unCheckedChildren="禁用" />
          </Form.Item>
        </Form>
      </GlassModal>
    </div>
  )
}
