import { Table, type TableProps } from 'antd'

export default function GlassTable<T>(props: TableProps<T>) {
  return (
    <div className="glass-card" style={{ padding: 4, overflow: 'hidden' }}>
      <Table<T> size="middle" pagination={{ showSizeChanger: true, showTotal: (t) => `共 ${t} 条` }} {...props} />
    </div>
  )
}
