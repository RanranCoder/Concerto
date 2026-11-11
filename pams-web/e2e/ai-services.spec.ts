import { test, expect, type Page } from '@playwright/test'
import { writeFile } from 'node:fs/promises'

test.use({ channel: process.platform === 'win32' ? 'msedge' : undefined })

async function setup(page: Page, mode = 'light', encryptionReady = true) {
  page.on('pageerror', error => console.error('AI settings browser error:', error.message))
  const user = { id: 1, username: 'teacher', realName: '测试老师', roleCode: 'TEACHER', roleLevel: 5,
    permissions: ['org:manage'], managementCapabilities: [], isOwner: true }
  await page.addInitScript(({ user, mode }) => {
    localStorage.setItem('pams_token', JSON.stringify({ state: { token: 'ai-ui-test', user }, version: 1 }))
    localStorage.setItem('pams_theme', JSON.stringify({ state: { mode }, version: 0 }))
  }, { user, mode })
  const listing = { items: [{ id: 7, version: 0, displayName: '组织助手', protocol: 'openai_responses',
    baseUrl: 'https://ai.example.com/v1', modelId: 'organization-model', summaryEnabled: true,
    hasApiKey: true, testStatus: 'PASSED', testedAt: '2026-10-31T08:00:00Z', testSummary: true, contextWindowTokens: null as number | null }],
    defaultId: null as number | null, defaultVersion: 0, deploymentProtocol: 'openai_chat_completions', deploymentModel: 'deployment-model',
    deploymentAvailable: true, encryptionReady, deploymentContextWindowTokens: 65536 }
  await page.route(url => url.pathname.startsWith('/api/'), async route => {
    const path = new URL(route.request().url()).pathname
    let data: unknown = []
    if (path === '/api/auth/me') data = user
    else if (path === '/api/orgs/current') data = { id: 1, name: '协奏测试组织' }
    else if (path === '/api/system/info') data = { version: '1.0.0', ping: 'pong', uploadDir: 'uploads' }
    else if (path === '/api/notifications/unread-count') data = 0
    else if (path === '/api/agent/providers') {
      if (route.request().method() === 'GET') data = listing
      else data = { id: 9, version: 0, ...route.request().postDataJSON() }
    } else if (path === '/api/agent/providers/test') data = { success: true, message: '普通请求、SSE 及工具往返测试通过', durationMs: 420, summaryAvailable: true, ticket: 'test-receipt' }
    else if (path === '/api/agent/providers/7/activate') { listing.defaultId = 7; listing.defaultVersion++; data = null }
    else if (path === '/api/agent/providers/7' && route.request().method() === 'PUT') {
      const item = listing.items[0]
      item.contextWindowTokens = route.request().postDataJSON().contextWindowTokens
      item.version++
      data = item
    }
    await route.fulfill({ json: { code: 200, data } })
  })
}

test('密钥存储未就绪时仍可填写，测试和保存明确提示并保留内容', async ({ page }) => {
  await setup(page, 'light', false)
  const sensitiveRequests: string[] = []
  page.on('request', request => {
    if (request.method() !== 'GET' && new URL(request.url()).pathname.startsWith('/api/agent/providers')) sensitiveRequests.push(request.url())
  })
  await page.goto('/admin/settings?section=ai')
  await expect(page.locator('.ai-services').getByText('密钥存储尚未就绪', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '新增服务' }).click()
  const drawer = page.getByRole('dialog')
  await drawer.getByLabel('显示名称', { exact: true }).fill('待初始化的服务')
  await drawer.getByLabel('Base URL', { exact: true }).fill('https://ai.example.com/v1')
  await drawer.getByLabel('API Key', { exact: true }).fill('test-key-only')
  await drawer.getByLabel('请求模型 ID', { exact: true }).fill('model-id')
  for (const action of ['测试连接', '保存配置']) {
    await drawer.getByRole('button', { name: action }).click()
    await expect(drawer.getByText('密钥存储自动初始化失败，暂时无法测试或保存。请检查服务端存储状态后重试；当前填写内容已保留。')).toBeVisible()
    await expect(drawer.getByLabel('API Key', { exact: true })).toHaveValue('test-key-only')
    await expect(drawer.getByLabel('显示名称', { exact: true })).toHaveValue('待初始化的服务')
  }
  expect(sensitiveRequests).toEqual([])
})

