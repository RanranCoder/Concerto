import { test, expect, type Page } from '@playwright/test'
import { mkdir, readFile } from 'node:fs/promises'
import path from 'node:path'

test.use({
  channel: process.platform === 'win32' ? 'msedge' : undefined,
  viewport: { width: 1440, height: 1000 },
})
test.setTimeout(90_000)
const output = path.resolve('../docs/design/ui-v6-activity-implementation')
test.beforeAll(async () => {
  await mkdir(output, { recursive: true })
})
async function setup(page: Page, theme = 'light', readOnly = false) {
  page.setDefaultTimeout(20_000)
  const permissions = readOnly
    ? ['activity:view', 'checkin:view']
    : [
        'activity:view',
        'activity:create',
        'activity:edit',
        'activity:review',
        'content:manage',
        'content:view',
        'checkin:view',
        'checkin:manage',
        'material:upload',
        'material:view',
        'seat:edit',
      ]
  const user = {
    id: 1,
    username: 'reviewer',
    realName: '林晓',
    roleCode: 'TEACHER',
    roleLevel: 5,
    permissions,
    businessPermissions: permissions,
    managementCapabilities: [],
    orgId: 1,
    orgName: '信息学院党建办公室',
    isOwner: !readOnly,
  }
  await page.addInitScript(
    ({ user, theme }) => {
      if (window !== window.top) return
      localStorage.setItem(
        'pams_token',
        JSON.stringify({ state: { token: 'activity-test', user }, version: 1 }),
      )
      localStorage.setItem(
        'pams_theme',
        JSON.stringify({ state: { mode: theme }, version: 0 }),
      )
    },
    { user, theme },
  )
  const activity = {
    id: 7,
    name: '十月主题党日活动',
    theme: '深学细悟，笃行实干',
    type: 'PARTY_DAY',
    status: 'PLAN_REVIEW',
    startDate: '2026-10-20',
    endDate: '2026-10-21',
    organizer: '组织部',
    location: '党员活动室',
    leader: '林同学',
    host: '周同学',
    targetAudience: '本院党员',
    description: '开展主题学习与小组交流。',
    taskCount: 4,
    completedTaskCount: 3,
    materialCount: 1,
  }
  let plan = {
    id: 11,
    ownerId: 1,
    revision: 0,
    reviewers: [] as Array<{
      userId: number
      name: string
      approved: boolean | null
      comment: string | null
    }>,
    activityId: 7,
    version: 3,
    status: 'DRAFT',
    background: '<p>结合学院工作安排开展本次活动。</p>',
    purpose: '<p>加强理论学习，落实组织分工。</p>',
    content: '<p>开展主题学习与交流。</p>',
    flow: '<p>签到、学习、小组讨论、总结。</p>',
    notice: '<p>提前核对设备和参会名单。</p>',
    emergency: '<p>设备故障时切换备用设备。</p>',
    budget:
      '<table><tr><td>项目</td><td>金额</td></tr><tr><td>资料印制</td><td>120元</td></tr></table>',
    sectionOrder: null,
    updatedAt: '2026-10-20T10:00:00',
    reviewComment: null,
  }
  const articles = [
    {
      id: 1,
      activityId: 7,
      title: '主题党日预热',
      articleType: 'PREHEAT',
      status: 'DRAFT',
      authorId: 1,
      deadline: '2026-10-22T18:00:00',
      summary: '一起学习与交流',
      content: '完整稿件正文，阅读时不能遗漏。',
      imageUrls: [],
      wxUrl: null,
      readCount: 0,
      likeCount: 0,
    },
  ]
  const materials = [
    {
      id: 1,
      name: '活动策划书.docx',
      bizType: 'PLAN',
      originFilename: '活动策划书.docx',
      fileId: 4,
      createdAt: '2026-10-20T10:00:00',
      activityId: 7,
    },
  ]
  const state = { failActivitySave: false, failPlanSave: false },
    calls: Array<{
      path: string
      method: string
      body: any
      query: URLSearchParams
    }> = [],
    errors: string[] = []
  page.on('pageerror', (error) => {
    errors.push(error.message)
    console.log('BROWSER ERROR:', error.message)
  })
  const workspace = () => {
    const visible = readOnly ? { ...plan, id: 10, version: 2 } : plan
    return {
      latest: visible,
      status: visible.status,
      currentHidden: readOnly,
      currentId: readOnly ? null : plan.id,
      canEdit: !readOnly && user.id === plan.ownerId,
      versions: [visible],
    }
  }
  await page.route('**/api/**', async (route) => {
    const req = route.request(),
      url = new URL(req.url()),
      pathname = url.pathname,
      method = req.method(),
      body =
        req.postData() && !req.headers()['content-type']?.includes('multipart')
          ? req.postDataJSON()
          : {}
    if (!pathname.startsWith('/api/')) return route.continue()
    calls.push({ path: pathname, method, body, query: url.searchParams })
    const ok = (data: unknown) => route.fulfill({ json: { code: 200, data } }),
      fail = () =>
        route.fulfill({
          status: 500,
          json: { code: 500, message: '保存失败，请重试' },
        })
    if (pathname === '/api/auth/me') return ok(user)
    if (pathname === '/api/orgs/current')
      return ok({
        id: 1,
        name: user.orgName,
        shortName: '党建办公室',
        type: '党建办公室',
        enabledPacks: '["party"]',
        themeJson: '{"primaryColor":"#1668DC"}',
      })
    if (pathname === '/api/depts') return ok([{ id: 1, name: '组织部' }])
    if (pathname === '/api/activities/organizers')
      return ok(['组织部', '文秘部'])
    if (pathname === '/api/dashboard')
      return ok({
        activityStats: {
          ASSIGNED: 0,
          PLANNING: 0,
          PLAN_REVIEW: 1,
          EXECUTING: 0,
          FINISHED: 0,
          ARCHIVED: 0,
        },
        recentArticles: [],
        recentMaterials: [],
        recentAnnouncements: [],
        myTasks: [],
      })
    if (pathname === '/api/activities' && method === 'GET')
      return ok({ records: [activity], total: 1, current: 1, size: 10 })
    if (pathname === '/api/activities/7' && method === 'GET')
      return ok(activity)
    if (pathname === '/api/activities/7' && method === 'PUT') {
      if (state.failActivitySave) return fail()
      Object.assign(activity, body)
      return ok(null)
    }
    if (pathname === '/api/activities/7/detail')
      return ok({
        activity,
        plan: workspace(),
        agendas: [
          {
            id: 1,
            stepNo: 1,
            title: '集中学习',
            remark: '负责人确认设备与材料。',
          },
        ],
        seatZones: {},
        score: { rules: [], records: [] },
        signinCount: 1,
        tasks: [
          {
            id: 1,
            name: '核对参会名单',
            progress: 75,
            startDate: '2026-10-19',
            endDate: '2026-10-20',
            assignee: '林同学',
            deptId: 1,
          },
        ],
      })
    if (pathname === '/api/plans/workspace') return ok(workspace())
    if (pathname === '/api/plans/reviewers')
      return ok([
        { id: 2, name: '审核甲', deptName: '组织部', roleName: '部长' },
        { id: 3, name: '审核乙', deptName: '宣传部', roleName: '部长' },
      ])
    if (
      (pathname === '/api/plans/11' || pathname === '/api/plans/10') &&
      method === 'GET'
    )
      return ok(workspace().latest)
    if (pathname === '/api/plans/11' && method === 'PUT') {
      if (state.failPlanSave) return fail()
      Object.assign(plan, body)
      plan.revision++
      return ok(null)
    }
    if (pathname === '/api/plans' && method === 'POST') {
      plan = { ...plan, ...body, id: 11, status: 'DRAFT' }
      return ok(plan)
    }
    if (pathname === '/api/plans/11/submit') {
      plan.status = 'PENDING'
      plan.reviewers = body.reviewerIds.map((id: number) => ({
        userId: id,
        name: id === 2 ? '审核甲' : '审核乙',
        approved: null,
        comment: null,
      }))
      return ok(null)
    }
    if (pathname === '/api/plans/11/review') {
      const own = plan.reviewers.find((r) => r.userId === user.id)!
      own.approved = body.approved
      own.comment = body.comment
      plan.status = !body.approved
        ? 'REJECTED'
        : plan.reviewers.every((r) => r.approved === true)
          ? 'APPROVED'
          : 'PENDING'
      plan.reviewComment = body.comment
      return ok(null)
    }
    if (pathname === '/api/activities/7/status') {
      activity.status = body.status
      return ok(null)
    }
    if (pathname === '/api/materials' && method === 'GET')
      return ok({ records: materials, total: materials.length })
    if (pathname === '/api/materials' && method === 'POST') {
      materials.push({ ...materials[0], ...body, id: 2 })
      return ok(materials[1])
    }
    if (pathname === '/api/files/upload')
      return ok({ id: 12, filename: '会务清单.txt', size: 10 })
    if (pathname === '/api/files/4/download')
      return route.fulfill({
        contentType:
          'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
        body: await readFile(
          path.resolve('../docs/design/ui-v6-activity/fixtures/a4-plan.docx'),
        ),
      })
    if (pathname === '/api/agendas')
      return ok([
        {
          id: 1,
          stepNo: 1,
          title: '集中学习',
          remark: '负责人确认设备与材料。',
        },
      ])
    if (pathname === '/api/seats') return ok({})
    if (pathname === '/api/seat-layouts/activity') return ok(null)
    if (pathname === '/api/scores')
      return ok({
        rules: [
          { id: 1, dimensionName: '内容质量', fullMarks: 100, sortOrder: 1 },
        ],
        records: [
          {
            id: 1,
            teamName: '第一小组',
            groupName: '计科2301',
            dimensionScores: '{"1":90}',
            total: 90,
          },
        ],
      })
    if (pathname === '/api/signins/groups/summary')
      return ok({ total: 2, signed: 1, unsigned: 1, groupCount: 0 })
    if (pathname === '/api/signins/count') return ok(1)
    if (pathname === '/api/signins')
      return ok([
        {
          id: 1,
          name: '陈同学',
          studentNo: '20231001',
          className: '计科2301',
          identityType: '党员',
          signType: 'SCAN',
          signTime: '2026-10-20T19:00:00',
        },
      ])
    if (pathname === '/api/articles' && method === 'GET')
      return ok({ records: articles, total: articles.length })
    if (pathname === '/api/articles/1' && method === 'PUT') {
      Object.assign(articles[0], body)
      return ok(null)
    }
    if (pathname === '/api/articles' && method === 'POST') {
      articles.push({ ...articles[0], ...body, id: 2 })
      return ok(2)
    }
    if (pathname === '/api/users')
      return ok({ records: [{ id: 1, realName: '林晓' }], total: 1 })
    if (method !== 'GET')
      throw Error('Unexpected mutation ' + method + ' ' + pathname)
    return ok([])
  })
  return { state, calls, errors, activity, user }
}
const planEditor = (page: Page) => page.locator('.activity-plan-inline-editor')
const drawer = (page: Page) =>
  page.locator('.ant-drawer-content:visible').last()
