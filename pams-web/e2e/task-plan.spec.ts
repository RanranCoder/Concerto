import { test, expect, type Page } from '@playwright/test'
import { mkdir } from 'node:fs/promises'
import path from 'node:path'
import dayjs from 'dayjs'
import type {
  TaskVO,
  TaskPlanRequest,
  TaskPlanUndo,
  TaskSave,
} from '../src/api/task'

test.use({
  channel: process.platform === 'win32' ? 'msedge' : undefined,
  viewport: { width: 1440, height: 1080 },
})
const output = path.resolve('../docs/design/ui-v6-gantt-implementation')
test.beforeAll(async () => {
  await mkdir(output, { recursive: true })
})

async function setup(
  page: Page,
  options: {
    theme?: string
    role?: 'manager' | 'executor' | 'viewer'
    archived?: boolean
    taskVisible?: boolean
  } = {},
) {
  page.setDefaultTimeout(15_000)
  const role = options.role || 'manager'
  const permissions = [
    'activity:view',
    ...(options.taskVisible === false ? [] : ['task:view']),
    'material:view',
    'plan:view',
    'agenda:view',
    'checkin:view',
    'content:view',
    'seat:view',
    'score:view',
    ...(role === 'manager'
      ? ['task:manage', 'task:progress']
      : role === 'executor'
        ? ['task:progress']
        : []),
  ]
  const user = {
    id: 1,
    username: 'task-review',
    realName: '林同学',
    permissions,
    businessPermissions: permissions,
    managementCapabilities: [],
    roleCode: 'TEACHER',
    roleLevel: 5,
    orgId: 1,
    orgName: '党建办公室',
    isOwner: false,
  }
  await page.addInitScript(
    ({ user, theme }) => {
      localStorage.setItem(
        'pams_token',
        JSON.stringify({
          state: { token: 'task-plan-test', user },
          version: 1,
        }),
      )
      localStorage.setItem(
        'pams_theme',
        JSON.stringify({ state: { mode: theme }, version: 0 }),
      )
    },
    { user, theme: options.theme || 'light' },
  )
  const date = (offset: number) =>
    dayjs().add(offset, 'day').format('YYYY-MM-DD')
  const activity = {
    id: 7,
    name: '十月主题党日活动',
    theme: '深学细悟，笃行实干',
    type: 'PARTY_DAY',
    status: options.archived ? 'ARCHIVED' : 'EXECUTING',
    startDate: date(2),
    endDate: date(2),
    organizer: '党建办公室',
    leader: '林同学',
    location: '党员活动室',
    host: '周同学',
    createdAt: date(-10),
  }
  const row = (
    id: number,
    name: string,
    dept: number | null,
    start: number | null,
    end: number | null,
    progress: number,
    dep: number | null = null,
    milestone = false,
  ): TaskVO => ({
    id,
    revision: 0,
    activityId: 7,
    name,
    deptId: dept,
    assignee: id % 2 ? '林同学' : '周同学',
    startDate: start === null ? null : date(start),
    endDate: milestone
      ? start === null
        ? null
        : date(start)
      : end === null
        ? null
        : date(end),
    progress,
    dependsOn: dep,
    isMilestone: milestone ? 1 : 0,
    status: 'TODO',
    priority: 0,
    description: '按活动分工完成准备，相关业务状态在各模块维护。',
    createdAt: date(-10),
    updatedAt: date(-1),
  })
  let tasks = [
    row(1, '策划定稿确认', 1, -8, -6, 100, null, true),
    row(2, '场地与设备确认', 2, -5, -1, 60),
    row(3, '现场会务准备', 2, 0, 2, 0, 2),
    row(4, '核对参会名单', 2, -2, 1, 75, 1),
    row(5, '活动举办', 3, 2, 2, 0, 3, true),
    row(6, '宣传报道', 3, 3, 4, 0, 5),
    row(7, '准备备用物资', null, null, null, 0),
  ]
  const state = {
    failMembers: false,
    memberDelay: 0,
    failList: false,
    failSave: false,
    conflict: false,
    writes: [] as Array<{ path: string; body: unknown }>,
  }
  const snapshot = (t: TaskVO): TaskSave => ({
    ...t,
    isMilestone: t.isMilestone ?? 0,
    progress: t.progress ?? 0,
    priority: t.priority ?? 0,
  })
  await page.route('**/api/**', async (route) => {
    const req = route.request(),
      url = new URL(req.url()),
      pathname = url.pathname,
      method = req.method(),
      body = req.postDataJSON()
    if (!pathname.startsWith('/api/')) return route.continue()
    const ok = (data: unknown) => route.fulfill({ json: { code: 200, data } })
    const fail = (message: string) =>
      route.fulfill({ json: { code: 2007, message } })
    if (method !== 'GET') state.writes.push({ path: pathname, body })
    if (pathname === '/api/auth/me') return ok(user)
    if (pathname === '/api/orgs/current')
      return ok({
        id: 1,
        name: '党建办公室',
        shortName: '党建办公室',
        enabledPacks: '[]',
        themeJson: '{"primaryColor":"#1668DC"}',
      })
    if (pathname === '/api/depts')
      return ok([
        { id: 1, name: '党建办公室' },
        { id: 2, name: '组织部' },
        { id: 3, name: '文秘部' },
      ])
    if (pathname === '/api/users/options') {
      const departmentId = Number(url.searchParams.get('deptId'))
      if (state.memberDelay && departmentId === 2)
        await new Promise((resolve) => setTimeout(resolve, state.memberDelay))
      if (state.failMembers) return fail('部门成员加载失败')
      return ok([
        {
          id: 11,
          realName: '林同学',
          deptId: 1,
          deptName: '党建办公室',
          roleName: '成员',
        },
        {
          id: 21,
          realName: '周同学',
          deptId: 2,
          deptName: '组织部',
          roleName: '成员',
        },
        {
          id: 22,
          realName: '王新宇',
          deptId: 2,
          deptName: '组织部',
          roleName: '成员',
        },
        {
          id: 23,
          realName: '王晓雨',
          deptId: 2,
          deptName: '组织部',
          roleName: '成员',
        },
        {
          id: 31,
          realName: '刘同学',
          deptId: 3,
          deptName: '文秘部',
          roleName: '成员',
        },
      ])
    }
    if (pathname === '/api/activities/7/detail')
      return ok({
        activity,
        tasks,
        plan: null,
        agendas: [],
        seatZones: {},
        score: { rules: [], records: [] },
        signinCount: 0,
      })
    if (pathname === '/api/activities/7') return ok(activity)
    if (pathname === '/api/tasks' && method === 'GET')
      return state.failList ? fail('任务计划加载失败') : ok(tasks)
    if (pathname === '/api/tasks/plan/preview') {
      const proposal = body as TaskPlanRequest
      const candidate = {
        ...(tasks.find((t) => t.id === proposal.id) ||
          row(99, '', null, null, null, 0)),
        ...proposal.task,
        id: proposal.id || 99,
      }
      const impacts =
        proposal.id === 2 &&
        candidate.endDate !== tasks.find((t) => t.id === 2)?.endDate
          ? tasks
              .filter((t) => [3, 5, 6].includes(t.id))
              .map((t) => ({
                before: t,
                after: {
                  ...t,
                  startDate: dayjs(t.startDate)
                    .add(3, 'day')
                    .format('YYYY-MM-DD'),
                  endDate: dayjs(t.endDate).add(3, 'day').format('YYYY-MM-DD'),
                },
              }))
          : []
      return ok({ candidate, impacts, token: 'preview-1' })
    }
    if (pathname === '/api/tasks/plan' && method === 'POST') {
      if (state.failSave) return fail('保存失败，请重试')
      if (state.conflict) return fail('任务计划已发生变化，请重新预览后保存')
      const proposal = body as TaskPlanRequest,
        existing = tasks.find((t) => t.id === proposal.id)
      const entries: TaskPlanUndo['entries'] = []
      const target = {
        ...(existing || row(99, '', null, null, null, 0)),
        ...proposal.task,
        id: existing?.id || 99,
        revision: (existing?.revision ?? 0) + 1,
      } as TaskVO
      entries.push({
        id: target.id,
        expectedRevision: target.revision,
        before: existing ? snapshot(existing) : null,
      })
      tasks = existing
        ? tasks.map((t) => (t.id === target.id ? target : t))
        : [...tasks, target]
      if (proposal.cascade)
        tasks = tasks.map((t) => {
          if (![3, 5, 6].includes(t.id)) return t
          const next = {
            ...t,
            revision: t.revision + 1,
            startDate: dayjs(t.startDate).add(3, 'day').format('YYYY-MM-DD'),
            endDate: dayjs(t.endDate).add(3, 'day').format('YYYY-MM-DD'),
          }
          entries.push({
            id: next.id,
            expectedRevision: next.revision,
            before: snapshot(t),
          })
          return next
        })
      return ok({ tasks, undo: { activityId: 7, entries } })
    }
    if (pathname === '/api/tasks/plan/undo') {
      const undo = body as TaskPlanUndo
      tasks = tasks.flatMap((t) => {
        const entry = undo.entries.find((e) => e.id === t.id)
        return !entry
          ? [t]
          : entry.before
            ? [{ ...t, ...entry.before, revision: t.revision + 1 } as TaskVO]
            : []
      })
      return ok({ tasks, undo })
    }
    if (/^\/api\/tasks\/\d+\/progress$/.test(pathname)) {
      const id = Number(pathname.split('/')[3]),
        current = tasks.find((t) => t.id === id)!
      if (state.conflict) return fail('任务已被其他操作更新，请刷新后重试')
      const next = {
        ...current,
        revision: current.revision + 1,
        progress: body.progress,
      }
      tasks = tasks.map((t) => (t.id === id ? next : t))
      return ok(next)
    }
    if (/^\/api\/tasks\/\d+$/.test(pathname) && method === 'DELETE') {
      const id = Number(pathname.split('/').at(-1)),
        t = tasks.find((t) => t.id === id)!
      tasks = tasks.filter((t) => t.id !== id)
      return ok({
        tasks,
        undo: {
          activityId: 7,
          entries: [
            { id, expectedRevision: t.revision + 1, before: snapshot(t) },
          ],
        },
      })
    }
    if (['/api/materials', '/api/articles', '/api/users'].includes(pathname))
      return ok({ records: [], total: 0 })
    if (pathname.includes('/unread')) return ok(0)
    if (pathname === '/api/orgs/tree') return ok([])
    return ok([])
  })
  await page.route('**/ws/**', (route) =>
    route.fulfill({
      status: 200,
      json: {
        websocket: false,
        origins: ['*:*'],
        cookie_needed: false,
        entropy: 1,
      },
    }),
  )
  return { state, date, tasks: () => tasks }
}
const drawer = (page: Page) => page.locator('.task-plan-drawer')
const departmentPicker = (page: Page) =>
  drawer(page)
    .locator('.ant-select')
    .filter({
      has: page.getByRole('combobox', { name: '负责部门', exact: true }),
    })
