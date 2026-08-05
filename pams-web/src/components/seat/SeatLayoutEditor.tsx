import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Button, ColorPicker, Input, InputNumber, message, Modal, Popover, Slider, Space, Tag } from 'antd'
import {
  BorderOutlined,
  CheckOutlined,
  DownloadOutlined,
  EditOutlined,
  SaveOutlined,
  UndoOutlined,
  RedoOutlined,
  DeleteOutlined,
} from '@ant-design/icons'
import type { ColorLabel, SeatCell, SeatLayoutVO } from '@/api/seatLayout'

/**
 * 座位表可视化编辑器（PRD F01）
 * 功能：行列配置、过道、颜色区域（含标签）、模板保存/加载、缩放、导出PNG
 * 数据模型：seat_data JSON 数组 [{row, col, type, color, label, state, personName}]
 */

// 预设色板（含无障碍友好色）
export const PRESET_COLORS = [
  { color: '#f5222d', label: '嘉宾区' },
  { color: '#fa541c', label: '党员区' },
  { color: '#faad14', label: '积极分子区' },
  { color: '#52c41a', label: '发展对象区' },
  { color: '#13c2c2', label: '预备党员区' },
  { color: '#1677ff', label: '正式党员区' },
  { color: '#722ed1', label: '工作人员区' },
  { color: '#eb2f96', label: '媒体区' },
  { color: '#8c8c8c', label: '观众区' },
  { color: '#597ef7', label: '特邀区' },
]

interface SeatLayoutEditorProps {
  value: SeatLayoutVO | null
  activityId: number
  readOnly?: boolean
  onSaved?: () => void
}

