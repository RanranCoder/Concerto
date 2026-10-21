import { expect, test, type Page } from '@playwright/test'
import { readFile } from 'node:fs/promises'
import * as XLSX from 'xlsx'

test.use({ channel: process.platform === 'win32' ? 'msedge' : undefined, viewport: { width: 1440, height: 1000 } })
test.setTimeout(30_000)

async function setup(page: Page, canImport = true, stale = false, settings: {
  recommendedRoleId?: number; historyFails?: boolean; roleLoadFailsOnce?: boolean
} = {}) {
  const caps = ['user:view', 'user:manage', 'role:assign', ...(canImport ? ['member:import_account'] : [])]
  const user = { id: 1, username: 'teacher', realName: '管理员', orgId: 1, roleId: 1,
    permissions: caps, businessPermissions: [], managementCapabilities: caps }
  await page.addInitScript(user => {
    localStorage.setItem('pams_token', JSON.stringify({ state: { token: 'account-ui-test', user }, version: 1 }))
    localStorage.setItem('pams_theme', JSON.stringify({ state: { mode: 'light' }, version: 0 }))
  }, user)
  const candidates = [{ id: 11, name: '林晓', studentNo: '20260001', deptName: '文秘部', positionLabel: '部长',
    position: 'DEPT_HEAD', recommendedRoleId: settings.recommendedRoleId ?? null, recommendationReason: null }]
  const roles = [
    { id: 5, code: 'CUSTOM', name: '活动协助员', status: 'ACTIVE' },
    { id: 6, code: 'SECRETARY_LEADER', name: '文秘部长', status: 'ACTIVE' },
    { id: 7, code: 'STAFF', name: '干事', status: 'ACTIVE' },
  ]
  let candidatesLoaded = false
  let roleFailureSent = false
  const row = { memberId: 11, name: '林晓', username: '20260001', deptId: 2, deptName: '文秘部',
    roleId: 5, roleName: '活动协助员', permissions: ['activity:view'], permissionNames: ['查看活动'] }
  const result = { id: 'batch-test-1', created: 1,
    accounts: [{ userId: 101, name: '林晓', username: '20260001', deptName: '文秘部', roleName: '活动协助员', activated: false, canIssue: true }] }
  const calls: { path: string; body: Record<string, unknown> }[] = []
  await page.route('**/api/**', async route => {
    const req = route.request(), path = new URL(req.url()).pathname
    if (!path.startsWith('/api/')) return route.continue()
    if (req.method() === 'POST') calls.push({ path, body: req.postData() ? req.postDataJSON() : {} })
    let data: unknown = []
    if (path === '/api/auth/me') data = user
    else if (path === '/api/orgs/current') data = { id: 1, name: '测试组织', themeJson: null, enabledPacks: '[]' }
    else if (path === '/api/users') data = { records: [], total: 0 }
    else if (path === '/api/users/summary') data = {total: 0, enabled: 0, pendingActivation: 0, disabled: 0, ownerCount: 0, administratorCount: 0, roles: []}
    else if (path === '/api/depts') data = [{ id: 2, name: '文秘部' }]
    else if (path === '/api/roles/assignable') {
      if (candidatesLoaded && !roleFailureSent && settings.roleLoadFailsOnce) {
        roleFailureSent = true
        return route.fulfill({ status: 500, json: { code: 500, message: '角色加载失败' } })
      }
      data = roles
    }
    else if (path === '/api/members/account-batches/sessions') data = [{ id: 1, name: '2026 届', isCurrent: 1 }]
    else if (path === '/api/members/unregistered') { candidatesLoaded = true; data = candidates }
    else if (path === '/api/members/account-batches/previews') {
      const role = roles.find(r => r.id === req.postDataJSON().roleIds['11'])!
      data = { id: result.id, expiresAt: '2026-10-16T18:00:00', rows: [{ ...row, roleId: role.id, roleName: role.name }] }
    }
    else if (path.endsWith('/apply')) {
      if (stale) return route.fulfill({ status: 409, json: { code: 409, message: '组织授权已变化，请重新预览' } })
      data = result
    } else if (path.endsWith('/credentials')) data = [{ name: '林晓', username: '20260001', deptName: '文秘部', roleName: '活动协助员', token: 'a'.repeat(43), expiresAt: '2026-10-18T17:00:00' }]
    else if (path === '/api/members/account-batches') {
      if (settings.historyFails) return route.fulfill({ status: 500, json: { code: 500, message: '历史加载失败' } })
      data = calls.some(c => c.path.endsWith('/apply')) ? [result] : []
    }
    await route.fulfill({ json: { code: 200, data } })
  })
  return calls
}

