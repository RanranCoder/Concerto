import { expect, test, type Page } from '@playwright/test'
import { mkdir } from 'node:fs/promises'
import path from 'node:path'

test.use({channel: process.platform === 'win32' ? 'msedge' : undefined, viewport: {width: 1440, height: 1000}})
test.setTimeout(40_000)
const output = path.resolve('../docs/design/ui-v6-admin-implementation')
async function setup(page: Page, options: {readOnly?: boolean; theme?: 'light' | 'dark'; failSystem?: boolean} = {}) {
  const full = ['user:view', 'user:manage', 'role:assign', 'role:view', 'role:manage', 'role:grant', 'org:manage', 'org:structure:manage', 'kb:read', 'kb:manage', 'member:import_account', 'member:view']
  const caps = options.readOnly ? ['user:view'] : full
  const user = {id: 1, username: 'admin', realName: '林晓', roleCode: 'TEACHER', roleLevel: 5, deptId: 1, deptName: '文秘部', orgId: 1, orgName: '信息与智能工程学院党建办公室', roleId: 1, isOwner: !options.readOnly, authzVersion: 1, permissions: [...caps, 'kb:read', 'org:manage'], businessPermissions: ['kb:read'], managementCapabilities: caps}
  let people = [
    {id: 1, realName: '林晓', username: 'admin', deptId: 1, deptName: '文秘部', roleId: 1, roleName: '总负责人', status: 1, managementSource: 'OWNER', studentNo: '20240001', phone: '13800000001'},
    {id: 2, realName: '陈禾', username: 'manager', deptId: 2, deptName: '组织部', roleId: 2, roleName: '组织部长', status: 1, managementSource: 'DELEGATED', studentNo: '20240002', phone: ''},
    {id: 3, realName: '周宁', username: 'staff', deptId: 2, deptName: '组织部', roleId: 3, roleName: '干事', status: 1, managementSource: 'NONE', studentNo: '20240003', phone: ''},
    {id: 4, realName: '许安', username: 'pending', deptId: 1, deptName: '文秘部', roleId: 3, roleName: '干事', status: 0, managementSource: 'NONE', pendingActivation: true, studentNo: '20260004', phone: ''},
    {id: 5, realName: '宋雨', username: 'disabled', deptId: 1, deptName: '文秘部', roleId: 3, roleName: '干事', status: 0, managementSource: 'NONE', studentNo: '20260005', phone: ''},
  ]
  const roles = [{id: 1, code: 'DIRECTOR', name: '总负责人', status: 'ACTIVE'}, {id: 2, code: 'LEADER', name: '组织部长', status: 'ACTIVE'}, {id: 3, code: 'STAFF', name: '干事', status: 'ACTIVE'}]
  const sources = [{sourceType: 'manual', label: '制度与录入文档', description: '经审核的制度、指引与常见问答，正文参与检索。', tier: 'T1', visibility: 'ALL', documentCount: 1, chunkCount: 2}, {sourceType: 'activity_plan', label: '活动策划书', description: '来自已审核的活动策划正文，沿用活动访问权限。', tier: 'T1', visibility: 'ALL', documentCount: 1, chunkCount: 1}]
  const entries = [{id: 11, sourceType: 'manual', sourceId: 11, sourceKey: 'manual:11', title: '学生组织活动材料归档指引', tier: 'T1', chunkCount: 2, visibility: 'ALL', indexedAt: '2026-10-19T09:30:00', sourceLabel: '制度与录入文档', path: '第一章 归档要求', snippet: '活动结束后统一整理材料。', link: null}, {id: 12, sourceType: 'activity_plan', sourceId: 12, sourceKey: 'activity_plan:12', title: '秋季积极分子培训方案', tier: 'T1', chunkCount: 1, visibility: 'ALL', indexedAt: '2026-10-18T15:30:00', sourceLabel: '活动策划书', path: '组织分工', snippet: '组织部负责培训安排。', link: '/activities/20'}]
  const state = {failDocument: false, failProfileOnce: false, partialStatus: false, failList: false, failSystem: !!options.failSystem, assigned: false}
  const calls: {method: string; path: string; body: any; query: URLSearchParams}[] = []
  const browserErrors: string[] = []
  page.on('pageerror', error => {browserErrors.push(error.message); console.log('BROWSER ERROR:', error.message)})
  await page.addInitScript(({user, theme}) => {
    localStorage.setItem('pams_token', JSON.stringify({state: {token: 'admin-v6-test', user}, version: 1}))
    localStorage.setItem('pams_theme', JSON.stringify({state: {mode: theme || 'light'}, version: 0}))
    localStorage.removeItem('concerto_preferences')
  }, {user, theme: options.theme})
  await page.route('**/api/**', async route => {
    const req = route.request(), url = new URL(req.url()), pathname = url.pathname, method = req.method(), q = url.searchParams
    if (!pathname.startsWith('/api/')) return route.continue()
    const body = req.postData() ? req.postDataJSON() : {}
    calls.push({method, path: pathname, body, query: q})
    const ok = (data: unknown) => route.fulfill({json: {code: 200, data}})
    const fail = (message: string) => route.fulfill({status: 500, json: {code: 500, message}})
    const paged = <T,>(list: T[]) => {const page = Number(q.get('page') || 1), size = Number(q.get('size') || 10); return ok({records: list.slice((page - 1) * size, page * size), total: list.length, current: page, size})}
    if (pathname === '/api/auth/me') return ok(user)
    if (pathname === '/api/orgs/current') return ok({id: 1, name: user.orgName, shortName: '党建办公室', type: '党建办公室', themeJson: '{"primaryColor":"#1668DC"}', enabledPacks: '["party"]', terminologyJson: null})
    if (pathname === '/api/system/info') return state.failSystem ? fail('系统信息读取失败') : ok({version: '1.0.0', uploadDir: '/app/uploads', ping: 'pong'})
    if (pathname === '/api/depts') return ok([{id: 1, name: '文秘部'}, {id: 2, name: '组织部'}])
    if (pathname === '/api/users/summary') return ok({total: people.length, enabled: people.filter(p => p.status === 1).length, pendingActivation: people.filter(p => p.pendingActivation).length, disabled: people.filter(p => !p.pendingActivation && p.status === 0).length, ownerCount: 1, administratorCount: 1, roles})
    if (pathname === '/api/users' && method === 'GET') {
      if (state.failList) return fail('账号暂时无法读取')
      return paged(people.filter(p => (!q.get('keyword') || `${p.realName} ${p.username} ${p.studentNo}`.includes(q.get('keyword')!)) && (!q.get('deptId') || p.deptId === Number(q.get('deptId'))) && (!q.get('roleId') || p.roleId === Number(q.get('roleId'))) && (!q.get('managementSource') || p.managementSource === q.get('managementSource')) && (!q.get('accountStatus') || (q.get('accountStatus') === 'PENDING' ? p.pendingActivation : q.get('accountStatus') === 'ENABLED' ? p.status === 1 : p.status === 0 && !p.pendingActivation))))
    }
    if (pathname === '/api/users' && method === 'POST') {people.unshift({...body, id: 6, deptName: body.deptId === 2 ? '组织部' : '文秘部', roleName: roles.find(r => r.id === body.roleId)?.name || '干事', managementSource: 'NONE', studentNo: body.studentNo || '', phone: body.phone || ''}); return ok(6)}
    if (pathname === '/api/users/status-changes') {
      return ok(body.userIds.map((id: number) => {const p = people.find(p => p.id === id)!; if (state.partialStatus && id === 5) return {id, status: 'FAILED', message: '账号授权已变化，请重新核对'}; p.status = body.status; return {id, status: 'UPDATED', message: '状态已更新'}}))
    }
    if (/\/api\/users\/\d+\/effective-permissions/.test(pathname)) return ok({businessPermissions: ['activity:view', 'kb:read'], managementCapabilities: [], roleId: 3, managementSource: 'NONE'})
    if (pathname === '/api/roles/assignable') return ok(roles)
    if (pathname === '/api/permissions/catalog') return ok([{code: 'activity:view', name: '查看活动'}, {code: 'activity:manage', name: '管理活动'}, {code: 'kb:read', name: '查看知识库'}])
    if (pathname === '/api/users/role-change-previews') return ok({id: 'change-test', details: {oldRoleId: 3, roleId: body.roleId, before: [{permission: 'activity:view', scope: 'ORG'}], grants: [{permission: 'activity:view', scope: 'ORG'}, {permission: 'activity:manage', scope: 'ORG'}]}, expiresInSeconds: 300})
    if (pathname === '/api/orgs/current/authorization-changes/change-test/apply') {state.assigned = true; const p = people.find(p => p.id === 3)!; p.roleId = 2; p.roleName = '组织部长'; return ok({status: 'APPLIED'})}
    if (/^\/api\/users\/\d+$/.test(pathname) && method === 'PUT') {if (state.failProfileOnce) {state.failProfileOnce = false; return fail('账号资料保存失败')}; Object.assign(people.find(p => p.id === Number(pathname.split('/').at(-1))), body); return ok(null)}
    if (/^\/api\/users\/\d+$/.test(pathname) && method === 'DELETE') {people = people.filter(p => p.id !== Number(pathname.split('/').at(-1))); return ok(null)}
    if (pathname === '/api/kb/stats') return ok({entryCount: 2, documentCount: 1, chunkCount: 3, snapshotCount: 1, snapshotLatestAsOf: '2026-10-19T02:00:00', lastIndexedAt: '2026-10-19T09:30:00', fulltextAvailable: true, retrievalTotal: 2, retrievalUnanswered: 1})
    if (pathname === '/api/kb/sources') return ok(sources)
    if (pathname === '/api/kb/entries') return paged(entries.filter(e => (!q.get('sourceType') || e.sourceType === q.get('sourceType')) && (!q.get('keyword') || e.title.includes(q.get('keyword')!)) && (!q.get('tier') || e.tier === q.get('tier'))))
    if (/^\/api\/kb\/chunks\//.test(pathname)) return ok([{...entries[0], seq: 1, content: '第一条 活动结束后归档策划书、签到记录与总结。'}, {...entries[0], id: 112, seq: 2, path: '第二章 复核要求', content: '第二条 负责人核对完整性后提交归档。'}])
    if (pathname === '/api/kb/documents/11') return ok({id: 11, title: entries[0].title, body: '原始正文', docType: 'MANUAL', visibility: 'ALL', status: 'PUBLISHED', requiredPermission: 'material:view', version: 'V1.2'})
    if (pathname === '/api/kb/documents' && method === 'POST') return state.failDocument ? fail('文档暂时无法保存') : ok(21)
    if (pathname === '/api/kb/documents/11' && method === 'PUT') return ok(null)
    if (pathname === '/api/kb/reindex') return ok({totalChunks: 3})
    if (pathname === '/api/kb/retrievals') return paged([{id: 1, queryText: '活动结束后需要哪些材料？', createdAt: '2026-10-19T09:42:00', hitCount: 3, allowedCount: 2, filteredCount: 1, skipReason: null, scanMs: 120, hitsJson: JSON.stringify([{sourceType: 'manual', sourceId: 11, title: entries[0].title, path: '第一章 归档要求'}])}, {id: 2, queryText: '下个月的培训安排确定了吗？', createdAt: '2026-10-19T09:40:00', hitCount: 0, allowedCount: 0, filteredCount: 0, skipReason: 'NO_HIT', scanMs: 88, hitsJson: '[]'}].filter(row => (!q.get('keyword') || row.queryText.includes(q.get('keyword')!)) && (!q.get('skipReason') || row.skipReason === q.get('skipReason') || (!row.skipReason && q.get('skipReason') === 'SUCCESS'))))
    if (pathname === '/api/kb/snapshot-specs') return ok([{code: 'member_composition', titleTemplate: '%s 成员构成', description: '只包含汇总计数，不含个人明细。', visibilityLabel: '全员可见', scopeCount: 1, refreshHint: '每日'}])
    if (pathname === '/api/kb/snapshots') return paged([{id: 1, specCode: 'member_composition', title: '2026 届成员构成', content: '截至 2026-10-19 02:00，本届共有 42 位成员。', asOf: '2026-10-19T02:00:00', generatedAt: '2026-10-19T02:05:00', visibility: 'ALL', refreshHint: '每日', description: '仅汇总计数，不包含个人明细。'}])
    if (method !== 'GET') throw Error('Unexpected mutation ' + method + ' ' + pathname)
    return ok([])
  })
  return {calls, state, browserErrors}
}
async function select(page: Page, name: string, label: string) {const control = page.getByRole('combobox', {name: new RegExp(name + '$')}); await control.focus(); await control.press('ArrowDown'); await page.locator('.ant-select-dropdown:visible').getByText(label, {exact: true}).click()}
const modal = (page: Page) => page.getByRole('dialog').last()

test('knowledge groups sources, opens complete chunks, and preserves a failed document save', async ({page}) => {
  const {calls, state, browserErrors} = await setup(page)
  await page.goto('/knowledge')
  await expect(page.locator('.admin-table tbody tr[data-row-key]')).toHaveCount(2)
  await page.locator('.admin-rail button').filter({hasText: '制度与录入文档'}).click()
  await expect(page.locator('.admin-table tbody tr[data-row-key]')).toHaveCount(1)
  await page.getByRole('button', {name: '查看', exact: true}).click()
  await expect(modal(page)).toContainText('第二条 负责人核对完整性后提交归档。')
  await modal(page).getByRole('button', {name: '编辑文档'}).click()
  await expect(modal(page).getByLabel('正文', {exact: true})).toHaveValue('原始正文')
  await modal(page).getByLabel('文档标题', {exact: true}).fill('更新归档指引')
  await modal(page).getByRole('button', {name: '保存并建立索引'}).click()
  expect(calls.find(c => c.method === 'PUT' && c.path === '/api/kb/documents/11')?.body.requiredPermission).toBe('material:view')
  await page.getByRole('button', {name: '录入文档', exact: true}).click()
  await modal(page).getByLabel('文档标题', {exact: true}).fill('新文档')
  await modal(page).getByLabel('正文', {exact: true}).fill('第一条 请核对真实的来源和权限。')
  state.failDocument = true
  await modal(page).getByRole('button', {name: '保存并建立索引'}).click()
  await expect(modal(page)).toContainText('文档暂时无法保存')
  await expect(modal(page).getByLabel('文档标题', {exact: true})).toHaveValue('新文档')
  state.failDocument = false
  await modal(page).getByRole('button', {name: '保存并建立索引'}).click()
  await expect(page.getByRole('dialog')).toHaveCount(0)
  expect(browserErrors).toEqual([])
})

test('personal retrieval reasons and snapshot cutoff remain visible', async ({page}) => {
  await setup(page); await page.goto('/knowledge')
  await page.getByRole('tab', {name: /检索记录/}).click()
  await page.getByRole('button', {name: '查看详情', exact: true}).last().click()
  await expect(modal(page)).toContainText('当前可见资料中没有命中内容')
  await expect(modal(page)).toContainText('暂无当前可查看的引用')
  await modal(page).getByRole('button', {name: '关闭', exact: true}).click()
  await page.getByRole('tab', {name: '事实快照', exact: true}).click()
  await expect(page.locator('.admin-table')).toContainText('2026-10-19 02:00')
  await page.getByRole('button', {name: '查看快照', exact: true}).click()
  await expect(modal(page)).toContainText('不代表此刻实时数据')
})

test('users route protects identities, filters remotely and retries profile save after role assignment', async ({page}) => {
  const {calls, state, browserErrors} = await setup(page)
  await page.goto('/admin/users?deptId=2')
  await expect(page.getByRole('combobox', {name: '部门筛选'})).toBeVisible()
  await expect(page.locator('.admin-table tbody tr[data-row-key]')).toHaveCount(2)
  await page.getByRole('button', {name: '重置', exact: true}).click()
  await page.getByRole('checkbox', {name: '选择林晓'}).isDisabled().then(value => expect(value).toBe(true))
  await expect(page.getByRole('checkbox', {name: '选择许安'})).toBeDisabled()
  await page.getByRole('textbox', {name: '搜索姓名、用户名或学号'}).fill('20240003')
  await expect(page.locator('.admin-table tbody tr[data-row-key]')).toHaveCount(1)
  await page.getByRole('button', {name: '编辑', exact: true}).click()
  await select(page, '业务主角色', '组织部长')
  await modal(page).getByRole('button', {name: '预览并保存'}).click()
  await expect(modal(page)).toContainText('管理活动')
  state.failProfileOnce = true
  await modal(page).getByRole('button', {name: '确认替换并保存'}).click()
  await expect(modal(page)).toContainText('角色已替换，账号资料尚未保存')
  await modal(page).getByRole('button', {name: '预览并保存'}).click()
  await expect(page.getByRole('dialog')).toHaveCount(0)
  expect(calls.filter(c => c.path.endsWith('/apply'))).toHaveLength(1)
  expect(calls.filter(c => c.path === '/api/users/role-change-previews')).toHaveLength(1)
  expect(browserErrors).toEqual([])
})

test('batch reports partial failures and delete requires the target username', async ({page}) => {
  const {state, calls} = await setup(page); await page.goto('/admin/users')
  await page.getByRole('checkbox', {name: '选择周宁'}).check(); await page.getByRole('checkbox', {name: '选择宋雨'}).check()
  state.partialStatus = true
  await page.getByRole('button', {name: '停用账号', exact: true}).click()
  await modal(page).getByRole('button', {name: '确认停用'}).click()
  await expect(modal(page)).toContainText('失败 1 个')
  await expect(modal(page)).toContainText('账号授权已变化，请重新核对')
  await modal(page).getByRole('button', {name: /^完\s*成$/}).click()
  await page.locator('.admin-table tbody tr').filter({hasText: '宋雨'}).getByRole('button', {name: '查看', exact: true}).click()
  await modal(page).getByRole('button', {name: '删除账号', exact: true}).click()
  await expect(modal(page).getByRole('button', {name: '删除账号', exact: true})).toBeDisabled()
  await modal(page).getByRole('textbox', {name: '输入用户名确认删除'}).fill('disabled')
  await modal(page).getByRole('button', {name: '删除账号', exact: true}).click()
  await expect(page.locator('.admin-table')).not.toContainText('宋雨')
  expect(calls.filter(c => c.method === 'DELETE')).toHaveLength(1)
})

test('creates a user through the real account form with required initial password', async ({page}) => {
  const {calls} = await setup(page); await page.goto('/admin/users')
  await page.getByRole('button', {name: '新增用户', exact: true}).click()
  await modal(page).getByLabel('用户名', {exact: true}).fill('new_account')
  await modal(page).getByLabel('姓名', {exact: true}).fill('新建成员')
  await select(page, '业务主角色', '干事')
  await modal(page).getByLabel('初始密码', {exact: true}).fill('test-password-only')
  await modal(page).getByRole('button', {name: '创建用户', exact: true}).click()
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await expect(page.locator('.admin-table')).toContainText('新建成员')
  expect(calls.find(c => c.method === 'POST' && c.path === '/api/users')?.body).toMatchObject({username: 'new_account', realName: '新建成员', roleId: 3, status: 1})
})

test('settings share theme, guard unsaved density and do not infer disk health', async ({page}) => {
  const {browserErrors} = await setup(page); await page.goto('/admin/settings')
  await expect(page.locator('.admin-running')).toContainText('系统基础健康检查通过')
  await page.getByRole('button', {name: '存储与诊断', exact: true}).click()
  await expect(page.locator('.admin-settings-content')).toContainText('未检测')
  await expect(page.locator('.admin-settings-content')).toContainText('未执行文件读写测试')
  await page.getByRole('button', {name: '使用偏好', exact: true}).click()
  await page.locator('.admin-preference-row').filter({hasText: '界面主题'}).getByText('深色', {exact: true}).click()
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')
  await page.locator('.admin-preference-row').filter({hasText: '用户列表密度'}).getByText('紧凑', {exact: true}).click()
  await page.getByRole('menuitem', {name: '用户与权限'}).click()
  await page.getByRole('menuitem', {name: '用户管理', exact: true}).click()
  await expect(modal(page)).toContainText('离开前保存偏好')
  await modal(page).getByRole('button', {name: '保存并离开'}).click()
  await expect(page).toHaveURL(/\/admin\/users/)
  await expect(page.locator('.admin-data')).toHaveClass(/compact/)
  expect(browserErrors).toEqual([])
})

test('load failure is retryable and read-only users cannot mutate data', async ({page}) => {
  const {state, calls} = await setup(page, {readOnly: true})
  state.failList = true; await page.goto('/admin/users')
  await expect(page.locator('.admin-load-error')).toContainText('账号暂时无法读取')
  state.failList = false; await page.locator('.admin-load-error').getByRole('button', {name: /重\s*试/}).click()
  await expect(page.locator('.admin-table tbody tr[data-row-key]')).toHaveCount(5)
  await expect(page.getByRole('button', {name: '新增用户', exact: true})).toHaveCount(0)
  await expect(page.locator('.admin-table').getByRole('button', {name: '编辑', exact: true})).toHaveCount(0)
  await page.goto('/knowledge'); await expect(page.getByRole('button', {name: '录入文档', exact: true})).toHaveCount(0)
  state.failSystem = true; await page.goto('/admin/settings')
  await expect(page.locator('.admin-running')).toContainText('系统状态需要核对')
  await expect(page.locator('.admin-settings-content')).not.toContainText('系统基础健康检查通过')
  expect(calls.filter(c => c.method !== 'GET')).toEqual([])
})

test('desktop and mobile light/dark views have readable records and no page overflow', async ({page}) => {
  await mkdir(output, {recursive: true})
  const {browserErrors} = await setup(page)
  for (const theme of ['light', 'dark']) for (const [name, route] of [['knowledge', '/knowledge'], ['users', '/admin/users'], ['settings', '/admin/settings']]) {
    await page.setViewportSize({width: 1440, height: 1000}); await page.goto(route)
    // The product theme control uses the same store as settings.
    if (theme === 'dark') await page.getByRole('button', {name: /切换.*深色|切换至深色|深色模式/}).first().click()
    await expect(page.locator('.admin-v6')).toBeVisible()
    await expect(page.locator('.admin-stats .ant-skeleton')).toHaveCount(0)
    await page.mouse.move(500, 70)
    const desktopStyle = await page.addStyleTag({content: '.app-content{overflow:visible!important}.ant-layout{height:auto!important;min-height:100vh}.ant-tooltip{display:none!important}'})
    await page.screenshot({path: path.join(output, `${name}-desktop-${theme}.png`), fullPage: true, animations: 'disabled'}); await desktopStyle.evaluate(el => el.remove())
    await page.setViewportSize({width: 390, height: 844})
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    if (name !== 'settings') await expect(page.locator('.admin-mobile-list')).toBeVisible()
    const mobileStyle = await page.addStyleTag({content: '.app-content{overflow:visible!important}.ant-layout{height:auto!important;min-height:100vh}.ant-tooltip{display:none!important}'})
    await page.screenshot({path: path.join(output, `${name}-mobile-${theme}.png`), fullPage: true, animations: 'disabled'}); await mobileStyle.evaluate(el => el.remove())
  }
  expect(browserErrors).toEqual([])
})
