import { test, expect, type Page } from '@playwright/test'
import { mkdirSync } from 'node:fs'
import { resolve } from 'node:path'

test.use({
  channel: process.platform === 'win32' ? 'msedge' : undefined,
  viewport: { width: 1600, height: 1100 },
  timezoneId: 'Asia/Shanghai',
})
const output = resolve('..', 'docs', 'ui-v6-routine-implementation')
const manager = {
  id: 1,
  username: 'director',
  realName: '林同学',
  roleCode: 'DIRECTOR',
  roleLevel: 1,
  deptId: 1,
  deptName: '文秘部',
  orgId: 1,
  orgName: '党建办公室',
  permissions: [
    'activity:view',
    'schedule:view',
    'schedule:manage',
    'schedule:generate',
    'schedule:free_table',
    'attendance:view',
    'attendance:manage',
    'checkin:view',
    'checkin:manage',
  ],
}
const member = {
  ...manager,
  username: 'staff',
  roleCode: 'STAFF',
  permissions: [
    'activity:view',
    'schedule:view',
    'schedule:free_table',
    'checkin:view',
  ],
}

async function setup(page: Page, viewer = manager) {
  await page.clock.setFixedTime(new Date('2026-10-14T10:00:00+08:00'))
  await page.addInitScript((user) => {
    localStorage.setItem(
      'pams_token',
      JSON.stringify({
        state: { token: 'routine-ui-contract-test', user },
        version: 1,
      }),
    )
    localStorage.setItem(
      'pams_theme',
      JSON.stringify({ state: { mode: 'light' }, version: 0 }),
    )
  }, viewer)
  const errors: string[] = [],
    writes: Array<{ path: string; body: any; url: URL }> = [],
    reads: string[] = []
  page.on('pageerror', (e) => errors.push(e.message))
  const people = [
    { id: 1, realName: '林同学', deptId: 1, deptName: '文秘部' },
    { id: 2, realName: '陈同学', deptId: 2, deptName: '组织部' },
    { id: 3, realName: '周同学', deptId: 2, deptName: '组织部' },
  ]
  const activities = [
    {
      id: 1,
      name: '第四十期入党积极分子培训班',
      type: 'PARTY_LESSON',
      status: 'EXECUTING',
      organizer: '组织部',
      location: '学术报告厅',
    },
    {
      id: 2,
      name: '十月主题党日',
      type: 'PARTY_DAY',
      status: 'EXECUTING',
      organizer: '党建办公室',
      location: '党员活动室',
    },
  ]
  const configs = ['08:00', '10:00', '14:00', '16:00', '19:00'].map((t, i) => ({
    id: i + 1,
    period: i + 1,
    label: `第${i * 2 + 1}-${i * 2 + 2}节`,
    startTime: t + ':00',
    endTime: ['09:40', '11:40', '15:40', '17:40', '20:40'][i] + ':00',
  }))
  let nextId = 50,
    matrix: Record<string, Record<string, string>> = {
      '1': { '1': '高等数学' },
      '3': { '2': '程序设计' },
    }
  const persons = [
    { id: 1, userId: 1, personName: '林同学', isPrimary: 1 },
    { id: 2, userId: 2, personName: '陈同学', isPrimary: 0 },
  ]
  const schedules: any[] = [
    ...[
      'CLASS_DUTY',
      'SMOKING_CURB',
      'ARCHIVE',
      'STAMP',
      'CLASS_CHECK',
      'BOOTH',
    ].map((t, i) => ({
      id: i + 1,
      scheduleType: t,
      scheduleDate: `2026-10-${[12, 12, 13, 14, 15, 16][i]}`,
      weekNo: 7,
      weekday: [1, 1, 2, 3, 4, 5][i],
      sessionName: configs[i % 4].label,
      location: ['党建办公室 302', '教学楼 A 区', '档案室 301'][i % 3],
      persons,
      notes: '核对值班事项',
    })),
    {
      id: 8,
      scheduleType: 'CLASS_DUTY',
      scheduleDate: null,
      weekNo: 6,
      weekday: 6,
      sessionName: '上午',
      location: '历史未标日期班次',
      persons,
    },
    {
      id: 21,
      scheduleType: 'ACTIVITY',
      activityId: 1,
      scheduleDate: '2026-10-15',
      startTime: '14:00:00',
      endTime: '15:40:00',
      status: 'DRAFT',
      source: 'AUTO',
      location: '学术报告厅',
      persons,
    },
    {
      id: 22,
      scheduleType: 'ACTIVITY',
      activityId: 1,
      scheduleDate: '2026-10-14',
      startTime: '19:00:00',
      endTime: '20:40:00',
      status: 'PUBLISHED',
      source: 'MANUAL',
      location: '学术报告厅',
      persons,
    },
    {
      id: 23,
      scheduleType: 'ACTIVITY',
      activityId: 2,
      scheduleDate: '2026-10-17',
      startTime: '09:00:00',
      endTime: '10:30:00',
      status: 'DRAFT',
      source: 'MANUAL',
      location: '党员活动室',
      persons,
    },
  ]
  const attendance: any[] = [
    'NORMAL',
    'LATE',
    'ABSENT',
    'LEAVE',
    'MAKEUP',
    'EARLY_LEAVE',
    'PENDING',
    'CANCELED',
  ].map((status, i) => ({
    id: 100 + i,
    scheduleId: i === 6 ? 22 : 20,
    personId: i === 0 || i === 6 ? 1 : 2,
    personName: i === 0 || i === 6 ? '林同学' : '陈同学',
    studentNo: i === 0 || i === 6 ? '20240001' : '20240002',
    activityId: i === 6 ? 2 : 1,
    activityName: activities[i === 6 ? 1 : 0].name,
    activityType: i === 6 ? 'PARTY_DAY' : 'PARTY_LESSON',
    activityDate: i === 6 ? '2026-10-14' : '2026-10-13',
    activityStart: '14:00:00',
    activityEnd: '15:40:00',
    checkedInAt: ['NORMAL', 'LATE', 'MAKEUP'].includes(status)
      ? '2026-10-13T14:05:00'
      : null,
    status,
    statusSource: i >= 3 && i <= 5 ? 'MANUAL' : 'AUTO',
    signCount: ['NORMAL', 'LATE', 'MAKEUP'].includes(status) ? 1 : 0,
    remark: null,
  }))
  const audits: Record<string, any[]> = {}
  const grid = configs.slice(0, 4).map((p) => ({
    period: p.period,
    label: p.label,
    halfDay: p.period < 3 ? '上午' : '下午',
    days: Object.fromEntries(
      [1, 2, 3, 4, 5].map((d) => [
        String(d),
        [
          { name: '林同学', freeWeeks: '1-20' },
          { name: '陈同学', freeWeeks: '1-19 单' },
          { name: '周同学', freeWeeks: '2-20 双' },
        ],
      ]),
    ),
  }))
  const filters = (r: any, url: URL) => {
    const q = url.searchParams
    return (
      (!q.get('activityId') || r.activityId === Number(q.get('activityId'))) &&
      (!q.get('date') || r.activityDate === q.get('date')) &&
      (!q.get('keyword') ||
        `${r.personName} ${r.studentNo}`.includes(q.get('keyword')!)) &&
      (!q.get('status') || r.status === q.get('status'))
    )
  }
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      const req = route.request(),
        url = new URL(req.url()),
        path = url.pathname,
        method = req.method()
      const body = req.postData()?.startsWith('{') ? req.postDataJSON() : null
      if (method === 'GET') reads.push(path)
      else writes.push({ path, body, url })
      let data: any = []
      if (path === '/api/auth/me') data = viewer
      else if (path === '/api/orgs/current')
        data = {
          id: 1,
          name: '党建办公室',
          themeJson: '{"primaryColor":"#1668DC"}',
          enabledPacks: null,
          terminologyJson: null,
        }
      else if (path === '/api/notifications/unread-count') data = 0
      else if (path === '/api/depts')
        data = [
          { id: 1, name: '文秘部' },
          { id: 2, name: '组织部' },
          { id: 3, name: '青年科技部' },
        ]
      else if (path === '/api/users/options') data = people
      else if (path === '/api/activities')
        data = { records: activities, total: activities.length }
      else if (path === '/api/course-schedules/configs') data = configs
      else if (path === '/api/course-schedules/mine') {
        if (method === 'POST') {
          matrix = {}
          body.cells.forEach((c: any) => {
            matrix[String(c.dayOfWeek)] ??= {}
            matrix[String(c.dayOfWeek)][String(c.period)] = c.courseName ?? ''
          })
        }
        data = { semester: url.searchParams.get('semester'), matrix, count: 2 }
      } else if (path === '/api/course-schedules/analyze')
        data = {
          semester: '2026-2027-1',
          periods: configs,
          users: people,
          heatmap: Object.fromEntries(
            [1, 2, 3, 4, 5, 6, 7].map((d) => [
              String(d),
              Object.fromEntries(
                configs.map((p) => [p.period, (d + p.period) % 2 ? 3 : 2]),
              ),
            ]),
          ),
          optimal: [
            {
              dayOfWeek: 1,
              period: 2,
              label: '第3-4节',
              freeCount: 3,
              allFree: true,
            },
          ],
        }
      else if (path === '/api/activity-schedules/semesters')
        data = ['2026-2027-1']
      else if (path === '/api/course-schedules/generated/latest')
        data = { deptId: 2, deptName: '组织部', semester: '2026-2027-1' }
      else if (path === '/api/course-schedules/generated')
        data =
          url.searchParams.get('deptId') === '3'
            ? null
            : {
                deptName:
                  url.searchParams.get('deptId') === '1' ? '文秘部' : '组织部',
                semester: '2026-2027-1',
                rows: grid,
                createdAt: '2026-10-12T18:30:00',
              }
      else if (path === '/api/course-schedules/import')
        data = {
          deptName: '文秘部',
          semester: '2026-2027-1',
          rows: grid,
          totalFiles: 2,
          successCount: 1,
          failed: [
            {
              fileName: '未命名课表.xlsx',
              reason: '姓名未识别，请修改文件名后重新导入',
            },
          ],
          warnings: ['周次已按原课表计算'],
          markdown: '# 无课表',
          downloadUrl: '无课表/文秘部/result.xlsx',
        }
      else if (
        path === '/api/course-schedules/import/download' ||
        path === '/api/schedules/export'
      ) {
        await route.fulfill({
          body: 'download-contract',
          contentType:
            'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
        })
        return
      } else if (path === '/api/schedules/my')
        data = schedules.filter((s) =>
          s.persons.some((p: any) => p.userId === 1),
        )
      else if (path === '/api/schedules' && method === 'GET')
        data = schedules.filter(
          (s) =>
            (!url.searchParams.get('type') ||
              s.scheduleType === url.searchParams.get('type')) &&
            (!url.searchParams.get('weekNo') ||
              s.weekNo === Number(url.searchParams.get('weekNo'))),
        )
      else if (/\/schedules\/\d+$/.test(path) && method === 'PUT') {
        Object.assign(
          schedules.find((s) => s.id === Number(path.split('/').at(-1))),
          body,
        )
        data = null
      } else if (path === '/api/schedules' && method === 'POST') {
        schedules.push({ ...body, id: nextId++ })
        data = nextId - 1
      } else if (path === '/api/activity-schedules' && method === 'GET')
        data = schedules.filter(
          (s) =>
            s.scheduleType === 'ACTIVITY' &&
            s.activityId === Number(url.searchParams.get('activityId')),
        )
      else if (path === '/api/activity-schedules/generate') {
        const s = {
          id: nextId++,
          scheduleType: 'ACTIVITY',
          activityId: body.activityId,
          scheduleDate: body.startDate,
          startTime: '14:00:00',
          endTime: '15:40:00',
          status: 'DRAFT',
          source: 'AUTO',
          persons,
        }
        schedules.push(s)
        data = [
          {
            date: body.startDate,
            count: 2,
            persons: people
              .slice(0, 2)
              .map((p) => ({ name: p.realName, userId: p.id })),
            slots: [
              {
                period: 3,
                label: '第5-6节',
                startTime: '14:00:00',
                endTime: '15:40:00',
                count: 2,
                persons: people
                  .slice(0, 2)
                  .map((p) => ({ name: p.realName, userId: p.id })),
              },
            ],
          },
        ]
      } else if (path === '/api/activity-schedules' && method === 'POST') {
        schedules.push({
          ...body,
          id: nextId++,
          scheduleType: 'ACTIVITY',
          status: 'DRAFT',
          source: 'MANUAL',
        })
        data = nextId - 1
      } else if (/\/activity-schedules\/\d+$/.test(path) && method === 'PUT') {
        Object.assign(
          schedules.find((s) => s.id === Number(path.split('/').at(-1))),
          body,
        )
        data = null
      } else if (
        path.endsWith('/publish-all') ||
        /\/activity-schedules\/\d+\/publish$/.test(path)
      ) {
        const id = Number(url.searchParams.get('activityId')),
          sid = Number(path.split('/').at(-2))
        let count = 0
        schedules
          .filter(
            (s) =>
              s.scheduleType === 'ACTIVITY' &&
              s.status === 'DRAFT' &&
              (id ? s.activityId === id : s.id === sid),
          )
          .forEach((s) => {
            s.status = 'PUBLISHED'
            count++
            s.persons.forEach((p: any) =>
              attendance.push({
                ...attendance[0],
                id: nextId++,
                scheduleId: s.id,
                personId: p.userId,
                personName: p.personName,
                activityId: s.activityId,
                activityName: activities.find((a) => a.id === s.activityId)!
                  .name,
                activityDate: s.scheduleDate,
                status: 'PENDING',
                statusSource: 'AUTO',
                checkedInAt: null,
                signCount: 0,
              }),
            )
          })
        data = count
      } else if (/\/activity-schedules\/\d+\/cancel$/.test(path)) {
        const id = Number(path.split('/').at(-2))
        schedules.find((s) => s.id === id).status = 'CANCELLED'
        attendance
          .filter((r) => r.scheduleId === id && r.statusSource === 'AUTO')
          .forEach((r) => (r.status = 'CANCELED'))
        data = null
      } else if (/\/activity-schedules\/\d+\/re-generate$/.test(path)) data = 2
      else if (path === '/api/attendances/my')
        data = attendance.filter((r) => r.personId === viewer.id)
      else if (path === '/api/attendances')
        data = attendance.filter((r) => filters(r, url))
      else if (/\/attendances\/\d+\/audits$/.test(path))
        data = audits[path.split('/').at(-2)!] ?? []
      else if (/\/attendances\/\d+\/(status|makeup)$/.test(path)) {
        const id = Number(path.split('/').at(-2)),
          r = attendance.find((a) => a.id === id),
          status = path.endsWith('/makeup') ? 'MAKEUP' : body.newStatus,
          remark = body?.remark ?? url.searchParams.get('remark')
        audits[id] ??= []
        audits[id].push({
          id: nextId++,
          attendanceId: id,
          oldStatus: r.status,
          newStatus: status,
          operatorName: '林同学',
          operateTime: '2026-10-14T10:00:00',
          remark,
        })
        Object.assign(r, { status, remark, statusSource: 'MANUAL' })
        data = r
      } else if (path === '/api/signins/fields')
        data = [
          {
            id: 1,
            fieldName: '联系电话',
            fieldKey: 'phone',
            fieldType: 'PHONE',
            required: 0,
          },
        ]
      else if (path === '/api/signins/token')
        data = {
          token: 'isolated-qr-token',
          activityId: body.activityId,
          expiresAt: '2026-10-14T10:30:00',
          qrContent: 'unused',
        }
      else if (path === '/api/signins')
        data = [
          {
            id: 1,
            name: '林同学',
            studentNo: '20240001',
            signTime: '2026-10-14T09:55:00',
            fieldsJson: '{"联系电话":"13800000000"}',
          },
        ]
      else if (method !== 'GET' && !path.endsWith('/close')) {
        await route.fulfill({
          status: 400,
          json: { code: 400, message: `Unmocked mutation: ${path}` },
        })
        return
      }
      await route.fulfill({ json: { code: 200, data } })
    },
  )
  mkdirSync(output, { recursive: true })
  return { errors, writes, reads, schedules, attendance, matrix }
}

