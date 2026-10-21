import type { OrgUnitInput } from '@/api/org'

export type UnitPath = number[]
export const UNIT_TYPES = ['部门', '中心', '小组', '分队']

/** 所有路径始终相对于根数组，递归视图不能把子树写回根。 */
export function updateUnitAt(nodes: OrgUnitInput[], path: UnitPath, update: (node: OrgUnitInput) => OrgUnitInput): OrgUnitInput[] {
  const [index, ...rest] = path
  return nodes.map((node, i) => i !== index ? node : rest.length
    ? { ...node, children: updateUnitAt(node.children ?? [], rest, update) }
    : update(node))
}

export function removeUnitAt(nodes: OrgUnitInput[], path: UnitPath): OrgUnitInput[] {
  const [index, ...rest] = path
  return rest.length
    ? nodes.map((node, i) => i === index ? { ...node, children: removeUnitAt(node.children ?? [], rest) } : node)
    : nodes.filter((_, i) => i !== index)
}

export function countUnits(nodes: OrgUnitInput[]): number {
  return nodes.reduce((total, node) => total + 1 + countUnits(node.children ?? []), 0)
}

export function validateUnits(nodes: OrgUnitInput[]): string | null {
  if (!nodes.length) return '请至少添加一个部门或小组'
  const names = new Set<string>()
  const walk = (items: OrgUnitInput[]): string | null => {
    for (const node of items) {
      const name = node.name.trim()
      if (!name) return '请填写每个部门或小组的名称，或删除空白节点'
      if (name.length > 50) return '节点名称请控制在 50 字以内'
      if (names.has(name)) return `「${name}」名称重复，请使用不同的名称`
      names.add(name)
      const error = walk(node.children ?? [])
      if (error) return error
    }
    return null
  }
  return walk(nodes)
}

export function trimUnits(nodes: OrgUnitInput[]): OrgUnitInput[] {
  return nodes.map((node) => ({ ...node, name: node.name.trim(), children: trimUnits(node.children ?? []) }))
}
