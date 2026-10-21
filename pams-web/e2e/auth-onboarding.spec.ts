import { expect, test, type Page } from '@playwright/test'
import type { OrgRegisterPayload } from '../src/api/org'

const orgs = [{ id: 1, name: '测试学院学生会', shortName: '测试学生会', logoUrl: null, themeJson: null }]

// 所有用例默认隔离真实后端；具体测试再覆盖需要的响应。
test.beforeEach(async ({ page }) => {
  await page.route('**/api/**', async (route) => {
    if (!new URL(route.request().url()).pathname.startsWith('/api/')) return route.continue()
    if (route.request().method() !== 'GET') {
      await route.abort()
      throw new Error('Unexpected API mutation: ' + route.request().url())
    }
    await route.fulfill({ json: { code: 200, data: [] } })
  })
})

async function mockPublicOrgs(page: Page) {
  await page.route('**/api/orgs/public', (route) => route.fulfill({ json: { code: 200, data: orgs } }))
}

async function startRegister(page: Page) {
  await page.goto('/register')
  await page.getByLabel('组织名称', { exact: true }).fill('协奏测试学生会')
  await page.getByLabel('组织简称（选填）').fill('测试学生会')
  await page.getByRole('button', { name: '下一步：组织架构' }).click()
  await page.getByRole('textbox', { name: '节点 1 名称', exact: true }).fill('组织部')
}

async function finishAdmin(page: Page) {
  await page.getByRole('button', { name: '下一步：管理员账号' }).click()
  await page.getByLabel('管理员用户名', { exact: true }).fill('team-admin')
  await page.getByLabel('登录密码', { exact: true }).fill('secure-password')
  await page.getByLabel('确认密码', { exact: true }).fill('secure-password')
  await page.getByRole('button', { name: '下一步：确认并创建' }).click()
}

test('cold navigation from login waits for the lazy register page without a synchronous suspension error', async ({ page }) => {
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  await mockPublicOrgs(page)
  let release!: () => void
  const gate = new Promise<void>((resolve) => { release = resolve })
  await page.route('**/src/pages/org/OrgRegister.tsx*', async (route) => { await gate; await route.continue() })
  await page.goto('/login')
  await expect(page.getByRole('heading', { name: '欢迎回来' })).toBeVisible()
  await page.getByRole('link', { name: '创建组织', exact: true }).first().click()
  // startTransition 可继续展示登录页；Suspense 也允许展示加载反馈。
  await expect(page.getByText('Unexpected Application Error!')).toHaveCount(0)
  release()
  await expect(page.getByRole('heading', { name: '先认识一下你的组织' })).toBeVisible()
  expect(errors).toEqual([])
  await expect(page.getByText('Unexpected Application Error!')).toHaveCount(0)
})

test('nested units can be added, edited and deleted, then submitted with their complete ancestry', async ({ page }) => {
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  await mockPublicOrgs(page)
  let submitted: OrgRegisterPayload | undefined
  await page.route('**/api/orgs/register', async (route) => {
    submitted = route.request().postDataJSON()
    await route.fulfill({ json: { code: 200, data: { orgId: 2, orgName: '协奏测试学生会', adminUsername: 'team-admin' } } })
  })
  await startRegister(page)
  await page.getByRole('button', { name: '为节点 1 添加子节点', exact: true }).click()
  await page.getByRole('textbox', { name: '节点 1.1 名称', exact: true }).fill('策划组')
  await page.getByRole('button', { name: '为节点 1.1 添加子节点', exact: true }).click()
  await page.getByRole('textbox', { name: '节点 1.1.1 名称', exact: true }).fill('现场分队')
  await page.getByRole('combobox', { name: '节点 1.1.1 类型', exact: true }).click()
  await page.getByRole('option', { name: '分队', exact: true }).click()
  await page.getByRole('button', { name: '为节点 1.1 添加子节点', exact: true }).click()
  await page.getByRole('textbox', { name: '节点 1.1.2 名称', exact: true }).fill('临时分队')
  await page.getByRole('button', { name: '删除节点 1.1.2', exact: true }).click()
  await page.getByRole('button', { name: '添加一级节点' }).click()
  await page.getByRole('textbox', { name: '节点 2 名称', exact: true }).fill('宣传部')
  await expect(page.getByRole('textbox', { name: '节点 1 名称', exact: true })).toHaveValue('组织部')
  await page.getByRole('button', { name: '上一步', exact: true }).click()
  await expect(page.getByLabel('组织名称', { exact: true })).toHaveValue('协奏测试学生会')
  await page.getByRole('button', { name: '下一步：组织架构' }).click()
  await expect(page.getByRole('textbox', { name: '节点 1.1.1 名称', exact: true })).toHaveValue('现场分队')
  await finishAdmin(page)
  await expect(page.locator('.register-review')).toContainText('现场分队')
  expect(submitted).toBeUndefined()
  await page.getByRole('checkbox', { name: /党建包/ }).check()
  await page.getByRole('button', { name: '创建组织', exact: true }).click()
  await expect(page.getByRole('heading', { name: '组织创建成功' })).toBeVisible()
  expect(submitted?.units).toEqual([
    { name: '组织部', unitType: '部门', children: [{ name: '策划组', unitType: '小组', children: [{ name: '现场分队', unitType: '分队', children: [] }] }] },
    { name: '宣传部', unitType: '部门', children: [] },
  ])
  expect(submitted?.enabledPacks).toEqual(['party'])
  expect(submitted?.admin).not.toHaveProperty('confirmPassword')
  await page.getByRole('button', { name: '使用管理员账号登录' }).click()
  await expect(page.getByLabel('用户名', { exact: true })).toHaveValue('team-admin')
  await expect(page.getByLabel('密码', { exact: true })).toHaveValue('')
  expect(errors).toEqual([])
})

