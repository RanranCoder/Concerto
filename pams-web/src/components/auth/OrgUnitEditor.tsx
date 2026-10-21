import { useState } from 'react'
import { Button, Input } from 'antd'
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons'
import GlassSelect from '@/components/glass/GlassSelect'
import type { OrgUnitInput } from '@/api/org'
import { UNIT_TYPES as UNIT_TYPE_NAMES, type UnitPath } from '@/utils/orgTree'

const UNIT_TYPES = UNIT_TYPE_NAMES.map((value) => ({ value, label: value }))

export default function OrgUnitEditor({ nodes, path = [], onUpdate, onAdd, onRemove }: {
  nodes: OrgUnitInput[]
  path?: UnitPath
  onUpdate: (path: UnitPath, patch: Partial<OrgUnitInput>) => void
  onAdd: (path: UnitPath) => void
  onRemove: (path: UnitPath) => void
}) {
  const [removing, setRemoving] = useState<string | null>(null)
  return (
    <ul className="unit-tree">
      {nodes.map((node, i) => {
        const current = [...path, i]
        const key = current.join('.')
        const label = current.map((n) => n + 1).join('.')
        return (
          <li key={key}>
            <div className="unit-row">
              <span className="unit-index" aria-hidden="true">{label}</span>
              <Input aria-label={`节点 ${label} 名称`} placeholder={path.length ? '下级小组名称' : '如：组织部'} value={node.name} maxLength={50}
                onChange={(e) => onUpdate(current, { name: e.target.value })} />
              <GlassSelect ariaLabel={`节点 ${label} 类型`} value={node.unitType ?? '部门'} options={UNIT_TYPES}
                onChange={(unitType) => onUpdate(current, { unitType })} />
              <div className="unit-actions">
                <Button type="text" icon={<PlusOutlined />} aria-label={`为节点 ${label} 添加子节点`} onClick={() => onAdd(current)}>子节点</Button>
                <Button type="text" danger icon={<DeleteOutlined />} aria-label={`删除节点 ${label}`}
                  onClick={() => node.children?.length ? setRemoving(removing === key ? null : key) : onRemove(current)} />
              </div>
            </div>
            {removing === key && <div className="unit-remove-confirm" role="alert">
              <span>将删除「{node.name || '未命名节点'}」及其全部下级。</span>
              <Button size="small" onClick={() => setRemoving(null)}>取消</Button>
              <Button size="small" danger onClick={() => { onRemove(current); setRemoving(null) }}>确认删除</Button>
            </div>}
            {!!node.children?.length && <OrgUnitEditor nodes={node.children} path={current} onUpdate={onUpdate} onAdd={onAdd} onRemove={onRemove} />}
          </li>
        )
      })}
    </ul>
  )
}