const openTask = async (page: Page, name: string) =>
  page
    .locator('.task-plan .task-name')
    .filter({ hasText: name })
    .first()
    .click()
const shot = async (page: Page, name: string) =>
  page.screenshot({
    path: path.join(output, `${name}.png`),
    fullPage: true,
    animations: 'disabled',
  })

test('任务标签、筛选、进度与撤销、摘要入口', async ({ page }) => {
  const errors: string[] = []
  page.on('pageerror', (e) => errors.push(e.message))
  const fixture = await setup(page)
  await page.goto('/activities/7')
  await expect(page.getByRole('tab', { name: '任务计划' })).toHaveAttribute(
    'aria-selected',
    'true',
  )
  await expect(
    page.locator('.activity-detail-tabs [role=tab]').first(),
  ).toHaveText('任务计划')
  await expect(page.locator('[data-task-row]')).toHaveCount(7)
  await page.getByRole('button', { name: '逾期 1', exact: true }).click()
  await expect(page.locator('[data-task-row]')).toHaveCount(1)
  await page.getByRole('button', { name: '清除筛选', exact: true }).click()
  await page.getByLabel('搜索任务、负责人').fill('不存在')
  await expect(page.getByText('没有符合条件的任务')).toBeVisible()
  await page
    .getByRole('button', { name: '清除筛选', exact: true })
    .first()
    .click()
  await openTask(page, '核对参会名单')
  await expect(drawer(page)).toContainText('前后置关系')
  await drawer(page)
    .getByRole('button', { name: '已完成', exact: true })
    .click()
  await drawer(page).getByRole('button', { name: '保存进度' }).click()
  await expect(page.getByText('执行进度已保存', { exact: true })).toBeVisible()
  expect(fixture.tasks().find((t) => t.id === 4)?.progress).toBe(100)
  await page.getByRole('button', { name: '撤销上次修改' }).click()
  await expect(
    page.getByText('已撤销上次任务修改', { exact: true }),
  ).toBeVisible()
  expect(fixture.tasks().find((t) => t.id === 4)?.progress).toBe(75)
  await page.getByRole('tab', { name: '基本信息', exact: true }).click()
  await page
    .locator('.activity-task-summary-list button')
    .filter({ hasText: '场地与设备确认' })
    .click()
  await expect(drawer(page)).toContainText('场地与设备确认')
  await drawer(page)
    .locator('.task-drawer-footer')
    .getByRole('button', { name: '关闭', exact: true })
    .click()
  await expect(page.getByRole('tab', { name: '任务计划' })).toHaveAttribute(
    'aria-selected',
    'true',
  )
  expect(errors).toEqual([])
})

