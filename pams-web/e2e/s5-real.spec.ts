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
  await page.getByPlaceholder('说说你要准备什么，或补充当前任务的要求…').fill(`创建一个活动，名称「${name}」，开始日期2026-11-20，结束日期2026-11-20，地点S5 测试礼堂，类型OTHER。请用createActivity生成待确认清单，先不要写入。`)
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
  mkdirSync(output, { recursive: true })
  await page.screenshot({ path: resolve(output, 'real-business-desktop.png'), fullPage: true })
  expect(errors).toEqual([])
})
test('real evaluation records retain unknown quality and cost on a narrow screen', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.addInitScript(() => localStorage.setItem('pams_theme', JSON.stringify({ state: { mode: 'dark' }, version: 0 })))
  await login(page)
  await page.goto('/admin/benchmark')
  await expect(page.getByText('单 Agent 与多 Agent 评估', { exact: true })).toBeVisible()
  await expect(page.getByText('最近评估记录', { exact: true })).toBeVisible()
  await expect(page.getByRole('columnheader', { name: '费用', exact: true })).toBeAttached()
  await expect(page.getByText('未知', { exact: true }).first()).toBeAttached()
  await expect(page.getByText('业务完成率', { exact: true })).toHaveCount(0)
  mkdirSync(output, { recursive: true })
  await page.screenshot({ path: resolve(output, 'evaluation-dark-phone.png'), fullPage: true })
})
