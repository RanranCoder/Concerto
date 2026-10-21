import { useEffect, useRef, useState } from 'react'
import { Alert, App, Form, Input, Select } from 'antd'
import FormSurface, { FormSection } from '@/components/form/FormSurface'
import { InfoList, LoadError, StatusTag, errorText } from '@/components/admin/AdminSurface'
import { assignableRoles, previewRoleAssignment, applyAuthorizationChange, roleCatalog, type CatalogPermission, type ChangePreview, type ManagedRole } from '@/api/permission'
import { createUser, updateUser, type UserSave, type UserVO } from '@/api/user'
import type { DeptVO } from '@/api/dept'
import { useAuthStore } from '@/stores/auth'

export default function UserEditor({user, depts, onClose, onSaved}: {user?: UserVO; depts: DeptVO[]; onClose: () => void; onSaved: () => void}) {
  const {message, modal} = App.useApp(), [form] = Form.useForm<UserSave>()
  const canPreviewRole = useAuthStore(s => s.user?.managementCapabilities.includes('role:view')) ?? false
  const [roles, setRoles] = useState<ManagedRole[]>([]), [catalog, setCatalog] = useState<CatalogPermission[]>([])
  const [metaLoading, setMetaLoading] = useState(true), [metaError, setMetaError] = useState(''), [retry, setRetry] = useState(0)
  const [saving, setSaving] = useState(false), [error, setError] = useState(''), [preview, setPreview] = useState<{data: UserSave; change: ChangePreview}>()
  const [appliedRole, setAppliedRole] = useState<number>(), running = useRef(false)
  useEffect(() => {
    let active = true; setMetaLoading(true); setMetaError('')
    assignableRoles().then(list => {if (active) setRoles(list)}).catch(err => {if (active) setMetaError(errorText(err, '可分配角色加载失败，请重试。'))}).finally(() => {if (active) setMetaLoading(false)})
    if (canPreviewRole) roleCatalog().then(list => {if (active) setCatalog(list)}).catch(() => {/* Permission codes still render if labels are unavailable. */})
    return () => {active = false}
  }, [retry, canPreviewRole])
  const close = () => {if (saving) return; if (!form.isFieldsTouched()) return onClose(); modal.confirm({title: '放弃未保存的账号修改？', okText: '放弃修改', cancelText: '继续编辑', onOk: onClose})}
  const persist = async (data: UserSave, change?: ChangePreview) => {
    if (running.current) return
    running.current = true; setSaving(true); setError(''); let assigned = false
    try {
      if (change) {await applyAuthorizationChange(change.id); assigned = true; setAppliedRole(data.roleId)}
      if (user) await updateUser(user.id, data); else await createUser(data)
      message.success(user ? '用户资料已保存' : '用户已创建'); form.resetFields(); onSaved()
    } catch (err) {if (assigned) setPreview(undefined); setError((assigned || appliedRole ? '角色已替换，账号资料尚未保存。可重试保存资料。' : '') + errorText(err, '保存失败，请重试。'))}
    finally {running.current = false; setSaving(false)}
  }
  const prepare = async (values: UserSave) => {
    if (running.current || metaError) return
    const data: UserSave = {...values, username: user?.username || values.username.trim(), realName: values.realName.trim(), studentNo: values.studentNo?.trim() || null, phone: values.phone?.trim() || null, deptId: values.deptId ?? null}
    if (user && data.roleId !== (appliedRole || user.roleId)) {
      running.current = true; setSaving(true); setError('')
      try {const change = await previewRoleAssignment(user.id, data.roleId); setPreview({data, change})}
      catch (err) {setError(errorText(err, '角色预览失败，请重试。'))} finally {running.current = false; setSaving(false)}
    } else await persist(data)
  }
  const permissionName = (code: string) => catalog.find(p => p.code === code)?.name || code
  const before = preview?.change.details.before || [], next = preview?.change.details.grants || []
  const added = next.filter(g => !before.some(old => old.permission === g.permission && old.scope === g.scope)), removed = before.filter(g => !next.some(n => n.permission === g.permission && n.scope === g.scope))
  const roleOptions = roles.map(role => ({value: role.id, label: role.name}))
  if (user?.roleId && !roleOptions.some(r => r.value === user.roleId)) roleOptions.push({value: user.roleId, label: user.roleName || '当前角色'})
  return <>
    <FormSurface mode="drawer" title={user ? '编辑用户' : '新增用户'} description="账号资料与业务主角色分开填写，管理身份在角色与权限中维护。" open onCancel={close} onOk={() => form.submit()} okText={user ? '预览并保存' : '创建用户'} confirmLoading={saving} okButtonProps={{disabled: metaLoading || !!metaError}} cancelButtonProps={{disabled: saving}} closable={!saving} keyboard={!saving}>
      {metaError && <LoadError message={metaError} retry={() => setRetry(v => v + 1)} />}{error && <Alert type="error" showIcon message={error} className="admin-form-error" />}
      <Form form={form} layout="vertical" preserve={false} initialValues={user ? {...user, deptId: user.deptId ?? undefined, status: user.status ?? 1} : {status: 1}} onFinish={prepare}>
        <FormSection title="账号资料"><Form.Item name="username" label="用户名" rules={[{required: true, message: '请输入用户名'}, {pattern: /^[a-zA-Z0-9_]+$/, message: '仅支持字母、数字和下划线'}]} extra={user ? '用户名创建后不能修改。' : '用于登录，使用未被占用的用户名。'}><Input disabled={!!user} maxLength={50} autoComplete="off" /></Form.Item>
          <Form.Item name="realName" label="姓名" rules={[{required: true, whitespace: true, message: '请输入姓名'}]}><Input maxLength={50} /></Form.Item><Form.Item name="studentNo" label="学号"><Input maxLength={20} /></Form.Item><Form.Item name="phone" label="联系电话"><Input maxLength={20} /></Form.Item></FormSection>
        <FormSection title="部门与业务角色"><Form.Item name="deptId" label="所属部门"><Select allowClear placeholder="未分配部门" options={depts.map(d => ({value: d.id, label: d.name}))} /></Form.Item><Form.Item name="roleId" label="业务主角色" rules={[{required: true, message: '请选择业务主角色'}]} extra={user && !canPreviewRole ? '当前账号可维护资料，角色替换需有权管理员处理。' : '一个账号对应一个主角色，替换前会预览权限变化。'}><Select loading={metaLoading} disabled={!!user && !canPreviewRole} options={roleOptions} /></Form.Item></FormSection>
        <FormSection title="登录与状态">{!user && <Form.Item name="password" label="初始密码" rules={[{required: true, min: 8, message: '请输入至少 8 位初始密码'}]}><Input.Password autoComplete="new-password" maxLength={100} /></Form.Item>}<Form.Item name="status" label="账号状态"><Select options={[{value: 1, label: '启用'}, {value: 0, label: '停用'}]} /></Form.Item></FormSection>
      </Form>
    </FormSurface>
    <FormSurface title="确认替换业务主角色" description="请核对角色与权限变化。" open={!!preview} onCancel={() => {if (!saving) setPreview(undefined)}} onOk={() => preview && persist(preview.data, preview.change)} okText="确认替换并保存" cancelText="返回编辑" confirmLoading={saving} keyboard={!saving} closable={!saving} cancelButtonProps={{disabled: saving}}>
      {error && <Alert type="error" message={error} className="admin-form-error" />}
      <div className="admin-role-diff"><div><small>原角色</small><strong>{roles.find(r => r.id === (appliedRole || user?.roleId))?.name || user?.roleName}</strong></div><span>→</span><div><small>新角色</small><strong>{roles.find(r => r.id === preview?.data.roleId)?.name}</strong></div></div>
      <InfoList items={[{label: '用户', value: user?.realName}, {label: '所属部门', value: depts.find(d => d.id === preview?.data.deptId)?.name || '未分配部门'}, {label: '管理身份', value: '保持不变'}]} />
      <h3 className="admin-section-title">权限变化</h3><ul className="admin-result-list">{added.map(g => <li key={'add' + g.permission}><span>{permissionName(g.permission)}</span><StatusTag tone="success">新增</StatusTag></li>)}{removed.map(g => <li key={'remove' + g.permission}><span>{permissionName(g.permission)}</span><StatusTag tone="warning">收回</StatusTag></li>)}{!added.length && !removed.length && <li>业务操作范围无变化</li>}</ul>
      <Alert type="warning" showIcon message="保存后采用新角色的业务授权，原角色权限不再保留。预览有效期 5 分钟，授权变化后需重新预览。" />
    </FormSurface>
  </>
}
