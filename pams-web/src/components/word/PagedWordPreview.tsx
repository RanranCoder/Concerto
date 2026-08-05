import { useEffect, useRef, useState } from 'react'
import { Button } from 'antd'
import { LeftOutlined, RightOutlined } from '@ant-design/icons'
import { planYearFromMeta, type PlanMeta } from './planTemplate'

interface PagedWordPreviewProps {
  /** HTML 字符串（自由编辑内容） */
  content: string
  meta?: PlanMeta
}

/** A4纸张内容区域高度（扣除上下边距后约 397px） */
const PAGE_HEIGHT = 397

/**
 * 分页 Word 预览组件
 * 将自由编辑 HTML 按 A4 纸张高度分页显示
 */
export default function PagedWordPreview({ content, meta }: PagedWordPreviewProps) {
  const [currentPage, setCurrentPage] = useState(0)
  const [pages, setPages] = useState<React.ReactNode[][]>([])
  const measureRef = useRef<HTMLDivElement>(null)

  // 将 HTML content 解析为可渲染的 React 节点
  // 使用 dangerouslySetInnerHTML 在测量容器和显示容器中渲染
  const contentHtml = content || '<div style="color:#999;text-align:center;padding:32px">暂无策划书内容</div>'

  // 分页逻辑：测量容器高度，按页高分组
  useEffect(() => {
    if (!measureRef.current) return

    const elements = measureRef.current.children
    if (elements.length === 0) return

    // 将子元素按高度分组为页
    const pageGroups: number[][] = [[]]
    let currentPageHeight = 0
    let pageIndex = 0

    for (let i = 0; i < elements.length; i++) {
      const el = elements[i] as HTMLElement
      const height = el.offsetHeight

      if (currentPageHeight + height > PAGE_HEIGHT && pageGroups[pageIndex].length > 0) {
        pageIndex++
        pageGroups.push([])
        currentPageHeight = 0
      }

      pageGroups[pageIndex].push(i)
      currentPageHeight += height
    }

    // 为每页创建 React 节点
    const childHtmls: string[] = []
    for (let i = 0; i < elements.length; i++) {
      childHtmls.push((elements[i] as HTMLElement).outerHTML)
    }

    const pageNodes: React.ReactNode[][] = pageGroups.map((indices) =>
      indices.map((idx) => (
        <div key={idx} dangerouslySetInnerHTML={{ __html: childHtmls[idx] }} />
      )),
    )

    setPages(pageNodes)
    setCurrentPage(0)
  }, [contentHtml])

  const year = planYearFromMeta(meta)
  const orgName = meta?.orgName || '信息工程学院党建办公室'

  // 完整内容（含落款）
  const fullContent = [
    contentHtml,
    `<div class="word-sign" style="text-align:right;margin-top:32px"><div>${orgName}</div><div>${year}年</div></div>`,
  ].join('')

  return (
    <div>
      {/* 隐藏的测量容器 */}
      <div
        ref={measureRef}
        style={{
          position: 'absolute',
          visibility: 'hidden',
          width: '210mm',
          padding: '96px 120px',
          boxSizing: 'border-box',
        }}
        dangerouslySetInnerHTML={{ __html: fullContent }}
      />

      {/* 显示当前页 */}
      <div className="word-page">
        <div style={{ padding: '96px 120px' }}>
          {pages.length > 0 ? (pages[currentPage] || <div>加载中...</div>) : (
            <div dangerouslySetInnerHTML={{ __html: fullContent }} />
          )}
        </div>
      </div>

      {/* 分页导航 */}
      {pages.length > 1 && (
        <div className="word-pagination">
          <Button icon={<LeftOutlined />} disabled={currentPage === 0} onClick={() => setCurrentPage(currentPage - 1)}>
            上一页
          </Button>
          <span className="word-pagination-info">{currentPage + 1} / {pages.length}</span>
          <Button disabled={currentPage === pages.length - 1} onClick={() => setCurrentPage(currentPage + 1)}>
            下一页 <RightOutlined />
          </Button>
        </div>
      )}
    </div>
  )
}