async function selectOption(page: Page, label: string, value: string) {
  const control = page.getByRole('combobox', { name: label, exact: true })
  await control.focus()
  await control.press('ArrowDown')
  await page
    .locator('.ant-select-dropdown:visible')
    .getByText(value, { exact: true })
    .click()
}
async function screenshot(page: Page, name: string) {
  await page.screenshot({
    path: resolve(output, name + '.png'),
    fullPage: !(await page
      .locator('.ant-drawer-open,.ant-modal:visible')
      .count()),
    animations: 'disabled',
    style: '.ant-tooltip,.ant-message {visibility:hidden!important}',
  })
}

test('六页布局、明暗主题、手机滚动和未标日期班次可访问', async ({ page }) => {
  const state = await setup(page)
  const pages = [
    ['schedules', '排班'],
    ['my-schedules', '我的排班'],
    ['activity-schedules?activityId=1', '活动排班'],
    ['attendance', '考勤中心'],
    ['course-schedule', '无课表制作'],
    ['free-schedules', '无课表'],
  ]
  for (const [path, title] of pages) {
    await page.goto('/routine/' + path)
    await expect(
      page.getByRole('heading', { name: title, exact: true }),
    ).toBeVisible()
    await expect(page.locator('.routine-module-nav a')).toHaveCount(6)
    await screenshot(page, path.split('?')[0] + '-light')
  }
  await page.goto('/routine/my-schedules')
  await page
    .locator('.ant-segmented')
    .getByText('全部安排', { exact: true })
    .click()
  await expect(
    page.getByText('历史未标日期班次', { exact: true }),
  ).toBeVisible()
  await page.goto('/routine/schedules')
  await page.getByRole('button', { name: '切换深色' }).click()
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')
  await screenshot(page, 'schedules-dark')
  await page.setViewportSize({ width: 390, height: 844 })
  await expect
    .poll(() =>
      page.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
    )
    .toBe(true)
  await screenshot(page, 'schedules-mobile-dark')
  await page.goto('/routine/course-schedule')
  await expect(page.getByRole('heading', { name: '无课表制作' })).toBeVisible()
  await expect
    .poll(() =>
      page.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
    )
    .toBe(true)
  await screenshot(page, 'course-mobile-dark')
  expect(state.errors).toEqual([])
  expect(state.writes).toHaveLength(0)
})

