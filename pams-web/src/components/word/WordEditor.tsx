import { lazy, Suspense, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Button, Select } from 'antd'
import { PlusOutlined } from '@ant-design/icons'
import type { IDomEditor } from '@wangeditor/editor'
import {
  defaultSectionOrder,
  isCustomSection,
  normalizeSectionTitle,
  PLAN_TEMPLATE_SECTIONS,
  sectionOverrideValue,
  stripHtml,
  type PlanFields,
  type PlanMeta,
  type PlanOverrides,
  type PlanSectionOrderItem,
} from './planTemplate'
import { sanitizeEditableHtml } from '../../utils/sanitizeHtml'

// wangEditor 体积较大，按需加载（进入编辑模式才拉取）
const PlanRichEditor = lazy(() => import('./PlanRichEditor'))

/** 纸张尺寸（mm）：A4/A3/A5、B4/B5、16开、Letter 等常见规格 */
export interface PaperSize {
  key: string
  label: string
  width: number
  height: number
  unit: string
}

export const PAPER_SIZES: PaperSize[] = [
  { key: 'a4', label: 'A4', width: 210, height: 297, unit: 'mm' },
  { key: 'a3', label: 'A3', width: 297, height: 420, unit: 'mm' },
  { key: 'a5', label: 'A5', width: 148, height: 210, unit: 'mm' },
  { key: 'b4', label: 'B4', width: 250, height: 353, unit: 'mm' },
  { key: 'b5', label: 'B5', width: 176, height: 250, unit: 'mm' },
  { key: '16k', label: '16开', width: 195, height: 270, unit: 'mm' },
  { key: 'letter', label: 'Letter', width: 216, height: 279, unit: 'mm' },
]

const PAPER_STORAGE_KEY = 'pams_plan_paper'

/** 从 localStorage 读取上次选择的纸张，损坏/未知则回退 A4 */
function loadPaper(): PaperSize {
  try {
    const key = localStorage.getItem(PAPER_STORAGE_KEY)
    if (key) {
      const hit = PAPER_SIZES.find((p) => p.key === key)
      if (hit) return hit
    }
  } catch {
    /* localStorage 不可用（隐私模式等）忽略 */
  }
  return PAPER_SIZES[0]
}

/** 页边距预设（mm，参考 Word 的 普通/窄/适中/宽） */
export interface MarginPreset {
  key: string
  label: string
  t: number
  b: number
  l: number
  r: number
}

export const MARGIN_PRESETS: MarginPreset[] = [
  { key: 'normal', label: '普通', t: 25.4, b: 25.4, l: 31.7, r: 31.7 },
  { key: 'narrow', label: '窄', t: 12.7, b: 12.7, l: 12.7, r: 12.7 },
  { key: 'moderate', label: '适中', t: 25.4, b: 25.4, l: 19.1, r: 19.1 },
  { key: 'wide', label: '宽', t: 25.4, b: 25.4, l: 25.4, r: 25.4 },
]

const MARGIN_STORAGE_KEY = 'pams_plan_margin'

function loadMargin(): MarginPreset {
  try {
    const key = localStorage.getItem(MARGIN_STORAGE_KEY)
    if (key) {
      const hit = MARGIN_PRESETS.find((m) => m.key === key)
      if (hit) return hit
    }
  } catch {
    /* localStorage 不可用忽略 */
  }
  return MARGIN_PRESETS[0]
}

const MM_TO_PX = 96 / 25.4
const mmToPx = (mm: number): number => Math.round(mm * MM_TO_PX)

/** 页与页之间的间隔（px）：末块补白下推、页间露底色，视觉上页与页独立 */
const PAGE_GAP = 40

