import { test, expect } from '@playwright/test'
import { mkdir } from 'node:fs/promises'
import path from 'node:path'

test.use({ channel: process.platform === 'win32' ? 'msedge' : undefined })
test('announcement reading and confirmation are separate, and pipeline failures can be replayed', async ({ page }) => {
  const user = { id: 1, username: 'operator', realName: '经办人', orgId: 1, roleCode: 'TEACHER', roleLevel: 5, permissions: ['notice:view', 'notice:publish'], managementCapabilities: ['org:audit:view', 'org:manage'] }
  await page.addInitScript(user => localStorage.setItem('pams_token', JSON.stringify({ state: { token: 'stage-three', user }, version: 1 })), user)
  let confirmed = false, read = false, retried = false, subscribed = false
  let policy = { quietEnabled: false, quietStart: '22:00:00', quietEnd: '08:00:00', digestEnabled: false, digestTime: '09:00:00', version: 0 }
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
    else if (pathname === '/api/notifications/operations/policy') {
      if(route.request().method() === 'PUT') { expect(route.request().postDataJSON().version).toBe(policy.version); policy = { ...route.request().postDataJSON(), version: policy.version + 1 } }
      data = policy
    }
    else if (pathname === '/api/notifications/operations/metrics') data = { outbox: { pending: 2, oldestPendingSeconds: 12, observedRetryClaims: 3, p95LatencyMs: 450, latencySamples: 10 }, noRecipientFacts: 0, allRecipientsSkippedFacts: 0, localSseConnections: 1, mergeCounters: [{ metric_key: 'fact_merge_hits', total_value: 2 }] }
    else if (pathname === '/api/notifications/operations/subscription') {
      if(route.request().method() === 'PUT') subscribed = route.request().postDataJSON().enabled
      data = { enabled: subscribed, enabledAt: subscribed ? '2026-10-29T10:00:00' : null }
    }
    else if (pathname === '/api/notifications/operations/incidents') data = [{ id: 1, category: 'KB_INDEX_FAILURE', window_date: '2026-10-29', occurrences: 4, last_seen_at: '2026-10-29T10:00:00' }]
    else if (pathname === '/api/notifications/operations/audit') data = retried ? [{ id: 1, actor_id: 1, pipeline_kind: 'outbox', record_id: 7, previous_attempts: 6, created_at: '2026-10-29T10:00:00' }] : []
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
  const evidence = path.resolve('../docs/design/notification-policy')
  await mkdir(evidence, { recursive: true })
  await page.screenshot({ path: path.join(evidence, 'announcement-confirmation.png'), fullPage: true })
  await page.goto('/notifications')
  await page.getByRole('radiogroup', { name: '通知视图' }).getByText('管线运维', { exact: true }).click()
  await expect(page.getByRole('switch', { name: '资讯免打扰' })).not.toBeChecked()
  await page.getByRole('switch', { name: '资讯免打扰' }).click()
  await page.getByRole('switch', { name: '每日资讯摘要' }).click()
  await page.getByRole('button', { name: '保存组织通知策略' }).click()
  await expect(page.getByText('组织通知策略已保存', { exact: true })).toBeVisible()
  expect(policy.version).toBe(1)
  const subscription = page.getByRole('switch', { name: '订阅本人维护告警' })
  await expect(subscription).not.toBeChecked()
  await subscription.click()
  await expect(subscription).toBeChecked()
  expect(subscribed).toBe(true)
  await page.goto('/notifications?view=operations')
  await expect(page.getByRole('switch', { name: '资讯免打扰' })).toBeChecked()
  await expect(page.getByRole('switch', { name: '每日资讯摘要' })).toBeChecked()
  await expect(page.getByText('事务内事实合并：2', { exact: true })).toBeVisible()
  await expect(page.getByRole('switch', { name: '订阅本人维护告警' })).toBeChecked()
  await expect(page.getByText('TransientDataAccessException', { exact: true })).toBeVisible()
  await expect(page.getByText('KB_INDEX_FAILURE', { exact: true })).toBeVisible()
  await page.screenshot({ path: path.join(evidence, 'operations.png'), fullPage: true })
  await page.getByRole('button', { name: '重新入队', exact: true }).click()
  await expect(page.getByText('暂无失败记录', { exact: true })).toBeVisible()
  await expect(page.getByRole('heading', { name: '重新入队操作记录' })).toBeVisible()
  await expect(page.getByRole('cell', { name: 'outbox', exact: true })).toBeVisible()
  await page.getByRole('heading', { name: '重新入队操作记录' }).scrollIntoViewIfNeeded()
  await page.screenshot({ path: path.join(evidence, 'operations-audit.png'), fullPage: true })
  expect(retried).toBe(true)
})
