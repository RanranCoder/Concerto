import { useMemo, useState } from 'react'
import { Input, DatePicker, Slider, Select, Empty, Button } from 'antd'
import dayjs from 'dayjs'
import GlassModal from '@/components/glass/GlassModal'
import { dayRange, buildDeps, todayStr, taskToPixels, type GanttTask } from './gantt.utils'

interface GanttChartProps {
  tasks: GanttTask[]
  onUpdate: (t: GanttTask) => void
  pxPerDay?: number
}

const ROW_HEIGHT = 48
const HEADER_H = 64
const LABEL_W = 180
const BAR_H = 26

// 黑白灰层级（按部门名散列取灰阶），关键任务（里程碑）红色描边
function barFill(deptName?: string): string {
  if (!deptName) return 'rgba(130, 140, 155, 0.55)'
  let h = 0
  for (let i = 0; i < deptName.length; i++) h = (h * 31 + deptName.charCodeAt(i)) % 360
  const v = 120 + (h % 5) * 18 // 120-192 灰阶
  return `rgb(${v}, ${v + 8}, ${v + 20})`
}

export default function GanttChart({ tasks, onUpdate, pxPerDay = 24 }: GanttChartProps) {
  const [editing, setEditing] = useState<GanttTask | null>(null)

  const range = useMemo(() => {
    if (!tasks.length) return { start: todayStr(), days: 30 }
    const start = tasks.reduce((a, t) => (t.startDate < a ? t.startDate : a), tasks[0].startDate)
    const end = tasks.reduce((a, t) => (t.endDate > a ? t.endDate : a), tasks[0].endDate)
    return { start, days: Math.max(dayRange(start, end), 1) }
  }, [tasks])

  const gridW = range.days * pxPerDay
  const width = LABEL_W + gridW
  const height = HEADER_H + tasks.length * ROW_HEIGHT
  const deps = useMemo(() => buildDeps(tasks), [tasks])
  const todayX = LABEL_W + Math.max(dayjs(todayStr()).diff(dayjs(range.start), 'day'), 0) * pxPerDay

  const rows = useMemo(() => {
    const byId = new Map(tasks.map((t) => [t.id, t]))
    return tasks.map((t, i) => {
      const offset = Math.max(dayjs(t.startDate).diff(dayjs(range.start), 'day'), 0)
      const px = taskToPixels(t, pxPerDay, offset)
      const y = HEADER_H + i * ROW_HEIGHT + (ROW_HEIGHT - BAR_H) / 2
      const from = t.dependsOn != null ? byId.get(t.dependsOn) : undefined
      const fromRow = from ? tasks.indexOf(from) : -1
      return { t, i, y, px, from, fromRow }
    })
  }, [tasks, range, pxPerDay])

  // 月份刻度（避免重复），每个自然月取该月的第一个天
  const months: Array<{ label: string; x: number }> = []
  const seenMonths = new Set<string>()
  for (let d = 0; d < range.days; d++) {
    const date = dayjs(range.start).add(d, 'day')
    const key = date.format('YYYY-MM')
    if (!seenMonths.has(key)) {
      seenMonths.add(key)
      months.push({ label: date.format('YYYY-MM'), x: LABEL_W + d * pxPerDay })
    }
  }

  const saveEdit = () => {
    if (editing) onUpdate(editing)
    setEditing(null)
  }

  if (!tasks.length) {
    return (
      <Empty
        image={Empty.PRESENTED_IMAGE_SIMPLE}
        description="主任尚未分发任务"
        style={{ padding: 48 }}
      />
    )
  }

  return (
    <div style={{ overflowX: 'auto', width: '100%' }}>
      <svg width={width} height={height} viewBox={`0 0 ${width} ${height}`}>
        {/* 背景棋盘格（按天） */}
        {Array.from({ length: range.days }, (_, d) => (
          <rect
            key={d}
            x={LABEL_W + d * pxPerDay}
            y={HEADER_H}
            width={pxPerDay}
            height={tasks.length * ROW_HEIGHT}
            fill={d % 2 === 0 ? 'rgba(255,255,255,0.03)' : 'transparent'}
          />
        ))}

        {/* 顶部时间轴：月刻度 */}
        <rect x={0} y={0} width={width} height={HEADER_H} fill="rgba(255,255,255,0.05)" />
        {months.map((m) => (
          <g key={m.label}>
            <line x1={m.x} y1={0} x2={m.x} y2={HEADER_H} stroke="rgba(140,150,165,0.35)" strokeDasharray="3 3" />
            <text x={m.x + 4} y={20} fill="var(--color-text-secondary)" fontSize={11}>
              {m.label}
            </text>
          </g>
        ))}
        {Array.from({ length: range.days }, (_, d) => (
          <text
            key={d}
            x={LABEL_W + d * pxPerDay + pxPerDay / 2}
            y={HEADER_H - 8}
            fill="rgba(140,150,165,0.55)"
            fontSize={10}
            textAnchor="middle"
          >
            {dayjs(range.start).add(d, 'day').date()}
          </text>
        ))}

        {/* 今日线 */}
        <line
          x1={todayX}
          y1={0}
          x2={todayX}
          y2={height}
          stroke="var(--color-red)"
          strokeWidth={1.5}
          strokeDasharray="4 3"
        />
        <text x={todayX - 6} y={HEADER_H - 14} fill="var(--color-red)" fontSize={10} textAnchor="end">
          今日
        </text>

        {/* 依赖连线 */}
        {deps.map((d, idx) => {
          const fromRow = rows.find((r) => r.t.id === d.from)
          const toRow = rows.find((r) => r.t.id === d.to)
          if (!fromRow || !toRow) return null
          const x1 = fromRow.px.left + fromRow.px.width
          const y1 = fromRow.y + BAR_H / 2
          const x2 = toRow.px.left
          const y2 = toRow.y + BAR_H / 2
          const midX = (x1 + x2) / 2
          return (
            <g key={idx}>
              <path
                d={`M ${x1} ${y1} C ${midX} ${y1}, ${midX} ${y2}, ${x2} ${y2}`}
                fill="none"
                stroke="rgba(150,160,175,0.6)"
                strokeWidth={1.5}
                strokeDasharray="4 3"
              />
              <path
                d={`M ${x2 - 5} ${y2 - 4} L ${x2} ${y2} L ${x2 - 5} ${y2 + 4}`}
                fill="none"
                stroke="rgba(150,160,175,0.8)"
                strokeWidth={1.5}
              />
            </g>
          )
        })}

        {/* 任务条 + 名称 */}
        {rows.map(({ t, y, px }) => (
          <g
            key={t.id}
            role="button"
            aria-label={t.name}
            style={{ cursor: 'pointer' }}
            onClick={() => setEditing(t)}
          >
            <text x={8} y={y + 16} fill="var(--color-text)" fontSize={12} style={{ userSelect: 'none' }}>
              {t.name}
            </text>
            <rect
              x={px.left}
              y={y}
              width={px.width}
              height={BAR_H}
              rx={6}
              fill={barFill(t.deptName)}
              stroke={t.isMilestone ? 'var(--color-red)' : 'transparent'}
              strokeWidth={t.isMilestone ? 2 : 0}
              strokeDasharray={t.isMilestone ? '5 3' : undefined}
            />
            {/* 进度覆盖层（红色） */}
            {!t.isMilestone && (
              <rect
                x={px.left}
                y={y}
                width={Math.max((px.width * (t.progress ?? 0)) / 100, 0)}
                height={BAR_H}
                rx={6}
                fill="var(--color-red)"
                opacity={0.75}
              />
            )}
            {/* 里程碑菱形 */}
            {t.isMilestone && (
              <rect
                x={px.left + px.width / 2 - 6}
                y={y + BAR_H / 2 - 6}
                width={12}
                height={12}
                rx={1.5}
                fill="var(--color-red)"
                transform={`rotate(45 ${px.left + px.width / 2} ${y + BAR_H / 2})`}
              />
            )}
            <text
              x={px.left + 6}
              y={y + 17}
              fill="#fff"
              fontSize={11}
              style={{ pointerEvents: 'none' }}
            >
              {t.isMilestone ? '里程碑' : `${t.progress ?? 0}%`}
            </text>
          </g>
        ))}
      </svg>

      {/* 编辑弹窗 */}
      <GlassModal
        open={!!editing}
        title={editing ? `编辑任务：${editing.name}` : ''}
        onCancel={() => setEditing(null)}
        footer={
          <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
            <Button onClick={() => setEditing(null)}>取消</Button>
            <Button type="primary" onClick={saveEdit}>保存</Button>
          </div>
        }
      >
        {editing && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
            <div>
              <div style={{ marginBottom: 6, color: 'var(--color-text-secondary)' }}>任务名称</div>
              <Input
                value={editing.name}
                onChange={(e) => setEditing({ ...editing, name: e.target.value })}
                placeholder="任务名称"
              />
            </div>
            <div style={{ display: 'flex', gap: 12 }}>
              <div style={{ flex: 1 }}>
                <div style={{ marginBottom: 6, color: 'var(--color-text-secondary)' }}>开始日期</div>
                <DatePicker
                  style={{ width: '100%' }}
                  value={dayjs(editing.startDate)}
                  onChange={(d) => d && setEditing({ ...editing, startDate: d.format('YYYY-MM-DD') })}
                />
              </div>
              <div style={{ flex: 1 }}>
                <div style={{ marginBottom: 6, color: 'var(--color-text-secondary)' }}>结束日期</div>
                <DatePicker
                  style={{ width: '100%' }}
                  value={dayjs(editing.endDate)}
                  onChange={(d) => d && setEditing({ ...editing, endDate: d.format('YYYY-MM-DD') })}
                />
              </div>
            </div>
            <div>
              <div style={{ marginBottom: 6, color: 'var(--color-text-secondary)' }}>
                进度：{editing.progress ?? 0}%
              </div>
              <Slider
                min={0}
                max={100}
                value={editing.progress ?? 0}
                onChange={(v) => setEditing({ ...editing, progress: v })}
              />
            </div>
            <div>
              <div style={{ marginBottom: 6, color: 'var(--color-text-secondary)' }}>负责人</div>
              <Select
                style={{ width: '100%' }}
                placeholder="选择负责人"
                value={editing.assignee || undefined}
                onChange={(v) => setEditing({ ...editing, assignee: v })}
                options={(editing.deptName ? [editing.deptName] : []).map((d) => ({ label: d, value: d }))}
              />
            </div>
          </div>
        )}
      </GlassModal>
    </div>
  )
}