test('例行排班编辑保留人员关联及主副班，导出使用真实下载接口', async ({
  page,
}) => {
  const state = await setup(page)
  await page.goto('/routine/schedules')
  await page
    .locator('.routine-shift')
    .filter({ hasText: '办公室值班' })
    .first()
    .click()
  await page.getByRole('button', { name: '编辑班次', exact: true }).click()
  const drawer = page.locator('.ant-drawer-open')
  await expect(page.locator('.ant-drawer-content-wrapper:visible')).toHaveCount(1)
  await expect(drawer.locator('input#location')).toHaveValue('党建办公室 302')
  await drawer.locator('input#location').fill('党建办公室 305')
  const colleague = drawer.getByRole('combobox', { name: '值班人员姓名', exact: true }).nth(1)
  await colleague.fill('周')
  await colleague.press('ArrowDown')
  await colleague.press('Enter')
  await expect(colleague).toHaveValue('周同学')
  await drawer.getByRole('button', { name: '保存排班', exact: true }).click()
  await expect
    .poll(
      () =>
        state.writes.find((w) => w.path === '/api/schedules/1')?.body.location,
    )
    .toBe('党建办公室 305')
  expect(
    state.writes
      .find((w) => w.path === '/api/schedules/1')!
      .body.persons.map((p: any) => [p.userId, p.isPrimary]),
  ).toEqual([
    [1, 1],
    [3, 0],
  ])
  await expect(page.locator('.ant-drawer-open')).toHaveCount(0)
  await page.getByLabel('查找排班').fill('不匹配')
  await expect(page.locator('.routine-shift')).toHaveCount(0)
  await page.getByRole('button', { name: '重置', exact: true }).click()
  const download = page.waitForEvent('download')
  await page.getByRole('button', { name: '导出值班表' }).click()
  expect((await download).suggestedFilename()).toContain('.xlsx')
  expect(state.errors).toEqual([])
})

