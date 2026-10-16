import { test, expect, type Page, type Locator } from '@playwright/test'
import { mkdirSync } from 'node:fs'
import { resolve } from 'node:path'

test.use({
  channel: process.platform === 'win32' ? 'msedge' : undefined,
  viewport: { width: 1440, height: 1000 },
})
const output = resolve('..', 'docs', 'ui-form-redesign')
const user = {
  id: 1,
  username: 'teacher',
  realName: '林老师',
  roleId: 1,
  roleCode: 'TEACHER',
  roleLevel: 5,
  deptId: 1,
  deptName: '党建办公室',
  orgId: 1,
  permissions: [
    'activity:view',
    'activity:create',
    'activity:manage',
    'activity:edit',
    'member:view',
    'member:manage',
    'party:view',
    'party:manage',
    'content:manage',
    'content:view',
    'kb:read',
    'kb:manage',
    'material:view',
    'material:upload',
    'template:view',
    'template:manage',
    'quality:view',
    'quality:add',
    'notice:view',
    'notice:publish',
    'chat:manage',
    'chat:category',
    'user:view',
    'user:manage',
  ],
  managementCapabilities: [
    'user:view',
    'user:manage',
    'role:view',
    'role:assign',
  ],
}
async function setup(page: Page) {
  await page.addInitScript((user) => {
    localStorage.setItem(
      'pams_token',
      JSON.stringify({
        state: { token: 'form-surface-fixture', user },
        version: 1,
      }),
    )
    localStorage.setItem(
      'pams_theme',
      JSON.stringify({ state: { mode: 'light' }, version: 0 }),
    )
  }, user)
  const writes: Array<{ path: string; body: Record<string, unknown> }> = []
  const errors: string[] = []
  page.on('pageerror', (e) => errors.push(e.message))
  const activity = {
    id: 1,
    name: '第四十期培训班',
    type: 'PARTY_LESSON',
    status: 'EXECUTING',
    leader: '林老师',
  }
  const party = {
    id: 1,
    name: '陈禾',
    studentNo: '2026240101',
    className: '计科2401',
    politicalStatus: '入党积极分子',
    phone: '13900000000',
    gender: '男',
    nation: '汉族',
  }
  let categories = [{ id: 1, name: '活动群', sortOrder: 0 }]
  await page.route('**/api/**', async (route) => {
    const request = route.request(),
      path = new URL(request.url()).pathname
    if (!path.startsWith('/api/')) return route.continue()
    let data: unknown = []
    if (request.method() !== 'GET') {
      const body = request.postData()?.startsWith('{')
        ? request.postDataJSON()
        : {}
      writes.push({ path, body })
      if (path === '/api/chat/categories/1')
        categories = [{ ...categories[0], name: body.name }]
      data = 2
    } else if (path === '/api/auth/me') data = user
    else if (path === '/api/orgs/current')
      data = { id: 1, name: '党建办公室', enabledPacks: null }
    else if (path === '/api/notifications/unread-count') data = 0
    else if (path === '/api/depts') data = [{ id: 1, name: '文秘部' }]
    else if (path === '/api/dashboard')
      data = { activityStats: { EXECUTING: 1 } }
    else if (path === '/api/activities')
      data = { records: [activity], total: 1 }
    else if (path === '/api/activities/1') data = activity
    else if (path === '/api/activities/1/detail')
      data = {
        activity,
        tasks: [],
        agendas: [],
        seatZones: {},
        score: { rules: [], records: [] },
        signinCount: 0,
        plan: {
          latest: {
            id: 1,
            activityId: 1,
            version: 1,
            status: 'DRAFT',
            background: '活动原有背景',
            purpose: '开展专题学习',
          },
          status: 'DRAFT',
        },
      }
    else if (path === '/api/users') data = { records: [user], total: 1 }
    else if (path === '/api/users/options') data = [user]
    else if (path === '/api/roles/assignable')
      data = [{ id: 1, code: 'TEACHER', name: '老师', status: 'ACTIVE' }]
    else if (path === '/api/member-sessions')
      data = [{ id: 1, name: '2026—2027届', isCurrent: 1 }]
    else if (path === '/api/members/stats')
      data = { total: 0, byDept: [], byPosition: [], byStatus: [] }
    else if (path === '/api/party/members/1') data = party
    else if (path === '/api/party/members/stages')
      data = [
        { id: 1, stage: 'ACTIVE', status: 'CURRENT', startDate: '2026-09-01' },
      ]
    else if (path === '/api/chat/categories') data = categories
    else if (path === '/api/kb/stats')
      data = {
        retriever: 'fulltext-ngram',
        fulltextAvailable: true,
        documentCount: 0,
        chunkCount: 0,
        retrievalTotal: 0,
      }
    else if (path === '/api/kb/chunks') data = { records: [], total: 0 }
    else if (
      [
        '/api/articles',
        '/api/news',
        '/api/members',
        '/api/party/members',
        '/api/materials',
        '/api/credits',
      ].includes(path)
    )
      data = { records: [], total: 0 }
    await route.fulfill({ json: { code: 200, data } })
  })
  mkdirSync(output, { recursive: true })
  return { writes, errors }
}
async function noOverflow(surface: Locator) {
  await expect
    .poll(() => surface.evaluate((el) => el.scrollWidth <= el.clientWidth + 1))
    .toBeTruthy()
}
async function snapshot(page: Page, name: string) {
  await page.screenshot({
    path: resolve(output, `${name}.png`),
    fullPage: true,
    animations: 'disabled',
  })
}

