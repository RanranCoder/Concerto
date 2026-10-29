import { test, expect } from '@playwright/test'
import { mkdir } from 'node:fs/promises'
import path from 'node:path'

test.use({ baseURL: 'http://127.0.0.1:18086', channel: process.platform === 'win32' ? 'msedge' : undefined })
test.skip(process.env.PAMS_NOTIFICATION_ACCEPTANCE_LIVE !== '1', 'Opt-in isolated Docker acceptance only')
test('real MySQL and Nginx deliver an announcement through SSE to the browser', async ({ page, request }) => {
  const teacherResponse = await request.post('/api/auth/login', { data: { username: 'teacher', password: '123456' } })
  const staffResponse = await request.post('/api/auth/login', { data: { username: 'staff', password: '123456' } })
  expect(teacherResponse.status()).toBe(200); expect(staffResponse.status()).toBe(200)
  const teacher = (await teacherResponse.json()).data, staff = (await staffResponse.json()).data
  await page.addInitScript(data => localStorage.setItem('pams_token', JSON.stringify({ state: { token: data.token, user: data.user }, version: 1 })), staff)
  let bearerSeen = false
  page.on('request', req => { if(new URL(req.url()).pathname === '/api/notifications/stream') { expect(new URL(req.url()).search).toBe(''); bearerSeen = req.headers().authorization?.startsWith('Bearer ') ?? false } })
  await page.goto('/notifications')
  await expect(page.getByRole('heading', { name: '通知中心' })).toBeVisible()
  await expect.poll(() => bearerSeen).toBe(true)
  const title = '真实链路验收-' + Date.now()
  const created = await request.post('/api/announcements', { headers: { Authorization: `Bearer ${teacher.token}` }, data: { title, content: '隔离验收合成数据', important: true, requireConfirmation: true, audienceAll: false, audienceUserIds: [staff.user.id] } })
  expect(created.status()).toBe(200)
  const announcement = (await created.json()).data
  await expect(page.getByRole('cell').filter({ hasText: title })).toBeVisible({ timeout: 15000 })
  await page.getByRole('radiogroup', { name: '通知视图' }).getByText('待我处理', { exact: true }).click()
  await expect(page.getByText(title, { exact: true })).toBeVisible()
  await page.locator('.workbench-task').filter({ hasText: title }).getByRole('button', { name: '确认公告', exact: true }).click()
  await page.getByRole('button', { name: '我已阅读并确认', exact: true }).click()
  await expect(page.getByText('公告已确认', { exact: true })).toBeVisible()
  await expect.poll(async () => { const r = await request.get('/api/notifications/pending', { headers: { Authorization: `Bearer ${staff.token}` } }); return (await r.json()).data.items.some((i: { entityType: string; entityId: number }) => i.entityType === 'ANNOUNCEMENT' && i.entityId === announcement.id) }).toBe(false)
  const evidence = path.resolve('../docs/design/notification-acceptance'); await mkdir(evidence, { recursive: true })
  await page.screenshot({ path: path.join(evidence, 'real-mysql-nginx-confirmation.png'), fullPage: true })
})
