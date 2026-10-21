import type { ReactNode } from 'react'
import { Alert, Button, Empty, Grid, Pagination, Skeleton, Table } from 'antd'
import type { TableProps } from 'antd'
import { RightOutlined } from '@ant-design/icons'
import { isAxiosError } from 'axios'

export function errorText(error: unknown, fallback: string) {
  if (isAxiosError<{message?: string}>(error)) return error.response?.data?.message || fallback
  return error instanceof Error ? error.message : fallback
}

export function StatusTag({children, tone = 'neutral'}: {children: ReactNode; tone?: 'neutral' | 'success' | 'warning' | 'danger' | 'brand'}) {
  return <span className={`admin-status ${tone}`}><i aria-hidden="true" />{children}</span>
}

export function StatsStrip({items, loading}: {loading?: boolean; items: {label: string; value: ReactNode; note: ReactNode; warning?: boolean}[]}) {
  return <div className="admin-stats">{items.map(item => <div className={item.warning ? 'attention' : ''} key={item.label}><span>{item.label}</span><strong>{loading ? <Skeleton.Input active size="small" /> : item.value}</strong><small>{item.note}</small></div>)}</div>
}

export function Panel({title, description, extra, children}: {title: string; description?: ReactNode; extra?: ReactNode; children: ReactNode}) {
  return <section className="admin-panel"><header><div><h3>{title}</h3>{description && <p>{description}</p>}</div>{extra}</header><div className="admin-panel-body">{children}</div></section>
}

export function InfoList({items}: {items: {label: string; value: ReactNode}[]}) {
  return <dl className="admin-info">{items.map(({label, value}) => <div key={label}><dt>{label}</dt><dd>{value ?? '—'}</dd></div>)}</dl>
}

export function RelatedLink({title, description, icon, onClick}: {title: string; description: string; icon: ReactNode; onClick: () => void}) {
  return <button className="admin-related" onClick={onClick}>{icon}<span><strong>{title}</strong><small>{description}</small></span><RightOutlined aria-hidden="true" /></button>
}

export function LoadError({message, retry}: {message: string; retry: () => void}) {
  return <Alert type="error" showIcon message={message} action={<Button size="small" onClick={retry}>重试</Button>} />
}

/** Desktop table and readable mobile records use the same server page and action handlers. */
export function AdminTable<T extends {id: number | string}>({rows, total, page, size, loading, error, retry, onPage, columns, renderMobile, empty, rowSelection, compact = false}: {
  rows: T[]; total: number; page: number; size: number; loading: boolean; error?: string
  retry: () => void; onPage: (page: number, size: number) => void
  columns: TableProps<T>['columns']; renderMobile: (row: T) => ReactNode; empty?: ReactNode
  rowSelection?: TableProps<T>['rowSelection']; compact?: boolean
}) {
  const screens = Grid.useBreakpoint()
  if (error) return <div className="admin-load-error"><LoadError message={error} retry={retry} /></div>
  return <div className={`admin-data${compact ? ' compact' : ''}`}>
    {screens.md ? <Table<T> className="admin-table" rowKey="id" columns={columns} dataSource={rows} loading={loading} pagination={false} rowSelection={rowSelection} size={compact ? 'small' : 'middle'} scroll={{x: 800}} locale={{emptyText: empty || <Empty description="没有匹配记录" />}} />
      : loading ? <div className="admin-mobile-loading"><Skeleton active paragraph={{rows: 5}} /></div>
        : rows.length ? <div className="admin-mobile-list">{rows.map(row => <article key={row.id}>{renderMobile(row)}</article>)}</div>
          : <div className="admin-empty">{empty || <Empty description="没有匹配记录" />}</div>}
    <div className="admin-pagination"><span>{total ? `${(page - 1) * size + 1}–${Math.min(page * size, total)} 条，共 ${total} 条` : '共 0 条'}</span><Pagination current={page} total={total} pageSize={size} showSizeChanger pageSizeOptions={[6, 10, 20, 50]} size="small" onChange={onPage} /></div>
  </div>
}
