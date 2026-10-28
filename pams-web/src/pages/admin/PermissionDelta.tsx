import { Table, Tag } from 'antd'
import { grantChanges } from './permissionEditing'
import type { CatalogPermission, Grant } from '@/api/permission'

export default function PermissionDelta({
  before,
  after,
  catalog,
}: {
  before: Grant[]
  after: Grant[]
  catalog: CatalogPermission[]
}) {
  const rows = grantChanges(before, after)
  return (
    <Table
      rowKey="code"
      size="small"
      pagination={false}
      dataSource={rows}
      locale={{ emptyText: '业务权限未变化' }}
      columns={[
        {
          title: '业务操作',
          render: (_, r) => (
            <>
              {catalog.find((p) => p.code === r.code)?.name ?? r.code}
              {catalog.find((p) => p.code === r.code)?.risk && (
                <Tag color="warning">高影响</Tag>
              )}
            </>
          ),
        },
        { title: '原权限', render: (_, r) => (r.old ? '本组织全部' : '无') },
        { title: '新权限', render: (_, r) => (r.next ? '本组织全部' : '撤销') },
      ]}
    />
  )
}
