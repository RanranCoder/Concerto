import { useEffect, useMemo, useState, Suspense, lazy } from 'react'
import { Button, Input, Tooltip } from 'antd'
import { ArrowDownOutlined, ArrowUpOutlined, DeleteOutlined, PlusOutlined } from '@ant-design/icons'
import {
  defaultSectionOrder,
  getSectionDisplayName,
  isCustomSection,
  type PlanFields,
  type PlanMeta,
  type PlanOverrides,
  type PlanSection,
  type PlanSectionOrderItem,
} from './planTemplate'

// wangEditor 体积较大，按需加载（进入编辑模式才拉取）
const PlanRichEditor = lazy(() => import('./PlanRichEditor'))

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

/** 只读章节展示值：override || meta */
function fixedDisplayValue(label: string, overrides: PlanOverrides | undefined, meta?: PlanMeta): string {
  const key = FIXED_OVERRIDE_MAP[label]
  if (key && overrides?.[key]?.trim()) return overrides[key]!
  switch (label) {
    case '一、活动名称':
      return meta?.name ?? ''
    case '二、活动主题':
      return meta?.theme ?? ''
    case '五、活动时间':
      return meta?.time ?? ''
    case '六、活动地点':
      return meta?.location ?? ''
    case '七、活动组织单位':
      return meta?.organizer ?? ''
    case '八、活动对象':
      return meta?.target ?? ''
    default:
      return ''
  }
}

/** 时间 override 拆「日期|时间段」 */
function splitTimeOverride(v: string | undefined): { date: string; period: string } {
  const s = v || ''
  const idx = s.indexOf('|')
  return idx >= 0 ? { date: s.slice(0, idx), period: s.slice(idx + 1) } : { date: s, period: '' }
}

