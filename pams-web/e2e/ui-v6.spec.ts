import { test, expect, type Page } from '@playwright/test'
import { mkdirSync } from 'node:fs'
import { resolve } from 'node:path'

// 使用隔离的接口样本验证真实 React 页面；不调用真实后端或写入用户数据。
test.use({ channel: process.platform === 'win32' ? 'msedge' : undefined, baseURL: 'http://localhost:5173', viewport: { width: 1440, height: 1000 }, timezoneId: 'Asia/Shanghai' })
const output = resolve('..', 'docs', 'ui-v6-implementation')
const user = { id: 1, username: 'teacher', realName: '林老师', roleCode: 'TEACHER', roleLevel: 5, deptName: '党建办公室', permissions: ['activity:view', 'activity:create', 'activity:edit', 'activity:delete', 'schedule:view', 'material:view', 'member:view', 'kb:read', 'template:view', 'quality:view', 'notice:view', 'user:view', 'user:permission'] }

async function setup(page: Page, viewer = user) {
  await page.clock.setFixedTime(new Date('2026-10-14T10:00:00+08:00'))
  await page.addInitScript((user) => {
    localStorage.setItem('pams_token', JSON.stringify({ state: { token: 'ui-validation-token', user }, version: 1 }))
    if (!localStorage.getItem('pams_theme')) localStorage.setItem('pams_theme', JSON.stringify({ state: { mode: 'light' }, version: 0 }))
  }, viewer)
  const records = [
    { id: 1, name: '第四十期培训班', theme: '专题学习', type: 'PARTY_LESSON', status: 'EXECUTING', startDate: '2026-10-13', endDate: '2026-10-15', location: '学术报告厅', organizer: '组织部', leader: '陈同学', host: '', createdAt: '2026-10-01' },
    { id: 2, name: '十月主题党日', theme: '学习交流', type: 'PARTY_DAY', status: 'PLAN_REVIEW', startDate: '2026-10-14', endDate: '2026-10-14', location: '党员活动室', organizer: '党建办公室', leader: '林同学', host: '', createdAt: '2026-10-02' },
    { id: 3, name: '周末志愿服务', theme: '校园服务', type: 'VOLUNTEER', status: 'EXECUTING', startDate: '2026-10-17', endDate: '2026-10-17', location: '校园服务站', organizer: '青年科技部', leader: '周同学', host: '', createdAt: '2026-10-03' },
  ]
  const tasks = [{ id: 10, activityId: 1, name: '核对培训班名单', assignee: '陈同学', startDate: '2026-10-13', endDate: '2026-10-14', progress: 60, status: 'DOING' }]
  const material = { id: 1, activityId: 1, name: '培训班策划书', bizType: 'PLAN', fileId: 1, createdAt: '2026-10-13T09:30:00' }
  await page.route((url) => url.pathname.startsWith('/api/'), async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname
    let data: unknown = []
    if (path === '/api/auth/me') data = viewer
    else if (path === '/api/orgs/current') data = { id: 1, name: '党建办公室', themeJson: '{"primaryColor":"#0034A2"}', enabledPacks: null, terminologyJson: null }
    else if (path === '/api/orgs/public') data = [{ id: 1, name: '党建办公室', shortName: '党建办公室', themeJson: null }]
    else if (path === '/api/notifications/unread-count') data = 0
    else if (path === '/api/dashboard') data = { activityStats: Object.fromEntries(['ASSIGNED', 'PLANNING', 'PLAN_REVIEW', 'EXECUTING', 'FINISHED', 'ARCHIVED'].map(status => [status, records.filter(a => a.status === status).length])), weekSchedules: 1, myTasks: tasks, recentArticles: [], recentMaterials: [material], recentAnnouncements: [] }
    else if (path === '/api/activities' && request.method() === 'POST') {
      const values = request.postDataJSON()
      data = 4
      records.push({ ...records[0], ...values, id: 4, status: 'ASSIGNED' })
    } else if (path === '/api/activities') {
      const filtered = records.filter(a => (!url.searchParams.get('status') || a.status === url.searchParams.get('status')) && (!url.searchParams.get('keyword') || a.name.includes(url.searchParams.get('keyword')!)))
      // 周历刻意分页，验证周末活动不因初始页限制而遗漏。
      const calendar = url.searchParams.get('size') === '100'
      data = { records: calendar ? filtered.slice((Number(url.searchParams.get('page')) - 1) * 2, Number(url.searchParams.get('page')) * 2) : filtered, total: filtered.length, current: 1, size: calendar ? 2 : 10 }
    } else if (/\/activities\/\d+\/detail$/.test(path)) data = { activity: records.find(a => a.id === Number(path.split('/')[3])) ?? records[0], plan: null, agendas: [], seatZones: {}, score: { rules: [], records: [] }, signinCount: 0, tasks: [] }
    else if (/\/activities\/\d+$/.test(path)) data = records.find(a => a.id === Number(path.split('/').at(-1)))
    else if (path === '/api/schedules') data = [{ id: 1, scheduleType: 'CLASS_DUTY', scheduleDate: '2026-10-14', weekday: 3, startTime: '09:00:00', endTime: '11:30:00', sessionName: '上午', persons: [{ personName: '陈同学', isPrimary: 1 }], status: 'PUBLISHED', activityId: null }]
    else if (path === '/api/materials') data = { records: [material], total: 1, current: 1, size: 50 }
    else if (path === '/api/party/members') data = { records: [{ id: 1, name: '陈同学', className: '智能工程2401', politicalStatus: '入党积极分子', stage: 'ACTIVIST', phone: '13800000000' }], total: 1, current: 1, size: 10 }
    else if (path === '/api/agent/conversations') data = { records: [], total: 0, page: 1, size: 20 }
    await route.fulfill({ json: { code: 200, data } })
  })
  mkdirSync(output, { recursive: true })
}

