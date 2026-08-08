/** 瀑布流"最短列优先"分配：给定每项高度与列数，返回每项应放入的列下标。 */
export function distributeToColumns(heights: number[], columnCount: number, gap: number): number[] {
  const colHeights = new Array<number>(columnCount).fill(0)
  return heights.map((h) => {
    let minCol = 0
    for (let c = 1; c < columnCount; c++) {
      if (colHeights[c] < colHeights[minCol]) minCol = c
    }
    colHeights[minCol] += h + gap
    return minCol
  })
}

/** 响应式列数：容器宽度 → 列数，至少 1。 */
export function columnCountFor(width: number, minColWidth: number, gap: number): number {
  if (width <= 0) return 1
  return Math.max(1, Math.floor((width + gap) / (minColWidth + gap)))
}
