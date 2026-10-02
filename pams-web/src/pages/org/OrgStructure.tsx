import { useCallback, useEffect, useMemo, useState } from 'react'
import { App, Button, Card, Empty, Form, Input, InputNumber, Popconfirm, Select, Space, Tree, Typography } from 'antd'
import { DeleteOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import { createInvitation, createUnit, deleteUnit, listUnits, updateUnit } from '@/api/org'
import type { OrgInvitation, OrgUnitNode } from '@/api/org'

const UNIT_TYPES = ['部门', '中心', '小组', '分队']

const ROLE_OPTIONS = [
  { value: 'DIRECTOR', label: '主任/主席' },
  { value: 'ORG_LEADER', label: '组织部长' },
  { value: 'SECRETARY_LEADER', label: '文秘部长' },
  { value: 'MEDIA_LEADER', label: '新媒体部长' },
  { value: 'TECH_LEADER', label: '青年科技部长' },
  { value: 'STAFF', label: '干事' },
  { value: 'TEACHER', label: '指导老师' },
]

interface TreeItem {
  key: number
  title: string
  children?: TreeItem[]
  raw: OrgUnitNode
}

function toTreeData(nodes: OrgUnitNode[]): TreeItem[] {
  return nodes.map((n) => ({
    key: n.id,
    title: `${n.name}${n.unitType ? `（${n.unitType}）` : ''}`,
    raw: n,
    children: n.children && n.children.length ? toTreeData(n.children) : undefined,
  }))
}

function findNode(nodes: OrgUnitNode[], id: number): OrgUnitNode | undefined {
  for (const n of nodes) {
    if (n.id === id) return n
    const hit = n.children ? findNode(n.children, id) : undefined
    if (hit) return hit
  }
  return undefined
}

export default function OrgStructure() {
  const { message } = App.useApp()
  const [form] = Form.useForm()
  const [units, setUnits] = useState<OrgUnitNode[]>([])
  const [selectedId, setSelectedId] = useState<number | null>(null)
  const [loading, setLoading] = useState(false)
  const [inviteRole, setInviteRole] = useState('STAFF')
  const [invitation, setInvitation] = useState<OrgInvitation | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const data = await listUnits()
      setUnits(data)
    } catch {
      /* http 拦截已提示 */
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    load()
  }, [load])

  const treeData = useMemo(() => toTreeData(units), [units])
  const selected = selectedId ? findNode(units, selectedId) : undefined

  useEffect(() => {
    if (selected) {
      form.setFieldsValue({
        name: selected.name,
        unitType: selected.unitType,
        leaderUserId: selected.leaderUserId,
        sortOrder: selected.sortOrder,
      })
    } else {
      form.resetFields()
    }
  }, [selected, form])

  const onAdd = async (asChild: boolean) => {
    try {
      const values = await form.validateFields(['name'])
      await createUnit({
        name: values.name,
        unitType: form.getFieldValue('unitType') ?? '部门',
        parentId: asChild && selectedId ? selectedId : null,
        leaderUserId: form.getFieldValue('leaderUserId') ?? null,
        sortOrder: form.getFieldValue('sortOrder') ?? 0,
      })
      message.success('已新增节点')
      form.resetFields()
      await load()
    } catch {
      /* 校验或接口错误 */
    }
  }

  const onSave = async () => {
    if (!selectedId) return
    const values = await form.validateFields()
    try {
      await updateUnit(selectedId, {
        name: values.name,
        unitType: values.unitType,
        leaderUserId: values.leaderUserId ?? null,
        sortOrder: values.sortOrder ?? 0,
      })
      message.success('已保存')
      await load()
    } catch {
      /* 已提示 */
    }
  }

  const onDelete = async () => {
    if (!selectedId) return
    try {
      await deleteUnit(selectedId)
      message.success('已删除')
      setSelectedId(null)
      await load()
    } catch {
      /* 已提示 */
    }
  }

  const onInvite = async () => {
    try {
      const inv = await createInvitation({
        roleCode: inviteRole,
        orgUnitId: selectedId ?? undefined,
        ttlHours: 72,
      })
      setInvitation(inv)
      message.success('已生成邀请码')
    } catch {
      /* 已提示 */
    }
  }

  return (
    <div style={{ padding: 16, display: 'flex', gap: 16, flexWrap: 'wrap' }}>
      <Card
        title="组织架构"
        style={{ width: 380 }}
        extra={<Button size="small" icon={<ReloadOutlined />} onClick={load} loading={loading}>刷新</Button>}
      >
        {treeData.length === 0 ? (
          <Empty description="暂无架构节点" />
        ) : (
          <Tree
            treeData={treeData}
            defaultExpandAll
            selectedKeys={selectedId ? [selectedId] : []}
            onSelect={(keys) => setSelectedId(keys.length ? Number(keys[0]) : null)}
          />
        )}
      </Card>

      <Card title={selected ? '编辑节点' : '新增节点'} style={{ width: 420 }}>
        <Form form={form} layout="vertical" initialValues={{ unitType: '部门', sortOrder: 0 }}>
          <Form.Item name="name" label="节点名称" rules={[{ required: true, message: '请输入名称' }]}>
            <Input placeholder="如 组织部" />
          </Form.Item>
          <Form.Item name="unitType" label="节点类型">
            <Select options={UNIT_TYPES.map((t) => ({ value: t, label: t }))} />
          </Form.Item>
          <Form.Item name="leaderUserId" label="负责人用户ID（可选）">
            <InputNumber style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="sortOrder" label="排序">
            <InputNumber style={{ width: '100%' }} />
          </Form.Item>
        </Form>
        <Space wrap>
          {selected ? (
            <>
              <Button type="primary" onClick={onSave}>保存</Button>
              <Button onClick={() => onAdd(true)} icon={<PlusOutlined />}>在其下新增子节点</Button>
              <Popconfirm title="确认删除该节点？" onConfirm={onDelete}>
                <Button danger icon={<DeleteOutlined />}>删除</Button>
              </Popconfirm>
            </>
          ) : (
            <Button type="primary" onClick={() => onAdd(false)} icon={<PlusOutlined />}>新增一级节点</Button>
          )}
          {selected && <Button type="link" onClick={() => setSelectedId(null)}>切换为新增</Button>}
        </Space>
        <Typography.Paragraph type="secondary" style={{ marginTop: 12 }}>
          提示：删除前需先清空其下级节点与该节点下的成员。
        </Typography.Paragraph>
      </Card>

      <Card title="邀请入组" style={{ width: 380 }}>
        <Space direction="vertical" style={{ width: '100%' }}>
          <Select
            value={inviteRole}
            style={{ width: '100%' }}
            options={ROLE_OPTIONS}
            onChange={setInviteRole}
          />
          <Typography.Text type="secondary">
            预约部门：{selected ? selected.name : '（未选，加入后由管理员分配）'}
          </Typography.Text>
          <Button type="primary" onClick={onInvite}>生成邀请码（72 小时有效）</Button>
          {invitation && (
            <Typography.Paragraph copyable style={{ marginBottom: 0 }}>
              邀请码：{invitation.code}
            </Typography.Paragraph>
          )}
        </Space>
      </Card>
    </div>
  )
}
