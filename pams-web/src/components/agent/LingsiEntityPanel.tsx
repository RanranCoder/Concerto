import { useState } from 'react'
import { useRef } from 'react'
import type { MouseEvent as ReactMouseEvent } from 'react'
import { App, Button, Tooltip } from 'antd'
import { useNavigate } from 'react-router-dom'
import { useAgentPanel } from '@/stores/agentPanel'
import type { PanelItem } from '@/stores/agentPanel'
import LingsiMarkdown from './LingsiMarkdown'
import { Close, Copy, OpenExternal, PanelRight } from './LingsiIcons'

/** 实体类型 → 中文名（与产物卡保持同一套映射） */
const TYPE_LABEL: Record<string, string> = {
  activity: '活动',
  member: '成员',
  article: '推文',
  seatLayout: '座位表',
  announcement: '公告',
  notification: '通知',
  plan: '策划书',
  entity: '内容',
}

/**
 * 右侧实体面板（UI v2 §A「产物分屏」）—— 对应 Trae Work 右栏的文件面板。
 *
 * <p><b>为什么是"实体"而不是"文件"</b>：灵思的产物是党务实体（活动/名册/策划书/座位表），
 * 不是源码文件。照搬文件面板会变成一个没人用的空壳，所以这里按"能跳回的业务页面"来组织：
 * 顶部 tab 一次可并列多个产物，正文给出身份信息 + 一个跳转按钮。
 *
 * <p><b>它能替换掉什么</b>：原来「查看全文（策划书/推文草稿）」是一个 720px 的 Drawer 遮罩，
 * 罩住整个对话。用户要么看正文、要么看清单，来回切换。分屏之后两者同时可见 ——
 * 这正是"确认前建议通读全文"这条设计意图真正落地的前提。
 *
 * <p><b>它刻意不做什么</b>：不重新实现活动详情页。埋一份实体视图的平行实现，
 * 既会立刻与业务页漂移，也违背灵思"复用现有 Service 不自建数据通道"的铁律。
 * 需要看全貌时用户点一下按钮回业务页，那里才是唯一事实来源。
 */
