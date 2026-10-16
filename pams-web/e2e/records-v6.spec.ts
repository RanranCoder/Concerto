import { test, expect, type Page } from '@playwright/test'
import { mkdirSync } from 'node:fs'
import { resolve } from 'node:path'

test.use({ channel: process.platform === 'win32' ? 'msedge' : undefined, viewport: { width: 1440, height: 1000 } })
test.setTimeout(30_000)
const output = resolve('..', 'docs', 'ui-v6-records-implementation')
const permissions = ['member:view', 'member:manage', 'member:export', 'member:import_account', 'material:view', 'material:upload', 'material:delete', 'template:view', 'template:manage', 'quality:view', 'quality:add', 'notice:view', 'notice:publish', 'notice:manage', 'chat:manage', 'chat:category', 'activity:view', 'user:view', 'content:view', 'content:manage', 'party:view', 'party:manage']
const teacher = { id: 1, username: 'teacher', realName: '林老师', roleCode: 'TEACHER', roleLevel: 5, deptName: '党建办公室', permissions, managementCapabilities: ['user:manage'] }
async function setup(page: Page, viewer = teacher) {
  await page.addInitScript(user => {
    localStorage.setItem('pams_token', JSON.stringify({ state: { token: 'records-ui-test', user }, version: 1 }))
    localStorage.setItem('pams_theme', JSON.stringify({ state: { mode: 'light' }, version: 0 }))
  }, viewer)
  const members = [
    { id: 1, sessionId: 1, sessionName: '2026—2027届', name: '林晓', deptId: 1, deptName: '文秘部', position: 'DEPT_HEAD', positionLabel: '部长', studentNo: '2024240101', className: '软件工程2401', gender: '女', phone: '13800000000', politicalStatus: '共青团员', status: 'ACTIVE', statusLabel: '在职', remark: '', createdAt: '2026-09-01' },
    { id: 2, sessionId: 1, sessionName: '2026—2027届', name: '陈禾', deptId: 2, deptName: '组织部', position: 'STAFF', positionLabel: '干事', studentNo: '2024240208', className: '计算机2402', gender: '男', phone: '13900000000', politicalStatus: '入党积极分子', status: 'ACTIVE', statusLabel: '在职', remark: '', createdAt: '2026-09-01' },
  ]
  let pending = [members[1]]
  const party = { id: 1, name: '陈禾', studentNo: '2024240208', className: '计算机2402', branchName: '学生第一党支部', college: '信息与智能工程学院', politicalStatus: '入党积极分子', gender: '男', nation: '汉族', phone: '13900000000', idCard: '440000000000000001', homeAddress: '示例地址', education: '本科在读' }
  const stages = [{ id: 1, memberId: 1, stage: 'ACTIVE', issueNo: '40', status: 'CURRENT', startDate: '2026-03-15', remark: '完成培养登记' }]
  const articles = [{ id: 1, title: '青春向党，学思践悟启新程', summary: '培训班专题学习报道', content: '学院党建办公室组织开展专题学习。\n各部门共同完成会务准备、现场记录和材料整理。', articleType: 'REPORT', status: 'PENDING', authorId: 1, activityId: 1, activityName: '第四十期培训班', deadline: '2026-10-20', imageUrls: [], readCount: 0, likeCount: 0, reviewComment: '' }]
  const materials = [{ id: 1, name: '培训班策划书', bizType: 'PLAN', activityId: 1, deptId: 1, uploaderId: 1, fileId: 7, originFilename: '培训班策划书.docx', tag: '活动材料', description: '培训班活动工作安排', createdAt: '2026-10-13T09:00:00' }]
  const roster = [{ id: 1, rosterType: 'RECOMMEND', issueNo: '40', name: '陈禾', studentNo: '2024240208', className: '计算机2402', branchName: '学生第一党支部' }]
  const writeRequests: { path: string; body: Record<string, unknown> }[] = []
  await page.route(url => url.pathname.startsWith('/api/'), async route => {
    const req = route.request(), url = new URL(req.url()), path = url.pathname, method = req.method()
    let data: unknown = []
    const body = () => req.postDataJSON() as Record<string, unknown>
    if (method !== 'GET') writeRequests.push({ path, body: req.postData()?.startsWith('{') ? body() : {} })
    if (path === '/api/auth/me') data = viewer
    else if (path === '/api/orgs/current') data = { id: 1, name: '党建办公室', enabledPacks: null, themeJson: null }
    else if (path === '/api/notifications/unread-count') data = 0
    else if (path === '/api/depts') data = [{ id: 1, name: '文秘部' }, { id: 2, name: '组织部' }]
    else if (path === '/api/member-sessions') data = [{ id: 1, name: '2026—2027届', isCurrent: 1 }]
    else if (path === '/api/members/stats') data = { total: members.length, byDept: [{ name: '文秘部', count: 1 }, { name: '组织部', count: 1 }], byPosition: [{ name: '部长', count: 1 }, { name: '干事', count: 1 }], byStatus: [{ name: '在职', count: members.length }] }
    else if (path === '/api/members/unregistered') data = pending
    else if (path === '/api/members/import') data = { total: 2, success: 1, skipped: 1, failed: [] }
    else if (path === '/api/members/import-accounts') { pending = []; data = { created: 1, skipped: 0 } }
    else if (/\/members\/\d+$/.test(path) && !path.includes('/party/')) {
      const m = members.find(m => m.id === Number(path.split('/').pop()))!
      if (method === 'PUT') Object.assign(m, body())
      data = { member: m, scheduleCount: 12, attendanceCount: 11, totalCredit: 4, credits: [{ id: 1, project: '培训班会务', credit: 2, basis: '工作记录', createdAt: '2026-10-13' }] }
    } else if (path === '/api/members') {
      if (method === 'POST') { members.push({ ...members[0], ...body(), id: 3 }); data = 3 }
      else { const filtered = members.filter(m => (!url.searchParams.get('deptId') || String(m.deptId) === url.searchParams.get('deptId')) && (!url.searchParams.get('keyword') || m.name.includes(url.searchParams.get('keyword')!))); data = { records: filtered, total: filtered.length } }
    } else if (path === '/api/users') data = { records: [teacher], total: 1 }
    else if (path === '/api/activities') data = { records: [{ id: 1, name: '第四十期培训班', status: 'EXECUTING' }], total: 1 }
    else if (path === '/api/articles') { const records = articles.filter(a => !url.searchParams.get('status') || a.status === url.searchParams.get('status')); data = { records, total: records.length } }
    else if (path === '/api/articles/1/review') { articles[0].status = body().approved ? 'APPROVED' : 'REJECTED'; articles[0].reviewComment = String(body().comment ?? ''); data = null }
    else if (path === '/api/articles/1/publish') { articles[0].status = 'PUBLISHED'; data = null }
    else if (path === '/api/news') data = { records: [{ id: 1, title: '学院举办培训班', subtitle: '专题学习', content: '新闻稿正文', status: 'DRAFT', authorId: 1, activityId: 1 }], total: 1 }
    else if (path === '/api/materials/1' && method === 'PUT') { Object.assign(materials[0], body()); data = null }
    else if (path === '/api/materials') { const records = materials.filter(m => (!url.searchParams.get('bizType') || m.bizType === url.searchParams.get('bizType')) && (!url.searchParams.get('keyword') || m.name.includes(url.searchParams.get('keyword')!))); data = { records, total: records.length } }
    else if (path === '/api/templates') data = [{ id: 1, name: '活动策划书标准模板', category: 'PLAN', description: '活动前准备工作', fileId: 7, originFilename: '策划模板.docx', createdBy: 1, createdAt: '2026-10-13' }]
    else if (path === '/api/party/members/stages') data = stages
    else if (path === '/api/party/members/1/stage') { party.politicalStatus = '重点发展对象'; stages.unshift({ id: 2, memberId: 1, stage: String(body().stage), status: 'CURRENT', startDate: String(body().startDate), issueNo: '40', remark: String(body().remark) }); data = null }
    else if (path === '/api/party/members/1') data = party
    else if (path === '/api/party/members') { const records = !url.searchParams.get('stage') || url.searchParams.get('stage') === party.politicalStatus ? [party] : []; data = { records, total: records.length } }
    else if (path === '/api/party/rosters') data = roster
    else if (path === '/api/credits') data = { records: [], total: 0 }
    await route.fulfill({ json: { code: 200, data } })
  })
  mkdirSync(output, { recursive: true })
  return { writeRequests }
}
async function shot(page: Page, name: string) { await page.screenshot({ path: resolve(output, name + '.png'), fullPage: true, animations: 'disabled' }) }

