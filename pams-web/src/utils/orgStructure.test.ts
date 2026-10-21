import { describe, expect, it } from 'vitest'
import { buildStructureIndex, filterStructure } from './orgStructure'
import type { OrgAccount, OrgUnitNode } from '@/api/org'

const node = (id: number, name: string, parentId: number | null = null, children: OrgUnitNode[] = []): OrgUnitNode => ({
  id, name, parentId, children, unitType: '部门', code: null, level: null, path: null, leaderUserId: null, sortOrder: 0,
})
const nodes = [node(1, '组织部', null, [node(2, '培训组', 1, [node(3, '服务分队', 2)])]), node(4, '宣传部')]
const accounts: OrgAccount[] = [
  {id: 1, username:'admin',realName:'管理员',deptId:1,roleName:'总负责人',status:1},
  {id: 2, username:'disabled',realName:'已停用',deptId:3,roleName:'干事',status:0},
  {id: 3, username:'unassigned',realName:null,deptId:null,roleName:null,status:1},
  {id: 4, username:'missing',realName:null,deptId:999,roleName:null,status:1},
]
describe('organization hierarchy and account relationships', () => {
  it('counts disabled accounts in descendants, keeps direct accounts separate and identifies unassigned accounts', () => {
    const index = buildStructureIndex(nodes, accounts)
    expect(index.totals.get(1)).toBe(2)
    expect(index.totals.get(2)).toBe(1)
    expect(index.direct.get(1)?.map(a => a.id)).toEqual([1])
    expect(index.unassigned.map(a => a.id)).toEqual([3, 4])
    expect(index.paths.get(3)?.map(n => n.id)).toEqual([1,2,3])
  })
  it('keeps complete ancestry for descendant matches without changing the original tree', () => {
    const result = filterStructure(nodes, '服务')
    expect(result.map(n => n.id)).toEqual([1])
    expect(result[0].children[0].children[0].id).toBe(3)
    expect(nodes).toHaveLength(2)
    expect(filterStructure(nodes, '   ')).toBe(nodes)
    expect(filterStructure(nodes, '不存在')).toEqual([])
  })
})
