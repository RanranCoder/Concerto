import { expect, test } from '@playwright/test'

test.use({ channel: process.platform === 'win32' ? 'msedge' : undefined })

test('youth and volunteer workspaces honor their switches and complete their core workflows', async ({ page }) => {
  const settings = '/admin/settings?section=extensions'
  const base = ['org:manage', 'activity:view', 'activity:create']
  const active = new Set<string>()
  const account = { id: 1, username: 'admin', realName: '林晓', orgId: 2, roleId: 1, isOwner: true,
    permissions: base, managementCapabilities: ['org:manage'] }
  await page.addInitScript(user => {
    localStorage.setItem('pams_token', JSON.stringify({ state: { token: 'pack-workspace', user }, version: 1 }))
  }, account)

  const user = () => ({ ...account, permissions: [...base, ...[...active].flatMap(pack =>
    ['view', 'manage', 'review'].map(permission => `${pack}:${permission}`))] })
  const member = { id: 11, name: '陈禾', studentNo: '20260118' }
  const branch = { id: 21, name: '第一团支部' }
  const event = { id: 32, activityId: 31, name: '校园志愿服务', startDate: '2026-11-12', activityDeleted: 0,
    maxMinutes: 180, status: 'EXECUTING', registered: 0, pending: 0, approvedMinutes: 0 }
  const volunteer = { id: 41, memberName: member.name, studentNo: member.studentNo, status: 'REGISTERED', hasSignin: 1, version: 0,
    serviceMinutes: 0, signinAt: '2026-11-12T09:00:00', reviewedAt: '2026-11-12T12:00:00' }
  const youth: unknown[] = []
  let addedEvent = false
  let registered = false
  const paged = (records: unknown[]) => ({ records, total: records.length, current: 1, size: 20 })
  await page.route(url => url.pathname.startsWith('/api/'), async route => {
    const req = route.request()
    const path = new URL(req.url()).pathname
    const method = req.method()
    let data: unknown = []
    if (path === '/api/auth/me') data = user()
    else if (path === '/api/orgs/current') data = { id: 2, name: '学院组织办公室', enabledPacks: JSON.stringify([...active]), themeJson: null }
    else if (path.startsWith('/api/orgs/current/packs/')) {
      expect(method).toBe('PUT')
      const code = path.split('/').at(-1)!
      if (req.postDataJSON().enabled) active.add(code)
      else active.delete(code)
      data = [...active]
    } else if (path.startsWith('/api/youth/')) {
      expect(active.has('youth')).toBe(true)
      if (path.endsWith('/overview')) data = { branches: [], pendingReviews: 0, pendingMinutes: 0, recentLife: [] }
      if (path.endsWith('/choices/members')) data = [member]
      if (path.endsWith('/choices/branches')) data = [branch]
      if (path === '/api/youth/members') {
        if (method === 'POST') {
          expect(req.postDataJSON()).toMatchObject({ memberId: member.id, branchId: branch.id, duty: '团支书' })
          youth.push({ id: 51, memberId: member.id, memberName: member.name, studentNo: member.studentNo,
            branchId: branch.id, branchName: branch.name, duty: '团支书', version: 0 })
        } else data = paged(youth)
      }
    } else if (path.startsWith('/api/volunteer/')) {
      expect(active.has('volunteer')).toBe(true)
      if (path.endsWith('/choices/activities')) data = [{ id: 31, name: event.name }]
      if (path.endsWith('/choices/members')) data = [member]
      if (path === '/api/volunteer/activities') {
        if (method === 'POST') {
          expect(req.postDataJSON()).toMatchObject({ activityId: 31, maxMinutes: 180 })
          addedEvent = true
        } else data = paged(addedEvent ? [event] : [])
      }
      if (path.endsWith('/registrations')) {
        expect(req.postDataJSON()).toEqual({ memberId: member.id })
        registered = true
      }
      if (path.endsWith('/submit')) {
        expect(req.postDataJSON()).toEqual({ version: 0, minutes: 120 })
        Object.assign(volunteer, { status: 'PENDING', serviceMinutes: 120, version: 1 })
      }
      if (path.endsWith('/review')) {
        expect(req.postDataJSON()).toMatchObject({ version: 1, approved: true })
        Object.assign(volunteer, { status: 'APPROVED', version: 2 })
      }
      if (path.endsWith('/32')) data = { activity: event, records: paged(registered ? [volunteer] : []) }
      if (path.endsWith('/export')) data = volunteer.status === 'APPROVED' ? [volunteer] : []
    }
    await route.fulfill({ json: { code: 200, data } })
  })

  const button = (name: string) => page.getByRole('button', { name, exact: true })
  const dialog = () => page.getByRole('dialog').last()
  const action = (name: string) => dialog().getByRole('button', { name, exact: true })
  async function toggle(pack: string, enabled: boolean) {
    await page.goto(settings)
    await button(`${enabled ? '查看' : '管理'}${pack}`).click()
    await action(enabled ? '启用功能' : '关闭功能').click()
    await action(enabled ? '确认启用' : '确认关闭').click()
    await expect(button(`${enabled ? '管理' : '查看'}${pack}`)).toBeVisible()
  }
  async function choose(label: string, value: RegExp) {
    await dialog().getByRole('combobox', { name: label }).click()
    await page.locator('.ant-select-dropdown:visible .ant-select-item-option').filter({ hasText: value }).click()
  }

  await page.goto('/youth')
  await expect(page.getByRole('heading', { name: '当前组织未启用此功能' })).toBeVisible()
  await toggle('团建工作包', true)
  await page.getByRole('menuitem', { name: '团务工作' }).click()
  await expect(page.getByRole('heading', { name: '待办事项' })).toBeVisible()
  for (const width of [1440, 768, 390]) {
    await page.setViewportSize({ width, height: 900 })
    await expect(button('开展组织生活')).toBeInViewport()
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width)
    await page.screenshot({ path: test.info().outputPath(`youth-${width}.png`) })
  }
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.getByRole('tab', { name: '团员与支部' }).click()
  await button('关联成员').click()
  await choose('现有成员', /陈禾/)
  await choose('所属支部', /第一团支部/)
  await dialog().getByRole('textbox', { name: '团内职务' }).fill('团支书')
  await action('保存').click()
  await expect(page.getByRole('heading', { name: '陈禾' })).toBeVisible()

  await toggle('志愿服务包', true)
  await page.getByRole('menuitem', { name: '志愿服务' }).click()
  await button('添加志愿活动').click()
  await choose('已有活动', /校园志愿服务/)
  await dialog().getByRole('spinbutton', { name: '每人时长上限（分钟）' }).fill('180')
  await action('保存').click()
  await page.getByRole('link', { name: '管理服务记录' }).click()
  await button('登记报名').click()
  await choose('志愿者', /陈禾/)
  await action('保存').click()
  await button('核定时长').click()
  await dialog().getByRole('spinbutton', { name: '核定时长（分钟）' }).fill('120')
  await action('提交审核').click()
  await button('确认时长').click()
  await action('确认时长').click()
  await expect(page.getByText('已确认', { exact: true })).toBeVisible()
  await page.setViewportSize({ width: 390, height: 900 })
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390)
  await page.screenshot({ path: test.info().outputPath('volunteer-390.png') })
  const download = page.waitForEvent('download')
  await button('导出已确认记录').click()
  expect((await download).suggestedFilename()).toBe('志愿服务记录-32.csv')
  await page.setViewportSize({ width: 1440, height: 900 })

  await toggle('志愿服务包', false)
  await expect(page.getByRole('menuitem', { name: '志愿服务' })).toHaveCount(0)
  await page.goto('/volunteer/32')
  await expect(page.getByRole('heading', { name: '当前组织未启用此功能' })).toBeVisible()
  await toggle('志愿服务包', true)
  await page.goto('/volunteer/32')
  await expect(page.getByText('已确认', { exact: true })).toBeVisible()
})
