import { create } from 'zustand'
import { useAuthStore } from './auth'
import { listAgentArtifacts } from '@/api/agent'
import type { AgentActionPlan, AgentArtifact, AgentCitation, AgentArtifactSummary } from '@/api/agent'

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

export type PanelItemKind = 'artifact' | 'text' | 'version'

export interface PanelItem {
  /** 稳定去重键：同类型同 id 的产物重复打开不产生新 tab */
  id: string
  artifact?: AgentArtifactSummary
  selectedVersion?: number
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
  drafts: Record<number,{version:number;fields:Record<string,string>}>
  rememberDraft: (id:number,version:number,fields:Record<string,string>) => void
  discardDraft: (id:number) => void
  conversationId: number | null
  catalog: AgentArtifactSummary[]
  syncConversation: (id: number) => Promise<void>
  openVersion: (artifact: AgentArtifactSummary, version?: number) => void
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

let catalogRequest=0
export const useAgentPanel = create<PanelState>()((set,get) => ({
  drafts:{},
  rememberDraft:(id,version,fields)=>set(s=>({drafts:{...s.drafts,[id]:{version,fields}}})),
  discardDraft:id=>set(s=>({drafts:Object.fromEntries(Object.entries(s.drafts).filter(([key])=>Number(key)!==id))})),
  conversationId:null,
  catalog:[],
  syncConversation: async (id) => {
    const request=++catalogRequest
    if(get().conversationId!==id) set({conversationId:id,catalog:[],items:[],activeId:null,collapsed:true})
    try {
      let catalog=await listAgentArtifacts(id)
      if(request!==catalogRequest || get().conversationId!==id || !Array.isArray(catalog)) return
      catalog=catalog.map(candidate=>{
        const known=get().catalog.find(a=>a.id===candidate.id)
        return known && (candidate.currentVersion<known.currentVersion || candidate.currentVersion===known.currentVersion && (candidate.taskVersion ?? 0)<(known.taskVersion ?? 0))?known:candidate
      })
      const first=get().catalog.length===0 && catalog.length>0
      set(s=>({catalog,items:s.items.map(it=>it.artifact?{...it,artifact:catalog.find(a=>a.id===it.artifact!.id) ?? it.artifact}:it)}))
      if(first && !get().activeId) get().openVersion(catalog.at(-1)!)
    } catch { /* Existing previews remain available; reconnect or the next state change retries the index. */ }
  },
  openVersion: (artifact,version) => set(s=>{
    const id=`v:${artifact.id}`
    const item:PanelItem={id,kind:'version',title:shortTitle(artifact.title),artifact,selectedVersion:version ?? artifact.currentVersion}
    return {items:s.items.some(it=>it.id===id)?s.items.map(it=>it.id===id?item:it):[...s.items,item],activeId:id,collapsed:false}
  }),
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

  closeAll: () => {catalogRequest++;set({ conversationId:null,catalog:[],items: [], activeId: null, collapsed:true })},

  toggle: () => {
    const s=get();if(!s.items.length && s.catalog.length) {get().openVersion(s.catalog.at(-1)!);return}
    set({collapsed:!s.collapsed})
  },

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

useAuthStore.subscribe((next,previous)=>{
  if(next.user?.id!==previous.user?.id || next.user?.orgId!==previous.user?.orgId || !next.token && previous.token) {
    useAgentPanel.getState().closeAll();useAgentPanel.setState({drafts:{}})
  }
})