test('配置接口加载失败时仍可打开新增表单', async ({ page }) => {
  await setup(page)
  await page.route(url => url.pathname === '/api/agent/providers', route => route.fulfill({ status: 503, json: { code: 503, message: '配置服务暂不可用' } }))
  await page.goto('/admin/settings?section=ai')
  await expect(page.locator('.ai-services').getByText('配置服务暂不可用')).toBeVisible()
  await page.getByRole('button', { name: '新增服务' }).click()
  await page.getByRole('dialog').getByLabel('显示名称', { exact: true }).fill('可继续填写')
  await expect(page.getByRole('dialog').getByLabel('显示名称', { exact: true })).toHaveValue('可继续填写')
})

test('新增服务按原生协议测试，改动后测试凭据失效', async ({ page }) => {
  await setup(page); await page.goto('/admin/settings?section=ai')
  await page.getByRole('button', { name: '新增服务' }).click()
  const drawer = page.getByRole('dialog')
  await drawer.getByLabel('显示名称', { exact: true }).fill('新服务')
  await drawer.getByLabel('Base URL', { exact: true }).fill('https://ai.example.com/v1')
  await drawer.getByLabel('API Key', { exact: true }).fill('test-key-only')
  await drawer.getByLabel('请求模型 ID', { exact: true }).fill('new-model')
  await drawer.locator('.ant-form-item').filter({ hasText: '接口协议' }).locator('.ant-select-selector').click()
  await page.locator('.ant-select-item-option').filter({ hasText: 'Responses' }).click()
  await expect(drawer.getByLabel('请求推理摘要', { exact: true })).toBeVisible()
  const testRequest = page.waitForRequest(r => r.url().endsWith('/agent/providers/test'))
  await drawer.getByRole('button', { name: '测试连接', exact: true }).click()
  expect((await testRequest).postDataJSON().protocol).toBe('openai_responses')
  await expect(drawer.getByText('普通请求、SSE 及工具往返测试通过')).toBeVisible()
  await drawer.getByLabel('请求模型 ID', { exact: true }).fill('changed-model')
  await expect(drawer.getByText('普通请求、SSE 及工具往返测试通过')).toHaveCount(0)
  await drawer.locator('.ant-form-item').filter({ hasText: '上下文窗口' }).locator('.ant-select-selector').click()
  await expect(page.locator('.ant-select-dropdown:visible .ant-select-item-option'))
    .toHaveText(['128k', '262k', '500k', '1000k'])
  await page.locator('.ant-select-item-option').filter({ hasText: '1000k' }).click()
  const saveRequest = page.waitForRequest(r => new URL(r.url()).pathname === '/api/agent/providers' && r.method() === 'POST')
  await drawer.getByRole('button', { name: '保存配置' }).click()
  const saved = (await saveRequest).postDataJSON()
  expect(saved.testTicket).toBeUndefined(); expect(saved.modelId).toBe('changed-model')
  expect(saved.contextWindowTokens).toBe(1_000_000)
  await expect(drawer).not.toBeVisible()
})

test('编辑不回填密钥，关闭前处理未保存修改', async ({ page }) => {
  await setup(page); await page.goto('/admin/settings?section=ai')
  await page.getByRole('button', { name: '编辑 / 测试' }).click()
  const drawer = page.getByRole('dialog')
  await expect(drawer.getByLabel('API Key', { exact: true })).toHaveValue('')
  await drawer.getByLabel('显示名称', { exact: true }).fill('修改名称')
  await drawer.getByRole('button', { name: /^取\s*消$/ }).click()
  await expect(page.locator('.ant-modal-confirm-title')).toHaveText('放弃未保存的服务配置？')
  await page.getByRole('button', { name: '继续编辑', exact: true }).click()
  await expect(drawer.getByLabel('显示名称', { exact: true })).toHaveValue('修改名称')
})