test('未排期创建、未保存保护与改期事务', async ({ page }) => {
  const fixture = await setup(page)
  await page.goto('/activities/7?tab=gantt')
  await page.getByRole('button', { name: '新建任务', exact: true }).click()
  await drawer(page).getByLabel('任务名称').fill('备用物资核对')
  await drawer(page).getByRole('button', { name: '取消', exact: true }).click()
  await expect(
    page
      .locator('.ant-modal-confirm-title')
      .filter({ hasText: '放弃未保存的修改？' }),
  ).toBeVisible()
  await page.getByRole('button', { name: '继续编辑' }).click()
  await drawer(page)
    .getByRole('button', { name: '创建任务', exact: true })
    .click()
  await expect(page.getByText('任务已创建', { exact: true })).toBeVisible()
  expect(
    fixture.tasks().find((t) => t.name === '备用物资核对')?.startDate,
  ).toBeNull()
  await openTask(page, '场地与设备确认')
  await drawer(page).getByRole('button', { name: '编辑安排' }).click()
  await expect(drawer(page).getByLabel('任务名称')).toHaveValue(
    '场地与设备确认',
  )
  await expect(drawer(page).getByLabel('开始日期')).toHaveValue(
    fixture.date(-5),
  )
  await drawer(page).getByLabel('结束日期').fill(fixture.date(2))
  await drawer(page).getByLabel('结束日期').press('Enter')
  await drawer(page).getByRole('button', { name: '保存安排' }).click()
  await expect(page.locator('.task-plan-impact')).toBeVisible()
  await expect(page.locator('.task-plan-impact')).toContainText(
    '3 项后续任务需要顺延',
  )
  await shot(page, 'reschedule-impact')
  await page.getByRole('radio', { name: /同时顺延/ }).check()
  await page.getByRole('button', { name: '确认保存', exact: true }).click()
  await expect(page.getByText('任务安排已保存', { exact: true })).toBeVisible()
  expect(
    fixture.state.writes.some(
      (w) =>
        w.path === '/api/tasks/plan' && (w.body as TaskPlanRequest).cascade,
    ),
  ).toBe(true)
  await page.getByRole('button', { name: '撤销上次修改' }).click()
  await expect(
    page.getByText('已撤销上次任务修改', { exact: true }),
  ).toBeVisible()
})

