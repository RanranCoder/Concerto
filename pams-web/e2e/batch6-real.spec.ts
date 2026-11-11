import { test, expect, type Page } from '@playwright/test'
import { readFileSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { createHash } from 'node:crypto'
import JSZip from 'jszip'
const output = process.env.PAMS_BATCH6_BROWSER_OUTPUT!
const fixture = JSON.parse(readFileSync(resolve(output, 'browser-fixture.json'), 'utf8'))
test.beforeEach(async ({ page }) => {
  await page.goto('/login')
  await page.getByLabel('用户名', { exact: true }).fill('teacher')
  await page.getByLabel('密码', { exact: true }).fill('123456')
  await page.getByRole('button', { name: '登录工作台', exact: true }).click()
  await expect(page).not.toHaveURL(/\/login/)
})
async function headers(page: Page) {
  const token = await page.evaluate(() => JSON.parse(localStorage.getItem('pams_token')!).state.token)
  return { Authorization: `Bearer ${token}` }
}
async function get(page: Page, url: string) {
  const response = await page.request.get(url, { headers: await headers(page) })
  const body = await response.json()
  expect(body.code, JSON.stringify(body)).toBe(200)
  return body.data
}
for (const row of fixture.cases) {
  if (['REPLAN', 'DOCX_REVISION', 'LIVE_WORD'].includes(row.kind)) continue
  test(`${row.kind}: persisted card, reload, explicit confirmation and receipt`, async ({ page, context }) => {
    const errors: string[] = []
    page.on('pageerror', e => errors.push(e.message))
    await page.goto('/agent')
    await page.locator('.lingsi-conv-select').filter({ hasText: `B6浏览器-${row.kind}` }).click()
    const confirm = page.getByRole('button', { name: /^确认(写入|执行)$/ })
    await expect(confirm).toBeVisible()
    const before = await get(page, `/api/agent/tasks/${row.taskId}`)
    expect(before.status).toBe('PENDING_CONFIRM')
    // A real network disconnect and fresh page load recover the persisted card.
    await context.setOffline(true)
    await context.setOffline(false)
    await page.reload()
    await page.locator('.lingsi-conv-select').filter({ hasText: `B6浏览器-${row.kind}` }).click()
    await expect(confirm).toBeVisible()
    await page.screenshot({ path: resolve(output, `${row.kind}-preview.png`), fullPage: true })
    await confirm.click()
    const expected = row.kind === 'STALE' ? 'FAILED' : 'SUCCESS'
    await expect.poll(async () => (await get(page, `/api/agent/tasks/${row.taskId}`)).status).toBe(expected)
    await expect(page.locator('.lingsi-preview-footer')).toContainText(expected === 'SUCCESS' ? '已执行' : '执行失败')
    // Duplicate authenticated delivery is idempotent; it cannot repeat the write.
    const duplicate = await page.request.post(`/api/agent/tasks/${row.taskId}/confirm`, {
      headers: await headers(page), data: { confirmToken: before.plan?.confirmToken ?? before.confirmToken },
    })
    expect((await duplicate.json()).data.status).toBe(expected)
    if (row.articleId) {
      const article = await get(page, `/api/articles/${row.articleId}`)
      if (row.kind === 'REVISION') {
        expect(article.content).toBe('<p>预算3000元，日期保留。</p>')
        expect(article.summary).toBe('摘要保留')
        expect(article.status).toBe('DRAFT')
      } else if (row.kind === 'ARTICLE_SUBMIT') expect(article.status).toBe('PENDING')
      else expect(article.content).toBe('原生并发修改保留')
    }
    if (row.planId) expect((await get(page, `/api/plans/${row.planId}`)).status).toBe('PENDING')
    if (row.scheduleId) {
      const schedules = await get(page, '/api/schedules')
      expect(schedules.find((s: { id: number }) => s.id === row.scheduleId).status).toBe('PUBLISHED')
      expect(schedules.find((s: { id: number }) => s.id === row.excludedScheduleId).status).toBe('DRAFT')
    }
    await page.reload()
    await page.locator('.lingsi-conv-select').filter({ hasText: `B6浏览器-${row.kind}` }).click()
    await expect(confirm).toHaveCount(0)
    await page.screenshot({ path: resolve(output, `${row.kind}-receipt.png`), fullPage: true })
    expect(errors).toEqual([])
  })
}
test('real model Word creation, sequential edits and version recovery', async ({ page }) => {
  const row = fixture.cases.find((entry: { kind: string }) => entry.kind === 'LIVE_WORD')
  test.skip(!row, 'Requires the isolated real-provider fixture')
  test.setTimeout(300_000)
  await page.goto('/agent')
  const editor = page.getByRole('textbox', { name: '任务内容' })
  const ask = async (text: string) => {
    await editor.fill(text)
    const pending = page.waitForResponse(response => new URL(response.url()).pathname === '/api/agent/chat'
      && response.request().method() === 'POST', { timeout: 120_000 })
    await editor.press('Enter')
    const body = await (await pending).json()
    expect(body.code, JSON.stringify(body)).toBe(200)
    expect(body.data.degraded, body.data.reply).toBe(false)
    expect(body.data.plan == null, body.data.reply).toBe(true)
    expect(body.data.provider).not.toBe('stub')
    return body.data
  }
  const latest = async () => (await get(page, `/api/plans/workspace?activityId=${row.activityId}`)).latest
  const loadWord = async (id: number, name: string) => {
    const response = await page.request.get(`/api/plans/${id}/document`, { headers: await headers(page) })
    expect(response.ok()).toBeTruthy()
    const bytes = await response.body()
    writeFileSync(resolve(output, name), bytes)
    const zip = await JSZip.loadAsync(bytes)
    return { bytes, xml: await zip.file('word/document.xml')!.async('string') }
  }
  const first = await ask(`为活动 #${row.activityId} 写一份 Word 策划书并直接保存为草稿。标题写合成验收培训班，正文写负责部门：办公室，预算表只有一行数据：物料2000元，不加合计行。`)
  const original = await latest()
  expect(original.status).toBe('DRAFT')
  const v1 = await loadWord(original.id, 'v1.docx')
  expect(v1.xml).toContain('办公室')
  expect(v1.xml).toContain('2000')
  expect(v1.xml).toContain('<w:tbl')
  await ask(`把策划书 #${original.id} 的预算表中物料金额从2000改为2500，直接保存为新的 Word 草稿。其余内容保留。`)
  const revised = await latest()
  expect(revised.version).toBe(original.version + 1)
  const v2 = await loadWord(revised.id, 'v2.docx')
  expect(v2.xml).toContain('2500')
  expect(v2.xml).not.toContain('2000')
  expect(v2.xml).toContain('办公室')
  await page.reload()
  await page.locator('.lingsi-conv-select').filter({ hasText: '写一份 Word' }).first().click()
  const third = await ask(`把策划书 #${revised.id} 正文中的办公室改为文秘组，直接保存为新的 Word 草稿。表格和其他内容保留。`)
  expect(third.conversationId).toBe(first.conversationId)
  const final = await latest()
  expect(final.version).toBe(revised.version + 1)
  const v3 = await loadWord(final.id, 'v3.docx')
  expect(v3.xml).toContain('文秘组')
  expect(v3.xml).toContain('2500')
  expect(v3.xml).not.toContain('办公室')
  expect((await loadWord(original.id, 'v1-after.docx')).bytes).toEqual(v1.bytes)
  await page.goto(`/activities/${row.activityId}?tab=plan&planId=${final.id}`)
  const engine = page.locator('[data-document-engine="superdoc"]')
  await expect(engine).toHaveAttribute('data-ready', 'true', { timeout: 90_000 })
  await expect(engine.getByText(/文秘组/).first()).toBeVisible()
  await expect(engine.getByText(/2500/).first()).toBeVisible()
  await page.screenshot({ path: resolve(output, 'real-word-final.png'), fullPage: true })
})
test('native DOCX budget revision creates one new draft, preserves the original and renders the revised body', async ({ page }) => {
  const row = fixture.cases.find((r: { kind: string }) => r.kind === 'DOCX_REVISION')
  const document = async (id: number) => {
    const response = await page.request.get(`/api/plans/${id}/document`, { headers: await headers(page) })
    expect(response.ok()).toBeTruthy()
    return response.body()
  }
  const originalBytes = await document(row.planId)
  expect(createHash('sha256').update(originalBytes).digest('hex')).toBe(row.sourceSha256)
  await page.goto('/agent')
  await page.locator('.lingsi-conv-select').filter({ hasText: 'B6浏览器-DOCX_REVISION' }).click()
  const before = await get(page, `/api/agent/tasks/${row.taskId}`)
  const workspace = async () => get(page, `/api/plans/workspace?activityId=${row.activityId}`)
  expect((await workspace()).latest.id).toBe(row.planId)
  await page.getByRole('button', { name: /^确认(写入|执行)$/ }).click()
  await expect.poll(async () => (await get(page, `/api/agent/tasks/${row.taskId}`)).status).toBe('SUCCESS')
  await expect(page.locator('.lingsi-preview-footer')).toContainText('已执行')
  const revised = (await workspace()).latest
  expect(revised.id).not.toBe(row.planId)
  expect(revised.status).toBe('DRAFT')
  expect(revised.version).toBe(2)
  const duplicate = await page.request.post(`/api/agent/tasks/${row.taskId}/confirm`, { headers: await headers(page), data: { confirmToken: before.plan.confirmToken } })
  expect((await duplicate.json()).data.status).toBe('SUCCESS')
  expect((await workspace()).latest.id).toBe(revised.id)
  expect(createHash('sha256').update(await document(row.planId)).digest('hex')).toBe(row.sourceSha256)
  const oldZip = await JSZip.loadAsync(originalBytes)
  const newZip = await JSZip.loadAsync(await document(revised.id))
  expect(Object.keys(newZip.files).sort()).toEqual(Object.keys(oldZip.files).sort())
  for (const name of Object.keys(oldZip.files)) if (name !== 'word/document.xml') {
    expect(await newZip.file(name)!.async('nodebuffer')).toEqual(await oldZip.file(name)!.async('nodebuffer'))
  }
  const xml = await newZip.file('word/document.xml')!.async('string')
  expect(xml).toContain('预算总额为3000元')
  expect(xml).toContain('不改此段落')
  expect(xml).toContain('原流程保留')
  await page.goto(`/activities/${row.activityId}?tab=plan&planId=${revised.id}`)
  const engine = page.locator('[data-document-engine="superdoc"]')
  await expect(engine).toHaveAttribute('data-ready', 'true', { timeout: 90_000 })
  await expect(engine.getByText('预算总额为3000元', { exact: true }).first()).toBeVisible()
  await expect(engine.getByText('不改此段落', { exact: true }).first()).toBeVisible()
  await page.screenshot({ path: resolve(output, 'DOCX_REVISION-native-document.png'), fullPage: true })
})
test('controlled local replan invalidates old confirmation and continues downstream with the new receipt', async ({ page }) => {
  const row = fixture.cases.find((r: { kind: string }) => r.kind === 'REPLAN')
  await page.goto('/agent')
  await page.locator('.lingsi-conv-select').filter({ hasText: 'B6浏览器-REPLAN' }).click()
  const old = await get(page, `/api/agent/tasks/${row.taskId}`)
  expect(old.status).toBe('PENDING_CONFIRM')
  const response = await page.request.post(`/api/agent/team/runs/${row.runId}/nodes/${row.nodeId}/replan`, {
    headers: await headers(page), data: { revision: 1, planRef: row.planRef, conversationId: row.conversationId, toolMode: 'standard' },
  })
  expect((await response.json()).code).toBe(200)
  await expect.poll(async () => (await get(page, `/api/agent/tasks/${row.taskId}`)).status).toBe('CANCELLED')
  const stale = await page.request.post(`/api/agent/tasks/${row.taskId}/confirm`, { headers: await headers(page), data: { confirmToken: old.plan.confirmToken } })
  expect((await stale.json()).data.status).toBe('CANCELLED')
  expect((await get(page, `/api/articles/${row.articleId}`)).status).toBe('DRAFT')
  await page.reload()
  await page.locator('.lingsi-conv-select').filter({ hasText: 'B6浏览器-REPLAN' }).click()
  const confirm = page.getByRole('button', { name: /^确认(写入|执行)$/ })
  await expect(confirm).toBeVisible()
  await confirm.click()
  await expect.poll(async () => (await get(page, `/api/articles/${row.articleId}`)).status).toBe('PENDING')
  await expect.poll(async () => (await get(page, `/api/agent/team/runs/${row.runId}`)).status).toBe('SUCCESS')
  await expect(page.locator('.lingsi-preview-footer')).toContainText('已执行')
  await page.screenshot({ path: resolve(output, 'REPLAN-receipt.png'), fullPage: true })
})
test('another authenticated user cannot view or confirm the owner tasks or conversations', async ({ page, browser }) => {
  const other = await browser.newContext()
  try {
    const outsider = await other.newPage()
    await outsider.goto('http://127.0.0.1:5185/login')
    await outsider.getByLabel('用户名', { exact: true }).fill('staff')
    await outsider.getByLabel('密码', { exact: true }).fill('123456')
    await outsider.getByRole('button', { name: '登录工作台', exact: true }).click()
    await expect(outsider).not.toHaveURL(/\/login/)
    for (const row of fixture.cases) {
      const owner = await get(page, `/api/agent/tasks/${row.taskId}`)
      const denied = await outsider.request.get(`http://127.0.0.1:5185/api/agent/tasks/${row.taskId}`, { headers: await headers(outsider) })
      const body = await denied.json()
      expect(body.code).not.toBe(200)
      expect(JSON.stringify(body)).not.toContain('confirmToken')
      const confirm = await outsider.request.post(`http://127.0.0.1:5185/api/agent/tasks/${row.taskId}/confirm`, { headers: await headers(outsider), data: { confirmToken: owner.plan?.confirmToken ?? '' } })
      expect((await confirm.json()).code).not.toBe(200)
      const history = await outsider.request.get(`http://127.0.0.1:5185/api/agent/conversations/${row.conversationId}/messages`, { headers: await headers(outsider) })
      expect((await history.json()).code).not.toBe(200)
    }
  } finally { await other.close() }
})
test('unauthenticated callers cannot obtain a confirmation or conversation', async ({ playwright }) => {
  const anonymous = await playwright.request.newContext({ baseURL: 'http://127.0.0.1:5185' })
  try {
    for (const row of fixture.cases) {
      const response = await anonymous.get(`/api/agent/tasks/${row.taskId}`)
      expect(response.status()).toBe(401)
    }
  } finally { await anonymous.dispose() }
})
test('expert diagnostics follow current permissions and read-only mode', async ({ page }) => {
  const canAudit = await page.evaluate(() => JSON.parse(localStorage.getItem('pams_token')!).state.user.permissions.includes('org:audit:view'))
  const response = await page.request.get('/api/agent/diagnostics?mode=readonly', { headers: await headers(page) })
  const body = await response.json()
  if (!canAudit) {
    expect(body.code).toBe(403)
    expect(JSON.stringify(body)).not.toContain('effectiveTools')
    return
  }
  expect(body.code).toBe(200)
  for (const expert of body.data.experts) for (const tool of expert.effectiveTools) expect(tool.operationKind).toBe('READ')
  expect(JSON.stringify(body)).not.toContain('rolePrompt')
  await page.goto('/agent')
  await page.getByRole('button', { name: '能力与资料范围', exact: true }).first().click()
  const details = page.getByText('专家能力与配置诊断', { exact: true })
  await expect(details).toBeVisible()
  await details.click()
  await expect(page.getByText('按当前账号权限和模式显示；能力可见不代表业务已执行。', { exact: true })).toBeVisible()
  await page.screenshot({ path: resolve(output, 'expert-diagnostics.png'), fullPage: true })
})
