import type { TableProps } from 'antd'
import type { ColumnType } from 'antd/es/table'
import GlassTable from '@/components/glass/GlassTable'

/** Keep real table semantics on desktop; labelled records are easier to scan on narrow screens. */
export default function ActivityTable<T extends object>(props: TableProps<T>) {
  const columns = props.columns?.map((base) => {
    const column = base as ColumnType<T>
    return {
      ...column,
      onCell: (record: T, index?: number) => ({
        ...column.onCell?.(record, index),
        'data-label':
          typeof column.title === 'string'
            ? column.title
            : column.key === 'action'
              ? '操作'
              : '',
      }),
    }
  })
  return (
    <div className="activity-table-cards">
      <GlassTable<T> {...props} columns={columns} />
    </div>
  )
}
