import { test, expect } from '@playwright/test'
import { mkdir } from 'node:fs/promises'
import path from 'node:path'

test.use({ channel: process.platform === 'win32' ? 'msedge' : undefined })
test('announcement reading and confirmation are separate, and pipeline failures can be replayed', async ({ page }) => {
  const user = { id: 1, username: 'operator', realName: '经办人', orgId: 1, roleCode: 'TEACHER', roleLevel: 5, permissions: ['notice:view', 'notice:publish'], managementCapabilities: ['org:audit:view'] }
  await page.addInitScript(user => localStorage.setItem('pams_token', JSON.stringify({ state: { token: 'stage-three', user }, version: 1 })), user)
  let confirmed = false, read = false, retried = false
  const announcement = { id: 31, title: '重要安排确认', content: '请核对本次活动安排。', publisherId: 2, publishTime: '2026-10-29T09:00:00', createdAt: '2026-10-29T09:00:00', important: true, requireConfirmation: true, confirmationVersion: 2 }
  await page.route('**/ws/**', route => route.abort())
  await page.route(url => url.pathname.startsWith('/api/'), async route => {
    const pathname = new URL(route.request().url()).pathname
    let data: unknown = []
    if (pathname === '/api/notifications/stream') { await route.fulfill({ contentType: 'text/event-stream', body: 'event: ready\ndata: {"protocolVersion":1}\n\n' }); return }
    if (pathname === '/api/auth/me') data = user
    else if (pathname === '/api/orgs/current') data = { id: 1, name: '第三阶段验证', enabledPacks: null, themeJson: null }
    else if (pathname === '/api/announcements') data = [announcement]
    else if (pathname === '/api/announcements/31/read') read = true
    else if (pathname === '/api/announcements/read-ids') data = read ? [31] : []
    else if (pathname === '/api/announcements/31/confirmation') data = [{ version: 2, confirmed_at: confirmed ? '2026-10-29T10:00:00' : null }]
    else if (pathname === '/api/announcements/31/confirm') { expect(route.request().postDataJSON()).toEqual({ version: 2 }); confirmed = true }
    else if (pathname === '/api/notifications/page') data = { records: [], total: 0, current: 1, size: 10 }
    else if (pathname === '/api/notifications/unread-count') data = 0
    else if (pathname === '/api/notifications/operations/outbox/7/retry') retried = true
    else if (pathname === '/api/notifications/operations') data = { notification_outbox: { states: [{ status: retried ? 'PENDING' : 'FAILED', count: 1 }], oldestPendingAt: null, retried: 1, failures: retried ? [] : [{ id: 7, status: 'FAILED', attempts: 6, last_error: 'TransientDataAccessException', created_at: '2026-10-29T09:00:00' }] } }
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, data }) })
  })
  await page.goto('/archive/announcements')
  await page.getByRole('button', { name: '重要安排确认', exact: true }).click()
  await expect.poll(() => read).toBe(true)
  expect(confirmed).toBe(false)
  await page.getByRole('button', { name: '我已阅读并确认', exact: true }).click()
  await expect(page.getByText('已确认', { exact: true })).toBeVisible()
  expect(confirmed).toBe(true)
  const evidence = path.resolve('../docs/design/notification-s3')
  await mkdir(evidence, { recursive: true })
  await page.screenshot({ path: path.join(evidence, 'announcement-confirmation.png'), fullPage: true })
  await page.goto('/notifications')
  await page.getByRole('radiogroup', { name: '通知视图' }).getByText('管线运维', { exact: true }).click()
  await expect(page.getByText('TransientDataAccessException', { exact: true })).toBeVisible()
  await page.screenshot({ path: path.join(evidence, 'operations.png'), fullPage: true })
  await page.getByRole('button', { name: '重新入队', exact: true }).click()
  await expect(page.getByText('暂无失败记录', { exact: true })).toBeVisible()
  expect(retried).toBe(true)
})