async function tab(page: Page, name: string) {
  await page.getByRole('tab', { name, exact: true }).click()
}
async function paper(page: Page) {
  await expect(
    page
      .frameLocator('iframe[title="白色 A4 策划书文档"]')
      .locator('section.activity-docx')
      .first(),
  ).toBeVisible({ timeout: 20000 })
  await expect(
    page.locator('.activity-word-main .ant-spin-spinning'),
  ).toHaveCount(0)
}

test('activity editing keeps values on failure and list filters/sorting use server parameters', async ({
  page,
}) => {
  const { state, calls, errors } = await setup(page)
  await page.goto('/activities')
  await expect(page.getByText('3 / 4', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '按活动日期排序' }).click()
  await expect
    .poll(() =>
      calls
        .filter((c) => c.path === '/api/activities')
        .at(-1)
        ?.query.get('dateOrder'),
    )
    .toBe('asc')
  await page.getByRole('combobox', { name: '组织单位' }).click()
  await page
    .locator('.ant-select-dropdown:visible .ant-select-item-option-content')
    .filter({ hasText: /^组织部$/ })
    .click()
  await expect
    .poll(() =>
      calls
        .filter((c) => c.path === '/api/activities')
        .at(-1)
        ?.query.get('organizer'),
    )
    .toBe('组织部')
  await page.goto('/activities/7')
  await page.getByRole('button', { name: '编辑活动', exact: true }).click()
  await drawer(page)
    .getByLabel('活动名称', { exact: true })
    .fill('编辑后的活动')
  state.failActivitySave = true
  const failedSave = page.waitForResponse(
    (response) =>
      response.url().endsWith('/api/activities/7') &&
      response.request().method() === 'PUT',
  )
  await drawer(page).getByRole('button', { name: '保存活动' }).click()
  expect((await failedSave).status()).toBe(500)
  await expect(
    drawer(page).getByRole('button', { name: '保存活动' }),
  ).toBeEnabled()
  await expect(
    drawer(page).getByLabel('活动名称', { exact: true }),
  ).toHaveValue('编辑后的活动')
  await page.screenshot({
    path: path.join(output, 'activity-editor-drawer.png'),
    animations: 'disabled',
  })
  state.failActivitySave = false
  await drawer(page).getByRole('button', { name: '保存活动' }).click()
  await expect(
    page.getByRole('heading', { name: '编辑后的活动', exact: true }),
  ).toBeVisible()
  expect(errors).toEqual([])
})

