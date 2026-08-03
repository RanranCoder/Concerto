import { useEffect, useRef, useState } from 'react'
import type { ClipboardEvent, CSSProperties, KeyboardEvent } from 'react'
import { Button, Space, Tooltip } from 'antd'
import { BoldOutlined, OrderedListOutlined, TableOutlined } from '@ant-design/icons'
import {
  PLAN_TEMPLATE_SECTIONS,
  sanitizeEditableHtml,
  sectionMetaValue,
  toEditableHtml,
  type PlanFields,
  type PlanMeta,
  type PlanSection,
} from './planTemplate'

interface WordEditorProps {
  value: PlanFields
  onChange: (v: PlanFields) => void
  meta?: PlanMeta
}

/** 每章可编辑区块（contenteditable），含章节标题与正文 */
function EditorSection({
  sec,
  index,
  html,
  meta,
  active,
  onFocus,
  onInput,
  onPaste,
}: {
  sec: PlanSection
  index: number
  html: string
  meta?: PlanMeta
  active: boolean
  onFocus: () => void
  onInput: (html: string) => void
  onPaste: (e: ClipboardEvent<HTMLDivElement>) => void
}) {
  const ref = useRef<HTMLDivElement>(null)
  const bodyHtml = sanitizeEditableHtml(toEditableHtml(html))
  // 编辑内容跟随外部 value（导入/初始化）同步；用户输入时仅 onInput 回写，避免光标跳动
  useEffect(() => {
    const el = ref.current
    if (el && el.innerHTML !== bodyHtml) el.innerHTML = bodyHtml
  }, [bodyHtml])

  const fixedVal = sec.field ? '' : sectionMetaValue(sec, meta)

  return (
    <div
      id={`word-sec-${index}`}
      className={`word-sec${active ? ' word-sec-active' : ''}`}
      onClick={onFocus}
    >
      {sec.field ? (
        <>
          <div className="word-sec-label">{sec.label}</div>
          <div
            ref={ref}
            className="word-sec-body"
            contentEditable
            suppressContentEditableWarning
            data-placeholder={sec.hint ?? '（点击填写）'}
            onInput={(e) => onInput((e.target as HTMLDivElement).innerHTML)}
            onFocus={onFocus}
            onPaste={onPaste}
            onKeyDown={(e: KeyboardEvent<HTMLDivElement>) => {
              if (e.key === 'Tab') {
                e.preventDefault()
                document.execCommand('insertText', false, '    ')
              }
            }}
          />
        </>
      ) : (
        <>
          <div className="word-sec-label">{sec.label}</div>
          {fixedVal ? (
            <div className="word-sec-body word-sec-static">{fixedVal}</div>
          ) : (
            <div className="word-sec-body word-sec-static word-sec-empty" data-placeholder={sec.hint ?? '（自动按活动信息填充）'} />
          )}
        </>
      )}
    </div>
  )
}

