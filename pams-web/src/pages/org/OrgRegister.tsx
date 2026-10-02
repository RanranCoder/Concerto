import { useState } from 'react'
import { App, Button, Checkbox, Form, Input, Result, Select, Space, Steps, Typography } from 'antd'
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons'
import { Link } from 'react-router-dom'
import { registerOrg } from '@/api/org'
import type { OrgRegisterPayload, OrgUnitInput } from '@/api/org'

const ORG_TYPES = [
  { value: 'PARTY', label: '党建办公室' },
  { value: 'STUDENT_UNION', label: '学生会' },
  { value: 'CLUB', label: '社团' },
  { value: 'VOLUNTEER', label: '青年志愿者协会' },
  { value: 'SCIENCE', label: '科技协会' },
  { value: 'OTHER', label: '其他' },
]

const UNIT_TYPES = ['部门', '中心', '小组', '分队']

const PACKS = [
  { value: 'party', label: '党建包（党务台账）' },
  { value: 'volunteer', label: '志愿包（服务时长）' },
  { value: 'club', label: '社团包（社员/社团活动）' },
]

// ---------- 架构树编辑工具（按路径不可变更新） ----------
type Path = number[]

function updateAt(nodes: OrgUnitInput[], path: Path, fn: (n: OrgUnitInput) => OrgUnitInput): OrgUnitInput[] {
  const [head, ...rest] = path
  return nodes.map((n, i) => {
    if (i !== head) return n
    if (rest.length === 0) return fn(n)
    return { ...n, children: updateAt(n.children ?? [], rest, fn) }
  })
}

function removeAt(nodes: OrgUnitInput[], path: Path): OrgUnitInput[] {
  const [head, ...rest] = path
  if (rest.length === 0) return nodes.filter((_, i) => i !== head)
  return nodes.map((n, i) => (i === head ? { ...n, children: removeAt(n.children ?? [], rest) } : n))
}

function addChildAt(nodes: OrgUnitInput[], path: Path): OrgUnitInput[] {
  const [head, ...rest] = path
  if (rest.length === 0) {
    return nodes.map((n, i) =>
      i === head ? { ...n, children: [...(n.children ?? []), { name: '', unitType: '部门' }] } : n,
    )
  }
  return nodes.map((n, i) => (i === head ? { ...n, children: addChildAt(n.children ?? [], rest) } : n))
}

function UnitNodeEditor({
  nodes,
  path,
  onChange,
  onAddRoot,
}: {
  nodes: OrgUnitInput[]
  path: Path
  onChange: (nodes: OrgUnitInput[]) => void
  onAddRoot: () => void
}) {
  return (
    <div style={{ paddingLeft: path.length ? 20 : 0 }}>
      {nodes.map((node, i) => {
        const p = [...path, i]
        return (
          <div key={p.join('-')} style={{ marginBottom: 8 }}>
            <Space>
              <Input
                placeholder="节点名称，如 组织部"
                value={node.name}
                style={{ width: 200 }}
                onChange={(e) => onChange(updateAt(nodes, p, (n) => ({ ...n, name: e.target.value })))}
              />
              <Select
                value={node.unitType ?? '部门'}
                style={{ width: 90 }}
                options={UNIT_TYPES.map((t) => ({ value: t, label: t }))}
                onChange={(v) => onChange(updateAt(nodes, p, (n) => ({ ...n, unitType: v })))}
              />
              <Button
                size="small"
                icon={<PlusOutlined />}
                onClick={() => onChange(addChildAt(nodes, p))}
              >
                子节点
              </Button>
              <Button size="small" danger icon={<DeleteOutlined />} onClick={() => onChange(removeAt(nodes, p))} />
            </Space>
            {node.children && node.children.length > 0 && (
              <UnitNodeEditor nodes={node.children} path={p} onChange={onChange} onAddRoot={onAddRoot} />
            )}
          </div>
        )
      })}
      {path.length === 0 && (
        <Button size="small" icon={<PlusOutlined />} onClick={onAddRoot}>
          添加一级节点
        </Button>
      )}
    </div>
  )
}