test('white fixed A4 preview, Word roundtrip, save retry, and real plan review', async ({
  page,
}) => {
  const { state, calls, errors, user } = await setup(page, 'dark')
  await page.goto('/activities/7?tab=plan')
  await paper(page)
  const geometry = await page
    .locator('iframe[title="白色 A4 策划书文档"]')
    .evaluate((f) => {
      const sheet = (f as HTMLIFrameElement).contentDocument!.querySelector(
        'section',
      )!
      return {
        width: sheet.getBoundingClientRect().width,
        height: sheet.getBoundingClientRect().height,
        color: getComputedStyle(sheet).backgroundColor,
      }
    })
  expect(geometry.color).toBe('rgb(255, 255, 255)')
  expect(geometry.width / geometry.height).toBeCloseTo(210 / 297, 3)
  const download = page.waitForEvent('download')
  await page.getByRole('button', { name: '下载 Word' }).click()
  const doc = await download
  expect(doc.suggestedFilename()).toContain('.docx')
  await page.locator('.activity-word-viewer input[type=file]').setInputFiles({
    name: doc.suggestedFilename(),
    mimeType:
      'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    buffer: await readFile((await doc.path())!),
  })
  await paper(page)
  await expect(
    page.getByText('本地试阅，文件未上传，也不会替换已保存的策划书。'),
  ).toBeVisible()
  await page.getByRole('button', { name: /编辑策划/ }).click()
  await expect(planEditor(page).locator('[data-slate-editor]')).toBeVisible()
  await page.screenshot({
    path: path.join(output, 'plan-editor-inline-dark.png'),
    animations: 'disabled',
  })
  state.failPlanSave = true
  const failedSave = page.waitForResponse(
    (response) =>
      response.url().includes('/api/plans/11') &&
      response.request().method() === 'PUT',
  )
  await planEditor(page).getByRole('button', { name: '保存策划书' }).click()
  expect((await failedSave).status()).toBe(500)
  await expect(
    planEditor(page).getByRole('button', { name: '保存策划书' }),
  ).toBeEnabled()
  await expect(planEditor(page)).toBeVisible()
  state.failPlanSave = false
  await planEditor(page).getByRole('button', { name: '保存策划书' }).click()
  await expect(planEditor(page)).toHaveCount(0)
  expect(
    calls.find((c) => c.path === '/api/plans/11' && c.method === 'PUT')?.body
      .budget,
  ).toContain('120元')
  await page.getByRole('button', { name: /提交审核$/ }).click()
  await page.getByRole('checkbox', { name: /审核甲/ }).check()
  await page.getByRole('button', { name: '提交给 1 位审核人' }).click()
  await expect
    .poll(
      () =>
        calls.find((c) => c.path === '/api/plans/11/submit')?.body.reviewerIds,
    )
    .toEqual([2])
  user.id = 2
  await page.reload()
  await page.getByRole('button', { name: '审核策划书', exact: true }).click()
  await page
    .locator('.plan-inspector')
    .getByRole('textbox')
    .fill('内容完整，审核通过')
  await page
    .locator('.plan-inspector')
    .getByRole('button', { name: '通过审核', exact: true })
    .click()
  await expect
    .poll(
      () =>
        calls.filter((c) => c.path === '/api/plans/11/review').at(-1)?.body
          .approved,
    )
    .toBe(true)
  expect(errors).toEqual([])
})

