import { useCallback, useEffect, useRef, useState } from 'react'
import { Button, Spin } from 'antd'
import { FullscreenOutlined, UploadOutlined } from '@ant-design/icons'
import { A4_HEIGHT, A4_WIDTH, DOCUMENT_CSS, paginateA4 } from './a4Pagination'

interface Props {
  blob: Blob | null
  loading?: boolean
  name: string
}
const FRAME_DOCUMENT = `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; img-src data: blob:; font-src data: blob:"></head><body></body></html>`

/** Read-only DOCX preview. All styles stay inside an isolated, white A4 document. */
export default function A4DocumentPreview({
  blob,
  loading = false,
  name,
}: Props) {
  const frame = useRef<HTMLIFrameElement>(null),
    canvas = useRef<HTMLDivElement>(null),
    file = useRef<HTMLInputElement>(null)
  const [ready, setReady] = useState(0),
    [busy, setBusy] = useState(false),
    [error, setError] = useState('')
  const [local, setLocal] = useState<{ blob: Blob; name: string } | null>(null)
  const [zoom, setZoom] = useState('fit'),
    [scale, setScale] = useState(1),
    [height, setHeight] = useState(A4_HEIGHT)
  const [pages, setPages] = useState(0),
    [outline, setOutline] = useState<Array<{ title: string; top: number }>>([])
  const [expanded, setExpanded] = useState(false)
  const fit = useCallback(() => {
    setScale(
      zoom === 'fit'
        ? Math.min(
            1,
            Math.max(
              0.1,
              ((canvas.current?.clientWidth ?? A4_WIDTH + 48) - 48) / A4_WIDTH,
            ),
          )
        : Number(zoom) / 100,
    )
  }, [zoom])
  useEffect(() => {
    fit()
    const observer = new ResizeObserver(fit)
    if (canvas.current) observer.observe(canvas.current)
    return () => observer.disconnect()
  }, [fit])
  useEffect(() => {
    const escape = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setExpanded(false)
    }
    if (expanded) window.addEventListener('keydown', escape)
    return () => window.removeEventListener('keydown', escape)
  }, [expanded])
  useEffect(() => {
    const data = local?.blob ?? blob,
      doc = frame.current?.contentDocument
    if (!data || !doc || !ready) return
    let cancelled = false
    setBusy(true)
    setError('')
    setPages(0)
    setOutline([])
    const render = async () => {
      const { renderAsync } = await import('docx-preview')
      if (cancelled) return
      const staging = doc.createElement('div'),
        styles = doc.createElement('div')
      await renderAsync(await data.arrayBuffer(), staging, styles, {
        className: 'activity-docx',
        inWrapper: false,
        ignoreWidth: true,
        ignoreHeight: true,
        breakPages: true,
        ignoreLastRenderedPageBreak: false,
        useBase64URL: true,
        renderHeaders: true,
        renderFooters: true,
        renderAltChunks: false,
      })
      if (cancelled) return
      doc.body.replaceChildren(...staging.childNodes)
      doc.head
        .querySelectorAll('[data-document-styles]')
        .forEach((n) => n.remove())
      styles.setAttribute('data-document-styles', '')
      doc.head.append(styles)
      doc.body
        .querySelectorAll('script,iframe,object,embed,link,base')
        .forEach((n) => n.remove())
      doc.body.querySelectorAll('*').forEach((n) => {
        for (const a of [...n.attributes])
          if (/^on/i.test(a.name)) n.removeAttribute(a.name)
        if (n.tagName === 'A') {
          n.removeAttribute('href')
          n.removeAttribute('target')
        }
        if (
          n.tagName === 'IMG' &&
          !/^(data:|blob:)/.test(n.getAttribute('src') ?? '')
        )
          n.remove()
      })
      const style = doc.createElement('style')
      style.setAttribute('data-document-styles', '')
      style.textContent = DOCUMENT_CSS
      doc.head.append(style)
      await doc.fonts.ready
      await Promise.all(
        [...doc.images].map((img) => img.decode().catch(() => {})),
      )
      if (cancelled) return
      const sheets = paginateA4(doc)
      setPages(sheets.length)
      const last = sheets.at(-1)
      setHeight(last ? last.offsetTop + last.offsetHeight : A4_HEIGHT)
      setOutline(
        [
          ...doc.querySelectorAll<HTMLElement>(
            'article h1,article h2,article h3,article p',
          ),
        ]
          .filter((n) =>
            /^([一二三四五六七八九十]+、|[1-9][.、])/.test(
              n.textContent?.trim() ?? '',
            ),
          )
          .slice(0, 40)
          .map((n) => ({
            title: n.textContent!.trim(),
            top: n.getBoundingClientRect().top,
          })),
      )
      fit()
    }
    render()
      .catch(() => {
        if (!cancelled) {
          doc.body.replaceChildren()
          setError(
            'Word 文档预览失败，请重新打开有效的 .docx 文件，或下载后使用 Word 查看。',
          )
        }
      })
      .finally(() => {
        if (!cancelled) setBusy(false)
      })
    return () => {
      cancelled = true
    }
  }, [blob, local, ready]) // Zoom changes only transform the completed paper; they never re-render the document.

  return (
    <div className={`activity-word-viewer${expanded ? ' expanded' : ''}`}>
      <aside className="activity-word-outline">
        <h3>文档目录</h3>
        {outline.map((item, i) => (
          <button
            key={i}
            onClick={() =>
              canvas.current?.scrollTo({
                top: item.top * scale,
                behavior: matchMedia('(prefers-reduced-motion: reduce)').matches
                  ? 'instant'
                  : 'smooth',
              })
            }
          >
            {item.title}
          </button>
        ))}
        <small>
          A4 纵向
          <br />
          210 × 297 mm
        </small>
      </aside>
      <div className="activity-word-main">
        <div className="activity-word-toolbar">
          <span>{local?.name ?? name}</span>
          <div>
            {local && (
              <Button type="link" size="small" onClick={() => setLocal(null)}>
                返回当前策划书
              </Button>
            )}
            <Button
              icon={<UploadOutlined />}
              onClick={() => file.current?.click()}
            >
              本地试阅
            </Button>
            <label>
              缩放{' '}
              <select
                aria-label="文档缩放"
                value={zoom}
                onChange={(e) => setZoom(e.target.value)}
              >
                {['fit', '75', '100', '125', '150'].map((z) => (
                  <option key={z} value={z}>
                    {z === 'fit' ? '适合宽度' : z + '%'}
                  </option>
                ))}
              </select>
            </label>
            <Button
              type="text"
              icon={<FullscreenOutlined />}
              aria-label={expanded ? '收起阅读区' : '展开阅读区'}
              onClick={() => setExpanded(!expanded)}
            />
          </div>
        </div>
        {local && (
          <p className="activity-inline-note">
            本地试阅，文件未上传，也不会替换已保存的策划书。
          </p>
        )}
        {error && (
          <p role="alert" className="activity-inline-note error">
            {error}
          </p>
        )}
        <Spin spinning={busy || loading}>
          <div
            className="activity-word-canvas"
            ref={canvas}
            tabIndex={0}
            aria-label="A4 策划书预览"
          >
            <div
              className="activity-word-scaled"
              style={{ width: A4_WIDTH * scale, height: height * scale }}
            >
              <iframe
                ref={frame}
                sandbox="allow-same-origin"
                srcDoc={FRAME_DOCUMENT}
                onLoad={() => setReady((v) => v + 1)}
                title="白色 A4 策划书文档"
                style={{
                  width: A4_WIDTH,
                  height,
                  transform: `scale(${scale})`,
                }}
              />
            </div>
          </div>
        </Spin>
        <footer className="activity-word-status">
          <span>{pages ? `共 ${pages} 页` : '准备预览'}</span>
          <span>A4 纵向 · 白色纸张</span>
          <span>{Math.round(scale * 100)}%</span>
        </footer>
      </div>
      <input
        ref={file}
        type="file"
        accept=".docx,.doc"
        hidden
        onChange={(e) => {
          const selected = e.target.files?.[0]
          e.target.value = ''
          if (!selected) return
          if (!/\.docx$/i.test(selected.name)) {
            setError('旧版 .doc 请先在 Word 中另存为 .docx 后再打开。')
            return
          }
          setLocal({ blob: selected, name: selected.name })
        }}
      />
    </div>
  )
}
