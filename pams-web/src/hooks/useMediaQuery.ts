import { useEffect, useState } from 'react'

/**
 * 极简媒体查询 hook（灵思 UI v2 §7「风险与降级」用）。
 *
 * <p>用它而不是 CSS 媒体查询的原因：这里的降级是**行为差异**，不是样式差异 ——
 * 窄屏下产物面板没有空间做分屏，必须改走 Drawer 弹层。CSS 做不到"换一个容器渲染"。
 *
 * <p>SSR 无关（本项目纯 CSR），初始化直接读 `window` 即可，避免首帧闪烁。
 */
export function useMediaQuery(query: string): boolean {
  const [matches, setMatches] = useState(() =>
    typeof window === 'undefined' ? false : window.matchMedia(query).matches,
  )

  useEffect(() => {
    const mql = window.matchMedia(query)
    const onChange = (e: MediaQueryListEvent) => setMatches(e.matches)
    setMatches(mql.matches)
    mql.addEventListener('change', onChange)
    return () => mql.removeEventListener('change', onChange)
  }, [query])

  return matches
}

/**
 * 窄屏（分屏放不下）—— 低于 1200px 时不再做左右分屏。
 *
 * 阈值算法：1200 − 页面左右 padding 48 − 任务栏 252 − 栏间距 16 = 884，
 * 再让出 384px 的面板宽度 → 对话区还剩约 500px，正好是"内容 760px 居中"
 * 还能读的下限。再窄就该走 Drawer 了。
 */
export function useNarrow(): boolean {
  return useMediaQuery('(max-width: 1199px)')
}
