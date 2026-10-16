import { useEffect, useRef, useState } from 'react'
import { Button, Checkbox, Form, Input } from 'antd'
import { isAxiosError } from 'axios'
import { ApartmentOutlined, ArrowLeftOutlined, CheckOutlined, PlusOutlined, SafetyCertificateOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import { registerOrg } from '@/api/org'
import type { OrgRegisterPayload, OrgUnitInput } from '@/api/org'
import AuthLayout from '@/components/auth/AuthLayout'
import OrgUnitEditor from '@/components/auth/OrgUnitEditor'
import { DEFAULT_PRIMARY_COLOR } from '@/stores/org'
import { countUnits, removeUnitAt, trimUnits, updateUnitAt, validateUnits } from '@/utils/orgTree'

const ORG_TYPES = [
  { value: 'PARTY', label: '党建办公室', detail: '党务工作与组织建设' },
  { value: 'STUDENT_UNION', label: '学生会', detail: '学生服务与校园活动' },
  { value: 'CLUB', label: '社团', detail: '共同兴趣与社团事务' },
  { value: 'VOLUNTEER', label: '青年志愿者协会', detail: '志愿服务与团队协作' },
  { value: 'SCIENCE', label: '科技协会', detail: '科创项目与学术交流' },
  { value: 'OTHER', label: '其他组织', detail: '按你的团队方式协作' },
]
const COLORS = [DEFAULT_PRIMARY_COLOR, '#087F8C', '#6750A4', '#C44C35', '#B23363']
const PACKS = [
  { value: 'party', label: '党建包', detail: '党务台账与党员管理' },
  { value: 'volunteer', label: '志愿包', detail: '志愿服务时长记录' },
  { value: 'club', label: '社团包', detail: '社员与社团活动管理' },
]
const STEPS = [
  { title: '组织信息', detail: '名称、类型与专属颜色', heading: '先认识一下你的组织', description: '这些信息将用于组织展示，之后也可以在设置中修改。' },
  { title: '组织架构', detail: '搭建部门与下级小组', heading: '让每一份分工，都有位置', description: '至少添加一个部门或小组。点击「子节点」可继续添加下级。' },
  { title: '管理员账号', detail: '设置第一个登录账号', heading: '为组织设置一位管理员', description: '这个账号将负责组织设置、成员邀请与权限管理，请妥善保管。' },
  { title: '确认并创建', detail: '核对信息，选择功能', heading: '准备好，开启团队协作', description: '核对以下信息，再选择组织需要的扩展功能。' },
]
interface RegisterValues {
  orgName: string
  orgType: string
  shortName?: string
  themeColor?: string
  username: string
  password: string
  confirmPassword: string
  realName?: string
  phone?: string
}

function OrgTypePicker({ value, onChange }: { value?: string; onChange?: (value: string) => void }) {
  return <div className="org-type-options" role="group" aria-label="组织类型">
    {ORG_TYPES.map((type) => <button key={type.value} type="button" aria-pressed={value === type.value}
      className={value === type.value ? 'selected' : ''} onClick={() => onChange?.(type.value)}>
      <span>{type.label}{value === type.value && <CheckOutlined />}</span><small>{type.detail}</small>
    </button>)}
  </div>
}

function ThemeColorPicker({ value = DEFAULT_PRIMARY_COLOR, onChange }: { value?: string; onChange?: (value: string) => void }) {
  return <div className="org-color-picker">
    <div className="org-color-swatches" role="group" aria-label="推荐主题色">
      {COLORS.map((color, i) => <button key={color} type="button" style={{ background: color }} aria-label={['协奏蓝', '湖水青', '鸢尾紫', '朱砂红', '玫瑰红'][i]}
        aria-pressed={value.toLowerCase() === color.toLowerCase()} onClick={() => onChange?.(color)}>{value.toLowerCase() === color.toLowerCase() && <CheckOutlined />}</button>)}
    </div>
    <Input aria-label="自定义主题色" value={value} placeholder="#1668DC" onChange={(e) => onChange?.(e.target.value)} />
  </div>
}

function ReviewTree({ nodes }: { nodes: OrgUnitInput[] }) {
  return <ul className="review-tree">{nodes.map((node, i) => <li key={i}><span>{node.name}<small>{node.unitType}</small></span>
    {!!node.children?.length && <ReviewTree nodes={node.children} />}</li>)}</ul>
}

export default function OrgRegister() {
  const navigate = useNavigate()
  const [form] = Form.useForm<RegisterValues>()
  const values = Form.useWatch([], form) as Partial<RegisterValues> | undefined
  const [step, setStep] = useState(0)
  const [units, setUnits] = useState<OrgUnitInput[]>([{ name: '', unitType: '部门' }])
  const [packs, setPacks] = useState<string[]>([])
  const [treeError, setTreeError] = useState<string | null>(null)
  const [error, setError] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const busy = useRef(false)
  const advancing = useRef(false)
  const [done, setDone] = useState<{ orgId: number; orgName: string; adminUsername: string } | null>(null)
  const titleRef = useRef<HTMLHeadingElement>(null)
  const brand = /^#[0-9a-f]{6}$/i.test(values?.themeColor ?? '') ? values!.themeColor! : DEFAULT_PRIMARY_COLOR
  const unitCount = countUnits(units)

  useEffect(() => {
    titleRef.current?.focus({ preventScroll: true })
    titleRef.current?.scrollIntoView({ block: 'nearest' })
  }, [step, done])

  const next = async () => {
    if (submitting || advancing.current) return
    advancing.current = true
    setError('')
    try {
      if (step === 0) await form.validateFields(['orgName', 'orgType', 'shortName', 'themeColor'])
      if (step === 1) {
        const problem = validateUnits(units)
        setTreeError(problem)
        if (problem) return
      }
      if (step === 2) await form.validateFields(['username', 'password', 'confirmPassword', 'realName', 'phone'])
      setStep((current) => Math.min(current + 1, 3))
    } catch { /* 字段旁显示校验信息，保持当前步骤 */ }
    finally { advancing.current = false }
  }

  const submit = async () => {
    if (busy.current) return
    busy.current = true
    setError('')
    let data: RegisterValues
    try { data = await form.validateFields() } catch {
      const hasOrgError = form.getFieldsError(['orgName', 'orgType', 'shortName', 'themeColor']).some((field) => field.errors.length)
      setStep(hasOrgError ? 0 : 2)
      busy.current = false
      return
    }
    const problem = validateUnits(units)
    if (problem) { setTreeError(problem); setStep(1); busy.current = false; return }
    const payload: OrgRegisterPayload = {
      org: { name: data.orgName.trim(), type: data.orgType, shortName: data.shortName?.trim(), themeColor: data.themeColor },
      admin: { username: data.username.trim(), password: data.password, realName: data.realName?.trim(), phone: data.phone?.trim() },
      units: trimUnits(units),
      enabledPacks: packs,
    }
    setSubmitting(true)
    try { setDone(await registerOrg(payload)) }
    catch (err) { setError(isAxiosError(err) ? err.response?.data?.message || '暂时无法创建，请检查网络后重试' : err instanceof Error ? err.message : '创建失败，请稍后重试') }
    finally { busy.current = false; setSubmitting(false) }
  }

  const editStep = (index: number) => { if (!submitting) { setError(''); setStep(index) } }

  return <AuthLayout register brand={brand} aside={<>
    <div className="register-intro"><h1>从一个组织，<br />到一群同行的人。</h1><p>搭好工作台，让协作从这里开始。</p></div>
    <nav className="register-steps" aria-label="创建组织步骤">
      <ol>{STEPS.map((item, index) => <li key={item.title} className={done || index < step ? 'complete' : index === step ? 'current' : ''}>
        <button type="button" disabled={!!done || index >= step || submitting} aria-current={!done && index === step ? 'step' : undefined} onClick={() => editStep(index)}>
          <span className="step-number">{done || index < step ? <CheckOutlined /> : index + 1}</span>
          <span><strong>{item.title}</strong><small>{item.detail}</small></span>
        </button>
      </li>)}</ol>
    </nav>
    <div className="org-live-preview"><span className="org-preview-mark"><ApartmentOutlined /></span><div><strong>{values?.shortName || values?.orgName || '你的组织'}</strong><p>{unitCount} 个架构节点 · {done ? '已创建' : '正在搭建'}</p></div><span className="org-preview-dot" /></div>
  </>}>
    {done ? <div className="register-success">
      <span className="success-symbol"><CheckOutlined /></span>
      <h2 ref={titleRef} tabIndex={-1}>组织创建成功</h2>
      <p>「{done.orgName}」的工作台已经准备就绪。</p>
      <div className="success-account"><span>管理员账号</span><strong>{done.adminUsername}</strong><small>使用刚才设置的密码登录</small></div>
      <Button type="primary" className="onboarding-primary" block onClick={() => navigate('/login', { replace: true, state: done })}>使用管理员账号登录</Button>
      <p className="onboarding-hint">登录后即可邀请成员，开始安排组织工作。</p>
    </div> : <div className="register-form-wrap">
      <div className="register-step-counter" role="status">创建组织 <span>第 {step + 1} 步，共 4 步</span></div>
      <div className="onboarding-title"><h2 ref={titleRef} tabIndex={-1}>{STEPS[step].heading}</h2><p>{STEPS[step].description}</p></div>
      <Form form={form} name="org-register" layout="vertical" requiredMark={false} disabled={submitting}
        initialValues={{ orgType: 'STUDENT_UNION', themeColor: DEFAULT_PRIMARY_COLOR }}
        onKeyDown={(event) => {
          if (event.key === 'Enter' && event.target instanceof HTMLInputElement && step < 3) {
            event.preventDefault()
            void next()
          }
        }} onFinish={submit}>
        <div hidden={step !== 0}>
          <Form.Item name="orgName" label="组织名称" rules={[{ required: true, whitespace: true, message: '请输入组织名称' }, { max: 100, message: '组织名称请控制在 100 字以内' }]}>
            <Input placeholder="如：信息与智能工程学院学生会" maxLength={100} />
          </Form.Item>
          <Form.Item name="shortName" label="组织简称（选填）" rules={[{ max: 50, message: '简称请控制在 50 字以内' }]}>
            <Input placeholder="如：信工学生会，便于日常展示" maxLength={50} />
          </Form.Item>
          <Form.Item name="orgType" label="组织类型" rules={[{ required: true, message: '请选择组织类型' }]}><OrgTypePicker /></Form.Item>
          <Form.Item name="themeColor" label="组织主题色" rules={[{ pattern: /^#[0-9a-f]{6}$/i, message: '请输入 6 位十六进制颜色，如 #1668DC' }]}><ThemeColorPicker /></Form.Item>
          <p className="onboarding-hint">组织颜色会用于登录展示与工作台主题。</p>
        </div>
        <div hidden={step !== 1}>
          <div className="unit-editor-header"><span><ApartmentOutlined /> {values?.orgName || '组织架构'}</span><small>{unitCount} 个节点</small></div>
          <OrgUnitEditor nodes={units}
            onUpdate={(path, patch) => { setUnits((root) => updateUnitAt(root, path, (node) => ({ ...node, ...patch }))); setTreeError(null) }}
            onAdd={(path) => { setUnits((root) => updateUnitAt(root, path, (node) => ({ ...node, children: [...(node.children ?? []), { name: '', unitType: '小组' }] }))); setTreeError(null) }}
            onRemove={(path) => { setUnits((root) => removeUnitAt(root, path)); setTreeError(null) }} />
          {!units.length && <p className="unit-empty">还没有部门或小组，添加一个作为协作的起点。</p>}
          <Button type="dashed" block icon={<PlusOutlined />} className="add-root-unit" onClick={() => { setUnits((root) => [...root, { name: '', unitType: '部门' }]); setTreeError(null) }}>添加一级节点</Button>
          {treeError && <div className="onboarding-error" role="alert">{treeError}</div>}
          <p className="onboarding-hint">先搭建基本架构即可，创建后可继续调整并邀请成员。</p>
        </div>
        <div hidden={step !== 2}>
          <div className="onboarding-notice"><SafetyCertificateOutlined /> 管理员可管理组织成员与权限，请使用由负责人保管的账号。</div>
          <Form.Item name="username" label="管理员用户名" rules={[{ required: true, whitespace: true, message: '请输入管理员用户名' }, { max: 50, message: '用户名请控制在 50 字以内' }]}>
            <Input placeholder="设置用于登录的用户名" autoComplete="username" maxLength={50} />
          </Form.Item>
          <div className="register-field-pair">
            <Form.Item name="password" label="登录密码" rules={[{ required: true, min: 6, message: '请设置至少 6 位密码' }]}>
              <Input.Password placeholder="至少 6 位" autoComplete="new-password" />
            </Form.Item>
            <Form.Item name="confirmPassword" label="确认密码" dependencies={['password']} rules={[{ required: true, message: '请再次输入密码' }, ({ getFieldValue }) => ({
              validator(_, value) { return !value || value === getFieldValue('password') ? Promise.resolve() : Promise.reject(new Error('两次输入的密码不一致')) },
            })]}>
              <Input.Password placeholder="再次输入登录密码" autoComplete="new-password" />
            </Form.Item>
          </div>
          <div className="register-field-pair">
            <Form.Item name="realName" label="管理员姓名（选填）"><Input placeholder="如何称呼你" autoComplete="name" /></Form.Item>
            <Form.Item name="phone" label="联系电话（选填）"><Input placeholder="便于组织内部联络" autoComplete="tel" type="tel" /></Form.Item>
          </div>
        </div>
        <div hidden={step !== 3}>
          <div className="register-review">
            <div className="review-section"><div className="review-heading"><h3>组织信息</h3><button type="button" onClick={() => editStep(0)}>修改</button></div>
              <strong>{values?.orgName}</strong><p>{ORG_TYPES.find((type) => type.value === values?.orgType)?.label}{values?.shortName ? ' · ' + values.shortName : ''}</p>
            </div>
            <div className="review-section"><div className="review-heading"><h3>组织架构 <small>{unitCount} 个节点</small></h3><button type="button" onClick={() => editStep(1)}>修改</button></div><ReviewTree nodes={units} /></div>
            <div className="review-section"><div className="review-heading"><h3>管理员</h3><button type="button" onClick={() => editStep(2)}>修改</button></div><strong>{values?.username}</strong><p>{values?.realName || '未填写姓名'} · 密码已设置</p></div>
          </div>
          <div className="pack-heading"><h3>扩展功能（选填）</h3><p>按组织需要启用，也可以稍后在设置中调整。</p></div>
          <div className="pack-options">{PACKS.map((pack) => <Checkbox key={pack.value} checked={packs.includes(pack.value)} onChange={(event) => setPacks((current) => event.target.checked ? [...current, pack.value] : current.filter((value) => value !== pack.value))}>
            <strong>{pack.label}</strong><span>{pack.detail}</span>
          </Checkbox>)}</div>
        </div>
        {error && <div className="onboarding-error" role="alert">{error}<span>请检查后重试，已填写的信息会保留。</span></div>}
        <div className="register-footer">
          {step > 0 ? <Button aria-label="上一步" disabled={submitting} icon={<ArrowLeftOutlined />} onClick={() => editStep(step - 1)}>上一步</Button> : <span className="onboarding-hint">创建后，你将成为组织管理员</span>}
          <Button type="primary" htmlType="button" onClick={step === 3 ? submit : next} loading={submitting} className="onboarding-primary">{step === 3 ? '创建组织' : '下一步：' + STEPS[step + 1].title}</Button>
        </div>
      </Form>
    </div>}
  </AuthLayout>
}