test('发布范围限于当前活动，考勤联动与修正审计保留原因', async ({ page }) => {
  const state = await setup(page)
  await page.goto('/routine/activity-schedules?activityId=1')
  await page.getByRole('button', { name: '发布草稿（1）', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '发布活动排班' })
  await expect(dialog).toContainText('将建立 2 条待签到记录')
  await dialog.getByRole('button', { name: '确认发布', exact: true }).click()
  await expect
    .poll(() =>
      state.writes
        .find((w) => w.path.endsWith('/publish-all'))
        ?.url.searchParams.get('activityId'),
    )
    .toBe('1')
  expect(state.schedules.find((s) => s.id === 23).status).toBe('DRAFT')
  await page.getByRole('button', { name: '查看考勤', exact: true }).click()
  await expect(page).toHaveURL(/attendance\?activityId=1/)
  const row = page
    .locator('.ant-table-tbody tr')
    .filter({ hasText: '缺勤' })
    .first()
  await row.getByRole('button', { name: '修正', exact: true }).click()
  let drawer = page.locator('.ant-drawer-open')
  await drawer.getByRole('button', { name: '保存修正', exact: true }).click()
  await expect(drawer.getByText('请填写原因', { exact: true })).toBeVisible()
  expect(state.writes.filter((w) => w.path.includes('/status'))).toHaveLength(0)
  await selectOption(page, '修正后状态', '请假')
  await page.getByLabel('修改原因', { exact: true }).fill('负责人核实因病请假')
  await drawer.getByRole('button', { name: '保存修正', exact: true }).click()
  await expect
    .poll(
      () =>
        state.writes.find((w) => w.path === '/api/attendances/102/status')?.body
          .remark,
    )
    .toBe('负责人核实因病请假')
  const corrected = page
    .locator('.ant-table-tbody tr')
    .filter({ hasText: '请假' })
    .first()
  await corrected.getByRole('button', { name: '详情', exact: true }).click()
  drawer = page.locator('.ant-drawer-open')
  await expect(drawer.locator('.routine-audit-row')).toContainText(
    '负责人核实因病请假',
  )
  await screenshot(page, 'attendance-audit')
  await drawer.getByRole('button', { name: '关闭', exact: true }).click()
  await page.goto('/routine/activity-schedules?activityId=1')
  await page.getByRole('tab', { name: '签到现场' }).click()
  await expect(
    page.getByRole('columnheader', { name: '联系电话', exact: true }),
  ).toBeVisible()
  expect(
    state.writes.filter((w) => w.path === '/api/signins/token'),
  ).toHaveLength(0)
  await page.getByRole('button', { name: /开启签到$/ }).click()
  await expect(page.locator('.routine-qr-box svg')).toBeVisible()
  await screenshot(page, 'signin-scene')
  expect(
    state.writes.find((w) => w.path === '/api/signins/token')?.body.activityId,
  ).toBe(1)
  expect(state.errors).toEqual([])
})