test('article reader and editor are contextual drawers and preserve the complete manuscript', async ({
  page,
}) => {
  const { calls, errors } = await setup(page)
  await page.goto('/activities/7?tab=articles')
  await page.getByRole('button', { name: /查看稿件/ }).click()
  await expect(
    drawer(page).getByText('完整稿件正文，阅读时不能遗漏。'),
  ).toBeVisible()
  await page.screenshot({
    path: path.join(output, 'article-reader-drawer.png'),
    animations: 'disabled',
  })
  await drawer(page)
    .getByRole('button', { name: '编辑稿件', exact: true })
    .click()
  await drawer(page).getByLabel('标题', { exact: true }).fill('修订后的预热稿')
  await drawer(page)
    .getByRole('button', { name: /^取\s*消$/ })
    .click()
  await expect(page.locator('.ant-modal-confirm-title:visible')).toHaveText(
    '放弃未保存的稿件修改？',
  )
  await page.getByRole('button', { name: '继续编辑', exact: true }).click()
  await drawer(page)
    .getByRole('button', { name: /^保\s*存$/ })
    .click()
  await expect
    .poll(
      () =>
        calls.find((c) => c.path === '/api/articles/1' && c.method === 'PUT')
          ?.body.content,
    )
    .toContain('完整稿件正文')
  await page.getByRole('button', { name: /新建推文/ }).click()
  await expect(drawer(page).getByLabel('标题', { exact: true })).toBeVisible()
  expect(errors).toEqual([])
})

