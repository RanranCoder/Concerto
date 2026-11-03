import { test, expect, type Page } from '@playwright/test'

test.use({ baseURL: process.env.PAMS_E2E_BASE_URL ?? 'http://localhost:5173', channel: process.platform === 'win32' ? 'msedge' : undefined, viewport: { width: 1440, height: 1000 } })

const notification = { id: 8, type: 'SYSTEM', title: '顶栏通知测试', content: '这是一条通知内容', entityType: null, entityId: null, senderName: null, read: false, createdAt: '2026-10-19T08:30:00' }

async function setup(page: Page) {
  const user = { id: 1, username: 'teacher', realName: '测试老师', roleCode: 'TEACHER', roleLevel: 5, permissions: ['kb:read', 'org:manage'] }
  await page.addInitScript((user) => {
    localStorage.setItem('pams_token', JSON.stringify({ state: { token: 'navigation-test', user }, version: 1 }))
  }, user)
  await page.route((url) => url.pathname.startsWith('/api/'), async (route) => {
    const path = new URL(route.request().url()).pathname
    let data: unknown = []
    if (path === '/api/auth/me') data = user
    else if (path === '/api/orgs/current') data = { id: 1, name: '测试组织' }
    else if (path === '/api/notifications/unread-count') data = 1
    else if (path === '/api/notifications') data = [notification]
    else if (path === '/api/notifications/page') data = { records: [notification], total: 1, current: 1, size: 10 }
    else if (path === '/api/dashboard') data = { activityStats: {}, weekSchedules: 0, myTasks: [], recentArticles: [], recentMaterials: [], recentAnnouncements: [] }
    else if (path === '/api/kb/stats') data = { retriever: 'fulltext-ngram', fulltextAvailable: true, documentCount: 0, chunkCount: 0, snapshotCount: 0, lastIndexedAt: null }
    else if (path === '/api/kb/chunks') data = { records: [], total: 0, current: 1, size: 20 }
    else if (path === '/api/kb/entries') data = { records: [], total: 0, current: 1, size: 20 }
    await route.fulfill({ json: { code: 200, data } })
  })
}

test('桌面切换知识库与协作实验时保持侧栏和顶栏尺寸', async ({ page }) => {
  await setup(page)
  await page.goto('/')
  const sidebar = page.locator('.app-sider')
  await expect(sidebar).toHaveCSS('width', '216px')
  for (const name of ['知识库', '协作实验', '工作台']) {
    await sidebar.getByRole('menuitem', { name: new RegExp(`${name}$`) }).click()
    await expect(sidebar.locator('.ant-menu-item-selected')).toContainText(name)
    await expect(sidebar).toHaveCSS('width', '216px')
    await expect(page.locator('.app-header')).toHaveCSS('height', '56px')
    await expect(page.locator('.business-breadcrumb')).toContainText(name)
    await expect(page.getByText('服务器内部错误', { exact: true })).toHaveCount(0)
  }
})

test('手机切换知识库与协作实验时保留抽屉导航入口', async ({ page }) => {
  await setup(page)
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/')
  for (const name of ['知识库', '协作实验', '工作台']) {
    await page.getByRole('button', { name: '打开导航' }).click()
    const drawer = page.getByRole('dialog')
    await drawer.getByRole('menuitem', { name: new RegExp(`${name}$`) }).click()
    await expect(drawer).not.toBeVisible()
    await expect(page.locator('.app-sider')).toHaveCount(0)
    await expect(page.getByRole('button', { name: '打开导航' })).toBeVisible()
    await expect(page.locator('.business-breadcrumb')).toContainText(name)
  }
})

async function expectLogout(page: Page) {
  await expect(page.getByRole('menuitem', { name: '修改密码' })).toBeVisible()
  const logoutRequest = page.waitForRequest((request) =>
    new URL(request.url()).pathname === '/api/auth/logout' && request.method() === 'POST',
  )
  await page.getByRole('menuitem', { name: '退出登录' }).click()
  await logoutRequest
  await expect(page).toHaveURL(/\/login$/)
  const auth = await page.evaluate(() => JSON.parse(localStorage.getItem('pams_token')!).state)
  expect(auth.token).toBeNull()
  expect(auth.user).toBeNull()
}

test('头像菜单支持点击和键盘打开，并可退出登录', async ({ page }) => {
  await setup(page)
  await page.goto('/')
  const account = page.getByRole('button', { name: '用户菜单' })
  await account.click()
  await expect(page.getByRole('menuitem', { name: '退出登录' })).toBeVisible()
  await expect(page.getByRole('menuitem', { name: '退出登录' })).toBeInViewport()
  await expect(page.getByRole('menuitem', { name: '退出登录' })).toHaveCSS('opacity', '1')
  await page.screenshot({ path: 'test-results/user-menu-desktop.png' })
  await account.click()
  await expect(page.getByRole('menuitem', { name: '退出登录' })).not.toBeVisible()
  await account.focus()
  await account.press('Enter')
  await expectLogout(page)
})

test.describe('手机头像菜单', () => {
  test.use({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true })

  test('触摸头像能显示退出登录并完成退出', async ({ page }) => {
    await setup(page)
    await page.goto('/')
    await page.getByRole('button', { name: '用户菜单' }).tap()
    await expect(page.getByRole('menuitem', { name: '退出登录' })).toBeVisible()
    await expectLogout(page)
  })
})

for (const reducedMotion of ['no-preference', 'reduce'] as const) {
  for (const mode of ['light', 'dark'] as const) {
    test.describe(`${mode} ${reducedMotion} 顶栏通知和用户菜单`, () => {
      test.use({ reducedMotion, colorScheme: mode })
      test('头像菜单和通知列表都在屏幕内，查看全部和退出登录可操作', async ({ page }) => {
        test.setTimeout(20_000)
        await setup(page)
        await page.addInitScript((mode) => {
          localStorage.setItem('pams_theme', JSON.stringify({ state: { mode }, version: 0 }))
        }, mode)
        await page.goto('/')
        const account = page.getByRole('button', { name: '用户菜单' })
        await account.click()
        await expect(page.getByRole('menuitem', { name: '退出登录' })).toBeInViewport()
        await account.click()
        await page.locator('.app-header').getByRole('img', { name: 'bell', exact: true }).click()
        await expect(page.getByText(notification.title, { exact: true })).toBeVisible()
        await expect(page.getByText(notification.title, { exact: true })).toBeInViewport()
        await expect(page.getByRole('button', { name: '查看全部' })).toBeInViewport()
        await page.getByRole('button', { name: '查看全部' }).click()
        await expect(page).toHaveURL(/\/notifications$/)
        await account.click()
        await expect(page.getByRole('menuitem', { name: '退出登录' })).toBeInViewport()
        await expectLogout(page)
      })
    })
  }
}
