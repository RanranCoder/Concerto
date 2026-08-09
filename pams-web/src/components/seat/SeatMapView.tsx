import { useMemo } from 'react'
import { Empty, Tooltip } from 'antd'
import type { SeatMapVO } from '@/api/activity'

interface SeatMapViewProps {
  seats: SeatMapVO[]
  legend: Record<string, string> // seatType -> 颜色
  onSelect?: (s: SeatMapVO) => void
}

const SEAT_SIZE = 40 // 座位格边长（px），与 gridTemplateColumns 的 repeat() 基准一致

/** 把 seats 按 zone 分组，并计算每组最大 colNo（决定该组网格列数） */
function groupByZone(seats: SeatMapVO[]): Array<{ zone: string; seats: SeatMapVO[]; maxCol: number; maxRow: number }> {
  const m = new Map<string, SeatMapVO[]>()
  for (const s of seats) {
    const zone = s.zone?.trim() ? s.zone : '未分区'
    const arr = m.get(zone)
    if (arr) arr.push(s)
    else m.set(zone, [s])
  }
  return [...m.entries()].map(([zone, zoneSeats]) => ({
    zone,
    seats: zoneSeats,
    maxCol: Math.max(1, ...zoneSeats.map((s) => s.colNo ?? 0)),
    maxRow: Math.max(1, ...zoneSeats.map((s) => s.rowNo ?? 0)),
  }))
}

/**
 * 电影选座风格座位矩阵（CSS Grid）：
 * 每个 zone 顶部显示区域名标签，下方按最大 colNo 定列、最大 rowNo 定行的网格；
 * rowNo 对应 grid 行（排），colNo 对应 grid 列（列）；左侧竖栏渲染排号（maxRow 个「n排」），
 * 顶部横栏渲染列号（maxCol 个「n列」），与 gridTemplateRows/Columns 对齐；
 * 座位为圆角方块，颜色 = legend[seatType] ?? 默认灰；已占座位显示就座人首字并加红描边；
 * 底部图例条展示所有 seatType 的色块。点击座位回调 onSelect。
 */
export default function SeatMapView({ seats, legend, onSelect }: SeatMapViewProps) {
  const groups = useMemo(() => groupByZone(seats), [seats])
  const legendEntries = useMemo(() => Object.entries(legend), [legend])

  if (seats.length === 0) {
    return <Empty description="尚未安排座位" />
  }

  return (
    <div>
      {groups.map(({ zone, seats: zoneSeats, maxCol, maxRow }) => (
        <div key={zone} style={{ marginBottom: 24 }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 10 }}>
            <div style={{ fontWeight: 600, color: 'var(--color-text)' }}>
              {zone}
              <span style={{ marginLeft: 8, fontSize: 12, fontWeight: 400, color: 'var(--color-text-secondary)' }}>
                共 {zoneSeats.length} 座
              </span>
            </div>
          </div>
          <div style={{ display: 'flex' }}>
            {/* 左侧排号栏：每个 grid 行一个「n排」（共 maxRow 个），高度对齐座位格 */}
            <div style={{ display: 'flex', flexDirection: 'column', gap: 6, paddingTop: 46 }}>
              {Array.from({ length: maxRow }, (_, i) => (
                <div
                  key={i}
                  style={{
                    width: 26,
                    height: SEAT_SIZE,
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    fontSize: 11,
                    color: 'var(--color-text-secondary)',
                  }}
                >
                  {i + 1}排
                </div>
              ))}
            </div>
            <div style={{ flex: 1 }}>
              {/* 顶部列号栏：每个 grid 列一个「n列」（共 maxCol 个），宽对齐座位格 */}
              <div style={{ display: 'flex', gap: 6, marginBottom: 6 }}>
                {Array.from({ length: maxCol }, (_, i) => (
                  <div
                    key={i}
                    style={{
                      width: SEAT_SIZE,
                      textAlign: 'center',
                      fontSize: 11,
                      color: 'var(--color-text-secondary)',
                    }}
                  >
                    {i + 1}列
                  </div>
                ))}
              </div>
              <div
                style={{
                  display: 'grid',
                  gridTemplateColumns: `repeat(${maxCol}, ${SEAT_SIZE}px)`,
                  gridTemplateRows: `repeat(${maxRow}, ${SEAT_SIZE}px)`,
                  gap: 6,
                }}
              >
                {zoneSeats.map((s) => {
                  const type = s.seatType?.trim() || ''
                  const color = type ? legend[type] : undefined
                  const taken = !!s.personName?.trim()
                  const cell = (
                    <button
                      key={s.id}
                      title={`${s.rowNo ?? '-'}排${s.colNo ?? '-'}列 · ${type || '普通'}${taken ? ` · ${s.personName}` : ''}`}
                      onClick={() => onSelect?.(s)}
                      style={{
                        width: '100%',
                        height: '100%',
                        aspectRatio: '1 / 1',
                        gridRow: s.rowNo ?? 'auto',
                        gridColumn: s.colNo ?? 'auto',
                        borderRadius: 8,
                        border: taken ? '2px solid var(--color-red)' : '1px solid var(--surface-border)',
                        background: color || 'rgba(148, 158, 175, 0.55)',
                        color: '#fff',
                        cursor: 'pointer',
                        fontSize: 12,
                        fontWeight: 600,
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                        boxSizing: 'border-box',
                        textShadow: '0 1px 2px rgba(0,0,0,0.35)',
                        transition: 'transform 0.12s var(--easing), box-shadow 0.12s var(--easing)',
                      }}
                      onMouseEnter={(e) => {
                        ;(e.currentTarget as HTMLButtonElement).style.transform = 'scale(1.08)'
                        ;(e.currentTarget as HTMLButtonElement).style.boxShadow = '0 4px 14px rgba(31,38,60,0.25)'
                      }}
                      onMouseLeave={(e) => {
                        ;(e.currentTarget as HTMLButtonElement).style.transform = ''
                        ;(e.currentTarget as HTMLButtonElement).style.boxShadow = ''
                      }}
                    >
                      {taken ? s.personName!.trim().slice(0, 1) : `${s.rowNo ?? ''}-${s.colNo ?? ''}`}
                    </button>
                  )
                  return taken ? (
                    <Tooltip key={s.id} title={`${s.personName} · ${s.rowNo ?? '-'}排${s.colNo ?? '-'}列 · ${type || '普通'}`}>
                      {cell}
                    </Tooltip>
                  ) : (
                    cell
                  )
                })}
              </div>
            </div>
          </div>
        </div>
      ))}

      {legendEntries.length > 0 ? (
        <div
          style={{
            display: 'flex',
            flexWrap: 'wrap',
            gap: 12,
            marginTop: 8,
            paddingTop: 12,
            borderTop: '1px solid var(--surface-border)',
          }}
        >
          {legendEntries.map(([type, color]) => (
            <span key={type} style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12, color: 'var(--color-text-secondary)' }}>
              <span style={{ width: 12, height: 12, borderRadius: 3, background: color, border: '1px solid var(--surface-border)' }} />
              {type}
            </span>
          ))}
        </div>
      ) : null}
    </div>
  )
}
