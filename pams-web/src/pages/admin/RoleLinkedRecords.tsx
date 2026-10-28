import { useEffect, useState } from 'react'
import { Alert, Button, Table, Tag } from 'antd'
import {
  roleMembers,
  roleHistory,
  roleReferences,
  type RoleMember,
  type RoleHistory,
  type RoleReference,
} from '@/api/permission'

export default function RoleLinkedRecords({
  roleId,
  mode,
  onEffective,
  onHistory,
}: {
  roleId: number
  mode: 'members' | 'history' | 'references'
  onEffective?: (id: number) => void
  onHistory?: (grants: string) => void
}) {
  const [items, setItems] = useState<
    (RoleMember & RoleHistory & RoleReference)[]
  >([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(false)
  const [retry, setRetry] = useState(0)
  useEffect(() => {
    setPage(1)
  }, [roleId, mode])
  useEffect(() => {
    let alive = true
    setLoading(true)
    setError(false)
    const load =
      mode === 'members'
        ? roleMembers
        : mode === 'history'
          ? roleHistory
          : roleReferences
    load(roleId, page)
      .then((result) => {
        if (alive) {
          setItems(result.items as (RoleMember & RoleHistory & RoleReference)[])
          setTotal(result.total)
        }
      })
      .catch(() => {
        if (alive) setError(true)
      })
      .finally(() => {
        if (alive) setLoading(false)
      })
    return () => {
      alive = false
    }
  }, [roleId, mode, page, retry])
  return (
    <>
      {error && (
        <Alert
          type="error"
          message="关联记录读取失败"
          action={<Button onClick={() => setRetry((v) => v + 1)}>重试</Button>}
        />
      )}
      {mode === 'references' && (
        <p>
          有效账号、岗位默认角色和未到期邀请会阻止停用或归档。请按下列名称和编号在账号管理或组织设置中替换角色、调整岗位、撤销邀请，再重新预览。删除未发布角色还需解除停用账号的关联。
        </p>
      )}
      <Table
        rowKey={(r) => `${r.kind ?? mode}-${r.id}`}
        size="small"
        loading={loading}
        dataSource={items}
        scroll={{ x: 560 }}
        pagination={{
          current: page,
          pageSize: 20,
          total,
          onChange: setPage,
          showSizeChanger: false,
        }}
        columns={
          mode === 'members'
            ? [
                { title: '姓名', dataIndex: 'realName' },
                { title: '账号', dataIndex: 'username' },
                { title: '部门', render: (_, r) => r.deptName || '未分配' },
                {
                  title: '状态',
                  render: (_, r) => (r.status === 1 ? '启用' : '停用'),
                },
                {
                  title: '管理身份',
                  render: (_, r) =>
                    ({
                      OWNER: '所有者',
                      DELEGATED: '管理员',
                      NONE: '普通账号',
                    })[r.managementSource],
                },
                {
                  title: '操作',
                  render: (_, r) => (
                    <Button type="link" onClick={() => onEffective?.(r.id)}>
                      查看有效权限
                    </Button>
                  ),
                },
              ]
            : mode === 'history'
              ? [
                  { title: '发布版本', dataIndex: 'id' },
                  { title: '发布时间', dataIndex: 'publishedAt' },
                  {
                    title: '操作人',
                    render: (_, r) => r.actorName || '历史记录',
                  },
                  { title: '原因', render: (_, r) => r.reason || '未填写' },
                  {
                    title: '目录版本',
                    render: (_, r) => r.catalogVersion ?? '历史',
                  },
                  {
                    title: '权限配置',
                    render: (_, r) => (
                      <Button type="link" onClick={() => onHistory?.(r.grants)}>
                        查看 {JSON.parse(r.grants).length} 项及差异
                      </Button>
                    ),
                  },
                ]
              : [
                  {
                    title: '类型',
                    render: (_, r) =>
                      ({
                        USER: '账号',
                        POSITION: '岗位',
                        INVITATION: '有效邀请',
                      })[r.kind],
                  },
                  { title: '编号', dataIndex: 'id' },
                  { title: '名称', dataIndex: 'name' },
                  { title: '说明', dataIndex: 'detail' },
                  {
                    title: '处理要求',
                    render: (_, r) => (
                      <Tag color={r.blocking ? 'warning' : 'default'}>
                        {r.blocking ? '须解除引用' : '仅删除时须解除'}
                      </Tag>
                    ),
                  },
                ]
        }
      />
    </>
  )
}
