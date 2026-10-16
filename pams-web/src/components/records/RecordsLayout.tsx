import type { ReactNode } from 'react'
import { NavLink } from 'react-router-dom'
import { useHasPerm } from '@/components/glass/RequireRole'

export function RecordsNav({ section }: { section: 'content' | 'archive' | 'party' }) {
  const hasPerm = useHasPerm()
  const items = section === 'content'
    ? [{ path: '/content/articles', label: '推文', visible: true }, { path: '/content/news', label: '新闻稿', visible: hasPerm('content:view') }]
    : section === 'party'
      ? [{ path: '/party/members', label: '发展台账', visible: true }, { path: '/party/rosters', label: '名册管理', visible: true }]
      : [
          { path: '/archive/materials', label: '材料库', visible: hasPerm('material:view') },
          { path: '/archive/templates', label: '模板库', visible: hasPerm('template:view') },
          { path: '/archive/credits', label: '素拓加分', visible: hasPerm('quality:view') },
          { path: '/archive/announcements', label: '通知公告', visible: hasPerm('notice:view') },
          { path: '/archive/group-chats', label: '群聊管理', visible: hasPerm('chat:view') },
        ]
  return <nav className="records-nav" aria-label={section === 'party' ? '党务台账' : section === 'archive' ? '材料与档案' : '内容宣传'}>
    {items.filter(i => i.visible).map(i => <NavLink key={i.path} to={i.path}>{i.label}</NavLink>)}
  </nav>
}

export function RecordsSummary({ items }: { items: { label: string; value: ReactNode; note?: string }[] }) {
  return <div className="records-summary">{items.map(i => <div key={i.label}><span>{i.label}</span><p><strong>{i.value}</strong>{i.note && <small>{i.note}</small>}</p></div>)}</div>
}

export function RecordsDirectory({ title, items, value, onChange }: { title: string; items: { key: string; label: string; count?: number }[]; value: string; onChange: (key: string) => void }) {
  return <aside className="records-directory"><h3>{title}</h3><div>{items.map(i => <button key={i.key} type="button" aria-pressed={value === i.key} onClick={() => onChange(i.key)}><span>{i.label}</span>{i.count != null && <small>{i.count}</small>}</button>)}</div></aside>
}

export function RecordsName({ name, meta, avatar = false }: { name: string; meta?: ReactNode; avatar?: boolean }) {
  return <div className="records-name">{avatar && <span className="records-avatar" aria-hidden="true">{name.slice(0, 1)}</span>}<div><strong>{name}</strong>{meta && <small>{meta}</small>}</div></div>
}

export function FileMark({ name }: { name: string }) {
  const extension = name.includes('.') ? name.split('.').pop()?.toUpperCase().slice(0, 5) : '文件'
  return <span className="records-filemark" aria-hidden="true">{extension}</span>
}
