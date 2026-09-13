import { useEffect, useMemo, useRef, useState, type MutableRefObject } from 'react'
import { Editor } from '@wangeditor/editor-for-react'
import { createToolbar } from '@wangeditor/editor'
import type { IDomEditor, IEditorConfig, IToolbarConfig, Toolbar } from '@wangeditor/editor'
import '@wangeditor/editor/dist/css/style.css'
import { uploadFile } from '@/api/file'
import { sanitizeEditableHtml } from './planTemplate'

/** 纸张 + 页边距布局（px）：由 WordEditor 统一计算传入 */
export interface PaperLayout {
  /** 纸张显示宽度（页边距外缘） */
  sheetW: number
  /** 纸张高度（页边距外缘） */
  pageH: number
  /** 单页内容区高度（pageH - 上下页边距） */
  contentH: number
  /** 页边距（px） */
  margin: { t: number; b: number; l: number; r: number }
}

interface PlanRichEditorProps {
  /** 当前整篇文档 HTML（富文本） */
  value: string
  onChange: (html: string) => void
  placeholder?: string
  /** 暴露编辑器实例（供父组件走编辑器 API 插入内容，避免直接拼 HTML + setHtml 破坏 slate 同步） */
  editorRef?: MutableRefObject<IDomEditor | null>
  /** 逐页分页视口（供父组件翻页/测量） */
  viewportRef?: MutableRefObject<HTMLDivElement | null>
  /** 纸张 + 页边距布局 */
  layout: PaperLayout
  /** 分页总数（父组件按内容高度测量） */
  pageCount: number
  /** 页与页之间的间隔（px） */
  gap: number
  /** 各页内容起始位置（内容坐标，第 0 页 = 0；由父组件测量/下推后得出） */
  pageStarts: number[]
}

/** wangEditor 5 工具栏：标题/加粗/斜体/下划线/删除线/颜色/高亮/对齐/列表/缩进/表格/图片/链接/清除格式/撤销重做 */
const TOOLBAR_KEYS: (string | { key: string; title: string; menuKeys: string[] })[] = [
  'headerSelect',
  '|',
  'bold',
  'italic',
  'underline',
  'through',
  'color',
  'bgColor',
  '|',
  'justifyLeft',
  'justifyCenter',
  'justifyRight',
  '|',
  'bulletedList',
  'numberedList',
  'indent',
  'delIndent',
  '|',
  'insertTable',
  'insertImage',
  'insertLink',
  '|',
  'undo',
  'redo',
  '|',
  'clearStyle',
  'fullScreen',
]

