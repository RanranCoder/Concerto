import { create } from 'zustand'
import type { AgentActionPlan, AgentArtifact, AgentCitation } from '@/api/agent'

/**
 * 灵思右侧实体面板（UI v2 §A「产物分屏」）。
 *
 * 为什么单独开一个 store 而不是把状态挂在 AgentHome 上：
 * 打开面板的动作发生在**消息流深处**（产物卡的一行、引用 chip、清单卡的「查看全文」），
 * 用 props 一路穿下去会让每个中间组件都被迫感知面板的存在，
 * 新增一个可打开的产物就要动一条 props 链。zustand 在这里是"消息流 → 面板"的直连通道。
 *
 * 与旧方案的差别：原来「查看全文」是一个 720px 的 Drawer 遮罩，罩住整个对话；
 * 用户看完只能关掉，没法一边看清单一边看正文。分屏让"对照阅读"成立。
 */

export type PanelItemKind = 'artifact' | 'text'

export interface PanelItem {
  /** 稳定去重键：同类型同 id 的产物重复打开不产生新 tab */
  id: string
  kind: PanelItemKind
  /** tab 标题（短） */
  title: string
  entityType?: string
  entityId?: number | string | null
  /** 完整名称（正文标题用，比 tab 标题长） */
  name?: string
  path?: string | null
  /** kind === 'text' 时的正文（Markdown 原文，渲染前会过净化） */
  text?: string
  plan?: AgentActionPlan
  citations?: AgentCitation[]
  source?: AgentCitation
}

interface PanelState {
  items: PanelItem[]
  activeId: string | null
  /** 收起态：窄屏默认收起，宽屏默认展开 */
  collapsed: boolean
  /**
   * 面板宽度（拖拽分隔条可调，320–720）。
   * 不放 CSS 常量的原因：它要跨会话记住用户的偏好——上一次拉到多宽，这一次就该多宽。
   */
  width: number

  openArtifact: (a: AgentArtifact) => void
  openText: (id: string, title: string, text: string, context?: Pick<PanelItem, 'plan' | 'citations' | 'source'>) => void
  activate: (id: string) => void
  close: (id: string) => void
  closeAll: () => void
  toggle: () => void
  setCollapsed: (v: boolean) => void
  setWidth: (w: number) => void
}

const WIDTH_KEY = 'lingsi.panel.width'
const WIDTH_MIN = 320
const WIDTH_MAX = 720

function loadWidth(): number {
  try {
    const v = Number(localStorage.getItem(WIDTH_KEY))
    if (Number.isFinite(v) && v >= WIDTH_MIN && v <= WIDTH_MAX) return Math.round(v)
  } catch {
    /* 隐私模式/无 localStorage：退回默认值，不影响功能 */
  }
  return 350
}

function saveWidth(w: number) {
  try {
    localStorage.setItem(WIDTH_KEY, String(Math.round(w)))
  } catch {
    /* 同上 */
  }
}

/** 同一产物重复打开时复用同一个 tab（entityId 可能是 null，用 name 兜底） */
function artifactKey(a: AgentArtifact): string {
  const tail = a.entityId ?? a.name
  return `a:${a.entityType}:${tail}`
}

export const useAgentPanel = create<PanelState>()((set) => ({
  items: [],
  activeId: null,
  collapsed: true,
  width: loadWidth(),

  openArtifact: (a) =>
    set((s) => {
      const id = artifactKey(a)
      const exists = s.items.some((it) => it.id === id)
      const item: PanelItem = {
        id,
        kind: 'artifact',
        title: shortTitle(a.name),
        entityType: a.entityType,
        entityId: a.entityId,
        name: a.name,
        path: a.path ?? null,
      }
      return {
        items: exists ? s.items : [...s.items, item],
        activeId: id,
        collapsed: false,
      }
    }),

  openText: (id, title, text, context) =>
    set((s) => {
      const key = `t:${id}`
      const exists = s.items.some((it) => it.id === key)
      return {
        items: exists ? s.items.map(it => it.id === key ? { ...it, title, text, ...context } : it)
          : [...s.items, { id: key, kind: 'text', title, text, ...context }],
        activeId: key,
        collapsed: false,
      }
    }),

  activate: (id) => set({ activeId: id, collapsed: false }),

  close: (id) =>
    set((s) => {
      const items = s.items.filter((it) => it.id !== id)
      const activeId =
        s.activeId === id ? (items.length > 0 ? items[items.length - 1].id : null) : s.activeId
      return { items, activeId }
    }),

  closeAll: () => set({ items: [], activeId: null, collapsed:true }),

  toggle: () => set((s) => ({ collapsed: !s.collapsed })),

  setCollapsed: (v) => set({ collapsed: v }),

  /** 拖拽调宽：先夹在区间内再落库，拖动过程中每帧都会进来 */
  setWidth: (w) => {
    const clamped = Math.min(WIDTH_MAX, Math.max(WIDTH_MIN, Math.round(w)))
    set({ width: clamped })
    saveWidth(clamped)
  },
}))

/** tab 标题超过 10 字就截断——tab 条是横向的，长标题会把别的 tab 挤出去 */
function shortTitle(name: string): string {
  const t = (name ?? '').trim()
  if (!t) return '详情'
  return t.length > 10 ? `${t.slice(0, 10)}…` : t
}
