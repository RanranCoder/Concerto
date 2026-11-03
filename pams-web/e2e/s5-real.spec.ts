import { test, expect, type Page } from '@playwright/test'
import { mkdirSync } from 'node:fs'
import { resolve } from 'node:path'

test.skip(process.env.PAMS_S5_BROWSER !== 'true', 'Requires the isolated S5 backend')
const output = resolve('../docs/ui-v6-lingsi-implementation/s5-acceptance')
async function login(page: Page) {
  await page.goto('/login')
  await page.getByLabel('用户名', { exact: true }).fill('teacher')
  await page.getByLabel('密码', { exact: true }).fill('123456')
  await page.getByRole('button', { name: '登录工作台', exact: true }).click()
  await expect(page).not.toHaveURL(/\/login/)
}
test('real login, streamed model preview, confirmation and persisted business row', async ({ page }) => {
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  await login(page)
  const token = await page.evaluate(() => JSON.parse(localStorage.getItem('pams_token')!).state.token as string)
  const name = `S5-浏览器活动-${Date.now()}`
  const records = async () => {
    const response = await page.request.get(`/api/activities?keyword=${encodeURIComponent(name)}&page=1&size=20`, { headers: { Authorization: `Bearer ${token}` } })
    expect(response.ok()).toBeTruthy()
    const body = await response.json()
    expect(body.code).toBe(200)
    return body.data.records as { id: number; name: string }[]
  }
  expect(await records()).toHaveLength(0)
  await page.goto('/agent')
  await page.locator('.lingsi-composer-ta').fill(`创建一个活动，名称「${name}」，开始日期2026-11-20，结束日期2026-11-20，地点S5 测试礼堂，类型OTHER。请用createActivity生成待确认清单，先不要写入。`)
  await page.getByRole('button', { name: '发送任务', exact: true }).click()
  const confirm = page.getByRole('button', { name: '确认写入', exact: true })
  await expect(confirm).toBeVisible()
  expect(await records()).toHaveLength(0)
  await confirm.click()
  await expect.poll(async () => (await records()).length, { timeout: 60_000 }).toBe(1)
  await expect(confirm).toHaveCount(0)
  const created = (await records())[0]
  await page.reload()
  await expect(page.locator('.lingsi-composer-ta')).toBeVisible()
  expect(await records()).toHaveLength(1)
  await page.goto(`/activities/${created.id}`)
  await expect(page.getByText(name, { exact: false }).first()).toBeVisible()
  const headers = { Authorization: `Bearer ${token}` }
  const originalResponse = await page.request.post('/api/plans', { headers, data: { activityId: created.id, content: '<p>KEEP_ORIGINAL_PLAN</p>' } })
  expect(originalResponse.ok()).toBeTruthy()
  const original = (await originalResponse.json()).data
  await page.goto('/agent')
  await page.locator('.lingsi-composer-ta').fill(`为活动ID ${created.id} 编写简短策划书，日期2026-11-20，地点S5 测试礼堂，使用自由结构两段HTML正文，最后写上 PLAN_BROWSER_BODY_END。调用 draftPlanSections 并把完整正文传入 documentBody，生成待确认清单。`)
  await page.getByRole('button', { name: '发送任务', exact: true }).click()
  const useGenerated = page.getByRole('button', { name: '使用 Agent 生成的版本', exact: true })
  await expect(useGenerated).toBeVisible({ timeout: 120_000 })
  await expect(page.getByRole('button', { name: '保留当前版本', exact: true })).toBeVisible()
  const workspace = async () => (await (await page.request.get(`/api/plans/workspace?activityId=${created.id}`, { headers })).json()).data
  expect((await workspace()).latest.id).toBe(original.id)
  await useGenerated.click()
  await expect.poll(async () => (await workspace()).latest.content, { timeout: 60_000 }).toContain('PLAN_BROWSER_BODY_END')
  const old = await page.request.get(`/api/plans/${original.id}`, { headers })
  expect((await old.json()).data.content).toContain('KEEP_ORIGINAL_PLAN')
  await page.goto(`/activities/${created.id}?tab=plan`)
  const engine = page.locator('[data-document-engine="superdoc"]')
  await expect(engine).toHaveAttribute('data-ready', 'true', { timeout: 120_000 })
  await expect(engine.getByText(/PLAN_BROWSER_BODY_END/).first()).toBeVisible()
  await page.getByRole('button', { name: /编辑策划$/ }).click()
  await expect(engine).toHaveAttribute('data-ready', 'true', { timeout: 120_000 })
  await page.getByRole('button', { name: '保存策划书', exact: true }).click()
  await expect(page.getByText('正在编辑策划书')).toHaveCount(0, { timeout: 60_000 })
  expect((await workspace()).latest.documentFileId).toBeTruthy()
  mkdirSync(output, { recursive: true })
  await page.screenshot({ path: resolve(output, 'real-business-desktop.png'), fullPage: true })
  expect(errors).toEqual([])
})
test('real evaluation records show unknown cost or an honest empty state on a narrow screen', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.addInitScript(() => localStorage.setItem('pams_theme', JSON.stringify({ state: { mode: 'dark' }, version: 0 })))
  await login(page)
  const recordsResponse = page.waitForResponse(response => new URL(response.url()).pathname === '/api/agent/bench/results')
  await page.goto('/admin/benchmark')
  const rows = (await (await recordsResponse).json()).data
  await expect(page.getByText('单 Agent 与多 Agent 评估', { exact: true })).toBeVisible()
  await expect(page.getByText('最近评估记录', { exact: true })).toBeVisible()
  await expect(page.getByRole('columnheader', { name: '费用', exact: true })).toBeAttached()
  if (rows.length) await expect(page.getByText('未知', { exact: true }).first()).toBeAttached()
  else await expect(page.locator('.ant-table-placeholder')).toBeVisible()
  await expect(page.getByText('业务完成率', { exact: true })).toHaveCount(0)
  mkdirSync(output, { recursive: true })
  await page.screenshot({ path: resolve(output, 'evaluation-dark-phone.png'), fullPage: true })
})
