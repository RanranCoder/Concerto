import { useEffect, useRef, useState } from 'react'
import { Button, Empty, Space, Spin } from 'antd'
import { AnnotationMode, getDocument, GlobalWorkerOptions } from 'pdfjs-dist'
import type { PDFDocumentLoadingTask, PDFDocumentProxy, RenderTask } from 'pdfjs-dist'
import workerSrc from 'pdfjs-dist/build/pdf.worker.min.mjs?url'

GlobalWorkerOptions.workerSrc = workerSrc

export default function PdfFilePreview({ blob }: { blob: Blob }) {
  const container = useRef<HTMLDivElement>(null)
  const canvas = useRef<HTMLCanvasElement>(null)
  const [document, setDocument] = useState<PDFDocumentProxy | null>(null)
  const [pageNumber, setPageNumber] = useState(1)
  const [busy, setBusy] = useState(true)
  const [error, setError] = useState(false)

  useEffect(() => {
    let disposed = false
    let task: PDFDocumentLoadingTask | undefined
    async function load() {
      try {
        const data = await blob.arrayBuffer()
        if (disposed) return
        // Canvas preview only, with no viewer scripting or interactive form layer.
        task = getDocument({ data, enableXfa: false, maxImageSize: 16_000_000 })
        const nextDocument = await task.promise
        if (!disposed) setDocument(nextDocument)
      } catch {
        if (!disposed) setError(true)
      }
    }
    void load()
    return () => {
      disposed = true
      void task?.destroy().catch(() => {})
    }
  }, [blob])

  useEffect(() => {
    if (!document || !canvas.current || !container.current) return
    const surface = canvas.current
    const availableWidth = container.current.clientWidth || 800
    let disposed = false
    let render: RenderTask | undefined
    setBusy(true)
    async function draw(pdf: PDFDocumentProxy) {
      try {
        const page = await pdf.getPage(pageNumber)
        if (disposed) return
        const natural = page.getViewport({ scale: 1 })
        const scale = Math.min(2, availableWidth / natural.width, 2400 / natural.height)
        const viewport = page.getViewport({ scale })
        surface.width = Math.ceil(viewport.width)
        surface.height = Math.ceil(viewport.height)
        render = page.render({ canvas: surface, viewport, annotationMode: AnnotationMode.DISABLE })
        await render.promise
        if (!disposed) setBusy(false)
      } catch {
        if (!disposed) setError(true)
      }
    }
    void draw(document)
    return () => {
      disposed = true
      render?.cancel()
    }
  }, [document, pageNumber])

  if (error) return <Empty description="PDF 预览失败，请下载后查看" />
  return (
    <div ref={container}>
      {document && document.numPages > 1 && (
        <Space style={{ marginBottom: 12 }}>
          <Button disabled={pageNumber <= 1 || busy} onClick={() => setPageNumber(pageNumber - 1)}>上一页</Button>
          <span aria-live="polite">{pageNumber} / {document.numPages}</span>
          <Button disabled={pageNumber >= document.numPages || busy} onClick={() => setPageNumber(pageNumber + 1)}>下一页</Button>
        </Space>
      )}
      {busy && <Spin />}
      <canvas ref={canvas} aria-label={`PDF 第 ${pageNumber} 页`} style={{ display: busy ? 'none' : 'block', maxWidth: '100%', margin: '0 auto' }} />
    </div>
  )
}
