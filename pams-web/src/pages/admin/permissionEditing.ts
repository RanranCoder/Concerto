import type { CatalogPermission, Grant } from '@/api/permission'

export function grantKey(grant?: Grant) {
  return grant
    ? `${grant.permission}:${grant.scope}:${grant.packVersion ?? ''}`
    : ''
}

export function grantChanges(before: Grant[], after: Grant[]) {
  const previous = new Map(before.map((g) => [g.permission, g]))
  const next = new Map(after.map((g) => [g.permission, g]))
  return [...new Set([...previous.keys(), ...next.keys()])].flatMap((code) => {
    const old = previous.get(code),
      value = next.get(code)
    return grantKey(old) === grantKey(value) ? [] : [{ code, old, next: value }]
  })
}

/** Return the complete local change; reject the whole edit if any dependency is read-only. */
export function togglePermission(
  catalog: CatalogPermission[],
  grants: Grant[],
  code: string,
  enabled: boolean,
) {
  const entries = new Map(catalog.map((p) => [p.code, p]))
  const next = new Map(grants.map((g) => [g.permission, g]))
  const changed: string[] = []
  const visited = new Set<string>()
  function enable(value: string) {
    if (visited.has(value)) return
    visited.add(value)
    const permission = entries.get(value)
    if (!permission) throw new Error(`权限目录缺少依赖：${value}`)
    if (!next.has(value)) {
      if (!permission.grantable)
        throw new Error(
          `${permission.name}：${permission.reason || '超出可授权清单'}`,
        )
      next.set(value, {
        permission: value,
        scope: 'ORG',
        ...(permission.packVersion
          ? { packVersion: permission.packVersion }
          : {}),
      })
      changed.push(value)
    }
    permission.dependencies?.forEach(enable)
  }
  if (enabled) enable(code)
  else {
    const removed = new Set([code])
    let expanded = true
    while (expanded) {
      expanded = false
      for (const permission of catalog) {
        if (
          next.has(permission.code) &&
          !removed.has(permission.code) &&
          permission.dependencies?.some((d) => removed.has(d))
        ) {
          removed.add(permission.code)
          expanded = true
        }
      }
    }
    for (const value of removed) {
      if (!entries.get(value)?.grantable)
        throw new Error(
          `${entries.get(value)?.name ?? value} 超出可授权清单，不能连带撤销`,
        )
      if (next.delete(value)) changed.push(value)
    }
  }
  return { grants: [...next.values()], changed }
}