test('成员目录、详情编辑与账号管理入口', async ({ page }) => {
  const { writeRequests } = await setup(page)
  await page.goto('/members')
  await expect(page.locator('.records-summary')).toContainText('2')
  await expect(page.locator('.ant-table-row')).toHaveCount(2)
  await page.locator('.records-directory').getByRole('button', { name: /文秘部/ }).click()
  await expect(page.locator('.ant-table-row')).toHaveCount(1)
  await page.getByRole('button', { name: '重置', exact: true }).click()
  await page.getByText('紧凑', { exact: true }).click()
  await expect(page.locator('.ant-table-small')).toBeVisible()
  await shot(page, 'members-light')
  await page.getByRole('button', { name: '详情', exact: true }).first().click()
  await expect(page.locator('.records-two')).toContainText('基本资料')
  await expect(page.locator('.records-summary')).toContainText('4.00')
  await shot(page, 'member-detail')
  await page.getByRole('button', { name: '编辑资料', exact: true }).click()
  await expect(page.getByRole('dialog').getByLabel('姓名', { exact: true })).toHaveValue('林晓')
  await page.getByRole('dialog').getByLabel('备注', { exact: true }).fill('已核对本届资料')
  await page.getByRole('dialog').getByRole('button', { name: /保\s*存$/ }).click()
  await expect(page.getByRole('dialog')).toHaveCount(0)
  expect(writeRequests.find(r => r.path === '/api/members/1')?.body.remark).toBe('已核对本届资料')
  await page.goto('/members')
  await page.getByRole('tab', { name: '账号关联' }).click()
  await expect(page.getByRole('heading', { name: '成员登录账号' })).toBeVisible()
  await expect(page.getByRole('link', { name: '前往用户管理' })).toHaveAttribute('href', '/admin/users')
})

