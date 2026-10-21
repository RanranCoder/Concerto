import { useRef, useState } from 'react'
import { Alert, Form, Input, InputNumber, Select } from 'antd'
import { isAxiosError } from 'axios'
import FormSurface from '@/components/form/FormSurface'
import { createUnit, updateUnit, updateOrg, type OrgAccount, type OrgInfo, type OrgUnitNode } from '@/api/org'
import { UNIT_TYPES } from '@/utils/orgTree'
import { DEFAULT_PRIMARY_COLOR } from '@/stores/org'

export function orgError(error: unknown, fallback: string) {
  return isAxiosError(error) ? error.response?.data?.message || fallback : fallback
}
export interface UnitEditorTarget { kind: 'create' | 'edit' | 'leader'; node?: OrgUnitNode; parentId: number | null }
interface UnitValues { name: string; unitType: string; sortOrder: number; leaderUserId?: number | null }

export function UnitEditor({ target, parentName, allNodes, accounts, onCancel, onSaved }: {
  target: UnitEditorTarget; parentName: string; allNodes: OrgUnitNode[]; accounts: OrgAccount[]
  onCancel: () => void; onSaved: (id: number) => Promise<void>
}) {
  const [form] = Form.useForm<UnitValues>()
  const [saving, setSaving] = useState(false), [error, setError] = useState('')
  const busy = useRef(false), node = target.node
  const leaderOnly = target.kind === 'leader', creating = target.kind === 'create'
  const initial = { name: node?.name, unitType: node?.unitType ?? (target.parentId ? '小组' : '部门'),
    sortOrder: node?.sortOrder ?? allNodes.filter(n => n.parentId === target.parentId).length,
    leaderUserId: node?.leaderUserId ?? undefined }
  const choices = accounts.filter(a => a.status === 1 || a.id === node?.leaderUserId).map(a => ({
    value: a.id, label: `${a.realName || a.username} / ${a.username}${a.status !== 1 ? '（已停用）' : ''}`, disabled: a.status !== 1,
  }))
  if (node?.leaderUserId && !choices.some(choice => choice.value === node.leaderUserId)) {
    choices.push({ value: node.leaderUserId, label: '原负责人账号已不可用，请重新选择', disabled: true })
  }
  async function save() {
    if (busy.current) return
    busy.current = true
    let values: UnitValues
    try { values = await form.validateFields() } catch { busy.current = false; return }
    setSaving(true); setError('')
    try {
      const payload = { name: leaderOnly ? node!.name : values.name.trim(), unitType: leaderOnly ? node!.unitType : values.unitType,
        sortOrder: leaderOnly ? node!.sortOrder ?? 0 : values.sortOrder, leaderUserId: values.leaderUserId ?? null }
      const id = creating ? await createUnit({ ...payload, parentId: target.parentId }) : node!.id
      if (!creating) await updateUnit(id, payload)
      await onSaved(id)
    } catch (err) { setError(orgError(err, '保存失败，请检查后重试，已填写的信息会保留。')) }
    finally { busy.current = false; setSaving(false) }
  }
  return <FormSurface open title={leaderOnly ? `设置${node!.name}负责人` : creating ? target.parentId ? '添加下级节点' : '添加一级节点' : '编辑节点'}
    description={leaderOnly ? '明确组织分工，账号角色与管理员身份分别维护。' : `所属上级：${parentName}`}
    onCancel={saving ? undefined : onCancel} onOk={save} confirmLoading={saving} closable={!saving} keyboard={!saving}
    cancelButtonProps={{ disabled: saving }} okText={leaderOnly ? '保存负责人' : creating ? '添加节点' : '保存修改'}>
    {error && <Alert type="error" showIcon message={error} />}
    <Form form={form} layout="vertical" initialValues={initial} disabled={saving} onFinish={save}>
      {!leaderOnly && <><Form.Item name="name" label="节点名称" rules={[{ required: true, whitespace: true, message: '请输入节点名称' }, { max: 50, message: '节点名称请控制在 50 字以内' }, {
        validator: (_, value: string) => !allNodes.some(n => n.id !== node?.id && n.name.toLocaleLowerCase() === value?.trim().toLocaleLowerCase())
          ? Promise.resolve() : Promise.reject(new Error('当前组织已存在同名节点，请使用不同的名称')),
      }]}><Input placeholder="如：组织部、培训组" maxLength={50} autoFocus /></Form.Item>
      <div className="org-form-pair"><Form.Item name="unitType" label="节点类型" rules={[{ required: true, message: '请选择节点类型' }]}><Select options={UNIT_TYPES.map(value => ({ value, label: value }))} /></Form.Item>
      <Form.Item name="sortOrder" label="显示排序" rules={[{ required: true, message: '请输入显示排序' }]}><InputNumber min={0} max={9999} precision={0} /></Form.Item></div></>}
      <Form.Item name="leaderUserId" label="节点负责人（选填）" extra="从当前组织正常账号中选择；设置负责人不会自动改变账号角色。"><Select allowClear showSearch optionFilterProp="label" options={choices} placeholder="暂不设置" notFoundContent="暂无可选择的正常账号" /></Form.Item>
      {!creating && !leaderOnly && <p className="org-form-hint">编辑节点信息将保留当前的上下级关系。</p>}
      <button type="submit" hidden aria-hidden="true" tabIndex={-1} />
    </Form>
  </FormSurface>
}

