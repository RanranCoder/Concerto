/** Normalize links from historical messages as well as current tool results. */
export function agentBusinessLink(link?: string | null): string | null {
  if (!link) return null
  const activity = /^\/activity\/(\d+)(?:\/(tasks|seat|signin))?(\?.*)?$/.exec(link)
  if (activity) {
    const root = `/activities/${activity[1]}`
    return !activity[2] ? root + (activity[3] ?? '')
      : activity[2] === 'tasks' ? `${root}/gantt` : `${root}?tab=${activity[2]}`
  }
  const member = /^\/(party\/)?member\?id=(\d+)$/.exec(link)
  if (member) return `/${member[1] ?? ''}members/${member[2]}`
  const aliases: Record<string, string> = {
    '/content/article': '/content/articles', '/archive/material': '/archive/materials',
    '/archive/credit': '/archive/credits', '/archive/announcement': '/archive/announcements',
    '/notification': '/notifications',
  }
  const [pathname] = link.split('?')
  return aliases[pathname] ? aliases[pathname] + link.slice(pathname.length) : link
}