test('长文在页内工作区编辑，保留活动关联并按原接口保存', async ({ page }) => {
  const { writes, errors } = await setup(page)
  await page.goto('/content/articles')
  await page.getByRole('button', { name: /撰写推文$/ }).click()
  const workspace = page.locator('.form-workspace')
  await expect(
    workspace.getByRole('heading', { name: '稿件信息' }),
  ).toBeVisible()
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await workspace.getByLabel('标题', { exact: true }).fill('培训班学习纪实')
  await workspace.getByLabel('所属活动').click()
  await page
    .locator('.ant-select-dropdown:visible .ant-select-item-option')
    .filter({ hasText: '第四十期培训班' })
    .click()
  await workspace.getByLabel('负责人', { exact: true }).click()
  await page
    .locator('.ant-select-dropdown:visible .ant-select-item-option')
    .filter({ hasText: '林老师' })
    .click()
  await workspace
    .getByLabel('正文（可选底稿）')
    .fill('专题学习内容\n现场记录与后续安排')
  await snapshot(page, 'article-workspace-light')
  await workspace.getByRole('button', { name: /保\s*存$/, exact: true }).click()
  await expect(workspace).toHaveCount(0)
  expect(writes.find((r) => r.path === '/api/articles')?.body).toMatchObject({
    title: '培训班学习纪实',
    activityId: 1,
    authorId: 1,
    content: '专题学习内容\n现场记录与后续安排',
  })
  await page.getByRole('button', { name: /撰写推文$/ }).click()
  await expect(workspace.getByLabel('标题', { exact: true })).toHaveValue('')
  await workspace.getByRole('button', { name: /取\s*消/ }).click()
  await page.goto('/content/news')
  await page.getByRole('button', { name: /撰写新闻稿$/ }).click()
  await workspace.getByLabel('标题', { exact: true }).fill('培训班开班')
  await workspace
    .getByLabel('正文', { exact: true })
    .fill('新闻稿正文，按原文保留换行。')
  await workspace.getByRole('button', { name: /保\s*存$/, exact: true }).click()
  await expect(workspace).toHaveCount(0)
  expect(writes.find((r) => r.path === '/api/news')?.body.title).toBe(
    '培训班开班',
  )
  expect(errors).toEqual([])
})

test('列表录入使用抽屉，桌面与深色手机布局无横向溢出', async ({ page }) => {
  test.setTimeout(90_000)
  const { errors } = await setup(page)
  const cases = [
    ['/activities', '新增活动', '活动名称'],
    ['/members', '新增成员', '姓名'],
    ['/admin/users', '新增用户', '用户名'],
    ['/archive/materials', '上传材料', '材料名称'],
    ['/archive/templates', '上传模板', '模板名称'],
    ['/archive/credits', '新增加分', '姓名'],
    ['/archive/group-chats', '新增群聊', '群聊名称'],
    ['/party/rosters', '新增名单', '姓名'],
    ['/archive/announcements', '发布公告', '标题'],
  ]
  for (const [path, button, label] of cases) {
    await page.setViewportSize({ width: 1440, height: 1000 })
    await page.goto(path)
    await page.getByRole('button', { name: new RegExp(button + '$') }).click()
    const drawer = page.getByRole('dialog')
    await expect(drawer.locator('.ant-drawer-body')).toBeVisible()
    await expect(drawer.getByLabel(label, { exact: true })).toBeVisible()
    await noOverflow(drawer.locator('.ant-drawer-body'))
    if (path === '/members') await snapshot(page, 'member-drawer-light')
    await drawer.getByRole('button', { name: /取\s*消/ }).click()
    await expect(page.getByRole('dialog')).toHaveCount(0)
    await page.getByRole('button', { name: '切换深色', exact: true }).click()
    await page.setViewportSize({ width: 390, height: 844 })
    await page.getByRole('button', { name: new RegExp(button + '$') }).click()
    await expect(drawer.locator('.ant-drawer-body')).toBeVisible()
    await noOverflow(drawer.locator('.ant-drawer-body'))
    const footer = drawer.locator('.ant-drawer-footer')
    await expect(footer).toBeInViewport()
    await expect
      .poll(async () => {
        const box = await drawer.boundingBox()
        return box!.x + box!.width
      })
      .toBeLessThanOrEqual(391)
    if (path === '/members') await snapshot(page, 'member-drawer-dark-mobile')
    await drawer.getByRole('button', { name: /取\s*消/ }).click()
  }
  expect(errors).toEqual([])
})