test('保存冲突保留输入，失败重试和旧链接兼容', async ({ page }) => {
  const fixture = await setup(page)
  await page.goto('/activities/7/gantt')
  await expect(page).toHaveURL(/tab=gantt/)
  await openTask(page, '核对参会名单')
  await drawer(page).getByRole('button', { name: '编辑安排' }).click()
  await drawer(page).getByLabel('任务名称').fill('保留中的修改')
  fixture.state.conflict = true
  await drawer(page).getByRole('button', { name: '保存安排' }).click()
  await expect(drawer(page)).toContainText('任务计划已发生变化')
  await expect(drawer(page).getByLabel('任务名称')).toHaveValue('保留中的修改')
  fixture.state.conflict = false
  await drawer(page).getByRole('button', { name: '保存安排' }).click()
  await expect(page.getByText('任务安排已保存', { exact: true })).toBeVisible()
  fixture.state.failList = true
  await page.getByRole('button', { name: '刷新任务' }).click()
  await expect(
    page.getByText('任务计划加载失败', { exact: true }),
  ).toBeVisible()
  fixture.state.failList = false
  await page.getByRole('button', { name: '重新加载' }).click()
  await expect(page.locator('[data-task-row]')).toHaveCount(7)
})

for (const role of ['executor', 'viewer'] as const)
  test(`${role} 权限保持单一进度入口`, async ({ page }) => {
    const fixture = await setup(page, { role })
    await page.goto('/activities/7?tab=gantt')
    await expect(
      page.getByRole('button', { name: '新建任务', exact: true }),
    ).toHaveCount(0)
    await openTask(page, '核对参会名单')
    await expect(
      drawer(page).getByRole('button', { name: '编辑安排' }),
    ).toHaveCount(0)
    if (role === 'viewer') {
      await expect(drawer(page).getByRole('slider')).toBeDisabled()
      await expect(
        drawer(page).getByRole('button', { name: '保存进度' }),
      ).toHaveCount(0)
    } else {
      await drawer(page)
        .getByRole('button', { name: '已完成', exact: true })
        .click()
      await drawer(page).getByRole('button', { name: '保存进度' }).click()
      expect(
        fixture.state.writes.some((w) => w.path === '/api/tasks/4/progress'),
      ).toBe(true)
    }
  })

test('归档活动只读', async ({ page }) => {
  await setup(page, { archived: true })
  await page.goto('/activities/7?tab=gantt')
  await expect(
    page.getByText('活动已归档，任务计划以只读方式保留。'),
  ).toBeVisible()
  await expect(
    page.getByRole('button', { name: '新建任务', exact: true }),
  ).toHaveCount(0)
})

test('拖动只预览，取消不会写入任务', async ({ page }) => {
  const fixture = await setup(page)
  await page.goto('/activities/7?tab=gantt')
  const bar = page.locator('.task-bar').filter({ hasText: '场地与设备确认' })
  await expect(bar).toBeVisible()
  const box = (await bar.boundingBox())!
  const original = fixture.tasks().find((t) => t.id === 2)?.endDate
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2)
  await page.mouse.down()
  await page.mouse.move(box.x + box.width / 2 + 85, box.y + box.height / 2, {
    steps: 8,
  })
  await page.mouse.up()
  await expect(page.locator('.task-plan-impact')).toBeVisible()
  expect(fixture.state.writes.some((w) => w.path === '/api/tasks/plan')).toBe(
    false,
  )
  await page
    .locator('.task-plan-impact .ant-modal-footer')
    .getByRole('button', { name: /取\s*消/ })
    .click()
  expect(fixture.tasks().find((t) => t.id === 2)?.endDate).toBe(original)
  await expect(page.locator('.task-plan-impact')).toHaveCount(0)
})