export default function WordEditor({ value, onChange, meta }: WordEditorProps) {
  const [activeSec, setActiveSec] = useState(0)
  const [editorKey, setEditorKey] = useState(0) // 触发 execCommand 后重绘

  const selectSection = (i: number) => {
    setActiveSec(i)
    requestAnimationFrame(() => {
      document.getElementById(`word-sec-${i}`)?.scrollIntoView({ behavior: 'smooth', block: 'center' })
    })
  }

  const runCmd = (cmd: string, val?: string) => {
    document.execCommand(cmd, false, val)
    setEditorKey((k) => k + 1)
  }

  /** 字号：在选区外包 <span style="font-size:XXpt"> 实现真实 12/14/22pt（execCommand fontSize 的 '3'/'5'/'6' 是 12/18/24pt，无法精确到 14/22pt）。
   * 无选区时不做操作（需先选中文字再点字号）。insertHTML 会触发 input 事件，onInput 自动回写 value。 */
  const applyFontSize = (pt: number) => {
    const sel = window.getSelection()
    if (sel && sel.rangeCount > 0) {
      const range = sel.getRangeAt(0)
      if (!range.collapsed) {
        const tmp = document.createElement('div')
        tmp.appendChild(range.cloneContents())
        document.execCommand('insertHTML', false, `<span style="font-size:${pt}pt">${tmp.innerHTML}</span>`)
      }
    }
    setEditorKey((k) => k + 1)
  }

  const insertTable = () => {
    const html = `<table class="word-table"><tr><th>物品</th><th>数量</th><th>单价（元）</th><th>总价（元）</th></tr><tr><td>　</td><td>　</td><td>　</td><td>　</td></tr></table><div><br/></div>`
    document.execCommand('insertHTML', false, html)
    setEditorKey((k) => k + 1)
  }

  const handleSectionInput = (index: number, html: string) => {
    const sec = PLAN_TEMPLATE_SECTIONS[index]
    if (!sec.field) return
    const next: PlanFields = { ...value }
    // 存内 HTML（Word 富文本），导出时 stripHtml 还原
    next[sec.field] = html
    onChange(next)
  }

  const handlePaste = (e: ClipboardEvent<HTMLDivElement>) => {
    e.preventDefault()
    const text = e.clipboardData.getData('text/plain')
    document.execCommand('insertText', false, text)
  }

  // 章节导航点击：聚焦到对应区块
  const navClick = (index: number) => {
    setActiveSec(index)
    const el = document.getElementById(`word-sec-${index}`)
    const body = el?.querySelector('.word-sec-body')
    if (body && body instanceof HTMLElement) {
      requestAnimationFrame(() => body.focus())
      selectSection(index)
    } else {
      selectSection(index)
    }
  }

  const toolbarStyle: CSSProperties = {
    display: 'flex',
    flexWrap: 'wrap',
    gap: 8,
    alignItems: 'center',
    marginBottom: 12,
    padding: '8px 12px',
    border: '1px solid var(--glass-border)',
    borderRadius: 10,
    background: 'var(--glass-bg-strong)',
  }

  return (
    <div style={{ display: 'flex', gap: 16, alignItems: 'flex-start' }}>
      {/* 左侧章节导航 */}
      <div style={{ width: 150, flexShrink: 0, position: 'sticky', top: 0 }}>
        <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--color-text)', marginBottom: 8 }}>章节导航</div>
        {PLAN_TEMPLATE_SECTIONS.map((s, i) => (
          <div
            key={i}
            onClick={() => navClick(i)}
            className={`word-nav-item${i === activeSec ? ' word-nav-item-active' : ''}`}
          >
            {s.label}
          </div>
        ))}
      </div>

      {/* 右侧编辑区 */}
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={toolbarStyle}>
          <span style={{ fontSize: 12, color: 'var(--color-text-secondary)' }}>编辑：</span>
          <Tooltip title="加粗">
            <Button
              size="small"
              icon={<BoldOutlined />}
              onMouseDown={(e) => e.preventDefault()}
              onClick={() => runCmd('bold')}
            />
          </Tooltip>
          <Tooltip title="编号列表">
            <Button
              size="small"
              icon={<OrderedListOutlined />}
              onMouseDown={(e) => e.preventDefault()}
              onClick={() => runCmd('insertOrderedList')}
            />
          </Tooltip>
          <Tooltip title="插入表格">
            <Button
              size="small"
              icon={<TableOutlined />}
              onMouseDown={(e) => e.preventDefault()}
              onClick={insertTable}
            />
          </Tooltip>
          <span style={{ width: 1, height: 18, background: 'var(--glass-border)', margin: '0 4px' }} />
          <span style={{ fontSize: 12, color: 'var(--color-text-secondary)' }}>字号：</span>
          <Button size="small" onMouseDown={(e) => e.preventDefault()} onClick={() => applyFontSize(12)}>
            12pt 正文
          </Button>
          <Button size="small" onMouseDown={(e) => e.preventDefault()} onClick={() => applyFontSize(14)}>
            14pt 章节
          </Button>
          <Button size="small" onMouseDown={(e) => e.preventDefault()} onClick={() => applyFontSize(22)}>
            22pt 标题
          </Button>
          <Space size={4} style={{ marginLeft: 'auto' }}>
            <span style={{ fontSize: 12, color: 'var(--color-text-secondary)' }}>Tab 插入空格 · 粘贴自动去格式</span>
          </Space>
        </div>

        <div className="word-paper">
          <div className="word-paper-padding">
            <div className="word-header-center">{meta?.orgName || '信息工程学院党建办公室'}</div>
            <div className="word-header-title">{meta?.name || '活动策划书'}</div>
            {meta?.theme ? <div className="word-header-sub">（主题：{meta.theme}）</div> : null}

            {PLAN_TEMPLATE_SECTIONS.map((sec, i) => {
              const html = sec.field ? (value[sec.field] ?? '') : ''
              return (
                <EditorSection
                  key={`${i}-${sec.field ?? 'fixed'}`}
                  sec={sec}
                  index={i}
                  html={html}
                  meta={meta}
                  active={i === activeSec}
                  onFocus={() => setActiveSec(i)}
                  onInput={(h) => handleSectionInput(i, h)}
                  onPaste={handlePaste}
                />
              )
            })}
          </div>
        </div>
      </div>
      <span hidden>{editorKey}</span>
    </div>
  )
}