test('保存并重新打开 AI 服务时保留所选上下文窗口', async ({ page }) => {
  await setup(page)
  await page.goto('/admin/settings?section=ai')
  const edit = page.getByRole('button', { name: '编辑 / 测试' })
  await edit.click()
  const drawer = page.getByRole('dialog')
  const window = drawer.locator('.ant-form-item').filter({ hasText: '上下文窗口' })
  await window.locator('.ant-select-selector').click()
  await page.locator('.ant-select-item-option').filter({ hasText: '500k' }).click()
  await drawer.getByRole('button', { name: '保存配置' }).click()
  await expect(drawer).not.toBeVisible()
  await edit.click()
  await expect(window.locator('.ant-select-selection-item')).toHaveText('500k')
})

test('Concerto 输入框展示当前配置，旧规则答复不会覆盖模型状态', async ({ page }) => {
  await setup(page)
  let configured = true
  let unavailable = false
  await page.route(url => url.pathname.startsWith('/api/agent/'), async route => {
    const path = new URL(route.request().url()).pathname
    let data: unknown
    if (path === '/api/agent/model-status') {
      if (unavailable) return route.fulfill({ status: 503, json: { code: 503, message: '服务状态暂不可用' } })
      data = { configured, displayName: '组织模型服务', protocol: 'openai_responses', modelId: 'organization-model', source: configured ? 'organization' : 'rules' }
    } else if (path === '/api/agent/conversations') data = { records: [{ id: 31, title: '旧规则会话', status: 'COMPLETED', pendingTaskCount: 0, createdAt: '2026-10-31T17:37:00', updatedAt: '2026-10-31T17:37:00' }], total: 1 }
    else if (path === '/api/agent/conversations/31/messages') data = { records: [
      { id: 1, conversationId: 31, role: 'USER', content: '旧任务' },
      { id: 2, conversationId: 31, role: 'ASSISTANT', content: '这是之前的规则答复。', provider: 'stub', durationMs: 100, toolCalls: [], references: [], artifacts: [], thinking: [], thinkingNotes: [] },
    ], total: 2 }
    else return route.fallback()
    await route.fulfill({ json: { code: 200, data } })
  })
  await page.goto('/agent')
  await page.locator('.lingsi-conv-title').filter({ hasText: '旧规则会话' }).click()
  await expect(page.getByText('这是之前的规则答复。')).toBeVisible()
  const pill = page.locator('.lingsi-composer-dock .lingsi-model-pill')
  await expect(pill).toHaveText('组织模型服务')
  await expect(page.locator('.lingsi-meta-tag')).toContainText('规则模式')
  configured = false
  await page.evaluate(() => window.dispatchEvent(new Event('focus')))
  await expect(pill).toHaveText('规则模式')
  unavailable = true
  await page.evaluate(() => window.dispatchEvent(new Event('focus')))
  await expect(pill).toHaveText('服务状态未确认')
})

for (const mode of ['light', 'dark']) for (const width of [1440, 390]) {
  test(`AI 服务布局 ${mode} ${width}`, async ({ page }) => {
    await setup(page, mode); await page.setViewportSize({ width, height: 960 })
    await page.goto('/admin/settings?section=ai')
    await expect(page.locator('html')).toHaveAttribute('data-theme', mode)
    await expect(page.locator('.ai-service-record').getByText('组织助手', { exact: true })).toBeVisible()
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
    await page.screenshot({ path: `../docs/design/ai-services/settings-${mode}-${width}.png`, fullPage: true, animations: 'disabled' })
    if (mode === 'light' && width === 1440) {
      const snapshot = await page.content()
      await writeFile('../docs/design/ai-services/settings-snapshot.html', snapshot.replace(/<script\b[^>]*>[\s\S]*?<\/script>/gi, '').replace(/(src|href)="\/(?!\/)/g, '$1="http://localhost:5173/'))
    }
    await page.getByRole('button', { name: '编辑 / 测试' }).click()
    await expect(page.getByRole('dialog').getByLabel('Base URL', { exact: true })).toBeVisible()
    await page.screenshot({ path: `../docs/design/ai-services/editor-${mode}-${width}.png`, fullPage: true, animations: 'disabled' })
  })
}