async function selectAndPreview(page: Page) {
  await page.goto('/admin/users')
  await page.getByRole('button', { name: '从花名册导入账号' }).click()
  await page.getByRole('row').filter({ hasText: '林晓' }).getByRole('checkbox').check()
  await expect(page.getByRole('button', { name: '预览授权（1 人）' })).toBeDisabled()
  await page.getByRole('combobox', { name: '林晓的开户角色' }).click()
  await page.locator('.ant-select-dropdown:visible .ant-select-item-option').filter({ hasText: '活动协助员' }).click()
  await page.getByRole('button', { name: '预览授权（1 人）' }).click()
  await expect(page.getByRole('heading', { name: '逐人确认账号与授权' })).toBeVisible()
}

test('preview each assignment, create accounts, download credentials and recover a batch', async ({ page }) => {
  const calls = await setup(page)
  await selectAndPreview(page)
  expect(calls.filter(c => c.path.endsWith('/apply'))).toHaveLength(0)
  expect(calls.find(c => c.path.endsWith('/previews'))?.body).toEqual({ sessionId: 1, memberIds: [11], roleIds: { '11': 5 } })
  await expect(page.getByRole('cell', { name: '查看活动', exact: true })).toBeVisible()
  await page.screenshot({ path: test.info().outputPath('account-preview.png'), animations: 'disabled' })
  await page.setViewportSize({ width: 390, height: 844 })
  await expect(page.getByRole('button', { name: '确认创建 1 个账号' })).toBeInViewport({ ratio: 1 })
  await page.screenshot({ path: test.info().outputPath('account-preview-mobile.png'), animations: 'disabled' })
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.getByRole('button', { name: '确认创建 1 个账号' }).click()
  await expect(page.getByRole('heading', { name: '已创建 1 个账号' })).toBeVisible()
  await page.getByRole('button', { name: /下载开户凭证$/ }).click()
  const downloadPromise = page.waitForEvent('download')
  await page.getByRole('button', { name: '生成并下载', exact: true }).click()
  const download = await downloadPromise
  const book = XLSX.read(await readFile((await download.path())!), { type: 'buffer' })
  const records = XLSX.utils.sheet_to_json<Record<string, string>>(book.Sheets[book.SheetNames[0]])
  expect(records[0].用户名).toBe('20260001')
  expect(records[0].设密链接).toBe(`http://localhost:5173/activate-account#token=${'a'.repeat(43)}`)
  await page.getByRole('button', { name: /完\s*成$/ }).click()
  await page.getByRole('button', { name: '从花名册导入账号' }).click()
  await page.getByRole('combobox', { name: '选择历史开户批次' }).click()
  await page.getByRole('combobox', { name: '选择历史开户批次' }).press('Enter')
  await expect(page.getByRole('heading', { name: '已创建 1 个账号' })).toBeVisible()
  expect(calls.filter(c => c.path.endsWith('/apply'))).toHaveLength(1)
})

test('stale preview returns to selection and requires another preview', async ({ page }) => {
  const calls = await setup(page, true, true)
  await selectAndPreview(page)
  await page.getByRole('button', { name: '确认创建 1 个账号' }).click()
  await expect(page.getByRole('alert')).toContainText('组织授权已变化，请重新预览')
  await expect(page.getByRole('button', { name: '预览授权（1 人）' })).toBeVisible()
  expect(calls.filter(c => c.path.endsWith('/apply'))).toHaveLength(1)
})

test('user management alone does not expose batch creation', async ({ page }) => {
  await setup(page, false)
  await page.goto('/admin/users')
  await expect(page.getByRole('button', { name: '新增用户' })).toBeVisible()
  await expect(page.getByRole('button', { name: '从花名册导入账号' })).toHaveCount(0)
})