test('Word headings, tables, images, explicit breaks and long paragraphs remain complete across A4 sheets', async ({
  page,
}) => {
  const { errors } = await setup(page)
  await page.goto('/activities/7?tab=plan')
  await paper(page)
  await page
    .locator('.activity-word-viewer input[type=file]')
    .setInputFiles(
      path.resolve('../docs/design/ui-v6-activity/fixtures/a4-plan.docx'),
    )
  await expect(
    page
      .locator('.activity-word-toolbar')
      .getByText('a4-plan.docx', { exact: true }),
  ).toBeVisible()
  await paper(page)
  const document = page.frameLocator('iframe[title="白色 A4 策划书文档"]')
  await expect
    .poll(() => document.locator('section.activity-docx').count())
    .toBeGreaterThan(2)
  expect(
    (await document.locator('section > article').allTextContents()).join(''),
  ).toContain('工作人员提前到场，检查音响与投影，核对签到名单。'.repeat(180))
  await expect(document.getByText('文末校验：全部内容显示完成。')).toBeVisible()
  await expect(document.locator('table')).toHaveCount(1)
  await expect(document.locator('img')).toHaveCount(1)
  const dimensions = await document
    .locator('section.activity-docx')
    .evaluateAll((nodes) =>
      nodes.map((node) => ({
        width: node.clientWidth,
        height: node.clientHeight,
        overflow: node.scrollHeight - node.clientHeight,
      })),
    )
  for (const sheet of dimensions) {
    expect(sheet.width / sheet.height).toBeCloseTo(210 / 297, 2)
    expect(sheet.overflow).toBeLessThanOrEqual(2)
  }
  await page.getByRole('combobox', { name: '文档缩放' }).selectOption('75')
  await expect(page.locator('.activity-word-status')).toContainText('75%')
  expect(await document.locator('section.activity-docx').count()).toBe(
    dimensions.length,
  )
  await page.screenshot({
    path: path.join(output, 'word-long-document.png'),
    fullPage: true,
    animations: 'disabled',
  })
  expect(errors).toEqual([])
})

