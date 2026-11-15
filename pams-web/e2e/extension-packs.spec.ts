import { expect, test } from '@playwright/test'

test.use({ channel: process.platform === 'win32' ? 'msedge' : undefined })

test('extension settings enable and hide party features without deleting records', async ({ page }) => {
  const settings = '/admin/settings?section=extensions'
  const user = { id: 1, username: 'admin', realName: '林晓', orgId: 2, roleId: 1, isOwner: true,
    permissions: ['org:manage', 'party:view'], managementCapabilities: ['org:manage'] }
  await page.addInitScript(account => {
    localStorage.setItem('pams_token', JSON.stringify({ state: { token: 'extension-test', user: account }, version: 1 }))
  }, user)
  let enabled = false
  const changes: boolean[] = []
  let reads = 0
  await page.route(url => url.pathname.startsWith('/api/'), async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    let data: unknown = []
    if (path === '/api/orgs/current/packs/party') {
      expect(request.method()).toBe('PUT')
      enabled = request.postDataJSON().enabled
      changes.push(enabled)
      data = enabled ? ['party'] : []
    } else {
      expect(request.method()).toBe('GET')
      if (path === '/api/auth/me') data = user
      if (path === '/api/orgs/current') data = {
        id: 2, name: '学院组织办公室', enabledPacks: enabled ? '["party"]' : '[]', themeJson: null,
      }
      if (path === '/api/party/members') {
        expect(enabled).toBe(true)
        reads++
        data = { total: 1, records: [{ id: 1, name: '原有台账记录' }] }
      }
    }
    await route.fulfill({ json: { code: 200, data } })
  })

  await page.goto(settings)
  const row = page.getByRole('listitem').filter({ has: page.getByRole('heading', { name: '党建工作包' }) })
  const menu = page.getByRole('menuitem', { name: '党务台账' })
  const confirm = (name: string) => page.getByRole('dialog').getByRole('button', { name, exact: true })
  await expect(row).toContainText('未启用')
  await expect(menu).toHaveCount(0)
  for (const width of [1440, 768, 390]) {
    await page.setViewportSize({ width, height: 900 })
    await expect(page.getByRole('button', { name: '扩展功能', exact: true })).toBeInViewport({ ratio: 1 })
    await expect(row.getByRole('button')).toBeInViewport()
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width)
    const screenshot = test.info().outputPath(`extensions-${width}.png`)
    await page.screenshot({ path: screenshot })
  }
  await page.setViewportSize({ width: 1440, height: 900 })
  await expect(page.getByRole('heading', { name: '社团管理包' })).toHaveCount(0)
  await page.getByRole('button', { name: '查看团建工作包' }).click()
  await expect(page.getByRole('dialog')).toContainText('教育评议')
  await expect(confirm('启用功能')).toBeVisible()
  await page.keyboard.press('Escape')

  await page.getByRole('button', { name: '查看党建工作包' }).click()
  await confirm('启用功能').click()
  await confirm('确认启用').click()
  await expect(row).toContainText('已启用')
  await expect(menu).toBeVisible()

  await page.getByRole('button', { name: '管理党建工作包' }).click()
  await confirm('关闭功能').click()
  await expect(page.getByRole('dialog', { name: '关闭党建工作包？' })).toContainText('已有业务记录、配置和关联关系将保留')
  await confirm('确认关闭').click()
  await expect(row).toContainText('未启用')
  await expect(menu).toHaveCount(0)

  await page.goto('/party/members')
  await expect(page.getByRole('heading', { name: '当前组织未启用此功能' })).toBeVisible()
  expect(reads).toBe(0)

  await page.goto(settings)
  await page.getByRole('button', { name: '查看党建工作包' }).click()
  await confirm('启用功能').click()
  await confirm('确认启用').click()
  await expect(menu).toBeVisible()
  await menu.click()
  await expect(page.getByText('原有台账记录')).toBeVisible()
  expect(changes).toEqual([true, false, true])
})