test('课程编辑、共同空闲分析、导入失败反馈与无课周次筛选', async ({ page }) => {
  const state = await setup(page)
  await page.goto('/routine/course-schedule?tab=edit')
  await expect(
    page.getByRole('button', { name: '高等数学 点击编辑课程' }),
  ).toBeVisible()
  await page
    .locator('.routine-course-cell')
    .filter({ hasText: '无课' })
    .first()
    .click()
  const dialog = page.getByRole('dialog', { name: '添加课程时段' })
  await dialog.getByLabel('课程名称（可留空）').fill('原型落地验收课程')
  await dialog.getByRole('button', { name: '保存时段' }).click()
  await expect(page.getByText('有未保存修改', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '保存课程表', exact: true }).click()
  await expect
    .poll(() =>
      state.writes
        .find((w) => w.path === '/api/course-schedules/mine')
        ?.body.cells.some((c: any) => c.courseName === '原型落地验收课程'),
    )
    .toBe(true)
  await page.getByRole('tab', { name: '共同空闲分析' }).click()
  await page.getByRole('button', { name: '分析共同空闲', exact: true }).click()
  await expect(page.locator('.routine-heat-cell')).toHaveCount(35)
  await screenshot(page, 'course-analysis')
  await page.getByRole('tab', { name: '批量导入', exact: true }).click()
  await page.locator('input[type=file]').setInputFiles([
    {
      name: '陈同学_课表.xlsx',
      mimeType:
        'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      buffer: Buffer.from('isolated-test-file'),
    },
    {
      name: '未命名课表.xlsx',
      mimeType:
        'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      buffer: Buffer.from('isolated-test-file'),
    },
  ])
  await page.getByRole('button', { name: '生成无课表', exact: true }).click()
  await page
    .getByRole('dialog', { name: '确认生成部门无课表' })
    .getByRole('button', { name: '确认生成', exact: true })
    .click()
  await expect(
    page.getByText('生成结果 · 文秘部', { exact: true }),
  ).toBeVisible()
  await expect(
    page.locator('.routine-import-file').filter({ hasText: '未命名' }),
  ).toContainText('导入失败')
  await screenshot(page, 'course-import-result')
  await page.getByRole('button', { name: '查看无课表', exact: true }).click()
  await expect(page).toHaveURL(/free-schedules\?deptId=1&semester=2026-2027-1/)
  await selectOption(page, '无课表周次', '第 8 周')
  await expect(
    page.locator('.routine-free-person').filter({ hasText: '陈同学' }),
  ).toHaveCount(0)
  await expect(
    page.locator('.routine-free-person').filter({ hasText: '周同学' }),
  ).toHaveCount(20)
  await selectOption(page, '无课表部门', '青年科技部')
  await expect(
    page.getByText('这个部门尚未生成本学期无课表', { exact: true }),
  ).toBeVisible()
  expect(state.errors).toEqual([])
})