/** wangEditor 5 单编辑器封装：功能栏独立固定顶部 + 逐页分页纸张（图片走 /api/files/upload） */
export default function PlanRichEditor({ value, onChange, placeholder, editorRef, viewportRef, layout, pageCount, gap, pageStarts }: PlanRichEditorProps) {
  const [editor, setEditor] = useState<IDomEditor | null>(null)
  const [ready, setReady] = useState(false)

  // 净化后的内容（进入编辑器统一走 sanitize，避免 XSS/格式差异导致 value 回写循环）
  const cleanValue = useMemo(() => sanitizeEditableHtml(value), [value])

  const editorConfig: Partial<IEditorConfig> = {
    placeholder: placeholder || '（点击填写）',
    onChange: (ed: IDomEditor) => {
      onChange(ed.getHtml())
    },
    MENU_CONF: {
      uploadImage: {
        customUpload: async (file: File, insertFn: (url: string, alt: string, href: string) => void) => {
          try {
            const rec = await uploadFile(file, 'PLAN')
            // 后端返回 FileRecord，图片用下载地址（预览/导出统一走 /api/files/{id}/download）
            insertFn(`/api/files/${rec.id}/download`, rec.filename, '')
          } catch {
            /* http 拦截已提示 */
          }
        },
        allowedFileTypes: ['image/*'],
      },
    },
  }

  const toolbarConfig: Partial<IToolbarConfig> = {
    toolbarKeys: TOOLBAR_KEYS,
    modalAppendToBody: true,
  }

  const toolbarRef = useRef<HTMLDivElement>(null)
  const toolbarInstRef = useRef<Toolbar | null>(null)

  // 自管理 toolbar 生命周期：editor-for-react 的 <Toolbar> 未销毁实例，
  // 在 React StrictMode 双调用 effect 下会报 'Repeated create toolbar'
  useEffect(() => {
    if (!editor || !toolbarRef.current) return
    // 清掉可能残留的 data-w-e-toolbar 标记，避免重复创建报错
    toolbarRef.current.removeAttribute('data-w-e-toolbar')
    toolbarInstRef.current = createToolbar({
      editor,
      selector: toolbarRef.current,
      config: toolbarConfig,
      mode: 'default',
    })
    return () => {
      toolbarInstRef.current?.destroy()
      toolbarInstRef.current = null
    }
  }, [editor])

  const { sheetW, pageH, contentH, margin } = layout
  // 纸张最小高度：内容区至少撑满最后一页卡片（最后一页起始 + 一页内容），保证每页都能滚动到
  const lastStart = pageStarts.length > 0 ? pageStarts[pageStarts.length - 1] : 0
  const sheetMinH = pageCount > 1 ? margin.t + margin.b + lastStart + contentH : pageH

  // 纸张底色：每页内容区白色、页与页之间透明（露出视口深色 → 页间隔清晰）
  const sheetBg = useMemo(() => {
    const stops: string[] = ['#fff 0px']
    for (let k = 0; k < pageStarts.length; k++) {
      const end = margin.t + pageStarts[k] + contentH
      stops.push(`#fff ${end}px`, `transparent ${end}px`)
      if (k < pageStarts.length - 1) {
        const next = margin.t + pageStarts[k + 1]
        stops.push(`transparent ${next}px`, `#fff ${next}px`)
      }
    }
    stops.push('#fff') // 末页以下保持白色
    return `linear-gradient(to bottom, ${stops.join(',')})`
  }, [pageStarts, margin.t, contentH])

  return (
    <div className="plan-paper-editor">
      {/* 功能栏：独立固定在顶部，不随纸张/分页滚动 */}
      <div
        className="plan-editor-toolbar"
        style={{
          position: 'sticky',
          top: 0,
          zIndex: 100,
          background: 'var(--surface)',
          border: '1px solid var(--surface-border)',
          borderRadius: 8,
          marginBottom: 16,
          boxShadow: '0 2px 8px rgba(0,0,0,0.06)',
        }}
      >
        {ready ? <div ref={toolbarRef} /> : <div style={{ height: 40 }} />}
      </div>

      {/* 逐页分页视口：高度=单页（含页边距），页与页之间透明露出深色间隔，视口滚动翻页 */}
      <div
        className="plan-page-viewport"
        ref={viewportRef}
        style={{
          height: pageH,
          overflowY: 'auto',
          borderRadius: 8,
          padding: '12px 8px',
          background: 'rgba(0,0,0,0.12)',
        }}
      >
        <div
          className="plan-page-sheet"
          style={{
            width: sheetW,
            maxWidth: '100%',
            margin: '0 auto',
            minHeight: sheetMinH,
            boxSizing: 'border-box',
            background: sheetBg,
            borderRadius: 6,
            boxShadow: '0 2px 10px rgba(0,0,0,0.10)',
            padding: `${margin.t}px ${margin.r}px ${margin.b}px ${margin.l}px`,
            position: 'relative',
          }}
        >
          {/* 页间隔中的页码（随内容滚动，pointer-events:none 不挡编辑） */}
          {pageStarts.slice(0, -1).map((start, k) => (
            <div
              key={k}
              style={{
                position: 'absolute',
                left: 0,
                right: 0,
                top: margin.t + start + contentH + gap / 2,
                transform: 'translateY(-50%)',
                textAlign: 'center',
                pointerEvents: 'none',
              }}
            >
              <span style={{ fontSize: 11, color: '#8c9198' }}>— 第 {k + 2} 页 —</span>
            </div>
          ))}

          {/* value 由 editor-for-react 内置 effect 与编辑器内容比对后 setHtml（避免光标跳动）；
              纸张高度自适应内容，分页由外层视口滚动完成 */}
          <Editor
            defaultConfig={editorConfig}
            defaultHtml={cleanValue}
            value={cleanValue}
            mode="default"
            onCreated={(ed) => {
              setEditor(ed)
              setReady(true)
              if (editorRef) editorRef.current = ed
            }}
            style={{ background: 'transparent' }}
          />
        </div>
      </div>
    </div>
  )
}
