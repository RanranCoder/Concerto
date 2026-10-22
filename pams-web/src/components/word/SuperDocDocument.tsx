import { forwardRef, useEffect, useImperativeHandle, useRef, useState } from 'react'
import { SuperDoc } from 'superdoc'
import 'superdoc/style.css'
import './superdoc.css'

export interface SuperDocDocumentHandle {
  exportDocx: () => Promise<Blob>
}
interface Props {
  document: Blob
  editing: boolean
  disabled?: boolean
  author: string
  onChange?: () => void
  onReady?: (ready: boolean) => void
}

/** One native DOCX engine for both reading and editing. Never falls back to HTML. */
export default forwardRef<SuperDocDocumentHandle, Props>(function SuperDocDocument(
  { document, editing, disabled = false, author, onChange, onReady }, ref,
) {
  const host = useRef<HTMLDivElement>(null)
  const toolbar = useRef<HTMLDivElement>(null)
  const instance = useRef<SuperDoc | null>(null)
  const callbacks = useRef({ onChange, onReady })
  callbacks.current = { onChange, onReady }
  const [ready, setReady] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [attempt, setAttempt] = useState(0)
  useImperativeHandle(ref, () => ({
    exportDocx: async () => {
      if (!instance.current || !ready || error) throw new Error('文档尚未准备好')
      const blob = await instance.current.export({ triggerDownload: false, commentsType: 'external' })
      if (!blob?.size) throw new Error('文档导出为空，请重试')
      return blob
    },
  }), [ready, error])
  useEffect(() => {
    if (!host.current || !toolbar.current) return
    let disposed = false, loaded = false
    setReady(false)
    setError('')
    setNotice('')
    callbacks.current.onReady?.(false)
    const fail = () => {
      if (disposed) return
      setError('文档加载或编辑失败，请重试。未保存的内容不会覆盖原稿。')
      callbacks.current.onReady?.(false)
    }
    let doc: SuperDoc | undefined
    try {
      doc = new SuperDoc({
        selector: host.current,
        document: new File([document], '策划书.docx', {
          type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
        }),
        documentMode: editing ? 'editing' : 'viewing',
        user: { name: author },
        contained: true,
        telemetry: { enabled: false },
        licenseKey: import.meta.env.VITE_SUPERDOC_LICENSE_KEY || undefined,
        ui: {
          toolbar: editing ? { container: toolbar.current } : false,
          search: true,
          comments: true,
        },
        viewing: { comments: true, trackedChanges: 'markup' },
        onReady: ({ superdoc }) => {
          if (disposed) return
          loaded = true
          superdoc.setZoomMode('fit-width')
          setReady(true)
          callbacks.current.onReady?.(true)
        },
        onEditorUpdate: () => {
          if (!disposed && loaded && editing) callbacks.current.onChange?.()
        },
        onCommentsUpdate: () => {
          if (!disposed && loaded && editing) callbacks.current.onChange?.()
        },
        onContentError: fail,
        onException: (event) => {
          if (disposed || ('code' in event && event.code === 'edit-rejected')) return
          if ('severity' in event && event.severity === 'warn') return
          if (!loaded) fail()
          else setNotice('此操作未完成，请重试。已编辑的内容仍保留。')
        },
      })
      instance.current = doc
    } catch { fail() }
    let previousWidth = 0
    const resize = new ResizeObserver(([entry]) => {
      const width = entry.contentRect.width
      if (Math.abs(width - previousWidth) < 1) return
      previousWidth = width
      if (loaded && !disposed) doc?.setZoomMode('fit-width')
    })
    resize.observe(host.current)
    return () => {
      disposed = true
      resize.disconnect()
      instance.current = null
      doc?.destroy()
      host.current?.replaceChildren()
      toolbar.current?.replaceChildren()
    }
  }, [document, editing, author, attempt])
  useEffect(() => {
    if (host.current) host.current.inert = disabled
    if (toolbar.current) toolbar.current.inert = disabled
    if (ready) instance.current?.setDocumentMode(editing && !disabled ? 'editing' : 'viewing')
  }, [disabled, ready, editing])
  return (
    <section className={`pams-superdoc ${editing ? 'is-editing' : 'is-viewing'}`}
      data-document-engine="superdoc" data-ready={ready && !error} aria-label={editing ? '策划书文档编辑器' : '策划书文档预览'}>
      <div ref={toolbar} className="pams-superdoc-toolbar" hidden={!editing} />
      {notice && <p role="status">{notice}</p>}
      {error ? <div className="pams-superdoc-error" role="alert">
        <p>{error}</p><button type="button" onClick={() => setAttempt(n => n + 1)}>重新加载文档</button>
      </div> : !ready && <p className="pams-superdoc-loading" role="status">正在打开文档…</p>}
      <div ref={host} className="pams-superdoc-canvas" />
    </section>
  )
})