test('成员从我的接口读取考勤，管理操作按权限码隐藏', async ({ page }) => {
  const state = await setup(page, member)
  await page.goto('/routine/schedules')
  await expect(
    page.getByRole('button', { name: '新建排班', exact: true }),
  ).toHaveCount(0)
  await expect(
    page.getByRole('button', { name: '导出值班表', exact: true }),
  ).toHaveCount(0)
  await page.goto('/routine/activity-schedules?activityId=1')
  await expect(
    page.getByRole('button', { name: '生成排班', exact: true }),
  ).toHaveCount(0)
  await expect(page.getByRole('button', { name: /发布草稿/ })).toHaveCount(0)
  await page.goto('/routine/attendance')
  await expect(
    page.getByRole('heading', { name: '我的考勤', exact: true }),
  ).toBeVisible()
  await expect(page.locator('.ant-table-row')).toHaveCount(2)
  expect(state.reads).toContain('/api/attendances/my')
  expect(state.reads).not.toContain('/api/attendances')
  await expect(
    page.getByRole('button', { name: '修正', exact: true }),
  ).toHaveCount(0)
  await page.goto('/routine/course-schedule')
  await expect(
    page.getByRole('tab', { name: '批量导入', exact: true }),
  ).toHaveCount(0)
  expect(state.writes).toHaveLength(0)
  expect(state.errors).toEqual([])
})