test('真实工作台按日期聚合，并完整显示第二页的周末活动', async ({ page }) => {
  await setup(page)
  await page.goto('/')
  await expect(page.locator('.workbench-week')).toBeVisible()
  const sidebar = page.locator('.app-sider')
  await expect(sidebar.locator('.sidebar-brand')).toContainText('协奏')
  await expect(sidebar.locator('.sidebar-organization')).toContainText('党建办公室')
  await expect(sidebar.locator('.ant-menu-item-group-title')).toHaveText(['日常工作', '组织与资料', '智能协作', '系统管理'])
  await expect(sidebar.locator('.sidebar-account')).toContainText('林老师')
  await expect(sidebar.getByRole('menuitem', { name: /工作台$/ })).toHaveCSS('height', '40px')
  await expect(sidebar.getByRole('menuitem', { name: /工作台$/ })).toHaveCSS('border-inline-start-color', 'rgb(22, 104, 220)')
  await expect(page.locator('.workbench-day.today')).toContainText('核对培训班名单')
  await page.getByText('全周', { exact: true }).click()
  await expect(page.locator('.workbench-day')).toHaveCount(7)
  await expect(page.locator('.workbench-event', { hasText: '周末志愿服务' })).toBeVisible()
  await page.getByText('工作日', { exact: true }).click()
  await page.screenshot({ path: resolve(output, 'dashboard-light.png'), fullPage: true, animations: 'disabled' })
  await page.getByRole('button', { name: '切换深色' }).click()
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')
  await expect(page.getByRole('button', { name: '新增活动', exact: true })).toHaveCSS('background-color', 'rgb(22, 104, 220)')
  await page.screenshot({ path: resolve(output, 'dashboard-dark.png'), fullPage: true, animations: 'disabled' })
  await page.reload()
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')
  await sidebar.getByRole('menuitem', { name: /排班考勤$/ }).click()
  await sidebar.getByRole('menuitem', { name: '我的排班', exact: true }).click()
  await expect(page).toHaveURL(/\/routine\/my-schedules$/)
  await expect(sidebar.locator('.ant-menu-item-selected')).toContainText('我的排班')
  const footer = await sidebar.locator('.sidebar-account').boundingBox()
  expect(footer!.y + footer!.height).toBeLessThanOrEqual(1000)
})

test('工作台新增入口、活动筛选、密度和详情材料可用', async ({ page }) => {
  await setup(page)
  await page.goto('/')
  await page.getByRole('button', { name: '新增活动', exact: true }).click()
  await expect(page.getByRole('dialog')).toBeVisible()
  await page.getByLabel('活动名称', { exact: true }).fill('UI验收活动')
  await page.getByRole('button', { name: /保\s*存/ }).click()
  await expect(page.locator('tr', { hasText: 'UI验收活动' })).toBeVisible()
  await page.locator('.business-status-tabs').getByRole('button', { name: '策划审核' }).click()
  await expect(page.locator('.ant-table-tbody .ant-table-row')).toHaveCount(1)
  await page.getByText('紧凑', { exact: true }).click()
  await expect(page.locator('.ant-table-small')).toBeVisible()
  await expect(page.locator('.ant-table-thead > tr')).toHaveCSS('height', '48px')
  await page.locator('.ant-message').waitFor({ state: 'hidden' })
  await page.screenshot({ path: resolve(output, 'activities-light.png'), fullPage: true, animations: 'disabled' })
  await page.getByRole('button', { name: '详情', exact: true }).click()
  await expect(page.locator('.activity-stage-strip .current')).toContainText('策划审核')
  await page.getByRole('tab', { name: '材料', exact: true }).click()
  await expect(page.getByText('培训班策划书', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '切换深色' }).click()
  await page.screenshot({ path: resolve(output, 'detail-dark.png'), fullPage: true, animations: 'disabled' })
})

