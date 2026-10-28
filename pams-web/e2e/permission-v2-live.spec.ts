import { expect, test } from '@playwright/test'

const origin = process.env.PAMS_PERMISSIONS_TEST_ORIGIN
test.skip(!origin, '需要独立权限验收服务；常规回归不连接真实业务库')
test('live permission catalogue, dependency preview, publication and responsive workspace', async ({
  page,
  request,
}) => {
  if (origin !== 'http://127.0.0.1:18088')
    throw new Error('仅允许本地独立权限验收服务')
  const login = await request.post(`${origin}/api/auth/login`, {
    data: { username: 'teacher', password: '123456' },
  })
  expect(login.ok()).toBeTruthy()
  const session = (await login.json()).data
  const pageErrors: string[] = []
  const serverErrors: string[] = []
  page.on('pageerror', (error) => pageErrors.push(error.message))
  page.on('response', (response) => {
    if (
      response.status() >= 500 &&
      new URL(response.url()).pathname.startsWith('/api/')
    )
      serverErrors.push(
        `${response.status()} ${new URL(response.url()).pathname}`,
      )
  })
  await page.addInitScript((session) => {
    localStorage.setItem(
      'pams_token',
      JSON.stringify({
        state: { token: session.token, user: session.user },
        version: 1,
      }),
    )
    localStorage.setItem(
      'pams_theme',
      JSON.stringify({ state: { mode: 'light' }, version: 0 }),
    )
  }, session)
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url())
    if (!url.pathname.startsWith('/api/')) return route.continue()
    const response = await route.fetch({
      url: `${origin}${url.pathname}${url.search}`,
    })
    await route.fulfill({ response })
  })
  await page.setViewportSize({ width: 1440, height: 1060 })
  await page.goto('/admin/permissions')
  const roleName = `策划审核员-${String(Date.now()).slice(-4)}`
  await page.getByRole('button', { name: '新建角色', exact: true }).click()
  await page.getByLabel('角色名称', { exact: true }).fill(roleName)
  await page
    .getByLabel('角色说明', { exact: true })
    .fill('仅查看并审核指定策划，不编辑活动和任务。')
  await page
    .getByRole('dialog')
    .getByRole('button', { name: '创建角色', exact: true })
    .click()
  await page.getByRole('button', { name: '策划编审', exact: true }).click()
  await page.getByRole('checkbox', { name: '审核策划书', exact: true }).click()
  await expect(
    page.getByRole('dialog', { name: '同时添加必要依赖？' }),
  ).toContainText('查看活动')
  await page.getByRole('dialog').locator('.ant-btn-primary').click()
  await expect(
    page.getByRole('checkbox', { name: '查看策划书', exact: true }),
  ).toBeChecked()
  await page
    .getByLabel('变更原因', { exact: true })
    .fill('一期补齐验收：确认审核职责')
  await page.getByRole('button', { name: '预览并发布', exact: true }).click()
  const preview = page.getByRole('dialog', { name: '确认授权变更' })
  await expect(preview).toContainText('审核策划书')
  await expect(preview).toContainText('影响账号')
  await expect(preview).toContainText('一期补齐验收：确认审核职责')
  await preview.locator('.ant-modal-footer .ant-btn-primary').click()
  await expect(page.getByText('变更已生效', { exact: true })).toBeVisible()
  expect(pageErrors).toEqual([])
  await expect(
    page.getByRole('button', { name: /预览并发布/ }),
  ).not.toHaveClass(/ant-btn-loading/)
  await page.getByRole('tab', { name: '发布历史', exact: true }).click()
  await expect(page.getByRole('table')).toContainText(
    '一期补齐验收：确认审核职责',
  )
  await page.getByRole('tab', { name: '权限配置', exact: true }).click()
  await page.getByRole('button', { name: '工作稿', exact: true }).click()
  await page
    .getByRole('checkbox', { name: '提交策划审核', exact: true })
    .click()
  const saveDraft = page.getByRole('button', { name: /保存工作稿/ })
  await expect(saveDraft).not.toHaveClass(/ant-btn-loading/)
  await saveDraft.click()
  await page
    .getByRole('button', { name: '放弃已保存工作稿', exact: true })
    .click()
  await page
    .getByRole('dialog', { name: '放弃已保存工作稿？' })
    .locator('.ant-btn-primary')
    .click()
  await expect(
    page.getByRole('button', { name: '放弃已保存工作稿', exact: true }),
  ).toHaveCount(0)
  await page.getByRole('button', { name: '已发布配置', exact: true }).click()
  await expect(
    page.getByRole('checkbox', { name: '提交策划审核', exact: true }),
  ).not.toBeChecked()
  const referenceResponse = page.waitForResponse((response) =>
    /\/api\/roles\/\d+\/references/.test(response.url()),
  )
  await page.getByRole('button', { name: '查看引用清单', exact: true }).click()
  expect((await referenceResponse).ok()).toBeTruthy()
  await expect(
    page.getByRole('dialog', { name: '角色引用清单' }),
  ).toContainText('有效账号')
  await page
    .getByRole('dialog', { name: '角色引用清单' })
    .getByRole('button', { name: 'Close', exact: true })
    .click()
  const peopleResponse = await request.get(`${origin}/api/users/options`, {
    headers: { Authorization: `Bearer ${session.token}` },
  })
  const people = (await peopleResponse.json()).data as {
    id: number
    realName: string
    roleName: string
  }[]
  const staff = people.find((person) => person.roleName === '干事')!
  expect(staff).toBeTruthy()
  await page.getByRole('tab', { name: '使用用户', exact: true }).click()
  await page.getByRole('tabpanel', {name:'使用用户',exact:true}).getByRole('combobox').click()
  await page
    .locator('.ant-select-item-option')
    .filter({ hasText: staff.realName })
    .first()
    .click()
  await page.getByRole('button', { name: '预览角色替换', exact: true }).click()
  await expect(preview).toContainText('高影响')
  await expect(preview).toContainText('本组织全部')
  await preview.locator('.ant-modal-footer .ant-btn-default').click()
  await page.getByRole('tab', { name: '权限配置', exact: true }).click()
  await page
    .getByRole('heading', { name: '角色与权限', exact: true })
    .scrollIntoViewIfNeeded()
  await expect(page.getByText('变更已生效', { exact: true })).toBeHidden()
  await expect(page.locator('.ant-message-notice')).toHaveCount(0)
  await page.screenshot({
    path: '../docs/design/permission-v2/desktop-light.png',
    fullPage: true,
  })
  await page.getByRole('tab', { name: '管理身份', exact: true }).click()
  await expect(
    page.getByRole('heading', { name: '可授予的业务操作' }),
  ).toBeVisible()
  await page.getByRole('tab', { name: '变更记录', exact: true }).click()
  await expect(page.getByRole('table')).toContainText(roleName)
  await page.getByRole('tab', { name: '业务角色', exact: true }).click()
  await page.getByRole('button', { name: '切换深色', exact: true }).click()
  await page.setViewportSize({ width: 390, height: 844 })
  await page.getByRole('button', { name: '全部业务', exact: true }).click()
  await page
    .getByRole('heading', { name: '角色与权限', exact: true })
    .scrollIntoViewIfNeeded()
  await page.screenshot({
    path: '../docs/design/permission-v2/mobile-dark.png',
    fullPage: true,
  })
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBeTruthy()
  expect(pageErrors).toEqual([])
  expect(serverErrors).toEqual([])
})
