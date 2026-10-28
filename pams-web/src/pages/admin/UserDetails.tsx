import PermissionExplanation from './PermissionExplanation'
import { useEffect, useState } from 'react'
import { Alert, Button, Empty, Space, Spin } from 'antd'
import FormSurface from '@/components/form/FormSurface'
import { InfoList, LoadError, StatusTag, errorText } from '@/components/admin/AdminSurface'
import { effectivePermissions, roleCatalog, type EffectivePermissions, type CatalogPermission } from '@/api/permission'
import type { UserVO } from '@/api/user'
import { useAuthStore } from '@/stores/auth'
import { managementLabel, userStatus } from './UserList'

export default function UserDetails({user, canEdit, canManage, canChangeStatus, onClose, onEdit, onStatus, onDelete}: {user: UserVO; canEdit: boolean; canManage: boolean; canChangeStatus: boolean; onClose: () => void; onEdit: () => void; onStatus: () => void; onDelete: () => void}) {
  const canReadPermissions = useAuthStore(s => s.user?.managementCapabilities.includes('role:view')) ?? false
  const [permissions, setPermissions] = useState<EffectivePermissions>(), [catalog, setCatalog] = useState<CatalogPermission[]>([])
  const [loading, setLoading] = useState(false), [error, setError] = useState(''), [retry, setRetry] = useState(0)
  useEffect(() => {
    if (!canReadPermissions) return
    let active = true; setPermissions(undefined); setLoading(true); setError('')
    effectivePermissions(user.id).then(next => {if (active) setPermissions(next)}).catch(err => {if (active) setError(errorText(err, '有效权限读取失败，请重试。'))}).finally(() => {if (active) setLoading(false)})
    roleCatalog().then(list => {if (active) setCatalog(list)}).catch(() => {})
    return () => {active = false}
  }, [user.id, canReadPermissions, retry])
  const names: Record<string, string> = {'user:view': '查看账号', 'user:manage': '管理账号', 'role:view': '查看角色与授权', 'role:manage': '维护角色', 'role:grant': '发布授权', 'role:assign': '分配业务角色', 'org:manage': '管理组织', 'org:structure:manage': '管理组织架构', 'org:invite': '管理邀请', 'member:import_account': '花名册开户'}
  return <FormSurface mode="drawer" title={user.realName || user.username} description={'账号详情 · ' + user.username} open onCancel={onClose} footer={<div className="admin-detail-footer">{canManage && <Button danger type="text" onClick={onDelete}>删除账号</Button>}<Space><Button onClick={onClose}>关闭</Button>{canChangeStatus && <Button onClick={onStatus}>{user.status === 1 ? '停用账号' : '启用账号'}</Button>}{canEdit && <Button type="primary" onClick={onEdit}>编辑资料</Button>}</Space></div>}>
    <InfoList items={[{label: '账号状态', value: userStatus(user)}, {label: '学号', value: user.studentNo || '—'}, {label: '联系电话', value: user.phone || '—'}, {label: '所属部门', value: user.deptName || '未分配部门'}, {label: '业务主角色', value: <StatusTag tone="brand">{user.roleName || '未分配角色'}</StatusTag>}, {label: '管理身份', value: managementLabel(user.managementSource)}]} />
    {user.pendingActivation && <Alert type="warning" showIcon message="此账号尚未完成首次密码设置，请由开户经办人核对交付状态。此处不展示明文凭证。" />}
    <h3 className="admin-section-title">实际访问权限</h3>
    {!canReadPermissions ? <p className="admin-note">当前账号无权读取权限明细，可向有权管理员核对此用户的已发布授权。</p> : error ? <LoadError message={error} retry={() => setRetry(v => v + 1)} /> : <Spin spinning={loading}>
      {permissions && <><PermissionExplanation userId={user.id} /><p className="admin-note">以下业务权限来自当前已发布角色。部门归属不会自动授予管理能力。</p><ul className="admin-permission-list">{permissions.businessPermissions.map(code => <li key={code}>{catalog.find(p => p.code === code)?.name || code}</li>)}</ul>{!permissions.businessPermissions.length && <Empty description="未授予业务操作权限" />}
        <h3 className="admin-section-title">治理能力来源 · {managementLabel(permissions.managementSource)}</h3><ul className="admin-permission-list">{permissions.managementCapabilities.map(code => <li key={code}>{names[code] || code}</li>)}</ul>{!permissions.managementCapabilities.length && <p className="admin-note">未授予管理能力。</p>}</>}
    </Spin>}
  </FormSurface>
}