/** 计算纸张 + 页边距 → 像素布局（编辑/分页/预览共用同一套数值） */
export function paperLayout(paper: PaperSize, margin: MarginPreset) {
  const marginPx = { t: mmToPx(margin.t), b: mmToPx(margin.b), l: mmToPx(margin.l), r: mmToPx(margin.r) }
  const sheetW = Math.min(mmToPx(paper.width), 1000)
  const pageH = mmToPx(paper.height)
  const contentH = Math.max(200, pageH - marginPx.t - marginPx.b)
  return { sheetW, pageH, contentH, margin: marginPx }
}

interface WordEditorProps {
  value: PlanFields
  onChange: (v: PlanFields) => void
  meta?: PlanMeta
  /** 自定义章节名称映射（fieldName -> customLabel） */
  customLabels?: Record<string, string>
  /** 自定义章节名称变化回调 */
  onCustomLabelChange?: (fieldName: string, label: string) => void
  /** 只读章节（活动基本信息）的可覆盖值 */
  overrides?: PlanOverrides
  onOverridesChange?: (o: PlanOverrides) => void
  /** 章节顺序 + 自定义节名 */
  sectionOrder?: PlanSectionOrderItem[]
  onSectionOrderChange?: (items: PlanSectionOrderItem[]) => void
}

/** 只读章节（活动基本信息）label → override 字段映射 */
const FIXED_OVERRIDE_MAP: Record<string, keyof PlanOverrides> = {
  '一、活动名称': 'nameOverride',
  '二、活动主题': 'themeOverride',
  '五、活动时间': 'timeOverride',
  '六、活动地点': 'locationOverride',
  '七、活动组织单位': 'organizerOverride',
  '八、活动对象': 'targetOverride',
}

/** 活动流程字段的章节标题（不属于 12 章模板，单编辑器里独立一节） */
const FLOW_LABEL = '活动流程'