test('step validation blocks empty child names and mismatched passwords; failed creation retains the form', async ({ page }) => {
  let requests = 0
  await page.route('**/api/orgs/register', async (route) => {
    requests++
    await route.fulfill({ json: { code: 2004, message: '管理员用户名已存在' } })
  })
  await startRegister(page)
  await page.getByRole('button', { name: '为节点 1 添加子节点', exact: true }).click()
  await page.getByRole('button', { name: '下一步：管理员账号' }).click()
  await expect(page.getByRole('alert')).toContainText('请填写每个部门或小组的名称')
  await page.getByRole('textbox', { name: '节点 1.1 名称', exact: true }).fill('策划组')
  await page.getByRole('button', { name: '下一步：管理员账号' }).click()
  await page.getByLabel('管理员用户名', { exact: true }).fill('team-admin')
  await page.getByLabel('登录密码', { exact: true }).fill('secure-password')
  await page.getByLabel('确认密码', { exact: true }).fill('wrong-password')
  await page.getByRole('button', { name: '下一步：确认并创建' }).click()
  await expect(page.getByText('两次输入的密码不一致')).toBeVisible()
  await page.getByLabel('确认密码', { exact: true }).fill('secure-password')
  await page.getByRole('button', { name: '下一步：确认并创建' }).click()
  expect(requests).toBe(0)
  await page.getByRole('button', { name: '创建组织', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('管理员用户名已存在')
  await page.getByRole('button', { name: '上一步', exact: true }).click()
  await expect(page.getByLabel('管理员用户名', { exact: true })).toHaveValue('team-admin')
  expect(requests).toBe(1)
})

test('login prevents duplicate requests and shows recoverable API errors', async ({ page }) => {
  await mockPublicOrgs(page)
  let release!: () => void
  let count = 0
  const gate = new Promise<void>((resolve) => { release = resolve })
  await page.route('**/api/auth/login', async (route) => {
    count++
    await gate
    await route.fulfill({ status: 401, json: { code: 401, message: '用户名或密码错误' } })
  })
  await page.goto('/login')
  await page.getByLabel('用户名', { exact: true }).fill('team-admin')
  await page.getByLabel('密码', { exact: true }).fill('test-password')
  await page.getByRole('button', { name: '登录工作台' }).click()
  await expect(page.getByLabel('用户名', { exact: true })).toBeDisabled()
  await page.getByLabel('密码', { exact: true }).press('Enter')
  release()
  await expect(page.getByRole('alert')).toContainText('用户名或密码错误')
  await expect(page.getByRole('button', { name: '登录工作台' })).toBeEnabled()
  await expect(page.getByLabel('用户名', { exact: true })).toHaveValue('team-admin')
  expect(count).toBe(1)
})

for (const mode of ['light', 'dark']) {
  test('mobile ' + mode + ' registration and nested editor fit the viewport', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 })
    await page.addInitScript((mode) => localStorage.setItem('pams_theme', JSON.stringify({ state: { mode }, version: 0 })), mode)
    await startRegister(page)
    await expect(page.locator('html')).toHaveAttribute('data-theme', mode)
    await page.getByRole('button', { name: '为节点 1 添加子节点', exact: true }).click()
    await page.getByRole('textbox', { name: '节点 1.1 名称', exact: true }).fill('策划组')
    await page.getByRole('button', { name: '为节点 1.1 添加子节点', exact: true }).click()
    await page.getByRole('textbox', { name: '节点 1.1.1 名称', exact: true }).fill('现场分队')
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
    await expect(page.getByRole('textbox', { name: '节点 1.1.1 名称', exact: true })).toBeInViewport()
    await page.screenshot({ path: '../docs/design/ui-auth-onboarding/register-mobile-' + mode + '.png', fullPage: true })
    await finishAdmin(page)
    await expect(page.locator('.register-review')).toContainText('现场分队')
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  })

  test('desktop ' + mode + ' login and registration visual review', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 1000 })
    await page.addInitScript((mode) => localStorage.setItem('pams_theme', JSON.stringify({ state: { mode }, version: 0 })), mode)
    await mockPublicOrgs(page)
    await page.goto('/login')
    await expect(page.getByRole('heading', { name: '欢迎回来' })).toBeVisible()
    await expect(page.locator('html')).toHaveAttribute('data-theme', mode)
    await page.screenshot({ path: '../docs/design/ui-auth-onboarding/login-desktop-' + mode + '.png', fullPage: true })
    await startRegister(page)
    await page.getByRole('button', { name: '为节点 1 添加子节点', exact: true }).click()
    await page.getByRole('textbox', { name: '节点 1.1 名称', exact: true }).fill('策划组')
    await page.screenshot({ path: '../docs/design/ui-auth-onboarding/register-desktop-' + mode + '.png', fullPage: true })
    await finishAdmin(page)
    await page.screenshot({ path: '../docs/design/ui-auth-onboarding/review-desktop-' + mode + '.png', fullPage: true })
  })
}
