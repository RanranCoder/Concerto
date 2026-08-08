// pams-web/src/components/glass/Masonry.tsx
import { Children, useLayoutEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { columnCountFor, distributeToColumns } from './masonry.utils'

interface MasonryProps {
  children: ReactNode[]
  gap?: number
  minColWidth?: number
  className?: string
}

export default function Masonry({ children, gap = 16, minColWidth = 340, className }: MasonryProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  const [cols, setCols] = useState(1)
  const [assign, setAssign] = useState<number[]>([])

  // 用 useMemo 稳定 cards 引用，避免 Children.toArray 每次 render 都生成新数组导致测高 effect 死循环
  const cards = useMemo(() => Children.toArray(children), [children])

  // 容器宽度 → 列数
  useLayoutEffect(() => {
    const el = containerRef.current
    if (!el) return
    const update = () => setCols(columnCountFor(el.clientWidth, minColWidth, gap))
    update()
    const ro = new ResizeObserver(update)
    ro.observe(el)
    return () => ro.disconnect()
  }, [minColWidth, gap])

  // 测量每张卡实际高度 → 最短列优先分配
  const itemRefs = useRef<Array<HTMLDivElement | null>>([])
  useLayoutEffect(() => {
    itemRefs.current.length = cards.length
    const heights = itemRefs.current.map((el) => el?.offsetHeight ?? 0)
    setAssign(distributeToColumns(heights, cols, gap))
  }, [cols, cards, gap])

  // 首次渲染尚未测量时，全部放第 0 列以便测高（layout effect 会在绘制前完成分配，无闪烁）
  const eff = assign.length === cards.length ? assign : cards.map(() => 0)

  return (
    <div ref={containerRef} className={className} style={{ display: 'flex', gap, alignItems: 'flex-start' }}>
      {Array.from({ length: cols }, (_, c) => (
        <div key={c} style={{ display: 'flex', flexDirection: 'column', gap, flex: 1, minWidth: 0 }}>
          {cards.map((card, i) =>
            eff[i] === c ? (
              <div key={i} ref={(el) => { itemRefs.current[i] = el }}>
                {card}
              </div>
            ) : null
          )}
        </div>
      ))}
    </div>
  )
}