test('新建里程碑只填写单日，不具备查看权限时隐藏任务入口', async ({ page }) => {
  const fixture = await setup(page)
  await page.goto('/activities/7?tab=gantt')
  await page.getByRole('button', { name: '新建任务', exact: true }).click()
  await drawer(page).getByLabel('任务名称').fill('会务准备确认')
  await drawer(page).getByRole('switch').check()
  await expect(drawer(page).getByLabel('结束日期')).toHaveCount(0)
  await drawer(page).getByLabel('里程碑日期').fill(fixture.date(1))
  await drawer(page).getByLabel('里程碑日期').press('Enter')
  await drawer(page)
    .getByRole('button', { name: '创建任务', exact: true })
    .click()
  await expect(page.getByText('任务已创建', { exact: true })).toBeVisible()
  const milestone = fixture.tasks().find((t) => t.name === '会务准备确认')!
  expect(milestone.startDate).toBe(milestone.endDate)
  expect(milestone.isMilestone).toBe(1)
})

test('没有任务查看权限时不加载或展示任务计划', async ({ page }) => {
  await setup(page, { taskVisible: false, role: 'viewer' })
  const requests: string[] = []
  page.on('request', (request) => {
    if (new URL(request.url()).pathname === '/api/tasks')
      requests.push(request.url())
  })
  await page.goto('/activities/7?tab=gantt')
  await expect(
    page.getByRole('tab', { name: '基本信息', exact: true }),
  ).toHaveAttribute('aria-selected', 'true')
  await expect(
    page.getByRole('tab', { name: '任务计划', exact: true }),
  ).toHaveCount(0)
  await expect(page.getByRole('button', { name: '管理任务计划' })).toHaveCount(
    0,
  )
  expect(requests).toEqual([])
})

test('负责人按部门联想选择，切换或清空部门会清空负责人', async ({ page }) => {
  const fixture = await setup(page)
  await page.goto('/activities/7?tab=gantt')
  await page.getByRole('button', { name: '新建任务', exact: true }).click()
  const owner = drawer(page).getByRole('combobox', {
    name: '负责人',
    exact: true,
  })
  await expect(owner).toBeDisabled()
  const selectDepartment = async (name: string) => {
    await departmentPicker(page).click()
    await page
      .locator('.ant-select-dropdown:visible')
      .getByText(name, { exact: true })
      .click()
  }
  await selectDepartment('组织部')
  await expect(owner).toBeEnabled()
  await owner.fill('王')
  const menu = page.locator('.ant-select-dropdown:visible')
  await expect(
    menu
      .locator('.ant-select-item-option-content')
      .filter({ hasText: /^王新宇$/ }),
  ).toBeVisible()
  await expect(
    menu
      .locator('.ant-select-item-option-content')
      .filter({ hasText: /^王晓雨$/ }),
  ).toBeVisible()
  await shot(page, 'assignee-department-search')
  await expect(menu.getByText('林同学', { exact: true })).toHaveCount(0)
  await menu
    .locator('.ant-select-item-option-content')
    .filter({ hasText: /^王新宇$/ })
    .click()
  await expect(
    drawer(page)
      .locator('.ant-select-selection-item')
      .filter({ hasText: '王新宇' }),
  ).toBeVisible()
  await selectDepartment('文秘部')
  await expect(owner).toBeEnabled()
  await expect(drawer(page).getByText('王新宇', { exact: true })).toHaveCount(0)
  await owner.click()
  await page
    .locator('.ant-select-dropdown:visible')
    .locator('.ant-select-item-option-content')
    .filter({ hasText: /^刘同学$/ })
    .click()
  await drawer(page).getByLabel('任务名称').fill('按部门指派负责人')
  await drawer(page)
    .getByRole('button', { name: '创建任务', exact: true })
    .click()
  await expect(page.getByText('任务已创建', { exact: true })).toBeVisible()
  const saved = fixture
    .tasks()
    .find((task) => task.name === '按部门指派负责人')!
  expect(saved.deptId).toBe(3)
  expect(saved.assignee).toBe('刘同学')
  await openTask(page, '按部门指派负责人')
  await drawer(page).getByRole('button', { name: '编辑安排' }).click()
  const department = departmentPicker(page)
  await department.hover()
  await department.locator('.ant-select-clear').click()
  await expect(
    drawer(page).getByRole('combobox', { name: '负责人', exact: true }),
  ).toBeDisabled()
  await expect(drawer(page).getByText('刘同学', { exact: true })).toHaveCount(0)
})