test('党务长档案和专项登记使用分组工作区，手机可返回并继续其他操作', async ({
  page,
}) => {
  const { writes, errors } = await setup(page)
  await page.goto('/party/members')
  await page.getByRole('button', { name: /新增成员$/ }).click()
  const workspace = page.locator('.form-workspace')
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await workspace.getByLabel('姓名', { exact: true }).fill('李同学')
  await workspace.getByLabel('联系电话', { exact: true }).fill('13800000000')
  await snapshot(page, 'party-record-workspace-light')
  await workspace.getByRole('button', { name: /保\s*存$/, exact: true }).click()
  await expect(workspace).toHaveCount(0)
  expect(
    writes.find((r) => r.path === '/api/party/members')?.body,
  ).toMatchObject({ name: '李同学', phone: '13800000000' })
  await page.goto('/party/members/1')
  await page.getByRole('button', { name: '切换深色', exact: true }).click()
  await page.setViewportSize({ width: 390, height: 844 })
  for (const [tab, group] of [
    ['函调', '父亲'],
    ['登记', '申请与谈话'],
    ['转移', '组织关系转移'],
  ]) {
    await page.getByRole('tab', { name: tab, exact: true }).click()
    await page
      .getByRole('button', { name: new RegExp('新增' + tab + '$') })
      .click()
    await expect(
      workspace.getByRole('heading', { name: group, exact: true }),
    ).toBeAttached()
    await noOverflow(workspace)
    if (tab === '登记') await snapshot(page, 'party-register-dark-mobile')
    await workspace
      .getByRole('button', { name: /返\s*回$/, exact: true })
      .click()
    await expect(workspace).toHaveCount(0)
  }
  await page.getByRole('button', { name: '记录阶段流转', exact: true }).click()
  await expect(
    page.getByRole('dialog').locator('.ant-modal-body'),
  ).toBeVisible()
  await expect(page.getByRole('dialog').getByLabel('流转说明')).toBeVisible()
  await snapshot(page, 'stage-dialog-dark-mobile')
  expect(errors).toEqual([])
})

test('分类重命名在列表行内完成，不打开第二层窗口', async ({ page }) => {
  const { writes, errors } = await setup(page)
  await page.goto('/archive/group-chats')
  await page.getByRole('button', { name: /分类管理$/ }).click()
  await page.getByRole('button', { name: '重命名活动群', exact: true }).click()
  await page.getByLabel('分类名称', { exact: true }).fill('培训班活动群')
  await page
    .getByRole('dialog')
    .getByRole('button', { name: /保\s*存$/, exact: true })
    .click()
  await expect(page.getByRole('dialog')).toHaveCount(1)
  await expect(
    page.getByRole('dialog').getByText('培训班活动群', { exact: true }),
  ).toBeVisible()
  expect(writes.find((r) => r.path === '/api/chat/categories/1')?.body).toEqual(
    { name: '培训班活动群' },
  )
  expect(errors).toEqual([])
})

test('策划书字段与制度文档在业务标签页内编辑，保留回填与保存内容', async ({
  page,
}) => {
  const { writes, errors } = await setup(page)
  await page.goto('/activities/1')
  await page.getByRole('tab', { name: '策划书', exact: true }).click()
  await page.getByRole('button', { name: /字段编辑$/ }).click()
  const workspace = page.locator('.form-workspace')
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await expect(workspace.getByLabel('活动背景', { exact: true })).toHaveValue(
    '活动原有背景',
  )
  await workspace.getByLabel('活动背景', { exact: true }).fill('更新活动背景')
  await workspace.getByRole('button', { name: /保\s*存$/, exact: true }).click()
  await expect(workspace).toHaveCount(0)
  expect(writes.find((r) => r.path === '/api/plans/1')?.body).toMatchObject({
    activityId: 1,
    background: '更新活动背景',
    purpose: '开展专题学习',
  })
  await page.getByRole('tab', { name: '推文', exact: true }).click()
  await page.getByRole('button', { name: /新建推文$/ }).click()
  await expect(workspace.getByLabel('标题', { exact: true })).toBeVisible()
  await workspace.getByRole('button', { name: /返\s*回$/, exact: true }).click()
  await expect(workspace).toHaveCount(0)
  await page.goto('/knowledge')
  await page.getByRole('button', { name: '录入制度文档', exact: true }).click()
  await expect(
    workspace.getByRole('heading', { name: '文档正文', exact: true }),
  ).toBeVisible()
  await workspace.getByLabel('标题', { exact: true }).fill('组织工作指引')
  await workspace
    .getByLabel('正文', { exact: true })
    .fill('第一条 保留条款编号与正文内容。')
  await snapshot(page, 'knowledge-workspace-light')
  await workspace
    .getByRole('button', { name: '保存并建立索引', exact: true })
    .click()
  await expect(workspace).toHaveCount(0)
  expect(
    writes.find((r) => r.path === '/api/kb/documents')?.body,
  ).toMatchObject({
    title: '组织工作指引',
    body: '第一条 保留条款编号与正文内容。',
    visibility: 'ALL',
  })
  expect(errors).toEqual([])
})