test('生成草稿及重新生成将部门、学期、开学日期传给后端，取消保留人工考勤', async ({
  page,
}) => {
  const state = await setup(page)
  await page.goto('/routine/activity-schedules?activityId=1')
  await page.locator('.page-header').getByRole('button', { name: /生成排班$/ }).click()
  const dialog = page.getByRole('dialog', { name: '从无课表生成活动排班' })
  const pickerInputs = dialog.locator('.ant-picker-input input')
  await pickerInputs.nth(0).fill('2026-10-19')
  await pickerInputs.nth(0).press('Tab')
  await pickerInputs.nth(1).fill('2026-10-19')
  await pickerInputs.nth(1).press('Enter')
  await pickerInputs.nth(2).fill('14:00')
  await pickerInputs.nth(2).press('Tab')
  await pickerInputs.nth(3).fill('15:40')
  await pickerInputs.nth(3).press('Enter')
  await expect(pickerInputs.nth(3)).toHaveValue('15:40')
  await pickerInputs.nth(4).fill('2026-08-31')
  await pickerInputs.nth(4).press('Enter')
  const dept = dialog.locator('#deptIds')
  await dept.focus()
  await dept.press('ArrowDown')
  await page
    .locator('.ant-select-dropdown:visible')
    .getByText('组织部', { exact: true })
    .click()
  await dept.press('Escape')
  await dialog.locator('#peoplePerSlot').fill('2')
  await screenshot(page, 'activity-generate')
  await dialog.getByRole('button', { name: '生成草稿', exact: true }).click()
  await expect(page.getByRole('dialog', { name: '排班生成结果' })).toBeVisible()
  const generated = state.writes.find(
    (w) => w.path === '/api/activity-schedules/generate',
  )!.body
  expect(generated).toMatchObject({
    activityId: 1,
    deptIds: [2],
    semester: '2026-2027-1',
    semesterStartDate: '2026-08-31',
    peoplePerSlot: 2,
    startDate: '2026-10-19',
  })
  await page.getByRole('button', { name: '查看草稿', exact: true }).click()
  await page
    .locator('.ant-table-row')
    .filter({ hasText: '2026-10-19' })
    .getByRole('button', { name: '查看', exact: true })
    .click()
  await page
    .getByRole('button', { name: '按无课表重新生成', exact: true })
    .click()
  const regen = page.getByRole('dialog', { name: '重新生成班次人员' })
  await regen.locator('#semesterStartDate').fill('2026-08-31')
  await regen.locator('#semesterStartDate').press('Enter')
  await regen.getByRole('button', { name: '确认重新生成', exact: true }).click()
  await expect
    .poll(() =>
      state.writes
        .find((w) => w.path.endsWith('/re-generate'))
        ?.url.searchParams.get('semesterStartDate'),
    )
    .toBe('2026-08-31')
  const manual = state.attendance.find((r) => r.id === 103)
  manual.scheduleId = 22
  await page.getByRole('combobox', { name: '班次状态', exact: true }).focus()
  await page
    .getByRole('combobox', { name: '班次状态', exact: true })
    .press('ArrowDown')
  await page
    .locator('.ant-select-dropdown:visible')
    .getByText('已发布', { exact: true })
    .click()
  await page
    .locator('.ant-table-row')
    .filter({ hasText: '2026-10-14' })
    .getByRole('button', { name: '取消', exact: true })
    .click()
  await page
    .getByRole('dialog', { name: '取消已发布班次' })
    .getByRole('button', { name: '确认取消班次', exact: true })
    .click()
  await expect
    .poll(() => state.attendance.find((r) => r.id === 106).status)
    .toBe('CANCELED')
  expect(manual.status).toBe('LEAVE')
  expect(state.errors).toEqual([])
})
