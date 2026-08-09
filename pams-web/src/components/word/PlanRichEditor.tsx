import { useMemo, useState } from 'react'
import { Editor, Toolbar } from '@wangeditor/editor-for-react'
import type { IDomEditor, IEditorConfig, IToolbarConfig } from '@wangeditor/editor'
import '@wangeditor/editor/dist/css/style.css'
import { uploadFile } from '@/api/file'
import { sanitizeEditableHtml } from './planTemplate'

interface PlanRichEditorProps {
  /** 当前章节 HTML（富文本） */
  value: string
  onChange: (html: string) => void
  placeholder?: string
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

/** wangEditor 5 单章节编辑器封装（毛玻璃适配 + 图片走 /api/files/upload） */
export default function PlanRichEditor({ value, onChange, placeholder }: PlanRichEditorProps) {
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

  return (
    <div className="plan-rich-editor" style={{ display: 'flex', flexDirection: 'column' }}>
      {ready ? (
        <Toolbar
          editor={editor}
          defaultConfig={toolbarConfig}
          mode="default"
          style={{ borderBottom: '1px solid var(--surface-border)', borderRadius: '8px 8px 0 0', background: 'var(--surface-strong)' }}
        />
      ) : null}
      {/* value 由 editor-for-react 内置 effect 与编辑器内容比对后 setHtml（避免光标跳动）；
          初次装载用净化后的 HTML */}
      <Editor
        defaultConfig={editorConfig}
        defaultHtml={cleanValue}
        value={cleanValue}
        mode="default"
        onCreated={(ed) => {
          setEditor(ed)
          setReady(true)
        }}
        style={{ height: 480, overflowY: 'hidden', borderRadius: '0 0 8px 8px', background: 'var(--surface)' }}
      />
    </div>
  )
}