export default function OrgRegister() {
  const { message } = App.useApp()
  const [form] = Form.useForm()
  const [step, setStep] = useState(0)
  const [units, setUnits] = useState<OrgUnitInput[]>([{ name: '', unitType: '部门' }])
  const [packs, setPacks] = useState<string[]>(['party'])
  const [submitting, setSubmitting] = useState(false)
  const [done, setDone] = useState<{ orgName: string; adminUsername: string } | null>(null)

  const next = async () => {
    if (step === 0) {
      await form.validateFields(['orgName', 'orgType'])
    }
    if (step === 2) {
      await form.validateFields(['username', 'password'])
    }
    setStep((s) => s + 1)
  }

  const submit = async () => {
    const values = await form.validateFields()
    const payload: OrgRegisterPayload = {
      org: {
        name: values.orgName,
        type: values.orgType,
        shortName: values.shortName,
        themeColor: values.themeColor,
      },
      admin: {
        username: values.username,
        password: values.password,
        realName: values.realName,
        phone: values.phone,
      },
      units: units.filter((u) => u.name && u.name.trim()),
      enabledPacks: packs,
    }
    if (payload.units.length === 0) {
      message.error('请至少填写一个组织架构节点')
      return
    }
    setSubmitting(true)
    try {
      const res = await registerOrg(payload)
      setDone({ orgName: res.orgName, adminUsername: res.adminUsername })
    } catch {
      /* http 拦截已提示 */
    } finally {
      setSubmitting(false)
    }
  }

  if (done) {
    return (
      <div style={{ padding: 48, maxWidth: 640, margin: '0 auto' }}>
        <Result
          status="success"
          title="组织注册成功"
          subTitle={`「${done.orgName}」已创建，管理员账号：${done.adminUsername}`}
          extra={<Link to="/login"><Button type="primary">去登录</Button></Link>}
        />
      </div>
    )
  }

  return (
    <div style={{ padding: 48, maxWidth: 720, margin: '0 auto' }}>
      <Typography.Title level={3}>注册组织</Typography.Title>
      <Steps
        current={step}
        style={{ marginBottom: 24 }}
        items={[{ title: '组织信息' }, { title: '组织架构' }, { title: '管理员' }, { title: '领域包' }]}
      />
      <Form form={form} layout="vertical" initialValues={{ orgType: 'STUDENT_UNION' }}>
        <div style={{ display: step === 0 ? 'block' : 'none' }}>
          <Form.Item name="orgName" label="组织名称" rules={[{ required: true, message: '请输入组织名称' }]}>
            <Input placeholder="如 信息与智能工程学院学生会" />
          </Form.Item>
          <Form.Item name="orgType" label="组织类型" rules={[{ required: true }]}>
            <Select options={ORG_TYPES} />
          </Form.Item>
          <Form.Item name="shortName" label="简称">
            <Input placeholder="如 信工学生会" />
          </Form.Item>
          <Form.Item name="themeColor" label="主题色（可选）">
            <Input placeholder="#2F6FED" />
          </Form.Item>
        </div>

        <div style={{ display: step === 1 ? 'block' : 'none' }}>
          <Typography.Paragraph type="secondary">
            填写本组织的架构（部门 / 中心 / 小组 / 分队，可多层嵌套）。
          </Typography.Paragraph>
          <UnitNodeEditor nodes={units} path={[]} onChange={setUnits} onAddRoot={() => setUnits([...units, { name: '', unitType: '部门' }])} />
        </div>

        <div style={{ display: step === 2 ? 'block' : 'none' }}>
          <Form.Item name="username" label="管理员用户名" rules={[{ required: true, message: '请输入用户名' }]}>
            <Input placeholder="登录账号" />
          </Form.Item>
          <Form.Item name="password" label="管理员密码" rules={[{ required: true, min: 6, message: '至少 6 位' }]}>
            <Input.Password />
          </Form.Item>
          <Form.Item name="realName" label="管理员姓名">
            <Input />
          </Form.Item>
          <Form.Item name="phone" label="联系电话（可选）">
            <Input />
          </Form.Item>
        </div>

        <div style={{ display: step === 3 ? 'block' : 'none' }}>
          <Typography.Paragraph type="secondary">选择本组织启用的领域包（可稍后在设置中调整）。</Typography.Paragraph>
          <Checkbox.Group options={PACKS} value={packs} onChange={(v) => setPacks(v as string[])} />
        </div>
      </Form>

      <Space style={{ marginTop: 24 }}>
        {step > 0 && <Button onClick={() => setStep((s) => s - 1)}>上一步</Button>}
        {step < 3 && <Button type="primary" onClick={next}>下一步</Button>}
        {step === 3 && <Button type="primary" loading={submitting} onClick={submit}>提交注册</Button>}
        <Link to="/login"><Button type="link">已有账号？去登录</Button></Link>
      </Space>
    </div>
  )
}