test('成员导入展示真实接口的成功与跳过结果', async ({ page }) => {
  await setup(page); await page.goto('/members')
  await expect(page.locator('.records-summary')).toContainText('2')
  await page.locator('#member-import').setInputFiles({ name: '成员导入.xlsx', mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', buffer: Buffer.from('isolated API fixture') })
  await expect(page.getByRole('dialog')).toContainText('成功 1')
  await expect(page.getByRole('dialog')).toContainText('跳过 1')
})

test('推文文档审阅、驳回校验与发布登记', async ({ page }) => {
  const { writeRequests } = await setup(page)
  await page.goto('/content/articles')
  await expect(page.locator('.records-flow')).toContainText('先审核')
  await page.getByRole('button', { name: /审核$/ }).click()
  await expect(page.locator('.records-paper')).toContainText('青春向党')
  await page.getByRole('button', { name: /驳\s*回$/ }).click()
  await expect(page.getByText('请填写驳回原因', { exact: true })).toBeVisible()
  expect(writeRequests.some(r => r.path.endsWith('/review'))).toBeFalsy()
  await page.getByLabel('审核意见', { exact: true }).fill('活动信息核对完成')
  await shot(page, 'article-review')
  await page.getByRole('button', { name: '通过（待发布）' }).click()
  await expect(page.locator('.ant-table-row')).toContainText('待发布')
  await page.getByRole('button', { name: /标记发布$/ }).click()
  await page.getByLabel('公众号链接').fill('https://mp.weixin.qq.com/s/records-ui')
  await page.getByRole('button', { name: '确认发布', exact: true }).click()
  await expect(page.locator('.ant-table-row')).toContainText('已发布')
  expect(writeRequests.find(r => r.path.endsWith('/publish'))?.body.wxUrl).toContain('https://mp.weixin.qq.com/')
  await page.locator('.records-nav').getByRole('link', { name: '新闻稿' }).click()
  await expect(page.locator('.records-flow')).toContainText('新闻稿独立维护')
  await page.getByRole('button', { name: /编辑$/ }).click()
  await expect(page.getByLabel('关联活动')).toBeVisible()
  await page.getByRole('button', { name: /取\s*消/ }).click()
})

test('材料分类与元数据编辑、模板工作面和档案导航', async ({ page }) => {
  const { writeRequests } = await setup(page)
  await page.goto('/archive/materials')
  await expect(page.locator('.ant-table-row')).toContainText('培训班策划书')
  await page.locator('.records-directory').getByRole('button', { name: '照片', exact: true }).click()
  await expect(page.getByText('没有匹配的材料，可调整筛选或上传材料')).toBeVisible()
  await page.getByRole('button', { name: '重置', exact: true }).click()
  await page.getByRole('button', { name: /编辑$/ }).click()
  await expect(page.getByLabel('材料名称')).toHaveValue('培训班策划书')
  await page.getByLabel('标签', { exact: true }).fill('最终材料')
  await page.getByRole('button', { name: /保\s*存/, exact: true }).click()
  await expect(page.locator('.ant-table-row')).toContainText('最终材料')
  expect(writeRequests.find(r => r.path === '/api/materials/1')?.body.fileId).toBe(7)
  await shot(page, 'materials-light')
  await page.locator('.records-nav').getByRole('link', { name: '模板库' }).click()
  await expect(page.locator('.records-template')).toContainText('活动策划书标准模板')
  await shot(page, 'templates-light')
  for (const [name, path] of [['素拓加分','credits'],['通知公告','announcements'],['群聊管理','group-chats']]) {
    await page.goto('/archive/' + path)
    await expect(page.locator('.records-nav')).toBeVisible()
    await expect(page.locator('.page-header-title')).toHaveText(name)
  }
})

test('党务阶段流转、专项记录与名册导航', async ({ page }) => {
  const { writeRequests } = await setup(page)
  await page.goto('/party/members')
  await expect(page.locator('.records-stage-filters')).toBeVisible()
  await page.locator('.records-stage-filters').getByRole('button', { name: /正式党员/ }).click()
  await expect(page.locator('.ant-table-row')).toHaveCount(0)
  await page.locator('.records-stage-filters').getByRole('button', { name: /入党积极分子/ }).click()
  await expect(page.locator('.ant-table-row')).toHaveCount(1)
  await page.getByRole('button', { name: /详情$/ }).click()
  await expect(page.locator('.records-stage-strip .current')).toContainText('入党积极分子')
  await shot(page, 'party-detail')
  for (const name of ['函调','登记','转移']) { await page.getByRole('tab', { name, exact: true }).click(); await expect(page.getByRole('button', { name: new RegExp('新增' + name + '$') })).toBeVisible() }
  await page.getByRole('button', { name: '记录阶段流转' }).click()
  await expect(page.getByRole('dialog').locator('.ant-select-selection-item')).toHaveText('重点发展对象')
  await page.getByLabel('流转说明').fill('已完成阶段登记')
  await page.getByRole('button', { name: '确认记录', exact: true }).click()
  await expect(page.locator('.records-stage-strip .current')).toContainText('重点发展对象')
  expect(writeRequests.find(r => r.path.endsWith('/stage'))?.body.stage).toBe('DEVELOPMENT')
  await page.goto('/party/rosters'); await expect(page.locator('.ant-table-row')).toContainText('陈禾'); await shot(page,'party-rosters')
})

test('四个模块明暗主题及手机宽度，无脚本异常与整页横向溢出', async ({ page }) => {
  const errors: string[] = []; page.on('pageerror', e => errors.push(e.message))
  await setup(page)
  for (const path of ['/members','/content/articles','/archive/materials','/party/members','/party/members/1','/archive/templates']) {
    await page.setViewportSize({ width: 1440, height: 1000 }); await page.goto(path); await expect(page.locator('.records-page')).toBeVisible()
    await page.getByRole('button', { name: '切换深色', exact: true }).click()
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')
    await shot(page,path.replaceAll('/','-')+'-dark')
    for (const width of [1024,390]) { await page.setViewportSize({ width, height: 844 }); await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy() }
    await shot(page,path.replaceAll('/','-')+'-mobile')
  }
  expect(errors).toEqual([])
})

test('仅查看成员的角色不出现账号创建、删除或状态修改', async ({ page }) => {
  await setup(page, { ...teacher, roleCode: 'STAFF', roleLevel: 1, permissions: ['member:view', 'material:view'] })
  await page.goto('/members')
  await expect(page.getByRole('tab', { name: '账号关联' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: /新增成员$/ })).toHaveCount(0)
  await expect(page.getByRole('button', { name: /删除$/ })).toHaveCount(0)
  await page.goto('/members/1')
  await expect(page.getByLabel('修改成员状态')).toHaveCount(0)
  await page.goto('/archive/materials')
  await expect(page.getByRole('button', { name: '上传材料' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: /编辑$/ })).toHaveCount(0)
})
