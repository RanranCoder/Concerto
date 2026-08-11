import { useCallback, useEffect, useMemo, useState } from 'react'
import { App, Button, Empty, Popconfirm, Select, Space, Tag } from 'antd'
import { DeleteOutlined } from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import GlassTable from '@/components/glass/GlassTable'
import {
  backfillSignins,
  deleteRoster,
  getRosterHeaders,
  listRoster,
  type RosterStatus,
  type SigninRosterVO,
} from '@/api/signin'

const STATUS_OPTIONS: Array<{ value: RosterStatus; label: string }> = [
  { value: 'ALL', label: '全部' },
  { value: 'SIGNED', label: '已签' },
  { value: 'UNSIGNED', label: '未签' },
]

interface SigninRosterListProps {
  activityId: number
  status: RosterStatus
  onStatusChange: (s: RosterStatus) => void
  /** 父级刷新信号：上传/字段配置/扫码签到/手动签到后 bump，强制本组件重新拉取名单 */
  reloadKey?: number
  /** 名单/字段/汇总变化后刷新（本组件删除/补签后触发父级刷新汇总） */
  onChanged: () => void
}

const EMPTY_TEXT: Record<RosterStatus, string> = {
  ALL: '尚未上传应签名单',
  SIGNED: '暂无已签名单',
  UNSIGNED: '暂无未签名单',
}

/**
 * 应签名单列表（GlassTable）。
 * 列 = 当前核验字段名（动态，字段配置变化后刷新）+ 状态列（已签绿/未签红 Tag）+ 操作（删除）。
 * 顶部状态筛选由父级传入（全部/已签/未签），未签行可勾选 + 「补签」批量调 backfillSignins。
 * 空态按筛选状态区分文案：全部 →「尚未上传应签名单」，已签/未签筛选下 →「暂无已签/未签名单」。
 */
export default function SigninRosterList({ activityId, status, onStatusChange, reloadKey = 0, onChanged }: SigninRosterListProps) {
  const { message } = App.useApp()
  const [rows, setRows] = useState<SigninRosterVO[]>([])
  const [fields, setFields] = useState<string[]>([])
  const [loading, setLoading] = useState(false)
  const [selectedIds, setSelectedIds] = useState<number[]>([])
  const [backfilling, setBackfilling] = useState(false)

  const fetchData = useCallback(async () => {
    setLoading(true)
    try {
      const [roster, headers] = await Promise.all([listRoster(activityId, status), getRosterHeaders(activityId)])
      setRows(roster ?? [])
      setFields(headers ?? [])
    } catch {
      /* http 拦截已提示 */
    } finally {
      setLoading(false)
    }
  }, [activityId, status, reloadKey])

  useEffect(() => {
    fetchData()
  }, [fetchData])

  // 行数据/筛选变化后清空勾选，避免勾选不属于当前视图的行
  useEffect(() => {
    setSelectedIds([])
  }, [rows, status])

  const handleDelete = async (id: number) => {
    try {
      await deleteRoster(id)
      message.success('已删除')
      onChanged()
    } catch {
      /* http 拦截已提示 */
    }
  }

  const handleBackfill = async () => {
    if (selectedIds.length === 0) {
      message.info('请先勾选要补签的未签名单')
      return
    }
    setBackfilling(true)
    try {
      const n = await backfillSignins(activityId, selectedIds)
      message.success(`已补签 ${n} 人`)
      setSelectedIds([])
      onChanged()
    } catch {
      /* http 拦截已提示 */
    } finally {
      setBackfilling(false)
    }
  }

  const columns = useMemo<ColumnsType<SigninRosterVO>>(() => {
    const fieldCols: ColumnsType<SigninRosterVO> = fields.map((name) => ({
      title: name,
      key: name,
      dataIndex: ['fields', name],
      ellipsis: true,
      render: (v: string | undefined) => v || '-',
    }))
    return [
      ...fieldCols,
      {
        title: '状态',
        key: 'status',
        width: 90,
        render: (_: unknown, r: SigninRosterVO) => (r.signed ? <Tag color="green">已签</Tag> : <Tag color="red">未签</Tag>),
      },
      {
        title: '操作',
        key: 'action',
        width: 70,
        render: (_: unknown, r: SigninRosterVO) => (
          <Popconfirm title="确认删除该名单行？" onConfirm={() => handleDelete(r.id)} okText="删除" cancelText="取消">
            <Button type="link" size="small" danger icon={<DeleteOutlined />} />
          </Popconfirm>
        ),
      },
    ]
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fields])

  return (
    <div>
      <Space style={{ marginBottom: 12 }} wrap>
        <Select<RosterStatus>
          value={status}
          onChange={onStatusChange}
          options={STATUS_OPTIONS}
          style={{ width: 120 }}
        />
        <Button loading={backfilling} disabled={selectedIds.length === 0} onClick={handleBackfill}>
          补签（{selectedIds.length}）
        </Button>
        {selectedIds.length > 0 && (
          <span style={{ color: 'var(--color-text-secondary)', fontSize: 12 }}>已勾选 {selectedIds.length} 行未签名单</span>
        )}
      </Space>
      <GlassTable<SigninRosterVO>
        rowKey="id"
        size="middle"
        loading={loading}
        columns={columns}
        dataSource={rows}
        pagination={rows.length > 10 ? { pageSize: 10, showTotal: (t) => `共 ${t} 条` } : false}
        rowSelection={{
          selectedRowKeys: selectedIds,
          onChange: (keys) => setSelectedIds(keys as number[]),
          // 已签行不可勾选（补签仅对未签有意义）
          getCheckboxProps: (r) => ({ disabled: r.signed }),
        }}
        locale={{ emptyText: <Empty description={EMPTY_TEXT[status]} /> }}
      />
    </div>
  )
}