export default function LingsiEntityPanel() {
  const items = useAgentPanel((s) => s.items)
  const activeId = useAgentPanel((s) => s.activeId)
  const collapsed = useAgentPanel((s) => s.collapsed)
  const activate = useAgentPanel((s) => s.activate)
  const close = useAgentPanel((s) => s.close)
  const setCollapsed = useAgentPanel((s) => s.setCollapsed)
  const width = useAgentPanel((s) => s.width)
  const setWidth = useAgentPanel((s) => s.setWidth)
  const dragging = useRef(false)

  /**
   * 拖拽调宽（B4）。挂在 window 上而不是手柄上：鼠标拖快了会滑出手柄，
   * 监听在 window 才不会"拖到一半丢掉"；mouseup 也一样。
   * 方向语义：面板在右侧，**向左拖 = 变宽**，所以是 startX - clientX。
   */
  const startDrag = (e: ReactMouseEvent) => {
    e.preventDefault()
    dragging.current = true
    const startX = e.clientX
    const startW = width
    document.body.classList.add('lingsi-resizing')
    const move = (ev: MouseEvent) => {
      if (!dragging.current) return
      setWidth(startW + (startX - ev.clientX))
    }
    const up = () => {
      dragging.current = false
      document.body.classList.remove('lingsi-resizing')
      window.removeEventListener('mousemove', move)
      window.removeEventListener('mouseup', up)
    }
    window.addEventListener('mousemove', move)
    window.addEventListener('mouseup', up)
  }

  // 收起态：留一条窄轨，否则"有产物但面板不见了"就成了死路
  if (collapsed) {
    if (items.length === 0) return null
    return (
      <div className="lingsi-panel-rail">
        <Tooltip title="展开产物面板" placement="left">
          <button type="button" className="lingsi-icon-btn" onClick={() => setCollapsed(false)}>
            <PanelRight size={15} />
          </button>
        </Tooltip>
        <span className="lingsi-panel-rail-count">{items.length}</span>
      </div>
    )
  }

  if (items.length === 0) return null

  const active = items.find((it) => it.id === activeId) ?? items[items.length - 1]

  return (
    <aside className="lingsi-panel glass-card" style={{ width, flexBasis: width }}>
      <div
        className="lingsi-panel-handle"
        role="separator"
        aria-orientation="vertical"
        aria-label="调整面板宽度（左右方向键微调，双击复位）"
        tabIndex={0}
        onMouseDown={startDrag}
        onDoubleClick={() => setWidth(452)}
        onKeyDown={(e) => {
          if (e.key === 'ArrowLeft') {
            e.preventDefault()
            setWidth(width + 16)
          }
          if (e.key === 'ArrowRight') {
            e.preventDefault()
            setWidth(width - 16)
          }
        }}
      />
      <div className="lingsi-panel-tabs">
        {items.map((it) => (
          <span
            key={it.id}
            className={`lingsi-ptab${it.id === active.id ? ' lingsi-ptab-active' : ''}`}
            role="button"
            tabIndex={0}
            onClick={() => activate(it.id)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' || e.key === ' ') activate(it.id)
            }}
          >
            <span className="lingsi-ptab-label">{it.title}</span>
            <button
              type="button"
              className="lingsi-ptab-x"
              aria-label="关闭"
              onClick={(e) => {
                e.stopPropagation()
                close(it.id)
              }}
            >
              <Close size={11} />
            </button>
          </span>
        ))}
        <span className="lingsi-panel-tab-spacer" />
        <Tooltip title="收起面板">
          <button type="button" className="lingsi-icon-btn" onClick={() => setCollapsed(true)}>
            <PanelRight size={15} />
          </button>
        </Tooltip>
      </div>

      <div className="lingsi-panel-body">
        {active.kind === 'text' ? (
          <TextPreview title={active.title} text={active.text ?? ''} />
        ) : (
          <ArtifactDetail item={active} />
        )}
      </div>
    </aside>
  )
}

/* ------------------------------------------------------------ 产物详情 */

function ArtifactDetail({ item }: { item: PanelItem }) {
  const navigate = useNavigate()
  const path = item.path ?? null

  return (
    <>
      <h3 className="lingsi-panel-title">{item.name}</h3>
      <div className="lingsi-panel-sub">
        {TYPE_LABEL[item.entityType ?? ''] ?? item.entityType}
        {item.entityId != null && ` · ID ${item.entityId}`}
        <span className="lingsi-panel-tag">本次会话写入</span>
      </div>

      <div className="lingsi-panel-note">
        这里只显示变更摘要。完整内容请在业务页面查看 —— 灵思不复制一份实体视图，
        否则它和真实数据一定会漂移。
      </div>

      {path ? (
        <Button type="primary" size="small" icon={<OpenExternal size={13} />} onClick={() => navigate(path)}>
          打开完整页面
        </Button>
      ) : (
        <div className="lingsi-panel-note">该产物没有可跳转的页面。</div>
      )}
    </>
  )
}

/* ------------------------------------------------------------ 正文预览 */

function TextPreview({ title, text }: { title: string; text: string }) {
  const { message } = App.useApp()
  const [copied, setCopied] = useState(false)

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(text)
      setCopied(true)
      message.success('已复制全文')
      window.setTimeout(() => setCopied(false), 1500)
    } catch {
      message.warning('复制失败，请手动选择文本')
    }
  }

  return (
    <>
      <div className="lingsi-panel-preview-head">
        <div style={{ minWidth: 0 }}>
          <h3 className="lingsi-panel-title">{title}</h3>
          <div className="lingsi-panel-sub">约 {text.length} 字 · 确认前请通读</div>
        </div>
        <Tooltip title={copied ? '已复制' : '复制全文'}>
          <button type="button" className="lingsi-icon-btn" onClick={() => void copy()}>
            <Copy size={14} />
          </button>
        </Tooltip>
      </div>
      <div className="lingsi-panel-doc">
        <LingsiMarkdown text={text} />
      </div>
    </>
  )
}