test('read-only users can inspect all tabs without mutation controls', async ({
  page,
}) => {
  const { calls, errors } = await setup(page, 'light', true)
  await page.goto('/activities/7?tab=plan')
  await paper(page)
  await expect(
    page.getByRole('button', { name: '编辑活动', exact: true }),
  ).toHaveCount(0)
  await expect(
    page.getByRole('button', {
      name: /编辑策划|提交审核|审核策划书|导入 Word/,
    }),
  ).toHaveCount(0)
  await tab(page, '材料 1')
  await expect(page.getByRole('button', { name: /上传材料/ })).toHaveCount(0)
  await tab(page, '评分')
  await expect(
    page.getByRole('button', { name: /新增评分|新增规则/ }),
  ).toHaveCount(0)
  await tab(page, '签到')
  await expect(page.getByRole('button', { name: /补录|手动添加/ })).toHaveCount(
    0,
  )
  await expect(page.locator('.anticon-delete')).toHaveCount(0)
  await tab(page, '推文')
  await page.getByRole('button', { name: /查看稿件/ }).click()
  await expect(
    drawer(page).getByText('完整稿件正文，阅读时不能遗漏。'),
  ).toBeVisible()
  expect(calls.filter((call) => call.method !== 'GET')).toEqual([])
  expect(errors).toEqual([])
})

test('materials upload associates the actual file and activity', async ({
  page,
}) => {
  const { calls, errors } = await setup(page)
  await page.goto('/activities/7?tab=materials')
  await page.getByRole('button', { name: /上传材料/ }).click()
  await drawer(page)
    .locator('input[type=file]')
    .setInputFiles({
      name: '会务清单.txt',
      mimeType: 'text/plain',
      buffer: Buffer.from('设备与名单'),
    })
  await drawer(page).getByLabel('材料说明').fill('现场准备核对表')
  await drawer(page).getByRole('button', { name: '上传并关联' }).click()
  await expect
    .poll(
      () =>
        calls.find(
          (call) => call.path === '/api/materials' && call.method === 'POST',
        )?.body,
    )
    .toMatchObject({ activityId: 7, fileId: 12, description: '现场准备核对表' })
  await expect(
    page.getByRole('button', { name: '会务清单.txt', exact: true }),
  ).toBeVisible()
  expect(errors).toEqual([])
})

