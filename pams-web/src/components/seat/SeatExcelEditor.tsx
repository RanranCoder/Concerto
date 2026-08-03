import { useMemo, useState } from 'react'
import { Button, ColorPicker, Input, Space, message } from 'antd'
import { RevoGrid, type BeforeSaveDataDetails, type ColumnRegular } from '@revolist/react-datagrid'
import type { SeatMapVO } from '@/api/activity'

interface SeatExcelEditorProps {
  seats: SeatMapVO[]
  legend: Record<string, string>
  onChangeLegend: (legend: Record<string, string>) => void
  onChangeSeats: (seats: SeatMapVO[]) => void
}

type RowModel = {
  id: number // 隐藏列：随行对象保留，编辑事件里用于定位后端座位
  zone: string
  rowNo: number | null
  colNo: number | null
  seatType: string | null
  personName: string | null
}

/** 数值空串/undefined/null 归一为 null，避免回写后端时空字符串 */
function toNum(v: unknown): number | null {
  if (v === null || v === undefined) return null
  if (typeof v === 'number') return Number.isFinite(v) ? v : null
  const t = String(v).trim()
  if (!t) return null
  const n = Number(t)
  return Number.isFinite(n) ? n : null
}

/**
 * 座位表 Excel 编辑（@revolist/revogrid 的 React 封装 RevoGrid）：
 * 网格列 = [区域, 排, 列, 座位类型, 就座人]，行 = 每个座位（含 id 的隐藏列随行对象保留）。
 * 编辑单元格经 onBeforeEdit 事件实时回写并 onChangeSeats 回传。
 * 注意：columns/source 用 useMemo 保持稳定引用，避免 revogrid 无限重渲染。
 * 图例配置面板：ColorPicker 改色 + Input/Button 新增 seatType。
 */
export default function SeatExcelEditor({ seats, legend, onChangeLegend, onChangeSeats }: SeatExcelEditorProps) {
  const [newType, setNewType] = useState('')

  const rows = useMemo<RowModel[]>(
    () =>
      seats.map((s) => ({
        id: s.id,
        zone: s.zone,
        rowNo: s.rowNo,
        colNo: s.colNo,
        seatType: s.seatType,
        personName: s.personName,
      })),
    [seats],
  )

  const columns = useMemo<ColumnRegular[]>(
    () => [
      { prop: 'zone', name: '区域', size: 140 },
      { prop: 'rowNo', name: '排', size: 70 },
      { prop: 'colNo', name: '列', size: 70 },
      { prop: 'seatType', name: '座位类型', size: 120 },
      { prop: 'personName', name: '就座人', size: 160 },
    ],
    [],
  )

  /** 编辑事件（beforeedit）：val 未定义/未变化时跳过；否则按行 id 定位座位回写并回传（columns/source 仍稳定引用，不回灌 source 即不触发重渲染） */
  const handleBeforeEdit = (e: CustomEvent<BeforeSaveDataDetails>) => {
    if (!e?.detail) return
    const { prop, val, model } = e.detail
    if (prop === null || prop === undefined || model == null) return
    const seatId = Number((model as RowModel).id)
    if (!Number.isFinite(seatId)) return

    // 与当前值比较：未变化则跳过，避免保存空改动
    if (model[prop as keyof RowModel] === val) return

    const next = seats.map((s) => {
      if (s.id !== seatId) return s
      const updated = { ...s }
      switch (prop as keyof RowModel) {
        case 'zone':
          updated.zone = String(val ?? '').trim() || s.zone
          break
        case 'rowNo':
          updated.rowNo = toNum(val)
          break
        case 'colNo':
          updated.colNo = toNum(val)
          break
        case 'seatType':
          updated.seatType = String(val ?? '').trim() || null
          break
        case 'personName':
          updated.personName = String(val ?? '').trim() || null
          break
        default:
          return s
      }
      return updated
    })
    onChangeSeats(next)
  }

  const handleAddType = () => {
    const t = newType.trim()
    if (!t) {
      message.warning('请输入座位类型')
      return
    }
    if (legend[t]) {
      message.warning('该座位类型已存在')
      return
    }
    onChangeLegend({ ...legend, [t]: '#8C9AAB' })
    setNewType('')
  }

  return (
    <div>
      {/* 图例配置面板：每个 seatType 一个 ColorPicker 色块 + 名称，可改色；下方可新增 seatType */}
      <div style={{ marginBottom: 12, display: 'flex', flexWrap: 'wrap', gap: 8, alignItems: 'center' }}>
        {Object.entries(legend).map(([type, color]) => (
          <span
            key={type}
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 4,
              padding: '2px 8px 2px 4px',
              borderRadius: 8,
              border: '1px solid var(--glass-border)',
              background: 'var(--glass-bg-strong)',
            }}
          >
            <ColorPicker
              size="small"
              value={color}
              onChangeComplete={(c) => onChangeLegend({ ...legend, [type]: c.toHexString() })}
            />
            <span style={{ fontSize: 12, color: 'var(--color-text)' }}>{type}</span>
          </span>
        ))}
        <Space.Compact style={{ marginLeft: 4 }}>
          <Input
            size="small"
            placeholder="新增座位类型"
            value={newType}
            maxLength={20}
            onChange={(e) => setNewType(e.target.value)}
            onPressEnter={handleAddType}
            style={{ width: 140 }}
          />
          <Button size="small" type="primary" icon={<span>＋</span>} onClick={handleAddType}>
            新增
          </Button>
        </Space.Compact>
      </div>

      <RevoGrid
        columns={columns}
        source={rows}
        onBeforeedit={handleBeforeEdit}
        rowHeaders
        theme="material"
        resize
        autoSizeColumn
        readonly={false}
      />
      <div style={{ marginTop: 8, fontSize: 12, color: 'var(--color-text-secondary)' }}>
        双击或回车进入单元格编辑，修改后实时保存到当前页；切换视图或刷新后由后端持久化。
      </div>
    </div>
  )
}