test('position recommendation is preselected and mouse override reaches the preview', async ({ page }) => {
  const calls = await setup(page, true, false, { recommendedRoleId: 6 })
  await page.goto('/admin/users')
  await page.getByRole('button', { name: '从花名册导入账号' }).click()
  const row = page.getByRole('row').filter({ hasText: '林晓' })
  await expect(row).toContainText('文秘部长')
  await row.getByRole('checkbox').check()
  await expect(page.getByRole('button', { name: '预览授权（1 人）' })).toBeEnabled()
  await row.locator('.ant-select-selector').click()
  const popup = page.locator('.ant-drawer-body .ant-select-dropdown:visible')
  await expect(popup).toBeVisible()
  await popup.getByRole('option', { name: '干事', exact: true }).click()
  await expect(row).toContainText('干事')
  await page.getByRole('button', { name: '预览授权（1 人）' }).click()
  expect(calls.find(c => c.path.endsWith('/previews'))?.body.roleIds).toEqual({ '11': 7 })
  await expect(page.getByRole('cell', { name: '干事', exact: true })).toBeVisible()
  await page.getByRole('button', { name: '返回调整', exact: true }).click()
  await expect(page.getByRole('row').filter({ hasText: '林晓' })).toContainText('干事')
})

test('history failure does not block roles and bulk mouse selection works', async ({ page }) => {
  await setup(page, true, false, { recommendedRoleId: 6, historyFails: true })
  await page.goto('/admin/users')
  await page.getByRole('button', { name: '从花名册导入账号' }).click()
  await expect(page.getByRole('alert')).toContainText('仍可选择成员和角色')
  const row = page.getByRole('row').filter({ hasText: '林晓' })
  await row.getByRole('checkbox').check()
  await page.getByRole('combobox', { name: '批量选择角色' }).click()
  await page.locator('.ant-drawer-body .ant-select-dropdown:visible').getByRole('option', { name: '活动协助员', exact: true }).click()
  await page.getByRole('button', { name: '应用到选中成员' }).click()
  await expect(row).toContainText('活动协助员')
  await expect(page.getByRole('button', { name: '预览授权（1 人）' })).toBeEnabled()
})

test('refresh retries role loading and reapplies the current position recommendation', async ({ page }) => {
  await setup(page, true, false, { recommendedRoleId: 6, roleLoadFailsOnce: true })
  await page.goto('/admin/users')
  await page.getByRole('button', { name: '从花名册导入账号' }).click()
  await expect(page.getByRole('alert')).toContainText('成员或角色加载失败')
  await page.getByRole('button', { name: /刷新名单$/ }).click()
  await expect(page.getByRole('row').filter({ hasText: '林晓' })).toContainText('文秘部长')
})

test('member uses their setup link without a session and confirms their own password', async ({ page }) => {
  let submitted: Record<string, string> | undefined
  await page.route('**/api/auth/account-setup', async route => {
    expect(route.request().headers().authorization).toBeUndefined()
    submitted = route.request().postDataJSON()
    await route.fulfill({ json: { code: 200, data: null } })
  })
  await page.goto(`/activate-account#token=${'a'.repeat(43)}`)
  await page.getByLabel('登录密码', { exact: true }).fill('NewPassword_2026')
  await page.getByLabel('确认密码', { exact: true }).fill('DifferentPassword')
  await page.getByRole('button', { name: '设置密码并激活' }).click()
  await expect(page.getByText('两次输入的密码不一致', { exact: true })).toBeVisible()
  expect(submitted).toBeUndefined()
  await page.getByLabel('确认密码', { exact: true }).fill('NewPassword_2026')
  await page.getByRole('button', { name: '设置密码并激活' }).click()
  await expect(page.getByRole('heading', { name: '账号已激活' })).toBeVisible()
  expect(submitted).toEqual({ token: 'a'.repeat(43), password: 'NewPassword_2026' })
  expect(page.url()).not.toContain('#')
  await page.screenshot({ path: test.info().outputPath('account-activated.png'), animations: 'disabled' })
})

test('incomplete and expired setup links give actionable feedback', async ({ page }) => {
  await page.goto('/activate-account')
  await expect(page.getByRole('alert')).toContainText('设密链接不完整')
  await page.route('**/api/auth/account-setup', route => route.fulfill({ status: 400, json: { code: 400, message: '设密链接无效、已使用或已过期，请联系管理员重新发放' } }))
  await page.goto(`/activate-account#token=${'b'.repeat(43)}`)
  await page.getByLabel('登录密码', { exact: true }).fill('NewPassword_2026')
  await page.getByLabel('确认密码', { exact: true }).fill('NewPassword_2026')
  await page.getByRole('button', { name: '设置密码并激活' }).click()
  await expect(page.getByRole('alert')).toContainText('请联系管理员重新发放')
})