test('成员加载失败可以重试，较早部门的慢请求不会污染新部门候选', async ({
  page,
}) => {
  const fixture = await setup(page)
  fixture.state.failMembers = true
  await page.goto('/activities/7?tab=gantt')
  await page.getByRole('button', { name: '新建任务', exact: true }).click()
  await departmentPicker(page).click()
  await page
    .locator('.ant-select-dropdown:visible')
    .getByText('组织部', { exact: true })
    .click()
  await expect(drawer(page).getByText('部门成员加载失败。')).toBeVisible()
  fixture.state.failMembers = false
  await drawer(page)
    .getByRole('button', { name: '重新加载', exact: true })
    .click()
  const owner = drawer(page).getByRole('combobox', {
    name: '负责人',
    exact: true,
  })
  await expect(owner).toBeEnabled()
  fixture.state.memberDelay = 600
  await departmentPicker(page).click()
  await page
    .locator('.ant-select-dropdown:visible')
    .getByText('文秘部', { exact: true })
    .click()
  await expect(owner).toBeEnabled()
  const oldResponse = page.waitForResponse((response) =>
    response.url().includes('/api/users/options?deptId=2'),
  )
  await departmentPicker(page).click()
  await page
    .locator('.ant-select-dropdown:visible')
    .getByText('组织部', { exact: true })
    .click()
  await departmentPicker(page).click()
  await page
    .locator('.ant-select-dropdown:visible')
    .getByText('文秘部', { exact: true })
    .click()
  await expect(owner).toBeEnabled()
  await oldResponse
  await owner.click()
  const menu = page.locator('.ant-select-dropdown:visible')
  await expect(
    menu
      .locator('.ant-select-item-option-content')
      .filter({ hasText: /^刘同学$/ }),
  ).toBeVisible()
  await expect(menu.getByText('王新宇', { exact: true })).toHaveCount(0)
})

async function chooseTimeScale(page: Page, label: string) {
  await page
    .locator('.task-plan-tools .ant-select')
    .filter({
      has: page.getByRole('combobox', { name: '时间尺度', exact: true }),
    })
    .click()
  await page
    .locator('.ant-select-dropdown:visible .ant-select-item-option-content')
    .filter({ hasText: new RegExp(`^${label}$`) })
    .click()
}

async function timeGeometry(page: Page) {
  return page.locator('.task-timeline-canvas').evaluate((canvas) => {
    const root = canvas as HTMLElement
    const bar = (id: number) =>
      root.querySelector(`[data-task-row="${id}"] .task-bar`) as HTMLElement
    const arrow =
      root.querySelector('.task-dependencies > path')?.getAttribute('d') || ''
    const coordinates = arrow.match(
      /^M([\d.-]+) [\d.-]+ H[\d.-]+ V[\d.-]+ H([\d.-]+)$/,
    )
    return {
      start: root.dataset.startDate!,
      end: root.dataset.endDate!,
      px: parseFloat(root.style.getPropertyValue('--task-day-width')),
      labelWidth: parseFloat(root.style.getPropertyValue('--task-label-width')),
      gridWidth: root
        .querySelector('.task-timeline-grid')!
        .getBoundingClientRect().width,
      rulerWidth: root
        .querySelector('.task-timeline-ruler')!
        .getBoundingClientRect().width,
      viewport: root.parentElement!.clientWidth,
      taskLeft: parseFloat(bar(4).style.left),
      taskWidth: bar(4).getBoundingClientRect().width,
      sourceRight:
        parseFloat(bar(2).style.left) + bar(2).getBoundingClientRect().width,
      targetLeft: parseFloat(bar(3).style.left),
      arrowStart: Number(coordinates?.[1]),
      arrowEnd: Number(coordinates?.[2]),
      todayLeft: parseFloat(
        (root.querySelector('.task-timeline-marker.today') as HTMLElement)
          ?.style.left,
      ),
    }
  })
}

