import { useCallback, useEffect, useMemo, useState } from 'react'
import { Button, Empty, message, Popconfirm, Space, Spin, Tree } from 'antd'
import type { DataNode } from 'antd/es/tree'
import { ReloadOutlined, SaveOutlined } from '@ant-design/icons'
import GlassCard from '@/components/glass/GlassCard'
import PageHeader from '@/components/glass/PageHeader'
import {
  getRolePermissions,
  restoreDefaultPermissions,
  saveRolePermissions,
  type PermissionModuleNode,
  type RolePermissionVO,
} from '@/api/permission'

const ROLE_LABEL: Record<string, string> = {
  TEACHER: '指导老师',
  DIRECTOR: '主任',
  ORG_LEADER: '组织部长',
  SECRETARY_LEADER: '文秘部长',
  MEDIA_LEADER: '新媒体部长',
  TECH_LEADER: '青年科技部长',
  STAFF: '干事',
}

/**
 * 权限管理（PRD F07.2）
 * 左侧角色列表 + 右侧权限树（模块 → 功能点勾选），支持恢复默认
 */
export default function PermissionManage() {
  const [roles, setRoles] = useState<RolePermissionVO[]>([])
  const [tree, setTree] = useState<PermissionModuleNode[]>([])
  const [currentRole, setCurrentRole] = useState<string>('TEACHER')
  const [checkedCodes, setCheckedCodes] = useState<string[]>([])
  const [halfChecked, setHalfChecked] = useState<string[]>([])
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)

  const fetchData = useCallback(async () => {
    setLoading(true)
    try {
      const res = await getRolePermissions()
      setRoles(res.roles ?? [])
      setTree(res.tree ?? [])
    } catch {
      /* 拦截已提示 */
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    fetchData()
  }, [fetchData])

  // 切换角色 → 加载该角色已勾选权限
  useEffect(() => {
    const role = roles.find((r) => r.role === currentRole)
    setCheckedCodes(role?.permissions ?? [])
    setHalfChecked([])
  }, [currentRole, roles])

  const treeData: DataNode[] = useMemo(
    () =>
      tree.map((mod) => ({
        key: `mod-${mod.module}`,
        title: mod.module,
        children: mod.children.map((p) => ({ key: p.code, title: `${p.name}（${p.code}）` })),
      })),
    [tree],
  )

  const handleCheck = (checked: React.Key[] | { checked: React.Key[]; halfChecked: React.Key[] }) => {
    if (Array.isArray(checked)) {
      setCheckedCodes(checked as string[])
    } else {
      setCheckedCodes(checked.checked as string[])
      setHalfChecked(checked.halfChecked as string[])
    }
  }

  const handleSave = async () => {
    setSaving(true)
    try {
      // 只保存叶子权限码（模块节点 key 以 mod- 开头）
      const codes = checkedCodes.filter((c) => !c.startsWith('mod-'))
      await saveRolePermissions(currentRole, codes)
      message.success(`「${ROLE_LABEL[currentRole] ?? currentRole}」权限已保存`)
      fetchData()
    } catch {
      /* 拦截已提示 */
    } finally {
      setSaving(false)
    }
  }

  const handleRestore = async () => {
    try {
      await restoreDefaultPermissions()
      message.success('已恢复默认权限')
      fetchData()
    } catch {
      /* 拦截已提示 */
    }
  }

  return (
    <div>
      <PageHeader
        title="权限管理"
        description="按角色配置模块与功能权限（RBAC），变更刷新页面即生效"
        extra={
          <Popconfirm title="确认恢复为系统预设权限？自定义配置将被覆盖" onConfirm={handleRestore} okText="恢复" cancelText="取消">
            <Button icon={<ReloadOutlined />}>恢复默认</Button>
          </Popconfirm>
        }
      />

      <div style={{ display: 'flex', gap: 16 }}>
        {/* 左侧角色列表 */}
        <GlassCard style={{ padding: 12, width: 200, flexShrink: 0 }}>
          <div style={{ fontSize: 13, fontWeight: 600, padding: '4px 8px', marginBottom: 8, color: 'var(--color-text-secondary)' }}>
            角色列表
          </div>
          <Spin spinning={loading}>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
              {roles.map((r) => (
                <div
                  key={r.role}
                  onClick={() => setCurrentRole(r.role)}
                  style={{
                    padding: '8px 12px',
                    borderRadius: 8,
                    cursor: 'pointer',
                    fontSize: 13,
                    background: currentRole === r.role ? 'var(--color-primary)' : 'transparent',
                    color: currentRole === r.role ? '#fff' : 'var(--color-text)',
                    transition: 'all .15s',
                  }}
                >
                  {ROLE_LABEL[r.role] ?? r.role}
                  <span style={{ float: 'right', fontSize: 11, opacity: 0.7 }}>{r.permissions.length} 项</span>
                </div>
              ))}
            </div>
          </Spin>
        </GlassCard>

        {/* 右侧权限树 */}
        <GlassCard style={{ padding: 16, flex: 1 }}>
          <Space style={{ width: '100%', justifyContent: 'space-between', marginBottom: 12 }}>
            <span style={{ fontWeight: 600 }}>
              {ROLE_LABEL[currentRole] ?? currentRole} 的权限
              <span style={{ marginLeft: 8, fontSize: 12, fontWeight: 400, color: 'var(--color-text-secondary)' }}>
                勾选 {checkedCodes.filter((c) => !c.startsWith('mod-')).length} 项
              </span>
            </span>
            <Button type="primary" icon={<SaveOutlined />} loading={saving} onClick={handleSave}>
              保存
            </Button>
          </Space>
          <Spin spinning={loading}>
            {treeData.length === 0 ? (
              <Empty description="暂无权限配置" />
            ) : (
              <Tree
                checkable
                defaultExpandAll
                treeData={treeData}
                checkedKeys={{ checked: checkedCodes, halfChecked }}
                onCheck={handleCheck}
                selectable={false}
              />
            )}
          </Spin>
        </GlassCard>
      </div>
    </div>
  )
}
