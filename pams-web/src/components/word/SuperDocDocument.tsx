import { forwardRef, useEffect, useImperativeHandle, useRef, useState } from 'react'
import { SuperDoc } from 'superdoc'
import SuperDocRibbon from './SuperDocRibbon'
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
  const root = useRef<HTMLElement>(null)
  const instance = useRef<SuperDoc | null>(null)
  const callbacks = useRef({ onChange, onReady })
  callbacks.current = { onChange, onReady }
  const [ready, setReady] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [attempt, setAttempt] = useState(0)
  const [engine, setEngine] = useState<SuperDoc | null>(null)
  const [editMode, setEditMode] = useState<'editing' | 'suggesting'>('editing')
  const [fullscreen, setFullscreen] = useState(false)
  const [toolBusy, setToolBusy] = useState(false)
  useImperativeHandle(ref, () => ({
    exportDocx: async () => {
      if (!instance.current || !ready || error || toolBusy) throw new Error('文档尚未准备好')
      const blob = await instance.current.export({ triggerDownload: false, commentsType: 'external' })
      if (!blob?.size) throw new Error('文档导出为空，请重试')
      return blob
    },
  }), [ready, error, toolBusy])
  useEffect(() => {
    if (!host.current) return
    let disposed = false, loaded = false
    setReady(false)
    setError('')
    setNotice('')
    setEditMode('editing')
    setEngine(null)
    setToolBusy(false)
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
          toolbar: false,
          search: { strings: {
            findPlaceholder: '查找内容', findAriaLabel: '查找内容', replacePlaceholder: '替换为', replaceAriaLabel: '替换内容',
            noResults: '未找到匹配内容', previousMatchTitle: '上一处（Shift+Enter）', previousMatchAriaLabel: '上一处匹配',
            nextMatchTitle: '下一处（Enter）', nextMatchAriaLabel: '下一处匹配', closeTitle: '关闭（Esc）', closeAriaLabel: '关闭查找替换',
            replace: '替换', replaceAll: '全部替换', toggleReplaceTitle: '展开或收起替换', toggleReplaceAriaLabel: '展开或收起替换',
            matchCaseAriaLabel: '区分大小写', regexAriaLabel: '使用正则表达式', invalidPattern: '表达式无效', ignoreDiacriticsAriaLabel: '忽略重音符号',
          } },
          comments: { layout: 'auto' },
        },
        measurementUnit: 'cm',
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
          if (loaded && 'diagnosticStage' in event && event.diagnosticStage === 'render' && String(event.error).includes('retained viewport action failed')) {
            setNotice('页面布局已变化，请重新选择正文后继续操作。')
            return
          }
          if (!loaded) fail()
          else setNotice('此操作未完成，请重试。已编辑的内容仍保留。')
        },
      })
      instance.current = doc
      setEngine(doc)
    } catch { fail() }
    return () => {
      disposed = true
      instance.current = null
      doc?.destroy()
      host.current?.replaceChildren()
    }
  }, [document, editing, author, attempt])
  useEffect(() => {
    if (host.current) host.current.inert = disabled
    if (ready) instance.current?.setDocumentMode(editing && !disabled ? editMode : 'viewing')
  }, [disabled, ready, editing, editMode])
  useEffect(() => {
    callbacks.current.onReady?.(ready && !error && !toolBusy)
  }, [ready, error, toolBusy])
  useEffect(() => {
    const syncFullscreen = () => setFullscreen(!!window.document.fullscreenElement && !!root.current?.closest(':fullscreen'))
    window.document.addEventListener('fullscreenchange', syncFullscreen)
    return () => window.document.removeEventListener('fullscreenchange', syncFullscreen)
  }, [])
  const toggleFullscreen = async () => {
    try {
      if (window.document.fullscreenElement) await window.document.exitFullscreen()
      else await (root.current?.closest<HTMLElement>('.activity-plan-inline-editor') ?? root.current)?.requestFullscreen()
    } catch { setNotice('浏览器未能进入全屏，请重试。') }
  }
  return (
    <section ref={root} className={`pams-superdoc ${editing ? 'is-editing' : 'is-viewing'} ${fullscreen ? 'is-fullscreen' : ''}`}
      data-document-engine="superdoc" data-ready={ready && !error} aria-label={editing ? '策划书文档编辑器' : '策划书文档预览'}>
      {editing && <SuperDocRibbon superdoc={engine} ready={ready && !error} disabled={disabled} mode={editMode}
        onModeChange={setEditMode} fullscreen={fullscreen} onFullscreen={() => void toggleFullscreen()} onNotice={setNotice} onBusyChange={setToolBusy} />}
      {notice && <p role="status">{notice}</p>}
      {error ? <div className="pams-superdoc-error" role="alert">
        <p>{error}</p><button type="button" onClick={() => setAttempt(n => n + 1)}>重新加载文档</button>
      </div> : !ready && <p className="pams-superdoc-loading" role="status">正在打开文档…</p>}
      <div ref={host} className="pams-superdoc-canvas" />
    </section>
  )
})