function escHtml(s: string): string {
  return (s || '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
}

/** 富文本是否为空（纯空白/仅空段落） */
function isEmptyRichText(html: string): boolean {
  return stripHtml(html).trim() === ''
}

/** 把文档 HTML 按 h1-h4 章节标题切分为 {titleHtml, titleText, bodyHtml}[]（正文原样保留）。
 *  导出供单测直测解析边界（innerHTML 注入点的 XSS 防护，见 wordEditorXss.test.ts） */
export function parseDocSections(html: string): Array<{ titleHtml: string; titleText: string; bodyHtml: string }> {
  if (typeof document === 'undefined') return []
  const frag = document.createElement('div')
  // XSS 防护：存库字段可能含恶意 HTML（如 <img onerror=…> 外带 token），innerHTML 会触发事件属性，
  // 解析/渲染边界统一走 sanitizeEditableHtml（DOMPurify，与 PlanRichEditor 兜底一致）净化后再解析
  frag.innerHTML = sanitizeEditableHtml(html || '')
  const out: Array<{ titleHtml: string; titleText: string; bodyHtml: string }> = []
  let current: { titleHtml: string; titleText: string; bodyHtml: string } | null = null
  for (const node of Array.from(frag.childNodes)) {
    const tag = node instanceof Element ? node.tagName.toLowerCase() : ''
    const text = (node.textContent ?? '').trim()
    if ((tag === 'h1' || tag === 'h2' || tag === 'h3' || tag === 'h4') && text) {
      current = { titleHtml: (node as Element).outerHTML, titleText: text, bodyHtml: '' }
      out.push(current)
    } else if (current) {
      current.bodyHtml += node instanceof Element ? node.outerHTML : escHtml(node.textContent ?? '')
    }
  }
  return out
}

/** 从 props 组装整篇可编辑文档 HTML：每节 = h2 章节标题 + 正文（字段富文本 / 自定义 contentHtml / meta 覆盖值）。
 *  导出供单测直测（buildDocHtml 消费处的 XSS 防护，见 wordEditorXss.test.ts） */
export function buildDocHtml(
  fields: PlanFields,
  overrides: PlanOverrides | undefined,
  meta: PlanMeta | undefined,
  sectionOrder: PlanSectionOrderItem[] | undefined,
): string {
  const sections = sectionOrder && sectionOrder.length > 0 ? sectionOrder : defaultSectionOrder()
  const parts = sections.map((it) => {
    const label = it.customLabel || it.label
    let body = ''
    if (it.field) {
      body = fields[it.field] ?? ''
    } else if (isCustomSection(it)) {
      body = it.contentHtml ?? ''
    } else {
      const v = sectionOverrideValue(it.label, overrides, meta).trim()
      body = v ? `<p>${escHtml(v)}</p>` : ''
    }
    return `<h2>${escHtml(label)}</h2>${body || '<p><br></p>'}`
  })
  parts.push(`<h2>${FLOW_LABEL}</h2>${(fields.flow ?? '') || '<p><br></p>'}`)
  return parts.join('')
}

/** 把编辑后的整篇文档反解回 PlanFields + overrides + sectionOrder（标题匹配回字段，未匹配为自定义章节）。
 *  导出供单测直测（回写落库边界的 XSS 防护，见 wordEditorXss.test.ts） */
export function applyDocToState(
  html: string,
  fields: PlanFields,
  overrides: PlanOverrides,
): { fields: PlanFields; overrides: PlanOverrides; order: PlanSectionOrderItem[] } {
  // 回写/落库边界净化：字段正文与自定义章节 contentHtml 会持久化，入库前剥掉脚本与事件属性
  const cleanHtml = sanitizeEditableHtml(html || '')
  const secs = parseDocSections(cleanHtml)
  const nextFields: PlanFields = { ...fields }
  const nextOv: PlanOverrides = { ...overrides }
  const nextOrder: PlanSectionOrderItem[] = []
  for (const sec of secs) {
    const label = normalizeSectionTitle(sec.titleText)
    // 活动流程：独立字段，不入 12 章模板
    if (label === FLOW_LABEL || sec.titleText.includes('活动流程')) {
      nextFields.flow = isEmptyRichText(sec.bodyHtml) ? '' : sec.bodyHtml
      continue
    }
    const tmpl = PLAN_TEMPLATE_SECTIONS.find((s) => s.label === label)
    if (tmpl?.field) {
      nextFields[tmpl.field] = isEmptyRichText(sec.bodyHtml) ? '' : sec.bodyHtml
      nextOrder.push({ label: tmpl.label, field: tmpl.field, hint: tmpl.hint })
    } else if (tmpl) {
      const key = FIXED_OVERRIDE_MAP[tmpl.label]
      if (key) nextOv[key] = stripHtml(sec.bodyHtml).trim()
      nextOrder.push({ label: tmpl.label, field: null, hint: tmpl.hint })
    } else {
      const t = stripHtml(sec.titleHtml).trim() || '新章节'
      nextOrder.push({ label: t, field: null, hint: '自定义章节', contentHtml: isEmptyRichText(sec.bodyHtml) ? '' : sec.bodyHtml })
    }
  }
  // 无任何章节标题的纯正文兜底：整体当作自定义章节，避免内容丢失
  if (nextOrder.length === 0 && cleanHtml.trim()) {
    nextOrder.push({ label: '新章节', field: null, hint: '自定义章节', contentHtml: cleanHtml })
  }
  return { fields: nextFields, overrides: nextOv, order: nextOrder }
}

/** 单编辑器整篇编辑：左侧章节导航（点击跳转）+ 右侧一个 wangEditor 承载全部章节，章节标题/正文自由编辑 */
export default function WordEditor({
  value,
  onChange,
  meta,
  overrides,
  onOverridesChange,
  sectionOrder,
  onSectionOrderChange,
}: WordEditorProps) {
  const ov = overrides ?? {}

  /** 当前纸张尺寸 + 页边距预设（localStorage 持久化） */
  const [paper, setPaper] = useState<PaperSize>(loadPaper)
  const [margin, setMargin] = useState<MarginPreset>(loadMargin)
  const layout = useMemo(() => paperLayout(paper, margin), [paper, margin])

  /** 分页：总页数 + 当前页 */
  const [pageCount, setPageCount] = useState(1)
  const [currentPage, setCurrentPage] = useState(1)

  /** 编辑器当前已装载文档 HTML（作为 value 只在外源变化时更新；编辑过程中不回写，
   *  避免 editor-for-react 的 value 比对触发 setHtml 打断 slate 同步/光标） */
  const [docHtml, setDocHtml] = useState(() => buildDocHtml(value, overrides ?? {}, meta, sectionOrder))
  /** 左侧导航标题：由编辑器最新内容实时生成（独立于 docHtml，编辑时随 onChange 更新） */
  const [navTitles, setNavTitles] = useState<string[]>(() => parseDocSections(docHtml).map((s) => s.titleText.trim()))
  /** 最近一次由本组件解析并上抛的状态快照（用于区分外部数据变化 vs 自身编辑回显） */
  const lastPushedRef = useRef('')

  const changePaper = (key: string) => {
    const next = PAPER_SIZES.find((p) => p.key === key)
    if (!next) return
    setPaper(next)
    try {
      localStorage.setItem(PAPER_STORAGE_KEY, key)
    } catch {
      /* localStorage 不可用忽略 */
    }
  }

  const changeMargin = (key: string) => {
    const next = MARGIN_PRESETS.find((m) => m.key === key)
    if (!next) return
    setMargin(next)
    try {
      localStorage.setItem(MARGIN_STORAGE_KEY, key)
    } catch {
      /* localStorage 不可用忽略 */
    }
  }

  // 外部数据变化（导入 docx / 模板库 / 保存重载）时重建文档；自身编辑回显跳过
  useEffect(() => {
    const cur = JSON.stringify({ value, overrides, order: sectionOrder ?? null, meta })
    if (cur !== lastPushedRef.current) {
      const rebuilt = buildDocHtml(value, overrides ?? {}, meta, sectionOrder)
      setDocHtml(rebuilt)
      setNavTitles(parseDocSections(rebuilt).map((s) => s.titleText.trim()))
    }
  }, [value, overrides, sectionOrder, meta])

  /** 编辑器内容变化：更新导航 → 反解字段/覆盖值/章节顺序上抛（不把内容回写 value，见 docHtml 注释） */
  const handleDocChange = (html: string) => {
    setNavTitles(parseDocSections(html).map((s) => s.titleText.trim()))
    const parsed = applyDocToState(html, value, ov)
    lastPushedRef.current = JSON.stringify({ value: parsed.fields, overrides: parsed.overrides, order: parsed.order, meta })
    onChange(parsed.fields)
    onOverridesChange?.(parsed.overrides)
    onSectionOrderChange?.(parsed.order)
  }

  const editorRef = useRef<IDomEditor | null>(null)

  /** 新增章节：走编辑器 API 在末尾插入新标题 + 空段落（避免直接拼 HTML + setHtml 导致 slate 与 DOM 失步） */
  const addSection = () => {
    const ed = editorRef.current
    if (!ed) return
    ed.focus(true)
    ed.dangerouslyInsertHtml('<h2>新章节</h2><p><br></p>')
  }

  const editorWrapRef = useRef<HTMLDivElement>(null)
  const viewportRef = useRef<HTMLDivElement | null>(null)

  /** 导航点击：在编辑器内容容器内找到对应标题并平滑滚动 */
  const scrollToSection = (label: string) => {
    const container = editorWrapRef.current?.querySelector<HTMLElement>('.w-e-text-container')
    const heads = container?.querySelectorAll('h1,h2,h3,h4') ?? []
    const target = Array.from(heads).find((h) => (h.textContent ?? '').trim() === label.trim())
    target?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }

  /** 翻页：滚动到指定页的起始位置 */
  const goToPage = (page: number) => {
    const vp = viewportRef.current
    if (!vp) return
    const target = pageStarts[Math.max(0, Math.min(page - 1, pageStarts.length - 1))]
    vp.scrollTo({ top: target, behavior: 'smooth' })
  }

  const measureTimerRef = useRef(0)
  /** 已被补页间隔的块（重测前需清除 padding-bottom） */
  const paddedBlocksRef = useRef<Set<HTMLElement>>(new Set())
  /** 各页间隔带位置（内容坐标，随下推累计偏移移动） */
  const [bandTops, setBandTops] = useState<number[]>([])

  /** 各页起始滚动位置：第 0 页 = 0，第 k 页 = 间隔带 k-1 的底（带顶 + gap） */
  const pageStarts = useMemo(() => [0, ...bandTops.map((t) => t + PAGE_GAP)], [bandTops])

  /** 测量内容高度（scrollHeight 不受祖先裁剪影响）→ 重新分页；
   *  同时给每页末块补 padding-bottom（padding 不折叠，位移精确），把跨页块整体推到下一页 */
  const scheduleMeasure = useCallback(() => {
    cancelAnimationFrame(measureTimerRef.current ?? 0)
    measureTimerRef.current = requestAnimationFrame(() => {
      measureTimerRef.current = requestAnimationFrame(() => {
        measureTimerRef.current = 0
        const container = editorWrapRef.current?.querySelector<HTMLElement>('.plan-page-sheet [data-slate-editor]')
        if (!container) return
        // 1. 清除上一次的页间隔补白，回到干净布局再测量
        for (const el of paddedBlocksRef.current) {
          el.style.paddingBottom = ''
        }
        paddedBlocksRef.current.clear()
        // 2. 干净内容高度 → 页数
        const pages = Math.max(1, Math.ceil(container.scrollHeight / layout.contentH))
        const bands: number[] = []
        if (pages > 1) {
          const baseTop = container.getBoundingClientRect().top
          const blocks = Array.from(container.children) as HTMLElement[]
          const cleanTops = blocks.map((b) => b.getBoundingClientRect().top - baseTop)
          const cleanBottoms = blocks.map((b, i) => cleanTops[i] + b.getBoundingClientRect().height)
          let cumulative = 0
          let lastIdx = -1
          // 3. 第 k 页末块补 padding-bottom = (边界 + gap - 跨页块顶)，跨页块整体落到下一页，
          //    页间隔带随累计偏移定位
          for (let k = 1; k < pages; k++) {
            const boundary = k * layout.contentH
            const idx = cleanBottoms.findIndex((b, i) => b > boundary && i > lastIdx)
            if (idx < 0) break // 单个块高于整页时无法在块内分页，跳过
            const prev = idx - 1
            if (prev < 0) break
            const pad = Math.max(0, boundary + PAGE_GAP - cleanTops[idx])
            bands.push(boundary + cumulative)
            blocks[prev].style.paddingBottom = `${pad}px`
            paddedBlocksRef.current.add(blocks[prev])
            cumulative += pad
            lastIdx = prev
          }
        }
        setBandTops(bands)
        setPageCount(pages)
        setCurrentPage((cur) => Math.min(cur, pages))
      })
    })
  }, [layout.contentH])

  /** 内容变化时重新分页 */
  useEffect(() => {
    scheduleMeasure()
  }, [scheduleMeasure, navTitles, editorRef.current])

  /** 窗口尺寸变化时重新分页 */
  useEffect(() => {
    window.addEventListener('resize', scheduleMeasure)
    return () => window.removeEventListener('resize', scheduleMeasure)
  }, [scheduleMeasure])

  /** 视口滚动 → 当前页 */
  useEffect(() => {
    const vp = viewportRef.current
    if (!vp) return
    const onScroll = () => {
      // +4px 容差：分数像素 DPR 下平滑滚动可能停在页界下方不足 1px
      const st = vp.scrollTop + 4
      let page = 1
      for (let k = pageStarts.length - 1; k > 0; k--) {
        if (st >= pageStarts[k]) {
          page = k + 1
          break
        }
      }
      setCurrentPage(Math.max(1, Math.min(page, pageCount)))
    }
    vp.addEventListener('scroll', onScroll, { passive: true })
    return () => vp.removeEventListener('scroll', onScroll)
  }, [pageStarts, pageCount])

  return (
    <div>
      {/* 控制栏：纸张大小 + 页边距（Word 预设）+ 分页导航 */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 16, marginBottom: 12, flexWrap: 'wrap' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <span style={{ fontSize: 14, color: 'var(--text-secondary)' }}>纸张大小</span>
          <Select
            size="middle"
            style={{ width: 110 }}
            value={paper.key}
            onChange={changePaper}
            options={PAPER_SIZES.map((p) => ({ value: p.key, label: p.label }))}
          />
          <span style={{ fontSize: 13, color: 'var(--text-secondary)' }}>
            {paper.width}×{paper.height}{paper.unit}
          </span>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <span style={{ fontSize: 14, color: 'var(--text-secondary)' }}>页边距</span>
          <Select
            size="middle"
            style={{ width: 96 }}
            value={margin.key}
            onChange={changeMargin}
            options={MARGIN_PRESETS.map((m) => ({ value: m.key, label: m.label }))}
          />
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginLeft: 'auto' }}>
          <Button size="small" disabled={currentPage <= 1} onClick={() => goToPage(currentPage - 1)}>
            上一页
          </Button>
          <span style={{ fontSize: 14, color: 'var(--text-secondary)', minWidth: 64, textAlign: 'center' }}>
            第 {currentPage} / {pageCount} 页
          </span>
          <Button size="small" disabled={currentPage >= pageCount} onClick={() => goToPage(currentPage + 1)}>
            下一页
          </Button>
        </div>
      </div>

      <div style={{ display: 'flex', gap: 16, alignItems: 'flex-start' }}>
        {/* 左侧章节导航：由文档标题实时生成，点击跳转 */}
        <div style={{ width: 200, flexShrink: 0, position: 'sticky', top: 0 }}>
          <div style={{ fontSize: 14, fontWeight: 600, color: 'var(--text-primary)', marginBottom: 8 }}>章节导航</div>
          {navTitles.map((label, i) => (
            <div
              key={`${label}-${i}`}
              className="word-nav-item"
              style={{ cursor: 'pointer' }}
              title={label}
              onClick={() => scrollToSection(label)}
            >
              {label}
            </div>
          ))}
          <Button block icon={<PlusOutlined />} style={{ marginTop: 8 }} onClick={addSection}>
            新增章节
          </Button>
        </div>

        {/* 右侧：功能栏固定 + 逐页分页纸张（单个 wangEditor）；min-width 防止窄窗把纸张压塌 */}
        <div style={{ flex: 1, minWidth: 480, maxWidth: '100%' }} ref={editorWrapRef}>
          <Suspense fallback={<div style={{ padding: 32, textAlign: 'center', color: 'var(--text-secondary)' }}>编辑器加载中...</div>}>
            <PlanRichEditor
              value={docHtml}
              onChange={handleDocChange}
              placeholder="（在此填写策划书正文；用标题工具将章节标题设为标题样式，左侧导航会同步）"
              editorRef={editorRef}
              viewportRef={viewportRef}
              layout={layout}
              pageCount={pageCount}
              gap={PAGE_GAP}
              pageStarts={pageStarts}
            />
          </Suspense>
          <div style={{ marginTop: 8, fontSize: 13, color: 'var(--text-secondary)' }}>
            提示：内容超过一页会自动排到下一页，可点「上一页/下一页」翻页；点击左侧章节可跳转；章节标题可用「标题」下拉设置；活动名称/主题/时间/地点/组织单位/对象修改后保存可同步更新活动基本信息。
          </div>
        </div>
      </div>
    </div>
  )
}

/** 序列化章节顺序 JSON（供父级保存） */
export function toSectionOrderJson(items: PlanSectionOrderItem[]): string {
  return JSON.stringify(items)
}
