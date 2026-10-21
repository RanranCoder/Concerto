import type { OrgAccount, OrgUnitNode } from '@/api/org'

export const ORG_TYPE_LABELS: Record<string, string> = {
  PARTY: '党建办公室', STUDENT_UNION: '学生会', CLUB: '社团',
  VOLUNTEER: '青年志愿者协会', SCIENCE: '科技协会', OTHER: '其他组织',
}
export const PACK_LABELS: Record<string, string> = { party: '党建包', volunteer: '志愿包', club: '社团包' }

export function buildStructureIndex(nodes: OrgUnitNode[], accounts: OrgAccount[]) {
  const byId = new Map<number, OrgUnitNode>()
  const paths = new Map<number, OrgUnitNode[]>()
  const totals = new Map<number, number>()
  const direct = new Map<number, OrgAccount[]>()
  for (const account of accounts) {
    if (account.deptId !== null) {
      const group = direct.get(account.deptId) ?? []
      group.push(account)
      direct.set(account.deptId, group)
    }
  }
  function visit(items: OrgUnitNode[], parentPath: OrgUnitNode[]) {
    let total = 0
    for (const node of items) {
      byId.set(node.id, node)
      paths.set(node.id, [...parentPath, node])
      const count = (direct.get(node.id)?.length ?? 0) + visit(node.children ?? [], [...parentPath, node])
      totals.set(node.id, count)
      total += count
    }
    return total
  }
  visit(nodes, [])
  return { byId, paths, totals, direct, unassigned: accounts.filter(a => a.deptId === null || !byId.has(a.deptId)) }
}

/** Keep ancestors of matching descendants so search always preserves hierarchy. */
export function filterStructure(nodes: OrgUnitNode[], query: string): OrgUnitNode[] {
  const text = query.trim().toLocaleLowerCase()
  if (!text) return nodes
  return nodes.flatMap(node => {
    const children = filterStructure(node.children ?? [], text)
    return node.name.toLocaleLowerCase().includes(text) ? [node] : children.length ? [{ ...node, children }] : []
  })
}
