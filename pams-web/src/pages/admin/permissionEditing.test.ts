import { describe, expect, it } from 'vitest'
import { grantChanges, togglePermission } from './permissionEditing'
import type { CatalogPermission, Grant } from '@/api/permission'

const catalog: CatalogPermission[] = [
  {
    id: 1,
    code: 'activity:view',
    name: '查看活动',
    module: 'activity',
    grantable: true,
    supportedScopes: ['ORG'],
    reason: '',
    dependencies: [],
  },
  {
    id: 2,
    code: 'plan:view',
    name: '查看策划',
    module: 'plan',
    grantable: true,
    supportedScopes: ['ORG'],
    reason: '',
    dependencies: ['activity:view'],
  },
  {
    id: 3,
    code: 'plan:review',
    name: '审核策划',
    module: 'plan',
    grantable: true,
    supportedScopes: ['ORG'],
    reason: '',
    dependencies: ['plan:view'],
  },
]
describe('permission editor', () => {
  it('adds transitive dependencies and removes dependent operations together', () => {
    const selected = togglePermission(catalog, [], 'plan:review', true)
    expect(selected.grants.map((g) => g.permission).sort()).toEqual([
      'activity:view',
      'plan:review',
      'plan:view',
    ])
    expect(
      togglePermission(catalog, selected.grants, 'activity:view', false).grants,
    ).toEqual([])
  })
  it('rejects a dependency outside the delegation ceiling without mutating local grants', () => {
    const grants: Grant[] = []
    expect(() =>
      togglePermission(
        catalog.map((p) =>
          p.code === 'activity:view' ? { ...p, grantable: false } : p,
        ),
        grants,
        'plan:review',
        true,
      ),
    ).toThrow('查看活动')
    expect(grants).toEqual([])
  })
  it('does not remove read-only dependent permissions', () => {
    const grants = catalog.map((p) => ({
      permission: p.code,
      scope: 'ORG' as const,
    }))
    expect(() =>
      togglePermission(
        catalog.map((p) =>
          p.code === 'plan:review' ? { ...p, grantable: false } : p,
        ),
        grants,
        'activity:view',
        false,
      ),
    ).toThrow('不能连带撤销')
  })
  it('compares authorization values independently of JSON property order', () => {
    expect(
      grantChanges(
        [{ permission: 'party:view', scope: 'ORG', packVersion: 1 }],
        [{ scope: 'ORG', packVersion: 1, permission: 'party:view' }],
      ),
    ).toEqual([])
    expect(
      grantChanges(
        [{ permission: 'party:view', scope: 'ORG', packVersion: 1 }],
        [{ permission: 'party:view', scope: 'ORG', packVersion: 2 }],
      ),
    ).toHaveLength(1)
  })
})