export function ProfileEditor({ org, onCancel, onSaved }: { org: OrgInfo; onCancel: () => void; onSaved: () => Promise<void> }) {
  const [form] = Form.useForm<{ name: string; shortName?: string; themeColor: string }>()
  const [saving, setSaving] = useState(false), [error, setError] = useState('')
  const busy = useRef(false)
  let color = DEFAULT_PRIMARY_COLOR
  try { color = JSON.parse(org.themeJson || '{}').primaryColor || color } catch { /* default */ }
  async function save() {
    if (busy.current) return
    busy.current = true
    let values: { name: string; shortName?: string; themeColor: string }
    try { values = await form.validateFields() } catch { busy.current = false; return }
    setSaving(true); setError('')
    try {
      await updateOrg({ name: values.name.trim(), shortName: values.shortName?.trim() || '', themeColor: values.themeColor, logoUrl: org.logoUrl ?? undefined })
      await onSaved()
    } catch (err) { setError(orgError(err, '组织资料保存失败，请检查后重试。')) }
    finally { busy.current = false; setSaving(false) }
  }
  return <FormSurface open title="编辑组织资料" description="名称、简称与主题色同步用于登录页的组织展示。"
    onCancel={saving ? undefined : onCancel} onOk={save} confirmLoading={saving} closable={!saving} keyboard={!saving} cancelButtonProps={{ disabled: saving }} okText="保存修改">
    {error && <Alert type="error" showIcon message={error} />}
    <Form form={form} layout="vertical" initialValues={{ name: org.name, shortName: org.shortName, themeColor: color }} disabled={saving} onFinish={save}>
      <Form.Item name="name" label="组织全称" rules={[{ required: true, whitespace: true, message: '请输入组织全称' }, { max: 100, message: '组织全称请控制在 100 字以内' }]}><Input maxLength={100} /></Form.Item>
      <Form.Item name="shortName" label="组织简称（选填）" rules={[{ max: 50, message: '简称请控制在 50 字以内' }]}><Input maxLength={50} /></Form.Item>
      <Form.Item name="themeColor" label="组织主题色" extra={`输入六位十六进制色值，例如 ${DEFAULT_PRIMARY_COLOR}。`} rules={[{ required: true, message: '请输入主题色' }, { pattern: /^#[0-9a-f]{6}$/i, message: '请使用 #RRGGBB 格式' }]}><Input placeholder={DEFAULT_PRIMARY_COLOR} maxLength={7} /></Form.Item>
      <button type="submit" hidden aria-hidden="true" tabIndex={-1} />
    </Form>
  </FormSurface>
}