test('Word import stays an unsaved draft until explicitly saved', async ({
  page,
}) => {
  const { calls, errors } = await setup(page)
  await page.goto('/activities/7?tab=plan')
  await paper(page)
  await page
    .locator('.activity-plan-surface > input[type=file]')
    .setInputFiles(
      path.resolve('../docs/design/ui-v6-activity/fixtures/a4-plan.docx'),
    )
  await expect(planEditor(page).locator('[data-slate-editor]')).toContainText(
    '本文件用于验证 Word 文档',
  )
  expect(calls.filter((call) => call.method !== 'GET')).toEqual([])
  await planEditor(page).getByRole('button', { name: '保存策划书' }).click()
  await expect
    .poll(
      () =>
        calls.find(
          (call) => call.path === '/api/plans/11' && call.method === 'PUT',
        )?.body.background,
    )
    .toContain('本文件用于验证 Word 文档')
  expect(errors).toEqual([])
})

test('phase rollback is confirmed and finished activities expose read-only basic information', async ({
  page,
}) => {
  const { activity, calls, errors } = await setup(page)
  await page.goto('/activities/7')
  await page.getByRole('button', { name: '回退阶段', exact: true }).click()
  await expect(page.getByText('回退到「排期中」？')).toBeVisible()
  expect(calls.filter((call) => call.path.endsWith('/status'))).toHaveLength(0)
  await page
    .locator('.ant-popconfirm:visible')
    .getByRole('button', { name: /^确\s*定$/ })
    .click()
  await expect
    .poll(
      () => calls.find((call) => call.path.endsWith('/status'))?.body.status,
    )
    .toBe('PLANNING')
  activity.status = 'FINISHED'
  await page.goto('/activities/7')
  await expect(
    page.getByRole('heading', { name: activity.name, exact: true }),
  ).toBeVisible()
  await expect(
    page.getByRole('button', { name: '回退阶段', exact: true }),
  ).toHaveCount(0)
  await expect(
    page.getByRole('button', { name: '编辑活动', exact: true }),
  ).toHaveCount(0)
  await expect(
    page.getByRole('button', { name: '推进至已归档', exact: true }),
  ).toBeVisible()
  await page.goto('/activities/7/edit')
  await expect(
    drawer(page).getByText('活动已结束或归档，基本信息仅供查看。'),
  ).toBeVisible()
  await expect(
    drawer(page).getByRole('button', { name: '保存活动' }),
  ).toBeDisabled()
  expect(errors).toEqual([])
})

test('all eight activity pages render on desktop/mobile in both themes without whole-page overflow', async ({
  page,
}) => {
  await mkdir(output, { recursive: true })
  const { errors } = await setup(page)
  for (const theme of ['light', 'dark'])
    for (const mobile of [false, true]) {
      await page.setViewportSize(
        mobile ? { width: 390, height: 844 } : { width: 1440, height: 1000 },
      )
      await page.goto('/activities')
      if (theme === 'dark')
        await page.getByRole('button', { name: '切换深色' }).click()
      await expect(
        page.getByText('十月主题党日活动', { exact: true }).first(),
      ).toBeVisible()
      await page.screenshot({
        path: path.join(
          output,
          `list-${theme}-${mobile ? 'mobile' : 'desktop'}.png`,
        ),
        fullPage: true,
        animations: 'disabled',
      })
      await page.goto('/activities/7')
      if (theme === 'dark')
        await page.getByRole('button', { name: '切换深色' }).click()
      for (const [key, name] of [
        ['basic', '基本信息'],
        ['materials', '材料 1'],
        ['plan', '策划书'],
        ['agenda', '议程'],
        ['seat', '座位表'],
        ['score', '评分'],
        ['signin', '签到'],
        ['articles', '推文'],
      ]) {
        await tab(page, name)
        if (key === 'plan') await paper(page)
        await expect
          .poll(() =>
            page.evaluate(
              () => document.documentElement.scrollWidth <= innerWidth,
            ),
          )
          .toBe(true)
        await page.screenshot({
          path: path.join(
            output,
            `${key}-${theme}-${mobile ? 'mobile' : 'desktop'}.png`,
          ),
          fullPage: true,
          animations: 'disabled',
        })
      }
    }
  expect(errors).toEqual([])
})
