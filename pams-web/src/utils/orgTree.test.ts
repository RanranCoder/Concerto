import { describe, expect, it } from 'vitest'
import type { OrgUnitInput } from '@/api/org'
import { countUnits, removeUnitAt, trimUnits, updateUnitAt, validateUnits } from './orgTree'

const tree = (): OrgUnitInput[] => [
  { name: '组织部', unitType: '部门', children: [{ name: '策划组', unitType: '小组', children: [{ name: '执行分队', unitType: '分队' }] }] },
  { name: '宣传部', unitType: '部门' },
]

describe('registration organization tree', () => {
  it('edits a grandchild from the root without losing its ancestors or siblings', () => {
    const before = tree()
    const after = updateUnitAt(before, [0, 0, 0], (node) => ({ ...node, name: '现场分队' }))
    expect(after[0].children?.[0].children?.[0].name).toBe('现场分队')
    expect(after[0].name).toBe('组织部')
    expect(after[1]).toBe(before[1])
    expect(before[0].children?.[0].children?.[0].name).toBe('执行分队')
  })
  it('adds and removes descendants without replacing the root with a child array', () => {
    const before = tree()
    const after = updateUnitAt(before, [0, 0, 0], (node) => ({ ...node, children: [{ name: '协调组' }] }))
    expect(countUnits(after)).toBe(5)
    expect(after.map((node) => node.name)).toEqual(['组织部', '宣传部'])
    const removed = removeUnitAt(after, [0, 0, 0])
    expect(removed[0].children?.[0].children).toEqual([])
    expect(countUnits(removed)).toBe(3)
    expect(countUnits(before)).toBe(4)
  })
  it('removes a whole branch while retaining other roots', () => {
    expect(removeUnitAt(tree(), [0])).toEqual([{ name: '宣传部', unitType: '部门' }])
  })
  it('rejects empty or duplicate nested names so no child is silently lost at submission', () => {
    expect(validateUnits([])).toBeTruthy()
    expect(validateUnits([{ name: '组织部', children: [{ name: ' ' }] }])).toContain('名称')
    expect(validateUnits([{ name: '组织部', children: [{ name: ' 组织部 ' }] }])).toContain('重复')
    expect(validateUnits(tree())).toBeNull()
    expect(trimUnits([{ name: ' 组织部 ', children: [{ name: ' 策划组 ' }] }])[0].children?.[0].name).toBe('策划组')
  })
})