test('Ctrl 滚轮连续缩放并保持鼠标日期，普通滚轮继续滚动，预设可恢复', async ({
  page,
}) => {
  const fixture = await setup(page)
  fixture.tasks().find((task) => task.id === 6)!.endDate = fixture.date(260)
  await page.goto('/activities/7?tab=gantt')
  const chart = page.locator('.task-timeline')
  await expect(page.locator('[data-task-row]')).toHaveCount(7)
  await chart.scrollIntoViewIfNeeded()
  await chart.evaluate((element) => {
    element.scrollLeft = 210
  })
  const initial = await timeGeometry(page)
  const box = (await chart.boundingBox())!
  const x = initial.labelWidth + 160
  const focus = () =>
    chart.evaluate((element, point) => {
      const canvas = element.querySelector(
        '.task-timeline-canvas',
      ) as HTMLElement
      const px = parseFloat(canvas.style.getPropertyValue('--task-day-width'))
      return { day: (element.scrollLeft + point) / px, top: element.scrollTop }
    }, x - initial.labelWidth)
  await page.mouse.move(box.x + x, box.y + 140)
  await page.mouse.wheel(0, 100)
  await expect.poll(async () => (await focus()).top).toBeGreaterThan(0)
  expect((await timeGeometry(page)).px).toBe(42)
  const before = await focus()
  await page.evaluate(() =>
    document.addEventListener(
      'wheel',
      (event) => {
        document.documentElement.dataset.ctrlWheelPrevented = String(
          event.defaultPrevented,
        )
      },
      { once: true },
    ),
  )
  await page.keyboard.down('Control')
  try {
    await page.mouse.wheel(0, -120)
    await expect
      .poll(async () => (await timeGeometry(page)).px)
      .toBeGreaterThan(42)
    expect((await focus()).day).toBeCloseTo(before.day, 1)
    expect((await focus()).top).toBe(before.top)
    await expect(page.locator('html')).toHaveAttribute(
      'data-ctrl-wheel-prevented',
      'true',
    )
    await expect(
      page.locator('.task-plan-tools .ant-select-selection-item'),
    ).toHaveText('自定义')
    const enlarged = await timeGeometry(page)
    expect(enlarged.arrowStart).toBeCloseTo(enlarged.sourceRight, 0)
    expect(enlarged.arrowEnd).toBeCloseTo(enlarged.targetLeft, 0)
    expect(enlarged.taskWidth).toBeCloseTo(4 * enlarged.px - 6, 0)
    await page.mouse.wheel(0, 120)
    await expect
      .poll(async () => (await timeGeometry(page)).px)
      .toBeCloseTo(42, 1)
    expect((await focus()).day).toBeCloseTo(before.day, 1)
  } finally {
    await page.keyboard.up('Control')
  }
  expect(
    await page.evaluate(() => ({
      width: innerWidth,
      scale: visualViewport?.scale,
    })),
  ).toEqual({ width: 1440, scale: 1 })
  await chooseTimeScale(page, '周视图')
  expect((await timeGeometry(page)).px).toBe(16)
  await page.getByRole('button', { name: '适合全部', exact: true }).click()
  const fit = await timeGeometry(page)
  expect(fit.labelWidth + fit.gridWidth).toBeCloseTo(fit.viewport, 0)
})

test('各时间视图支持 Ctrl 滚轮，缩放有边界且仅在甘特区域拦截', async ({
  page,
}) => {
  await setup(page)
  await page.goto('/activities/7?tab=gantt')
  const chart = page.locator('.task-timeline')
  await expect(page.locator('[data-task-row]')).toHaveCount(7)
  for (const label of ['日视图', '周视图', '月视图', '全览']) {
    await chooseTimeScale(page, label)
    const before = await timeGeometry(page)
    const prevented = await chart.evaluate((element) => {
      const event = new WheelEvent('wheel', {
        ctrlKey: true,
        deltaY: -3,
        deltaMode: 1,
        cancelable: true,
        bubbles: true,
      })
      element.dispatchEvent(event)
      return event.defaultPrevented
    })
    expect(prevented).toBe(true)
    await expect
      .poll(async () => (await timeGeometry(page)).px)
      .toBeGreaterThan(before.px)
    await expect(
      page.locator('.task-plan-tools .ant-select-selection-item'),
    ).toHaveText('自定义')
  }
  for (const [deltaY, expected] of [
    [-240, 126],
    [240, 0.5],
  ] as const) {
    for (let i = 0; i < 22; i++) {
      await chart.evaluate(
        (element, delta) =>
          element.dispatchEvent(
            new WheelEvent('wheel', {
              ctrlKey: true,
              deltaY: delta,
              cancelable: true,
              bubbles: true,
            }),
          ),
        deltaY,
      )
    }
    await expect.poll(async () => (await timeGeometry(page)).px).toBe(expected)
  }
  const scope = await page.evaluate(() => {
    const event = new WheelEvent('wheel', {
      ctrlKey: true,
      deltaY: -100,
      cancelable: true,
      bubbles: true,
    })
    document.querySelector('.task-plan-summary')!.dispatchEvent(event)
    return event.defaultPrevented
  })
  expect(scope).toBe(false)
  expect((await timeGeometry(page)).px).toBe(0.5)
})