export default function WordEditor({
  value,
  onChange,
  meta,
  overrides,
  onOverridesChange,
  sectionOrder,
  onSectionOrderChange,
}: WordEditorProps) {
  const [activeIdx, setActiveIdx] = useState(0)
  const [order, setOrder] = useState<PlanSectionOrderItem[]>(sectionOrder?.length ? sectionOrder : defaultSectionOrder())

  // 外部 sectionOrder 变化（保存后重载）时同步
  useEffect(() => {
    if (sectionOrder && sectionOrder.length > 0) {
      setOrder(sectionOrder)
    }
  }, [sectionOrder])

  const ov = overrides ?? {}

  const sections: PlanSection[] = useMemo(
    () =>
      order.map((it) => ({
        label: it.customLabel || it.label,
        field: it.field,
        hint: it.hint,
        customLabel: it.customLabel,
      })),
    [order],
  )

  const active = sections[activeIdx] ?? sections[0]
  const activeField = active?.field
  const activeItem = order[activeIdx] ?? order[0]
  const activeIsCustom = activeItem ? isCustomSection(activeItem) : false

  const emitOrder = (next: PlanSectionOrderItem[]) => {
    setOrder(next)
    onSectionOrderChange?.(next)
  }

  /** 新增章节：field=null 自定义章节，正文存 section_order.contentHtml */
  const addSection = () => {
    const next = [...order, { label: '新章节', field: null, hint: '自定义章节', contentHtml: '' }]
    emitOrder(next)
    setActiveIdx(next.length - 1)
  }

  const removeSection = (idx: number) => {
    const next = order.filter((_, i) => i !== idx)
    emitOrder(next)
    if (activeIdx >= next.length) setActiveIdx(Math.max(0, next.length - 1))
  }

  const moveSection = (idx: number, dir: -1 | 1) => {
    const target = idx + dir
    if (target < 0 || target >= order.length) return
    const next = [...order]
    const [item] = next.splice(idx, 1)
    next.splice(target, 0, item)
    emitOrder(next)
    setActiveIdx(target)
  }

  /** 双击重命名章节 */
  const renameSection = (idx: number) => {
    const it = order[idx]
    const current = it.customLabel || it.label
    const newLabel = window.prompt('请输入新的章节名称：', current)
    if (newLabel !== null && newLabel.trim()) {
      const next = order.map((s, i) => (i === idx ? { ...s, customLabel: newLabel.trim() } : s))
      emitOrder(next)
    }
  }

  const setOverride = (key: keyof PlanOverrides, val: string) => {
    onOverridesChange?.({ ...ov, [key]: val })
  }

  /** 可编辑章节正文写入对应字段；自定义章节正文存 section_order.contentHtml */
  const handleBodyChange = (html: string) => {
    if (activeIsCustom && activeItem) {
      emitOrder(order.map((s, i) => (i === activeIdx ? { ...s, contentHtml: html } : s)))
      return
    }
    if (!activeField) return
    onChange({ ...value, [activeField]: html })
  }

  const timeParts = splitTimeOverride(ov.timeOverride)
  const isTimeSection = active?.label === '五、活动时间'

  return (
    <div style={{ display: 'flex', gap: 16, alignItems: 'flex-start' }}>
      {/* 左侧章节导航：增删/上下移 + 双击重命名 */}
      <div style={{ width: 200, flexShrink: 0, position: 'sticky', top: 0 }}>
        <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--color-text)', marginBottom: 8 }}>章节导航</div>
        {sections.map((s, i) => (
          <div
            key={`${s.label}-${i}`}
            onClick={() => setActiveIdx(i)}
            title="双击可重命名章节"
            onDoubleClick={() => renameSection(i)}
            className={`word-nav-item${i === activeIdx ? ' word-nav-item-active' : ''}`}
            style={{ display: 'flex', alignItems: 'center', gap: 2 }}
          >
            <span style={{ flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{getSectionDisplayName(s)}</span>
            {i > 0 && (
              <Tooltip title="上移">
                <Button
                  size="small"
                  type="text"
                  icon={<ArrowUpOutlined />}
                  onClick={(e) => {
                    e.stopPropagation()
                    moveSection(i, -1)
                  }}
                />
              </Tooltip>
            )}
            {i < sections.length - 1 && (
              <Tooltip title="下移">
                <Button
                  size="small"
                  type="text"
                  icon={<ArrowDownOutlined />}
                  onClick={(e) => {
                    e.stopPropagation()
                    moveSection(i, 1)
                  }}
                />
              </Tooltip>
            )}
            <Tooltip title="删除章节">
              <Button
                size="small"
                type="text"
                danger
                icon={<DeleteOutlined />}
                onClick={(e) => {
                  e.stopPropagation()
                  removeSection(i)
                }}
              />
            </Tooltip>
          </div>
        ))}
        <Button block icon={<PlusOutlined />} style={{ marginTop: 8 }} onClick={addSection}>
          新增章节
        </Button>
      </div>

      {/* 右侧单个 wangEditor 实例 */}
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ marginBottom: 8, fontSize: 14, fontWeight: 600, color: 'var(--color-text)' }}>
          {active ? getSectionDisplayName(active) : ''}
        </div>

        {activeField || activeIsCustom ? (
          <Suspense fallback={<div style={{ padding: 32, textAlign: 'center', color: 'var(--color-text-secondary)' }}>编辑器加载中...</div>}>
            <PlanRichEditor
              key={activeField || `custom-${activeIdx}`}
              value={activeIsCustom ? activeItem?.contentHtml ?? '' : (value[activeField!] ?? '')}
              onChange={handleBodyChange}
              placeholder={active?.hint ?? '（点击填写）'}
            />
          </Suspense>
        ) : isTimeSection ? (
          <div className="word-paper" style={{ padding: 24 }}>
            <div style={{ display: 'flex', gap: 12, alignItems: 'center', flexWrap: 'wrap' }}>
              <span style={{ fontSize: 13, color: 'var(--color-text-secondary)' }}>日期：</span>
              <Input
                style={{ width: 200 }}
                placeholder="YYYY-MM-DD"
                value={timeParts.date}
                onChange={(e) => setOverride('timeOverride', e.target.value ? `${e.target.value}|${timeParts.period}` : timeParts.period)}
              />
              <span style={{ fontSize: 13, color: 'var(--color-text-secondary)' }}>时间段：</span>
              <Input
                style={{ width: 280 }}
                placeholder="如 9:00-11:00"
                value={timeParts.period}
                onChange={(e) => setOverride('timeOverride', timeParts.date ? `${timeParts.date}|${e.target.value}` : e.target.value)}
              />
            </div>
            <div style={{ marginTop: 12, fontSize: 12, color: 'var(--color-text-secondary)' }}>
              当前活动信息：{fixedDisplayValue(active.label, overrides, meta)}
            </div>
          </div>
        ) : (
          <div className="word-paper" style={{ padding: 24 }}>
            <Input.TextArea
              rows={3}
              placeholder="（点击填写）"
              value={fixedDisplayValue(active?.label ?? '', overrides, meta)}
              onChange={(e) => {
                const key = FIXED_OVERRIDE_MAP[active?.label ?? '']
                if (key) setOverride(key, e.target.value)
              }}
            />
            <div style={{ marginTop: 12, fontSize: 12, color: 'var(--color-text-secondary)' }}>
              当前活动信息：{fixedDisplayValue(active?.label ?? '', undefined, meta)}
            </div>
          </div>
        )}

        <div style={{ marginTop: 8, fontSize: 12, color: 'var(--color-text-secondary)' }}>
          提示：修改章节名称请双击导航中的章节标题；活动名称/主题/时间/地点/组织单位/对象保存后可同步更新活动基本信息。
        </div>
      </div>
    </div>
  )
}

/** 序列化章节顺序 JSON（供父级保存） */
export function toSectionOrderJson(items: PlanSectionOrderItem[]): string {
  return JSON.stringify(items)
}