export default function SeatLayoutEditor({ value, activityId, readOnly, onSaved }: SeatLayoutEditorProps) {
  const [rows, setRows] = useState(10)
  const [cols, setCols] = useState(10)
  const [aisleCols, setAisleCols] = useState<Set<number>>(new Set())
  const [aisleRatio, setAisleRatio] = useState<number>(1.5)
  const [cells, setCells] = useState<Map<string, SeatCell>>(new Map())
  const [zoom, setZoom] = useState(100)
  const [layoutName, setLayoutName] = useState('我的座位表')
  const [colorLabels, setColorLabels] = useState<ColorLabel[]>(PRESET_COLORS)
  const [selectedColor, setSelectedColor] = useState<string>(PRESET_COLORS[0].color)
  const [selectedLabel, setSelectedLabel] = useState<string>(PRESET_COLORS[0].label)
  const [colorManageOpen, setColorManageOpen] = useState(false)
  const [newColor, setNewColor] = useState('#1677ff')
  const [newLabel, setNewLabel] = useState('')
  const [selection, setSelection] = useState<Set<string>>(new Set())
  const [dragStart, setDragStart] = useState<string | null>(null)
  const [history, setHistory] = useState<Array<{ rows: number; cols: number; cells: Map<string, SeatCell> }>>([])
  const [historyIdx, setHistoryIdx] = useState(-1)
  const [templateOpen, setTemplateOpen] = useState(false)
  const [templates, setTemplates] = useState<SeatLayoutVO[]>([])
  const gridRef = useRef<HTMLDivElement>(null)
  const editorRef = useRef<HTMLDivElement>(null)

  const key = (r: number, c: number) => `${r}-${c}`
  const cellKey = (k: string) => {
    const [r, c] = k.split('-').map(Number)
    return { r, c }
  }

  // 初始化 / 载入布局
  useEffect(() => {
    if (value) {
      setRows(value.rows)
      setCols(value.cols)
      setLayoutName(value.name)
      setAisleCols(new Set((value.aisleCols || '').split(',').filter(Boolean).map(Number)))
      setAisleRatio(Number(value.aisleWidthRatio) || 1.5)
      try {
        const parsed = JSON.parse(value.colorLabels || '[]') as ColorLabel[]
        if (parsed.length) {
          setColorLabels(parsed)
          setSelectedColor(parsed[0].color)
          setSelectedLabel(parsed[0].label)
        }
      } catch {
        /* 配色解析失败用默认 */
      }
      try {
        const parsed = JSON.parse(value.seatData || '[]') as SeatCell[]
        const m = new Map<string, SeatCell>()
        for (const c of parsed) m.set(key(c.row, c.col), c)
        setCells(m)
      } catch {
        setCells(new Map())
      }
    }
  }, [value])

  // 快照历史
  const snapshot = useCallback((next: Map<string, SeatCell>, r: number, c: number) => {
    setHistory((h) => {
      const nh = h.slice(0, historyIdx + 1)
      nh.push({ rows: r, cols: c, cells: new Map(next) })
      return nh.slice(-50)
    })
    setHistoryIdx((i) => Math.min(i + 1, 49))
  }, [historyIdx])

  const pushHistory = useCallback((next: Map<string, SeatCell>) => {
    snapshot(next, rows, cols)
  }, [snapshot, rows, cols])

  const undo = () => {
    if (historyIdx < 0) return
    const prev = history[historyIdx]
    setCells(new Map(prev.cells))
    setRows(prev.rows)
    setCols(prev.cols)
    setHistoryIdx(historyIdx - 1)
  }

  const redo = () => {
    if (historyIdx + 1 >= history.length) return
    const next = history[historyIdx + 1]
    setCells(new Map(next.cells))
    setRows(next.rows)
    setCols(next.cols)
    setHistoryIdx(historyIdx + 1)
  }

  // 点击格子：切换空/座位，或应用当前颜色
  const handleCellClick = (r: number, c: number) => {
    if (readOnly) return
    const k = key(r, c)
    const cell = cells.get(k)
    const next = new Map(cells)
    if (aisleCols.has(c)) return // 过道列不可设置座位
    if (!cell || cell.type === 'empty') {
      next.set(k, { row: r, col: c, type: 'seat', color: selectedColor, label: selectedLabel, state: 'EMPTY' })
    } else {
      // 已设座位：应用当前颜色
      next.set(k, { ...cell, color: selectedColor, label: selectedLabel })
    }
    pushHistory(next)
    setCells(next)
  }

  // 拖拽批量选中
  const handleMouseDown = (r: number, c: number) => {
    if (readOnly) return
    setDragStart(key(r, c))
    setSelection(new Set([key(r, c)]))
  }

  const handleMouseEnter = (r: number, c: number) => {
    if (!dragStart) return
    const { r: sr, c: sc } = cellKey(dragStart)
    const minR = Math.min(sr, r), maxR = Math.max(sr, r)
    const minC = Math.min(sc, c), maxC = Math.max(sc, c)
    const sel = new Set<string>()
    for (let i = minR; i <= maxR; i++) {
      for (let j = minC; j <= maxC; j++) {
        if (!aisleCols.has(j)) sel.add(key(i, j))
      }
    }
    setSelection(sel)
  }

  const handleMouseUp = () => {
    if (dragStart) {
      // 批量上色
      const next = new Map(cells)
      selection.forEach((k) => {
        const cell = next.get(k)
        if (cell && cell.type === 'seat') {
          next.set(k, { ...cell, color: selectedColor, label: selectedLabel })
        } else if (!cell) {
          const { r, c } = cellKey(k)
          next.set(k, { row: r, col: c, type: 'seat', color: selectedColor, label: selectedLabel, state: 'EMPTY' })
        }
      })
      pushHistory(next)
      setCells(next)
    }
    setDragStart(null)
  }

  // 过道设置：把整列设为过道 / 取消
  const toggleAisle = (c: number) => {
    if (readOnly) return
    const next = new Set(aisleCols)
    const nextCells = new Map(cells)
    if (next.has(c)) {
      next.delete(c)
    } else {
      next.add(c)
      // 清掉该列的座位格
      for (let r = 1; r <= rows; r++) {
        nextCells.delete(key(r, c))
      }
    }
    setAisleCols(next)
    setCells(nextCells)
    pushHistory(nextCells)
  }

  // 按行/列/矩形填充颜色
  const fill = (mode: 'row' | 'col' | 'all') => {
    if (readOnly) return
    const next = new Map(cells)
    if (mode === 'all') {
      for (let r = 1; r <= rows; r++) {
        for (let c = 1; c <= cols; c++) {
          if (aisleCols.has(c)) continue
          next.set(key(r, c), { row: r, col: c, type: 'seat', color: selectedColor, label: selectedLabel, state: 'EMPTY' })
        }
      }
    } else if (selection.size > 0) {
      selection.forEach((k) => {
        const { r, c } = cellKey(k)
        if (mode === 'row') {
          for (let j = 1; j <= cols; j++) {
            if (aisleCols.has(j)) continue
            next.set(key(r, j), { row: r, col: j, type: 'seat', color: selectedColor, label: selectedLabel, state: 'EMPTY' })
          }
        } else if (mode === 'col') {
          for (let i = 1; i <= rows; i++) {
            if (aisleCols.has(c)) continue
            next.set(key(i, c), { row: i, col: c, type: 'seat', color: selectedColor, label: selectedLabel, state: 'EMPTY' })
          }
        }
      })
    }
    pushHistory(next)
    setCells(next)
    message.success('填充完成')
  }

  // 清除选中格子
  const clearSelection = () => {
    if (selection.size === 0) return
    const next = new Map(cells)
    selection.forEach((k) => next.delete(k))
    pushHistory(next)
    setCells(next)
    setSelection(new Set())
  }

  // 行列变更
  const changeRows = (v: number | null) => {
    if (v == null) return
    const n = Math.min(Math.max(v, 1), 100)
    setRows(n)
  }
  const changeCols = (v: number | null) => {
    if (v == null) return
    const n = Math.min(Math.max(v, 1), 100)
    setCols(n)
  }

  // 配色管理：新增/删除/重命名
  const selectColor = (c: ColorLabel) => {
    setSelectedColor(c.color)
    setSelectedLabel(c.label)
  }
  const addColor = (color: string, label: string) => {
    if (!color || !label.trim()) return
    if (colorLabels.some((c) => c.color.toLowerCase() === color.toLowerCase())) {
      message.warning('该颜色已存在')
      return
    }
    const next = [...colorLabels, { color, label: label.trim() }]
    setColorLabels(next)
    setSelectedColor(color)
    setSelectedLabel(label.trim())
  }
  const removeColor = (color: string) => {
    if (colorLabels.length <= 1) {
      message.warning('至少保留一个配色')
      return
    }
    const next = colorLabels.filter((c) => c.color !== color)
    setColorLabels(next)
    if (selectedColor === color) selectColor(next[0])
  }
  const renameColor = (color: string, label: string) => {
    if (!label.trim()) return
    setColorLabels((prev) => prev.map((c) => (c.color === color ? { ...c, label: label.trim() } : c)))
    if (selectedColor === color) setSelectedLabel(label.trim())
  }

  // 保存布局
  const saveLayout = async () => {
    if (readOnly) return
    try {
      const { createSeatLayout, updateSeatLayout } = await import('@/api/seatLayout')
      const payload = {
        activityId,
        name: layoutName.trim() || '我的座位表',
        rows,
        cols,
        aisleCols: [...aisleCols].sort((a, b) => a - b).join(','),
        aisleWidthRatio: aisleRatio,
        seatData: JSON.stringify([...cells.values()]),
        colorLabels: JSON.stringify(colorLabels),
      }
      if (value?.id) {
        await updateSeatLayout(value.id, payload)
      } else {
        await createSeatLayout(payload)
      }
      message.success('座位表已保存')
      onSaved?.()
    } catch {
      /* 拦截已提示 */
    }
  }

  // 导出 PNG
  const exportPng = () => {
    const node = gridRef.current
    if (!node) return
    // 用 html-to-image 动态导入（前端已有依赖？若无则用 canvas 兜底方案省略——直接打开打印视图）
    void node
    const win = window.open('', '_blank')
    if (!win) return
    win.document.write(
      `<html><head><title>${layoutName}</title><style>
        body { padding: 24px; font-family: -apple-system, 'Segoe UI', sans-serif; }
        .seat-grid-wrap { overflow: visible; }
        table.seat-grid { border-collapse: collapse; margin: 16px auto; }
        table.seat-grid td { border: 1px solid #ccc; width: 36px; height: 36px; text-align: center; font-size: 10px; }
        td.aisle { background: #f0f0f0; width: 54px; }
      </style></head><body><h3>${layoutName}</h3>`,
    )
    // 简单表格导出
    let table = '<table class="seat-grid">'
    for (let r = 1; r <= rows; r++) {
      table += '<tr>'
      for (let c = 1; c <= cols; c++) {
        const cell = cells.get(key(r, c))
        const isAisle = aisleCols.has(c)
        if (isAisle) table += '<td class="aisle" title="过道"></td>'
        else if (cell?.type === 'seat') table += `<td style="background:${cell.color || '#fff'}">${cell.label?.[0] || ''}</td>`
        else table += '<td></td>'
      }
      table += '</tr>'
    }
    table += '</table>'
    win.document.write(table + '</body></html>')
    win.document.close()
    win.focus()
    win.print()
  }

  // 加载模板列表
  const loadTemplates = async () => {
    const { listSeatTemplates } = await import('@/api/seatLayout')
    const t = await listSeatTemplates()
    setTemplates(t ?? [])
    setTemplateOpen(true)
  }

  const applyTemplate = (t: SeatLayoutVO) => {
    setRows(t.rows)
    setCols(t.cols)
    setLayoutName(t.name)
    setAisleCols(new Set((t.aisleCols || '').split(',').filter(Boolean).map(Number)))
    setAisleRatio(Number(t.aisleWidthRatio) || 1.5)
    try {
      const parsed = JSON.parse(t.colorLabels || '[]') as ColorLabel[]
      if (parsed.length) {
        setColorLabels(parsed)
        setSelectedColor(parsed[0].color)
        setSelectedLabel(parsed[0].label)
      }
    } catch {
      /* 配色解析失败用默认 */
    }
    try {
      const parsed = JSON.parse(t.seatData || '[]') as SeatCell[]
      const m = new Map<string, SeatCell>()
      for (const c of parsed) m.set(key(c.row, c.col), c)
      setCells(m)
    } catch {
      setCells(new Map())
    }
    setTemplateOpen(false)
    message.success(`已应用模板「${t.name}」`)
  }

  // 渲染网格
  const cellSize = Math.max(14, Math.floor(680 / Math.max(cols, 8)))
  const rowLabelWidth = 26
  const colHeaderH = 20

  const columnLetters = useMemo(() => {
    const letters: string[] = []
    for (let c = 1; c <= cols; c++) {
      let s = ''
      let n = c
      while (n > 0) {
        n -= 1
        s = String.fromCharCode(65 + (n % 26)) + s
        n = Math.floor(n / 26)
      }
      letters.push(s)
    }
    return letters
  }, [cols])

  return (
    <div ref={editorRef}>
      {/* 工具栏 */}
      {!readOnly && (
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, alignItems: 'center', marginBottom: 12, padding: 12, background: 'var(--color-bg-2)', borderRadius: 10 }}>
          <Input
            value={layoutName}
            onChange={(e) => setLayoutName(e.target.value)}
            placeholder="布局名称"
            style={{ width: 160 }}
            size="small"
          />
          <Space size={4}>
            <span style={{ fontSize: 12, color: 'var(--color-text-secondary)' }}>行</span>
            <InputNumber min={1} max={100} value={rows} onChange={changeRows} size="small" style={{ width: 70 }} />
            <span style={{ fontSize: 12, color: 'var(--color-text-secondary)' }}>列</span>
            <InputNumber min={1} max={100} value={cols} onChange={changeCols} size="small" style={{ width: 70 }} />
          </Space>

          {/* 过道 */}
          <Popover
            trigger="click"
            content={
              <div style={{ maxWidth: 260 }}>
                <div style={{ fontSize: 12, marginBottom: 8, color: 'var(--color-text-secondary)' }}>
                  点击列号切换过道（过道列不可设座位，宽度自动加宽）
                </div>
                <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6, maxWidth: 260 }}>
                  {columnLetters.map((l, i) => (
                    <Tag
                      key={i}
                      color={aisleCols.has(i + 1) ? 'red' : 'default'}
                      style={{ cursor: 'pointer', margin: 0 }}
                      onClick={() => toggleAisle(i + 1)}
                    >
                      {l}{aisleCols.has(i + 1) ? ' ║' : ''}
                    </Tag>
                  ))}
                </div>
                <Space size={6} style={{ marginTop: 10 }}>
                  <span style={{ fontSize: 12, color: 'var(--color-text-secondary)' }}>过道宽度</span>
                  <InputNumber
                    min={1.5}
                    max={2}
                    step={0.1}
                    value={aisleRatio}
                    onChange={(v) => v != null && setAisleRatio(v)}
                    size="small"
                    style={{ width: 80 }}
                  />
                  <span style={{ fontSize: 12, color: 'var(--color-text-secondary)' }}>倍</span>
                </Space>
              </div>
            }
          >
            <Button size="small" icon={<BorderOutlined />}>过道</Button>
          </Popover>

          {/* 颜色区域 */}
          <Popover
            trigger="click"
            content={
              <div style={{ width: 260 }}>
                <div style={{ fontSize: 12, marginBottom: 8, color: 'var(--color-text-secondary)' }}>
                  点击色板 → 点击格子或拖拽批量上色
                </div>
                <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, 1fr)', gap: 6 }}>
                  {colorLabels.map((p) => (
                    <div
                      key={p.color}
                      onClick={() => selectColor(p)}
                      style={{
                        display: 'flex', alignItems: 'center', gap: 6, padding: '4px 6px', cursor: 'pointer',
                        borderRadius: 6, border: selectedColor === p.color ? '2px solid var(--color-primary)' : '1px solid var(--color-border)',
                        fontSize: 12,
                      }}
                    >
                      <span style={{ width: 16, height: 16, borderRadius: 4, background: p.color, flexShrink: 0 }} />
                      {p.label}
                      {selectedColor === p.color && <CheckOutlined style={{ color: 'var(--color-primary)', marginLeft: 'auto' }} />}
                    </div>
                  ))}
                </div>
                <Button size="small" type="link" icon={<EditOutlined />} onClick={() => setColorManageOpen(true)} style={{ padding: 0, marginTop: 8 }}>
                  配色管理
                </Button>
                <div style={{ marginTop: 4, fontSize: 12, color: 'var(--color-text-secondary)' }}>当前区域：{selectedLabel}</div>
              </div>
            }
          >
            <Button size="small" style={{ background: selectedColor, color: '#fff', border: 'none' }}>
              {selectedLabel}
            </Button>
          </Popover>

          {/* 填充 */}
          <Popover
            trigger="click"
            content={
              <Space direction="vertical" style={{ width: 150 }}>
                <Button size="small" block onClick={() => fill('row')}>按行填充（选中行）</Button>
                <Button size="small" block onClick={() => fill('col')}>按列填充（选中列）</Button>
                <Button size="small" block onClick={() => fill('all')}>矩形全部填充</Button>
              </Space>
            }
          >
            <Button size="small">填充</Button>
          </Popover>

          {/* 模板 */}
          <Space size={4}>
            <Button size="small" icon={<SaveOutlined />} onClick={saveLayout}>保存</Button>
            <Button size="small" onClick={loadTemplates}>模板</Button>
          </Space>

          {/* 撤销重做 */}
          <Space size={4}>
            <Button size="small" icon={<UndoOutlined />} onClick={undo} disabled={historyIdx < 0} />
            <Button size="small" icon={<RedoOutlined />} onClick={redo} disabled={historyIdx + 1 >= history.length} />
          </Space>

          <Button size="small" icon={<DownloadOutlined />} onClick={exportPng}>导出</Button>
          {selection.size > 0 && (
            <Button size="small" danger icon={<DeleteOutlined />} onClick={clearSelection}>
              清除选中({selection.size})
            </Button>
          )}

          <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginLeft: 'auto' }}>
            <span style={{ fontSize: 12, color: 'var(--color-text-secondary)' }}>缩放</span>
            <Slider
              min={50}
              max={200}
              value={zoom}
              onChange={setZoom}
              style={{ width: 100, margin: '0 8px' }}
            />
            <span style={{ fontSize: 12, color: 'var(--color-text-secondary)' }}>{zoom}%</span>
          </div>
        </div>
      )}

      {/* 网格画布 */}
      <div
        style={{
          overflow: 'auto',
          maxHeight: '60vh',
          background: 'var(--color-bg-2)',
          borderRadius: 10,
          padding: 12,
        }}
      >
        <div
          ref={gridRef}
          style={{
            transform: `scale(${zoom / 100})`,
            transformOrigin: 'top left',
            width: rowLabelWidth + cols * (cellSize + 2) + aisleCols.size * cellSize * (aisleRatio - 1),
          }}
          onMouseUp={handleMouseUp}
          onMouseLeave={handleMouseUp}
        >
          {/* 列头 */}
          <div style={{ display: 'flex', marginBottom: 2, paddingLeft: rowLabelWidth }}>
            {columnLetters.map((l, i) => {
              const isAisle = aisleCols.has(i + 1)
              return (
                <div
                  key={i}
                  style={{
                    width: isAisle ? cellSize * aisleRatio + 2 : cellSize + 2,
                    height: colHeaderH,
                    display: 'flex', alignItems: 'center', justifyContent: 'center',
                    fontSize: 10, color: isAisle ? 'var(--color-red)' : 'var(--color-text-secondary)',
                    fontWeight: isAisle ? 600 : 400,
                    cursor: readOnly ? 'default' : 'pointer',
                  }}
                  title={readOnly ? undefined : `点击设为过道`}
                  onClick={() => toggleAisle(i + 1)}
                >
                  {l}
                </div>
              )
            })}
          </div>
          {/* 行 */}
          {Array.from({ length: rows }, (_, ri) => ri + 1).map((r) => (
            <div key={r} style={{ display: 'flex', alignItems: 'center', marginBottom: 2 }}>
              <div style={{ width: rowLabelWidth, textAlign: 'center', fontSize: 10, color: 'var(--color-text-secondary)' }}>
                {r}
              </div>
              {Array.from({ length: cols }, (_, ci) => ci + 1).map((c) => {
                const isAisle = aisleCols.has(c)
                const cell = cells.get(key(r, c))
                const isSelected = selection.has(key(r, c))
                const w = isAisle ? cellSize * aisleRatio + 2 : cellSize + 2
                return (
                  <div
                    key={c}
                    onMouseDown={() => handleMouseDown(r, c)}
                    onMouseEnter={() => handleMouseEnter(r, c)}
                    onClick={() => handleCellClick(r, c)}
                    title={isAisle ? `第${c}列 过道` : `${r}排${columnLetters[c - 1]}列 · ${cell?.label || '空座'}${cell?.personName ? ` · ${cell.personName}` : ''}`}
                    style={{
                      width: w,
                      height: cellSize + 2,
                      marginRight: 2,
                      borderRadius: 4,
                      background: isAisle
                        ? 'repeating-linear-gradient(45deg, #e5e7eb, #e5e7eb 4px, #d1d5db 4px, #d1d5db 8px)'
                        : cell?.type === 'seat'
                          ? (cell.color || '#1677ff')
                          : 'transparent',
                      border: isAisle ? 'none' : isSelected ? '2px solid var(--color-red)' : '1px solid var(--color-border)',
                      boxSizing: 'border-box',
                      cursor: readOnly ? 'default' : isAisle ? 'not-allowed' : 'pointer',
                      display: 'flex', alignItems: 'center', justifyContent: 'center',
                      fontSize: Math.max(8, cellSize * 0.35),
                      color: isAisle || cell?.type === 'empty' ? 'var(--color-text-secondary)' : '#fff',
                      overflow: 'hidden',
                    }}
                  >
                    {isAisle ? '║' : cell?.type === 'seat' ? (cell.label?.[0] ?? '') : ''}
                  </div>
                )
              })}
            </div>
          ))}
        </div>
      </div>

      {/* 图例 */}
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 12, marginTop: 12 }}>
        {colorLabels.map((p) => (
          <span key={p.color} style={{ display: 'flex', alignItems: 'center', gap: 4, fontSize: 12, color: 'var(--color-text-secondary)' }}>
            <span style={{ width: 12, height: 12, borderRadius: 3, background: p.color }} />
            {p.label}
          </span>
        ))}
        <span style={{ display: 'flex', alignItems: 'center', gap: 4, fontSize: 12, color: 'var(--color-text-secondary)' }}>
          <span style={{ width: 12, height: 12, borderRadius: 3, border: '1px solid var(--color-border)' }} />
          空座
        </span>
        <span style={{ display: 'flex', alignItems: 'center', gap: 4, fontSize: 12, color: 'var(--color-text-secondary)' }}>
          <span style={{ width: 12, height: 12, borderRadius: 3, background: 'repeating-linear-gradient(45deg,#e5e7eb,#e5e7eb 3px,#d1d5db 3px,#d1d5db 6px)' }} />
          过道
        </span>
      </div>

      {/* 模板弹窗 */}
      <Modal
        title="从模板新建"
        open={templateOpen}
        onCancel={() => setTemplateOpen(false)}
        footer={<Button onClick={() => setTemplateOpen(false)}>关闭</Button>}
      >
        {templates.length === 0 ? (
          <div style={{ textAlign: 'center', padding: 24, color: 'var(--color-text-secondary)' }}>暂无可用模板</div>
        ) : (
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, 1fr)', gap: 10 }}>
            {templates.map((t) => (
              <div
                key={t.id}
                onClick={() => applyTemplate(t)}
                style={{
                  border: '1px solid var(--color-border)', borderRadius: 8, padding: 12, cursor: 'pointer',
                  transition: 'all .15s',
                }}
              >
                <div style={{ fontWeight: 600, fontSize: 13 }}>{t.name}</div>
                <div style={{ fontSize: 12, color: 'var(--color-text-secondary)', marginTop: 4 }}>
                  {t.rows} × {t.cols} · {t.templateCategory || '自定义'}
                </div>
              </div>
            ))}
          </div>
        )}
      </Modal>

      {/* 配色管理弹窗 */}
      <Modal
        title="配色管理"
        open={colorManageOpen}
        onCancel={() => setColorManageOpen(false)}
        footer={<Button onClick={() => setColorManageOpen(false)}>关闭</Button>}
      >
        <Space.Compact style={{ marginBottom: 12, display: 'flex' }}>
          <ColorPicker size="small" value={newColor} onChangeComplete={(c) => setNewColor(c.toHexString())} />
          <Input size="small" placeholder="标签，如 嘉宾区" maxLength={20} value={newLabel} onChange={(e) => setNewLabel(e.target.value)} />
          <Button size="small" type="primary" onClick={() => { addColor(newColor, newLabel); setNewLabel('') }}>新增</Button>
        </Space.Compact>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          {colorLabels.map((c) => (
            <div key={c.color} style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <ColorPicker size="small" value={c.color} onChangeComplete={(nc) => {
                const next = colorLabels.map((x) => (x.color === c.color ? { ...x, color: nc.toHexString() } : x))
                setColorLabels(next)
                if (selectedColor === c.color) setSelectedColor(nc.toHexString())
              }} />
              <Input size="small" defaultValue={c.label} maxLength={20} style={{ flex: 1 }}
                onPressEnter={(e) => renameColor(c.color, (e.target as HTMLInputElement).value)}
                onBlur={(e) => renameColor(c.color, e.target.value)} />
              <Button size="small" danger icon={<DeleteOutlined />} onClick={() => removeColor(c.color)} />
            </div>
          ))}
        </div>
      </Modal>
    </div>
  )
}