test('短活动在日周月视图真正缩放，主轴、任务条、依赖和今日线同步', async ({
  page,
}) => {
  const fixture = await setup(page)
  await page.goto('/activities/7?tab=gantt')
  await expect(page.locator('[data-task-row]')).toHaveCount(7)
  const day = (value: string) => Date.parse(`${value}T00:00:00Z`) / 86400000
  const task = fixture.tasks().find((t) => t.id === 4)!
  for (const [label, unit, expected] of [
    ['日视图', 'day', 42],
    ['周视图', 'week', 16],
    ['月视图', 'month', 5],
  ] as const) {
    await chooseTimeScale(page, label)
    await expect(page.locator('.task-timeline-ruler')).toHaveAttribute(
      'data-unit',
      unit,
    )
    const geometry = await timeGeometry(page)
    expect(geometry.px).toBe(expected)
    expect(geometry.gridWidth).toBeCloseTo(geometry.rulerWidth, 0)
    const inset = Math.min(3, expected * 0.12)
    expect(geometry.taskLeft).toBeCloseTo(
      (day(task.startDate!) - day(geometry.start)) * expected + inset,
      0,
    )
    expect(geometry.taskWidth).toBeCloseTo(
      (day(task.endDate!) - day(task.startDate!) + 1) * expected - 2 * inset,
      0,
    )
    expect(geometry.arrowStart).toBeCloseTo(geometry.sourceRight, 0)
    expect(geometry.arrowEnd).toBeCloseTo(geometry.targetLeft, 0)
    expect(geometry.todayLeft - geometry.labelWidth).toBeCloseTo(
      (day(fixture.date(0)) - day(geometry.start)) * expected + expected / 2,
      0,
    )
    if (unit === 'week')
      await expect(
        page.locator('.task-timeline-date > span').first(),
      ).toHaveText(/\d+\/\d+ — \d+\/\d+/)
    if (unit === 'month')
      await expect(
        page.locator('.task-timeline-date > span').first(),
      ).toHaveText(/月/)
    if (unit !== 'day') await shot(page, `zoom-${unit}`)
  }
  const beforeMonthNavigation = await timeGeometry(page)
  await page.getByRole('button', { name: '后一月 ›', exact: true }).click()
  await expect
    .poll(async () => (await timeGeometry(page)).start)
    .toBe(
      dayjs(beforeMonthNavigation.start).add(1, 'month').format('YYYY-MM-DD'),
    )
  await chooseTimeScale(page, '全览')
  await expect
    .poll(async () => {
      const g = await timeGeometry(page)
      return Math.abs(g.labelWidth + g.gridWidth - g.viewport)
    })
    .toBeLessThan(2)
  const fit = await timeGeometry(page)
  expect(fit.start <= fixture.date(-8) && fit.end >= fixture.date(4)).toBe(true)
  await expect(
    page.getByRole('button', { name: '后一周 ›', exact: true }),
  ).toBeDisabled()
  await page.getByRole('button', { name: '定位今日', exact: true }).click()
  await expect(page.locator('.task-timeline-ruler')).toHaveAttribute(
    'data-unit',
    'day',
  )
  const focused = await timeGeometry(page)
  expect(
    focused.start <= fixture.date(0) && focused.end >= fixture.date(0),
  ).toBe(true)
})

test('长计划切换各视图不会截断日期，全览在桌面和手机上适配可用宽度', async ({
  page,
}) => {
  const fixture = await setup(page)
  fixture.tasks().find((task) => task.id === 6)!.endDate = fixture.date(260)
  await page.goto('/activities/7?tab=gantt')
  await expect(page.locator('[data-task-row]')).toHaveCount(7)
  for (const label of ['日视图', '周视图', '月视图', '全览']) {
    await chooseTimeScale(page, label)
    const geometry = await timeGeometry(page)
    expect(geometry.end >= fixture.date(260)).toBe(true)
  }
  await page.setViewportSize({ width: 390, height: 844 })
  await expect
    .poll(async () => {
      const g = await timeGeometry(page)
      return Math.abs(g.labelWidth + g.gridWidth - g.viewport)
    })
    .toBeLessThan(2)
  await expect
    .poll(() =>
      page.evaluate(() => ({
        width: innerWidth,
        scrollWidth: document.documentElement.scrollWidth,
      })),
    )
    .toEqual({ width: 390, scrollWidth: 390 })
  await chooseTimeScale(page, '月视图')
  await expect.poll(async () => (await timeGeometry(page)).px).toBe(5)
  await chooseTimeScale(page, '周视图')
  await expect.poll(async () => (await timeGeometry(page)).px).toBe(16)
})

for (const theme of ['light', 'dark'])
  test(`${theme} 桌面与手机布局`, async ({ page }) => {
    await setup(page, { theme })
    await page.goto('/activities/7?tab=gantt')
    await expect(page.locator('[data-task-row]')).toHaveCount(7)
    await shot(page, `desktop-${theme}`)
    await openTask(page, '核对参会名单')
    await shot(page, `drawer-${theme}`)
    await drawer(page)
      .locator('.task-drawer-footer')
      .getByRole('button', { name: '关闭', exact: true })
      .click()
    await page.setViewportSize({ width: 390, height: 844 })
    await page.reload()
    await expect(page.locator('.task-plan-list')).toBeVisible()
    await shot(page, `mobile-${theme}`)
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBe(true)
    await page
      .locator('.task-plan-tools .ant-segmented')
      .getByText('甘特图', { exact: true })
      .click()
    await expect(page.locator('.task-timeline')).toBeVisible()
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBe(true)
    await shot(page, `mobile-gantt-${theme}`)
  })
