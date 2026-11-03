import { test, expect } from '@playwright/test'
import { mkdir } from 'node:fs/promises'
import path from 'node:path'

test.use({ channel: process.platform === 'win32' ? 'msedge' : undefined })
test('SSE uses Bearer and read reconciliation preserves the plan action', async ({ page }) => {
  page.on('pageerror', error => console.error('Notification browser error:', error.message))
  page.on('console', message => { if (message.type() === 'error') console.error('Notification console:', message.text()) })
  const user = { id: 1, username: 'reviewer', realName: '审核人', orgId: 1, roleCode: 'TEACHER', roleLevel: 5, permissions: ['activity:view', 'plan:view', 'plan:review'], managementCapabilities: [] }
  await page.addInitScript(user => {
    localStorage.setItem('pams_token', JSON.stringify({ state: { token: 'notification-e2e', user }, version: 1 }))
  }, user)
  let read = false, streamBearer = ''
  const pending = { total: 1, overdue: 0, counts: { PLAN_REVIEW: 1 }, items: [{ key: 'PLAN:11', type: 'PLAN_REVIEW', entityType: 'PLAN', entityId: 11, activityId: 7, title: '活动策划书审核', action: '审核策划书', dueAt: null, overdue: false, progress: null, assignee: null }] }
  const notice = () => ({ id: 101, type: 'PLAN_SUBMITTED', title: '策划书待审核', content: '您被选为审核人，请查看本次提交版本',
    entityType: 'PLAN', entityId: 11, activityId: 7, actionState: 'open', priority: 'NORMAL', senderName: '提交人', read, createdAt: '2026-10-28T09:00:00', notify: true })
  await page.route('**/ws/**', route => route.abort())
  await page.route(url => url.pathname.startsWith('/api/'), async route => {
    const path = new URL(route.request().url()).pathname
    if (path === '/api/notifications/stream') {
      streamBearer = route.request().headers().authorization
      await route.fulfill({ contentType: 'text/event-stream', body: 'event: ready\ndata: {"protocolVersion":1}\n\n' })
      return
    }
    let data: unknown = []
    if (path === '/api/auth/me') data = user
    else if (path === '/api/orgs/current') data = { id: 1, name: '通知验证组织', enabledPacks: null, themeJson: null }
    else if (path === '/api/notifications/101/read') read = true
    else if (path === '/api/notifications/read-all') read = true
    else if (path === '/api/notifications') data = [notice()]
    else if (path === '/api/notifications/page') data = { records: [notice()], total: 1, current: 1, size: 10 }
    else if (path === '/api/notifications/unread-count') data = read ? 0 : 1
    else if (path === '/api/notifications/pending') data = pending
    else if (path === '/api/notifications/preferences') data = [{ type: 'TASK_ASSIGNED', enabled: true, system: false }, { type: 'SYSTEM', enabled: true, system: true }]
    else if (path === '/api/dashboard') data = { activityStats: {}, weekSchedules: 0, myTasks: [], recentArticles: [], recentMaterials: [], recentAnnouncements: [], pendingWork: pending }
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, data }) })
  })
  await page.goto('/notifications')
  await expect(page.getByRole('heading', { name: '通知中心' })).toBeVisible()
  await expect(page.getByRole('button', { name: '去审核' })).toBeVisible()
  const evidence = path.resolve('../docs/design/notification-v6')
  await mkdir(evidence, { recursive: true })
  for (const width of [1440, 1024, 390]) {
    await page.setViewportSize({ width, height: 900 })
    await expect(page.locator('.notification-row')).toBeVisible()
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy()
    await page.screenshot({ path: path.join(evidence, `messages-${width}.png`), fullPage: true, animations: 'disabled' })
  }
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.getByRole('button', { name: '通知设置', exact: true }).click()
  const settings = page.getByRole('dialog')
  await expect(settings.getByRole('switch', { name: '系统', exact: true })).toBeDisabled()
  const saved = page.waitForRequest(r => r.url().endsWith('/notifications/preferences') && r.method() === 'PUT')
  await settings.getByRole('switch', { name: '任务', exact: true }).click()
  expect((await saved).postDataJSON()).toContainEqual({ type: 'TASK_ASSIGNED', enabled: false, system: false })
  await expect(settings.getByRole('switch', { name: '任务', exact: true })).toBeEnabled()
  await page.screenshot({ path: path.join(evidence, 'settings.png'), fullPage: true, animations: 'disabled' })
  await settings.getByRole('button', { name: '完成' }).click()
  await page.getByRole('button', { name: '打开通知' }).click()
  await expect(page.locator('.notification-preview')).toBeVisible()
  await page.screenshot({ path: path.join(evidence, 'bell.png'), fullPage: true, animations: 'disabled' })
  await page.getByRole('button', { name: '打开通知' }).click()
  await expect(page.locator('.notification-popover')).not.toBeVisible()
  await page.getByRole('button', { name: '切换深色' }).click()
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')
  await page.screenshot({ path: path.join(evidence, 'messages-dark.png'), fullPage: true, animations: 'disabled' })
  await expect.poll(() => streamBearer).toBe('Bearer notification-e2e')
  await page.getByRole('button', { name: '标记已读', exact: true }).click()
  await expect(page.locator('.notification-row').filter({ hasText: '策划书待审核' }).getByText('已读', { exact: true })).toBeVisible()
  await expect(page.locator('.ant-badge-count')).toHaveCount(0)
  await page.getByRole('radiogroup', { name: '通知视图' }).getByText('待我处理', { exact: true }).click()
  await expect(page.getByRole('heading', { name: '待我处理' })).toBeVisible()
  await expect(page.getByRole('button', { name: '审核策划书' })).toBeVisible()
  await page.screenshot({ path: path.join(evidence, 'notification-pending.png'), fullPage: true, animations: 'disabled' })
  await page.goto('/')
  await expect(page.locator('.business-metric').filter({ hasText: '我的待办' })).toContainText('1')
  await expect(page.getByRole('button', { name: '审核策划书' })).toBeVisible()
  await page.screenshot({ path: path.join(evidence, 'dashboard-pending.png'), fullPage: true, animations: 'disabled' })
})