test('灵思与党务台账使用各自确认后的 V6 布局', async ({ page }) => {
  await setup(page)
  await page.goto('/agent')
  await expect(page.locator('.lingsi-sider')).toBeVisible()
  await expect(page.locator('.content-inner')).toHaveClass(/agent-content-inner/)
  await expect(page.locator('.app-sider')).toHaveCSS('width', '216px')
  await expect(page.locator('.lingsi-sider')).toHaveCSS('width', '218px')
  await expect(page.locator('.content-inner')).toHaveCSS('width', '1224px')
  await expect(page.locator('.lingsi-composer')).toHaveCSS('width', '1006px')
  expect(await page.locator('.lingsi-page').evaluate(el => el.clientHeight === el.closest('.app-content')?.clientHeight)).toBeTruthy()
  await page.getByRole('button', { name: '切换深色' }).click()
  await page.screenshot({ path: resolve(output, 'agent-dark.png'), fullPage: true, animations: 'disabled' })
  await page.goto('/party/members')
  await expect(page.locator('.page-header-title')).toHaveCSS('font-size', '26px')
  await expect(page.locator('.content-inner')).toHaveClass(/ui-v6/)
  await expect(page.locator('.records-stage-filters')).toBeVisible()
  await page.screenshot({ path: resolve(output, 'party-dark.png'), fullPage: true, animations: 'disabled' })
})

test('手机周历与抽屉导航可操作，登录表单适配明暗主题', async ({ page }) => {
  await setup(page)
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/')
  await expect(page.locator('.workbench-week')).toBeVisible()
  await page.getByText('全周', { exact: true }).click()
  await page.locator('.workbench-mobile-days button').nth(5).click()
  await expect(page.locator('.workbench-day.selected-day')).toContainText('周末志愿服务')
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.getByRole('button', { name: '打开导航' }).click()
  await expect(page.getByRole('dialog')).toBeVisible()
  await expect(page.getByRole('dialog').locator('.sidebar-brand')).toContainText('协奏')
  await expect(page.getByRole('dialog').getByRole('menuitem', { name: /活动管理$/ })).toHaveCSS('height', '44px')
  await page.getByRole('dialog').getByRole('menuitem', { name: /活动管理$/ }).click()
  await expect(page).toHaveURL(/\/activities$/)
  await expect(page.getByRole('dialog')).not.toBeVisible()
  await page.goto('/')
  await page.getByRole('button', { name: '切换深色' }).click()
  await page.getByRole('button', { name: '打开导航' }).click()
  await page.screenshot({ path: resolve(output, 'sidebar-mobile-dark.png'), fullPage: true, animations: 'disabled' })
  await page.keyboard.press('Escape')
  await page.screenshot({ path: resolve(output, 'dashboard-mobile-dark.png'), fullPage: true, animations: 'disabled' })
  await page.goto('/login')
  await expect(page.locator('.auth-panel')).toHaveCSS('background-color', 'rgb(30, 33, 39)')
  await page.screenshot({ path: resolve(output, 'login-mobile-dark.png'), fullPage: true, animations: 'disabled' })
})

test('侧栏分组遵循权限，空分组隐藏且灵思保留独立配色', async ({ page }) => {
  await setup(page, { ...user, roleCode: 'STAFF', roleLevel: 1, realName: '陈同学', permissions: ['activity:view'] })
  await page.goto('/')
  const sidebar = page.locator('.app-sider')
  await expect(sidebar.locator('.ant-menu-item-group-title')).toHaveText(['日常工作', '智能协作'])
  await expect(sidebar.getByRole('menuitem')).toHaveCount(3)
  await expect(sidebar.locator('.sidebar-assistant-group')).toContainText('灵思')
  await expect(sidebar.locator('.sidebar-account')).toContainText('陈同学')
  await sidebar.getByRole('menuitem', { name: /灵思$/ }).click()
  await expect(page).toHaveURL(/\/agent$/)
  await expect(page.locator('.lingsi-sider')).toBeVisible()
})
