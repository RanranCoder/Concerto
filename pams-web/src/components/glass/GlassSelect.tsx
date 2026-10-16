import { useCallback, useEffect, useId, useLayoutEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import '@/styles/select.css'

export interface GlassSelectOption<T extends string | number> {
  value: T
  label: string
  disabled?: boolean
}

interface Props<T extends string | number> {
  value: T
  options: GlassSelectOption<T>[]
  onChange: (value: T) => void
  /** 可访问名称：触发器不显示该文案时必填（与 antd Select 的 aria-label 用法一致） */
  ariaLabel?: string
  disabled?: boolean
  /** 追加在根节点上的类名：调用方按场景调尺寸与配色 */
  className?: string
}

interface PanelPos {
  left: number
  top?: number
  bottom?: number
  minWidth: number
}

/**
 * 自建下拉选框：全站统一，不再出现浏览器原生 select 的观感。
 *
 * <p>原生 select 的**弹层由操作系统绘制**，只能改到收起态那一条，展开后必然回到系统默认样式，
 * 明暗主题也跟不上 —— 所以这里退回 button + listbox 自绘。
 *
 * <p>交互按 WAI-ARIA combobox 规范：焦点始终留在触发器上，用 aria-activedescendant 表达高亮，
 * 方向键 / Home / End / Enter / Space / Esc / Tab 与原生 select 一致。
 *
 * <p>弹层 portal 到 body、fixed 定位：宿主容器多数是 overflow: hidden 的卡片，
 * 就地渲染会被裁掉；滚动或缩放时跟随触发器重算位置。
 */
export default function GlassSelect<T extends string | number>({
  value,
  options,
  onChange,
  ariaLabel,
  disabled = false,
  className,
}: Props<T>) {
  const [open, setOpen] = useState(false)
  const [active, setActive] = useState(0)
  const [pos, setPos] = useState<PanelPos | null>(null)
  const rootRef = useRef<HTMLDivElement>(null)
  const triggerRef = useRef<HTMLButtonElement>(null)
  const panelRef = useRef<HTMLDivElement>(null)
  const listId = useId()

  const optionId = (i: number) => `${listId}-option-${i}`
  const selectedIndex = options.findIndex((o) => o.value === value)

  const close = useCallback(() => setOpen(false), [])

  const openList = () => {
    const start = selectedIndex >= 0 ? selectedIndex : options.findIndex((o) => !o.disabled)
    setActive(start >= 0 ? start : 0)
    setOpen(true)
  }

  const toggle = () => {
    if (disabled) return
    if (open) close()
    else openList()
  }

  // 定位：默认贴触发器下沿；下方空间不足且上方更宽裕时翻到上方，右侧溢出则整体左移
  useLayoutEffect(() => {
    if (!open) {
      setPos(null)
      return
    }
    const compute = () => {
      const el = triggerRef.current
      if (!el) return
      const rect = el.getBoundingClientRect()
      const width = panelRef.current?.offsetWidth ?? rect.width
      const height = panelRef.current?.offsetHeight ?? 0
      const below = window.innerHeight - rect.bottom - 6
      const above = rect.top - 6
      const openUp = height > below && above > below
      setPos({
        left: Math.max(8, Math.min(rect.left, window.innerWidth - width - 8)),
        top: openUp ? undefined : rect.bottom + 6,
        bottom: openUp ? window.innerHeight - rect.top + 6 : undefined,
        minWidth: rect.width,
      })
    }
    compute()
    window.addEventListener('resize', compute)
    window.addEventListener('scroll', compute, true)
    return () => {
      window.removeEventListener('resize', compute)
      window.removeEventListener('scroll', compute, true)
    }
  }, [open])

  // 点外部关闭：弹层挂在 body 上，触发器与弹层两侧都要判
  useEffect(() => {
    if (!open) return
    const onPointerDown = (e: MouseEvent) => {
      const target = e.target as Node
      if (rootRef.current?.contains(target) || panelRef.current?.contains(target)) return
      close()
    }
    document.addEventListener('mousedown', onPointerDown)
    return () => document.removeEventListener('mousedown', onPointerDown)
  }, [open, close])

  // 键盘移动后把高亮滚进可视区（选项较多时用得上；jsdom 无布局，可选调用兜底）
  useEffect(() => {
    if (!open) return
    panelRef.current
      ?.querySelector<HTMLElement>('[data-active="true"]')
      ?.scrollIntoView?.({ block: 'nearest' })
  }, [open, active])

  const move = (step: 1 | -1) => {
    for (let i = active + step; i >= 0 && i < options.length; i += step) {
      if (!options[i].disabled) {
        setActive(i)
        return
      }
    }
  }

  const firstEnabled = () => options.findIndex((o) => !o.disabled)
  const lastEnabled = () => {
    for (let i = options.length - 1; i >= 0; i--) if (!options[i].disabled) return i
    return -1
  }

  const commit = (i: number) => {
    const option = options[i]
    if (!option || option.disabled) return
    if (option.value !== value) onChange(option.value)
    close()
  }

  const onKeyDown = (e: React.KeyboardEvent<HTMLButtonElement>) => {
    if (disabled) return
    if (!open) {
      // 收起态：Space/Enter 会另外触发 click，这里 preventDefault 掉，避免开一次又关一次
      if (e.key === 'ArrowDown' || e.key === 'ArrowUp' || e.key === 'Enter' || e.key === ' ') {
        e.preventDefault()
        openList()
      }
      return
    }
    switch (e.key) {
      case 'ArrowDown':
        e.preventDefault()
        move(1)
        break
      case 'ArrowUp':
        e.preventDefault()
        move(-1)
        break
      case 'Home': {
        e.preventDefault()
        const i = firstEnabled()
        if (i >= 0) setActive(i)
        break
      }
      case 'End': {
        e.preventDefault()
        const i = lastEnabled()
        if (i >= 0) setActive(i)
        break
      }
      case 'Enter':
      case ' ':
        e.preventDefault()
        commit(active)
        break
      case 'Escape':
        e.preventDefault()
        close()
        break
      case 'Tab':
        close()
        break
    }
  }

  return (
    <div ref={rootRef} className={className ? `glass-select ${className}` : 'glass-select'}>
      <button
        ref={triggerRef}
        type="button"
        className="glass-select-trigger"
        role="combobox"
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-controls={open ? listId : undefined}
        aria-activedescendant={open ? optionId(active) : undefined}
        aria-label={ariaLabel}
        disabled={disabled}
        onClick={toggle}
        onKeyDown={onKeyDown}
      >
        <span className="glass-select-value">{options[selectedIndex]?.label ?? ''}</span>
        <svg
          className="glass-select-caret"
          width="10"
          height="10"
          viewBox="0 0 10 10"
          aria-hidden="true"
        >
          <path
            d="M2.2 3.9 5 6.7l2.8-2.8"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.4"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        </svg>
      </button>

      {open &&
        createPortal(
          <div
            ref={panelRef}
            id={listId}
            className="glass-select-panel"
            role="listbox"
            aria-label={ariaLabel}
            style={{
              left: pos?.left,
              top: pos?.top,
              bottom: pos?.bottom,
              minWidth: pos?.minWidth,
              visibility: pos ? 'visible' : 'hidden',
            }}
          >
            {options.map((option, i) => (
              <div
                key={String(option.value)}
                id={optionId(i)}
                className="glass-select-option"
                role="option"
                aria-selected={option.value === value}
                aria-disabled={option.disabled || undefined}
                data-active={i === active || undefined}
                onMouseEnter={() => !option.disabled && setActive(i)}
                onMouseDown={(e) => e.preventDefault()}
                onClick={() => commit(i)}
              >
                {option.label}
              </div>
            ))}
          </div>,
          document.body,
        )}
    </div>
  )
}
